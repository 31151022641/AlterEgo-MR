package com.oculus.camerademo.mirror

/** Local provenance for inferred video controls, never physical sensor data. */
data class AvatarVideoChannels(
    val faceCapturedAtMs: Long = 0L,
    val leftHandCapturedAtMs: Long = 0L,
    val rightHandCapturedAtMs: Long = 0L,
    val revision: Long = 0L,
)

/** Keeps face and hand smoothing clocks independent on one serial coordinator.
 * A face passed to the hand retargeter supplies framing only; its head, expression
 * and gaze output is discarded. The hand refinement cannot invent new face motion. */
internal class AvatarSampleChannelTracking {
    private val faceTracking = AvatarVideoTracking()
    private val handTracking = AvatarVideoTracking()
    private val channels = AvatarSampleChannelMerger()
    private var faceFraming: AvatarVideoFace? = null
    private var faceFramingAt = 0L
    private var parallelFramingAvailable: Boolean? = null

    fun reset(run: Long, selection: Long) {
        faceTracking.reset(); handTracking.reset(); channels.reset(run,selection)
        faceFraming = null; faceFramingAt = 0L
        parallelFramingAvailable = null
    }

    fun updateFace(face: AvatarVideoFace?, capturedAt: Long, now: Long,
        run: Long, selection: Long): WearerSnapshot? {
        if (!channels.acceptsFace(capturedAt,now,run,selection)) return null
        val snapshot = channels.updateFace(faceTracking.update(face,emptyList(),capturedAt),
            capturedAt,now,run,selection)
        if (face != null && snapshot?.hasVideoFace == true) {
            // Only bounds calibrate hand projection. Expression, gaze and head
            // motion from this possibly different frame must not be republished.
            val identity = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
            faceFraming = face.copy(rotation = identity,expression = com.oculus.camerademo.ml.FaceEmotionState(),
                gazeX = 0f,gazeY = 0f,blendshapes = emptyMap())
            faceFramingAt = capturedAt
        }
        return snapshot
    }

    fun updateHands(faceForFraming: AvatarVideoFace?, hands: List<AvatarVideoHand>,
        capturedAt: Long, now: Long, run: Long, selection: Long): WearerSnapshot? {
        if (!channels.acceptsHands(capturedAt,now,run,selection)) return null
        return channels.updateHands(handTracking.update(faceForFraming,hands,capturedAt),
            capturedAt,now,run,selection)
    }

    /** Parallel model completions use the latest bounded face geometry only.
     * Hand controls and their smoothing still use the hand image's own clock. */
    fun updateHands(hands: List<AvatarVideoHand>,capturedAt: Long,now: Long,
        run: Long,selection: Long): WearerSnapshot? {
        if (!channels.acceptsHands(capturedAt,now,run,selection)) return null
        val framing = faceFraming?.takeIf { now >= faceFramingAt && now-faceFramingAt <= 5000L }
        val available = framing != null
        if (parallelFramingAvailable != null && parallelFramingAvailable != available) {
            // The hand retargeter has its own framing cache. Clear it at a
            // calibration transition so repeated hand frames cannot extend
            // the last observed face geometry beyond its real five-second age.
            handTracking.reset()
        }
        parallelFramingAvailable = available
        return updateHands(framing,hands,capturedAt,now,run,selection)
    }
}

/** Owns at most one last observation per channel. Definitive misses clear only
 * their channel. A newer face may retain a hand, but never changes its capture time. */
internal class AvatarSampleChannelMerger {
    private var run = Long.MIN_VALUE
    private var selection = Long.MIN_VALUE
    private var latestInputAt = 0L
    private var faceResultAt = 0L
    private var handResultAt = 0L
    private var revision = 0L
    private var face: WearerSnapshot? = null
    private var faceAt = 0L
    private val handPackets = arrayOfNulls<FloatArray>(2)
    private val handAt = LongArray(2)

    fun reset(run: Long, selection: Long) {
        this.run = run; this.selection = selection
        latestInputAt = 0L; faceResultAt = 0L; handResultAt = 0L; revision = 0L
        face = null; faceAt = 0L; handPackets.fill(null); handAt.fill(0L)
    }

    private fun accepts(capturedAt: Long, now: Long, run: Long, selection: Long,
        previousChannelAt: Long) = this.run == run && this.selection == selection &&
        capturedAt > 0L && capturedAt > previousChannelAt && now >= capturedAt

    fun acceptsFace(capturedAt: Long, now: Long, run: Long, selection: Long) =
        accepts(capturedAt,now,run,selection,faceResultAt)

    fun acceptsHands(capturedAt: Long, now: Long, run: Long, selection: Long) =
        accepts(capturedAt,now,run,selection,handResultAt)

    fun updateFace(observation: WearerSnapshot?, capturedAt: Long, now: Long,
        run: Long, selection: Long): WearerSnapshot? {
        if (!acceptsFace(capturedAt,now,run,selection)) return null
        faceResultAt = capturedAt; latestInputAt = maxOf(latestInputAt,capturedAt); revision++
        val packet = validPacket(observation,capturedAt)
        face = observation?.takeIf { packet != null && packet[2] == 1f && it.hasVideoFace && it.head != null }
            ?.copy(nativePacket = packet!!.copyOf(),videoChannels = null)
        faceAt = if (face != null) capturedAt else 0L
        return compose(latestInputAt,now)
    }

    fun updateHands(observation: WearerSnapshot?, capturedAt: Long, now: Long,
        run: Long, selection: Long): WearerSnapshot? {
        if (!acceptsHands(capturedAt,now,run,selection)) return null
        handResultAt = capturedAt; latestInputAt = maxOf(latestInputAt,capturedAt); revision++
        val packet = validPacket(observation,capturedAt)
        for (side in 0..1) {
            val observed = packet != null && packet[3+side] == 1f &&
                observation!!.hands.getOrNull(side)?.any { it != null } == true
            handPackets[side] = if (observed) packet!!.copyOf() else null
            handAt[side] = if (observed) capturedAt else 0L
        }
        return compose(latestInputAt,now)
    }

    private fun validPacket(observation: WearerSnapshot?, capturedAt: Long): FloatArray? =
        observation?.nativePacket?.takeIf { observation.sampledAtMs == capturedAt &&
            observation.head != null && it.size == 1060 && it[0] == 1f && it.all { value -> value.isFinite() } }

    private fun compose(capturedAt: Long, now: Long): WearerSnapshot {
        if (!AvatarSampleFramePolicy.fresh(faceAt,now)) { face = null; faceAt = 0L }
        for (side in 0..1) if (!AvatarSampleFramePolicy.fresh(handAt[side],now)) {
            handPackets[side] = null; handAt[side] = 0L
        }
        val packet = FloatArray(1060).apply { this[0] = 1f; this[6] = 1.6f; this[11] = 1f }
        face?.nativePacket?.let { current ->
            packet[2] = 1f
            current.copyInto(packet,5,5,12)
            current.copyInto(packet,364,364,434)
        }
        for (side in 0..1) handPackets[side]?.let { current ->
            packet[1] = 1f; packet[3+side] = 1f
            val wrist = 12+(if (side == 0) 12 else 17)*8
            current.copyInto(packet,wrist,wrist,wrist+8)
            val positions = 156+side*104
            current.copyInto(packet,positions,positions,positions+104)
            val orientations = 434+side*104
            current.copyInto(packet,orientations,orientations,orientations+104)
            packet[642+side] = current[642+side]
            val bind = 644+side*208
            current.copyInto(packet,bind,bind,bind+208)
        }
        val currentFace = face
        val eyes = currentFace?.hasEyes == true && currentFace.gazeX.isFinite() && currentFace.gazeY.isFinite()
        val handCount = handPackets.count { it != null }
        val message = if (currentFace != null) "face${if (eyes) ", eyes" else ""}, $handCount hand${if (handCount == 1) "" else "s"}"
            else if (handCount > 0) "$handCount hand${if (handCount == 1) "" else "s"}; face not visible"
            else "no face or hands visible"
        return WearerPacketDecoder.decode(packet,capturedAt).copy(
            expression = currentFace?.expression ?: com.oculus.camerademo.ml.FaceEmotionState(),
            hasPhysicalFace = false,hasVideoFace = currentFace != null,hasEyes = eyes,
            gazeX = if (eyes) currentFace!!.gazeX else 0f,gazeY = if (eyes) currentFace!!.gazeY else 0f,
            message = "Video estimates: $message",
            videoChannels = AvatarVideoChannels(faceAt,handAt[0],handAt[1],revision))
    }
}
