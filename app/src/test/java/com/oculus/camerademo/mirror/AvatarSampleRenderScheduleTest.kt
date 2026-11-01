package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class AvatarSampleRenderScheduleTest {
    @Test fun activeCadenceMeasuresSuccessfulRenderStartsRatherThanCompletion() {
        val schedule = AvatarSampleRenderSchedule().apply { activeRefreshMs = 33L }
        val input = face(1000)
        schedule.rendered(input,1025,1000)
        assertFalse(schedule.needsRender(input,1032))
        assertTrue(schedule.needsRender(input,1033))
        schedule.rendered(input,1058,1033)
        assertTrue(schedule.needsRender(input,1066))
        assertEquals(1000L,input.sampledAtMs)
    }

    @Test fun freshHeldControlsAdvanceSdkAnimationWithoutRefreshingTheirCapture() {
        val schedule = AvatarSampleRenderSchedule().apply { activeRefreshMs = 33L }
        val input = AvatarSamplePresentation.neutral(1000).copy(hasVideoFace = true,
            videoChannels = AvatarVideoChannels(1000,0,0,7))
        schedule.rendered(input,1100)
        assertFalse(schedule.needsRender(input,1132))
        assertTrue(schedule.needsRender(input,1133))
        schedule.rendered(input,1133)
        assertFalse(schedule.needsRender(input,1165))
        assertTrue(schedule.needsRender(input,1166))
        assertEquals(1000L,input.sampledAtMs)
        assertEquals(1000L,input.videoChannels!!.faceCapturedAtMs)
        assertEquals(7L,input.videoChannels.revision)
    }

    @Test fun refreshCannotKeepAnExpiredObservationDrawing() {
        val schedule = AvatarSampleRenderSchedule().apply { activeRefreshMs = 33L }
        val input = face(1000).copy(videoChannels = AvatarVideoChannels(1000,0,0,1))
        schedule.rendered(input,1900)
        assertTrue(schedule.needsRender(input,2000))
        schedule.rendered(input,2000)
        assertFalse(schedule.needsRender(input,2033))
    }

    @Test fun activeRefreshDoesNotCreateAPermanentNeutralRenderLoop() {
        val schedule = AvatarSampleRenderSchedule().apply { activeRefreshMs = 33L }
        schedule.rendered(neutral(1000),1000)
        for (now in 1200L..3000L step 200L) {
            assertTrue(schedule.needsRender(neutral(now),now))
            schedule.rendered(neutral(now),now)
        }
        assertFalse(schedule.needsRender(neutral(4000),4000))
    }

    @Test fun refreshIntervalCanAdaptWithoutChangingObservationIdentity() {
        val schedule = AvatarSampleRenderSchedule().apply { activeRefreshMs = 33L }
        val input = face(1000)
        schedule.rendered(input,1100)
        schedule.activeRefreshMs = 100L
        assertFalse(schedule.needsRender(input,1199))
        assertTrue(schedule.needsRender(input,1200))
        schedule.activeRefreshMs = 0L
        assertFalse(schedule.needsRender(input,1300))
    }

    private fun neutral(timestamp: Long) = AvatarSamplePresentation.neutral(timestamp)
    private fun face(timestamp: Long) = neutral(timestamp).copy(hasVideoFace = true)
    private fun eyes(timestamp: Long) = neutral(timestamp).copy(hasEyes = true)
    private fun hand(timestamp: Long, side: Int) = neutral(timestamp).let { snapshot ->
        snapshot.copy(hands = List(2) { h -> List(26) { joint ->
            if (h == side && joint == 1) snapshot.head else null
        } }, hasBody = true)
    }

    @Test fun initialNeutralSettlesThenCachesWithoutRestartingForNewTimestamps() {
        val schedule = AvatarSampleRenderSchedule()
        val first = neutral(100)
        assertTrue(schedule.needsRender(first,100))
        schedule.rendered(first,100)
        assertFalse(schedule.needsRender(neutral(5000),299))
        for (now in 300L..2100L step 200L) {
            assertTrue(schedule.needsRender(neutral(now+5000),now))
            schedule.rendered(neutral(now+5000),now)
            assertFalse(schedule.needsRender(neutral(now+5001),now+199))
        }
        assertFalse(schedule.needsRender(neutral(10000),2300))
        assertFalse(schedule.needsRender(neutral(20000),20000))
    }

    @Test fun eachNewObservationRendersWhileRepeatedOrOlderInputReusesPixels() {
        val schedule = AvatarSampleRenderSchedule()
        val first = face(100)
        assertTrue(schedule.needsRender(first,100))
        schedule.rendered(first,100)
        assertFalse(schedule.needsRender(first,300))
        assertFalse(schedule.needsRender(face(99),300))
        assertTrue(schedule.needsRender(face(200),300))
        schedule.rendered(face(200),300)
        assertFalse(schedule.needsRender(face(200),500))
        assertTrue(schedule.needsRender(face(201),500))
    }

    @Test fun eyeOnlyAndEitherHandOnlyInputsIndependentlyDriveTheCadence() {
        for (input in listOf(eyes(100), hand(100, 0), hand(100, 1))) {
            val schedule = AvatarSampleRenderSchedule()
            schedule.rendered(neutral(50),50)
            assertTrue(schedule.needsRender(input,100))
            schedule.rendered(input,100)
            assertFalse(schedule.needsRender(input,300))
            assertTrue(schedule.needsRender(input.copy(sampledAtMs = 200),300))
        }
    }

    @Test fun trackingLossStartsSettlingAndRecoveryCancelsIt() {
        val schedule = AvatarSampleRenderSchedule()
        schedule.rendered(face(100),100)
        val lost = neutral(200)
        assertTrue(schedule.needsRender(lost,200))
        schedule.rendered(lost,200)
        assertFalse(schedule.needsRender(neutral(300),300))
        assertTrue(schedule.needsRender(neutral(400),400))
        assertTrue(schedule.needsRender(hand(400, 1),400))
        schedule.rendered(hand(400, 1),400)
        assertFalse(schedule.needsRender(hand(400, 1),800))
        assertTrue(schedule.needsRender(neutral(500),800))
    }

    @Test fun unavailableOrExpiredBitmapForcesNeutralEvenAfterNeutralWasRendered() {
        val schedule = AvatarSampleRenderSchedule()
        schedule.rendered(face(100),100)
        schedule.invalidate()
        val neutralAfterExpiry = neutral(601)
        assertTrue(schedule.needsRender(neutralAfterExpiry,601))
        schedule.rendered(neutralAfterExpiry,601)
        assertFalse(schedule.needsRender(neutral(700),700))
        schedule.invalidate()
        assertTrue(schedule.needsRender(neutral(800),800))
        schedule.rendered(neutral(800),800)
        assertFalse(schedule.needsRender(neutral(900),900))
    }

    @Test fun failedSdkRenderRemainsRetryableUntilOutputIsMarkedUsable() {
        val schedule = AvatarSampleRenderSchedule()
        val input = face(100)
        assertTrue(schedule.needsRender(input,100))
        // No rendered() call when SDK loading or drawing fails.
        assertTrue(schedule.needsRender(input,300))
        schedule.rendered(input,300)
        assertFalse(schedule.needsRender(input,500))
        schedule.invalidate()
        assertTrue(schedule.needsRender(input,500))
    }

    @Test fun pausedSessionResetCannotSuppressItsFirstFrameAfterResume() {
        val schedule = AvatarSampleRenderSchedule()
        schedule.rendered(face(100),100)
        schedule.reset()
        assertTrue(schedule.needsRender(face(100),200))
        schedule.rendered(face(100),200)
        assertFalse(schedule.needsRender(face(100),400))
        schedule.reset()
        assertTrue(schedule.needsRender(neutral(200),400))
    }

    @Test fun eitherHandLossSettlesRatherThanFreezingItsFirstNeutralPose() {
        for (side in 0..1) {
            val schedule = AvatarSampleRenderSchedule()
            schedule.rendered(hand(100,side),100)
            assertTrue(schedule.needsRender(neutral(200),200))
            schedule.rendered(neutral(200),200)
            for (now in 400L..2200L step 200L) {
                assertTrue(schedule.needsRender(neutral(now),now))
                schedule.rendered(neutral(now),now)
            }
            assertFalse(schedule.needsRender(neutral(2400),2400))
            assertTrue(schedule.needsRender(hand(2500,side),2500))
        }
    }

    @Test fun lateTickProducesFinalSettledFrameBeforeCaching() {
        val schedule = AvatarSampleRenderSchedule()
        schedule.rendered(hand(100,0),100)
        schedule.rendered(neutral(200),200)
        assertTrue(schedule.needsRender(neutral(2100),2100))
        schedule.rendered(neutral(2100),2100)
        assertFalse(schedule.needsRender(neutral(2200),2200))
        assertTrue(schedule.needsRender(neutral(10000),10000))
        schedule.rendered(neutral(10000),10000)
        assertFalse(schedule.needsRender(neutral(20000),20000))
    }

    @Test fun failedNeutralRenderRemainsDueAndDoesNotEndSettling() {
        val schedule = AvatarSampleRenderSchedule()
        schedule.rendered(hand(100,1),100)
        schedule.rendered(neutral(200),200)
        assertTrue(schedule.needsRender(neutral(400),400))
        // No rendered() call for the failed draw; later attempts remain due.
        assertTrue(schedule.needsRender(neutral(500),500))
        assertTrue(schedule.needsRender(neutral(5000),5000))
        schedule.rendered(neutral(5000),5000)
        assertFalse(schedule.needsRender(neutral(10000),10000))
    }

    @Test fun sameCaptureHandRefinementRendersByRevision() {
        val schedule = AvatarSampleRenderSchedule()
        val first = face(1000).copy(videoChannels = AvatarVideoChannels(1000,0,0,1))
        schedule.rendered(first,1150)
        val refined = hand(1000,0).copy(hasVideoFace = true,videoChannels = AvatarVideoChannels(1000,1000,0,2))
        assertTrue(schedule.needsRender(refined,1300))
        schedule.rendered(refined,1300)
        assertFalse(schedule.needsRender(refined,1350))
    }

    @Test fun changedFaceControlsRenderEvenWhenRetainedHandKeepsOldestClockUnchanged() {
        val schedule = AvatarSampleRenderSchedule()
        val first = hand(1000,0).copy(hasVideoFace = true,videoChannels = AvatarVideoChannels(1200,1000,0,3))
        schedule.rendered(first,1300)
        val changed = first.copy(videoChannels = AvatarVideoChannels(1300,1000,0,4))
        assertTrue(schedule.needsRender(changed,1400))
        schedule.rendered(changed,1400)
        assertFalse(schedule.needsRender(changed,1450))
    }

    @Test fun independentExpiryRendersTheNewActiveMaskWithoutChangingRevision() {
        val schedule = AvatarSampleRenderSchedule()
        val both = hand(1000,0).copy(hasVideoFace = true,videoChannels = AvatarVideoChannels(1800,1000,0,3))
        schedule.rendered(both,2000)
        val expiredHand = face(1800).copy(videoChannels = AvatarVideoChannels(1800,0,0,3))
        assertTrue(schedule.needsRender(expiredHand,2001))
        schedule.rendered(expiredHand,2001)
        assertFalse(schedule.needsRender(expiredHand,2050))
    }

    @Test fun refinementCanRenderWhenOldestChannelClockMovesBackward() {
        val schedule = AvatarSampleRenderSchedule()
        val first = face(1300).copy(videoChannels = AvatarVideoChannels(1300,0,0,1))
        schedule.rendered(first,1400)
        val refined = hand(1200,0).copy(hasVideoFace = true,videoChannels = AvatarVideoChannels(1300,1200,0,2))
        assertTrue(schedule.needsRender(refined,1500))
        schedule.rendered(refined,1500)
        assertFalse(schedule.needsRender(refined,1550))
    }

    @Test fun failedRefinementAndLifecycleResetCannotLoseASameFrameUpdate() {
        val schedule = AvatarSampleRenderSchedule()
        val first = face(1000).copy(videoChannels = AvatarVideoChannels(1000,0,0,1))
        schedule.rendered(first,1100)
        val next = first.copy(videoChannels = AvatarVideoChannels(1000,0,0,2))
        assertTrue(schedule.needsRender(next,1200))
        assertTrue(schedule.needsRender(next,1300))
        schedule.rendered(next,1300)
        assertFalse(schedule.needsRender(next,1350))
        schedule.reset()
        assertTrue(schedule.needsRender(next,1400))
    }

    @Test fun retainedFaceAndExpiredHandKeepEasingWithoutAnotherModelRevision() {
        val packet = FloatArray(1060).apply {
            this[0] = 1f; this[6] = 1.6f; this[11] = 1f; this[2] = 1f
            this[3] = 1f; this[156] = 1f; this[158] = 1.3f; this[437] = 1f
        }
        val source = WearerPacketDecoder.decode(packet,1800).copy(hasPhysicalFace = false,hasVideoFace = true,
            videoChannels = AvatarVideoChannels(1800,1000,0,2))
        val schedule = AvatarSampleRenderSchedule()
        schedule.rendered(AvatarSamplePresentation.input(source,1800,2000),2000)
        val lost = AvatarSamplePresentation.input(source,1800,2001)
        assertTrue(lost.hasVideoFace); assertTrue(lost.hands[0].all { it == null })
        assertTrue(schedule.needsRender(lost,2001)); schedule.rendered(lost,2001)
        assertFalse(schedule.needsRender(lost,2200))
        for (now in 2201L..2601L step 200L) {
            val unchanged = AvatarSamplePresentation.input(source,1800,now)
            assertEquals(1800,unchanged.sampledAtMs)
            assertEquals(2,unchanged.videoChannels!!.revision)
            assertTrue(unchanged.hasVideoFace)
            assertTrue(schedule.needsRender(unchanged,now))
            schedule.rendered(unchanged,now)
        }
        // The remaining face must still expire at its original capture deadline.
        val expiredFace = AvatarSamplePresentation.input(source,1800,2801)
        assertFalse(expiredFace.hasVideoFace); assertEquals(0,AvatarSamplePresentation.activeMask(expiredFace))
        assertTrue(schedule.needsRender(expiredFace,2801)); schedule.rendered(expiredFace,2801)
    }

    @Test fun partialSettlingIsBoundedAndNewFaceRevisionsDoNotExtendLostHandClock() {
        val schedule = AvatarSampleRenderSchedule()
        val both = hand(1000,0).copy(hasVideoFace = true,videoChannels = AvatarVideoChannels(1000,1000,0,1))
        schedule.rendered(both,1000)
        val lost = face(1100).copy(videoChannels = AvatarVideoChannels(1100,0,0,2))
        schedule.rendered(lost,1100)
        var current = lost
        for (now in 1300L..3100L step 200L) {
            // Fresh face observations keep the survivor valid. They do not
            // restart the hand's fixed two-second settling budget.
            if (now == 1900L || now == 2700L) current = face(now).copy(videoChannels =
                AvatarVideoChannels(now,0,0,if (now == 1900L) 3 else 4))
            assertTrue(schedule.needsRender(current,now))
            schedule.rendered(current,now)
        }
        assertFalse(schedule.needsRender(current,3300))
        assertEquals(2700,current.sampledAtMs)
        assertEquals(0,current.videoChannels!!.leftHandCapturedAtMs)
    }

    @Test fun recoveringTheRemovedHandCancelsOnlyItsSettling() {
        val schedule = AvatarSampleRenderSchedule()
        val both = hand(1000,0).copy(hasVideoFace = true,videoChannels = AvatarVideoChannels(1000,1000,0,1))
        schedule.rendered(both,1000)
        val lost = face(1100).copy(videoChannels = AvatarVideoChannels(1100,0,0,2))
        schedule.rendered(lost,1100)
        assertTrue(schedule.needsRender(lost,1300)); schedule.rendered(lost,1300)
        val recovered = hand(1350,0).copy(hasVideoFace = true,videoChannels = AvatarVideoChannels(1350,1350,0,3))
        assertTrue(schedule.needsRender(recovered,1350)); schedule.rendered(recovered,1350)
        assertFalse(schedule.needsRender(recovered,1550))
    }

    @Test fun eyeRecoveryDoesNotCancelTheStillMissingHandSettlement() {
        val schedule = AvatarSampleRenderSchedule()
        val all = hand(1000,0).copy(hasVideoFace = true,hasEyes = true,videoChannels = AvatarVideoChannels(1000,1000,0,1))
        schedule.rendered(all,1000)
        val lost = face(1100).copy(videoChannels = AvatarVideoChannels(1100,0,0,2))
        schedule.rendered(lost,1100)
        val eyesRecovered = face(1200).copy(hasEyes = true,videoChannels = AvatarVideoChannels(1200,0,0,3))
        schedule.rendered(eyesRecovered,1200)
        assertTrue(schedule.needsRender(eyesRecovered,1400))
        schedule.rendered(eyesRecovered,1400)
        assertEquals(0,eyesRecovered.videoChannels!!.leftHandCapturedAtMs)
    }

    @Test fun legacyPartialLossKeepsItsExistingCacheBehavior() {
        val schedule = AvatarSampleRenderSchedule()
        schedule.rendered(hand(1000,0).copy(hasVideoFace = true),1000)
        val faceOnly = face(1100)
        assertTrue(schedule.needsRender(faceOnly,1100)); schedule.rendered(faceOnly,1100)
        assertFalse(schedule.needsRender(faceOnly,1300))
        assertFalse(schedule.needsRender(faceOnly,1500))
    }

    @Test fun partialLossClockStartsOnlyAfterSuccessfulDrawAndResetClearsIt() {
        val schedule = AvatarSampleRenderSchedule()
        val both = hand(1000,0).copy(hasVideoFace = true,videoChannels = AvatarVideoChannels(1000,1000,0,1))
        schedule.rendered(both,1000)
        val lost = face(1100).copy(videoChannels = AvatarVideoChannels(1100,0,0,2))
        assertTrue(schedule.needsRender(lost,1100))
        assertTrue(schedule.needsRender(lost,1200))
        schedule.rendered(lost,1200)
        assertFalse(schedule.needsRender(lost,1399))
        assertTrue(schedule.needsRender(lost,1400))
        schedule.reset(); schedule.rendered(lost,1400)
        assertFalse(schedule.needsRender(lost,1600))
    }
}
