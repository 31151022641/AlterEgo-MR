package com.oculus.camerademo.ui

import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.Vector3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class FlatPanelPlacementTest {
    @Test fun menuRecoveryUsesTwoFreshFramesWithoutTheSystemResetDelay() {
        val reset=FlatPanelReset()
        reset.request(1000L,waitForReferenceChange=false)
        val current=Pose(Vector3(2f,1.6f,-3f),Quaternion(0f,90f,0f))
        assertNull(reset.sample(1001L,true,current))
        val layout=reset.sample(1012L,true,current)!!
        assertPoint(current*Vector3(0f,0f,FlatPanelPlacement.WINDOW_DISTANCE),layout.menu.t)
        assertPoint(current*Vector3(0f,-.52f,1.10f),layout.dock.t)
        assertPoint(current*Vector3(0f,0f,FlatPanelPlacement.WINDOW_DISTANCE),layout.mirror.t)
    }

    @Test fun movingHeadCannotPreventRecoveryIndefinitely() {
        val reset=FlatPanelReset()
        reset.request(0L,waitForReferenceChange=false)
        for(step in 0..5)assertNull(reset.sample(step*100L,true,
            Pose(Vector3(0f),Quaternion(0f,step*20f,0f))))
        val current=Pose(Vector3(0f),Quaternion(0f,120f,0f))
        val layout=reset.sample(600L,true,current)!!
        assertPoint(current*Vector3(0f,0f,FlatPanelPlacement.WINDOW_DISTANCE),layout.menu.t)
        assertFalse(reset.pending)
    }

    @Test fun timeoutNeverUsesAnInvalidOrMissingPose() {
        val reset=FlatPanelReset()
        reset.request(0L,waitForReferenceChange=false)
        assertNull(reset.sample(0L,true,Pose()))
        assertNull(reset.sample(700L,true,Pose(Vector3(Float.NaN,0f,0f))))
        assertNull(reset.sample(800L,true,null))
        assertTrue(reset.pending)
        assertNull(reset.sample(900L,true,Pose()))
        assertNotNull(reset.sample(910L,true,Pose()))
    }

    @Test fun pausePreservesRecoveryButRequiresTwoPostResumeFrames() {
        val reset=FlatPanelReset()
        reset.request(0L,waitForReferenceChange=false)
        assertNull(reset.sample(10L,true,Pose()))
        reset.invalidateSamples()
        assertTrue(reset.pending)
        val current=Pose(Vector3(4f,1.6f,-2f),Quaternion(0f,180f,0f))
        assertNull(reset.sample(5000L,true,current))
        val layout=reset.sample(5010L,true,current)!!
        assertPoint(current*Vector3(0f,0f,FlatPanelPlacement.WINDOW_DISTANCE),layout.menu.t)
    }

    private fun assertPoint(expected: Vector3, actual: Vector3) {
        assertEquals(expected.x, actual.x, .0001f)
        assertEquals(expected.y, actual.y, .0001f)
        assertEquals(expected.z, actual.z, .0001f)
    }

    @Test fun originIsAValidTrackedPositionAndMenuIsInFront() {
        val layout = FlatPanelPlacement.fromViewer(Pose())!!
        assertPoint(Vector3(0f, 0f, FlatPanelPlacement.WINDOW_DISTANCE), layout.menu.t)
        assertPoint(Vector3(0f, -.52f, 1.10f), layout.dock.t)
        val normal = layout.menu * Vector3(0f, 0f, -1f) - layout.menu.t
        assertPoint(Vector3(0f, 0f, -1f), normal)
    }

    @Test fun menuFollowsCurrentHeadingAtAllFourRoomDirections() {
        for (yaw in listOf(0f, 90f, 180f, 270f)) {
            val viewer = Pose(Vector3(2f, 1.65f, -3f), Quaternion(0f, yaw, 0f))
            val layout = FlatPanelPlacement.fromViewer(viewer)!!
            assertPoint(viewer * Vector3(0f, 0f, FlatPanelPlacement.WINDOW_DISTANCE), layout.menu.t)
            assertPoint(viewer * Vector3(0f, -.52f, 1.10f), layout.dock.t)
        }
    }

    @Test fun seatedAndReclinedResetCentersTheWindowOnTheCurrentSightlineWithoutRoll() {
        for (height in listOf(.8f, 1.1f, 1.7f)) for (pitch in listOf(-55f, -30f, 0f, 35f, 55f)) {
            val viewer = Pose(Vector3(.2f, height, .4f), Quaternion(pitch, 60f, 25f))
            val layout = FlatPanelPlacement.fromViewer(viewer)!!
            val forward = viewer.q * Vector3(0f, 0f, 1f)
            assertPoint(viewer.t + forward * FlatPanelPlacement.WINDOW_DISTANCE, layout.menu.t)
            assertPoint(layout.menu.t, layout.mirror.t)
            // Removing roll keeps the window's screen-right axis horizontal.
            assertEquals(0f, (layout.menu.q * Vector3(1f, 0f, 0f)).y, .0001f)
            val toViewer = viewer.t - layout.menu.t
            assertTrue((layout.menu.q * Vector3(0f, 0f, -1f)).dot(toViewer) > 1f)
        }
    }

    @Test fun straightUpOrDownHasAStableHeadingAndKeepsTheWindowInView() {
        for (pitch in listOf(90f, -90f)) {
            val level = Pose(Vector3(0f), Quaternion(0f, 40f, 0f))
            val viewer = Pose(Vector3(0f), Quaternion(pitch, 40f, 0f))
            val layout = FlatPanelPlacement.fromViewer(viewer)!!
            val displacement = layout.menu.t - viewer.t
            val heading = level.q * Vector3(0f, 0f, 1f)
            val horizontal = sqrt(displacement.x*displacement.x + displacement.z*displacement.z)
            assertEquals(heading.x, displacement.x/horizontal, .0001f)
            assertEquals(heading.z, displacement.z/horizontal, .0001f)
            val gaze = viewer.q * Vector3(0f, 0f, 1f)
            assertTrue(displacement.dot(gaze) / FlatPanelPlacement.WINDOW_DISTANCE > .98f)
        }
    }

    @Test fun mirrorSlotIsCenteredRatherThanThirtyDegreesOffToTheSide() {
        val layout = FlatPanelPlacement.fromViewer(Pose())!!
        assertPoint(Vector3(0f, 0f, FlatPanelPlacement.WINDOW_DISTANCE), layout.mirror.t)
        val normal = layout.mirror * Vector3(0f, 0f, -1f) - layout.mirror.t
        assertPoint(Vector3(0f, 0f, -1f), normal)
    }

    @Test fun dockCannotCoverTheWindowOrItsFooterAtAnyRecoveryHeading() {
        for (yaw in listOf(0f, 90f, 180f, 270f)) for (pitch in listOf(-40f, 0f, 40f)) {
            val viewer = Pose(Vector3(2f, 1.1f, -3f), Quaternion(pitch, yaw, 20f))
            val layout = FlatPanelPlacement.fromViewer(viewer)!!
            val forward = layout.menu.q * Vector3(0f, 0f, 1f)
            val up = layout.menu.q * Vector3(0f, 1f, 0f)
            fun verticalCorners(panel: Pose, width: Float, height: Float): List<Double> =
                listOf(-1f, 1f).flatMap { x -> listOf(-1f, 1f).map { y ->
                    val delta = panel * Vector3(x*width/2f, y*height/2f, 0f) - viewer.t
                    kotlin.math.atan2(delta.dot(up).toDouble(), delta.dot(forward).toDouble())
                } }
            val windowBottom = verticalCorners(layout.menu, FlatPanelPlacement.WINDOW_WIDTH,
                FlatPanelPlacement.WINDOW_HEIGHT).minOrNull()!!
            val dockTop = verticalCorners(layout.dock, FlatPanelPlacement.DOCK_WIDTH,
                FlatPanelPlacement.DOCK_HEIGHT).maxOrNull()!!
            assertTrue("Dock overlaps window at $pitch/$yaw", windowBottom - dockTop > Math.toRadians(2.0))
        }
    }

    @Test fun systemResetCanRefinePlacementAfterALateReferenceChangeAndFocusReturn() {
        val reset = FlatPanelReset()
        val refinement = PanelFocusRecovery()
        val old = Pose(Vector3(4f, 1.6f, -3f))
        val current = Pose(Vector3(-2f, 1.1f, 1f), Quaternion(30f, 120f, 0f))
        refinement.request(1000L); reset.request(1000L)
        assertNull(reset.sample(1200L, true, old))
        assertNotNull(reset.sample(1210L, true, old))
        assertFalse(refinement.onFocusChanged(false, 1220L))
        assertTrue(refinement.onFocusChanged(true, 2000L))
        reset.request(2000L, waitForReferenceChange=false)
        assertNull(reset.sample(2001L, true, current))
        val corrected = reset.sample(2012L, true, current)!!
        assertPoint(current * Vector3(0f, 0f, FlatPanelPlacement.WINDOW_DISTANCE), corrected.menu.t)
        assertFalse(refinement.onFocusChanged(true, 2100L))
        assertNull(reset.sample(2200L, true, old))
    }

    @Test fun visiblePanelFacesAndTouchSurfacesPointTowardTheWearerAtEveryHeading() {
        for (yaw in listOf(0f, 90f, 180f, 270f)) {
            val viewer = Pose(Vector3(2f, 1.65f, -3f), Quaternion(0f, yaw, 0f))
            val layout = FlatPanelPlacement.fromViewer(viewer)!!
            // The SDK panel's visible/touchable side is -Z, unlike an avatar's
            // +Z facing axis. Catch a layout that is in front but back-facing.
            for (panel in listOf(layout.menu, layout.dock, layout.mirror)) {
                val front = panel.q * Vector3(0f, 0f, -1f)
                val toViewer = viewer.t - panel.t
                assertTrue("Back-facing panel at heading $yaw", front.dot(toViewer) > .6f)
            }
            assertTrue((layout.dock.q * Vector3(0f, 0f, -1f)).y > .2f)
            // Screen-right must remain wearer-right for hand pointer hits.
            assertPoint(viewer.q * Vector3(1f, 0f, 0f), layout.menu.q * Vector3(1f, 0f, 0f))
        }
    }

    @Test fun missingOrInvalidTrackingNeverCreatesAnArbitraryLayout() {
        assertNull(FlatPanelPlacement.fromViewer(Pose(Vector3(Float.NaN, 0f, 0f))))
        assertNull(FlatPanelPlacement.fromViewer(Pose(Vector3(0f), Quaternion(0f, 0f, 0f, 0f))))
    }

    @Test fun resetWhilePausedWaitsForFreshFramesAndUsesTheNewPosition() {
        val reset = FlatPanelReset()
        val old = Pose(Vector3(-5f, 1f, 3f))
        val current = Pose(Vector3(2f, 1.6f, -1f), Quaternion(0f, 90f, 0f))
        reset.request(1000L)
        assertNull(reset.sample(1300L, false, old))
        assertTrue(reset.pending)
        assertNull(reset.sample(1310L, true, null))
        assertNull(reset.sample(1320L, true, current))
        val layout = reset.sample(1330L, true, current)!!
        assertPoint(current * Vector3(0f, 0f, FlatPanelPlacement.WINDOW_DISTANCE), layout.menu.t)
        assertFalse(reset.pending)
        assertNull(reset.sample(1340L, true, old))
    }

    @Test fun originChangeBetweenFramesMustSettleBeforeReset() {
        val reset = FlatPanelReset()
        reset.request(0L)
        assertNull(reset.sample(200L, true, Pose(Vector3(4f, 1f, 0f))))
        assertNull(reset.sample(210L, true, Pose(Vector3(0f, 1f, 0f))))
        assertNotNull(reset.sample(220L, true, Pose(Vector3(0f, 1f, 0f))))
    }

    @Test fun repeatedResetRequestsCoalesceAndDoNotRelocatePanelsLater() {
        val reset = FlatPanelReset()
        reset.request(0L); reset.request(100L)
        assertNull(reset.sample(200L, true, Pose()))
        assertNull(reset.sample(220L, true, Pose()))
        assertNotNull(reset.sample(230L, true, Pose()))
        assertNull(reset.sample(1000L, true, Pose(Vector3(5f, 0f, 0f))))
    }
}
