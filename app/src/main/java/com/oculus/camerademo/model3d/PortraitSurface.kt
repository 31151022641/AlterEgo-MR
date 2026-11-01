package com.oculus.camerademo.model3d

import kotlin.math.*

/** Surface finishes shared by the live mesh, gallery and video renderers. */
object PortraitSurface {
    const val SKIN = 0
    const val HAIR = 1
    const val EYE = 2
    const val MOUTH = 3
    const val ACCENT = 4
    const val HORN = 5
    const val COUNT = 6

    data class Sections(val indices: IntArray, val ranges: IntArray, val materialIds: IntArray)

    /** Native SDK material ranges are flattened (startIndex, indexCount) pairs. */
    fun sections(indices: IntArray, surfaces: IntArray): Sections {
        val counts = IntArray(COUNT)
        for (i in indices.indices step 3) counts[surfaces[indices[i]]] += 3
        val used = (0 until COUNT).filter { counts[it] > 0 }.toIntArray()
        val starts = IntArray(COUNT)
        var offset = 0
        val ranges = IntArray(used.size * 2)
        used.forEachIndexed { i, material ->
            starts[material] = offset
            ranges[i * 2] = offset
            ranges[i * 2 + 1] = counts[material]
            offset += counts[material]
        }
        val cursor = starts.copyOf()
        val sorted = IntArray(indices.size)
        for (i in indices.indices step 3) {
            val material = surfaces[indices[i]]
            val at = cursor[material]
            sorted[at] = indices[i]; sorted[at + 1] = indices[i + 1]; sorted[at + 2] = indices[i + 2]
            cursor[material] += 3
        }
        return Sections(sorted, ranges, used)
    }

    /** Soft warm key, cool fill and restrained material-specific reflections. */
    @JvmStatic fun shade(color: Int, normalX: Float, normalY: Float, normalZ: Float,
                         surface: Int, metallic: Boolean): Int {
        if (surface == ACCENT) return color or (0xff shl 24)
        val length = sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ)
        val valid = length.isFinite() && length > 1e-8f
        val nx = if (valid) normalX / length else 0f
        val ny = if (valid) normalY / length else 0f
        val nz = if (valid) normalZ / length else 1f
        val key = ((-.52f * nx + .62f * ny + .59f * nz) * .85f + .15f).coerceIn(0f, 1f).pow(.7f)
        val fill = (.70f * nx + .10f * ny + .707f * nz).coerceIn(0f, 1f)
        val half = (-.292f * nx + .348f * ny + .891f * nz).coerceIn(0f, 1f)
        val gloss = when (surface) { EYE -> .30f; HAIR -> .10f; MOUTH -> .065f; HORN -> .14f; else -> .045f }
        val shine = half.pow(if (surface == EYE) 38 else 24) * if (metallic) .34f else gloss
        val strip = if (metallic) (.63f * nx + .18f * ny + .755f * nz).coerceIn(0f, 1f).pow(32) * .22f else 0f
        val rim = (1f - abs(nz)).coerceIn(0f, 1f).pow(3) * max(0f, nx) * .10f
        fun channel(shift: Int, ambient: Float, keyAmount: Float, fillAmount: Float, highlight: Float, edge: Float): Int {
            val base = (color ushr shift) and 255
            return (base * (ambient + keyAmount * key + fillAmount * fill) + highlight * (shine + strip) + edge * rim)
                .roundToInt().coerceIn(0, 255)
        }
        val r = channel(16, .45f, .45f, .10f, 255f, 135f)
        val g = channel(8, .45f, .43f, .14f, if (metallic) 232f else 249f, 196f)
        val b = channel(0, .47f, .40f, .18f, if (metallic) 176f else 243f, 255f)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
}
