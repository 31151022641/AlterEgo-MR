package com.oculus.camerademo.mirror

/** Bounded diagnostic inputs for the SDK's public sample. Never reads the camera
 * or wearer sensors. The caller supplies an explicitly synthetic active pose. */
object AvatarSampleGapSequence {
    const val FRAMES_PER_PHASE = 30
    enum class Phase(val fileName: String) {
        BASELINE("sample-gap-baseline.png"),
        ACTIVE("sample-gap-active.png"),
        GAP("sample-gap-neutral.png"),
        RECOVERED("sample-gap-recovered.png"),
        HANDS_WITHOUT_FACE("sample-gap-hands-only.png"),
        FINAL_NEUTRAL("sample-gap-final-neutral.png")
    }
    val totalFrames = Phase.values().size * FRAMES_PER_PHASE

    fun phase(frame: Int): Phase? = if (frame in 1..totalFrames)
        Phase.values()[(frame - 1) / FRAMES_PER_PHASE] else null

    fun capture(frame: Int) = frame in 1..totalFrames && frame % FRAMES_PER_PHASE == 0

    fun input(frame: Int, syntheticActive: WearerSnapshot, now: Long): WearerSnapshot = when (phase(frame)) {
        Phase.ACTIVE, Phase.RECOVERED -> AvatarSamplePresentation.input(syntheticActive,
            syntheticActive.sampledAtMs, now)
        Phase.HANDS_WITHOUT_FACE -> {
            val packet = syntheticActive.nativePacket?.copyOf()
            if (packet == null || packet.size != 1060) AvatarSamplePresentation.neutral(now)
            else {
                // Keep only this frame's hand observations. Face, gaze and head
                // motion are deliberately unavailable during this phase.
                packet[2] = 0f
                for (index in 364 until 434) packet[index] = 0f
                for (index in 5..11) packet[index] = 0f
                packet[6] = 1.6f; packet[11] = 1f
                val handsOnly = WearerPacketDecoder.decode(packet, syntheticActive.sampledAtMs)
                    .copy(hasPhysicalFace = false, message = "Video estimates: hands · face unavailable")
                AvatarSamplePresentation.input(handsOnly, handsOnly.sampledAtMs, now)
            }
        }
        else -> AvatarSamplePresentation.neutral(now)
    }

    /** Assertions on the exact SDK inputs, separate from visual verification that
     * the SDK actually consumes neutral controls instead of retaining its cache. */
    fun channelsMatch(phase: Phase, snapshot: WearerSnapshot): Boolean {
        val packet = snapshot.nativePacket ?: return false
        if (packet.size != 1060 || packet.any { !it.isFinite() } || packet[0] != 1f ||
            snapshot.hasPhysicalFace) return false
        val neutralFace = packet[2] == 0f && (364 until 434).all { packet[it] == 0f } &&
            !snapshot.hasVideoFace && !snapshot.hasEyes && snapshot.gazeX == 0f && snapshot.gazeY == 0f
        return when (phase) {
            Phase.ACTIVE, Phase.RECOVERED -> packet[2] == 1f && packet[3] == 1f &&
                packet[4] == 1f && snapshot.hasVideoFace && snapshot.hasEyes && packet[364 + 24] > .5f
            Phase.HANDS_WITHOUT_FACE -> neutralFace && packet[3] == 1f && packet[4] == 1f
            else -> neutralFace && (1..4).all { packet[it] == 0f } &&
                snapshot.body.all { it == null } && snapshot.hands.flatten().all { it == null }
        }
    }
}
