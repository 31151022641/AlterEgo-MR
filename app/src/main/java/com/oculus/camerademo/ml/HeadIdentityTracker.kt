package com.oculus.camerademo.ml

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max

/** Temporal geometry association, not biometric identification. Missing detections are never emitted. */
class HeadIdentityTracker(private val retentionMs: Long = 350L) {
    data class Observation(val centerX: Float, val centerY: Float, val width: Float, val height: Float)

    private data class Track(
        val id: Int,
        var observation: Observation,
        var timestampMs: Long,
        var velocityX: Float = 0f,
        var velocityY: Float = 0f
    )

    private val tracks = mutableListOf<Track>()
    private var nextId = 1
    var ambiguousObservations: Set<Int> = emptySet(); private set

    @Synchronized
    fun associate(observations: List<Observation>, timestampMs: Long): List<Int> {
        tracks.removeAll { timestampMs - it.timestampMs > retentionMs || timestampMs < it.timestampMs }
        ambiguousObservations = emptySet()
        if (observations.isEmpty()) return emptyList()
        require(observations.size <= 4)
        require(observations.all {
            it.centerX.isFinite() && it.centerY.isFinite() && it.width.isFinite() &&
                it.height.isFinite() && it.width > 0f && it.height > 0f
        })

        val costs = observations.map { observation ->
            tracks.map { track ->
                val elapsed = (timestampMs - track.timestampMs).coerceIn(0L, 150L) / 1000f
                val predictedX = track.observation.centerX + track.velocityX * elapsed
                val predictedY = track.observation.centerY + track.velocityY * elapsed
                val scale = max(0.06f, hypot(track.observation.width, track.observation.height) * 0.65f)
                val displacement = hypot(observation.centerX - predictedX, observation.centerY - predictedY) / scale
                val scaleChange = abs(ln(observation.width / track.observation.width)) +
                    abs(ln(observation.height / track.observation.height))
                if (displacement > 1.8f || scaleChange > 1.4f) Float.POSITIVE_INFINITY
                else displacement * displacement + scaleChange * 0.3f
            }
        }
        ambiguousObservations = costs.mapIndexedNotNull { index,row ->
            val finite = row.filter { it.isFinite() }.sorted()
            index.takeIf { finite.size >= 2 && finite[1]-finite[0] < .12f }
        }.toSet()
        // With at most four faces, global assignment avoids a greedy match stealing another person's track.
        var bestCost = Float.POSITIVE_INFINITY
        var best = IntArray(observations.size) { -1 }
        val assignment = IntArray(observations.size) { -1 }
        val used = BooleanArray(tracks.size)
        fun search(index: Int, cost: Float) {
            if (cost >= bestCost) return
            if (index == observations.size) {
                bestCost = cost
                best = assignment.copyOf()
                return
            }
            assignment[index] = -1
            search(index + 1, cost + 2.5f)
            for (trackIndex in tracks.indices) {
                if (!used[trackIndex] && costs[index][trackIndex].isFinite()) {
                    used[trackIndex] = true
                    assignment[index] = trackIndex
                    search(index + 1, cost + costs[index][trackIndex])
                    used[trackIndex] = false
                }
            }
        }
        search(0, 0f)

        val ids = observations.mapIndexed { index, observation ->
            val trackIndex = best[index]
            if (trackIndex < 0) {
                Track(nextId++, observation, timestampMs).also { tracks.add(it) }.id
            } else {
                val track = tracks[trackIndex]
                val seconds = (timestampMs - track.timestampMs).coerceAtLeast(1L) / 1000f
                track.velocityX = ((observation.centerX - track.observation.centerX) / seconds).coerceIn(-2f, 2f)
                track.velocityY = ((observation.centerY - track.observation.centerY) / seconds).coerceIn(-2f, 2f)
                track.observation = observation
                track.timestampMs = timestampMs
                track.id
            }
        }
        // Bound association work when repeated false detections briefly create several different tracks.
        if (tracks.size > 12) {
            val currentIds = ids.toSet()
            val remove = tracks.filter { it.id !in currentIds }.sortedBy { it.timestampMs }
                .take(tracks.size - 12).toSet()
            tracks.removeAll(remove)
        }
        return ids
    }

    @Synchronized
    fun clear() {
        tracks.clear()
        ambiguousObservations = emptySet()
    }
}
