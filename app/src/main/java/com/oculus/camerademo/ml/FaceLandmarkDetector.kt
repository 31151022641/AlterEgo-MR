package com.oculus.camerademo.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.oculus.camerademo.camera.CameraFrame
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class FaceTrackingData(
    val hasFace: Boolean,
    val faceCenterX: Float = 0.5f,
    val faceCenterY: Float = 0.5f,
    val headDistanceMeters: Float = 1.2f,
    val rotationYaw: Float = 0f,
    val rotationPitch: Float = 0f,
    val rotationRoll: Float = 0f,
    val emotion: FaceEmotionState = FaceEmotionState(),
    val jawOpen: Float = emotion.jawOpen,
    val smileScore: Float = (emotion.mouthSmileLeft + emotion.mouthSmileRight) * 0.5f,
    val eyeBlinkScore: Float = (emotion.eyeBlinkLeft + emotion.eyeBlinkRight) * 0.5f,
    val landmarksCount: Int = 0,
    val landmarks3D: List<FloatArray> = emptyList(),
    val trackedHeadId: Int? = null,
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
    val anchorZ: Float = -1.2f,
    val orientation: HeadQuaternion = HeadQuaternion(),
    val capturedAtUptimeMs: Long = 0L,
    val depthIsApproximate: Boolean = true
)

class FaceLandmarkDetector(
    private val context: Context,
    private val onFaceDetected: ((FaceTrackingData) -> Unit)? = null,
    private val onMultiFaceDetected: ((MultiFaceTrackingData) -> Unit)? = null
) {
    private data class PendingFrame(
        val frame: CameraFrame,
        val image: MPImage,
        val ownedBitmap: Bitmap,
        val taskTimestampMs: Long,
        val generation: Long,
        val submittedAtUptimeMs: Long
    )

    private var faceLandmarker: FaceLandmarker? = null
    @Volatile private var isInitialized = false
    @Volatile private var permanentlyClosed = false
    @Volatile private var initializationFailed = false
    // Initialization may run on an executor. The lifecycle monitor only guards commit/submit/detach.
    private var initializing = false
    private val trackingLock = Any()
    private val identityTracker = HeadIdentityTracker()
    private val pending = AtomicReference<PendingFrame?>(null)
    private val generation = AtomicLong(0L)
    private var lastTaskTimestampMs = -1L
    private var lastTimeoutReportMs = 0L

    val isBusy: Boolean get() = pending.get() != null

    @Volatile var latestTrackingData = FaceTrackingData(hasFace = false)
        private set
    @Volatile var latestMultiTrackingData = MultiFaceTrackingData()
        private set

    val canAcceptFrame: Boolean
        get() {
            val work = pending.get()
            if (work != null && SystemClock.uptimeMillis() - work.submittedAtUptimeMs > 1000L &&
                SystemClock.uptimeMillis() - lastTimeoutReportMs > 1000L) {
                lastTimeoutReportMs = SystemClock.uptimeMillis()
                // A retained image can belong to a prior pause/reset generation.
                // The blocked task is a current availability fault, not a result
                // from that old image; retain native ownership until its callback.
                publishEmpty(FaceTrackingStatus.ERROR)
            }
            // Do not clear busy while native inference still owns an image.
            return !permanentlyClosed && isInitialized && work == null
        }

    fun init() {
        synchronized(this) {
            if (permanentlyClosed || isInitialized || initializing) return
            initializing = true
            initializationFailed = false
        }
        try {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath("face_landmarker.task")
                    .setDelegate(Delegate.CPU).build())
                .setOutputFaceBlendshapes(true)
                .setOutputFacialTransformationMatrixes(true)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setNumFaces(4)
                .setResultListener { result: FaceLandmarkerResult, _: MPImage ->
                    val work = pending.get()
                    try {
                        if (!permanentlyClosed && work != null && work.generation == generation.get()) {
                            if (work.taskTimestampMs == result.timestampMs()) {
                                processResult(result, work.frame, work.generation)
                            } else {
                                publishEmpty(FaceTrackingStatus.ERROR, expectedGeneration = work.generation)
                            }
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Unable to process face landmarks", error)
                        if (work != null) publishEmpty(FaceTrackingStatus.ERROR, expectedGeneration = work.generation)
                    } finally {
                        releasePending(work)
                    }
                }
                .setErrorListener { error ->
                    Log.e(TAG, "MediaPipe error: ${error.message}")
                    val work = pending.get()
                    if (work != null) publishEmpty(FaceTrackingStatus.ERROR, expectedGeneration = work.generation)
                    releasePending(work)
                }.build()
            // Native initialization and disposal stay outside monitors: either can wait for callbacks.
            val created = FaceLandmarker.createFromOptions(context, options)
            val accepted = synchronized(this) {
                initializing = false
                if (permanentlyClosed) false else {
                    faceLandmarker = created
                    initializationFailed = false
                    isInitialized = true
                    true
                }
            }
            if (!accepted) created.close()
            else publishEmpty(FaceTrackingStatus.NO_FACE)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to initialize FaceLandmarker", error)
            synchronized(this) {
                initializing = false
                isInitialized = false
                initializationFailed = true
            }
            publishEmpty(FaceTrackingStatus.UNAVAILABLE)
        }
    }

    @Synchronized
    fun processFrame(frame: CameraFrame): Boolean {
        if (permanentlyClosed) return false
        val frameGeneration = generation.get()
        if (!isInitialized || faceLandmarker == null || frame.isMock) {
            publishEmpty(FaceTrackingStatus.UNAVAILABLE, frame, frameGeneration)
            return false
        }
        if (!frame.isCalibrationValid || frame.intrinsics == null || frame.cameraExtrinsics == null) {
            publishEmpty(FaceTrackingStatus.UNCALIBRATED, frame, frameGeneration)
            return false
        }
        if (!canAcceptFrame) return false
        var bitmapCopy: Bitmap? = null
        var image: MPImage? = null
        try {
            // Camera conversion reuses its bitmap; inference owns an immutable snapshot until its callback.
            bitmapCopy = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                ?: throw IllegalStateException("Unable to copy camera frame")
            image = BitmapImageBuilder(bitmapCopy).build()
            if (frameGeneration != generation.get()) {
                try { image.close() } finally { bitmapCopy.recycle() }
                return false
            }
            val taskTimestampMs = maxOf(frame.timestampMs, lastTaskTimestampMs + 1L)
            lastTaskTimestampMs = taskTimestampMs
            val work = PendingFrame(frame.copy(bitmap = bitmapCopy), image, bitmapCopy,
                taskTimestampMs, frameGeneration, SystemClock.uptimeMillis())
            pending.set(work)
            faceLandmarker!!.detectAsync(image, taskTimestampMs)
            return true
        } catch (error: Exception) {
            Log.e(TAG, "Unable to submit real camera frame", error)
            val work = pending.get()
            if (work != null) releasePending(work) else {
                image?.close()
                bitmapCopy?.recycle()
            }
            publishEmpty(FaceTrackingStatus.ERROR, frame, frameGeneration)
            return false
        }
    }

    private fun processResult(result: FaceLandmarkerResult, frame: CameraFrame, resultGeneration: Long) {
        val intrinsics = frame.intrinsics ?: return publishEmpty(FaceTrackingStatus.UNCALIBRATED, frame, resultGeneration)
        val faces = result.faceLandmarks()
        val blendshapes = result.faceBlendshapes().orElse(emptyList())
        val matrices = result.facialTransformationMatrixes().orElse(emptyList())
        val candidates = faces.mapIndexedNotNull { index, face ->
            if (face.size <= 263 || face.any { !it.x().isFinite() || !it.y().isFinite() || !it.z().isFinite() }) {
                return@mapIndexedNotNull null
            }
            val rays = CalibratedFacePose.canonicalPoints.mapNotNull { point ->
                val landmark = face[point.landmarkIndex]
                val ray = intrinsics.pixelRay(landmark.x() * frame.bitmap.width, landmark.y() * frame.bitmap.height)
                    ?: return@mapNotNull null
                if (ray.z >= -0.0001f) return@mapNotNull null
                CalibratedFacePose.Correspondence(point.landmarkIndex, ray.x / -ray.z, ray.y / -ray.z)
            }
            val pose = CalibratedFacePose.solve(rays, (frame.focalLengthX + frame.focalLengthY) * 0.5f,
                matrices.getOrNull(index)) ?: return@mapIndexedNotNull null
            val minX = face.minOf { it.x() }
            val maxX = face.maxOf { it.x() }
            val minY = face.minOf { it.y() }
            val maxY = face.maxOf { it.y() }
            val width = maxX - minX
            val height = maxY - minY
            if (width < 0.015f || height < 0.02f) return@mapIndexedNotNull null
            val observation = HeadIdentityTracker.Observation((minX + maxX) * 0.5f, (minY + maxY) * 0.5f, width, height)
            val weights = blendshapes.getOrNull(index)?.associate { category ->
                category.categoryName() to category.score().coerceIn(0f, 1f)
            } ?: emptyMap()
            Triple(observation, pose, extractEmotion(weights)) to face.map { floatArrayOf(it.x(), it.y(), it.z()) }
        }
        val data = synchronized(trackingLock) {
            // Clearing tracking and associating identities must be one ordered operation.
            if (permanentlyClosed || resultGeneration != generation.get()) return
            val ids = identityTracker.associate(candidates.map { it.first.first }, frame.timestampMs)
            val heads = candidates.mapIndexedNotNull { index, (candidate, landmarks) ->
                if (index in identityTracker.ambiguousObservations) return@mapIndexedNotNull null
                val (observation, pose, emotion) = candidate
                TrackedHeadObject(id = ids[index], faceCenterX = observation.centerX, faceCenterY = observation.centerY,
                    faceWidth = observation.width, faceHeight = observation.height,
                    headDistanceMeters = kotlin.math.sqrt(pose.x * pose.x + pose.y * pose.y + pose.z * pose.z),
                    rotationYaw = pose.yawDegrees, rotationPitch = pose.pitchDegrees, rotationRoll = pose.rollDegrees,
                    anchorX = pose.x, anchorY = pose.y, anchorZ = pose.z, orientation = pose.orientation,
                    capturedAtUptimeMs = frame.timestampMs, timestampNanos = frame.timestampNanos,
                    poseTimestampReliable = frame.poseTimestampReliable, cameraExtrinsics = frame.cameraExtrinsics,
                    reprojectionErrorPixels = pose.reprojectionErrorPixels, landmarks3D = landmarks, emotion = emotion)
            }.sortedBy { it.id }
            MultiFaceTrackingData(trackedHeads = heads, totalTrackedHeads = heads.size,
                capturedAtUptimeMs = frame.timestampMs, timestampNanos = frame.timestampNanos,
                receivedAtUptimeMs = SystemClock.uptimeMillis(), poseTimestampReliable = frame.poseTimestampReliable,
                cameraExtrinsics = frame.cameraExtrinsics,
                status = if (identityTracker.ambiguousObservations.isNotEmpty() && heads.isEmpty()) FaceTrackingStatus.AMBIGUOUS
                    else if (heads.isEmpty()) FaceTrackingStatus.NO_FACE else FaceTrackingStatus.TRACKING)
        }
        // Recheck under the state monitor; a clear may occur after the association block.
        publish(data, resultGeneration)
    }

    private fun extractEmotion(weights: Map<String, Float>): FaceEmotionState {
        fun score(name: String) = weights[name] ?: 0f
        val (expression, strength) = FaceEmotionState.classifyEmotion(score("eyeBlinkLeft"), score("eyeBlinkRight"),
            score("jawOpen"), score("mouthSmileLeft"), score("mouthSmileRight"), score("mouthFrownLeft"),
            score("mouthFrownRight"), score("mouthPucker"), score("mouthFunnel"), score("browInnerUp"),
            score("browDownLeft"), score("browDownRight"))
        return FaceEmotionState(expression, strength,
            eyeBlinkLeft = score("eyeBlinkLeft"), eyeBlinkRight = score("eyeBlinkRight"),
            eyeSquintLeft = score("eyeSquintLeft"), eyeSquintRight = score("eyeSquintRight"),
            eyeWideLeft = score("eyeWideLeft"), eyeWideRight = score("eyeWideRight"), jawOpen = score("jawOpen"),
            mouthSmileLeft = score("mouthSmileLeft"), mouthSmileRight = score("mouthSmileRight"),
            mouthFrownLeft = score("mouthFrownLeft"), mouthFrownRight = score("mouthFrownRight"),
            mouthPucker = score("mouthPucker"), mouthFunnel = score("mouthFunnel"),
            browInnerUp = score("browInnerUp"), browDownLeft = score("browDownLeft"), browDownRight = score("browDownRight"),
            browOuterUpLeft = score("browOuterUpLeft"), browOuterUpRight = score("browOuterUpRight"),
            cheekPuff = score("cheekPuff"), cheekSquintLeft = score("cheekSquintLeft"), cheekSquintRight = score("cheekSquintRight"))
    }

    /** Invalidates an in-flight result on pause/restart without freeing an image still used by native inference. */
    fun clearTracking(status: FaceTrackingStatus = FaceTrackingStatus.NO_FACE) {
        val (data, clearedGeneration) = synchronized(trackingLock) {
            val value = generation.incrementAndGet()
            identityTracker.clear()
            val data = emptyData(if (permanentlyClosed || initializationFailed) FaceTrackingStatus.UNAVAILABLE else status)
            latestMultiTrackingData = data
            latestTrackingData = data.toLegacyFaceTrackingData()
            data to value
        }
        notifyResults(data, data.toLegacyFaceTrackingData(), clearedGeneration)
    }

    private fun emptyData(status: FaceTrackingStatus, frame: CameraFrame? = null) =
        MultiFaceTrackingData(capturedAtUptimeMs = frame?.timestampMs ?: SystemClock.uptimeMillis(),
            timestampNanos = frame?.timestampNanos ?: 0L, receivedAtUptimeMs = SystemClock.uptimeMillis(),
            poseTimestampReliable = frame?.poseTimestampReliable ?: false,
            cameraExtrinsics = frame?.cameraExtrinsics, status = status)

    private fun publishEmpty(status: FaceTrackingStatus, frame: CameraFrame? = null,
        expectedGeneration: Long = generation.get()) {
        publish(emptyData(status, frame), expectedGeneration)
    }

    private fun publish(data: MultiFaceTrackingData, expectedGeneration: Long = generation.get()) {
        val legacy = data.toLegacyFaceTrackingData()
        synchronized(trackingLock) {
            if (permanentlyClosed || expectedGeneration != generation.get()) return
            latestMultiTrackingData = data
            latestTrackingData = legacy
        }
        notifyResults(data, legacy, expectedGeneration)
    }

    private fun notifyResults(data: MultiFaceTrackingData, legacy: FaceTrackingData, expectedGeneration: Long) {
        // Callers may change lifecycle state; never invoke external callbacks while holding state locks.
        if (!permanentlyClosed && expectedGeneration == generation.get()) onMultiFaceDetected?.invoke(data)
        if (!permanentlyClosed && expectedGeneration == generation.get()) onFaceDetected?.invoke(legacy)
    }

    private fun releasePending(work: PendingFrame?) {
        if (work != null && pending.compareAndSet(work, null)) {
            try { work.image.close() } finally { work.ownedBitmap.recycle() }
        }
    }

    fun close() {
        val detached = synchronized(this) {
            if (permanentlyClosed) return
            permanentlyClosed = true
            isInitialized = false
            faceLandmarker.also { faceLandmarker = null }
        }
        clearTracking(FaceTrackingStatus.UNAVAILABLE)
        try { detached?.close() } catch (error: Exception) { Log.w(TAG, "Error closing FaceLandmarker", error) }
        finally { releasePending(pending.get()) }
    }

    companion object { private const val TAG = "FaceLandmarkDetector" }
}
