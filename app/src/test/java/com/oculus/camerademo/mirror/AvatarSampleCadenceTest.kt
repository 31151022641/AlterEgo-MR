package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class AvatarSampleCadenceTest {
    @Test fun renderingTimeConsumesTheTargetInterval() {
        assertEquals(8L,AvatarSampleCadence.delayMs(1000,1025,33))
        assertEquals(33L,AvatarSampleCadence.delayMs(1000,1000,33))
        assertEquals(1L,AvatarSampleCadence.delayMs(1000,1100,33))
    }

    @Test fun clockAnomaliesAndInvalidIntervalsCannotSpinTheMainThread() {
        assertEquals(33L,AvatarSampleCadence.delayMs(1000,999,33))
        assertEquals(1L,AvatarSampleCadence.delayMs(1000,1000,0))
        assertEquals(1L,AvatarSampleCadence.delayMs(1000,1001,-1))
    }

    @Test fun detailAndThermalLoadBoundRefreshWork() {
        assertEquals(33L,AvatarSampleCadence.intervalMs(AvatarSampleDetail.FAST,0))
        assertEquals(50L,AvatarSampleCadence.intervalMs(AvatarSampleDetail.BALANCED,0))
        assertEquals(66L,AvatarSampleCadence.intervalMs(AvatarSampleDetail.MAXIMUM,0))
        assertEquals(66L,AvatarSampleCadence.intervalMs(AvatarSampleDetail.FAST,2))
        assertEquals(100L,AvatarSampleCadence.intervalMs(AvatarSampleDetail.MAXIMUM,3))
    }
}
