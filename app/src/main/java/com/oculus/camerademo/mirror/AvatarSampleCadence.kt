package com.oculus.camerademo.mirror

/** Rendering cadence is independent of observation clocks. Work already spent
 * drawing consumes the interval rather than adding another full interval. */
internal object AvatarSampleCadence {
    fun intervalMs(detail: AvatarSampleDetail, thermalStatus: Int): Long {
        val target = when (detail) {
            AvatarSampleDetail.FAST -> 33L
            AvatarSampleDetail.BALANCED -> 50L
            AvatarSampleDetail.MAXIMUM -> 66L
        }
        return maxOf(target,when {
            thermalStatus >= 3 -> 100L
            thermalStatus >= 2 -> 66L
            else -> 1L
        })
    }

    fun delayMs(startedAt: Long, finishedAt: Long, intervalMs: Long): Long =
        (intervalMs.coerceAtLeast(1L)-(finishedAt-startedAt).coerceAtLeast(0L)).coerceAtLeast(1L)
}
