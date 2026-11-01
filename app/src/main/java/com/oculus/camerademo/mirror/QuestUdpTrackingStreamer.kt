package com.oculus.camerademo.mirror

import android.os.SystemClock
import android.util.Log
import com.oculus.camerademo.eyetracking.EyeGazeData
import com.oculus.camerademo.ml.FaceTrackingData
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Low-latency UDP Telemetry Streamer for Meta Quest Pro.
 * Streams real-time 6DoF head pose, facial blendshapes, and hardware eye tracking
 * to the Windows PC application (pc_avatar_webcam) over local Wi-Fi/LAN.
 */
class QuestUdpTrackingStreamer : AutoCloseable {

    private val executor = Executors.newSingleThreadExecutor()
    private val sequence = AtomicLong(0)

    @Volatile
    private var isStreaming = false

    @Volatile
    private var socket: DatagramSocket? = null

    @Volatile
    private var targetAddress: InetAddress? = null

    @Volatile
    private var targetPort: Int = 9005

    var onStatusChanged: ((String) -> Unit)? = null

    /**
     * Starts UDP telemetry streaming to the PC IP and port.
     */
    fun startStreaming(pcIpAddress: String, port: Int = 9005) {
        stopStreaming()
        executor.execute {
            try {
                targetAddress = InetAddress.getByName(pcIpAddress.trim())
                targetPort = port
                socket = DatagramSocket()
                isStreaming = true
                Log.i(TAG, "Quest Pro UDP Streamer active -> $pcIpAddress:$port")
                onStatusChanged?.invoke("Streaming to $pcIpAddress:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize UDP streamer: ${e.message}", e)
                onStatusChanged?.invoke("Connection failed: ${e.message}")
                stopStreaming()
            }
        }
    }

    /**
     * Sends a real-time tracking packet containing head pose, eye gaze, and blendshapes.
     */
    fun sendTracking(
        tracking: FaceTrackingData,
        gaze: EyeGazeData,
        wearer: WearerSnapshot? = null
    ) {
        if (!isStreaming) return
        val currentSocket = socket ?: return
        val currentAddr = targetAddress ?: return

        executor.execute {
            try {
                val seq = sequence.incrementAndGet()
                val nowSec = System.currentTimeMillis() / 1000.0

                val root = JSONObject()
                root.put("timestamp", nowSec)
                root.put("device", "Meta Quest Pro")
                root.put("sequence", seq)

                // 1. Pose
                val poseObj = JSONObject()
                val posObj = JSONObject()
                val rotObj = JSONObject()

                val wearerHead = wearer?.head
                if (wearerHead != null) {
                    posObj.put("x", wearerHead.position.x)
                    posObj.put("y", wearerHead.position.y)
                    posObj.put("z", wearerHead.position.z)
                    // Convert Quaternion to Euler angles approximately
                    val euler = wearerHead.orientation.toEulerAngles()
                    rotObj.put("pitch", euler[0])
                    rotObj.put("yaw", euler[1])
                    rotObj.put("roll", euler[2])
                } else {
                    posObj.put("x", (tracking.faceCenterX - 0.5f) * tracking.headDistanceMeters)
                    posObj.put("y", (0.5f - tracking.faceCenterY) * tracking.headDistanceMeters)
                    posObj.put("z", -tracking.headDistanceMeters)
                    rotObj.put("pitch", tracking.rotationPitch)
                    rotObj.put("yaw", tracking.rotationYaw)
                    rotObj.put("roll", tracking.rotationRoll)
                }
                poseObj.put("pos", posObj)
                poseObj.put("rot", rotObj)
                root.put("pose", poseObj)

                // 2. Eyes
                val eyeObj = JSONObject()
                eyeObj.put("gazeX", gaze.gazeX)
                eyeObj.put("gazeY", gaze.gazeY)
                eyeObj.put("dwellTimeMs", gaze.dwellTimeMs)
                eyeObj.put("isFixating", gaze.isFaceLocked)
                eyeObj.put("leftEyeClosed", tracking.emotion.eyeBlinkLeft)
                eyeObj.put("rightEyeClosed", tracking.emotion.eyeBlinkRight)
                eyeObj.put("confidence", 0.98)
                root.put("eyes", eyeObj)

                // 3. Blendshapes
                val blendObj = JSONObject()
                blendObj.put("jawOpen", tracking.jawOpen)
                blendObj.put("mouthSmileLeft", tracking.emotion.mouthSmileLeft)
                blendObj.put("mouthSmileRight", tracking.emotion.mouthSmileRight)
                blendObj.put("eyeBlinkLeft", tracking.emotion.eyeBlinkLeft)
                blendObj.put("eyeBlinkRight", tracking.emotion.eyeBlinkRight)
                blendObj.put("browInnerUp", tracking.emotion.browInnerUp)
                blendObj.put("mouthPucker", tracking.emotion.mouthPucker)
                blendObj.put("mouthFunnel", tracking.emotion.mouthFunnel)
                blendObj.put("cheekPuff", tracking.emotion.cheekPuff)
                root.put("blendshapes", blendObj)

                val bytes = root.toString().toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(bytes, bytes.size, currentAddr, targetPort)
                currentSocket.send(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Error emitting UDP telemetry packet: ${e.message}")
            }
        }
    }

    fun stopStreaming() {
        isStreaming = false
        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        targetAddress = null
        onStatusChanged?.invoke("Streaming stopped")
    }

    override fun close() {
        stopStreaming()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "QuestUdpStreamer"

        private fun com.meta.spatial.core.Quaternion.toEulerAngles(): FloatArray {
            val qx = this.x
            val qy = this.y
            val qz = this.z
            val qw = this.w

            // Roll (x-axis rotation)
            val sinr_cosp = 2.0 * (qw * qx + qy * qz)
            val cosr_cosp = 1.0 - 2.0 * (qx * qx + qy * qy)
            val roll = Math.toDegrees(Math.atan2(sinr_cosp, cosr_cosp)).toFloat()

            // Pitch (y-axis rotation)
            val sinp = 2.0 * (qw * qy - qz * qx)
            val pitch = if (Math.abs(sinp) >= 1.0) {
                Math.toDegrees(Math.copySign(Math.PI / 2.0, sinp)).toFloat()
            } else {
                Math.toDegrees(Math.asin(sinp)).toFloat()
            }

            // Yaw (z-axis rotation)
            val siny_cosp = 2.0 * (qw * qz + qx * qy)
            val cosy_cosp = 1.0 - 2.0 * (qy * qy + qz * qz)
            val yaw = Math.toDegrees(Math.atan2(siny_cosp, cosy_cosp)).toFloat()

            return floatArrayOf(pitch, yaw, roll)
        }
    }
}
