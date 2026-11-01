package com.oculus.camerademo.ml

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class CalibratedFacePoseTest {
    private fun project(rotation: DoubleArray, x: Double, y: Double, z: Double) =
        CalibratedFacePose.canonicalPoints.map { point ->
            val px = rotation[0] * point.x + rotation[1] * point.y + rotation[2] * point.z + x
            val py = rotation[3] * point.x + rotation[4] * point.y + rotation[5] * point.z + y
            val pz = rotation[6] * point.x + rotation[7] * point.y + rotation[8] * point.z + z
            CalibratedFacePose.Correspondence(point.landmarkIndex, (px / -pz).toFloat(), (py / -pz).toFloat())
        }

    private fun matrix(rotation: DoubleArray) = floatArrayOf(
        rotation[0].toFloat(), rotation[3].toFloat(), rotation[6].toFloat(), 0f,
        rotation[1].toFloat(), rotation[4].toFloat(), rotation[7].toFloat(), 0f,
        rotation[2].toFloat(), rotation[5].toFloat(), rotation[8].toFloat(), 0f,
        0f, 0f, -120f, 1f)

    @Test fun recoversFrontalMetricPoseFromCalibratedRays() {
        val rotation = CalibratedFacePose.rotationMatrix(0.0, 0.0, 0.0)
        val pose = CalibratedFacePose.solve(project(rotation, 0.0, 0.0, -1.2), 600f, matrix(rotation))!!
        assertEquals(0f, pose.x, 0.0001f)
        assertEquals(0f, pose.y, 0.0001f)
        assertEquals(-1.2f, pose.z, 0.0001f)
        assertEquals(1f, pose.orientation.w, 0.0001f)
        assertTrue(pose.reprojectionErrorPixels < 0.01f)
    }

    @Test fun preservesYawPitchAndRollForFaceNearImageEdge() {
        val rotation = CalibratedFacePose.rotationMatrix(0.22, -0.55, 0.18)
        val pose = CalibratedFacePose.solve(project(rotation, 0.8, 0.35, -2.1), 750f, matrix(rotation))!!
        assertEquals(0.8f, pose.x, 0.002f)
        assertEquals(0.35f, pose.y, 0.002f)
        assertEquals(-2.1f, pose.z, 0.003f)
        val theta = sqrt(0.22 * 0.22 + 0.55 * 0.55 + 0.18 * 0.18)
        val sine = kotlin.math.sin(theta / 2.0) / theta
        assertEquals((0.22 * sine).toFloat(), pose.orientation.x, 0.001f)
        assertEquals((-0.55 * sine).toFloat(), pose.orientation.y, 0.001f)
        assertEquals((0.18 * sine).toFloat(), pose.orientation.z, 0.001f)
        assertTrue(pose.reprojectionErrorPixels < 0.02f)
    }

    @Test fun refinesMatrixSeedToTheActualCameraProjection() {
        val actual = CalibratedFacePose.rotationMatrix(-0.18, 0.4, -0.2)
        val virtualCameraSeed = CalibratedFacePose.rotationMatrix(-0.05, 0.2, -0.15)
        val pose = CalibratedFacePose.solve(project(actual, -0.6, 0.1, -1.8), 550f, matrix(virtualCameraSeed))!!
        assertEquals(-0.6f, pose.x, 0.003f)
        assertEquals(-1.8f, pose.z, 0.003f)
        assertTrue(pose.reprojectionErrorPixels < 0.02f)
        assertTrue(abs(pose.yawDegrees) > 15f)
    }

    @Test fun usesActualRaysWithoutVirtualCameraTranslation() {
        val rotation = CalibratedFacePose.rotationMatrix(0.1, 0.25, 0.08)
        val seed = matrix(rotation).also { it[12] = 1200f; it[13] = -400f; it[14] = -10f }
        val pose = CalibratedFacePose.solve(project(rotation, 0.25, -0.15, -1.5), 600f, seed)!!
        assertEquals(0.25f, pose.x, 0.002f)
        assertEquals(-0.15f, pose.y, 0.002f)
        assertEquals(-1.5f, pose.z, 0.002f)
    }

    @Test fun rejectsInvalidOrMissingCalibratedObservations() {
        assertNull(CalibratedFacePose.solve(emptyList(), 600f, null))
        val rotation = CalibratedFacePose.rotationMatrix(0.0, 0.0, 0.0)
        val observations = project(rotation, 0.0, 0.0, -1.2)
        assertNull(CalibratedFacePose.solve(observations, 0f, null))
        assertNull(CalibratedFacePose.solve(observations.map { it.copy(rayX = Float.NaN) }, 600f, null))
        assertNull(CalibratedFacePose.solve(observations.map { it.copy(rayX = 0f, rayY = 0f) }, 600f, null))
    }

    @Test fun rejectsLandmarkConfigurationThatCannotFitARigidFace() {
        val rotation = CalibratedFacePose.rotationMatrix(0.0, 0.0, 0.0)
        val observations = project(rotation, 0.0, 0.0, -1.2).map {
            if (it.landmarkIndex == 6) it.copy(rayY = 0.8f) else it
        }
        assertNull(CalibratedFacePose.solve(observations, 600f, matrix(rotation)))
    }
}
