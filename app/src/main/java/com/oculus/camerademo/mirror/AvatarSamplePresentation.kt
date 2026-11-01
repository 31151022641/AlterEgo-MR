package com.oculus.camerademo.mirror

/** Chooses animation input without changing the avatar selected for the mirror.
 * Camera pixels are observations only; a tracking gap must never show that camera's
 * Horizon avatar in place of the selected Meta sample. */
object AvatarSamplePresentation {
    const val WAITING_MESSAGE = "Tracking unavailable · Meta sample waiting"

    /** Validates the incoming camera frame, then expires its channels separately.
     * A later face completion cannot refresh an earlier hand. The returned clock
     * is the oldest active channel, a conservative rendering/LAN freshness bound. */
    fun input(snapshot: WearerSnapshot?, capturedAt: Long, now: Long): WearerSnapshot {
        if (!AvatarSampleFramePolicy.fresh(capturedAt, now) || snapshot == null ||
            snapshot.sampledAtMs != capturedAt || snapshot.head == null) return neutral(now)
        val packet = snapshot.nativePacket
        if (packet == null || packet.size != 1060 || packet[0] != 1f ||
            packet.any { !it.isFinite() }) return neutral(now)
        // Own the array: camera workers must not be able to mutate an input queued
        // for the renderer. Decoding also rejects an invalid head orientation.
        val channels = snapshot.videoChannels
        fun channelFresh(timestamp: Long) = timestamp <= capturedAt && AvatarSampleFramePolicy.fresh(timestamp,now)
        val hasFace = packet[2] == 1f && snapshot.hasVideoFace &&
            (channels == null || channels.revision > 0L && channelFresh(channels.faceCapturedAtMs))
        val activeHands = BooleanArray(2) { side -> packet[3+side] == 1f &&
            snapshot.hands.getOrNull(side)?.any { it != null } == true &&
            (channels == null || channels.revision > 0L && channelFresh(if (side == 0)
                channels.leftHandCapturedAtMs else channels.rightHandCapturedAtMs)) }
        val ownedPacket = packet.copyOf().apply {
            if (!hasFace) {
                this[2] = 0f; fill(0f,364,434)
                if (channels != null) { fill(0f,5,12); this[6] = 1.6f; this[11] = 1f }
            }
            if (channels != null) {
                for (side in 0..1) if (!activeHands[side]) clearHand(this,side)
                this[1] = if (activeHands.any { it }) 1f else 0f
                if (this[1] == 0f) fill(0f,12,156)
            }
        }
        val prunedChannels = channels?.copy(
            faceCapturedAtMs = if (hasFace) channels.faceCapturedAtMs else 0L,
            leftHandCapturedAtMs = if (activeHands[0]) channels.leftHandCapturedAtMs else 0L,
            rightHandCapturedAtMs = if (activeHands[1]) channels.rightHandCapturedAtMs else 0L)
        val oldestActive = prunedChannels?.let {
            listOf(it.faceCapturedAtMs,it.leftHandCapturedAtMs,it.rightHandCapturedAtMs)
                .filter { timestamp -> timestamp > 0L }.minOrNull()
        }
        if (channels != null && oldestActive == null) return neutral(now).copy(videoChannels = prunedChannels)
        val decoded = WearerPacketDecoder.decode(ownedPacket,oldestActive ?: capturedAt)
        if (decoded.head == null) return neutral(now)
        val eyes = hasFace && snapshot.hasEyes &&
            snapshot.gazeX.isFinite() && snapshot.gazeY.isFinite()
        return decoded.copy(expression = if (hasFace) snapshot.expression else com.oculus.camerademo.ml.FaceEmotionState(), hasPhysicalFace = false,
            hasEyes = eyes, gazeX = if (eyes) snapshot.gazeX.coerceIn(-.8f, .8f) else 0f,
            gazeY = if (eyes) snapshot.gazeY.coerceIn(-.8f, .8f) else 0f,
            message = if (channels != null) channelMessage(hasFace,eyes,activeHands.count { it }) else snapshot.message,
            hasVideoFace = hasFace,videoChannels = prunedChannels)
    }

    /** Includes expiry transitions even when the merger revision is unchanged. */
    fun activeMask(snapshot: WearerSnapshot): Int =
        (if (snapshot.hasVideoFace) 1 else 0) or (if (snapshot.hasEyes) 2 else 0) or
            (if (snapshot.hands.getOrNull(0)?.any { it != null } == true) 4 else 0) or
            (if (snapshot.hands.getOrNull(1)?.any { it != null } == true) 8 else 0)

    private fun channelMessage(face: Boolean,eyes: Boolean,hands: Int): String {
        val handLabel = "$hands hand${if (hands == 1) "" else "s"}"
        return "Video estimates: " + if (face) "face${if (eyes) ", eyes" else ""}, $handLabel"
            else "$handLabel; face not visible"
    }

    private fun clearHand(packet: FloatArray,side: Int) {
        packet[3+side] = 0f
        val wrist = 12+(if (side == 0) 12 else 17)*8
        packet.fill(0f,wrist,wrist+8)
        val positions = 156+side*104; packet.fill(0f,positions,positions+104)
        val orientations = 434+side*104; packet.fill(0f,orientations,orientations+104)
        packet[642+side] = 0f
        val bind = 644+side*208; packet.fill(0f,bind,bind+208)
    }

    /** A renderable neutral pose, not an observation of the wearer. Its timestamp
     * describes this neutral render; it never refreshes a previous tracking input.
     * The head placeholder keeps the SDK visible while every motion channel is
     * inactive. UI must use the message/channel flags rather than head != null to
     * describe whether tracking is available. */
    fun neutral(now: Long): WearerSnapshot {
        val packet = FloatArray(1060).apply {
            this[0] = 1f
            this[6] = 1.6f
            this[11] = 1f
        }
        return WearerPacketDecoder.decode(packet, now.coerceAtLeast(0L))
            .copy(hasPhysicalFace = false, hasEyes = false, message = WAITING_MESSAGE)
    }
}
