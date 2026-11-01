package com.oculus.camerademo.renderer

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.oculus.camerademo.camera.CameraExtrinsics
import com.oculus.camerademo.ml.TrackedHeadObject
import org.junit.Assert.*
import org.junit.Test

class SceneCoordinateMappingTest {
    private val camera = CameraExtrinsics(0.04f, 0f, -0.02f, 0f, 0f, 0f, 1f)

    @Test fun cameraForwardMapsInFrontWithPhysicalCameraOffset() {
        val pose = SceneCoordinateMapping.cameraHeadToWorld(Pose(), camera,
            TrackedHeadObject(7, anchorX = 0.1f, anchorY = 0.2f, anchorZ = -2f))
        assertEquals(0.14f, pose.t.x, 0.00001f)
        assertEquals(0.2f, pose.t.y, 0.00001f)
        assertEquals(2.02f, pose.t.z, 0.00001f)
    }

    @Test fun wearerPitchAndRollArePreservedInTheWorldAnchor() {
        val viewer = Pose(Vector3(0.4f, 1.5f, -0.1f), Quaternion(25f, 40f, 15f))
        val head = TrackedHeadObject(7, anchorX = 0.1f, anchorY = 0.2f, anchorZ = -2f)
        val actual = SceneCoordinateMapping.cameraHeadToWorld(viewer, camera, head)
        val expected = viewer * Vector3(0.14f, 0.2f, 2.02f)
        assertEquals(expected.x, actual.t.x, 0.00001f)
        assertEquals(expected.y, actual.t.y, 0.00001f)
        assertEquals(expected.z, actual.t.z, 0.00001f)
        assertTrue(actual.q.isWithinAngleDegrees(viewer.q, 0.01f))
    }

    @Test fun delayedFrameUsesHistoricalPoseInsteadOfCurrentViewer() {
        val history = ViewerPoseHistory()
        history.record(1000L, Pose(Vector3(0f, 1f, 0f)))
        history.record(1050L, Pose(Vector3(0.2f, 1f, 0f)))
        history.record(1100L, Pose(Vector3(0.4f, 1f, 0f)))
        assertEquals(0.1f, history.at(1025L)!!.t.x, 0.00001f)
        assertNull(history.at(950L))
        assertNull(history.at(1101L))
        history.clear()
        assertNull(history.at(1025L))
    }
}
