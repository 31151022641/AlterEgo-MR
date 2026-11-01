package com.oculus.camerademo.model3d

import com.oculus.camerademo.ml.FaceEmotionState
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CartoonCreatureMeshTest {
    private val mesh = PortraitMesh()
    private val styles = listOf(
        PortraitStyle(0xffffb300.toInt(), character = "lion"),
        PortraitStyle(0xff4caf50.toInt(), character = "dragon"),
        PortraitStyle(0xffffffff.toInt(), character = "unicorn"))

    @Test fun eachCreatureKeepsValidBoundedGeometryUnderExtremeTrackingInputs() {
        val expression = FaceEmotionState(jawOpen = 1f, eyeBlinkLeft = 1f, eyeWideRight = 50f,
            mouthPucker = Float.NaN, mouthSmileLeft = Float.POSITIVE_INFINITY, browInnerUp = 1f)
        for (style in styles) {
            val face = mesh.build(style, expression, Float.NaN, 500f, detail = Int.MAX_VALUE)
            assertTrue(style.character, face.positions.all { it.isFinite() && abs(it) < .32f })
            assertTrue(style.character, face.colors.size < 12000)
            assertTrue(face.indices.all { it in face.colors.indices })
            for (at in face.normals.indices step 3) {
                val length = sqrt(face.normals[at] * face.normals[at] + face.normals[at + 1] * face.normals[at + 1] +
                    face.normals[at + 2] * face.normals[at + 2])
                assertEquals(style.character, 1f, length, .003f)
            }
        }
    }

    @Test fun primaryColorCustomizationChangesTheCoatWithoutChangingTheRig() {
        val selectedColor = 0xff8054cf.toInt()
        for (style in styles) {
            val original = mesh.build(style)
            val custom = mesh.build(style.copy(skin = selectedColor))
            assertArrayEquals(original.positions, custom.positions, 0f)
            assertArrayEquals(original.indices, custom.indices)
            assertFalse(style.character, original.colors.contentEquals(custom.colors))
            assertTrue(style.character, custom.colors.any { it == selectedColor })
        }
    }

    @Test fun anatomicalBlinkClosesOnlyTheRequestedCreatureEye() {
        for (style in styles) {
            val neutral = mesh.build(style)
            val blink = mesh.build(style, FaceEmotionState(eyeBlinkLeft = 1f))
            assertArrayEquals(neutral.indices, blink.indices)
            fun eye(positive: Boolean) = neutral.colors.indices.filter {
                neutral.colors[it] == 0xfffffbf0.toInt() && (neutral.positions[it * 3] > 0f) == positive
            }
            val left = eye(true); val right = eye(false)
            assertTrue(left.isNotEmpty() && right.isNotEmpty())
            fun height(face: PortraitGeometry, ids: List<Int>) = ids.maxOf { face.positions[it * 3 + 1] } -
                ids.minOf { face.positions[it * 3 + 1] }
            assertTrue(style.character, height(blink, left) < height(neutral, left) * .3f)
            right.forEach { i -> for (axis in 0..2) assertEquals(style.character,
                neutral.positions[i * 3 + axis], blink.positions[i * 3 + axis], .000001f) }
        }
    }

    @Test fun animalMuzzlesProjectWellBeyondTheEyesWithoutManes() {
        for (style in styles) {
            val face = mesh.build(style, hairEnabled = false)
            val eyes = face.colors.indices.filter { face.colors[it] == 0xfffffbf0.toInt() }
            val eyeDepth = eyes.maxOf { face.positions[it * 3 + 2] }
            val snout = face.colors.indices.filter { face.positions[it * 3 + 1] < -.025f }
            val noseDepth = snout.maxOf { face.positions[it * 3 + 2] }
            assertTrue("${style.character} has a projecting animal muzzle", noseDepth > eyeDepth + .060f)
            assertTrue("${style.character} is not a shallow human mask", noseDepth > .140f)
        }
    }

    @Test fun unicornHasLongHorseBridgeAndDragonHasBroadSnoutAndSlitPupils() {
        val horse = mesh.build(styles[2], hairEnabled = false)
        val horseEyes = horse.colors.indices.filter { horse.colors[it] == 0xfffffbf0.toInt() }
        val eyeY = horseEyes.map { horse.positions[it * 3 + 1] }.average().toFloat()
        val horseNose = horse.colors.indices.filter { horse.positions[it * 3 + 2] > .175f }
        assertTrue(horseNose.isNotEmpty())
        assertTrue("long nasal bridge", horseNose.all { horse.positions[it * 3 + 1] < eyeY - .125f })

        val dragon = mesh.build(styles[1], hairEnabled = false)
        val broadSnout = dragon.colors.indices.filter {
            dragon.positions[it * 3 + 1] in -.09f..-.04f && dragon.positions[it * 3 + 2] > .080f
        }
        assertTrue("broad reptilian muzzle", broadSnout.maxOf { abs(dragon.positions[it * 3]) } > .090f)
        val pupil = dragon.colors.indices.filter { dragon.colors[it] == 0xff172b35.toInt() && dragon.positions[it * 3] > 0f }
        val width = pupil.maxOf { dragon.positions[it * 3] } - pupil.minOf { dragon.positions[it * 3] }
        val height = pupil.maxOf { dragon.positions[it * 3 + 1] } - pupil.minOf { dragon.positions[it * 3 + 1] }
        assertTrue("vertical reptilian pupil", height > width * 1.8f)
        assertTrue("pointed lateral frills", dragon.positions.filterIndexed { i, _ -> i % 3 == 0 }.maxOf { abs(it) } > .168f)
    }

    @Test fun creatureSpeechMovesJawWithoutDraggingEyesOrChangingTopology() {
        for (style in styles) {
            val neutral = mesh.build(style)
            val speaking = mesh.build(style, FaceEmotionState(jawOpen = 1f))
            assertArrayEquals(neutral.indices, speaking.indices)
            // The lion's mane extends below its jaw and must remain stationary.
            val largestJawDrop = neutral.colors.indices.maxOf { neutral.positions[it * 3 + 1] - speaking.positions[it * 3 + 1] }
            assertTrue(style.character, largestJawDrop > .024f)
            neutral.colors.indices.filter { neutral.colors[it] == 0xfffffbf0.toInt() }.forEach { i ->
                for (axis in 0..2) assertEquals(style.character, neutral.positions[i * 3 + axis], speaking.positions[i * 3 + axis], .00001f)
            }
        }
    }

    @Test fun extremeGazeStaysWithinEachAnimalEye() {
        for (style in styles) for (gaze in listOf(-1f, 1f)) {
            val face = mesh.build(style, gazeX = gaze, gazeY = gaze)
            for (positive in listOf(false, true)) {
                val eye = face.colors.indices.filter { face.colors[it] == 0xfffffbf0.toInt() && (face.positions[it * 3] > 0f) == positive }
                val pupil = face.colors.indices.filter { face.colors[it] == 0xff172b35.toInt() && (face.positions[it * 3] > 0f) == positive }
                for (axis in 0..1) {
                    val low = eye.minOf { face.positions[it * 3 + axis] } - .00001f
                    val high = eye.maxOf { face.positions[it * 3 + axis] } + .00001f
                    assertTrue("${style.character} gaze axis $axis", pupil.all { face.positions[it * 3 + axis] in low..high })
                }
            }
        }
    }
}
