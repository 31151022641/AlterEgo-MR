package com.oculus.camerademo.targeting

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Vector3
import com.oculus.camerademo.eyetracking.EyeGazeData
import com.oculus.camerademo.eyetracking.EyeTrackingDeviceStatus
import com.oculus.camerademo.eyetracking.GazeHeadTarget
import com.oculus.camerademo.eyetracking.GazeRay
import com.oculus.camerademo.eyetracking.GazeSelection
import com.oculus.camerademo.eyetracking.GazeTargetSelector
import com.oculus.camerademo.eyetracking.GazeVector

enum class TargetingMode(val label: String) {
    EYE_TRACKING("Eye tracking"), HEAD_DIRECTION("Head direction")
}

data class TargetingSample(val mode: TargetingMode, val ray: GazeRay?) {
    val isValid: Boolean get() = ray != null
}

/** Real eye gaze is preferred; head direction is a distinct, explicitly labelled input. */
class FaceTargetingController(private val eyeLossGraceMs: Long = 750L) {
    var sample = TargetingSample(TargetingMode.HEAD_DIRECTION, null)
        private set
    private var eyeUnavailableSinceMs: Long? = null
    private val selector = GazeTargetSelector()

    fun update(eye: EyeGazeData, headRay: GazeRay?, nowMs: Long): TargetingSample {
        val eyeRay = eye.worldRay?.takeIf { eye.status == EyeTrackingDeviceStatus.READY && eye.isValid && isFresh(it, nowMs) }
        val next = if (eyeRay != null) {
            eyeUnavailableSinceMs = null
            TargetingSample(TargetingMode.EYE_TRACKING, eyeRay)
        } else {
            val expectingEye = eye.status == EyeTrackingDeviceStatus.READY ||
                eye.status == EyeTrackingDeviceStatus.TRACKING_UNAVAILABLE
            val lostSince = eyeUnavailableSinceMs ?: nowMs.also { eyeUnavailableSinceMs = it }
            if (expectingEye && nowMs >= lostSince && nowMs - lostSince < eyeLossGraceMs) {
                // A blink must not briefly select a different person at the centre of the headset.
                TargetingSample(TargetingMode.EYE_TRACKING, null)
            } else TargetingSample(TargetingMode.HEAD_DIRECTION, headRay?.takeIf { isFresh(it, nowMs) })
        }
        if (next.mode != sample.mode || next.ray == null) selector.reset()
        sample = next
        return next
    }

    fun selectHeads(targets: List<GazeHeadTarget>, nowMs: Long): GazeSelection =
        selector.select(sample.ray, targets, nowMs)

    fun projectOnPanel(pose: Pose, width: Float, height: Float, nowMs: Long): Pair<Float, Float>? {
        val ray = sample.ray?.takeIf { isFresh(it, nowMs) } ?: return null
        if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f) return null
        val origin = pose.inverse() * Vector3(ray.origin.x, ray.origin.y, ray.origin.z)
        val direction = pose.q.inverse() * Vector3(ray.direction.x, ray.direction.y, ray.direction.z)
        if (kotlin.math.abs(direction.z) < 0.00001f) return null
        val distance = -origin.z / direction.z
        if (!distance.isFinite() || distance < 0f) return null
        val x = 0.5f + (origin.x + direction.x * distance) / width
        val y = 0.5f - (origin.y + direction.y * distance) / height
        return if (x in 0f..1f && y in 0f..1f) x to y else null
    }

    fun reset() {
        selector.reset()
        eyeUnavailableSinceMs = null
        sample = TargetingSample(TargetingMode.HEAD_DIRECTION, null)
    }

    companion object {
        fun headRay(viewer: Pose?, nowMs: Long): GazeRay? {
            if (viewer == null) return null
            val direction = viewer.q * Vector3(0f, 0f, 1f)
            val origin = GazeVector(viewer.t.x, viewer.t.y, viewer.t.z)
            val normalized = GazeVector(direction.x, direction.y, direction.z).normalized() ?: return null
            return if (origin.isFinite()) GazeRay(origin, normalized, nowMs) else null
        }

        private fun isFresh(ray: GazeRay, nowMs: Long) =
            nowMs >= ray.sampledAtMs && nowMs - ray.sampledAtMs <= 150L &&
                ray.origin.isFinite() && ray.direction.normalized() != null
    }
}
