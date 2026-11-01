package com.oculus.camerademo.diagnostics

import org.junit.Assert.*
import org.junit.Test

class SceneFrameWindowTest {
    private fun SceneFrameWindow.frame(ms: Long, flags: Int = SceneFrameWindow.FOCUSED,
        hands: Int = 2, active: Int = 2, menu: Boolean = false) =
        record(ms * 1_000_000, (ms + 2) * 1_000_000, (ms + 3) * 1_000_000,
            flags, hands, active, 0, menu)

    @Test fun smoothFramesKeepSdkAndAppWorkSeparate() {
        val window = SceneFrameWindow(30_000_000)
        assertNull(window.frame(100))
        assertNull(window.frame(111))
        assertNull(window.frame(122))
        val summary = window.frame(133)!!
        assertEquals(4, summary.frames)
        assertEquals(3, summary.intervals)
        assertEquals(33_000_000L, summary.intervalTotalNs)
        assertEquals(11_000_000L, summary.intervalMaxNs)
        assertEquals(8_000_000L, summary.sdkTotalNs)
        assertEquals(4_000_000L, summary.appTotalNs)
        assertEquals(0, summary.gapsOver25ms)
    }

    @Test fun leavingAppDoesNotCountSleepAsAStall() {
        val window = SceneFrameWindow()
        window.frame(100)
        window.frame(111)
        assertEquals(2, window.drain()!!.frames)
        window.frame(90_000)
        val summary = window.drain()!!
        assertEquals(1, summary.frames)
        assertEquals(0, summary.intervals)
        assertEquals(0, summary.gapsOver25ms)
    }

    @Test fun menuAndFocusChangesAreNotMixedInOneWindow() {
        val window = SceneFrameWindow()
        window.frame(100)
        window.frame(111)
        val previous = window.frame(150, SceneFrameWindow.MENU_VISIBLE, hands = -1, active = 0)!!
        assertEquals(SceneFrameWindow.FOCUSED, previous.flags)
        assertEquals(2, previous.frames)
        val next = window.drain()!!
        assertEquals(SceneFrameWindow.MENU_VISIBLE, next.flags)
        assertEquals(0, next.intervals)
        assertEquals(0, next.inputFrames)
    }

    @Test fun observedLongIntervalsAreCountedWithoutCallingThemDroppedFrames() {
        val window = SceneFrameWindow()
        window.frame(100)
        window.frame(111)
        window.frame(151)
        val summary = window.drain()!!
        assertEquals(1, summary.gapsOver25ms)
        assertEquals(40_000_000L, summary.intervalMaxNs)
    }

    @Test fun inactiveHandInputStillRecordsMenuWithoutInventingActiveTracking() {
        val window = SceneFrameWindow()
        window.frame(100, hands = 2, active = 0, menu = true)
        window.frame(111, hands = 0, active = 0)
        window.frame(122, hands = -1, active = 0)
        val summary = window.drain()!!
        assertEquals(2, summary.inputFrames)
        assertEquals(1, summary.handFrames)
        assertEquals(0, summary.activeHandFrames)
        assertEquals(2, summary.maxHands)
        assertEquals(1, summary.menuActions)
    }

    @Test fun invalidOrNonIncreasingTimesDoNotPoisonNextWindow() {
        val window = SceneFrameWindow()
        window.frame(100)
        assertEquals(1, window.frame(99)!!.frames)
        assertNull(window.record(0, 0, 0, 0, -1, 0, 0, false))
        assertNull(window.record(10, 9, 11, 0, -1, 0, 0, false))
        window.frame(1_000)
        val summary = window.drain()!!
        assertEquals(1, summary.frames)
        assertEquals(0, summary.intervals)
        assertEquals(0, summary.menuActions)
        assertNull(window.drain())
    }
}
