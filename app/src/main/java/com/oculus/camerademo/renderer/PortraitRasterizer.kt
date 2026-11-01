package com.oculus.camerademo.renderer

import android.graphics.Bitmap

/** Composite only the portrait's bounds; pixels outside the mesh keep the camera/body image. */
class PortraitRasterizer {
    private val buffer = PortraitDepthBuffer()

    fun begin(width: Int, height: Int) = buffer.begin(width, height)

    fun draw(bitmap: Bitmap, coordinates: FloatArray, depths: FloatArray, colors: IntArray,
        indices: IntArray? = null, scaleX: Float = 1f, scaleY: Float = 1f) {
        if (buffer.width != bitmap.width || buffer.height != bitmap.height) begin(bitmap.width, bitmap.height)
        val region = buffer.region(coordinates, scaleX, scaleY) ?: return
        val offset = region.top * bitmap.width + region.left
        bitmap.getPixels(buffer.pixels, offset, bitmap.width, region.left, region.top, region.width, region.height)
        buffer.draw(coordinates, depths, colors, indices, scaleX, scaleY)
        bitmap.setPixels(buffer.pixels, offset, bitmap.width, region.left, region.top, region.width, region.height)
    }
}
