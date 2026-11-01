package com.oculus.camerademo.renderer

import com.meta.spatial.core.Pose
import java.util.ArrayDeque

/** Capture-time transforms avoid attaching delayed detections to the wearer's current head pose. */
class ViewerPoseHistory(private val retentionMs: Long = 1500L) {
    private data class Sample(val timeMs: Long, val pose: Pose)
    private val samples = ArrayDeque<Sample>()

    @Synchronized fun record(timeMs: Long, pose: Pose) {
        if (samples.lastOrNull()?.timeMs?.let { timeMs <= it } == true) return
        samples.addLast(Sample(timeMs, Pose(pose.t.copy(), pose.q.copy())))
        while (samples.size > 2 && timeMs - samples.first.timeMs > retentionMs) samples.removeFirst()
    }

    @Synchronized fun at(timeMs: Long): Pose? {
        if (samples.isEmpty()) return null
        if (timeMs < samples.first.timeMs || timeMs > samples.last.timeMs) return null
        val list = samples.toList()
        for (i in list.indices) {
            val right = list[i]
            if (right.timeMs == timeMs) return right.pose.copy()
            if (right.timeMs > timeMs && i > 0) {
                val left = list[i - 1]
                if (right.timeMs - left.timeMs > 100L) return null
                return left.pose.lerp(right.pose,
                    (timeMs - left.timeMs).toFloat() / (right.timeMs - left.timeMs))
            }
        }
        return null
    }

    @Synchronized fun clear() = samples.clear()
}
