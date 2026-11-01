package com.oculus.camerademo.mirror

import kotlin.math.abs
import kotlin.math.sqrt

/** Local, ephemeral comparison of image-derived controls. Only these coarse flags
 * leave this helper; they describe changing estimates, not physical sensor accuracy. */
internal data class AvatarVideoMotionFlags(val faceExpressionChanged: Boolean = false,
    val gazeChanged: Boolean = false, val leftHandChanged: Boolean = false,
    val rightHandChanged: Boolean = false)

internal class AvatarVideoMotionDiagnostics {
    private var previousFace: FloatArray? = null
    private var previousGaze: Pair<Float, Float>? = null
    private val previousHands = arrayOfNulls<FloatArray>(2)
    private var latestFace: FloatArray? = null
    private var latestGaze: Pair<Float, Float>? = null
    private val latestHands = arrayOfNulls<FloatArray>(2)
    private var previousTimestamp = 0L
    private var flags = AvatarVideoMotionFlags()

    fun reset() {
        clearPrevious(); flags = AvatarVideoMotionFlags()
    }

    private fun clearPrevious() {
        previousFace = null; previousGaze = null; previousHands.fill(null)
        latestFace = null; latestGaze = null; latestHands.fill(null)
        previousTimestamp = 0L
    }

    fun update(snapshot: WearerSnapshot?, nowMs: Long) {
        val packet = snapshot?.nativePacket
        if (snapshot == null || snapshot.head == null || !AvatarSampleFramePolicy.fresh(snapshot.sampledAtMs, nowMs) ||
            snapshot.sampledAtMs <= previousTimestamp || packet == null || packet.size != 1060 ||
            packet[0] != 1f || packet.any { !it.isFinite() }) {
            clearPrevious(); return
        }
        // Long gaps cannot become motion on reappearance, even if a new detection is valid.
        if (previousTimestamp > 0L && snapshot.sampledAtMs-previousTimestamp > AvatarSampleFramePolicy.MAX_INPUT_AGE_MS)
            clearPrevious()
        previousTimestamp = snapshot.sampledAtMs
        val face = if (snapshot.hasVideoFace && packet[2] == 1f)
            packet.copyOfRange(364, 434).takeIf { values -> values.all { it in 0f..1f } } else null
        val faceChanged = face != null && previousFace?.let { old ->
            face.indices.any { abs(face[it]-old[it]) >= .08f }
        } == true
        latestFace = face
        // Keep a fixed comparison anchor until a meaningful change is detected.
        // Otherwise several small steps of a slow movement would each look like jitter.
        if (face == null || previousFace == null || faceChanged) previousFace = face
        val gaze = if (face != null && snapshot.hasEyes && snapshot.gazeX.isFinite() && snapshot.gazeY.isFinite())
            snapshot.gazeX to snapshot.gazeY else null
        val gazeChanged = gaze != null && previousGaze?.let { old ->
            val dx = gaze.first-old.first; val dy = gaze.second-old.second
            dx*dx+dy*dy >= .08f*.08f
        } == true
        latestGaze = gaze
        if (gaze == null || previousGaze == null || gazeChanged) previousGaze = gaze
        val changedHands = BooleanArray(2)
        for (side in 0..1) {
            val hand = hand(packet, side)
            changedHands[side] = hand != null && previousHands[side]?.let { old -> handChanged(old, hand) } == true
            latestHands[side] = hand
            if (hand == null || previousHands[side] == null || changedHands[side]) previousHands[side] = hand
        }
        flags = AvatarVideoMotionFlags(flags.faceExpressionChanged || faceChanged,
            flags.gazeChanged || gazeChanged, flags.leftHandChanged || changedHands[0],
            flags.rightHandChanged || changedHands[1])
    }

    /** Window flags latch every processed frame, including a brief blink that has
     * already ended by the five-second metadata report. Numeric inputs stay local. */
    fun takeWindow(): AvatarVideoMotionFlags = flags.also {
        flags = AvatarVideoMotionFlags()
        previousFace = latestFace; previousGaze = latestGaze
        for (side in 0..1) previousHands[side] = latestHands[side]
    }

    private fun hand(packet: FloatArray, side: Int): FloatArray? {
        if (packet[3+side] != 1f) return null
        val points = packet.copyOfRange(156+side*104, 260+side*104)
        val rotations = packet.copyOfRange(434+side*104, 538+side*104)
        if (points[4] != 1f) return null // A visible wrist is required.
        for (joint in 0..25) {
            if (points[joint*4] != 1f) continue
            val at = joint*4
            val norm = sqrt((0..3).sumOf { (rotations[at+it]*rotations[at+it]).toDouble() }).toFloat()
            if (!norm.isFinite() || norm < .001f) return null
            for (n in 0..3) rotations[at+n] /= norm
        }
        return points+rotations
    }

    private fun handChanged(old: FloatArray, current: FloatArray): Boolean {
        for (joint in 0..25) {
            val at = joint*4
            if (old[at] != 1f || current[at] != 1f) continue
            var displacementSquared = 0f
            for (n in 1..3) {
                val difference = current[at+n]-old[at+n]
                displacementSquared += difference*difference
            }
            if (displacementSquared >= .02f*.02f) return true
            var dot = 0f
            for (n in 0..3) dot += old[104+at+n]*current[104+at+n]
            // q and -q represent the same orientation. Chord distance .1 is
            // roughly eleven degrees, large enough to ignore tiny model jitter.
            if (2f-2f*abs(dot).coerceIn(0f, 1f) >= .1f*.1f) return true
        }
        return false
    }
}
