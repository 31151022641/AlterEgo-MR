package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class AvatarInferenceCaptureTest {
    @Test fun prefersNative640WideStreamOverEquivalentLargerConversions() {
        val sizes = listOf(AvatarVideoSize(1600,1200),AvatarVideoSize(1024,576),
            AvatarVideoSize(640,480),AvatarVideoSize(640,360))
        assertEquals(AvatarVideoSize(640,360),AvatarVideoResolution.chooseInference(sizes))
    }

    @Test fun native640FourByThreeRetainsFullInferenceCropWithLessConversion() {
        val sizes = listOf(AvatarVideoSize(1024,576),AvatarVideoSize(640,480))
        assertEquals(AvatarVideoSize(640,480),AvatarVideoResolution.chooseInference(sizes))
    }

    @Test fun choosesSupportedLargerFallbackWithoutInventingAnUnavailable640Stream() {
        val sizes = listOf(AvatarVideoSize(1600,1200),AvatarVideoSize(1024,576),AvatarVideoSize(1280,720))
        assertEquals(AvatarVideoSize(1024,576),AvatarVideoResolution.chooseInference(sizes))
    }

    @Test fun preservesMostUsefulInferencePixelsWhenAllStreamsAreSmaller() {
        val sizes = listOf(AvatarVideoSize(320,240),AvatarVideoSize(480,270),AvatarVideoSize(512,384))
        assertEquals(AvatarVideoSize(512,384),AvatarVideoResolution.chooseInference(sizes))
    }

    @Test fun portraitStreamMustSupplyUsefulCropHeightInsteadOfWinningByWidthAlone() {
        val sizes = listOf(AvatarVideoSize(640,240),AvatarVideoSize(480,640))
        assertEquals(AvatarVideoSize(480,640),AvatarVideoResolution.chooseInference(sizes))
    }

    @Test fun originalMirrorQualitySelectionIsIndependentOfInferenceTarget() {
        val sizes = listOf(AvatarVideoSize(1600,1200),AvatarVideoSize(1280,720),AvatarVideoSize(640,360))
        assertEquals(AvatarVideoSize(1600,1200),AvatarVideoResolution.choose(sizes,AvatarVideoQuality.MAXIMUM))
        assertEquals(AvatarVideoSize(1280,720),AvatarVideoResolution.choose(sizes,AvatarVideoQuality.SMOOTH))
        assertEquals(AvatarVideoSize(640,360),AvatarVideoResolution.chooseInference(sizes))
    }

    @Test fun invalidAndExcessiveStreamsCannotBeSelectedForInference() {
        val invalid = listOf(AvatarVideoSize(0,0),AvatarVideoSize(-640,480),AvatarVideoSize(8192,8192))
        assertNull(AvatarVideoResolution.chooseInference(invalid))
        assertNull(AvatarVideoResolution.chooseInference(emptyList()))
        assertEquals(AvatarVideoSize(640,360),AvatarVideoResolution.chooseInference(invalid+AvatarVideoSize(640,360)))
    }

    @Test fun slowLanAndHighDetailBudgetCannotDelaySampleCaptureWhileThermalCan() {
        val budget = AvatarVideoBudget().apply {
            quality = AvatarVideoQuality.MAXIMUM; processing(237); network(500)
        }
        assertEquals(100L,budget.intervalMs)
        assertEquals(33L,AvatarSampleCapturePolicy.intervalMs(budget.thermalStatus))
        assertEquals(33L,AvatarSampleCapturePolicy.intervalMs(1))
        assertEquals(66L,AvatarSampleCapturePolicy.intervalMs(2))
        assertEquals(100L,AvatarSampleCapturePolicy.intervalMs(3))
        assertEquals(100L,AvatarSampleCapturePolicy.intervalMs(6))
    }

    @Test fun restoredSamplePolicyUsesObservedQuest1024StreamInsteadOfNative640() {
        // These streams have been observed on this Quest: the previous sample
        // used 1024 × 576; inference-only selected 640 × 360; maximum used 1600 × 1200.
        val sizes = listOf(AvatarVideoSize(640,360),AvatarVideoSize(1024,576),AvatarVideoSize(1600,1200))
        assertEquals(AvatarVideoSize(1024,576),AvatarVideoResolution.choose(sizes,AvatarVideoQuality.PERFORMANCE))
        assertEquals(AvatarVideoSize(640,360),AvatarVideoResolution.chooseInference(sizes))
        assertEquals(AvatarVideoSize(1600,1200),AvatarVideoResolution.choose(sizes,AvatarVideoQuality.MAXIMUM))
    }

    @Test fun restoredSamplePolicyFallsBackToAvailableStreamWithoutInventing1024() {
        assertEquals(AvatarVideoSize(640,360),AvatarVideoResolution.choose(
            listOf(AvatarVideoSize(640,360)),AvatarVideoQuality.PERFORMANCE))
        assertEquals(AvatarVideoSize(1280,720),AvatarVideoResolution.choose(
            listOf(AvatarVideoSize(640,360),AvatarVideoSize(1280,720)),AvatarVideoQuality.PERFORMANCE))
        assertNull(AvatarVideoResolution.choose(emptyList(),AvatarVideoQuality.PERFORMANCE))
    }
}
