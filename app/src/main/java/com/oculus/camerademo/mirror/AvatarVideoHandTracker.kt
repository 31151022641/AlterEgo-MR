package com.oculus.camerademo.mirror

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

data class AvatarVideoLandmark(val x: Float, val y: Float, val z: Float) {
    val finite get() = x.isFinite() && y.isFinite() && z.isFinite()
}

enum class AvatarVideoHandSide(val packetSide: Int) { LEFT(0), RIGHT(1) }

/** Observations of the OS avatar's rendered hand. These are not raw wearer sensors. */
data class AvatarVideoHand(
    val side: AvatarVideoHandSide,
    val confidence: Float,
    val landmarks: List<AvatarVideoLandmark>,
    val worldLandmarks: List<AvatarVideoLandmark>,
    val sampledAtMs: Long,
    val imageAspectRatio: Float = 16f / 9f,
)

/** GPU inference has a CPU fallback and does not require an XR session. */
class AvatarVideoHandTracker(context: Context, accelerated: Boolean = false) : AutoCloseable {
    private val task = AvatarInferenceTask("Hands",accelerated) { delegate -> HandLandmarker.createFromOptions(context,
        HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task")
                .setDelegate(delegate).build())
            .setRunningMode(RunningMode.VIDEO).setNumHands(2)
            .setMinHandDetectionConfidence(.5f).setMinHandPresenceConfidence(.5f)
            .setMinTrackingConfidence(.5f).build()) }
    private var lastTimestamp = -1L

    fun estimate(bitmap: Bitmap, timestamp: Long): List<AvatarVideoHand> {
        if (timestamp < 0 || timestamp <= lastTimestamp) return emptyList()
        val image = AvatarInferenceImage.create(bitmap)
        return try { estimate(image, timestamp) } finally { image.close() }
    }

    /** The caller owns this image until both synchronous sample estimators finish. */
    internal fun estimate(image: MPImage, timestamp: Long): List<AvatarVideoHand> {
        if (timestamp < 0 || timestamp <= lastTimestamp) return emptyList()
        lastTimestamp = timestamp
        val result = task.run { it.detectForVideo(image, timestamp) }
        return result.landmarks().mapIndexedNotNull { index, landmarks ->
            val category = result.handedness().getOrNull(index)?.maxByOrNull { it.score() }
                ?: return@mapIndexedNotNull null
            val side = wearerSide(category.categoryName()) ?: return@mapIndexedNotNull null
            val world = result.worldLandmarks().getOrNull(index) ?: return@mapIndexedNotNull null
            AvatarVideoHand(side, category.score(), landmarks.map { AvatarVideoLandmark(it.x(), it.y(), it.z()) },
                world.map { AvatarVideoLandmark(it.x(), it.y(), it.z()) }, timestamp,
                image.width.toFloat() / image.height)
        }
    }

    override fun close() = task.close()

    companion object {
        /** MediaPipe labels assume a selfie image. The OS camera frames are unmirrored.
         * https://chuoling.github.io/mediapipe/solutions/hands.html#multi_handedness */
        internal fun wearerSide(label: String): AvatarVideoHandSide? = when (label.lowercase()) {
            "left" -> AvatarVideoHandSide.RIGHT
            "right" -> AvatarVideoHandSide.LEFT
            else -> null
        }
    }
}
