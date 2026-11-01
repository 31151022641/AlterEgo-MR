package com.oculus.camerademo.mirror

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarSampleAnimationTimingTest {
    @Test fun firstRenderAndInvalidClockUseSafeInitialStep() {
        for ((previous,now) in listOf(0L to 1000L,1000L to 1000L,1000L to 999L))
            assertEquals(1f/15f,AvatarSampleAnimationTiming.deltaSeconds(previous,now),.00001f)
    }
    @Test fun slowerCameraCadenceAdvancesRealAnimationTime() {
        assertEquals(.12f,AvatarSampleAnimationTiming.deltaSeconds(1000,1120),.00001f)
        assertEquals(.20f,AvatarSampleAnimationTiming.deltaSeconds(1000,1200),.00001f)
    }
    @Test fun pauseCannotReplaySecondsOfCachedInput() {
        assertEquals(.25f,AvatarSampleAnimationTiming.deltaSeconds(1000,21000),.00001f)
    }
    @Test fun veryShortIntervalsUseBoundedSimulationStep() {
        assertEquals(1f/120f,AvatarSampleAnimationTiming.deltaSeconds(1000,1001),.00001f)
    }
}
