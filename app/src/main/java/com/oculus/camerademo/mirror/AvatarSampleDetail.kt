package com.oculus.camerademo.mirror

/** Response/detail choices for the inferred sample only. The original Horizon
 * camera and the direct sensor renderer keep their existing quality settings. */
enum class AvatarSampleDetail(val label: String, val width: Int, val height: Int, val samples: Int) {
    FAST("Fast response",960,540,1),
    BALANCED("Balanced",1280,720,2),
    MAXIMUM("Maximum detail",1920,1080,4)
}
