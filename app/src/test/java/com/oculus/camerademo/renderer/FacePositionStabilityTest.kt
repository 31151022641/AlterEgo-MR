package com.oculus.camerademo.renderer

import org.junit.Assert.*
import org.junit.Test

class FacePositionStabilityTest {
    @Test fun jitterIsReducedWithoutFollowingOldFrames() {
        val filter = FacePositionStability()
        val first = FacePositionStability.Position(0f,1f,2f)
        assertEquals(first,filter.update(first,100))
        val stable = filter.update(first.copy(x = .002f),133)
        assertTrue(stable.x in 0f.. .002f)
        assertEquals(stable,filter.update(first.copy(x = 5f),133))
        val fast = filter.update(first.copy(x = 1f),166)
        assertEquals(1f,fast.x,0f)
        assertEquals(first,filter.update(first,500))
    }
    @Test fun movingFaceNeverTrailsByMoreThanFourMillimeters() {
        val filter = FacePositionStability(); filter.update(FacePositionStability.Position(0f,0f,0f),100)
        val stable = filter.update(FacePositionStability.Position(.025f,0f,0f),133)
        assertTrue(.025f-stable.x <= .00401f)
    }
}
