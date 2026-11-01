package com.oculus.camerademo.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import com.oculus.camerademo.CAMERA_SOURCE_PASSTHROUGH
import com.oculus.camerademo.KEY_POSITION
import com.oculus.camerademo.KEY_SOURCE
import java.util.concurrent.Executor
import kotlin.math.abs

/** Pixels live only during the source callback; async consumers must copy them synchronously. */
data class CameraFrame(
    val bitmap: Bitmap,
    /** Exposure uptime when reliable, otherwise delivery uptime. */
    val timestampMs: Long,
    val focalLengthX: Float = Float.NaN,
    val focalLengthY: Float = Float.NaN,
    val principalPointX: Float = Float.NaN,
    val principalPointY: Float = Float.NaN,
    val isMock: Boolean = false,
    val intrinsics: CameraIntrinsics? = null,
    val cameraExtrinsics: CameraExtrinsics? = null,
    val timestampNanos: Long = 0L,
    val sensorTimestampSource: Int = CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN,
    val poseTimestampReliable: Boolean = false,
    val cameraId: String? = null,
    val isCalibrationValid: Boolean = false
)

enum class CameraSourceErrorCode {
    UNSUPPORTED_DEVICE, PERMISSION_DENIED, CAMERA_UNAVAILABLE, CALIBRATION_UNAVAILABLE,
    STREAM_UNAVAILABLE, DISCONNECTED, CAMERA_IN_USE, CAMERA_DISABLED, CAMERA_FAILURE
}
data class CameraSourceError(val code: CameraSourceErrorCode, val message: String)

interface CameraSource {
    val name: String
    val isRunning: Boolean
    fun start(onFrame: (CameraFrame) -> Unit)
    fun stop()
}

/** A missing camera is an explicit error, never a simulated person. */
class QuestPassthroughCameraSource(
    private val context: Context,
    private val targetWidth: Int = 1280,
    private val targetHeight: Int = 960
) : CameraSource {
    override val name = "Quest Passthrough Camera"
    override val isRunning: Boolean get() = activeRun != null
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    // Device callbacks outlive the per-run worker so a late open can always be closed.
    private val deviceCallbackHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeRun: CameraRun? = null
    @Volatile var lastError: CameraSourceError? = null
        private set
    var onError: ((CameraSourceError) -> Unit)? = null
    var canAcceptFrame: (() -> Boolean)? = null

    private class CameraRun(val id: String, val characteristics: CameraCharacteristics,
                            val size: Size, val onFrame: (CameraFrame) -> Unit) {
        val thread = HandlerThread("QuestCameraThread").apply { start() }
        val handler = Handler(thread.looper)
        val executor = Executor { task -> handler.post(task); Unit }
        var reader: ImageReader? = null
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        // Confined to handler; metadata is paired to its exact image timestamp.
        val results = LinkedHashMap<Long, TotalCaptureResult>()
        val pendingImages = LinkedHashMap<Long, Image>()
        var receivedFrames = 0L
    }

    fun isPassthroughAvailable(): Boolean = availabilityError() == null
    fun availabilityError(): CameraSourceError? {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(HEADSET_CAMERA_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            return CameraSourceError(CameraSourceErrorCode.PERMISSION_DENIED,
                "Allow Camera or Headset Camera access to detect people.")
        }
        return try {
            // Compatibility mode can report an older headset model. Real camera capabilities
            // decide availability; the reported model only helps explain a missing camera.
            if (findPassthroughCameraId() != null) {
                null
            } else if (Build.DEVICE.equals("seacliff", true) || Build.MODEL.contains("Quest Pro", true)) {
                CameraSourceError(CameraSourceErrorCode.UNSUPPORTED_DEVICE,
                    "No supported outward camera is exposed by this headset. Quest Pro does not provide its outward images through Meta's Passthrough Camera API, so live face replacement cannot start.")
            } else {
                CameraSourceError(CameraSourceErrorCode.CAMERA_UNAVAILABLE,
                    "No supported outward passthrough camera is available on this device or Horizon OS version.")
            }
        } catch (_: SecurityException) {
            CameraSourceError(CameraSourceErrorCode.PERMISSION_DENIED,
                "Camera access was denied. Allow Camera or Headset Camera access to continue.")
        } catch (error: Exception) {
            CameraSourceError(CameraSourceErrorCode.CAMERA_FAILURE, "Camera discovery failed: ${error.message}")
        }
    }

    @Synchronized override fun start(onFrame: (CameraFrame) -> Unit) {
        if (isRunning) return
        lastError = null
        availabilityError()?.let { reportError(it); return }
        try {
            val id = findPassthroughCameraId() ?: return reportError(CameraSourceError(
                CameraSourceErrorCode.CAMERA_UNAVAILABLE, "The passthrough camera is unavailable."))
            val characteristics = manager.getCameraCharacteristics(id)
            val size = selectResolution(characteristics) ?: return reportError(CameraSourceError(
                CameraSourceErrorCode.STREAM_UNAVAILABLE, "The camera has no supported YUV image stream."))
            val calibration = characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            if (calibration == null || calibration.size < 5 || calibration[0] <= 0f ||
                calibration[1] <= 0f || readExtrinsics(characteristics) == null) {
                return reportError(CameraSourceError(CameraSourceErrorCode.CALIBRATION_UNAVAILABLE,
                    "This camera does not provide calibrated intrinsics and a headset-relative lens pose. Accurate overlays cannot start."))
            }
            val run = CameraRun(id, characteristics, size, onFrame)
            activeRun = run
            run.handler.post {
                if (activeRun !== run) return@post
                try {
                    run.reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).apply {
                        setOnImageAvailableListener({ reader -> onImageAvailable(run, reader) }, run.handler)
                    }
                    openCamera(run)
                    run.handler.postDelayed({
                        if (activeRun === run && run.receivedFrames == 0L) {
                            fail(run, CameraSourceErrorCode.STREAM_UNAVAILABLE,
                                "The headset camera did not deliver image frames. Check camera access and resume the app to retry.")
                        }
                    }, 8000L)
                } catch (error: Exception) {
                    fail(run, CameraSourceErrorCode.CAMERA_FAILURE, "Unable to start camera images: ${error.message}")
                }
            }
        } catch (_: SecurityException) {
            reportError(CameraSourceError(CameraSourceErrorCode.PERMISSION_DENIED, "Headset camera permission was denied."))
        } catch (error: Exception) {
            reportError(CameraSourceError(CameraSourceErrorCode.CAMERA_FAILURE, "Unable to open headset camera: ${error.message}"))
        }
    }

    /** Meta documents source=0 and position=0/1; no first-camera or facing fallback. */
    private fun findPassthroughCameraId(): String? = manager.cameraIdList.mapNotNull { id ->
        val characteristics = manager.getCameraCharacteristics(id)
        val source = try { characteristics.get(KEY_SOURCE) } catch (_: IllegalArgumentException) { null }
        val position = try { characteristics.get(KEY_POSITION) } catch (_: IllegalArgumentException) { null }
        if (source == CAMERA_SOURCE_PASSTHROUGH && (position == 0 || position == 1)) id to position else null
    }.sortedBy { it.second }.firstOrNull()?.first

    private fun selectResolution(characteristics: CameraCharacteristics): Size? {
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val aspect = targetWidth.toFloat() / targetHeight
        return sizes.minByOrNull { size ->
            abs(size.width.toFloat() / size.height - aspect) * 5000f +
                abs(size.width.toFloat() * size.height - targetWidth.toFloat() * targetHeight) /
                (targetWidth.toFloat() * targetHeight) * 1000f
        }
    }

    private fun readExtrinsics(characteristics: CameraCharacteristics): CameraExtrinsics? {
        if (characteristics.get(CameraCharacteristics.LENS_POSE_REFERENCE) == CameraMetadata.LENS_POSE_REFERENCE_UNDEFINED) return null
        return CameraExtrinsics.fromAndroidPose(characteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION),
            characteristics.get(CameraCharacteristics.LENS_POSE_ROTATION))
    }

    private fun openCamera(run: CameraRun) {
        try {
            manager.openCamera(run.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    synchronized(this@QuestPassthroughCameraSource) {
                        if (activeRun !== run) { camera.close(); return }
                        run.device = camera
                        run.handler.post { if (activeRun === run) createSession(run) }
                    }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    fail(run, CameraSourceErrorCode.DISCONNECTED, "The camera disconnected. Resume the app to retry.")
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    val (code, message) = when (error) {
                        ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> CameraSourceErrorCode.CAMERA_IN_USE to "The camera is in use by another app. Close it and retry."
                        ERROR_CAMERA_DISABLED -> CameraSourceErrorCode.CAMERA_DISABLED to "Camera access is disabled by headset privacy or device settings."
                        else -> CameraSourceErrorCode.CAMERA_FAILURE to "The headset camera failed (error $error). Resume the app to retry."
                    }
                    fail(run, code, message)
                }
            }, deviceCallbackHandler)
        } catch (_: SecurityException) {
            fail(run, CameraSourceErrorCode.PERMISSION_DENIED, "Headset camera permission was denied.")
        } catch (error: Exception) {
            fail(run, CameraSourceErrorCode.CAMERA_FAILURE, "Unable to open camera: ${error.message}")
        }
    }

    private fun createSession(run: CameraRun) {
        val camera = run.device ?: return
        val reader = run.reader ?: return
        try {
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(reader.surface)), run.executor, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (activeRun !== run) { session.close(); return }
                        run.session = session
                        try {
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                                set(CaptureRequest.SCALER_ROTATE_AND_CROP, CameraMetadata.SCALER_ROTATE_AND_CROP_NONE)
                                val corrections = run.characteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                                if (corrections?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) == true) {
                                    set(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
                                }
                            }
                            session.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                    if (activeRun !== run) return
                                    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                                    val image = run.pendingImages.remove(timestamp)
                                    if (image != null) deliver(run, image, result) else {
                                        run.results[timestamp] = result
                                        while (run.results.size > 8) run.results.remove(run.results.keys.first())
                                    }
                                }
                            }, run.handler)
                            Log.i(TAG, "Calibrated camera ${run.id} started at ${run.size}")
                        } catch (error: Exception) {
                            fail(run, CameraSourceErrorCode.STREAM_UNAVAILABLE, "Unable to start stream: ${error.message}")
                        }
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        session.close()
                        fail(run, CameraSourceErrorCode.STREAM_UNAVAILABLE, "The headset rejected the camera configuration.")
                    }
                }))
        } catch (error: Exception) {
            fail(run, CameraSourceErrorCode.STREAM_UNAVAILABLE, "Unable to configure camera: ${error.message}")
        }
    }

    private fun onImageAvailable(run: CameraRun, reader: ImageReader) {
        val image = try { reader.acquireLatestImage() } catch (_: IllegalStateException) { null } ?: return
        run.receivedFrames++
        if (activeRun !== run || canAcceptFrame?.invoke() == false) { image.close(); return }
        // Keep at most one image awaiting metadata; prefer recent pixels over queued latency.
        run.pendingImages.values.forEach { it.close() }
        run.pendingImages.clear()
        val result = run.results.remove(image.timestamp)
        if (result != null) deliver(run, image, result) else run.pendingImages[image.timestamp] = image
    }

    private fun deliver(run: CameraRun, image: Image, result: TotalCaptureResult) {
        try {
            if (activeRun !== run || canAcceptFrame?.invoke() == false) return
            val frame = processYuvImage(run, image, result)
            if (!frame.poseTimestampReliable) {
                frame.bitmap.recycle()
                fail(run, CameraSourceErrorCode.CALIBRATION_UNAVAILABLE,
                    "Camera exposure timestamps cannot be synchronized with headset movement. This camera must report a reliable realtime timestamp before accurate overlays can start.")
                return
            }
            if (!frame.isCalibrationValid) {
                frame.bitmap.recycle()
                fail(run, CameraSourceErrorCode.CALIBRATION_UNAVAILABLE, "Camera frame calibration is unavailable. Face overlays have stopped.")
                return
            }
            try {
                run.onFrame(frame)
            } finally { frame.bitmap.recycle() }
        } catch (error: Exception) {
            fail(run, CameraSourceErrorCode.CAMERA_FAILURE, "Unable to process camera image: ${error.message}")
        } finally { image.close() }
    }

    private fun processYuvImage(run: CameraRun, image: Image, result: TotalCaptureResult): CameraFrame {
        val step = if (image.width > 800) 2 else 1
        val imageCrop = image.cropRect
        val width = imageCrop.width() / step
        val height = imageCrop.height() / step
        val colors = IntArray(width * height)
        fun sample(plane: Image.Plane, x: Int, y: Int): Int =
            plane.buffer.get(y * plane.rowStride + x * plane.pixelStride).toInt() and 255
        for (y in 0 until height) for (x in 0 until width) {
            val sx = imageCrop.left + x * step
            val sy = imageCrop.top + y * step
            val luma = (sample(image.planes[0], sx, sy) - 16).coerceAtLeast(0) * 1.164f
            val u = sample(image.planes[1], sx / 2, sy / 2) - 128
            val v = sample(image.planes[2], sx / 2, sy / 2) - 128
            val red = (luma + 1.596f * v).toInt().coerceIn(0, 255)
            val green = (luma - 0.392f * u - 0.813f * v).toInt().coerceIn(0, 255)
            val blue = (luma + 2.017f * u).toInt().coerceIn(0, 255)
            colors[y * width + x] = (255 shl 24) or (red shl 16) or (green shl 8) or blue
        }
        val bitmap = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
        val characteristics = run.characteristics
        val pre = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            ?: characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val calibration = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
            ?: characteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
        val requestedCrop = result.get(CaptureResult.SCALER_CROP_REGION) ?: pre
        val correction = result.get(CaptureResult.DISTORTION_CORRECTION_MODE)
        val distortion = if (correction == null || correction == CameraMetadata.DISTORTION_CORRECTION_MODE_OFF)
            (result.get(CaptureResult.LENS_DISTORTION) ?: characteristics.get(CameraCharacteristics.LENS_DISTORTION))?.toList().orEmpty()
        else emptyList()
        val sensorCrop = requestedCrop?.let { centerCrop(it, image.width, image.height) }?.let { crop ->
            SensorCrop(crop.left + imageCrop.left * crop.width / image.width,
                crop.top + imageCrop.top * crop.height / image.height,
                imageCrop.width() * crop.width / image.width, imageCrop.height() * crop.height / image.height)
        }
        val intrinsics = if (calibration != null && pre != null && sensorCrop != null)
            CameraIntrinsics.fromSensorCalibration(calibration, pre.left, pre.top, sensorCrop, width, height, distortion)
        else null
        val extrinsics = readExtrinsics(characteristics)
        val source = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
            ?: CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN
        val nowUptime = SystemClock.uptimeMillis()
        val ageMs = (SystemClock.elapsedRealtimeNanos() - image.timestamp) / 1_000_000L
        val reliable = source == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME && ageMs in 0..1000
        return CameraFrame(bitmap, if (reliable) nowUptime - ageMs else nowUptime,
            intrinsics?.focalLengthX ?: Float.NaN, intrinsics?.focalLengthY ?: Float.NaN,
            intrinsics?.principalPointX ?: Float.NaN, intrinsics?.principalPointY ?: Float.NaN,
            intrinsics = intrinsics, cameraExtrinsics = extrinsics, timestampNanos = image.timestamp,
            sensorTimestampSource = source, poseTimestampReliable = reliable, cameraId = run.id,
            isCalibrationValid = intrinsics?.isValid == true && extrinsics != null)
    }

    private fun centerCrop(rect: Rect, width: Int, height: Int): SensorCrop {
        val aspect = width.toFloat() / height
        val wider = rect.width().toFloat() / rect.height() > aspect
        val croppedWidth = if (wider) rect.height() * aspect else rect.width().toFloat()
        val croppedHeight = if (wider) rect.height().toFloat() else rect.width() / aspect
        return SensorCrop(rect.left + (rect.width() - croppedWidth) / 2f,
            rect.top + (rect.height() - croppedHeight) / 2f, croppedWidth, croppedHeight)
    }

    private fun reportError(error: CameraSourceError) {
        lastError = error
        Log.e(TAG, "${error.code}: ${error.message}")
        onError?.invoke(error)
    }
    @Synchronized private fun fail(run: CameraRun, code: CameraSourceErrorCode, message: String) {
        if (activeRun !== run) return
        stop()
        reportError(CameraSourceError(code, message))
    }
    @Synchronized override fun stop() {
        val run = activeRun ?: return
        activeRun = null
        run.handler.post {
            try {
                run.pendingImages.values.forEach { it.close() }
                run.pendingImages.clear()
                run.results.clear()
                try { run.session?.stopRepeating() } catch (_: Exception) { }
                try { run.session?.close() } catch (_: Exception) { }
                try { run.device?.close() } catch (_: Exception) { }
                try {
                    run.reader?.setOnImageAvailableListener(null, null)
                    run.reader?.close()
                } catch (_: Exception) { }
            } finally { run.thread.quitSafely() }
        }
    }
    companion object {
        private const val TAG = "QuestPassthroughCamera"
        const val HEADSET_CAMERA_PERMISSION = "horizonos.permission.HEADSET_CAMERA"
    }
}
