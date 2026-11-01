package com.oculus.camerademo.renderer

import kotlin.math.ceil
import kotlin.math.floor

/** Opaque portrait triangles need per-pixel depth, even when their average depths overlap. */
class PortraitDepthBuffer {
    data class Region(val left: Int, val top: Int, val width: Int, val height: Int)

    var width = 0; private set
    var height = 0; private set
    var pixels = IntArray(0); private set
    private var depths = FloatArray(0)

    fun begin(width: Int, height: Int) {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
        this.width = width; this.height = height
        val count = width * height
        if (pixels.size < count) {
            pixels = IntArray(count)
            depths = FloatArray(count)
        }
        depths.fill(Float.NEGATIVE_INFINITY, 0, count)
    }

    fun region(coordinates: FloatArray, scaleX: Float = 1f, scaleY: Float = 1f): Region? {
        if (coordinates.size < 6 || width == 0 || height == 0) return null
        var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (at in 0 until coordinates.size - 1 step 2) {
            val x = coordinates[at] * scaleX; val y = coordinates[at + 1] * scaleY
            if (!x.isFinite() || !y.isFinite()) continue
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        if (minX > maxX || minY > maxY || maxX < 0f || maxY < 0f || minX >= width || minY >= height) return null
        val left = floor(minX.coerceIn(0f, (width - 1).toFloat())).toInt()
        val top = floor(minY.coerceIn(0f, (height - 1).toFloat())).toInt()
        val right = ceil(maxX.coerceIn(0f, (width - 1).toFloat())).toInt()
        val bottom = ceil(maxY.coerceIn(0f, (height - 1).toFloat())).toInt()
        return Region(left, top, right - left + 1, bottom - top + 1)
    }

    /** Larger depth is nearer. Perspective callers supply inverse view depth. */
    fun draw(coordinates: FloatArray, vertexDepths: FloatArray, colors: IntArray,
        indices: IntArray? = null, scaleX: Float = 1f, scaleY: Float = 1f) {
        require(coordinates.size == colors.size * 2 && vertexDepths.size == colors.size)
        val count = indices?.size ?: colors.size
        require(count % 3 == 0)
        for (at in 0 until count step 3) {
            val a = indices?.get(at) ?: at
            val b = indices?.get(at + 1) ?: (at + 1)
            val c = indices?.get(at + 2) ?: (at + 2)
            require(a in colors.indices && b in colors.indices && c in colors.indices)
            val ax = coordinates[a * 2] * scaleX; val ay = coordinates[a * 2 + 1] * scaleY
            val bx = coordinates[b * 2] * scaleX; val by = coordinates[b * 2 + 1] * scaleY
            val cx = coordinates[c * 2] * scaleX; val cy = coordinates[c * 2 + 1] * scaleY
            val az = vertexDepths[a]; val bz = vertexDepths[b]; val cz = vertexDepths[c]
            if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite() ||
                !cx.isFinite() || !cy.isFinite() || !az.isFinite() || !bz.isFinite() || !cz.isFinite()) continue
            // Camera-facing CCW geometry becomes clockwise after screen Y inversion.
            val area = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
            if (area >= -.000001f) continue
            val minX = maxOf(0f, floor(minOf(ax, bx, cx))).coerceAtMost(width.toFloat()).toInt()
            val maxX = minOf((width - 1).toFloat(), ceil(maxOf(ax, bx, cx))).toInt()
            val minY = maxOf(0f, floor(minOf(ay, by, cy))).coerceAtMost(height.toFloat()).toInt()
            val maxY = minOf((height - 1).toFloat(), ceil(maxOf(ay, by, cy))).toInt()
            if (minX > maxX || minY > maxY) continue
            val inverse = 1f / area
            val ux = (by - cy) * inverse
            val vx = (cy - ay) * inverse
            val redA = (colors[a] ushr 16) and 255; val redB = (colors[b] ushr 16) and 255; val redC = (colors[c] ushr 16) and 255
            val greenA = (colors[a] ushr 8) and 255; val greenB = (colors[b] ushr 8) and 255; val greenC = (colors[c] ushr 8) and 255
            val blueA = colors[a] and 255; val blueB = colors[b] and 255; val blueC = colors[c] and 255
            for (y in minY..maxY) {
                var u = ((by - cy) * (minX + .5f - cx) + (cx - bx) * (y + .5f - cy)) * inverse
                var v = ((cy - ay) * (minX + .5f - cx) + (ax - cx) * (y + .5f - cy)) * inverse
                var pixel = y * width + minX
                for (x in minX..maxX) {
                    val w = 1f - u - v
                    if (u >= -.00001f && v >= -.00001f && w >= -.00001f) {
                        val depth = u * az + v * bz + w * cz
                        if (depth > depths[pixel]) {
                            depths[pixel] = depth
                            val red = (u * redA + v * redB + w * redC).toInt().coerceIn(0, 255)
                            val green = (u * greenA + v * greenB + w * greenC).toInt().coerceIn(0, 255)
                            val blue = (u * blueA + v * blueB + w * blueC).toInt().coerceIn(0, 255)
                            pixels[pixel] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
                        }
                    }
                    pixel++; u += ux; v += vx
                }
            }
        }
    }
}
