package com.oculus.camerademo.model3d

import com.oculus.camerademo.ml.FaceEmotionState
import org.junit.Assert.*
import org.junit.Test

class PortraitSurfaceTest {
    private val styles = listOf(
        PortraitStyle(0xffe1af91.toInt()), PortraitStyle(0xffdaa884.toInt(), beard = true),
        PortraitStyle(0xffdcb432.toInt(), metallic = true), PortraitStyle(0xff3d5b77.toInt(), variant = "cyber"),
        PortraitStyle(0xff00ffb4.toInt(), variant = "holo"), PortraitStyle(0xffffb300.toInt(), character = "lion"),
        PortraitStyle(0xff4caf50.toInt(), character = "dragon"), PortraitStyle(0xffffffff.toInt(), character = "unicorn"))

    @Test fun materialSectionsPreserveEveryTriangleAndItsWinding() {
        for (style in styles) for (detail in listOf(12, 24)) {
            val face = PortraitMesh().build(style, detail = detail)
            assertEquals(face.colors.size, face.surfaces.size)
            assertTrue(face.surfaces.all { it in 0 until PortraitSurface.COUNT })
            val sections = PortraitSurface.sections(face.indices, face.surfaces)
            assertEquals(sections.materialIds.size * 2, sections.ranges.size)
            var end = 0
            for (section in sections.materialIds.indices) {
                val start = sections.ranges[section * 2]
                val count = sections.ranges[section * 2 + 1]
                assertEquals(end, start)
                assertTrue(count > 0 && count % 3 == 0)
                for (i in start until start + count) assertEquals(sections.materialIds[section], face.surfaces[sections.indices[i]])
                end = start + count
            }
            assertEquals(face.indices.size, end)
            fun triangles(indices: IntArray) = indices.toList().chunked(3).map { it.joinToString(",") }.sorted()
            assertEquals(triangles(face.indices), triangles(sections.indices))
        }
    }

    @Test fun expressionAndGazeDoNotReassignMaterialSections() {
        for (style in styles) {
            val mesh = PortraitMesh()
            val neutral = mesh.build(style)
            val expression = mesh.build(style, FaceEmotionState(jawOpen = 1f, eyeBlinkLeft = 1f,
                eyeWideRight = 1f, mouthSmileLeft = 1f), gazeX = 1f, gazeY = -1f)
            assertArrayEquals(neutral.surfaces, expression.surfaces)
            val before = PortraitSurface.sections(neutral.indices, neutral.surfaces)
            val after = PortraitSurface.sections(expression.indices, expression.surfaces)
            assertArrayEquals(before.indices, after.indices)
            assertArrayEquals(before.ranges, after.ranges)
            assertArrayEquals(before.materialIds, after.materialIds)
        }
    }

    @Test fun studioLightingHandlesScaledAndInvalidNormalsAndKeepsAccentsLuminous() {
        val color = 0xff6f8c9b.toInt()
        val expected = PortraitSurface.shade(color, -.292f, .348f, .891f, PortraitSurface.SKIN, false)
        assertEquals(expected, PortraitSurface.shade(color, -2.92f, 3.48f, 8.91f, PortraitSurface.SKIN, false))
        assertEquals(color, PortraitSurface.shade(color, 0f, 0f, -1f, PortraitSurface.ACCENT, false))
        assertEquals(PortraitSurface.shade(color, 0f, 0f, 1f, PortraitSurface.SKIN, false),
            PortraitSurface.shade(color, Float.NaN, Float.POSITIVE_INFINITY, 0f, PortraitSurface.SKIN, false))
        val eye = PortraitSurface.shade(color, -.292f, .348f, .891f, PortraitSurface.EYE, false)
        assertTrue("clear eye reflection differs from satin skin", (eye and 255) > (expected and 255) + 30)
    }
}
