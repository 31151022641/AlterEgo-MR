package com.oculus.camerademo.eyetracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class GazeTargetSelectorTest {
    private fun ray(time: Long, direction: GazeVector = GazeVector(0f, 0f, 1f)) =
        GazeRay(GazeVector(0f, 0f, 0f), direction, time)
    private fun head(id: Int, x: Float = 0f, z: Float = 2f) =
        GazeHeadTarget(id, GazeVector(x, 0f, z), GazeVector(0.10f, 0.15f, 0.10f))

    @Test fun noSampleNeverLocksTheCentralFace() {
        val selector = GazeTargetSelector()
        assertNull(selector.select(null, listOf(head(7)), 1000L).candidateId)
        assertNull(selector.select(null, listOf(head(7)), 2000L).lockedId)
    }

    @Test fun selectsNearestHeadOnTheRayAndRequiresDwell() {
        val selector = GazeTargetSelector()
        val targets = listOf(head(3, z = 3f), head(8, z = 2f))
        assertEquals(8, selector.select(ray(1000L), targets, 1000L).candidateId)
        assertNull(selector.select(ray(1100L), targets, 1100L).lockedId)
        assertEquals(8, selector.select(ray(1120L), targets, 1120L).lockedId)
    }

    @Test fun lookingAwayClearsLockImmediately() {
        val selector = GazeTargetSelector()
        selector.select(ray(1000L), listOf(head(7)), 1000L)
        assertEquals(7, selector.select(ray(1200L), listOf(head(7)), 1200L).lockedId)
        val away = selector.select(ray(1210L, GazeVector(1f, 0f, 1f)), listOf(head(7)), 1210L)
        assertNull(away.lockedId)
        assertEquals(0L, away.dwellTimeMs)
    }

    @Test fun targetIdentityChangeRestartsDwell() {
        val selector = GazeTargetSelector()
        selector.select(ray(1000L), listOf(head(7)), 1000L)
        selector.select(ray(1200L), listOf(head(7)), 1200L)
        val changed = selector.select(ray(1210L), listOf(head(9)), 1210L)
        assertEquals(9, changed.candidateId)
        assertNull(changed.lockedId)
        assertEquals(0L, changed.dwellTimeMs)
        assertEquals(9, selector.select(ray(1330L), listOf(head(9)), 1330L).lockedId)
    }

    @Test fun staleAndInvalidSamplesClearDwell() {
        val selector = GazeTargetSelector()
        selector.select(ray(1000L), listOf(head(7)), 1000L)
        assertNull(selector.select(ray(1000L), listOf(head(7)), 1151L).lockedId)
        assertNull(selector.select(ray(1160L, GazeVector(Float.NaN, 0f, 1f)), listOf(head(7)), 1160L).candidateId)
        assertNull(selector.select(ray(1170L, GazeVector(0f, 0f, 0f)), listOf(head(7)), 1170L).candidateId)
    }

    @Test fun headBehindTheViewerCannotBeSelected() {
        assertNull(GazeTargetSelector().select(ray(1000L), listOf(head(4, z = -2f)), 1000L).candidateId)
    }

    @Test fun rotatedHeadUsesItsOrientedBounds() {
        val diagonal = sqrt(0.5f)
        val narrowSidewaysHead = GazeHeadTarget(
            3, GazeVector(0.3f, 0f, 2f), GazeVector(0.5f, 0.15f, 0.05f),
            GazeRotation(diagonal, 0f, diagonal, 0f),
        )
        // Rotation exchanges the wide X extent and narrow Z extent; the central ray misses.
        assertNull(GazeTargetSelector().select(ray(1000L), listOf(narrowSidewaysHead), 1000L).candidateId)
        assertEquals(3, GazeTargetSelector().select(
            ray(1000L), listOf(narrowSidewaysHead.copy(rotation = GazeRotation())), 1000L,
        ).candidateId)
    }

    @Test fun disappearingFacesRestartDwellWhenTheyReturn() {
        val selector = GazeTargetSelector()
        selector.select(ray(1000L), listOf(head(7)), 1000L)
        assertEquals(7, selector.select(ray(1200L), listOf(head(7)), 1200L).lockedId)
        assertNull(selector.select(ray(1210L), emptyList(), 1210L).lockedId)
        assertNull(selector.select(ray(1220L), listOf(head(7)), 1220L).lockedId)
    }
}
