package com.oculus.camerademo.model3d

import com.oculus.camerademo.ml.FaceEmotionState
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

class PortraitMeshTest {
    private val mesh = PortraitMesh()
    private val style = PortraitStyle(0xffdfaf91.toInt())

    @Test fun renderBuffersAreFiniteNormalizedAndIndexable() {
        val face = mesh.build(style.copy(beard = true), detail = 20)
        assertEquals(face.colors.size * 3, face.positions.size)
        assertEquals(face.positions.size, face.normals.size)
        assertEquals(0, face.indices.size % 3)
        assertTrue(face.indices.all { it in face.colors.indices })
        assertTrue(face.positions.all { it.isFinite() })
        for (i in face.colors.indices) {
            val j = i * 3
            val length = sqrt(face.normals[j] * face.normals[j] + face.normals[j + 1] * face.normals[j + 1] + face.normals[j + 2] * face.normals[j + 2])
            assertEquals("normal $i", 1f, length, .002f)
        }
        assertTrue(face.colors.all { it ushr 24 == 255 })
    }

    @Test fun anatomicalLeftBlinkClosesOnlyPositiveXEye() {
        val neutral = mesh.build(style, hairEnabled = false)
        val blink = mesh.build(style, FaceEmotionState(eyeBlinkLeft = 1f), hairEnabled = false)
        assertArrayEquals(neutral.indices, blink.indices)
        val positiveEye = neutral.colors.indices.filter { neutral.colors[it] == 0xfff7f0e5.toInt() && neutral.positions[it * 3] > 0f }
        val negativeEye = neutral.colors.indices.filter { neutral.colors[it] == 0xfff7f0e5.toInt() && neutral.positions[it * 3] < 0f }
        fun height(face: PortraitGeometry, ids: List<Int>) = ids.maxOf { face.positions[it * 3 + 1] } - ids.minOf { face.positions[it * 3 + 1] }
        assertTrue(positiveEye.isNotEmpty() && negativeEye.isNotEmpty())
        assertTrue(height(blink, positiveEye) < height(neutral, positiveEye) * .25f)
        assertEquals(height(neutral, negativeEye), height(blink, negativeEye), .00001f)
        negativeEye.forEach { i -> for (axis in 0..2) assertEquals(neutral.positions[i * 3 + axis], blink.positions[i * 3 + axis], .000001f) }
    }

    @Test fun jawChangesChinWithoutMovingTheForehead() {
        val neutral = mesh.build(style, hairEnabled = false)
        val speaking = mesh.build(style, FaceEmotionState(jawOpen = 1f), hairEnabled = false)
        val chin = neutral.colors.indices.minBy { neutral.positions[it * 3 + 1] }
        assertTrue(speaking.positions[chin * 3 + 1] < neutral.positions[chin * 3 + 1] - .025f)
        val forehead = neutral.colors.indices.filter { neutral.positions[it * 3 + 1] > .105f }
        assertTrue(forehead.isNotEmpty())
        forehead.forEach { i -> for (axis in 0..2) assertEquals(neutral.positions[i * 3 + axis], speaking.positions[i * 3 + axis], .000001f) }
    }

    @Test fun invalidTrackingWeightsCannotPoisonTheMesh() {
        val bad = FaceEmotionState(jawOpen = Float.NaN, eyeBlinkLeft = Float.POSITIVE_INFINITY,
            eyeBlinkRight = -80f, eyeWideRight = 99f, mouthSmileLeft = Float.NaN,
            mouthFrownRight = Float.NEGATIVE_INFINITY, mouthPucker = 42f,
            browInnerUp = Float.NaN, browOuterUpLeft = Float.POSITIVE_INFINITY,
            cheekPuff = Float.NaN)
        val face = mesh.build(style, bad, Float.NaN, Float.POSITIVE_INFINITY, detail = Int.MAX_VALUE)
        assertTrue(face.positions.all { it.isFinite() && abs(it) < .30f })
        assertTrue(face.normals.all { it.isFinite() })
        assertTrue(face.colors.size < 12000)
    }

    @Test fun workloadIsBoundedAndFacialDetailSurvivesLowQuality() {
        val small = mesh.build(style, detail = Int.MIN_VALUE)
        val medium = mesh.build(style, detail = 20)
        val large = mesh.build(style, detail = Int.MAX_VALUE)
        assertTrue(small.colors.size > 2000)
        assertTrue(small.colors.size < medium.colors.size)
        assertTrue(medium.colors.size < large.colors.size)
        assertTrue(large.colors.size < 12000)
        assertTrue(large.indices.size < 72000)
        assertTrue(small.colors.any { it == 0xff211a17.toInt() })
    }

    @Test fun expressionsHaveStableTopologyAndGazeStaysInsideLids() {
        val neutral = mesh.build(style)
        val expression = mesh.build(style, FaceEmotionState(jawOpen = 1f, eyeBlinkRight = .8f,
            mouthSmileLeft = 1f, mouthSmileRight = .4f, mouthFunnel = 1f), gazeX = 1000f, gazeY = -1000f)
        assertArrayEquals(neutral.indices, expression.indices)
        assertEquals(neutral.positions.size, expression.positions.size)
        expression.colors.indices.filter { expression.colors[it] == 0xff211a17.toInt() }.forEach { i ->
            val x = expression.positions[i * 3]
            val y = expression.positions[i * 3 + 1]
            assertTrue(abs(abs(x) - .047f) < .027f)
            assertTrue(y in .002f.. .058f)
        }
    }

    @Test fun lipsTeethAndEyelashSurfacesFaceTheCameraWhenCulled() {
        val face = mesh.build(style, FaceEmotionState(jawOpen = .85f), hairEnabled = false)
        fun mix(a: Int, b: Int, t: Float): Int {
            fun c(shift: Int) = (((a ushr shift) and 255) * (1f - t) + ((b ushr shift) and 255) * t).roundToInt()
            return (0xff shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }
        val materials = mapOf("lower lip" to mix(mix(style.lips, style.skin, .23f), style.skin, .12f),
            "teeth" to 0xfff2e8d6.toInt(), "eyelash" to mix(style.hair, style.skin, .18f))
        materials.forEach { (label, color) ->
            var front = 0
            var back = 0
            face.indices.toList().chunked(3).forEach { triangle ->
                if (triangle.all { face.colors[it] == color }) {
                    val a = triangle[0] * 3; val b = triangle[1] * 3; val c = triangle[2] * 3
                    val crossZ = (face.positions[b] - face.positions[a]) * (face.positions[c + 1] - face.positions[a + 1]) -
                        (face.positions[b + 1] - face.positions[a + 1]) * (face.positions[c] - face.positions[a])
                    if (crossZ > 1e-11f) front++ else if (crossZ < -1e-11f) back++
                }
            }
            assertTrue("$label has visible triangles", front + back > 0)
            assertTrue("$label must survive backface culling ($front front, $back back)", front > 0 && back == 0)
        }
    }

    @Test fun cartoonFaceHasSoftTaperedProportionsAndRelaxedAlmondEyes() {
        val face = mesh.build(style, hairEnabled = false)
        val xs = face.positions.filterIndexed { index, _ -> index % 3 == 0 }
        val ys = face.positions.filterIndexed { index, _ -> index % 3 == 1 }
        val width = xs.max() - xs.min()
        val height = ys.max() - ys.min()
        assertTrue("compact, broad face", height / width in .94f..1.10f)
        val eyes = face.colors.indices.filter { face.colors[it] == 0xfff7f0e5.toInt() }
        val top = eyes.maxOf { face.positions[it * 3 + 1] }
        val bottom = eyes.minOf { face.positions[it * 3 + 1] }
        assertTrue("relaxed almond eye aperture", top - bottom in .032f.. .038f)
        assertTrue("eyes leave a short midface", (top + bottom) / 2f in .019f.. .029f)
        assertTrue("warm layered iris", face.colors.any { it == 0xff795433.toInt() })
    }

    @Test fun hairAndShortBeardHaveSculptedVolume() {
        val bare = mesh.build(style, hairEnabled = false)
        val quiff = mesh.build(style)
        val bearded = mesh.build(style.copy(beard = true), hairEnabled = false)
        fun top(face: PortraitGeometry) = face.colors.indices.maxOf { face.positions[it * 3 + 1] }
        assertTrue("quiff changes silhouette", top(quiff) > top(bare) + .015f)
        assertTrue(bearded.colors.size > bare.colors.size)
        // The appended beard is a short chin/jaw contour and a small moustache.
        // It must not cover half of the face up to the eye sockets.
        val beardVertices = bare.colors.size until bearded.colors.size
        val highestBeard = beardVertices.maxOf { bearded.positions[it * 3 + 1] }
        assertTrue("beard stays below the upper cheek ($highestBeard)", highestBeard <= -.014f + .000001f)
        assertTrue(beardVertices.any { bearded.positions[it * 3 + 2] > .060f })
    }

    @Test fun roundedNoseProjectsFromTheFaceAndTheChinHasARoundedSilhouette() {
        val face = mesh.build(style, hairEnabled = false, detail = 24)
        val nose = face.colors.indices.filter {
            face.surfaces[it] == PortraitSurface.SKIN && abs(face.positions[it * 3]) < .020f &&
                face.positions[it * 3 + 1] in -.024f.. .003f
        }
        val eyes = face.colors.indices.filter { face.colors[it] == 0xfff7f0e5.toInt() }
        assertTrue("nose has a sculpted profile", nose.maxOf { face.positions[it * 3 + 2] } >
            eyes.maxOf { face.positions[it * 3 + 2] } + .020f)
        val chin = face.colors.indices.filter {
            face.surfaces[it] == PortraitSurface.SKIN && face.positions[it * 3 + 1] in -.115f..-.108f
        }
        val width = chin.maxOf { face.positions[it * 3] } - chin.minOf { face.positions[it * 3] }
        assertTrue("rounded chin retains volume near its base", width > .040f)
    }

    @Test fun cyberAndHoloHaveDistinctGeometryAndLuminousInlays() {
        val classic = mesh.build(style)
        val cyber = mesh.build(style.copy(variant = "cyber"))
        val holo = mesh.build(style.copy(variant = "holo"))
        assertFalse(classic.positions.contentEquals(cyber.positions))
        assertFalse(classic.positions.contentEquals(holo.positions))
        assertTrue(cyber.colors.any { it == 0xff65e9ee.toInt() })
        assertTrue(holo.colors.any { it == 0xffaffff0.toInt() })
        assertTrue(cyber.positions.all { it.isFinite() })
        assertTrue(holo.positions.all { it.isFinite() })
    }

    @Test fun hairstylesStayRigidWhileTheFaceTalksAndCachedHairCannotBeMutatedThroughAPortrait() {
        val bare = mesh.build(style, hairEnabled = false)
        val neutral = mesh.build(style)
        val speaking = mesh.build(style, FaceEmotionState(jawOpen = 1f, mouthSmileLeft = 1f, mouthSmileRight = 1f))
        val hairStart = bare.colors.size * 3
        val expected = neutral.positions.copyOfRange(hairStart, neutral.positions.size)
        assertTrue(expected.isNotEmpty())
        assertArrayEquals(expected, speaking.positions.copyOfRange(hairStart, speaking.positions.size), 0f)
        neutral.positions[hairStart] = 100f
        val fresh = mesh.build(style)
        assertArrayEquals(expected, fresh.positions.copyOfRange(hairStart, fresh.positions.size), 0f)
    }

    @Test fun eachHumanHairstyleHasValidDistinctBoundedGeometry() {
        val shapes = listOf("classic", "bearded", "gold", "cyber", "holo").map { kind ->
            val hair = PortraitHairMesh.build(style.hair, kind, 24)
            assertTrue(kind, hair.positions.all { it.isFinite() && abs(it) < .22f })
            assertTrue(kind, hair.colors.size < 5000)
            assertTrue(kind, hair.indices.all { it in hair.colors.indices })
            for (at in hair.normals.indices step 3) {
                val length = sqrt(hair.normals[at] * hair.normals[at] + hair.normals[at + 1] * hair.normals[at + 1] + hair.normals[at + 2] * hair.normals[at + 2])
                assertEquals(kind, 1f, length, .003f)
            }
            hair.positions.contentHashCode()
        }
        assertEquals(5, shapes.toSet().size)
    }
}
