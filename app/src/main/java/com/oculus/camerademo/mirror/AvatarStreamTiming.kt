package com.oculus.camerademo.mirror

import java.util.concurrent.atomic.AtomicBoolean

/** A completed write cannot be closed by a timer that was already queued when its Future was cancelled. */
class AvatarWriteDeadline {
    private val finished = AtomicBoolean(false)
    fun complete() { finished.set(true) }
    fun claimTimeout(): Boolean = finished.compareAndSet(false,true)
}

object AvatarStreamTiming {
    const val IMAGE_GRACE_MS = 2000L
    const val NO_IMAGE_INTERVAL_MS = 500L
    const val WRITE_TIMEOUT_MS = 3000L
    const val CAMERA_RESTART_AFTER_MS = 5000L
    const val CAMERA_RETRY_MS = 3000L
    fun restartDue(lastCameraEvent: Long, now: Long) =
        lastCameraEvent > 0 && now >= lastCameraEvent && now - lastCameraEvent >= CAMERA_RESTART_AFTER_MS
    fun imageExpired(lastImageAt: Long, now: Long) =
        lastImageAt > 0 && now >= lastImageAt && now-lastImageAt >= IMAGE_GRACE_MS
    fun noImageDue(lastImageAt: Long, lastRecordAt: Long, now: Long, explicitlyUnavailable: Boolean = false) =
        (explicitlyUnavailable || imageExpired(lastImageAt,now)) && now >= lastRecordAt && now-lastRecordAt >= NO_IMAGE_INTERVAL_MS
}
