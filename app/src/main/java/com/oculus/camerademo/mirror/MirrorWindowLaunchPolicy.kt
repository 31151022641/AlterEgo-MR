package com.oculus.camerademo.mirror

/** Explicit navigation can leave an uninitialized XR scene; automatic startup waits. */
object MirrorWindowLaunchPolicy {
    fun allowed(userRequested: Boolean, automaticReady: Boolean, launched: Boolean, closing: Boolean): Boolean =
        !launched && !closing && (userRequested || automaticReady)
}
