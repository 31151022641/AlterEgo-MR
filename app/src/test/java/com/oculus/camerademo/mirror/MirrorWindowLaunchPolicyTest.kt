package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorWindowLaunchPolicyTest {
    @Test fun explicitExitWorksWhileCameraPermissionsOrXrInitializationArePending() {
        assertTrue(MirrorWindowLaunchPolicy.allowed(userRequested=true,automaticReady=false,launched=false,closing=false))
    }

    @Test fun automaticHandoffStillWaitsForItsReadyState() {
        assertFalse(MirrorWindowLaunchPolicy.allowed(false,false,false,false))
        assertTrue(MirrorWindowLaunchPolicy.allowed(false,true,false,false))
    }

    @Test fun repeatedExitCannotLaunchAnotherMirrorDuringHandoff() {
        assertFalse(MirrorWindowLaunchPolicy.allowed(true,false,true,false))
        assertFalse(MirrorWindowLaunchPolicy.allowed(true,true,true,false))
    }

    @Test fun closingActivityCannotLaunchANewWindow() {
        assertFalse(MirrorWindowLaunchPolicy.allowed(true,false,false,true))
        assertFalse(MirrorWindowLaunchPolicy.allowed(false,true,false,true))
    }
}
