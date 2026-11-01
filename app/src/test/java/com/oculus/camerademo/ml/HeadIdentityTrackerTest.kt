package com.oculus.camerademo.ml

import org.junit.Assert.*
import org.junit.Test

class HeadIdentityTrackerTest {
    private fun head(x: Float, y: Float = 0.5f) = HeadIdentityTracker.Observation(x, y, 0.16f, 0.22f)

    @Test fun resultOrderingDoesNotChangeHeadIds() {
        val tracker = HeadIdentityTracker()
        val first = tracker.associate(listOf(head(0.25f), head(0.75f)), 1000L)
        val reordered = tracker.associate(listOf(head(0.74f), head(0.26f)), 1033L)
        assertEquals(listOf(first[1], first[0]), reordered)
    }

    @Test fun emptyFrameNeverInventsOrEmitsAHead() {
        val tracker = HeadIdentityTracker()
        val id = tracker.associate(listOf(head(0.5f)), 1000L).single()
        assertTrue(tracker.associate(emptyList(), 1033L).isEmpty())
        assertEquals(id, tracker.associate(listOf(head(0.51f)), 1100L).single())
    }

    @Test fun expiredTrackIsNotReusedForANewPerson() {
        val tracker = HeadIdentityTracker()
        val id = tracker.associate(listOf(head(0.5f)), 1000L).single()
        assertNotEquals(id, tracker.associate(listOf(head(0.5f)), 1500L).single())
    }

    @Test fun distantDetectionDoesNotStealAnExistingId() {
        val tracker = HeadIdentityTracker()
        val id = tracker.associate(listOf(head(0.15f)), 1000L).single()
        assertNotEquals(id, tracker.associate(listOf(head(0.85f)), 1033L).single())
    }

    @Test fun motionPredictionKeepsIdsWhenPeopleCross() {
        val tracker = HeadIdentityTracker()
        val first = tracker.associate(listOf(head(0.35f), head(0.65f)), 1000L)
        tracker.associate(listOf(head(0.45f), head(0.55f)), 1100L)
        val afterCrossing = tracker.associate(listOf(head(0.45f), head(0.55f)), 1200L)
        assertEquals(listOf(first[1], first[0]), afterCrossing)
    }

    @Test fun fourPeopleHaveFourUniqueStableIds() {
        val tracker = HeadIdentityTracker()
        val heads = listOf(head(0.2f, 0.3f), head(0.7f, 0.3f), head(0.2f, 0.7f), head(0.7f, 0.7f))
        val first = tracker.associate(heads, 1000L)
        assertEquals(4, first.toSet().size)
        assertEquals(first.reversed(), tracker.associate(heads.reversed(), 1033L))
    }

    @Test fun clearDoesNotReuseOldIds() {
        val tracker = HeadIdentityTracker()
        val id = tracker.associate(listOf(head(0.5f)), 1000L).single()
        tracker.clear()
        assertNotEquals(id, tracker.associate(listOf(head(0.5f)), 1033L).single())
    }
    @Test fun indistinguishableCrossingIsFlaggedUntilClear() {
        val tracker = HeadIdentityTracker()
        tracker.associate(listOf(head(.45f),head(.55f)),1000)
        tracker.associate(listOf(head(.5f),head(.5f)),1033)
        assertEquals(setOf(0,1),tracker.ambiguousObservations)
        tracker.associate(emptyList(),1066)
        assertTrue(tracker.ambiguousObservations.isEmpty())
    }
}
