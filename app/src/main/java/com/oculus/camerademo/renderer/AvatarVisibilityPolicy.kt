package com.oculus.camerademo.renderer

/** Camera tracking gates all avatars; the selected eye/head target controls Focus Mode. */
object AvatarVisibilityPolicy {
    fun visibleIds(ids: List<Int>, focusId: Int?, partyMode: Boolean,
        cameraReady: Boolean, trackingFresh: Boolean, alpha: Float): Set<Int> {
        if (!cameraReady || !trackingFresh || !alpha.isFinite() || alpha <= 0f) return emptySet()
        val detected = ids.distinct().take(4)
        return if (partyMode) detected.toSet()
            else detected.filter { it == focusId }.toSet()
    }
}
