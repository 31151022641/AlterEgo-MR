package com.oculus.camerademo.ui

import org.junit.Assert.*
import org.junit.Test

class ImmersiveInputStateTest {
    private val menu = MenuPressGate()
    private val state = ImmersiveInputState(menu, MenuPressGate(), MenuPressGate())

    @Test fun handMenuWithSuppressedPointerIsAccepted() {
        state.beginFrame()
        state.sample(hand = true, active = false, menuDown = true, menuPressed = true)
        assertEquals(ImmersiveInputState.MENU or ImmersiveInputState.HAND, state.finishFrame(true, 1000))
    }

    @Test fun staleInactivePhysicalControllerCannotAct() {
        state.beginFrame()
        state.sample(false, false, true, true, true, true)
        assertEquals(0, state.finishFrame(true, 1000))
    }

    @Test fun heldHandMenuOnlyActsOnceUntilRelease() {
        state.beginFrame(); state.sample(true, false, true, true)
        assertTrue(state.finishFrame(true, 1000) and ImmersiveInputState.MENU != 0)
        state.beginFrame(); state.sample(true, false, true, false)
        assertEquals(0, state.finishFrame(true, 2000))
        state.beginFrame(); state.sample(true, true, false, false)
        assertEquals(0, state.finishFrame(true, 3000))
        state.beginFrame(); state.sample(true, false, true, true)
        assertTrue(state.finishFrame(true, 4000) and ImmersiveInputState.MENU != 0)
    }

    @Test fun multipleInputSourcesAndAndroidDeliveryOpenOnce() {
        assertTrue(menu.key(true, 0, true, 1000))
        state.beginFrame()
        state.sample(true, false, true, true)
        state.sample(false, true, true, true)
        assertEquals(0, state.finishFrame(true, 1005))
    }

    @Test fun freshHandMenuRecoversAfterLostAndroidKeyUp() {
        menu.key(true, 0, true, 1000)
        state.beginFrame(); state.sample(true, false, true, true)
        assertTrue(state.finishFrame(true, 2000) and ImmersiveInputState.MENU != 0)
        assertFalse(menu.key(true, 0, true, 2005))
    }

    @Test fun unfocusedHandAndControllerButtonsCannotAct() {
        state.beginFrame()
        state.sample(true, false, true, true)
        state.sample(false, true, true, true, true, true)
        assertEquals(0, state.finishFrame(false, 1000))
    }

    @Test fun actualControllerBackAndMirrorRemainAvailable() {
        state.beginFrame(); state.sample(false, true, false, false, true, true)
        assertEquals(ImmersiveInputState.BACK or ImmersiveInputState.MIRROR, state.finishFrame(true, 1000))
        state.beginFrame(); state.sample(false, true, false, false, true, true)
        assertEquals(0, state.finishFrame(true, 2000))
    }

    @Test fun ordinaryHandPinchCannotBecomeBackOrMirror() {
        state.beginFrame(); state.sample(true, true, false, false, true, true)
        assertEquals(0, state.finishFrame(true, 1000))
    }
}
