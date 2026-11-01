package com.oculus.camerademo.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarVisibilityPolicyTest {
    @Test fun focusSelectsTheLookedAtPersonRatherThanFirstDetection() {
        assertEquals(setOf(27), visible(listOf(9, 27, 31, 42), 27))
    }
    @Test fun lookingAwayAndStaleFramesHideAllAvatars() {
        assertEquals(emptySet<Int>(), visible(listOf(9, 27), null))
        assertEquals(emptySet<Int>(), visible(listOf(9, 27), 27, fresh = false))
    }
    @Test fun partyRendersFourDistinctPeople() {
        assertEquals(setOf(9, 27, 31, 42), visible(listOf(9, 27, 31, 42), null, party = true))
    }
    @Test fun unsupportedHardwareAndZeroOpacityNeverRender() {
        assertEquals(emptySet<Int>(), visible(listOf(9), 9, ready = false))
        assertEquals(emptySet<Int>(), visible(listOf(9), 9, alpha = 0f))
    }
    @Test fun missingPeopleNeverGenerateCompanions() {
        assertEquals(emptySet<Int>(), visible(emptyList(), 9, party = true))
        assertEquals(emptySet<Int>(), visible(listOf(9), 27))
    }
    private fun visible(ids: List<Int>, focus: Int?, party: Boolean = false,
        ready: Boolean = true, fresh: Boolean = true, alpha: Float = 1f) =
        AvatarVisibilityPolicy.visibleIds(ids, focus, party, ready, fresh, alpha)
}
