package com.oculus.camerademo.model3d

/**
 * A fitting reference for a caller-supplied NEUTRAL portrait. Hair, horns,
 * mouth interiors and expressions must not redefine the face's fitted size.
 * Keep and rotate the returned neutral coordinates instead of measuring each
 * animated frame. This helper does not load or mutate the selected model.
 */
object PortraitHeadReference {
    @JvmStatic fun skinPositions(geometry: PortraitGeometry): FloatArray {
        val count = geometry.positions.size / 3
        if (count == 0) return FloatArray(0)
        val referenced = BooleanArray(count)
        for (index in geometry.indices) if (index in 0 until count) referenced[index] = true
        val skin = ArrayList<Float>()
        val finite = ArrayList<Float>()
        for (vertex in 0 until count) {
            if (!referenced[vertex]) continue
            val at = vertex * 3
            val x = geometry.positions[at]
            val y = geometry.positions[at + 1]
            val z = geometry.positions[at + 2]
            if (!x.isFinite() || !y.isFinite() || !z.isFinite()) continue
            finite.add(x); finite.add(y); finite.add(z)
            if (geometry.surfaces.getOrNull(vertex) == PortraitSurface.SKIN) {
                skin.add(x); skin.add(y); skin.add(z)
            }
        }
        // Models without classified skin still have a bounded rigid reference.
        // Entirely missing/invalid geometry fails closed with an empty result;
        // callers must skip drawing rather than invent an arbitrary fit.
        return (if (skin.isNotEmpty()) skin else finite).toFloatArray()
    }
}
