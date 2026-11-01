package com.oculus.camerademo.mirror

import com.oculus.camerademo.renderer.PortraitDepthBuffer
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

class PortraitLayerBatchesTest {
    private fun coordinates(count: Int) = FloatArray(count * 6) { at ->
        when (at % 6) { 3, 4 -> 1f; else -> 0f }
    }

    @Test fun bodyDepthTiesAndPortraitIndexOrderMatchThePreviousStableLayerSort() {
        val z = floatArrayOf(.5f, -.5f, -1f, 1f, -.75f).flatMap { listOf(it, it, it) }.toFloatArray()
        val batches = PortraitLayerBatches.create(coordinates(5), z, IntArray(15) { it }, floatArrayOf(-.5f, .5f))
        assertArrayEquals(intArrayOf(6, 7, 8, 12, 13, 14), batches[0])
        assertArrayEquals(intArrayOf(3, 4, 5), batches[1])
        assertArrayEquals(intArrayOf(0, 1, 2, 9, 10, 11), batches[2])
    }

    @Test fun backfacesAndInvalidProjectionsAreCulledBeforeBatchAllocation() {
        val points = coordinates(4)
        points[6 + 3] = 0f; points[6 + 2] = 1f; points[6 + 4] = 0f; points[6 + 5] = 1f
        points[12] = Float.NaN
        val z = FloatArray(12)
        z[9] = Float.POSITIVE_INFINITY
        val batches = PortraitLayerBatches.create(points, z, IntArray(12) { it }, floatArrayOf(0f))
        assertTrue(batches[0].isEmpty())
        assertArrayEquals(intArrayOf(0, 1, 2), batches[1])
    }

    @Test fun noBodyLayersKeepAllVisibleTrianglesInOneIndexedBatch() {
        val batches = PortraitLayerBatches.create(coordinates(2), FloatArray(6), IntArray(6) { it }, FloatArray(0))
        assertEquals(1, batches.size)
        assertArrayEquals(IntArray(6) { it }, batches[0])
    }

    @Test fun indexedBatchesPreservePixelsWhenPortraitTrianglesCrossTrackedBodyLayers() {
        val random = Random(73425)
        val points = FloatArray(48 * 6)
        val z = FloatArray(48 * 3)
        val colors = IntArray(z.size)
        for (triangle in 0 until 48) {
            val x = random.nextInt(40).toFloat(); val y = random.nextInt(40).toFloat()
            val at = triangle * 6
            points[at] = x; points[at + 1] = y
            points[at + 2] = x; points[at + 3] = y + 8f + random.nextInt(16)
            points[at + 4] = x + 8f + random.nextInt(16); points[at + 5] = y
            for (corner in 0..2) {
                z[triangle * 3 + corner] = random.nextFloat() * 2f - 1f
                colors[triangle * 3 + corner] = 0xff000000.toInt() or random.nextInt(0xffffff)
            }
        }
        val layers = floatArrayOf(-.4f, 0f, .4f)
        fun buffer() = PortraitDepthBuffer().apply { begin(64, 64); pixels.fill(0xff121a22.toInt()) }
        fun body(buffer: PortraitDepthBuffer, layer: Int) {
            for (y in 12 + layer * 6 until 35 + layer * 6) for (x in 10 until 43)
                buffer.pixels[y * 64 + x] = 0xff4d7b96.toInt() + layer * 0x181600
        }
        val before = buffer()
        val events = layers.mapIndexed { index, depth -> depth to (-index - 1) }.toMutableList()
        for (triangle in 0 until 48) events += ((z[triangle * 3] + z[triangle * 3 + 1] + z[triangle * 3 + 2]) / 3f) to triangle
        for ((_, event) in events.sortedBy { it.first }) {
            if (event < 0) body(before, -event - 1)
            else before.draw(points, z, colors, intArrayOf(event * 3, event * 3 + 1, event * 3 + 2))
        }
        val after = buffer()
        val batches = PortraitLayerBatches.create(points, z, IntArray(z.size) { it }, layers)
        for (layer in batches.indices) {
            after.draw(points, z, colors, batches[layer])
            if (layer < layers.size) body(after, layer)
        }
        assertArrayEquals(before.pixels, after.pixels)
    }
}
