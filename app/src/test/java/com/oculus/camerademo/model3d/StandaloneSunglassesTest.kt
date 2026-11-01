package com.oculus.camerademo.model3d

import com.oculus.camerademo.ml.FaceEmotionState
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StandaloneSunglassesTest {
    @Before fun installAuthoredFaces() {
        val root = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .first { File(it, "portraits/rain.aeph").isFile }
        AuthoredPortraitStore.installDirectory(root.path)
    }
    @After fun clearAuthoredFaces() { AuthoredPortraitStore.clear() }

    @Test fun selectedSunglassesContainOnlyEyewearAndNoReplacementHead() {
        val actual = PortraitMesh().build(PortraitStyle(0xff111b25.toInt(), character = "sunglasses"))
        val standalone = PortraitSunglasses.standalone()
        assertArrayEquals(standalone.positions, actual.positions, 0f)
        assertArrayEquals(standalone.indices, actual.indices)
        assertTrue(actual.surfaces.any { it == PortraitSurface.EYE })
        assertTrue(actual.surfaces.all { it in listOf(PortraitSurface.EYE, PortraitSurface.HORN, PortraitSurface.ACCENT) })
        assertTrue("No scalp, face or body", actual.colors.size < 2000)
        val minY = (1 until actual.positions.size step 3).minOf { actual.positions[it] }
        val maxY = (1 until actual.positions.size step 3).maxOf { actual.positions[it] }
        assertTrue("Eyewear height should not include a head", maxY - minY < .12f)
    }

    @Test fun standaloneEyewearKeepsValidSolidGeometryAndIndependentBuffers() {
        val geometry = PortraitSunglasses.standalone()
        assertTrue(geometry.indices.size / 3 in 100..2999)
        assertEquals(geometry.colors.size * 3, geometry.positions.size)
        assertEquals(geometry.positions.size, geometry.normals.size)
        assertEquals(geometry.colors.size, geometry.surfaces.size)
        assertTrue(geometry.positions.all { it.isFinite() && abs(it) < .3f })
        assertTrue(geometry.indices.all { it in geometry.colors.indices })
        for (at in geometry.normals.indices step 3) {
            val length = sqrt(geometry.normals[at] * geometry.normals[at] +
                geometry.normals[at + 1] * geometry.normals[at + 1] + geometry.normals[at + 2] * geometry.normals[at + 2])
            assertEquals(1f, length, .002f)
        }
        val expected = geometry.positions.copyOf()
        java.util.Arrays.fill(geometry.positions, 999f)
        assertArrayEquals(expected, PortraitSunglasses.standalone().positions, 0f)
    }

    @Test fun expressionHairAndGazeDoNotChangeStandaloneSunglasses() {
        val style = PortraitStyle(0xff111b25.toInt(), character = "sunglasses")
        val mesh = PortraitMesh()
        val neutral = mesh.build(style)
        val animated = mesh.build(style, FaceEmotionState(jawOpen = 1f, eyeBlinkLeft = 1f,
            mouthSmileRight = 1f), gazeX = 1f, gazeY = -1f, hairEnabled = false, detail = 12)
        assertArrayEquals(neutral.positions, animated.positions, 0f)
        assertArrayEquals(neutral.indices, animated.indices)
        assertArrayEquals(neutral.colors, animated.colors)
    }

    @Test fun originalHumanAndCreaturePortraitsDoNotAutomaticallyGainSunglasses() {
        val styles = listOf(PortraitStyle(0xffe1af91.toInt()),
            PortraitStyle(0xffdaa884.toInt(), beard = true, variant = "bearded"),
            PortraitStyle(0xffffffff.toInt(), character = "unicorn"),
            PortraitStyle(0xffffb300.toInt(), character = "lion"),
            PortraitStyle(0xff4caf50.toInt(), character = "dragon"))
        for (style in styles) {
            val expression = FaceEmotionState(jawOpen = .3f, mouthSmileLeft = .4f)
            val original = if (style.character == "human")
                AuthoredPortraitStore.build(style, expression, 0f, 0f, true)!!
            else CartoonCreatureMesh.build(style, expression, 0f, 0f, true, 20)
            val selected = PortraitMesh().build(style, expression, detail = 20)
            assertArrayEquals(original.positions, selected.positions, 0f)
            assertArrayEquals(original.normals, selected.normals, 0f)
            assertArrayEquals(original.colors, selected.colors)
            assertArrayEquals(original.indices, selected.indices)
            assertArrayEquals(original.surfaces, selected.surfaces)
        }
    }
}
