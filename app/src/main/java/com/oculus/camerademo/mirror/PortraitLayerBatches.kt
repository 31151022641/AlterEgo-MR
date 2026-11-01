package com.oculus.camerademo.mirror

/**
 * Places portrait triangles around the few Canvas body/hand layers without sorting
 * or allocating objects per triangle. The portrait's depth buffer resolves overlap
 * within each batch. Stable index order preserves equal-depth triangle behavior.
 */
internal object PortraitLayerBatches {
    fun create(coordinates: FloatArray, vertexZ: FloatArray, indices: IntArray,
        layerDepths: FloatArray): Array<IntArray> {
        require(coordinates.size == vertexZ.size * 2 && indices.size % 3 == 0)
        require(layerDepths.all(Float::isFinite))
        for (i in 1 until layerDepths.size) require(layerDepths[i] >= layerDepths[i - 1])
        val assignments = IntArray(indices.size / 3) { -1 }
        val counts = IntArray(layerDepths.size + 1)
        for (at in indices.indices step 3) {
            val a = indices[at]; val b = indices[at + 1]; val c = indices[at + 2]
            require(a in vertexZ.indices && b in vertexZ.indices && c in vertexZ.indices)
            val ax = coordinates[a * 2]; val ay = coordinates[a * 2 + 1]
            val bx = coordinates[b * 2]; val by = coordinates[b * 2 + 1]
            val cx = coordinates[c * 2]; val cy = coordinates[c * 2 + 1]
            if (!ax.isFinite() || !ay.isFinite() || !bx.isFinite() || !by.isFinite() ||
                !cx.isFinite() || !cy.isFinite()) continue
            // Screen Y is inverted, so an outward front face has negative area.
            if ((bx - ax) * (cy - ay) - (by - ay) * (cx - ax) >= 0f) continue
            val depth = (vertexZ[a] + vertexZ[b] + vertexZ[c]) / 3f
            if (!depth.isFinite()) continue
            var low = 0; var high = layerDepths.size
            while (low < high) {
                val middle = (low + high) ushr 1
                // The old stable sort draws a body layer before a tied portrait.
                if (depth < layerDepths[middle]) high = middle else low = middle + 1
            }
            assignments[at / 3] = low
            counts[low] += 3
        }
        val batches = Array(counts.size) { IntArray(counts[it]) }
        val cursors = IntArray(counts.size)
        for (triangle in assignments.indices) {
            val batch = assignments[triangle]
            if (batch < 0) continue
            val from = triangle * 3; val to = cursors[batch]
            batches[batch][to] = indices[from]
            batches[batch][to + 1] = indices[from + 1]
            batches[batch][to + 2] = indices[from + 2]
            cursors[batch] += 3
        }
        return batches
    }
}
