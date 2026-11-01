package com.oculus.camerademo.mirror

import com.oculus.camerademo.ml.FaceEmotionState
import org.junit.Assert.*
import org.junit.Test

class AvatarCameraPolicyTest {
    @Test fun avatarVendorTagTakesPriorityOverOtherFrontCamera() {
        assertEquals("avatar",AvatarCameraPolicy.select(listOf(
            AvatarCameraCandidate("unknown",null,true),AvatarCameraCandidate("avatar",1,false))))
    }
    @Test fun oldFirmwareFrontCameraIsAcceptedWithoutVendorMetadata() {
        assertEquals("front",AvatarCameraPolicy.select(listOf(
            AvatarCameraCandidate("back",null,false),AvatarCameraCandidate("front",null,true))))
    }
    @Test fun outwardAndThirdPartyCamerasAreNeverUsedAsMirrorFallback() {
        assertNull(AvatarCameraPolicy.select(listOf(AvatarCameraCandidate("outward",0,true),
            AvatarCameraCandidate("external",2,true),AvatarCameraCandidate("spatial",3,true))))
    }
    @Test fun cropKeepsWidePortraitAndSquareSourcesInsideImage() {
        for ((w,h) in listOf(1920 to 1080,1280 to 960,640 to 640,540 to 960)) {
            for (zoom in listOf(1f,1.4f,2.2f)) {
                val crop = AvatarFrameFraming.crop(w,h,zoom)
                assertTrue(crop.left >= 0 && crop.top >= 0)
                assertTrue(crop.left+crop.width <= w+.01f && crop.top+crop.height <= h+.01f)
                assertEquals(16f/9f,crop.width/crop.height,.0001f)
            }
        }
    }
    @Test fun invalidZoomFallsBackToWideFrameAndExtremeZoomIsClamped() {
        assertEquals(AvatarFrameFraming.crop(960,540,1f),AvatarFrameFraming.crop(960,540,Float.NaN))
        assertEquals(AvatarFrameFraming.crop(960,540,2.2f),AvatarFrameFraming.crop(960,540,100f))
    }
    @Test fun headRotationUsesColumnMajorMatrixAndIgnoresTranslation() {
        val matrix = floatArrayOf(0f,1f,0f,0f,-1f,0f,0f,0f,0f,0f,1f,0f,9f,8f,7f,1f)
        assertArrayEquals(floatArrayOf(0f,1f,0f),AvatarVideoRotation.rotate(matrix,1f,0f,0f),.0001f)
    }
    @Test fun metricScaleDoesNotDistortPresetHeadRotation() {
        val matrix = floatArrayOf(2f,0f,0f,0f,0f,2f,0f,0f,0f,0f,2f,0f,0f,0f,0f,1f)
        assertArrayEquals(floatArrayOf(.1f,.2f,.3f),AvatarVideoRotation.rotate(matrix,.1f,.2f,.3f),.0001f)
    }
    @Test fun invalidFaceBoundsAndDegenerateRotationCannotProduceAnOverlay() {
        val identity = floatArrayOf(1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f)
        val face = AvatarVideoFace(.3f,.2f,.6f,.7f,identity,FaceEmotionState())
        assertTrue(face.usable)
        assertFalse(face.copy(left=Float.NaN).usable)
        assertFalse(face.copy(right=.31f).usable)
        assertFalse(face.copy(top=-.1f).usable)
        assertFalse(face.copy(rotation=FloatArray(16)).usable)
    }
}
