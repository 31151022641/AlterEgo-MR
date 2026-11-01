package com.oculus.camerademo.targeting

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import com.oculus.camerademo.eyetracking.*
import com.oculus.camerademo.renderer.AvatarVisibilityPolicy
import org.junit.Assert.*
import org.junit.Test

class FaceTargetingControllerTest {
    private val targets = listOf(
        GazeHeadTarget(8, GazeVector(1f, 0f, 2f), GazeVector(.1f, .1f, .1f)),
        GazeHeadTarget(7, GazeVector(0f, 0f, 2f), GazeVector(.1f, .1f, .1f)))
    private fun head(now: Long) = FaceTargetingController.headRay(Pose(), now)
    private fun eye(now: Long) = EyeGazeData(status = EyeTrackingDeviceStatus.READY, isValid = true,
        worldRay = GazeRay(GazeVector(0f, 0f, 0f), GazeVector(.5f, 0f, 1f), now))

    @Test fun questWithoutEyeHardwareUsesMeasuredHeadDirectionAndDwell() {
        val controller = FaceTargetingController()
        assertEquals(TargetingMode.HEAD_DIRECTION, controller.update(EyeGazeData(), head(0), 0).mode)
        assertNull(controller.selectHeads(targets, 0).lockedId)
        controller.update(EyeGazeData(), head(130), 130)
        assertEquals(7, controller.selectHeads(targets, 130).lockedId)
    }

    @Test fun realEyesSelectThePersonLookedAtRatherThanHeadCentre() {
        val controller = FaceTargetingController()
        assertEquals(TargetingMode.EYE_TRACKING, controller.update(eye(0), head(0), 0).mode)
        controller.selectHeads(targets, 0)
        controller.update(eye(130), head(130), 130)
        assertEquals(8, controller.selectHeads(targets, 130).lockedId)
    }

    @Test fun denyingEyePermissionStillAllowsHeadSelection() {
        val controller = FaceTargetingController()
        val denied = eye(0).copy(status = EyeTrackingDeviceStatus.PERMISSION_DENIED)
        assertEquals(TargetingMode.HEAD_DIRECTION, controller.update(denied, head(0), 0).mode)
        assertEquals(7, controller.selectHeads(targets, 0).candidateId)
    }

    @Test fun missingEyeRuntimeImmediatelyUsesHeadDirection() {
        val controller = FaceTargetingController()
        val unavailable = EyeGazeData(status = EyeTrackingDeviceStatus.RUNTIME_UNAVAILABLE)
        assertTrue(controller.update(unavailable, head(0), 0).isValid)
        assertEquals(TargetingMode.HEAD_DIRECTION, controller.sample.mode)
    }

    @Test fun briefEyeLossNeverSelectsAnotherPersonByHeadDirection() {
        val controller = FaceTargetingController()
        controller.update(eye(0), head(0), 0)
        controller.selectHeads(targets, 0)
        val lost = EyeGazeData(status = EyeTrackingDeviceStatus.TRACKING_UNAVAILABLE)
        assertFalse(controller.update(lost, head(20), 20).isValid)
        assertEquals(TargetingMode.EYE_TRACKING, controller.sample.mode)
        assertNull(controller.selectHeads(targets, 20).candidateId)
        assertFalse(controller.update(lost, head(769), 769).isValid)
        assertEquals(TargetingMode.HEAD_DIRECTION, controller.update(lost, head(770), 770).mode)
        assertEquals(7, controller.selectHeads(targets, 770).candidateId)
    }

    @Test fun switchingBackToEyesRestartsDwellInsteadOfTransferringALock() {
        val controller = FaceTargetingController()
        controller.update(EyeGazeData(), head(0), 0)
        controller.selectHeads(targets, 0)
        controller.update(EyeGazeData(), head(130), 130)
        assertEquals(7, controller.selectHeads(targets, 130).lockedId)
        controller.update(eye(140), head(140), 140)
        assertNull(controller.selectHeads(targets, 140).lockedId)
        controller.update(eye(270), head(270), 270)
        assertEquals(8, controller.selectHeads(targets, 270).lockedId)
    }

    @Test fun staleHeadPoseAndMissingViewerNeverInventATarget() {
        val controller = FaceTargetingController()
        assertFalse(controller.update(EyeGazeData(), head(0), 151).isValid)
        assertNull(FaceTargetingController.headRay(null, 151))
        assertNull(controller.selectHeads(targets, 151).lockedId)
    }

    @Test fun headDirectionIncludesWearerRotationAndWorldPosition() {
        val viewer = Pose(Vector3(2f, 1f, -3f), Quaternion(15f, 90f, 20f))
        val ray = FaceTargetingController.headRay(viewer, 100)!!
        val expected = viewer.q * Vector3(0f, 0f, 1f)
        assertEquals(2f, ray.origin.x, .00001f)
        assertEquals(1f, ray.origin.y, .00001f)
        assertEquals(-3f, ray.origin.z, .00001f)
        assertEquals(expected.x, ray.direction.x, .00001f)
        assertEquals(expected.y, ray.direction.y, .00001f)
        assertEquals(expected.z, ray.direction.z, .00001f)
    }

    @Test fun headDirectionCanTargetMenuAndMissOrStaleClearsIt() {
        val controller = FaceTargetingController()
        controller.update(EyeGazeData(), head(100), 100)
        val panel = Pose(Vector3(0f, 0f, 1f))
        val hit = controller.projectOnPanel(panel, 1f, 1f, 100)!!
        assertEquals(.5f, hit.first, .00001f)
        assertEquals(.5f, hit.second, .00001f)
        assertNull(controller.projectOnPanel(Pose(Vector3(2f, 0f, 1f)), 1f, 1f, 100))
        assertNull(controller.projectOnPanel(panel, 1f, 1f, 251))
    }

    @Test fun resetRemovesOldTargetAndDwell() {
        val controller = FaceTargetingController()
        controller.update(EyeGazeData(), head(0), 0)
        controller.selectHeads(targets, 0)
        controller.reset()
        assertFalse(controller.sample.isValid)
        assertNull(controller.selectHeads(targets, 130).lockedId)
    }

    @Test fun focusAndPartyVisibilityDoNotRequireEyeSensors() {
        val controller = FaceTargetingController()
        controller.update(EyeGazeData(), head(0), 0)
        controller.selectHeads(targets, 0)
        controller.update(EyeGazeData(), head(130), 130)
        val selected = controller.selectHeads(targets, 130).lockedId
        assertEquals(setOf(7), AvatarVisibilityPolicy.visibleIds(listOf(8, 7), selected, false, true, true, 1f))
        assertEquals(setOf(8, 7), AvatarVisibilityPolicy.visibleIds(listOf(8, 7), null, true, true, true, 1f))
    }
}
