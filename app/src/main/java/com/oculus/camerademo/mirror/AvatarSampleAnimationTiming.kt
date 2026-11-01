package com.oculus.camerademo.mirror

/** Advance animation by elapsed render time without replaying a long paused session. */
object AvatarSampleAnimationTiming {
    const val FIRST_STEP_SECONDS = 1f / 15f
    fun deltaSeconds(previousAt: Long, now: Long): Float {
        if (previousAt <= 0L || now <= previousAt) return FIRST_STEP_SECONDS
        return ((now - previousAt) / 1000f).coerceIn(1f / 120f, .25f)
    }
}
