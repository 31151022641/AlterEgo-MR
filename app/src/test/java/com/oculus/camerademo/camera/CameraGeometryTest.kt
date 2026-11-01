package com.oculus.camerademo.camera

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class CameraGeometryTest {
    @Test fun principalPointAndAxesGiveCalibratedGraphicsRays() {
        val intrinsics = CameraIntrinsics(500f, 400f, 320f, 240f)
        val center = intrinsics.pixelRay(320f, 240f)!!
        assertEquals(0f, center.x, 0.00001f)
        assertEquals(0f, center.y, 0.00001f)
        assertEquals(-1f, center.z, 0.00001f)
        val offCenter = intrinsics.pixelRay(820f, 640f)!!
        val component = 1f / sqrt(3f)
        assertEquals(component, offCenter.x, 0.00001f)
        assertEquals(-component, offCenter.y, 0.00001f)
        assertEquals(-component, offCenter.z, 0.00001f)
    }

    @Test fun sensorOriginCropAndResizeChangeIntrinsicsTogether() {
        val intrinsics = CameraIntrinsics.fromSensorCalibration(
            floatArrayOf(1000f, 800f, 640f, 480f, 10f),
            10, 20, SensorCrop(170f, 140f, 960f, 720f), 640, 480)!!
        assertEquals(1000f * 2f / 3f, intrinsics.focalLengthX, 0.0001f)
        assertEquals(800f * 2f / 3f, intrinsics.focalLengthY, 0.0001f)
        assertEquals(320f, intrinsics.principalPointX, 0.0001f)
        assertEquals(240f, intrinsics.principalPointY, 0.0001f)
        assertEquals(10f * 2f / 3f, intrinsics.skew, 0.0001f)
    }

    @Test fun androidBackCameraPoseBecomesGraphicsIdentityWithPhysicalLensOffset() {
        val extrinsics = CameraExtrinsics.fromAndroidPose(
            floatArrayOf(-0.03f, 0.01f, -0.04f), floatArrayOf(1f, 0f, 0f, 0f))!!
        val point = extrinsics.toHeadset(CameraPoint(0.2f, -0.1f, -2f))
        assertEquals(0.17f, point.x, 0.00001f)
        assertEquals(-0.09f, point.y, 0.00001f)
        assertEquals(-2.04f, point.z, 0.00001f)
    }

    @Test fun radialDistortionIsInvertedBeforeUnprojection() {
        val intrinsics = CameraIntrinsics(100f, 100f, 50f, 50f, distortion = listOf(0.1f, 0f, 0f, 0f, 0f))
        // Undistorted x=.5,y=0 projects to distorted x=.5125.
        val ray = intrinsics.pixelRay(101.25f, 50f)!!
        assertEquals(0.5f, ray.x / -ray.z, 0.0001f)
    }

    @Test fun missingOrInvalidCalibrationDoesNotInventARayOrLensPose() {
        assertNull(CameraIntrinsics(0f, 100f, 50f, 50f).pixelRay(50f, 50f))
        assertNull(CameraIntrinsics(Float.NaN, 100f, 50f, 50f).pixelRay(50f, 50f))
        assertNull(CameraExtrinsics.fromAndroidPose(null, floatArrayOf(0f, 0f, 0f, 1f)))
        assertNull(CameraExtrinsics.fromAndroidPose(floatArrayOf(0f, 0f, 0f), FloatArray(4)))
    }
}
