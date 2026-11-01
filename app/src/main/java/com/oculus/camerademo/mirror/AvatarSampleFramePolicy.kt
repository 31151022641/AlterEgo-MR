package com.oculus.camerademo.mirror

/** A sample pose must belong to the current camera run and avatar selection.
 * Use the camera acquisition time for both inferred input and rendered output. */
object AvatarSampleFramePolicy {
    // Live OS-avatar inference currently takes about 300–430 ms, plus camera
    // queueing and the SDK draw. A 500 ms acquisition deadline forced neutral
    // between healthy estimates and could reject a hand after its render.
    // Keep the actual acquisition time and expire within one second; this is
    // only a bounded video-estimate budget, never the raw-sensor deadline.
    const val MAX_INPUT_AGE_MS = 1000L

    fun fresh(inputTimestampMs: Long, nowMs: Long): Boolean =
        inputTimestampMs > 0 && nowMs >= inputTimestampMs &&
            nowMs - inputTimestampMs <= MAX_INPUT_AGE_MS

    /** Local sample inference has its own budget; original video and LAN frames
     * continue to use the transport limit. Every queue boundary must agree. */
    fun previewFresh(sample: Boolean, capturedAt: Long, nowMs: Long): Boolean =
        if (sample) fresh(capturedAt,nowMs) else AvatarStreamProtocol.fresh(capturedAt,nowMs)

    fun accepts(frameGeneration: Long, currentGeneration: Long,
        frameSelection: Long, currentSelection: Long,
        inputTimestampMs: Long, nowMs: Long, resumed: Boolean): Boolean =
        resumed && frameGeneration == currentGeneration &&
            frameSelection == currentSelection && fresh(inputTimestampMs, nowMs)

}
