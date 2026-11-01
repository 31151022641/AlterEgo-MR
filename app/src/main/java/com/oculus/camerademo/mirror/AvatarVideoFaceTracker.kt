package com.oculus.camerademo.mirror

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.oculus.camerademo.ml.FaceEmotionState

/** Estimates expressions from rendered pixels, not from the wearer's sensor data. */
class AvatarVideoFaceTracker(context: Context, accelerated: Boolean = false) : AutoCloseable {
    private val task = AvatarInferenceTask("Face",accelerated) { delegate -> FaceLandmarker.createFromOptions(context,
        FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task")
                .setDelegate(delegate).build())
            .setRunningMode(RunningMode.VIDEO).setNumFaces(1)
            .setMinFaceDetectionConfidence(.45f).setMinFacePresenceConfidence(.5f)
            .setMinTrackingConfidence(.5f).setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true).build()) }
    private var lastTimestamp = -1L

    fun estimate(bitmap: Bitmap, timestampMs: Long): AvatarVideoFace? {
        // MPImage owns its Bitmap and recycles it on close. Keep the output frame
        // alive for compositing and display by giving inference a separate copy.
        val image = AvatarInferenceImage.create(bitmap)
        return try { estimate(image, timestampMs) } finally { image.close() }
    }

    /** Borrows one caller-owned image for synchronous VIDEO inference. A sample
     * worker can share that image with hand inference and close it after both. */
    internal fun estimate(image: MPImage, timestampMs: Long): AvatarVideoFace? {
        val timestamp = maxOf(timestampMs, lastTimestamp + 1)
        lastTimestamp = timestamp
        val result = task.run { it.detectForVideo(image, timestamp) }
        val points = result.faceLandmarks().firstOrNull() ?: return null
        val matrix = result.facialTransformationMatrixes().orElse(emptyList()).firstOrNull() ?: return null
        if (points.size < 468) return null
        val weights = result.faceBlendshapes().orElse(emptyList()).firstOrNull()
            ?.associate { it.categoryName() to it.score() }.orEmpty()
        fun score(name: String) = (weights[name] ?: 0f).takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
        val expression = FaceEmotionState(
            eyeBlinkLeft = score("eyeBlinkLeft"), eyeBlinkRight = score("eyeBlinkRight"),
            eyeSquintLeft = score("eyeSquintLeft"), eyeSquintRight = score("eyeSquintRight"),
            eyeWideLeft = score("eyeWideLeft"), eyeWideRight = score("eyeWideRight"),
            jawOpen = score("jawOpen"), mouthSmileLeft = score("mouthSmileLeft"),
            mouthSmileRight = score("mouthSmileRight"), mouthFrownLeft = score("mouthFrownLeft"),
            mouthFrownRight = score("mouthFrownRight"), mouthPucker = score("mouthPucker"),
            mouthFunnel = score("mouthFunnel"), browInnerUp = score("browInnerUp"),
            browDownLeft = score("browDownLeft"), browDownRight = score("browDownRight"),
            browOuterUpLeft = score("browOuterUpLeft"), browOuterUpRight = score("browOuterUpRight"),
            cheekPuff = score("cheekPuff"), cheekSquintLeft = score("cheekSquintLeft"),
            cheekSquintRight = score("cheekSquintRight"))
        return AvatarVideoFace(points.minOf { it.x() }, points.minOf { it.y() },
            points.maxOf { it.x() }, points.maxOf { it.y() }, matrix, expression,
            ((score("eyeLookOutLeft") - score("eyeLookInLeft")) +
                (score("eyeLookInRight") - score("eyeLookOutRight"))) * .5f,
            ((score("eyeLookUpLeft") - score("eyeLookDownLeft")) +
                (score("eyeLookUpRight") - score("eyeLookDownRight"))) * .5f,
            weights.mapValues { (_, value) -> if (value.isFinite()) value.coerceIn(0f,1f) else 0f }).takeIf { it.usable }
    }

    override fun close() = task.close()
}

/** Limit preprocessing cost while retaining enough pixels for upper-body faces/hands.
 * The returned MPImage owns its independent bitmap; normalized coordinates remain unchanged. */
internal object AvatarInferenceImage {
    fun create(bitmap: Bitmap): com.google.mediapipe.framework.image.MPImage {
        val longest = maxOf(bitmap.width,bitmap.height)
        val owned = if (longest > 640) Bitmap.createScaledBitmap(bitmap,
            (bitmap.width*640/longest).coerceAtLeast(1),(bitmap.height*640/longest).coerceAtLeast(1),true)
        else bitmap.copy(Bitmap.Config.ARGB_8888,false)
        return BitmapImageBuilder(owned).build()
    }
}
