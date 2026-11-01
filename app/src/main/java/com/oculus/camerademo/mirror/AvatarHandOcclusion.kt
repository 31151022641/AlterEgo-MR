package com.oculus.camerademo.mirror

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker

object AvatarHandOverlap {
    fun coversFace(face: AvatarVideoFace,hands: List<List<Pair<Float,Float>>>): Boolean = hands.any { hand ->
        if (hand.size < 21 || hand.any { !it.first.isFinite() || !it.second.isFinite() }) false else {
            val left = hand.minOf { it.first }; val right = hand.maxOf { it.first }
            val top = hand.minOf { it.second }; val bottom = hand.maxOf { it.second }
            val width = (minOf(face.right,right)-maxOf(face.left,left)).coerceAtLeast(0f)
            val height = (minOf(face.bottom,bottom)-maxOf(face.top,top)).coerceAtLeast(0f)
            width*height > (face.right-face.left)*(face.bottom-face.top)*.08f
        }
    }
}

/** Conservative overlap estimate. Fall back to OS pixels rather than painting over a hand. */
class AvatarHandOcclusion(context: Context) : AutoCloseable {
    private val task = HandLandmarker.createFromOptions(context,HandLandmarker.HandLandmarkerOptions.builder()
        .setBaseOptions(BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build())
        .setRunningMode(RunningMode.VIDEO).setNumHands(2).setMinHandDetectionConfidence(.5f)
        .setMinHandPresenceConfidence(.5f).setMinTrackingConfidence(.5f).build())
    private var lastTimestamp = -1L
    fun overlaps(bitmap: Bitmap,face: AvatarVideoFace,timestamp: Long): Boolean {
        lastTimestamp = maxOf(lastTimestamp+1,timestamp)
        val image = AvatarInferenceImage.create(bitmap)
        val result = try { task.detectForVideo(image,lastTimestamp) } finally { image.close() }
        return AvatarHandOverlap.coversFace(face,result.landmarks().map { hand -> hand.map { it.x() to it.y() } })
    }
    override fun close() = task.close()
}
