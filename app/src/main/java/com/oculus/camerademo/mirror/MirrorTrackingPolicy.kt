package com.oculus.camerademo.mirror

object MirrorTrackingPolicy {
    fun canSample(sceneReady: Boolean, sessionVisible: Boolean, mirrorMode: Boolean,
        activityResumed: Boolean, windowVisible: Boolean): Boolean =
        sceneReady && sessionVisible && mirrorMode && (activityResumed || windowVisible)
}
