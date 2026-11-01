package com.oculus.camerademo.mirror

import com.oculus.camerademo.ml.FaceEmotionState
import kotlin.math.*

/** Retargets observations of the OS avatar video to the sample SDK avatar.
 * No permissions or physical tracking availability are inferred from successful image analysis.
 * Depth and wrist/finger rotation are estimates; hidden hands immediately become inactive. */
class AvatarVideoTracking {
    private var lastTimestamp = -1L
    private var headRotation: VideoQ? = null
    private var headPosition: VideoP? = null
    private var expressionHistory: FloatArray? = null
    private var gazeHistory: Pair<Float, Float>? = null
    private val handHistory = arrayOfNulls<List<VideoJoint>>(2)
    private var framingReference: VideoFraming? = null

    fun reset() {
        lastTimestamp = -1L; headRotation = null; headPosition = null; expressionHistory = null; gazeHistory = null
        handHistory.fill(null); framingReference = null
    }

    fun update(face: AvatarVideoFace?, hands: List<AvatarVideoHand>, timestamp: Long): WearerSnapshot {
        if (timestamp < 0 || timestamp <= lastTimestamp) {
            headRotation = null; headPosition = null; expressionHistory = null; gazeHistory = null; handHistory.fill(null)
            framingReference = null
            return WearerSnapshot(message = "Video tracking waiting for a new camera frame")
        }
        val dt = if (lastTimestamp >= 0) timestamp - lastTimestamp else 0L
        lastTimestamp = timestamp
        val alpha = if (dt in 1..250) (1.0 - exp(-dt / 45.0)).toFloat() else 1f
        val candidateFace = face?.takeIf { it.usable }
        val rotation = candidateFace?.let { VideoQ.face(it.rotation) }
        val observation = candidateFace?.takeIf { rotation != null }
        val validHands = hands.filter { validHand(it, timestamp) }
        val p = FloatArray(1060)
        // The SDK needs a head pose even when only hands are visible. This neutral framing
        // pose is not an observed face; the separate face flag remains false during occlusion.
        p[0] = 1f
        writePose(p, 5, VideoJoint(VideoP(0f, 1.6f, 0f), VideoQ(0f, 0f, 0f, 1f)))
        val weights = if (observation != null) faceWeights(observation) else FloatArray(70)
        if (observation != null) {
            val q = headRotation?.mix(requireNotNull(rotation), alpha) ?: requireNotNull(rotation)
            headRotation = q
            val faceX = (observation.left + observation.right) * .5f
            val faceY = (observation.top + observation.bottom) * .5f
            // Keep only camera framing as a short-lived reference for hand projection.
            // Missing face observations never reuse its pose, expression or gaze.
            val heightSpan = (.22f / (observation.bottom - observation.top)).coerceIn(.55f, 1.8f)
            val aspect = validHands.firstOrNull()?.imageAspectRatio?.takeIf { it.isFinite() && it in .5f..3f } ?: (16f / 9f)
            val widthSpan = heightSpan * aspect
            framingReference = VideoFraming(faceY, widthSpan, heightSpan, timestamp)
            val position = VideoP((faceX - .5f) * widthSpan, 1.6f, 0f)
            val filteredPosition = if (alpha == 1f) position else headPosition?.let { it + (position - it) * alpha } ?: position
            headPosition = filteredPosition
            writePose(p, 5, VideoJoint(filteredPosition, q))
            p[2] = 1f
        } else {
            headRotation = null; headPosition = null; expressionHistory = null; gazeHistory = null
        }
        val bothEyesClosed = weights[12] >= .65f && weights[13] >= .65f
        // A shorter expression filter preserves quick blinks and mouth movement.
        val expressionAlpha = if (dt in 1..250) (1.0 - exp(-dt / 25.0)).toFloat() else 1f
        if (expressionAlpha != 1f) expressionHistory?.let { old -> for (n in weights.indices) weights[n] = old[n] + (weights[n] - old[n]) * expressionAlpha }
        expressionHistory = if (observation != null) weights.copyOf() else null
        weights.copyInto(p, 364)
        val framing = framingReference?.takeIf { timestamp - it.sampledAtMs in 0..5000 }
            ?: VideoFraming(.29f, .86f * (16f / 9f), .86f, timestamp).also { framingReference = null }
        val candidates = validHands.groupBy { it.side.packetSide }
        var observedHands = 0
        for (side in 0..1) {
            // Never resolve duplicate handedness by screen X: crossed hands retain their identity.
            val hand = candidates[side]?.singleOrNull()
            val joints = hand?.let { reconstruct(it, framing.faceY, framing.widthSpan, framing.heightSpan) }
            if (joints == null) { handHistory[side] = null; continue }
            val old = handHistory[side]
            val filtered = joints.mapIndexed { n, current -> old?.get(n)?.mix(current, alpha) ?: current }
            handHistory[side] = filtered
            p[3 + side] = 1f; p[1] = 1f
            val bodyWrist = if (side == 0) 12 else 17
            p[12 + bodyWrist * 8] = 1f
            writePose(p, 13 + bodyWrist * 8, filtered[1])
            filtered.forEachIndexed { n, joint ->
                val at = 156 + side * 104 + n * 4
                p[at] = 1f; p[at + 1] = joint.position.x; p[at + 2] = joint.position.y; p[at + 3] = joint.position.z
                writeQ(p, 434 + side * 104 + n * 4, joint.orientation)
            }
            // This fixed open-hand reference is independent of the first live pose.
            // Starting with a fist cannot redefine a fist as the SDK's neutral hand.
            p[642 + side] = 1f
            neutralBind(side).forEachIndexed { n, joint ->
                val at = 644 + side * 208 + n * 8
                p[at] = handParents[n].toFloat(); writePose(p, at + 1, joint)
            }
            observedHands++
        }
        val decoded = WearerPacketDecoder.decode(p, timestamp)
        val eyes = observation != null && !bothEyesClosed && observation.gazeX.isFinite() && observation.gazeY.isFinite()
        val gaze = if (eyes) {
            val gx = requireNotNull(observation).gazeX.coerceIn(-.8f, .8f); val gy = observation.gazeY.coerceIn(-.8f, .8f)
            if (expressionAlpha == 1f) gx to gy else gazeHistory?.let { (x,y) -> (x + (gx-x)*expressionAlpha) to (y + (gy-y)*expressionAlpha) } ?: (gx to gy)
        } else null
        gazeHistory = gaze
        val expression = observation?.expression?.copy(eyeBlinkLeft = weights[12], eyeBlinkRight = weights[13],
            eyeSquintLeft = weights[28], eyeSquintRight = weights[29], eyeWideLeft = weights[59], eyeWideRight = weights[60],
            jawOpen = weights[24], mouthSmileLeft = weights[32], mouthSmileRight = weights[33],
            mouthFrownLeft = weights[30], mouthFrownRight = weights[31], mouthPucker = weights[40], mouthFunnel = weights[34],
            browInnerUp = weights[22], browDownLeft = weights[0], browDownRight = weights[1],
            browOuterUpLeft = weights[57], browOuterUpRight = weights[58], cheekPuff = weights[2],
            cheekSquintLeft = weights[4], cheekSquintRight = weights[5]) ?: FaceEmotionState()
        val handLabel = "$observedHands hand${if (observedHands == 1) "" else "s"}"
        val trackingMessage = if (observation != null) "face${if (eyes) ", eyes" else ""}, $handLabel"
            else if (observedHands > 0) "$handLabel; face not visible" else "no face or hands visible"
        return decoded.copy(expression = expression, hasPhysicalFace = false,
            hasEyes = eyes, gazeX = gaze?.first ?: 0f, gazeY = gaze?.second ?: 0f,
            hasVideoFace = observation != null, message = "Video estimates: $trackingMessage")
    }

    private fun validHand(hand: AvatarVideoHand, timestamp: Long): Boolean {
        if (!hand.confidence.isFinite() || hand.confidence < .7f || hand.sampledAtMs < 0 || hand.sampledAtMs > timestamp ||
            timestamp - hand.sampledAtMs > 150 || hand.landmarks.size != 21 || hand.worldLandmarks.size != 21 ||
            hand.landmarks.any { !it.finite || it.x !in -.15f..1.15f || it.y !in -.15f..1.15f } ||
            hand.worldLandmarks.any { !it.finite || abs(it.x) > .4f || abs(it.y) > .4f || abs(it.z) > .4f }) return false
        val w = hand.worldLandmarks.map { VideoP(it.x, -it.y, -it.z) }
        val forward = w[9] - w[0]
        val radial = w[5] - w[17]
        val forwardLength = forward.length
        val radialLength = radial.length
        // Rendered side-view palms can be narrower than 25 mm in the model's
        // estimated world coordinates. Scale to the observed palm length rather
        // than rejecting every such candidate as an impossible human hand.
        if (forwardLength !in .025f.. .2f || radialLength !in (forwardLength * .1f).. .18f) return false
        // A collapsed or nearly collinear palm cannot supply a stable wrist twist
        // reference. Do not turn its numerical noise into a full hand rotation.
        if (forward.cross(radial).length / (forwardLength * radialLength) <= .2f) return false
        for (finger in 0..4) for (n in 0..2) {
            val at = 1 + finger * 4 + n
            if ((w[at + 1] - w[at]).length !in .004f.. .12f) return false
        }
        return true
    }

    private fun reconstruct(hand: AvatarVideoHand, faceY: Float, width: Float, height: Float): List<VideoJoint>? {
        val wrist = hand.landmarks[0]
        val origin = VideoP((wrist.x - .5f) * width, 1.6f + (faceY - wrist.y) * height, -.25f)
        val world = hand.worldLandmarks.map { VideoP(it.x, -it.y, -it.z) }
        val points = world.map { origin + it - world[0] }
        return makeJoints(points, neutralBind(hand.side.packetSide))
    }

    companion object {
        private val handParents = intArrayOf(1, -1, 1, 2, 3, 4, 1, 6, 7, 8, 9, 1, 11, 12, 13, 14, 1, 16, 17, 18, 19, 1, 21, 22, 23, 24)
        private val binds = Array(2) { side ->
            val radialSign = if (side == 0) 1f else -1f
            val points = mutableListOf(VideoP(0f, 0f, 0f))
            points += listOf(VideoP(.035f * radialSign, .025f, 0f), VideoP(.058f * radialSign, .05f, 0f),
                VideoP(.074f * radialSign, .069f, 0f), VideoP(.09f * radialSign, .084f, 0f))
            val xs = floatArrayOf(.032f, .01f, -.015f, -.039f)
            val lengths = floatArrayOf(.038f, .043f, .04f, .031f)
            for (f in 0..3) {
                val base = VideoP(xs[f] * radialSign, .07f, 0f)
                points += base
                points += base + VideoP(0f, lengths[f], 0f)
                points += base + VideoP(0f, lengths[f] * 1.6f, 0f)
                points += base + VideoP(0f, lengths[f] * 2f, 0f)
            }
            requireNotNull(makeJoints(points))
        }
        private fun neutralBind(side: Int) = binds[side]

        /** MediaPipe's 21 visible points expand to OpenXR's 26 joints. Metacarpals
         * are interpolated, not independently observed. Every segment orientation follows
         * observed 3D geometry; the palm supplies a consistent twist reference. */
        private fun makeJoints(points: List<VideoP>, bind: List<VideoJoint>? = null): List<VideoJoint>? {
            val palm = VideoQ.basis(points[9] - points[0], points[5] - points[17]) ?: return null
            val radialAxis = palm.rotate(VideoP(1f, 0f, 0f))
            fun bone(joint: Int, a: VideoP, b: VideoP): VideoQ? {
                val forward = (b - a).normalized() ?: return null
                if (bind != null) {
                    val bindLocal = bind[1].orientation.inverse() * bind[joint].orientation
                    val from = bindLocal.rotate(VideoP(0f, 0f, 1f))
                    val to = palm.inverse().rotate(forward)
                    // Swing the reference segment to the observed direction. Re-projecting a
                    // fixed palm normal would flip fingers by 180 degrees past a 90-degree curl.
                    val swing = VideoQ.swing(from, to) ?: return null
                    return palm * swing * bindLocal
                }
                return VideoQ.basis(forward, radialAxis)
            }
            val result = MutableList(26) { VideoJoint(points[0], palm) }
            result[0] = VideoJoint((points[5] + points[9] + points[13] + points[17]) * .25f, palm)
            for (n in 0..3) {
                val point = n + 1
                result[n + 2] = VideoJoint(points[point], bone(n + 2, points[if (n == 3) point - 1 else point], points[if (n == 3) point else point + 1]) ?: return null)
            }
            for (finger in 0..3) {
                val mp = 5 + finger * 4; val xr = 6 + finger * 5
                val metacarpal = points[0] + (points[mp] - points[0]) * .5f
                result[xr] = VideoJoint(metacarpal, bone(xr, metacarpal, points[mp]) ?: return null)
                for (n in 0..3) {
                    val point = mp + n
                    result[xr + n + 1] = VideoJoint(points[point], bone(xr + n + 1, points[if (n == 3) point - 1 else point], points[if (n == 3) point else point + 1]) ?: return null)
                }
            }
            return result
        }

        internal fun faceWeights(face: AvatarVideoFace): FloatArray {
            val e = face.expression; val m = face.blendshapes
            fun score(name: String, fallback: Float = 0f) = (m[name] ?: fallback).let { if (it.isFinite()) it.coerceIn(0f, 1f) else 0f }
            val w = FloatArray(70)
            fun pair(at: Int, left: String, right: String, l: Float = 0f, r: Float = 0f) { w[at] = score(left, l); w[at + 1] = score(right, r) }
            pair(0, "browDownLeft", "browDownRight", e.browDownLeft, e.browDownRight)
            w[2] = score("cheekPuff", e.cheekPuff); w[3] = w[2]
            pair(4, "cheekSquintLeft", "cheekSquintRight", e.cheekSquintLeft, e.cheekSquintRight)
            pair(10, "mouthDimpleLeft", "mouthDimpleRight")
            pair(12, "eyeBlinkLeft", "eyeBlinkRight", e.eyeBlinkLeft, e.eyeBlinkRight)
            pair(14, "eyeLookDownLeft", "eyeLookDownRight")
            pair(16, "eyeLookOutLeft", "eyeLookInRight")
            pair(18, "eyeLookInLeft", "eyeLookOutRight")
            pair(20, "eyeLookUpLeft", "eyeLookUpRight")
            w[22] = score("browInnerUp", e.browInnerUp); w[23] = w[22]
            w[24] = score("jawOpen", e.jawOpen); w[25] = score("jawLeft"); w[26] = score("jawRight"); w[27] = score("jawForward")
            pair(28, "eyeSquintLeft", "eyeSquintRight", e.eyeSquintLeft, e.eyeSquintRight)
            pair(30, "mouthFrownLeft", "mouthFrownRight", e.mouthFrownLeft, e.mouthFrownRight)
            pair(32, "mouthSmileLeft", "mouthSmileRight", e.mouthSmileLeft, e.mouthSmileRight)
            for (n in 34..37) w[n] = score("mouthFunnel", e.mouthFunnel)
            pair(38, "mouthPressLeft", "mouthPressRight")
            w[40] = score("mouthPucker", e.mouthPucker); w[41] = w[40]
            pair(42, "mouthStretchLeft", "mouthStretchRight")
            w[44] = score("mouthRollLower"); w[46] = w[44]
            w[45] = score("mouthRollUpper"); w[47] = w[45]
            w[50] = score("mouthClose")
            pair(51, "mouthLowerDownLeft", "mouthLowerDownRight")
            w[53] = score("mouthLeft"); w[54] = score("mouthRight")
            pair(55, "noseSneerLeft", "noseSneerRight")
            pair(57, "browOuterUpLeft", "browOuterUpRight", e.browOuterUpLeft, e.browOuterUpRight)
            pair(59, "eyeWideLeft", "eyeWideRight", e.eyeWideLeft, e.eyeWideRight)
            pair(61, "mouthUpperUpLeft", "mouthUpperUpRight")
            // Tongue and unobserved FACS controls remain neutral.
            return w
        }

        private fun writeQ(p: FloatArray, at: Int, q: VideoQ) { p[at] = q.x; p[at + 1] = q.y; p[at + 2] = q.z; p[at + 3] = q.w }
        private fun writePose(p: FloatArray, at: Int, joint: VideoJoint) {
            p[at] = joint.position.x; p[at + 1] = joint.position.y; p[at + 2] = joint.position.z
            writeQ(p, at + 3, joint.orientation)
        }
    }
}

private data class VideoFraming(val faceY: Float, val widthSpan: Float, val heightSpan: Float, val sampledAtMs: Long)

private data class VideoJoint(val position: VideoP, val orientation: VideoQ) {
    fun mix(other: VideoJoint, alpha: Float) = if (alpha == 1f) other else VideoJoint(position + (other.position - position) * alpha, orientation.mix(other.orientation, alpha))
}
private data class VideoP(val x: Float, val y: Float, val z: Float) {
    operator fun plus(p: VideoP) = VideoP(x + p.x, y + p.y, z + p.z)
    operator fun minus(p: VideoP) = VideoP(x - p.x, y - p.y, z - p.z)
    operator fun times(s: Float) = VideoP(x * s, y * s, z * s)
    fun dot(p: VideoP) = x * p.x + y * p.y + z * p.z
    fun cross(p: VideoP) = VideoP(y * p.z - z * p.y, z * p.x - x * p.z, x * p.y - y * p.x)
    val length get() = sqrt(dot(this))
    fun normalized(): VideoP? = length.takeIf { it.isFinite() && it > .00001f }?.let { this * (1f / it) }
}

private data class VideoQ(val x: Float, val y: Float, val z: Float, val w: Float) {
    operator fun times(b: VideoQ) = VideoQ(w * b.x + x * b.w + y * b.z - z * b.y,
        w * b.y - x * b.z + y * b.w + z * b.x, w * b.z + x * b.y - y * b.x + z * b.w,
        w * b.w - x * b.x - y * b.y - z * b.z)
    fun inverse() = VideoQ(-x, -y, -z, w)
    fun mix(other: VideoQ, alpha: Float): VideoQ {
        if (alpha == 1f) return other
        val sign = if (x * other.x + y * other.y + z * other.z + w * other.w < 0f) -1f else 1f
        return normalize(x + (other.x * sign - x) * alpha, y + (other.y * sign - y) * alpha,
            z + (other.z * sign - z) * alpha, w + (other.w * sign - w) * alpha) ?: other
    }
    fun rotate(p: VideoP): VideoP {
        val u = VideoP(x, y, z)
        return p + u.cross(p) * (2f * w) + u.cross(u.cross(p)) * 2f
    }
    companion object {
        private fun normalize(x: Float, y: Float, z: Float, w: Float): VideoQ? {
            val n = sqrt(x * x + y * y + z * z + w * w)
            return if (n.isFinite() && n > .00001f) VideoQ(x / n, y / n, z / n, w / n) else null
        }
        fun face(matrix: FloatArray): VideoQ? {
            if (matrix.size != 16 || matrix.any { !it.isFinite() }) return null
            val x = VideoP(matrix[0], matrix[1], matrix[2]).normalized() ?: return null
            val y0 = VideoP(matrix[4], matrix[5], matrix[6]).normalized() ?: return null
            val z0 = VideoP(matrix[8], matrix[9], matrix[10]).normalized() ?: return null
            if (abs(x.dot(y0)) > .1f || abs(x.dot(z0)) > .1f || abs(y0.dot(z0)) > .1f || x.cross(y0).dot(z0) < .9f) return null
            val z = x.cross(y0).normalized() ?: return null
            val y = z.cross(x)
            val q = columns(x, y, z) ?: return null
            // Depth changes sign between the frontal metric face and native avatar axes.
            // Conjugating that reflection preserves image X, Y and roll; no extra selfie flip.
            return VideoQ(-q.x, -q.y, q.z, q.w)
        }
        fun basis(forward: VideoP, radial: VideoP): VideoQ? {
            val z = forward.normalized() ?: return null
            val y = z.cross(radial).normalized() ?: return null
            val x = y.cross(z).normalized() ?: return null
            return columns(x, y, z)
        }
        fun swing(from: VideoP, to: VideoP): VideoQ? {
            val a = from.normalized() ?: return null; val b = to.normalized() ?: return null
            val dot = a.dot(b).coerceIn(-1f, 1f)
            if (dot < -.99999f) {
                val seed = if (abs(a.x) < abs(a.y) && abs(a.x) < abs(a.z)) VideoP(1f, 0f, 0f)
                    else if (abs(a.y) < abs(a.z)) VideoP(0f, 1f, 0f) else VideoP(0f, 0f, 1f)
                val axis = a.cross(seed).normalized() ?: return null
                return VideoQ(axis.x, axis.y, axis.z, 0f)
            }
            val cross = a.cross(b)
            return normalize(cross.x, cross.y, cross.z, 1f + dot)
        }
        private fun columns(x: VideoP, y: VideoP, z: VideoP): VideoQ? {
            val a = arrayOf(floatArrayOf(x.x, y.x, z.x), floatArrayOf(x.y, y.y, z.y), floatArrayOf(x.z, y.z, z.z))
            val trace = a[0][0] + a[1][1] + a[2][2]
            val q = if (trace > 0f) {
                val s = sqrt(trace + 1f) * 2f
                floatArrayOf((a[2][1] - a[1][2]) / s, (a[0][2] - a[2][0]) / s, (a[1][0] - a[0][1]) / s, s / 4f)
            } else {
                val i = (0..2).maxBy { a[it][it] }; val j = (i + 1) % 3; val k = (i + 2) % 3
                val s = sqrt(1f + a[i][i] - a[j][j] - a[k][k]) * 2f
                FloatArray(4).apply {
                    this[i] = s / 4f; this[j] = (a[i][j] + a[j][i]) / s; this[k] = (a[i][k] + a[k][i]) / s
                    this[3] = (a[k][j] - a[j][k]) / s
                }
            }
            return normalize(q[0], q[1], q[2], q[3])
        }
    }
}
