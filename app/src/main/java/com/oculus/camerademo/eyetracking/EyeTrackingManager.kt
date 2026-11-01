package com.oculus.camerademo.eyetracking

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.meta.spatial.core.Pose
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene

enum class EyeTrackingDeviceStatus {
    READY,
    NO_HARDWARE,
    PERMISSION_DENIED,
    TRACKING_UNAVAILABLE,
    RUNTIME_UNAVAILABLE,
}

data class EyeGazeData(
    val gazeX: Float = 0.5f,
    val gazeY: Float = 0.5f,
    val foveaRadius: Float = 0.20f,
    val isFaceLocked: Boolean = false,
    val dwellTimeMs: Long = 0L,
    val status: EyeTrackingDeviceStatus = EyeTrackingDeviceStatus.NO_HARDWARE,
    val isValid: Boolean = false,
    val worldRay: GazeRay? = null,
    val candidateHeadId: Int? = null,
    val lockedHeadId: Int? = null,
)

/** Reads physical eye sensors through the SDK's existing OpenXR session on every scene tick. */
class EyeTrackingManager(private val context: Context) {
    @Volatile var gazeData = EyeGazeData()
        private set
    var onGazeUpdated: ((EyeGazeData) -> Unit)? = null
    var isRunning = false
        private set

    private var foveaRadius = 0.20f
    private var nativeTracker = 0L
    private var nativeSession = 0L
    private val selector = GazeTargetSelector()
    private var attemptedSession = 0L
    private var nextAttemptMs = 0L
    private var loggedValidSample = false
    private var nextPermissionCheckMs = 0L

    val isQuestProDevice: Boolean get() = isQuestPro()

    /** New Horizon OS uses the horizonos namespace; older Quest Pro releases use com.oculus. */
    val requiredPermissions: List<String> by lazy {
        if (!hasEyeTrackingHardware(context)) emptyList() else listOf(MODERN_PERMISSION, LEGACY_PERMISSION).filter { permission ->
            try {
                context.packageManager.getPermissionInfo(permission, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }.ifEmpty { listOf(LEGACY_PERMISSION) }
    }

    val isPermissionGranted: Boolean
        get() = requiredPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    fun checkDeviceCompatibility(): EyeTrackingDeviceStatus = when {
        !hasEyeTrackingHardware(context) -> EyeTrackingDeviceStatus.NO_HARDWARE
        !isPermissionGranted -> EyeTrackingDeviceStatus.PERMISSION_DENIED
        else -> EyeTrackingDeviceStatus.READY
    }

    @Synchronized fun start(): EyeTrackingDeviceStatus {
        releaseTracker()
        attemptedSession = 0L
        loggedValidSample = false
        val status = checkDeviceCompatibility()
        nextPermissionCheckMs = SystemClock.uptimeMillis() + 1000L
        isRunning = status == EyeTrackingDeviceStatus.READY
        invalidate(if (isRunning) EyeTrackingDeviceStatus.TRACKING_UNAVAILABLE else status)
        return status
    }

    @Synchronized fun stop() {
        isRunning = false
        releaseTracker()
        attemptedSession = 0L
        invalidate(EyeTrackingDeviceStatus.TRACKING_UNAVAILABLE)
    }

    fun close() = stop()

    fun setFoveaRadius(radius: Float) {
        if (radius.isFinite()) foveaRadius = radius.coerceIn(0.10f, 0.40f)
    }

    /** Call after the scene is ready and once per onSceneTick, before evaluating world targets. */
    @OptIn(SpatialSDKExperimentalAPI::class)
    @Synchronized fun updateFromScene(scene: Scene) {
        if (!isRunning) return
        val nowMs = SystemClock.uptimeMillis()
        // Permission discovery is stable for this installation. Refresh grants
        // periodically and on start/resume, rather than doing package-manager
        // and permission IPC on every 90 Hz interaction frame.
        if (nowMs >= nextPermissionCheckMs) {
            nextPermissionCheckMs = nowMs + 1000L
            if (!isPermissionGranted) {
                isRunning = false
                releaseTracker()
                invalidate(EyeTrackingDeviceStatus.PERMISSION_DENIED)
                return
            }
        }
        val session = scene.getOpenXrSessionHandle()
        if (session == 0L) {
            invalidate(EyeTrackingDeviceStatus.TRACKING_UNAVAILABLE)
            return
        }
        if (nativeSession != session) {
            releaseTracker()
            val now = SystemClock.uptimeMillis()
            if (attemptedSession == session && now < nextAttemptMs) {
                invalidate(EyeTrackingDeviceStatus.RUNTIME_UNAVAILABLE)
                return
            }
            attemptedSession = session
            nextAttemptMs = now + 2000L
            nativeTracker = try {
                NativeEyeGazeBridge.create(scene.getOpenXrInstanceHandle(), session, scene.getOpenXrGetInstanceProcAddrHandle())
            } catch (error: UnsatisfiedLinkError) {
                Log.e(TAG, "Eye tracking native library is unavailable", error)
                0L
            }
            if (nativeTracker == 0L) {
                invalidate(EyeTrackingDeviceStatus.RUNTIME_UNAVAILABLE)
                return
            }
            nativeSession = session
        }
        val sample = NativeEyeGazeBridge.sample(nativeTracker)
        if (sample == null || sample.size != 7 || sample.any { !it.isFinite() }) {
            invalidate(EyeTrackingDeviceStatus.TRACKING_UNAVAILABLE)
            return
        }
        // OpenXR VIEW is right handed with -Z forward; Spatial SDK uses +Z forward.
        val viewer = scene.getViewerPose()
        val worldOrigin = viewer * Vector3(sample[0], sample[1], -sample[2])
        val worldDirection = viewer.q * Vector3(sample[3], sample[4], -sample[5])
        val direction = GazeVector(worldDirection.x, worldDirection.y, worldDirection.z).normalized()
        if (direction == null || !worldOrigin.x.isFinite() || !worldOrigin.y.isFinite() || !worldOrigin.z.isFinite()) {
            invalidate(EyeTrackingDeviceStatus.TRACKING_UNAVAILABLE)
            return
        }
        if (!loggedValidSample) {
            Log.i(TAG, "Valid hardware eye gaze acquired from OpenXR sensors")
            loggedValidSample = true
        }
        gazeData = gazeData.copy(
            isValid = true,
            worldRay = GazeRay(GazeVector(worldOrigin.x, worldOrigin.y, worldOrigin.z), direction, SystemClock.uptimeMillis()),
            status = EyeTrackingDeviceStatus.READY,
            foveaRadius = foveaRadius,
        )
        onGazeUpdated?.invoke(gazeData)
    }

    /** Targets must use the same world pose and radii as the actual rendered heads. */
    fun evaluateWorldHeadsGaze(targets: List<GazeHeadTarget>): Int? {
        val result = selector.select(if (isRunning && gazeData.isValid) gazeData.worldRay else null, targets, SystemClock.uptimeMillis())
        gazeData = gazeData.copy(
            candidateHeadId = result.candidateId,
            lockedHeadId = result.lockedId,
            isFaceLocked = result.lockedId != null,
            dwellTimeMs = result.dwellTimeMs,
        )
        onGazeUpdated?.invoke(gazeData)
        return result.lockedId
    }

    /** Normalized panel coordinates from a real world ray, or null when not looking at the panel. */
    fun projectOnPanel(pose: Pose, widthMeters: Float, heightMeters: Float): Pair<Float, Float>? {
        if (!isRunning || !gazeData.isValid || widthMeters <= 0f || heightMeters <= 0f) return null
        val ray = gazeData.worldRay ?: return null
        if (SystemClock.uptimeMillis() - ray.sampledAtMs > 150L) return null
        val localOrigin = pose.inverse() * Vector3(ray.origin.x, ray.origin.y, ray.origin.z)
        val localDirection = pose.q.inverse() * Vector3(ray.direction.x, ray.direction.y, ray.direction.z)
        if (kotlin.math.abs(localDirection.z) < 0.00001f) return null
        val distance = -localOrigin.z / localDirection.z
        if (!distance.isFinite() || distance < 0f) return null
        val x = 0.5f + (localOrigin.x + localDirection.x * distance) / widthMeters
        val y = 0.5f - (localOrigin.y + localDirection.y * distance) / heightMeters
        return if (x in 0f..1f && y in 0f..1f) x to y else null
    }

    private fun releaseTracker() {
        if (nativeTracker != 0L) NativeEyeGazeBridge.destroy(nativeTracker)
        nativeTracker = 0L
        nativeSession = 0L
    }

    private fun invalidate(status: EyeTrackingDeviceStatus) {
        selector.reset()
        gazeData = EyeGazeData(status = status, foveaRadius = foveaRadius)
        onGazeUpdated?.invoke(gazeData)
    }

    companion object {
        private const val TAG = "EyeTrackingManager"
        const val MODERN_PERMISSION = "horizonos.permission.EYE_TRACKING"
        const val LEGACY_PERMISSION = "com.oculus.permission.EYE_TRACKING"
        val OPENXR_EXTENSIONS = listOf("XR_FB_eye_tracking_social", "XR_KHR_convert_timespec_time")

        fun hasEyeTrackingHardware(context: Context): Boolean =
            context.packageManager.hasSystemFeature("oculus.software.eye_tracking") || isQuestPro()

        private fun isQuestPro(): Boolean = listOf(Build.MODEL, Build.DEVICE, Build.PRODUCT, Build.HARDWARE)
            .any { value -> value.contains("quest pro", ignoreCase = true) || value.contains("cambria", ignoreCase = true) }
    }
}
