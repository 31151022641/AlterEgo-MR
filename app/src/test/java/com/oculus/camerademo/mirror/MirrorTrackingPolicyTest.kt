package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class MirrorTrackingPolicyTest {
    @Test fun liveWindowContinuesWhenSensorActivityLosesAndroidFocus() {
        assertTrue(MirrorTrackingPolicy.canSample(true, true, true, false, true))
    }
    @Test fun homeOrAnotherImmersiveSessionCannotUseCachedHeadPoseAsLiveTracking() {
        assertFalse(MirrorTrackingPolicy.canSample(true, false, true, false, true))
    }
    @Test fun closingWindowWhileSensorActivityIsPausedStopsCapture() {
        assertFalse(MirrorTrackingPolicy.canSample(true, true, true, false, false))
    }
    @Test fun sceneShutdownAndPassthroughModeDisableSelfTracking() {
        assertFalse(MirrorTrackingPolicy.canSample(false, true, true, true, true))
        assertFalse(MirrorTrackingPolicy.canSample(true, true, false, true, true))
    }
}
