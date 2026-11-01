package com.oculus.camerademo.renderer

import org.junit.Assert.*
import org.junit.Test

class AvatarDetailPolicyTest {
    @Test fun validEyesPreserveFullDetailForSelectedPersonAndReducePeripheralWork() {
        val focused = AvatarDetailPolicy.detail(ResolutionQuality.BALANCED, true, true, 7, 7)
        val peripheral = AvatarDetailPolicy.detail(ResolutionQuality.BALANCED, true, true, 8, 7)
        assertEquals(0L, focused.expressionIntervalMs)
        assertTrue(focused.skullRows * focused.skullColumns > peripheral.skullRows * peripheral.skullColumns)
        assertEquals(100L, peripheral.expressionIntervalMs)
    }
    @Test fun headDirectionAndUnavailableEyesRetainUserSelectedQuality() {
        val expected = AvatarDetail(14, 20, 33L)
        assertEquals(expected, AvatarDetailPolicy.detail(ResolutionQuality.BALANCED, true, false, 7, 7))
        assertEquals(expected, AvatarDetailPolicy.detail(ResolutionQuality.BALANCED, true, true, 7, null))
    }
    @Test fun disablingPriorityKeepsAllFacesAtChosenQuality() {
        val expected = AvatarDetail(20, 28, 0L)
        assertEquals(expected, AvatarDetailPolicy.detail(ResolutionQuality.HIGH, false, true, 8, 7))
    }
    @Test fun qualityControlsNativeGeometryAndUpdateCost() {
        val high = AvatarDetailPolicy.detail(ResolutionQuality.HIGH, false, false, 7, null)
        val low = AvatarDetailPolicy.detail(ResolutionQuality.PERFORMANCE, false, false, 7, null)
        assertTrue(high.skullRows > low.skullRows)
        assertTrue(high.skullColumns > low.skullColumns)
        assertTrue(high.expressionIntervalMs < low.expressionIntervalMs)
    }
}
