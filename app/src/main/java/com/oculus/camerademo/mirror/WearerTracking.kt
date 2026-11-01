package com.oculus.camerademo.mirror

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.Scene
import com.oculus.camerademo.ml.FaceEmotionState

internal object NativeWearerBridge {
    init { System.loadLibrary("alterego_eye_gaze") }
    external fun supportedExtensions(): Array<String>
    external fun create(instance: Long, session: Long, loader: Long, body: Boolean, face: Boolean, hands: Boolean): Long
    external fun sample(handle: Long): FloatArray?
    external fun destroy(handle: Long)
}

data class WearerJoint(val position: Vector3, val orientation: Quaternion = Quaternion())
data class WearerSnapshot(
    val sampledAtMs: Long = 0,
    val head: WearerJoint? = null,
    val body: List<WearerJoint?> = List(18) { null },
    val hands: List<List<WearerJoint?>> = List(2) { List(26) { null } },
    val expression: FaceEmotionState = FaceEmotionState(),
    val hasPhysicalFace: Boolean = false,
    val hasBody: Boolean = false,
    val hasEyes: Boolean = false,
    val gazeX: Float = 0f,
    val gazeY: Float = 0f,
    val message: String = "Waiting for the wearer tracking session",
    // Private, ephemeral SDK input. The LAN sender only accepts rendered pixels.
    internal val nativePacket: FloatArray? = null,
    // A renderable neutral head is not an observed face. Keep this separate
    // from physical tracking and the sample renderer's head placeholder.
    val hasVideoFace: Boolean = false,
    // Video channels can complete independently. Their acquisition times are
    // private provenance, never refreshed by a later channel or rendered frame.
    internal val videoChannels: AvatarVideoChannels? = null
) {
    fun isFresh(now: Long) = head != null && now >= sampledAtMs && now - sampledAtMs <= 150
}

/** Deliberately separate from outward-camera detections of other people. */
object WearerPacketDecoder {
    fun decode(p: FloatArray?, now: Long): WearerSnapshot {
        if (p == null || p.size !in listOf(434, 642, 1060) || p.any { !it.isFinite() }) return WearerSnapshot()
        fun joint(at: Int): WearerJoint? {
            val q = Quaternion(p[at + 6], -p[at + 3], -p[at + 4], p[at + 5])
            val norm = p.sliceArray(at + 3..at + 6).sumOf { (it * it).toDouble() }
            if (norm < 0.5 || norm > 1.5) return null
            return WearerJoint(Vector3(p[at], p[at + 1], -p[at + 2]), q)
        }
        val head = if (p[0] == 1f) joint(5) else null
        val body = List(18) { n -> if (p[1] == 1f && p[12 + n * 8] == 1f) joint(13 + n * 8) else null }
        val hands = List(2) { h -> List(26) { n ->
            val at = 156 + h * 104 + n * 4
            if (p[3 + h] == 1f && p[at] == 1f) {
                val q = 434 + h * 104 + n * 4
                val orientation = if (p.size >= 642) Quaternion(p[q + 3], -p[q], -p[q + 1], p[q + 2]) else Quaternion()
                WearerJoint(Vector3(p[at + 1], p[at + 2], -p[at + 3]), orientation)
            } else null
        } }
        val face = p[2] == 1f
        fun weight(n: Int) = if (face) p[364 + n].coerceIn(0f, 1f) else 0f
        val expression = FaceEmotionState(eyeBlinkLeft = weight(12), eyeBlinkRight = weight(13),
            jawOpen = weight(24), mouthSmileLeft = weight(32), mouthSmileRight = weight(33),
            browInnerUp = (weight(22) + weight(23)) * .5f)
        return WearerSnapshot(now, head, body, hands, expression, face, body.any { it != null },
            message = if (head == null) "Wearer tracking is paused" else "Live wearer tracking", nativePacket = p)
    }
}

class WearerTrackingManager(private val context: Context) : AutoCloseable {
    private var tracker = 0L
    private var session = 0L
    private var attemptedSession = 0L
    private var permissionKey = ""
    private var lastStatus = ""
    private var nextAttemptMs = 0L

    val requiredPermissions: List<String> get() = listOf("com.oculus.permission.BODY_TRACKING", "com.oculus.permission.FACE_TRACKING")
        .filter { try { context.packageManager.getPermissionInfo(it, 0); true } catch (_: PackageManager.NameNotFoundException) { false } }
    private fun allowed(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @OptIn(SpatialSDKExperimentalAPI::class)
    @Synchronized
    fun update(scene: Scene): WearerSnapshot {
        val current = scene.getOpenXrSessionHandle()
        val key = "${allowed("com.oculus.permission.BODY_TRACKING")}:${allowed("com.oculus.permission.FACE_TRACKING")}:${allowed("com.oculus.permission.HAND_TRACKING")}"
        if (current == 0L) return reportStatus(WearerSnapshot(message = "Wearer tracking session is unavailable"), "session_unavailable")
        if (current != session || key != permissionKey) {
            val now = SystemClock.uptimeMillis()
            if (current == attemptedSession && key == permissionKey && now < nextAttemptMs)
                return reportStatus(WearerSnapshot(message = "Connecting wearer sensors…"), "retry_wait")
            close()
            attemptedSession = current; permissionKey = key
            nextAttemptMs = now + 2000L
            tracker = NativeWearerBridge.create(scene.getOpenXrInstanceHandle(), current,
                scene.getOpenXrGetInstanceProcAddrHandle(), allowed("com.oculus.permission.BODY_TRACKING"),
                allowed("com.oculus.permission.FACE_TRACKING"), allowed("com.oculus.permission.HAND_TRACKING"))
            if (tracker == 0L) return reportStatus(WearerSnapshot(message = "Wearer sensors are unavailable in this session"), "create_failed")
            session = current
        }
        val sample = WearerPacketDecoder.decode(NativeWearerBridge.sample(tracker), SystemClock.uptimeMillis())
        return reportStatus(sample, "sample")
    }
    private fun reportStatus(sample: WearerSnapshot, phase: String): WearerSnapshot {
        // Coarse transitions also explain early returns; no sensor values or
        // OpenXR instance/session identities are written to diagnostics.
        val status = "Wearer phase=$phase; head=${sample.head != null}, body=${sample.hasBody}, face=${sample.hasPhysicalFace}, hands=${sample.hands.map { h -> h.any { it != null } }}"
        if (status != lastStatus) { Log.i("AlterEgoWearer", status); lastStatus = status }
        return sample
    }
    @Synchronized
    override fun close() { if (tracker != 0L) NativeWearerBridge.destroy(tracker); tracker = 0; session = 0; attemptedSession = 0 }

    companion object {
        fun supportedExtensions(): List<String> {
            val available = try { NativeWearerBridge.supportedExtensions().toSet() }
                catch (error: UnsatisfiedLinkError) { Log.w("AlterEgoWearer", "Early loader query unavailable", error); emptySet() }
            val requested = listOf("XR_KHR_convert_timespec_time", "XR_FB_body_tracking", "XR_EXT_hand_tracking", "XR_FB_hand_tracking_mesh", "XR_FB_face_tracking2")
                .filter { it in available }.toMutableSet()
            // Spatial SDK's driver loader can be private before its session exists. Quest devices on
            // the required OS 69+ expose these documented Movement extensions; do not require them
            // on unknown future headsets or glasses merely because they have eye hardware.
            val model = Build.MODEL.orEmpty().replace('_', ' ').lowercase()
            if (model.contains("quest")) {
                requested += listOf("XR_KHR_convert_timespec_time", "XR_FB_body_tracking", "XR_EXT_hand_tracking", "XR_FB_hand_tracking_mesh")
                if (model.contains("quest pro")) requested += "XR_FB_face_tracking2"
            }
            return requested.toList().also { Log.i("AlterEgoWearer", "Requested wearer extensions: $it") }
        }
    }
}
