package com.oculus.camerademo.ui

import org.junit.Assert.*
import org.junit.Test

class PanelFocusRecoveryTest {
    @Test fun focusReturnRefinesAnEarlierPlacementUnderTheSystemOverlay() {
        val recovery = PanelFocusRecovery()
        recovery.request(1_000L)
        assertFalse(recovery.onFocusChanged(false, 1_010L))
        // Visible scene frames may already have placed panels in the old reference
        // space. They do not clear this separate request for a focused refinement.
        assertFalse(recovery.onFocusChanged(false, 1_500L))
        assertTrue(recovery.onFocusChanged(true, 2_000L))
    }

    @Test fun focusCallbacksConsumeOnlyOnce() {
        val recovery = PanelFocusRecovery()
        recovery.request(100L)
        assertTrue(recovery.onFocusChanged(true, 120L))
        assertFalse(recovery.onFocusChanged(true, 130L))
        assertFalse(recovery.onFocusChanged(false, 140L))
        assertFalse(recovery.onFocusChanged(true, 150L))
    }

    @Test fun unrelatedFocusReturnAfterThirtySecondsCannotMovePanels() {
        val recovery = PanelFocusRecovery()
        recovery.request(1_000L)
        assertFalse(recovery.onFocusChanged(false, 1_100L))
        assertFalse(recovery.onFocusChanged(true, 31_001L))
        assertFalse(recovery.onFocusChanged(true, 31_100L))
    }

    @Test fun requestRemainsValidAtTheThirtySecondBoundary() {
        val recovery = PanelFocusRecovery()
        recovery.request(1_000L)
        assertTrue(recovery.onFocusChanged(true, 31_000L))
    }

    @Test fun clearCancelsRecoveryAcrossSceneShutdownOrModeChange() {
        val recovery = PanelFocusRecovery()
        recovery.request(1_000L)
        recovery.clear()
        assertFalse(recovery.onFocusChanged(true, 1_100L))
        recovery.request(2_000L)
        assertTrue(recovery.onFocusChanged(true, 2_100L))
    }

    @Test fun repeatedRequestsCoalesceAndUseTheLatestResetDeadline() {
        val recovery = PanelFocusRecovery()
        recovery.request(1_000L)
        recovery.request(20_000L)
        assertFalse(recovery.onFocusChanged(false, 31_100L))
        assertTrue(recovery.onFocusChanged(true, 40_000L))
        assertFalse(recovery.onFocusChanged(true, 40_100L))
    }

    @Test fun focusWithoutAResetDoesNotTriggerPlacement() {
        val recovery = PanelFocusRecovery()
        assertFalse(recovery.onFocusChanged(false, 10L))
        assertFalse(recovery.onFocusChanged(true, 20L))
    }

    @Test fun invalidClockOrderCannotLeaveAStaleRequestArmed() {
        val recovery = PanelFocusRecovery()
        recovery.request(1_000L)
        assertFalse(recovery.onFocusChanged(true, 900L))
        assertFalse(recovery.onFocusChanged(true, 1_100L))
        recovery.request(-1L)
        assertFalse(recovery.onFocusChanged(true, 1_200L))
    }
}
