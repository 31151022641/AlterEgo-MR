package com.oculus.camerademo.renderer

import com.oculus.camerademo.ml.FaceEmotionState
import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarExpressionMapperTest {
    @Test fun anatomicalLeftWinkDrivesPositiveXEyeOnLegacyRig() {
        val result = AvatarExpressionMapper.forLegacyMesh(FaceEmotionState(eyeBlinkLeft = 1f, eyeBlinkRight = 0f,
            mouthSmileLeft = 0.7f, browDownRight = 0.8f, jawOpen = 0.4f))
        assertEquals(1f, result.eyeBlinkRight, 0f)
        assertEquals(0f, result.eyeBlinkLeft, 0f)
        assertEquals(0.7f, result.mouthSmileRight, 0f)
        assertEquals(0.8f, result.browDownLeft, 0f)
        assertEquals(0.4f, result.jawOpen, 0f)
    }
}
