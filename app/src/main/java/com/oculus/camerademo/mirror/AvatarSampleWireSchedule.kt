package com.oculus.camerademo.mirror

/** Refined estimates and SDK easing pictures may share a capture time. The
 * revision identifies a successful render, without refreshing its acquisition.
 * Keep wire ordering monotonic and freshness tied to the oldest observation. */
internal class AvatarSampleWireSchedule {
    private var lastCapture = 0L
    private var lastRevision = Long.MIN_VALUE
    private var lastMask = -1

    fun accepts(capturedAtMs: Long, revision: Long, activeMask: Int, nowMs: Long): Boolean =
        AvatarStreamProtocol.fresh(capturedAtMs,nowMs) && capturedAtMs >= lastCapture &&
            (capturedAtMs != lastCapture || revision != lastRevision || activeMask != lastMask)

    fun offered(capturedAtMs: Long, revision: Long, activeMask: Int) {
        lastCapture = capturedAtMs; lastRevision = revision; lastMask = activeMask
    }

    fun reset() { lastCapture = 0L; lastRevision = Long.MIN_VALUE; lastMask = -1 }
}
