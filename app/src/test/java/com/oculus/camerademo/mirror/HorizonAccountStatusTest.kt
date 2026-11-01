package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class HorizonAccountStatusTest {
    @Test fun rejectedIdentityDoesNotAskForAppEntitlementAgain() {
        val message = HorizonAccountStatus.message(-3, 2)
        assertTrue(message.contains("identity access"))
        assertTrue(message.contains("verification"))
        assertTrue(message.contains("User ID / User Profile"))
        assertFalse(message.contains("test release"))
    }
    @Test fun entitlementFailureExplainsTheMissingAppAccess() {
        assertTrue(HorizonAccountStatus.message(-1, -9).contains("test release"))
        assertTrue(HorizonAccountStatus.message(-2, 1971051).contains("entitlement"))
    }
    @Test fun confirmedAccessDoesNotClaimThatAnAvatarWasRendered() {
        assertTrue(HorizonAccountStatus.message(6).contains("Loading the model"))
        assertFalse(HorizonAccountStatus.isPending(6))
    }
    @Test fun sampleCannotBeMistakenForPersonalAvatar() {
        assertTrue(HorizonAccountStatus.isPending(20))
        assertFalse(HorizonAccountStatus.isPending(21))
        assertTrue(HorizonAccountStatus.message(21).contains("not your personal"))
        assertTrue(HorizonAccountStatus.isPending(7))
        assertFalse(HorizonAccountStatus.isPending(8))
    }
    @Test fun ErrorsAndTimeoutsAllowRetry() {
        (-9..-1).forEach { assertFalse(HorizonAccountStatus.isPending(it)) }
        (1..5).forEach { assertTrue(HorizonAccountStatus.isPending(it)) }
        assertTrue(HorizonAccountStatus.message(-7).contains("Data Use"))
    }
}
