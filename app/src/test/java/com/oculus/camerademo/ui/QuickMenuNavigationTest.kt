package com.oculus.camerademo.ui
import org.junit.Assert.*
import org.junit.Test

class QuickMenuNavigationTest {
    @Test fun resetRevealsLiveMirrorFromEveryLauncherPageAndVisibilityState() {
        for (page in QuickMenuPage.values()) for (open in listOf(false, true)) {
            val recovered = QuickMenuNavigation(open, page).recoverWindow(liveMirror = true)
            assertFalse("Mirror covered after reset from $page, open=$open", recovered.open)
            assertEquals(page, recovered.page)
            assertTrue(recovered.backReturnsToMirror)
        }
    }

    @Test fun passthroughResetRecoversTheLauncherHomeFromEveryPageAndVisibilityState() {
        for (page in QuickMenuPage.values()) for (open in listOf(false, true)) {
            val recovered = QuickMenuNavigation(open, page).recoverWindow(liveMirror = false)
            assertTrue("No launcher after reset from $page, open=$open", recovered.open)
            assertEquals(QuickMenuPage.HOME, recovered.page)
        }
    }

    @Test fun explicitMenuStillOpensAfterResetHasRevealedTheMirror() {
        for (page in QuickMenuPage.values()) for (open in listOf(false, true)) {
            val recovered = QuickMenuNavigation(open, page).recoverWindow(liveMirror = true)
            val menu = recovered.show()
            assertTrue("Explicit Menu failed after mirror reset from $page, open=$open", menu.open)
            assertEquals(QuickMenuPage.HOME, menu.page)
        }
    }

    @Test fun freshLocalMenuEdgeWorksAfterFocusLossWithoutRequiringAnotherPress(){
        val gate=MenuPressGate()
        gate.focusLost();gate.controller(false,false,1000)
        assertTrue(gate.controllerPress(true,1200))
        assertFalse(gate.controller(true,true,1210))
    }
    @Test fun freshLocalMenuEdgesStillDeduplicateAndroidDelivery(){
        val sdkFirst=MenuPressGate()
        assertTrue(sdkFirst.controllerPress(true,1000))
        assertFalse(sdkFirst.key(true,0,true,1005))
        val androidFirst=MenuPressGate()
        assertTrue(androidFirst.key(true,0,true,1000))
        assertFalse(androidFirst.controllerPress(true,1005))
    }
    @Test fun repeatedFreshEdgeDeliveryDoesNotActTwiceAndLaterPressRecovers(){
        val gate=MenuPressGate()
        assertFalse(gate.controllerPress(false,900))
        assertTrue(gate.controllerPress(true,1000))
        assertFalse(gate.controllerPress(true,1005))
        assertFalse(gate.controller(true,true,1500))
        gate.controller(false,true,1700)
        assertTrue(gate.controllerPress(true,2000))
    }
    @Test fun repeatedHandControlMenuPressesBringTheLauncherUpRatherThanClosingIt(){
        val gate=MenuPressGate()
        var menu=QuickMenuNavigation(false,QuickMenuPage.FACES)
        if(gate.key(true,0,true,1000))menu=menu.show()
        if(gate.controller(true,true,1005))menu=menu.show()
        assertEquals(QuickMenuNavigation(true,QuickMenuPage.HOME),menu)
        gate.key(false,0,true,1200);gate.controller(false,true,1205)
        if(gate.controller(true,true,1400))menu=menu.show()
        assertEquals(QuickMenuNavigation(true,QuickMenuPage.HOME),menu)
    }
    @Test fun menuRecoversWhenTheHandControlKeyUpWasLost(){
        val gate=MenuPressGate()
        assertTrue(gate.key(true,0,true,1000))
        assertFalse(gate.key(true,0,true,1010))
        assertTrue(gate.key(true,0,true,2000))
    }
    @Test fun heldAndroidRepeatsCannotReopenTheMenuEvenAfterTheDeduplicationWindow(){
        val gate=MenuPressGate()
        assertTrue(gate.key(true,0,true,1000))
        assertFalse(gate.key(true,1,true,2000))
        assertFalse(gate.key(true,2,true,3000))
    }
    @Test fun closedMenuBackReturnsToMirrorEvenAfterASubpageWasClosed(){
        for(page in QuickMenuPage.values())assertTrue(QuickMenuNavigation(false,page).backReturnsToMirror)
    }
    @Test fun visibleLauncherHomeBackReturnsToMirror(){assertTrue(QuickMenuNavigation(true).backReturnsToMirror)}
    @Test fun visibleSubmenuBackReturnsHomeBeforeLeavingImmersive(){
        for(page in QuickMenuPage.values().filter{it!=QuickMenuPage.HOME}) {
            val submenu=QuickMenuNavigation(true,page)
            assertFalse(submenu.backReturnsToMirror)
            assertTrue(submenu.back().open)
            assertTrue(submenu.back().backReturnsToMirror)
        }
    }
    @Test fun duplicateBackDeliveryCannotSkipFromASubmenuStraightOutOfImmersive(){
        val gate=MenuPressGate()
        var menu=QuickMenuNavigation(true,QuickMenuPage.FACES)
        var exits=0
        fun navigateBack(){if(menu.backReturnsToMirror)exits++ else menu=menu.back()}
        if(gate.controller(true,true,1000))navigateBack()
        if(gate.key(true,0,true,1005))navigateBack()
        assertEquals(QuickMenuPage.HOME,menu.page);assertEquals(0,exits)
        gate.controller(false,true,1200);gate.key(false,0,true,1205)
        if(gate.controller(true,true,1400))navigateBack()
        assertEquals(1,exits)
    }
    @Test fun openingAlwaysStartsAtLauncher(){assertEquals(QuickMenuPage.HOME,QuickMenuNavigation(false,QuickMenuPage.LINK).toggle().page)}
    @Test fun secondPressCloses(){assertFalse(QuickMenuNavigation().toggle().toggle().open)}
    @Test fun pageBackReturnsHomeWithoutClosing(){val result=QuickMenuNavigation(true,QuickMenuPage.IMPORT).back();assertTrue(result.open);assertEquals(QuickMenuPage.HOME,result.page)}
    @Test fun homeBackCloses(){assertFalse(QuickMenuNavigation(true).back().open)}
    @Test fun closePreservesNavigationButNotVisibility(){assertEquals(QuickMenuNavigation(false,QuickMenuPage.FACES),QuickMenuNavigation(true,QuickMenuPage.FACES).close())}
    @Test fun explicitNavigationOpens(){assertTrue(QuickMenuNavigation().show(QuickMenuPage.FACES).open)}
    @Test fun sdkThenAndroidDeliveryOnlyTogglesOnce(){val gate=MenuPressGate();assertTrue(gate.controller(true,true,1000));assertFalse(gate.key(true,0,true,1005))}
    @Test fun androidThenSdkDeliveryOnlyTogglesOnce(){val gate=MenuPressGate();assertTrue(gate.key(true,0,true,1000));assertFalse(gate.controller(true,true,1005))}
    @Test fun heldAndRepeatDoNotToggle(){val gate=MenuPressGate();assertTrue(gate.controller(true,true,1000));assertFalse(gate.controller(true,true,1400));assertFalse(gate.key(true,0,true,1800));assertFalse(gate.key(true,1,true,2200))}
    @Test fun anOlderAndroidTimestampStillDeduplicates(){val gate=MenuPressGate();assertTrue(gate.controller(true,true,1000));gate.controller(false,true,1040);assertFalse(gate.key(true,0,true,990))}
    @Test fun delayedSdkHeldStateDoesNotDuplicateAndroidPress(){val gate=MenuPressGate();assertTrue(gate.key(true,0,true,1000));assertFalse(gate.controller(true,true,1800))}
    @Test fun releaseIsNotAnAction(){val gate=MenuPressGate();assertFalse(gate.controller(false,true,1000));assertFalse(gate.key(false,0,true,1000))}
    @Test fun aLaterNewPressIsAccepted(){val gate=MenuPressGate();assertTrue(gate.controller(true,true,1000));gate.controller(false,true,1200);assertTrue(gate.controller(true,true,1400))}
    @Test fun deliberateFastDoublePressStillWorks(){val gate=MenuPressGate();assertTrue(gate.controller(true,true,1000));gate.controller(false,true,1060);assertTrue(gate.controller(true,true,1120))}
    @Test fun deliberateTwoAndroidPressesStillWork(){val gate=MenuPressGate();assertTrue(gate.key(true,0,true,1000));gate.key(false,0,true,1060);assertTrue(gate.key(true,0,true,1120))}
    @Test fun focusRecoveryWaitsForControllerRelease(){val gate=MenuPressGate();gate.focusLost();assertFalse(gate.controller(true,true,1000));assertFalse(gate.controller(false,true,1100));assertTrue(gate.controller(true,true,1300))}
    @Test fun unfocusedInputsCannotOpen(){val gate=MenuPressGate();assertFalse(gate.controller(true,false,1000));assertFalse(gate.key(true,0,false,1100))}
    @Test fun controllerReleaseAfterFocusLossRearms(){val gate=MenuPressGate();gate.controller(true,false,1000);assertFalse(gate.controller(true,true,1200));gate.controller(false,true,1300);assertTrue(gate.controller(true,true,1400))}
}
