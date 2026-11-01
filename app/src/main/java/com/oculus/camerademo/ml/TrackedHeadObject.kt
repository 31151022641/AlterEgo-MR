package com.oculus.camerademo.ml

import com.oculus.camerademo.camera.CameraExtrinsics

/**
 * A face observed in one real camera frame, associated temporally by geometry (not identity recognition).
 * Position and quaternion are in the camera's graphics coordinates: +X right, +Y up, -Z forward.
 * Apply cameraExtrinsics and the full headset pose at capturedAtUptimeMs before world rendering.
 */
data class TrackedHeadObject(
    val id: Int,
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.5f,
    val faceWidth: Float = 0.2f,
    val faceHeight: Float = 0.25f,
    val headDistanceMeters: Float = 1.2f,

    // Euler convenience values; use orientation for complete pose composition.
    val rotationYaw: Float = 0f,
    val rotationPitch: Float = 0f,
    val rotationRoll: Float = 0f,

    // Metric scale is estimated from a canonical face, not measured by a depth sensor.
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
    val anchorZ: Float = -1.2f,
    val orientation: HeadQuaternion = HeadQuaternion(),
    val capturedAtUptimeMs: Long = 0L,
    val timestampNanos: Long = 0L,
    val poseTimestampReliable: Boolean = false,
    val cameraExtrinsics: CameraExtrinsics? = null,
    val depthIsApproximate: Boolean = true,
    val reprojectionErrorPixels: Float = Float.POSITIVE_INFINITY,
    val landmarks3D: List<FloatArray> = emptyList(),

    // Comprehensive real-time facial expression and emotion tracking
    val emotion: FaceEmotionState = FaceEmotionState(),

    // Real-time animated blendshapes (backward compatibility convenience)
    val jawOpen: Float = emotion.jawOpen,
    val smileScore: Float = (emotion.mouthSmileLeft + emotion.mouthSmileRight) * 0.5f,
    val eyeBlinkScore: Float = (emotion.eyeBlinkLeft + emotion.eyeBlinkRight) * 0.5f,

    // Gaze-contingent state: true when the user's eyes look at this head object
    val isEyeLookedAt: Boolean = false,
    val gazeDistance: Float = 1.0f,
    val stickTransitionAlpha: Float = 0.0f
)

/**
 * Container holding all physical human head objects currently detected in the passthrough camera.
 */
data class MultiFaceTrackingData(
    val trackedHeads: List<TrackedHeadObject> = emptyList(),
    val gazeLockedHead: TrackedHeadObject? = null,
    val totalTrackedHeads: Int = 0,
    val hasGazeLockedHead: Boolean = false,
    val capturedAtUptimeMs: Long = 0L,
    val timestampNanos: Long = 0L,
    val receivedAtUptimeMs: Long = 0L,
    val poseTimestampReliable: Boolean = false,
    val cameraExtrinsics: CameraExtrinsics? = null,
    val status: FaceTrackingStatus = FaceTrackingStatus.NO_FACE
) {
    val hasFace: Boolean get() = trackedHeads.isNotEmpty()

    fun isFresh(nowUptimeMs: Long, maximumAgeMs: Long = 250L): Boolean =
        hasFace && capturedAtUptimeMs > 0L && nowUptimeMs >= capturedAtUptimeMs &&
            nowUptimeMs - capturedAtUptimeMs <= maximumAgeMs

    /**
     * Converts to single-face FaceTrackingData for backward compatibility.
     */
    fun toLegacyFaceTrackingData(): FaceTrackingData {
        val target = gazeLockedHead ?: trackedHeads.firstOrNull()
        return if (target != null) {
            FaceTrackingData(
                hasFace = true,
                faceCenterX = target.faceCenterX,
                faceCenterY = target.faceCenterY,
                headDistanceMeters = target.headDistanceMeters,
                rotationYaw = target.rotationYaw,
                rotationPitch = target.rotationPitch,
                rotationRoll = target.rotationRoll,
                emotion = target.emotion,
                jawOpen = target.jawOpen,
                smileScore = target.smileScore,
                eyeBlinkScore = target.eyeBlinkScore,
                landmarksCount = target.landmarks3D.size,
                landmarks3D = target.landmarks3D,
                trackedHeadId = target.id,
                anchorX = target.anchorX,
                anchorY = target.anchorY,
                anchorZ = target.anchorZ,
                orientation = target.orientation,
                capturedAtUptimeMs = target.capturedAtUptimeMs,
                depthIsApproximate = target.depthIsApproximate
            )
        } else {
            FaceTrackingData(hasFace = false, capturedAtUptimeMs = capturedAtUptimeMs)
        }
    }
}

enum class FaceTrackingStatus { TRACKING, NO_FACE, UNCALIBRATED, UNAVAILABLE, ERROR, AMBIGUOUS }
