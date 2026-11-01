package com.oculus.camerademo.ml

import org.junit.Assert.*
import org.junit.Test

class MultiFaceTrackingDataTest {
    @Test fun captureAgeRejectsMissingStaleAndFutureFrames() {
        val data = MultiFaceTrackingData(trackedHeads = listOf(TrackedHeadObject(id = 42)), capturedAtUptimeMs = 1000L)
        assertTrue(data.isFresh(1000L))
        assertTrue(data.isFresh(1250L))
        assertFalse(data.isFresh(1251L))
        assertFalse(data.isFresh(999L))
        assertFalse(data.copy(capturedAtUptimeMs = 0L).isFresh(1000L))
        assertFalse(data.copy(trackedHeads = emptyList()).isFresh(1000L))
    }

    @Test fun legacyDataCarriesTheGazeSelectedPersonsPoseAndExpression() {
        val first = TrackedHeadObject(id = 10, emotion = FaceEmotionState(jawOpen = 0.1f))
        val selected = TrackedHeadObject(id = 20, anchorX = 0.3f, anchorY = -0.1f, anchorZ = -1.6f,
            capturedAtUptimeMs = 1234L, emotion = FaceEmotionState(jawOpen = 0.8f),
            landmarks3D = listOf(floatArrayOf(0.1f, 0.2f, 0.3f)))
        val legacy = MultiFaceTrackingData(trackedHeads = listOf(first, selected), gazeLockedHead = selected)
            .toLegacyFaceTrackingData()
        assertEquals(20, legacy.trackedHeadId)
        assertEquals(0.8f, legacy.jawOpen, 0f)
        assertEquals(0.3f, legacy.anchorX, 0f)
        assertEquals(-1.6f, legacy.anchorZ, 0f)
        assertEquals(1234L, legacy.capturedAtUptimeMs)
        assertEquals(1, legacy.landmarksCount)
    }

    @Test fun anEmptyResultCannotProduceLegacyFaceData() {
        val legacy = MultiFaceTrackingData(capturedAtUptimeMs = 1000L).toLegacyFaceTrackingData()
        assertFalse(legacy.hasFace)
        assertNull(legacy.trackedHeadId)
        assertEquals(0, legacy.landmarksCount)
    }
}
