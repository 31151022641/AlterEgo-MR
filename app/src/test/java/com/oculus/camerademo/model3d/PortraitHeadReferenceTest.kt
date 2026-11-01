package com.oculus.camerademo.model3d

import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PortraitHeadReferenceTest {
    @After fun clearSource() = AuthoredPortraitStore.clear()

    private fun mesh(positions: FloatArray, surfaces: IntArray, indices: IntArray = IntArray(positions.size / 3) { it }) =
        PortraitGeometry(positions, FloatArray(positions.size), IntArray(positions.size / 3) { -1 }, indices, surfaces)

    @Test fun hairAndHornsDoNotEnlargeTheFittedFace() {
        val geometry = mesh(floatArrayOf(-.1f, -.1f, .03f, .1f, .14f, .02f, 0f, .55f, .2f,
            0f, .9f, .1f), intArrayOf(PortraitSurface.SKIN, PortraitSurface.SKIN, PortraitSurface.HAIR, PortraitSurface.HORN))
        assertArrayEquals(floatArrayOf(-.1f, -.1f, .03f, .1f, .14f, .02f),
            PortraitHeadReference.skinPositions(geometry), 0f)
    }

    @Test fun referenceUsesOnlyFiniteVerticesThatCanActuallyBeRendered() {
        val geometry = mesh(floatArrayOf(-.1f, -.1f, .03f, .1f, .14f, .02f, Float.NaN, .2f, .1f,
            20f, 20f, 20f), IntArray(4), intArrayOf(0, 1, 2, -1, 99))
        assertArrayEquals(floatArrayOf(-.1f, -.1f, .03f, .1f, .14f, .02f),
            PortraitHeadReference.skinPositions(geometry), 0f)
    }

    @Test fun unclassifiedOrNonSkinModelsFallBackToTheirFiniteRenderedGeometry() {
        val positions = floatArrayOf(-.1f, -.1f, 0f, .1f, .1f, 0f, 0f, .05f, .2f)
        assertArrayEquals(positions, PortraitHeadReference.skinPositions(mesh(positions,
            IntArray(3) { PortraitSurface.HORN })), 0f)
        assertArrayEquals(positions, PortraitHeadReference.skinPositions(mesh(positions, IntArray(0))), 0f)
    }

    @Test fun invalidAndUnrenderedGeometryFailsClosed() {
        assertTrue(PortraitHeadReference.skinPositions(mesh(floatArrayOf(Float.NaN, 0f, 0f,
            0f, Float.POSITIVE_INFINITY, 0f), IntArray(2))).isEmpty())
        assertTrue(PortraitHeadReference.skinPositions(mesh(floatArrayOf(-.1f, -.1f, 0f,
            .1f, .1f, 0f), IntArray(2), IntArray(0))).isEmpty())
        assertTrue(PortraitHeadReference.skinPositions(mesh(FloatArray(0), IntArray(0))).isEmpty())
    }

    @Test fun referenceOwnsItsCoordinatesAndCannotChangeWithLaterMeshMutation() {
        val geometry = mesh(floatArrayOf(-.1f, -.1f, 0f, .1f, .14f, 0f, 0f, .1f, .03f), IntArray(3))
        val reference = PortraitHeadReference.skinPositions(geometry)
        geometry.positions[1] = -.8f
        assertEquals(-.1f, reference[1], 0f)
        reference[0] = -20f
        assertEquals(-.1f, geometry.positions[0], 0f)
    }

    @Test fun actualRainAndSnowFitTheirAuthoredFaceRatherThanTheOldThirtyOneCentimetreTemplate() {
        val assets = listOf(File("src/main/assets"), File("app/src/main/assets"))
            .firstOrNull { File(it, "portraits/rain.aeph").isFile } ?: error("Packaged Rain head missing")
        assertTrue("Packaged Snow head missing", File(assets, "portraits/snow.aeph").isFile)
        AuthoredPortraitStore.installDirectory(assets.path)
        val portrait = PortraitMesh()
        for (style in listOf(PortraitStyle(0xffe1af91.toInt()), PortraitStyle(0xffdaa884.toInt(), variant = "bearded"))) {
            val geometry = portrait.build(style)
            assertTrue("Reference must use the authored model", geometry.colors.size > 10_000)
            val reference = PortraitHeadReference.skinPositions(geometry)
            val y = reference.indices.step(3).map { reference[it + 1] }
            val faceHeight = y.maxOrNull()!! - y.minOrNull()!!
            assertTrue("${style.variant} face height $faceHeight", faceHeight in .23f.. .25f)
            assertTrue("Old template underfilled the actual detected face", faceHeight < .31f * .82f)
            val before = reference.copyOf()
            portrait.build(style, com.oculus.camerademo.ml.FaceEmotionState(jawOpen = 1f))
            assertArrayEquals("Cached neutral reference survives animated builds", before, reference, 0f)
        }
    }
}
