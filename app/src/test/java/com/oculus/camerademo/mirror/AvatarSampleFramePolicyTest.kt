package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class AvatarSampleFramePolicyTest {
    @Test fun currentCameraInputIsUsableAtTheFreshnessBoundary() {
        assertTrue(AvatarSampleFramePolicy.fresh(1000, 1000))
        assertTrue(AvatarSampleFramePolicy.fresh(1000, 2000))
        assertFalse(AvatarSampleFramePolicy.fresh(1000, 2001))
    }

    @Test fun absentFutureOrRewoundCameraTimestampsCannotAnimateTheSample() {
        assertFalse(AvatarSampleFramePolicy.fresh(0, 1000))
        assertFalse(AvatarSampleFramePolicy.fresh(-1, 1000))
        assertFalse(AvatarSampleFramePolicy.fresh(1000, 999))
        assertFalse(AvatarSampleFramePolicy.fresh(Long.MAX_VALUE, 1000))
    }

    @Test fun onlyTheCurrentCameraRunAndSelectionCanPublish() {
        assertTrue(AvatarSampleFramePolicy.accepts(3, 3, 8, 8, 1000, 1050, true))
        assertFalse(AvatarSampleFramePolicy.accepts(2, 3, 8, 8, 1000, 1050, true))
        assertFalse(AvatarSampleFramePolicy.accepts(3, 3, 7, 8, 1000, 1050, true))
    }

    @Test fun switchingAwayAndBackStillRejectsAnEarlierSampleFrame() {
        // The source is Meta sample again, but its selection epoch has changed.
        assertFalse(AvatarSampleFramePolicy.accepts(3, 3, 8, 10, 1000, 1050, true))
        assertTrue(AvatarSampleFramePolicy.accepts(3, 3, 10, 10, 1000, 1050, true))
    }

    @Test fun pauseOrAStalledMainThreadCannotPublishQueuedInput() {
        assertFalse(AvatarSampleFramePolicy.accepts(3, 3, 8, 8, 1000, 1050, false))
        assertFalse(AvatarSampleFramePolicy.accepts(3, 3, 8, 8, 1000, 2001, true))
    }

    @Test fun slowLiveInferenceSurvivesDrawingAndTheNextInferenceInterval() {
        // Measured live acquisition age 479 ms + 49 ms SDK draw + 429 ms
        // until the next estimate. Accepting only before draw lost the hand.
        assertTrue(AvatarSampleFramePolicy.fresh(1000, 1479))
        assertTrue(AvatarSampleFramePolicy.fresh(1000, 1528))
        assertTrue(AvatarSampleFramePolicy.fresh(1000, 1957))
        assertFalse(AvatarSampleFramePolicy.fresh(1000, 2001))
    }

    @Test fun sampleQueueBoundariesAllowSlowEstimatesWithoutExtendingVideoTransport() {
        assertTrue(AvatarSampleFramePolicy.previewFresh(true,1000,1900))
        assertFalse(AvatarSampleFramePolicy.previewFresh(false,1000,1900))
        assertFalse(AvatarStreamProtocol.fresh(1000,1900))
        assertFalse(AvatarSampleFramePolicy.previewFresh(true,1000,2001))
        assertTrue(AvatarSampleFramePolicy.previewFresh(false,1000,1750))
        assertFalse(AvatarSampleFramePolicy.previewFresh(false,1000,1751))
    }

}
