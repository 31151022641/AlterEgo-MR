package com.oculus.camerademo.ui

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class SpatialMenuInteractionTest {
    @Test fun scrolledOutTargetsCannotBeSelected() {
        val menu=SpatialMenuInteraction();var clicks=0
        menu.registerTarget("face",Rect(10f,10f,90f,90f)){clicks++}
        menu.navigateNext()
        menu.registerTarget("face",Rect.Zero){clicks++}
        assertFalse(menu.triggerSelection());assertEquals(0,clicks);assertNull(menu.focusedTargetId)
    }
    @Test fun invalidBoundsNeverEnterNavigation() {
        val menu=SpatialMenuInteraction()
        menu.registerTarget("invalid",Rect(Float.NaN,0f,20f,20f)){}
        menu.registerTarget("reversed",Rect(40f,0f,20f,20f)){}
        menu.navigateNext();assertNull(menu.focusedTargetId)
    }
    @Test fun returningVisibleTargetCanBeSelectedAgain() {
        val menu=SpatialMenuInteraction();var clicks=0
        menu.registerTarget("face",Rect.Zero){clicks++}
        menu.registerTarget("face",Rect(10f,10f,90f,90f)){clicks++}
        menu.navigateNext();assertTrue(menu.triggerSelection());assertEquals(1,clicks)
    }
    @Test fun pointerOutsidePanelDoesNotClampOntoAnEdgeButton() {
        val menu=SpatialMenuInteraction();var clicks=0
        menu.registerTarget("close",Rect(90f,0f,100f,20f)){clicks++}
        menu.updatePointer(1.2f,.1f,100f,100f,"EYE_PINCH")
        assertFalse(menu.triggerSelection());assertEquals(0,clicks);assertFalse(menu.isGazeActive)
    }
    @Test fun clearFocusPreventsOldPageSelection() {
        val menu=SpatialMenuInteraction()
        menu.registerTarget("previous-page",Rect(0f,0f,100f,100f)){}
        menu.navigateNext();menu.clearFocus();assertFalse(menu.triggerSelection())
    }
    @Test fun targetCallbacksFollowLatestSelection() {
        val menu=SpatialMenuInteraction();var selected=""
        menu.registerTarget("face",Rect(0f,0f,100f,100f)){selected="old"}
        menu.registerTarget("face",Rect(0f,0f,100f,100f)){selected="new"}
        menu.updatePointer(.5f,.5f,100f,100f,"EYE_PINCH")
        assertTrue(menu.triggerSelection());assertEquals("new",selected)
    }
}
