package com.oculus.camerademo.mirror

import android.graphics.Bitmap
import com.oculus.camerademo.model3d.HumanPersona
import com.oculus.camerademo.model3d.PortraitAppearance
import com.oculus.camerademo.model3d.PortraitMesh
import com.oculus.camerademo.model3d.PortraitSurface
import com.oculus.camerademo.model3d.PortraitHeadReference
import com.oculus.camerademo.renderer.PortraitRasterizer
import kotlin.math.*

/** Rotation only: MediaPipe's matrix translation/scale are not headset poses. */
object AvatarVideoRotation {
    fun rotate(matrix: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        fun axis(column: Int): FloatArray {
            val at = column * 4
            val length = sqrt(matrix[at]*matrix[at] + matrix[at+1]*matrix[at+1] + matrix[at+2]*matrix[at+2])
                .coerceAtLeast(.0001f)
            return floatArrayOf(matrix[at]/length, matrix[at+1]/length, matrix[at+2]/length)
        }
        val a = axis(0); val b = axis(1); val c = axis(2)
        return floatArrayOf(a[0]*x + b[0]*y + c[0]*z,
            a[1]*x + b[1]*y + c[1]*z, a[2]*x + b[2]*y + c[2]*z)
    }
}

/** Composites a shaded 3D preset head over the detected face, retaining OS body/hands. */
class AvatarVideoFacePainter {
    private val portrait = PortraitMesh()
    private val rasterizer = PortraitRasterizer()
    // Fitting is rigid: talking or blinking must never resize the whole head.
    private val neutralReferences = mutableMapOf<HumanPersona, FloatArray>()

    @Synchronized fun draw(bitmap: Bitmap, face: AvatarVideoFace, persona: HumanPersona) {
        if (face.usable) drawPortrait(bitmap, face, persona)
    }
    /** Same sculpted geometry as the stereo scene, with smooth vertex lighting for video. */
    private fun drawPortrait(bitmap: Bitmap, face: AvatarVideoFace, persona: HumanPersona) {
        val appearance = PortraitAppearance.forPersona(persona)
        val fitPositions = neutralReferences.getOrPut(persona) {
            PortraitHeadReference.skinPositions(portrait.build(appearance, detail=20))
        }
        if (fitPositions.size < 9) return
        val geometry = portrait.build(appearance, face.expression,
            face.gazeX, face.gazeY, detail = 20)
        val axes = Array(3) { column ->
            val at = column * 4
            val length = sqrt(face.rotation[at] * face.rotation[at] + face.rotation[at + 1] * face.rotation[at + 1] +
                face.rotation[at + 2] * face.rotation[at + 2]).coerceAtLeast(.0001f)
            floatArrayOf(face.rotation[at] / length, face.rotation[at + 1] / length, face.rotation[at + 2] / length)
        }
        fun rotate(source: FloatArray): FloatArray = FloatArray(source.size).also { result ->
            for (at in source.indices step 3) for (component in 0..2) {
                result[at + component] = axes[0][component] * source[at] +
                    axes[1][component] * source[at + 1] + axes[2][component] * source[at + 2]
            }
        }
        val positions = rotate(geometry.positions)
        val normals = rotate(geometry.normals)
        val bounds = rotate(fitPositions)
        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        for (at in bounds.indices step 3) {
            minX = minOf(minX, bounds[at]); maxX = maxOf(maxX, bounds[at])
            minY = minOf(minY, bounds[at + 1]); maxY = maxOf(maxY, bounds[at + 1])
        }
        val sx = (face.right - face.left) * bitmap.width / (maxX - minX).coerceAtLeast(.03f)
        val sy = (face.bottom - face.top) * bitmap.height / (maxY - minY).coerceAtLeast(.03f)
        val centerX = (face.left + face.right) * .5f * bitmap.width
        val centerY = (face.top + face.bottom) * .5f * bitmap.height
        val projected = FloatArray(geometry.colors.size * 2)
        val shaded = IntArray(geometry.colors.size)
        for (vertex in geometry.colors.indices) {
            val at = vertex * 3
            projected[vertex * 2] = centerX + (positions[at] - (minX + maxX) * .5f) * sx
            projected[vertex * 2 + 1] = centerY - (positions[at + 1] - (minY + maxY) * .5f) * sy
            shaded[vertex] = PortraitSurface.shade(geometry.colors[vertex], normals[at], normals[at + 1], normals[at + 2],
                geometry.surfaces[vertex], persona == HumanPersona.GOLDEN_SCULPTURE)
        }
        rasterizer.begin(bitmap.width, bitmap.height)
        rasterizer.draw(bitmap, projected, FloatArray(shaded.size) { positions[it * 3 + 2] }, shaded, geometry.indices)
    }
}
