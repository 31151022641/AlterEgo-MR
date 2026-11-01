package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class AvatarStreamTimingTest {
    @Test fun completedWriteCannotBeClosedByLateQueuedTimer() {
        val write = AvatarWriteDeadline()
        write.complete()
        assertFalse(write.claimTimeout())
    }
    @Test fun blockedWriteHasExactlyOneTimeoutOwner() {
        val write = AvatarWriteDeadline()
        assertTrue(write.claimTimeout())
        assertFalse(write.claimTimeout())
        write.complete()
        assertFalse(write.claimTimeout())
    }
    @Test fun PreviousWriteCannotExpireTheNextWritesDeadline() {
        val previous = AvatarWriteDeadline(); previous.complete()
        val next = AvatarWriteDeadline()
        assertFalse(previous.claimTimeout())
        assertTrue(next.claimTimeout())
    }
    @Test fun briefCameraGapDoesNotTriggerExpensiveRestart() {
        assertFalse(AvatarStreamTiming.restartDue(1000,1751))
        assertFalse(AvatarStreamTiming.restartDue(1000,5999))
        assertTrue(AvatarStreamTiming.restartDue(1000,6000))
        assertFalse(AvatarStreamTiming.restartDue(1000,999))
        assertFalse(AvatarStreamTiming.restartDue(0,6000))
    }
    @Test fun cameraGapRetainsShortGraceButClearsAtTwoSeconds() {
        assertFalse(AvatarStreamTiming.imageExpired(1000,1751))
        assertFalse(AvatarStreamTiming.imageExpired(1000,2999))
        assertTrue(AvatarStreamTiming.imageExpired(1000,3000))
        assertFalse(AvatarStreamTiming.imageExpired(1000,999))
    }
    @Test fun noImageControlsWaitForGraceAndAreBoundedToTwicePerSecond() {
        assertFalse(AvatarStreamTiming.noImageDue(1000,1000,2999))
        assertTrue(AvatarStreamTiming.noImageDue(1000,1000,3000))
        assertFalse(AvatarStreamTiming.noImageDue(1000,3000,3499))
        assertTrue(AvatarStreamTiming.noImageDue(1000,3000,3500))
        assertFalse(AvatarStreamTiming.noImageDue(3490,3000,3500))
    }
    @Test fun explicitCameraFailureKeepsBlankControlAliveWithoutTwoSecondWait() {
        assertTrue(AvatarStreamTiming.noImageDue(1000,1000,1500,true))
        assertFalse(AvatarStreamTiming.noImageDue(1000,1500,1999,true))
        assertTrue(AvatarStreamTiming.noImageDue(1000,1500,2000,true))
    }
}
