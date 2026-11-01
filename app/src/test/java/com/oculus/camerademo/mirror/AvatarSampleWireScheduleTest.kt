package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class AvatarSampleWireScheduleTest {
    @Test fun sameFrameHandRefinementCanFollowFaceWithoutDuplicatingTheSameRender() {
        val schedule = AvatarSampleWireSchedule()
        assertTrue(schedule.accepts(1000,1,3,1100))
        schedule.offered(1000,1,3)
        assertFalse(schedule.accepts(1000,1,3,1200))
        assertTrue(schedule.accepts(1000,2,15,1200))
        schedule.offered(1000,2,15)
        assertFalse(schedule.accepts(1000,2,15,1300))
    }

    @Test fun delayedHandCannotMoveTheWindowsCameraClockBackward() {
        val schedule = AvatarSampleWireSchedule()
        schedule.offered(1200,3,3)
        assertFalse(schedule.accepts(1100,4,15,1300))
        assertTrue(schedule.accepts(1200,5,15,1350))
        schedule.offered(1200,5,15)
        assertTrue(schedule.accepts(1300,6,15,1400))
    }

    @Test fun aNewRevisionCannotRefreshAnExpiredOrFutureCapture() {
        val schedule = AvatarSampleWireSchedule()
        assertTrue(schedule.accepts(1000,1,15,1750))
        assertFalse(schedule.accepts(1000,2,15,1751))
        assertFalse(schedule.accepts(1800,3,15,1750))
        assertFalse(schedule.accepts(0,4,0,1750))
    }

    @Test fun channelExpiryCanRefineAFrameAndNewRunResetsOrdering() {
        val schedule = AvatarSampleWireSchedule()
        schedule.offered(1000,7,15)
        assertTrue(schedule.accepts(1000,7,3,1200))
        schedule.offered(1000,7,3)
        assertFalse(schedule.accepts(1000,7,3,1250))
        schedule.reset()
        assertTrue(schedule.accepts(900,1,3,1000))
    }

    @Test fun distinctSettlingPicturesKeepTheirOriginalCaptureDeadline() {
        val schedule = AvatarSampleWireSchedule()
        schedule.offered(1000,10,3)
        assertTrue(schedule.accepts(1000,11,3,1400))
        schedule.offered(1000,11,3)
        assertTrue(schedule.accepts(1000,12,3,1600))
        schedule.offered(1000,12,3)
        assertFalse(schedule.accepts(1000,13,3,1800))
    }
}
