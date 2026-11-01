package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class AvatarSamplePresentationTest {
    private fun tracked(timestamp: Long): WearerSnapshot {
        val packet = FloatArray(1060).apply {
            this[0] = 1f; this[6] = 1.6f; this[11] = 1f
            this[2] = 1f; this[364 + 24] = .8f; this[364 + 12] = .9f
            this[1] = 1f; this[12 + 12 * 8] = 1f; this[19 + 12 * 8] = 1f
            this[3] = 1f; this[156] = 1f; this[157] = .3f; this[437] = 1f
        }
        return WearerPacketDecoder.decode(packet, timestamp)
            .copy(hasPhysicalFace = false, hasVideoFace = true, hasEyes = true, gazeX = .3f, gazeY = -.2f,
                message = "Video estimates: face, eyes, 1 hand")
    }

    private fun assertNeutral(snapshot: WearerSnapshot) {
        val packet = snapshot.nativePacket!!
        assertEquals(1060, packet.size)
        assertTrue(packet.all { it.isFinite() })
        assertEquals(1f, packet[0], 0f)
        assertEquals(1.6f, packet[6], 0f)
        assertEquals(1f, packet[11], 0f)
        assertEquals(0f, packet[5], 0f); assertEquals(0f, packet[7], 0f)
        assertTrue((1..4).all { packet[it] == 0f })
        assertTrue((12 until packet.size).all { packet[it] == 0f })
        assertTrue(snapshot.body.all { it == null })
        assertTrue(snapshot.hands.flatten().all { it == null })
        assertFalse(snapshot.hasBody); assertFalse(snapshot.hasPhysicalFace); assertFalse(snapshot.hasEyes)
        assertFalse(snapshot.hasVideoFace)
        assertEquals(0f, snapshot.gazeX, 0f); assertEquals(0f, snapshot.gazeY, 0f)
        assertEquals(0f, snapshot.expression.jawOpen, 0f)
        assertEquals(0f, snapshot.expression.eyeBlinkLeft, 0f)
        assertEquals(AvatarSamplePresentation.WAITING_MESSAGE, snapshot.message)
    }

    @Test fun missingObservationHasRenderableNeutralIdentityWithoutClaimingTracking() {
        val neutral = AvatarSamplePresentation.input(null, 1000, 1050)
        assertNeutral(neutral)
        assertNotNull(neutral.head)
        assertEquals(1050, neutral.sampledAtMs)
    }

    @Test fun videoInferenceUsesBoundedOneSecondBudgetWithoutRefreshingAcquisitionTime() {
        val observation = tracked(1000)
        assertFalse(observation.isFresh(2000))
        val accepted = AvatarSamplePresentation.input(observation, 1000, 2000)
        assertEquals(1000, accepted.sampledAtMs)
        assertEquals(.8f, accepted.nativePacket!![388], 0f)
        assertTrue(accepted.hasEyes)
        assertTrue(accepted.hasVideoFace)
        assertNeutral(AvatarSamplePresentation.input(observation, 1000, 2001))
    }

    @Test fun futureAbsentAndMismatchedTimestampsCannotResurrectOldMotion() {
        for ((captured, now) in listOf(0L to 1000L, -1L to 1000L, 1000L to 999L,
            Long.MAX_VALUE to 1000L, 999L to 1050L)) {
            assertNeutral(AvatarSamplePresentation.input(tracked(1000), captured, now))
        }
    }

    @Test fun staleTrackedChannelsClearTogetherRatherThanReplayingFaceOrHands() {
        val neutral = AvatarSamplePresentation.input(tracked(1000), 1000, 2100)
        assertNeutral(neutral)
        assertEquals(2100, neutral.sampledAtMs)
    }

    @Test fun malformedPacketsCannotAnimateOrPoisonTheSample() {
        val valid = tracked(1000)
        val cases = listOf(null, valid.nativePacket!!.copyOf(642),
            valid.nativePacket.copyOf().apply { this[11] = 0f },
            valid.nativePacket.copyOf().apply { this[0] = 0f },
            valid.nativePacket.copyOf().apply { this[400] = Float.NaN })
        for (packet in cases) assertNeutral(AvatarSamplePresentation.input(valid.copy(nativePacket = packet), 1000, 1050))
        assertNeutral(AvatarSamplePresentation.input(valid.copy(head = null), 1000, 1050))
    }

    @Test fun independentHandObservationSurvivesMissingFaceAndEyes() {
        val observation = tracked(1000)
        val packet = observation.nativePacket!!.copyOf().apply {
            this[2] = 0f
            for (index in 364 until 434) this[index] = 0f
        }
        val handOnly = WearerPacketDecoder.decode(packet, 1000)
            .copy(message = "Video estimates: 1 hand · face unavailable")
        val accepted = AvatarSamplePresentation.input(handOnly, 1000, 1050)
        assertNotNull(accepted.hands[0][0])
        assertTrue(accepted.hasBody)
        assertEquals(1f, accepted.nativePacket!![3], 0f)
        assertEquals(0f, accepted.nativePacket[2], 0f)
        assertFalse(accepted.hasEyes)
        assertEquals(0f, accepted.expression.jawOpen, 0f)
    }

    @Test fun invalidEyesDisableOnlyGazeWhileOtherFreshChannelsRemainActive() {
        val accepted = AvatarSamplePresentation.input(tracked(1000).copy(gazeX = Float.NaN), 1000, 1050)
        assertFalse(accepted.hasEyes)
        assertEquals(0f, accepted.gazeX, 0f); assertEquals(0f, accepted.gazeY, 0f)
        assertEquals(.8f, accepted.nativePacket!![388], 0f)
        assertNotNull(accepted.hands[0][0])
    }

    @Test fun missingFaceCannotClaimAnOldEyeObservation() {
        val observation = tracked(1000)
        val packet = observation.nativePacket!!.copyOf().apply { this[2] = 0f }
        val accepted = AvatarSamplePresentation.input(observation.copy(nativePacket = packet), 1000, 1050)
        assertFalse(accepted.hasEyes)
        assertEquals(0f, accepted.gazeX, 0f); assertEquals(0f, accepted.gazeY, 0f)
        assertEquals(1f, accepted.nativePacket!![3], 0f)
        assertEquals(0f, accepted.nativePacket[388], 0f)
        assertFalse(accepted.hasVideoFace)
    }

    @Test fun inactiveFaceMetadataClearsPacketExpressionWithoutStoppingHands() {
        val accepted = AvatarSamplePresentation.input(tracked(1000).copy(hasVideoFace = false),1000,1050)
        assertFalse(accepted.hasVideoFace); assertFalse(accepted.hasEyes)
        assertEquals(0f,accepted.nativePacket!![2],0f)
        assertEquals(0f,accepted.nativePacket[388],0f)
        assertEquals(0f,accepted.expression.jawOpen,0f)
        assertNotNull(accepted.hands[0][0])
    }

    @Test fun freshVideoNeverClaimsPhysicalFaceAndBoundsEstimatedGaze() {
        val accepted = AvatarSamplePresentation.input(tracked(1000)
            .copy(hasPhysicalFace = true, gazeX = 20f, gazeY = -20f), 1000, 1050)
        assertFalse(accepted.hasPhysicalFace)
        assertEquals(.8f, accepted.gazeX, 0f); assertEquals(-.8f, accepted.gazeY, 0f)
    }

    @Test fun queuedInputAndNeutralPacketsOwnIndependentMemory() {
        val observation = tracked(1000)
        val accepted = AvatarSamplePresentation.input(observation, 1000, 1050)
        assertNotSame(observation.nativePacket, accepted.nativePacket)
        observation.nativePacket!![388] = 0f
        assertEquals(.8f, accepted.nativePacket!![388], 0f)
        val first = AvatarSamplePresentation.neutral(1000)
        val second = AvatarSamplePresentation.neutral(1050)
        first.nativePacket!![3] = 1f
        assertNeutral(second)
    }

    @Test fun noObservationAndRecoveryDoNotCarryOldBlinkOrHandValues() {
        val previous = AvatarSamplePresentation.input(tracked(1000), 1000, 1050)
        assertEquals(.9f, previous.nativePacket!![376], 0f)
        assertNeutral(AvatarSamplePresentation.input(null, 1100, 1150))
        val newPacket = FloatArray(1060).apply { this[0] = 1f; this[6] = 1.6f; this[11] = 1f }
        val recovered = AvatarSamplePresentation.input(WearerPacketDecoder.decode(newPacket, 1200), 1200, 1250)
        assertEquals(0f, recovered.nativePacket!![376], 0f)
        assertEquals(0f, recovered.nativePacket[3], 0f)
        assertFalse(recovered.hasEyes)
    }

    private fun channelTracked(timestamp: Long,faceAt: Long,leftAt: Long,rightAt: Long = 0L,
        revision: Long = 2L): WearerSnapshot {
        val original = tracked(timestamp)
        val packet = original.nativePacket!!.copyOf()
        if (rightAt > 0L) {
            packet[4] = 1f
            packet.copyInto(packet,260,156,260)
            packet.copyInto(packet,538,434,538)
            packet.copyInto(packet,12+17*8,12+12*8,12+13*8)
        }
        return WearerPacketDecoder.decode(packet,timestamp).copy(expression = original.expression,
            hasPhysicalFace = false,hasVideoFace = true,hasEyes = true,gazeX = .3f,gazeY = -.2f,
            videoChannels = AvatarVideoChannels(faceAt,leftAt,rightAt,revision))
    }

    @Test fun expiredFaceClearsHeadExpressionAndGazeWhileFreshHandsContinue() {
        val source = channelTracked(1800,1000,1800)
        val input = AvatarSamplePresentation.input(source,1800,2001)
        assertFalse(input.hasVideoFace); assertFalse(input.hasEyes)
        assertEquals(0f,input.nativePacket!![388],0f)
        assertEquals(0f,input.expression.jawOpen,0f)
        assertArrayEquals(floatArrayOf(0f,1.6f,0f,0f,0f,0f,1f),input.nativePacket.sliceArray(5..11),0f)
        assertNotNull(input.hands[0][0])
        assertEquals(1800,input.sampledAtMs)
        assertEquals(AvatarVideoChannels(0,1800,0,2),input.videoChannels)
        assertEquals(4,AvatarSamplePresentation.activeMask(input))
    }

    @Test fun eachHandExpiresIndependentlyIncludingItsWristAndBindControls() {
        val source = channelTracked(1800,1800,1000,1700)
        val input = AvatarSamplePresentation.input(source,1800,2001)
        assertTrue(input.hasVideoFace); assertTrue(input.hasEyes)
        assertTrue(input.hands[0].all { it == null }); assertNotNull(input.hands[1][0])
        assertNull(input.body[12]); assertNotNull(input.body[17])
        assertEquals(0f,input.nativePacket!![3],0f)
        assertEquals(0f,input.nativePacket[642],0f)
        assertTrue(input.nativePacket.sliceArray(156..259).all { it == 0f })
        assertTrue(input.nativePacket.sliceArray(434..537).all { it == 0f })
        assertTrue(input.nativePacket.sliceArray(644..851).all { it == 0f })
        assertEquals(1700,input.sampledAtMs)
        assertEquals(AvatarVideoChannels(1800,0,1700,2),input.videoChannels)
        assertEquals(11,AvatarSamplePresentation.activeMask(input))
    }

    @Test fun newerFaceCannotRefreshRetainedHandAtTheOneSecondBoundary() {
        val source = channelTracked(1800,1800,1000)
        val boundary = AvatarSamplePresentation.input(source,1800,2000)
        assertEquals(1000,boundary.sampledAtMs)
        assertNotNull(boundary.hands[0][0])
        val expired = AvatarSamplePresentation.input(source,1800,2001)
        assertTrue(expired.hasVideoFace)
        assertTrue(expired.hands[0].all { it == null })
        assertEquals(1800,expired.sampledAtMs)
        assertEquals(source.videoChannels!!.revision,expired.videoChannels!!.revision)
        assertEquals(3,AvatarSamplePresentation.activeMask(expired))
    }

    @Test fun missingOrFutureChannelTimeCannotClaimThatChannelWhileOthersStayValid() {
        for (faceAt in listOf(-1L,0L,1801L,Long.MAX_VALUE)) {
            val input = AvatarSamplePresentation.input(channelTracked(1800,faceAt,1700),1800,1850)
            assertFalse(input.hasVideoFace); assertFalse(input.hasEyes)
            assertNotNull(input.hands[0][0]); assertEquals(1700,input.sampledAtMs)
        }
        val noHand = AvatarSamplePresentation.input(channelTracked(1800,1800,1801),1800,1850)
        assertTrue(noHand.hasVideoFace)
        assertTrue(noHand.hands[0].all { it == null })
        assertEquals(1800,noHand.sampledAtMs)
    }

    @Test fun allExpiredChannelsBecomeNeutralWhileRetainingRevisionForTheTransition() {
        val input = AvatarSamplePresentation.input(channelTracked(1800,1000,1000),1800,2001)
        assertNeutral(input)
        assertEquals(2001,input.sampledAtMs)
        assertEquals(AvatarVideoChannels(0,0,0,2),input.videoChannels)
        assertEquals(0,AvatarSamplePresentation.activeMask(input))
    }

    @Test fun mergedSnapshotStillRequiresItsActualIncomingFrameTimestamp() {
        val source = channelTracked(1800,1800,1700)
        assertNeutral(AvatarSamplePresentation.input(source,1700,1850))
        assertNeutral(AvatarSamplePresentation.input(source,1800,1799))
        assertNeutral(AvatarSamplePresentation.input(source,1800,2801))
    }

    @Test fun conservativeCaptureTimeAndCopiesKeepEarlierChannelsUnrefreshed() {
        val source = channelTracked(2300,2300,2000,2200,9)
        val input = AvatarSamplePresentation.input(source,2300,2400)
        assertEquals(2000,input.sampledAtMs)
        assertEquals(source.videoChannels,input.videoChannels)
        assertFalse(AvatarStreamProtocol.fresh(input.sampledAtMs,2751))
        source.nativePacket!![157] = 90f
        assertEquals(.3f,input.nativePacket!![157],0f)
        assertEquals(15,AvatarSamplePresentation.activeMask(input))
    }
}
