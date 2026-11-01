package com.oculus.camerademo.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.DataSpace
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size

/** Opens only the OS-rendered avatar camera; does not start an OpenXR session. */
class AvatarCameraSource(context: Context) {
    @Volatile var intervalMs = 33L
    @Volatile var quality = AvatarVideoQuality.MAXIMUM
    @Volatile var inferenceOnly = false
    var onSize: ((Int,Int) -> Unit)? = null
    private val manager = context.getSystemService(CameraManager::class.java)
    // A pending open must deliver its callback even if the camera worker stops.
    private val deviceCallbacks = Handler(Looper.getMainLooper())
    private class Run(val thread: HandlerThread, val onFrame: (Bitmap, Long) -> Unit,
        val onError: (String, Boolean) -> Unit, val inferenceOnly: Boolean,
        val quality: AvatarVideoQuality) {
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var reader: ImageReader? = null
        var lastFrame = 0L
        var received = 0L
    }
    @Volatile private var active: Run? = null
    @SuppressLint("MissingPermission")
    @Synchronized fun start(onFrame: (Bitmap, Long) -> Unit, onError: (String, Boolean) -> Unit) {
        if (active != null) return
        val thread = HandlerThread("AlterEgoAvatarCamera").apply { start() }
        val run = Run(thread, onFrame, onError, inferenceOnly, quality)
        active = run
        run.handler.post {
            try {
                val cameras = manager.cameraIdList.map { id ->
                    val characteristics = manager.getCameraCharacteristics(id)
                    val key = characteristics.keys.firstOrNull { it.name == "com.meta.extra_metadata.camera_source" }
                    @Suppress("UNCHECKED_CAST")
                    val value = key?.let { characteristics.get(it as CameraCharacteristics.Key<Any>) }
                    val source = when (value) {
                        is ByteArray -> value.firstOrNull()?.toInt()
                        is IntArray -> value.firstOrNull()
                        is Number -> value.toInt()
                        else -> null
                    }
                    AvatarCameraCandidate(id, source,
                        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT)
                }
                val id = AvatarCameraPolicy.select(cameras) ?: kotlin.run {
                    fail(run,"This headset does not expose an avatar camera. Update Horizon OS or use Animate mirror.",false)
                    return@post
                }
                val characteristics = manager.getCameraCharacteristics(id)
                val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
                val size = chooseSize(sizes.toList(),run.inferenceOnly,run.quality) ?: kotlin.run {
                    fail(run,"Avatar camera has no supported video stream.",false)
                    return@post
                }
                Log.i(TAG,"Avatar camera stream sizes=${sizes.joinToString { "${it.width}x${it.height}" }}; " +
                    "inferenceOnly=${run.inferenceOnly}; selected=${size.width}x${size.height}")
                Log.i(TAG, "Opening system avatar camera: $id at $size")
                onSize?.invoke(size.width,size.height)
                val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
                run.reader = reader
                reader.setOnImageAvailableListener({ images ->
                    val image = runCatching { images.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        val now = SystemClock.uptimeMillis()
                        if (active !== run || now - run.lastFrame < intervalMs) return@setOnImageAvailableListener
                        run.lastFrame = now
                        val bitmap = toBitmap(image)
                        if (active === run) {
                            run.onFrame(bitmap, now)
                            run.received++
                            if (run.received == 1L || run.received % 150L == 0L)
                                Log.i(TAG, "Avatar camera frames=${run.received}, size=${bitmap.width}x${bitmap.height}")
                        } else bitmap.recycle()
                    } catch (error: Exception) { fail(run, "Avatar preview failed: ${error.message}") }
                    finally { image.close() }
                }, run.handler)
                if (active !== run) return@post
                manager.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (active !== run) { device.close(); return }
                        run.device = device
                        try {
                        @Suppress("DEPRECATION")
                        device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                if (active !== run) { session.close(); return }
                                run.session = session
                                try {
                                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                        addTarget(reader.surface)
                                        set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                    }.build()
                                    session.setRepeatingRequest(request, null, run.handler)
                                    Log.i(TAG, "System avatar camera opened: $id at $size")
                                } catch (error: Exception) { fail(run, "Could not start avatar camera: ${error.message}") }
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                session.close(); fail(run, "Horizon OS rejected the avatar camera stream. Choose Retry camera.")
                            }
                        }, run.handler)
                        } catch (error: Exception) { fail(run,"Could not configure avatar camera: ${error.message}") }
                    }
                    override fun onDisconnected(device: CameraDevice) {
                        device.close(); fail(run, "Avatar camera disconnected. Choose Retry camera.")
                    }
                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        fail(run, when (error) {
                            ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> "Avatar camera is in use. Close other camera apps, then retry."
                            ERROR_CAMERA_DISABLED -> "Avatar camera is disabled in headset settings."
                            else -> "Avatar camera failed ($error). Choose Retry camera."
                        },error != ERROR_CAMERA_DISABLED)
                    }
                }, deviceCallbacks)
            } catch (_: SecurityException) { fail(run, "Allow avatar camera access, then choose Retry camera.",false) }
            catch (error: Exception) { fail(run, error.message ?: "Avatar camera unavailable") }
        }
    }

    private fun chooseSize(sizes: List<Size>,inferenceOnly: Boolean,quality: AvatarVideoQuality): Size? {
        val available = sizes.map { AvatarVideoSize(it.width,it.height) }
        val selected = if (inferenceOnly) AvatarVideoResolution.chooseInference(available)
            else AvatarVideoResolution.choose(available,quality)
        return selected?.let { Size(it.width,it.height) }
    }

    private fun toBitmap(image: Image): Bitmap {
        val crop = image.cropRect
        val bitmap = Bitmap.createBitmap(crop.width(),crop.height(),Bitmap.Config.ARGB_8888)
        val planes = image.planes
        val dataSpace = if (android.os.Build.VERSION.SDK_INT>=33) image.dataSpace else 0
        val colorMode = if (android.os.Build.VERSION.SDK_INT>=33)
            (if (DataSpace.getRange(dataSpace)==DataSpace.RANGE_FULL) 1 else 0) or
            (if (DataSpace.getStandard(dataSpace)==DataSpace.STANDARD_BT709) 2 else 0) else 0
        val ok = NativeAvatarPixels.convert(bitmap,
            planes[0].buffer.slice(),planes[1].buffer.slice(),planes[2].buffer.slice(),
            planes[0].rowStride,planes[0].pixelStride,planes[1].rowStride,planes[1].pixelStride,
            planes[2].rowStride,planes[2].pixelStride,crop.left,crop.top,colorMode)
        if (!ok) { bitmap.recycle(); error("Avatar camera supplied an invalid YUV layout") }
        return bitmap
    }

    private fun fail(run: Run, message: String, recoverable: Boolean = true) {
        // Detach only the run that reported this failure. An old callback must
        // never close a replacement camera opened concurrently with teardown.
        synchronized(this) {
            if (active !== run) return
            active = null
        }
        Log.w(TAG, message)
        dispose(run)
        // The receiver may acquire its own lifecycle lock. Do not call it while
        // holding the camera source lock used by start/stop.
        run.onError(message,recoverable)
    }

    fun stop() {
        val run = synchronized(this) {
            val current = active ?: return
            active = null
            current
        }
        dispose(run)
    }

    private fun dispose(run: Run) {
        run.handler.post {
            runCatching { run.session?.stopRepeating() }
            runCatching { run.session?.close() }
            runCatching { run.device?.close() }
            runCatching { run.reader?.setOnImageAvailableListener(null, null); run.reader?.close() }
            run.thread.quitSafely()
        }
    }
    companion object { private const val TAG = "AlterEgoAvatarCamera" }
}
