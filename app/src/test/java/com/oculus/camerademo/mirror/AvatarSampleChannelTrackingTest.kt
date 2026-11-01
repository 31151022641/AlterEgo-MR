package com.oculus.camerademo.mirror

import com.oculus.camerademo.ml.FaceEmotionState
import org.junit.Assert.*
import org.junit.Test

class AvatarSampleChannelTrackingTest {
    private val run = 7L
    private val selection = 3L
    private fun merger() = AvatarSampleChannelMerger().apply { reset(run,selection) }
    private fun faceObservation(jaw: Float = .8f,x: Float = .4f): AvatarVideoFace {
        val matrix = FloatArray(16).apply { this[0] = 1f; this[5] = 1f; this[10] = 1f; this[15] = 1f }
        return AvatarVideoFace(x,.15f,x+.2f,.45f,matrix,FaceEmotionState(jawOpen = jaw),.3f,-.2f)
    }
    private fun face(timestamp: Long,jaw: Float = .8f) =
        AvatarVideoTracking().update(faceObservation(jaw),emptyList(),timestamp)
    private fun handObservation(timestamp: Long,x: Float): AvatarVideoHand {
        val points = mutableListOf(AvatarVideoLandmark(0f,0f,0f))
        points += listOf(AvatarVideoLandmark(.035f,-.025f,0f),AvatarVideoLandmark(.058f,-.05f,0f),
            AvatarVideoLandmark(.074f,-.069f,0f),AvatarVideoLandmark(.09f,-.084f,0f))
        for ((offset,length) in listOf(.032f to .038f,.01f to .043f,-.015f to .04f,-.039f to .031f)) {
            points += AvatarVideoLandmark(offset,-.07f,0f)
            points += AvatarVideoLandmark(offset,-.07f-length,0f)
            points += AvatarVideoLandmark(offset,-.07f-length*1.6f,0f)
            points += AvatarVideoLandmark(offset,-.07f-length*2f,0f)
        }
        return AvatarVideoHand(AvatarVideoHandSide.LEFT,.95f,
            points.map { it.copy(x = x+it.x,y = .6f+it.y) },points,timestamp)
    }
    private fun hands(timestamp: Long,left: Boolean = true,right: Boolean = true): WearerSnapshot {
        val packet = FloatArray(1060).apply { this[0] = 1f; this[6] = 1.6f; this[11] = 1f }
        for (side in 0..1) if (if (side == 0) left else right) {
            packet[1] = 1f; packet[3+side] = 1f
            val wrist = 12+(if (side == 0) 12 else 17)*8
            packet[wrist] = 1f; packet[wrist+1] = if (side == 0) -.3f else .3f; packet[wrist+2] = 1.3f; packet[wrist+7] = 1f
            for (joint in 0..25) {
                val position = 156+side*104+joint*4
                packet[position] = 1f; packet[position+1] = if (side == 0) -.3f else .3f; packet[position+2] = 1.3f
                packet[434+side*104+joint*4+3] = 1f
                val bind = 644+side*208+joint*8
                packet[bind] = if (joint == 1) -1f else 1f; packet[bind+7] = 1f
            }
            packet[642+side] = 1f
        }
        return WearerPacketDecoder.decode(packet,timestamp)
    }
    private fun putFace(merger: AvatarSampleChannelMerger,timestamp: Long,observation: WearerSnapshot? = face(timestamp),now: Long = timestamp) =
        merger.updateFace(observation,timestamp,now,run,selection)!!
    private fun putHands(merger: AvatarSampleChannelMerger,timestamp: Long,observation: WearerSnapshot? = hands(timestamp),now: Long = timestamp) =
        merger.updateHands(observation,timestamp,now,run,selection)!!

    @Test fun faceIsPublishableBeforeHandsFromTheSameFrameFinish() {
        val first = putFace(merger(),1000)
        assertTrue(first.hasVideoFace); assertTrue(first.hasEyes)
        assertTrue(first.hands.flatten().all { it == null })
        assertEquals(1000,first.sampledAtMs)
        assertEquals(AvatarVideoChannels(1000,0,0,1),first.videoChannels)
        assertFalse(first.hasPhysicalFace)
    }

    @Test fun sameFrameHandRefinementKeepsTheExistingFaceAndChangesRevision() {
        val merger = merger()
        val first = putFace(merger,1000)
        val misleadingFace = hands(1000).copy(expression = FaceEmotionState(jawOpen = .1f),hasVideoFace = true,hasEyes = false)
        val refined = putHands(merger,1000,misleadingFace,1250)
        assertEquals(1000,refined.sampledAtMs)
        assertEquals(AvatarVideoChannels(1000,1000,1000,2),refined.videoChannels)
        assertArrayEquals(first.nativePacket!!.sliceArray(5..11),refined.nativePacket!!.sliceArray(5..11),0f)
        assertArrayEquals(first.nativePacket.sliceArray(364..433),refined.nativePacket.sliceArray(364..433),0f)
        assertEquals(first.expression,refined.expression); assertTrue(refined.hasEyes)
        assertTrue(refined.hands.all { hand -> hand.all { it != null } })
    }

    @Test fun newerFaceRetainsHandsWithTheirOriginalAcquisitionTime() {
        val merger = merger()
        putFace(merger,1000); val old = putHands(merger,1000)
        val next = putFace(merger,1200,face(1200,.2f),1300)
        assertEquals(1200,next.sampledAtMs)
        assertEquals(AvatarVideoChannels(1200,1000,1000,3),next.videoChannels)
        assertEquals(.2f,next.nativePacket!![388],0f)
        assertArrayEquals(old.nativePacket!!.sliceArray(156..363),next.nativePacket.sliceArray(156..363),0f)
        assertEquals(1000,AvatarSamplePresentation.input(next,1200,1300).sampledAtMs)
    }

    @Test fun definitiveFaceMissClearsFaceAndEyesWithoutClearingHands() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000)
        val lost = putFace(merger,1200,null)
        assertFalse(lost.hasVideoFace); assertFalse(lost.hasEyes)
        assertEquals(0f,lost.expression.jawOpen,0f)
        assertEquals(0L,lost.videoChannels!!.faceCapturedAtMs)
        assertEquals(1000,lost.videoChannels.leftHandCapturedAtMs)
        assertTrue(lost.hands.all { it.all { joint -> joint != null } })
        assertArrayEquals(floatArrayOf(0f,1.6f,0f,0f,0f,0f,1f),lost.nativePacket!!.sliceArray(5..11),0f)
    }

    @Test fun oneHandMissClearsAllItsControlsWithoutClearingFaceOrOtherHand() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000)
        putFace(merger,1200)
        val lost = putHands(merger,1200,hands(1200,left = false))
        assertTrue(lost.hasVideoFace); assertTrue(lost.hasEyes)
        assertTrue(lost.hands[0].all { it == null }); assertTrue(lost.hands[1].all { it != null })
        assertEquals(AvatarVideoChannels(1200,0,1200,4),lost.videoChannels)
        val packet = lost.nativePacket!!
        assertEquals(0f,packet[3],0f); assertEquals(0f,packet[642],0f)
        assertTrue(packet.sliceArray(156..259).all { it == 0f })
        assertTrue(packet.sliceArray(434..537).all { it == 0f })
        assertTrue(packet.sliceArray(644..851).all { it == 0f })
        assertNull(lost.body[12]); assertNotNull(lost.body[17])
    }

    @Test fun continuingFacesCannotRefreshAnExpiredHand() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000)
        val boundary = putFace(merger,2000)
        assertEquals(1000,boundary.videoChannels!!.leftHandCapturedAtMs)
        val expired = putFace(merger,2001)
        assertTrue(expired.hasVideoFace)
        assertTrue(expired.hands.flatten().all { it == null })
        assertEquals(0L,expired.videoChannels!!.leftHandCapturedAtMs)
        assertEquals(2001,AvatarSamplePresentation.input(expired,2001,2050).sampledAtMs)
    }

    @Test fun duplicateOrOlderChannelResultsAreIgnoredWithoutResettingCurrentControls() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000)
        val latest = putFace(merger,1200)
        assertNull(merger.updateFace(null,1200,1250,run,selection))
        assertNull(merger.updateHands(null,999,1250,run,selection))
        assertNull(merger.updateFace(null,1300,1299,run,selection))
        val next = putHands(merger,1200)
        assertTrue(next.hasVideoFace)
        assertEquals(latest.videoChannels!!.revision+1,next.videoChannels!!.revision)
        assertEquals(1200,next.videoChannels.faceCapturedAtMs)
    }

    @Test fun eitherCompletionOrderCanRefineTheSameAcquisition() {
        val merger = merger()
        val first = putHands(merger,1000)
        assertFalse(first.hasVideoFace)
        val refined = putFace(merger,1000)
        assertEquals(AvatarVideoChannels(1000,1000,1000,2),refined.videoChannels)
        assertTrue(refined.hasVideoFace); assertTrue(refined.hands.all { it.all { joint -> joint != null } })
    }

    @Test fun replacementRunRejectsOldResultsAndDoesNotCarryItsChannels() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000)
        merger.reset(run+1,selection)
        assertNull(merger.updateHands(hands(1100),1100,1200,run,selection))
        val next = merger.updateFace(face(1300),1300,1350,run+1,selection)!!
        assertEquals(AvatarVideoChannels(1300,0,0,1),next.videoChannels)
        assertTrue(next.hands.flatten().all { it == null })
    }

    @Test fun changedSelectionRejectsOldResultsAndRestartsRevision() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000)
        merger.reset(run,selection+1)
        assertNull(merger.updateFace(face(1100),1100,1200,run,selection))
        val next = merger.updateHands(hands(1300,left = false),1300,1350,run,selection+1)!!
        assertEquals(AvatarVideoChannels(0,0,1300,1),next.videoChannels)
        assertFalse(next.hasVideoFace)
    }

    @Test fun storedAndPublishedPacketsOwnIndependentMemory() {
        val merger = merger()
        val observedFace = face(1000)
        val observedHands = hands(1000)
        val first = putFace(merger,1000,observedFace)
        val refined = putHands(merger,1000,observedHands)
        observedFace.nativePacket!![388] = 0f; observedHands.nativePacket!![157] = 99f
        first.nativePacket!![388] = 0f; refined.nativePacket!![157] = 88f
        val retained = putFace(merger,1100)
        assertEquals(-.3f,retained.nativePacket!![157],0f)
        val handRefinement = putHands(merger,1100)
        assertEquals(.8f,handRefinement.nativePacket!![388],0f)
    }

    @Test fun invalidFacePacketClearsOnlyFaceInsteadOfKeepingItsLastExpression() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000)
        val invalid = face(1100).copy(nativePacket = face(1100).nativePacket!!.copyOf().apply { this[388] = Float.NaN })
        val lost = putFace(merger,1100,invalid)
        assertFalse(lost.hasVideoFace); assertFalse(lost.hasEyes)
        assertTrue(lost.hands.all { it.all { joint -> joint != null } })
        assertTrue(lost.nativePacket!!.all { it.isFinite() })
    }

    @Test fun mismatchedHandCaptureClearsOnlyHandsAndKeepsCurrentFace() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000); putFace(merger,1100)
        val lost = putHands(merger,1100,hands(1000))
        assertTrue(lost.hasVideoFace); assertTrue(lost.hasEyes)
        assertTrue(lost.hands.flatten().all { it == null })
        assertEquals(1100,lost.videoChannels!!.faceCapturedAtMs)
    }

    @Test fun handRefinementsDoNotChangeTheFaceFilterClockOrReapplyItsControls() {
        val tracking = AvatarSampleChannelTracking().apply { reset(run,selection) }
        val baseline = AvatarVideoTracking()
        tracking.updateFace(faceObservation(.2f),1000,1050,run,selection)
        baseline.update(faceObservation(.2f),emptyList(),1000)
        tracking.updateHands(faceObservation(.9f),emptyList(),1000,1200,run,selection)
        val actual = tracking.updateFace(faceObservation(.9f,.5f),1050,1250,run,selection)!!
        val expected = baseline.update(faceObservation(.9f,.5f),emptyList(),1050)
        assertArrayEquals(expected.nativePacket!!.sliceArray(5..11),actual.nativePacket!!.sliceArray(5..11),.00001f)
        assertArrayEquals(expected.nativePacket.sliceArray(364..433),actual.nativePacket.sliceArray(364..433),.00001f)
    }

    @Test fun handSmoothingUsesItsActualCaptureDeltaDespiteFaceFirstPublication() {
        val tracking = AvatarSampleChannelTracking().apply { reset(run,selection) }
        val baseline = AvatarVideoTracking()
        val face = faceObservation()
        tracking.updateFace(face,1000,1050,run,selection)
        tracking.updateHands(face,listOf(handObservation(1000,.35f)),1000,1200,run,selection)
        baseline.update(face,listOf(handObservation(1000,.35f)),1000)
        tracking.updateFace(face,1050,1250,run,selection)
        val actual = tracking.updateHands(face,listOf(handObservation(1050,.65f)),1050,1350,run,selection)!!
        val expected = baseline.update(face,listOf(handObservation(1050,.65f)),1050)
        assertTrue(actual.hands[0].all { it != null })
        assertArrayEquals(expected.nativePacket!!.sliceArray(156..259),actual.nativePacket!!.sliceArray(156..259),.00001f)
        assertArrayEquals(expected.nativePacket.sliceArray(434..537),actual.nativePacket.sliceArray(434..537),.00001f)
    }

    @Test fun laterHandCompletionKeepsItsOwnClockWhilePublicationClockNeverMovesBackward() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000)
        putFace(merger,1300)
        val delayed = putHands(merger,1200,now = 1400)
        assertEquals(1300,delayed.sampledAtMs)
        assertEquals(AvatarVideoChannels(1300,1200,1200,4),delayed.videoChannels)
        assertEquals(1200,AvatarSamplePresentation.input(delayed,1300,1400).sampledAtMs)
        assertTrue(delayed.hasVideoFace); assertTrue(delayed.hands.all { it.all { joint -> joint != null } })
    }

    @Test fun laterFaceCompletionIsAcceptedWithoutRetimestampingNewerHands() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000)
        putHands(merger,1400)
        val delayed = putFace(merger,1200,face(1200,.2f),1450)
        assertEquals(1400,delayed.sampledAtMs)
        assertEquals(AvatarVideoChannels(1200,1400,1400,4),delayed.videoChannels)
        assertEquals(.2f,delayed.nativePacket!![388],0f)
        assertEquals(1200,AvatarSamplePresentation.input(delayed,1400,1450).sampledAtMs)
    }

    @Test fun aLateStaleChannelCannotBorrowFreshnessFromTheOtherWorkersClock() {
        val merger = merger()
        putHands(merger,900)
        putFace(merger,2000)
        val stale = putHands(merger,1000,now = 2050)
        assertEquals(2000,stale.sampledAtMs)
        assertTrue(stale.hasVideoFace)
        assertEquals(AvatarVideoChannels(2000,0,0,3),stale.videoChannels)
        assertTrue(stale.hands.flatten().all { it == null })
        val input = AvatarSamplePresentation.input(stale,2000,2050)
        assertEquals(2000,input.sampledAtMs)
        assertEquals(3,AvatarSamplePresentation.activeMask(input))
    }

    @Test fun oppositeChannelMissDoesNotClearTheFreshSurvivorOrMovePublicationBackward() {
        val merger = merger()
        putHands(merger,1000); putFace(merger,1400)
        val handMiss = putHands(merger,1200,null,1500)
        assertEquals(1400,handMiss.sampledAtMs)
        assertTrue(handMiss.hasVideoFace); assertTrue(handMiss.hasEyes)
        assertTrue(handMiss.hands.flatten().all { it == null })
        val newHands = putHands(merger,1600)
        val faceMiss = putFace(merger,1500,null,1700)
        assertEquals(1600,faceMiss.sampledAtMs)
        assertFalse(faceMiss.hasVideoFace); assertFalse(faceMiss.hasEyes)
        assertEquals(newHands.videoChannels!!.leftHandCapturedAtMs,faceMiss.videoChannels!!.leftHandCapturedAtMs)
        assertTrue(faceMiss.hands.all { it.all { joint -> joint != null } })
    }

    @Test fun lateSameChannelResultCannotUndoItsNewerMissOrRecovery() {
        val merger = merger()
        putFace(merger,1000); putHands(merger,1000)
        putFace(merger,1400,null)
        assertNull(merger.updateFace(face(1300),1300,1500,run,selection))
        val recovered = putFace(merger,1600)
        assertNull(merger.updateFace(null,1500,1700,run,selection))
        val next = putHands(merger,1500,now = 1750)
        assertEquals(1600,next.sampledAtMs)
        assertEquals(recovered.videoChannels!!.faceCapturedAtMs,next.videoChannels!!.faceCapturedAtMs)
        assertTrue(next.hasVideoFace)
    }

    @Test fun channelFramingCannotPublishExpressionsFromTheOtherFrame() {
        val tracking = AvatarSampleChannelTracking().apply { reset(run,selection) }
        val observed = tracking.updateFace(faceObservation(.8f),1300,1400,run,selection)!!
        // A hands-only completion uses geometry for projection, but must leave
        // the accepted face controls and their real1300 capture time intact.
        val refined = tracking.updateHands(emptyList(),1200,1450,run,selection)!!
        assertEquals(1300,refined.sampledAtMs)
        assertEquals(1300,refined.videoChannels!!.faceCapturedAtMs)
        assertEquals(observed.expression,refined.expression)
        assertArrayEquals(observed.nativePacket!!.sliceArray(364..433),refined.nativePacket!!.sliceArray(364..433),0f)
    }

    @Test fun repeatedHandsCannotExtendTheLastObservedFaceGeometryBeyondFiveSeconds() {
        val tracking = AvatarSampleChannelTracking().apply { reset(run,selection) }
        tracking.updateFace(faceObservation(),1000,1050,run,selection)
        tracking.updateHands(listOf(handObservation(1000,.35f)),1000,1100,run,selection)
        val beforeExpiry = tracking.updateHands(listOf(handObservation(5900,.35f)),5900,5900,run,selection)!!
        assertFalse(beforeExpiry.hasVideoFace)
        assertTrue(beforeExpiry.hands[0].all { it != null })
        val expired = tracking.updateHands(listOf(handObservation(6100,.35f)),6100,6100,run,selection)!!
        val expected = AvatarVideoTracking().update(null,listOf(handObservation(6100,.35f)),6100)
        assertArrayEquals(expected.nativePacket!!.sliceArray(156..259),expired.nativePacket!!.sliceArray(156..259),.00001f)
        assertEquals(AvatarVideoChannels(0,6100,0,4),expired.videoChannels)
    }

    @Test fun newRunCannotProjectHandsThroughThePreviousRunsFaceCalibration() {
        val tracking = AvatarSampleChannelTracking().apply { reset(run,selection) }
        tracking.updateFace(faceObservation(),1000,1050,run,selection)
        tracking.updateHands(listOf(handObservation(1000,.35f)),1000,1100,run,selection)
        tracking.reset(run+1,selection)
        assertNull(tracking.updateHands(listOf(handObservation(1200,.35f)),1200,1250,run,selection))
        val current = tracking.updateHands(listOf(handObservation(1300,.35f)),1300,1350,run+1,selection)!!
        val expected = AvatarVideoTracking().update(null,listOf(handObservation(1300,.35f)),1300)
        assertArrayEquals(expected.nativePacket!!.sliceArray(156..259),current.nativePacket!!.sliceArray(156..259),.00001f)
        assertEquals(AvatarVideoChannels(0,1300,0,1),current.videoChannels)
    }
}
