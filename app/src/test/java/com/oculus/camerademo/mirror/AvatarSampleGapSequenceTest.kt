package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class AvatarSampleGapSequenceTest {
    private fun active(now: Long) = WearerPacketDecoder.decode(FloatArray(1060).apply {
        this[0] = 1f; this[6] = 1.6f; this[9] = .3f; this[11] = kotlin.math.sqrt(.91f)
        this[2] = 1f; this[3] = 1f; this[4] = 1f
        this[364 + 24] = .8f; this[364 + 12] = .8f; this[364 + 13] = .1f
        this[156] = 1f; this[260] = 1f
        this[157] = -.3f; this[261] = .3f; this[437] = 1f; this[541] = 1f
    }, now).copy(hasPhysicalFace = false, hasVideoFace = true, hasEyes = true, gazeX = .5f, gazeY = -.3f)

    @Test fun diagnosticIsBoundedAndCapturesEachPhaseOnce() {
        assertNull(AvatarSampleGapSequence.phase(0))
        assertNull(AvatarSampleGapSequence.phase(AvatarSampleGapSequence.totalFrames + 1))
        val captures = (1..AvatarSampleGapSequence.totalFrames).filter(AvatarSampleGapSequence::capture)
        assertEquals(AvatarSampleGapSequence.Phase.values().size, captures.size)
        assertEquals(AvatarSampleGapSequence.Phase.values().toList(), captures.map { AvatarSampleGapSequence.phase(it) })
        assertFalse(AvatarSampleGapSequence.capture(0))
        assertFalse(AvatarSampleGapSequence.capture(AvatarSampleGapSequence.totalFrames + 30))
    }

    @Test fun activeGapRecoveryAndPartialLossMatchTheirChannelExpectations() {
        for (frame in 1..AvatarSampleGapSequence.totalFrames) {
            val now = 1000L + frame
            val input = AvatarSampleGapSequence.input(frame, active(now), now)
            assertTrue("Frame $frame", AvatarSampleGapSequence.channelsMatch(
                AvatarSampleGapSequence.phase(frame)!!, input))
        }
    }

    @Test fun lossClearsOldFaceAndGazeWhileBothFreshHandsRemainIndependent() {
        val frame = AvatarSampleGapSequence.FRAMES_PER_PHASE * 4 + 1
        val input = AvatarSampleGapSequence.input(frame, active(1000), 1000)
        assertEquals(0f, input.nativePacket!![2], 0f)
        assertEquals(0f, input.nativePacket[9], 0f)
        assertEquals(0f, input.expression.jawOpen, 0f)
        assertEquals(0f, input.gazeX, 0f); assertEquals(0f, input.gazeY, 0f)
        assertNotNull(input.hands[0][0]); assertNotNull(input.hands[1][0])
        assertEquals(-.3f, input.hands[0][0]!!.position.x, 0f)
        assertEquals(.3f, input.hands[1][0]!!.position.x, 0f)
    }

    @Test fun sameSyntheticControlsRecoverAfterGapWithoutMutatingTheirSource() {
        val source = active(1000)
        val active = AvatarSampleGapSequence.input(31, source, 1000)
        val gap = AvatarSampleGapSequence.input(61, source, 1000)
        val recovered = AvatarSampleGapSequence.input(91, source, 1000)
        assertArrayEquals(active.nativePacket, recovered.nativePacket, 0f)
        assertEquals(.8f, source.nativePacket!![388], 0f)
        assertEquals(0f, gap.nativePacket!![388], 0f)
        assertNotSame(source.nativePacket, recovered.nativePacket)
    }

    @Test fun aSyntheticFixtureStillCannotBeFreshenedAfterItExpires() {
        assertEquals(AvatarSamplePresentation.WAITING_MESSAGE,
            AvatarSampleGapSequence.input(31, active(1000), 2001).message)
        assertEquals(AvatarSamplePresentation.WAITING_MESSAGE,
            AvatarSampleGapSequence.input(121, active(1000), 2001).message)
    }

    @Test fun visualPhaseChecksRejectCachedOrIncorrectChannelInputs() {
        val active = active(1000)
        assertFalse(AvatarSampleGapSequence.channelsMatch(AvatarSampleGapSequence.Phase.GAP, active))
        assertFalse(AvatarSampleGapSequence.channelsMatch(AvatarSampleGapSequence.Phase.ACTIVE,
            AvatarSamplePresentation.neutral(1000)))
        assertFalse(AvatarSampleGapSequence.channelsMatch(AvatarSampleGapSequence.Phase.HANDS_WITHOUT_FACE,
            active))
        assertFalse(AvatarSampleGapSequence.channelsMatch(AvatarSampleGapSequence.Phase.GAP,
            AvatarSamplePresentation.neutral(1000).copy(hasPhysicalFace = true)))
    }
}
