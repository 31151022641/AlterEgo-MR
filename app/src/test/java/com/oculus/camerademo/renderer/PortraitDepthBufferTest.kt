package com.oculus.camerademo.renderer

import org.junit.Assert.*
import org.junit.Test

class PortraitDepthBufferTest {
    private val triangle = floatArrayOf(1f, 1f, 1f, 8f, 8f, 1f)
    private val red = 0xffff0000.toInt()
    private val blue = 0xff0000ff.toInt()
    private val background = 0xff213141.toInt()

    @Test fun intersectingSurfacesAreResolvedPerPixelInEitherDrawOrder() {
        fun render(reverse: Boolean): IntArray {
            val buffer = PortraitDepthBuffer().apply { begin(10, 10); pixels.fill(background) }
            fun slope() = buffer.draw(triangle, floatArrayOf(.1f, .9f, .1f), IntArray(3) { red })
            fun flat() = buffer.draw(triangle, FloatArray(3) { .53f }, IntArray(3) { blue })
            if (reverse) { flat(); slope() } else { slope(); flat() }
            return buffer.pixels.copyOf()
        }
        val result = render(false)
        assertArrayEquals(result, render(true))
        assertTrue("flat surface is in front near the top", (result[2 * 10 + 2] and 255) > 240)
        assertTrue("sloped surface is in front near the bottom", ((result[6 * 10 + 1] ushr 16) and 255) > 240)
        assertEquals(background, result[9 * 10 + 9])
    }

    @Test fun frameResetClearsDepthAndBatchesPreserveNearerFragments() {
        val buffer = PortraitDepthBuffer().apply { begin(10, 10); pixels.fill(background) }
        buffer.draw(triangle, FloatArray(3) { 1f }, IntArray(3) { red })
        buffer.draw(triangle, FloatArray(3) { 0f }, IntArray(3) { blue })
        assertTrue(((buffer.pixels[22] ushr 16) and 255) > 240)
        buffer.begin(10, 10)
        buffer.draw(triangle, FloatArray(3) { 0f }, IntArray(3) { blue })
        assertTrue((buffer.pixels[22] and 255) > 240)
    }

    @Test fun clippingCullingAndInvalidCoordinatesCannotCorruptOtherPixels() {
        val buffer = PortraitDepthBuffer().apply { begin(10, 10); pixels.fill(background) }
        buffer.draw(triangle, FloatArray(3), IntArray(3) { red }, intArrayOf(0, 2, 1))
        assertTrue(buffer.pixels.all { it == background })
        buffer.draw(floatArrayOf(Float.NaN, 1f, 1f, 8f, 8f, 1f), FloatArray(3), IntArray(3) { red })
        assertTrue(buffer.pixels.all { it == background })
        buffer.draw(floatArrayOf(-20f, -20f, -20f, 5f, 5f, -20f), FloatArray(3), IntArray(3) { red })
        assertEquals(background, buffer.pixels[99])
        assertNull(buffer.region(floatArrayOf(-10f, -10f, -10f, -5f, -5f, -10f)))
        assertEquals(PortraitDepthBuffer.Region(1, 1, 8, 8), buffer.region(triangle))
    }

    @Test fun vertexColorsInterpolateAndIndexedTrianglesRespectOutputScaling() {
        val buffer = PortraitDepthBuffer().apply { begin(20, 20); pixels.fill(background) }
        buffer.draw(triangle, FloatArray(3), intArrayOf(red, blue, 0xff00ff00.toInt()),
            intArrayOf(0, 1, 2), 2f, 2f)
        val center = buffer.pixels[5 * 20 + 5]
        assertTrue(((center ushr 16) and 255) > 30)
        assertTrue(((center ushr 8) and 255) > 30)
        assertTrue((center and 255) > 30)
        assertEquals(background, buffer.pixels[19 * 20 + 19])
    }
}
