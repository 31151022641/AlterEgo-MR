package com.oculus.camerademo.renderer

data class AvatarDetail(val skullRows: Int, val skullColumns: Int, val expressionIntervalMs: Long)

/** Mesh detail scheduling, independent of the compositor's hardware foveation support. */
object AvatarDetailPolicy {
    fun detail(quality: ResolutionQuality, prioritizeGaze: Boolean, validEyeGaze: Boolean,
        headId: Int, focusedId: Int?): AvatarDetail {
        if (prioritizeGaze && validEyeGaze && focusedId != null) {
            return if (headId == focusedId) AvatarDetail(20, 28, 0L) else AvatarDetail(6, 10, 100L)
        }
        return when (quality) {
            ResolutionQuality.HIGH -> AvatarDetail(20, 28, 0L)
            ResolutionQuality.BALANCED -> AvatarDetail(14, 20, 33L)
            ResolutionQuality.PERFORMANCE -> AvatarDetail(8, 12, 66L)
        }
    }
}
