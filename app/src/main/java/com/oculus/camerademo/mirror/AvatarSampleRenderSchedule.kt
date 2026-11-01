package com.oculus.camerademo.mirror

/** Main-thread render cadence for inputs already validated by AvatarSamplePresentation.
 * Reusing pixels for the same observation leaves GPU time for camera inference. The SDK
 * eases hands toward its neutral pose, so neutral controls settle briefly before caching. */
class AvatarSampleRenderSchedule {
    /** Zero retains observation-only caching for legacy/public fixtures. */
    var activeRefreshMs = 0L
    private var hasRenderedFrame = false
    private var renderedObservation = false
    private var observedTimestamp = Long.MIN_VALUE
    private var observedRevision: Long? = null
    private var observedMask = 0
    private val removedChannelStartedAt = LongArray(4) { Long.MIN_VALUE }
    private var neutralStartedAt: Long? = null
    private var lastRenderedAt = Long.MIN_VALUE
    private var lastRenderStartedAt = Long.MIN_VALUE

    fun needsRender(snapshot: WearerSnapshot, nowMs: Long): Boolean {
        if (!hasRenderedFrame) return true
        return if (observed(snapshot)) {
            val changed = !renderedObservation || if (snapshot.videoChannels != null)
                snapshot.videoChannels.revision != observedRevision || AvatarSamplePresentation.activeMask(snapshot) != observedMask ||
                    settling(snapshot) && nowMs-lastRenderedAt >= NEUTRAL_INTERVAL_MS
            else snapshot.sampledAtMs > observedTimestamp
            changed || activeRefreshMs > 0L && AvatarSampleFramePolicy.fresh(snapshot.sampledAtMs,nowMs) &&
                nowMs-lastRenderStartedAt >= activeRefreshMs
        } else {
            if (renderedObservation) true else {
                val started = neutralStartedAt ?: return true
                // Include a final settled frame even if a slow tick crosses the deadline.
                lastRenderedAt - started < NEUTRAL_SETTLE_MS &&
                    nowMs - lastRenderedAt >= NEUTRAL_INTERVAL_MS
            }
        }
    }

    /** Call only after the SDK has successfully produced a usable output bitmap. */
    fun rendered(snapshot: WearerSnapshot, nowMs: Long, startedAtMs: Long = nowMs) {
        val observation = observed(snapshot)
        if (observation) {
            val mask = AvatarSamplePresentation.activeMask(snapshot)
            if (snapshot.videoChannels != null) {
                for (channel in removedChannelStartedAt.indices) {
                    val bit = 1 shl channel
                    val started = removedChannelStartedAt[channel]
                    if ((mask and bit) != 0) removedChannelStartedAt[channel] = Long.MIN_VALUE
                    else if (renderedObservation && observedRevision != null && (observedMask and bit) != 0)
                        removedChannelStartedAt[channel] = nowMs
                    else if (started != Long.MIN_VALUE && nowMs-started >= NEUTRAL_SETTLE_MS)
                        removedChannelStartedAt[channel] = Long.MIN_VALUE
                }
            } else removedChannelStartedAt.fill(Long.MIN_VALUE)
            neutralStartedAt = null
            observedTimestamp = snapshot.sampledAtMs
            observedRevision = snapshot.videoChannels?.revision
            observedMask = AvatarSamplePresentation.activeMask(snapshot)
        } else {
            removedChannelStartedAt.fill(Long.MIN_VALUE)
            if (!hasRenderedFrame || renderedObservation || neutralStartedAt == null) neutralStartedAt = nowMs
        }
        hasRenderedFrame = true
        renderedObservation = observation
        lastRenderedAt = nowMs
        lastRenderStartedAt = if (startedAtMs in 0L..nowMs) startedAtMs else nowMs
    }

    /** Losing output pixels requires a fresh render even if the input is unchanged. */
    fun invalidate() {
        hasRenderedFrame = false
        renderedObservation = false
        observedTimestamp = Long.MIN_VALUE
        observedRevision = null
        observedMask = 0
        removedChannelStartedAt.fill(Long.MIN_VALUE)
        neutralStartedAt = null
        lastRenderedAt = Long.MIN_VALUE
        lastRenderStartedAt = Long.MIN_VALUE
    }

    /** Reset after pausing, changing selection or replacing the SDK entity. */
    fun reset() = invalidate()

    /** A partial loss needs SDK easing even while surviving controls are unchanged.
     * This changes only drawing cadence, never a channel's acquisition clock. */
    private fun settling(snapshot: WearerSnapshot): Boolean {
        if (!observed(snapshot) || snapshot.videoChannels == null) return false
        val mask = AvatarSamplePresentation.activeMask(snapshot)
        // The first loss render has not yet established its settling clock.
        if (renderedObservation && observedRevision != null && (observedMask and mask.inv()) != 0) return true
        return removedChannelStartedAt.indices.any { channel ->
            val started = removedChannelStartedAt[channel]
            (mask and (1 shl channel)) == 0 && started != Long.MIN_VALUE &&
                lastRenderedAt-started < NEUTRAL_SETTLE_MS
        }
    }

    private fun observed(snapshot: WearerSnapshot) = snapshot.hasVideoFace || snapshot.hasEyes ||
        snapshot.hands.any { hand -> hand.any { it != null } }

    companion object {
        private const val NEUTRAL_INTERVAL_MS = 200L
        private const val NEUTRAL_SETTLE_MS = 2000L
    }
}
