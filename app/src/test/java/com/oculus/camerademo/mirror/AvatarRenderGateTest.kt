package com.oculus.camerademo.mirror

import org.junit.Assert.*
import org.junit.Test

class AvatarRenderGateTest {
    @Test fun busyRenderKeepsOnlyOneOwnerThroughItsOutputHandoff() {
        val gate = AvatarRenderGate()
        val active = gate.issue()!!
        assertTrue(gate.isCurrent(active))
        repeat(100) { assertNull(gate.issue()) }
        assertTrue(gate.complete(active))
        assertNotNull(gate.issue())
    }

    @Test fun invalidationRejectsOldOutputButCannotStartAnotherPainterInParallel() {
        val gate = AvatarRenderGate()
        val old = gate.issue()!!
        gate.invalidate()
        assertFalse(gate.isCurrent(old))
        assertNull(gate.issue())
        assertFalse(gate.complete(old))
        val current = gate.issue()!!
        assertTrue(gate.isCurrent(current))
        assertTrue(gate.complete(current))
    }

    @Test fun duplicateOldCompletionCannotReleaseANewerRender() {
        val gate = AvatarRenderGate()
        val first = gate.issue()!!
        assertTrue(gate.complete(first))
        val next = gate.issue()!!
        assertFalse(gate.complete(first))
        assertFalse(gate.isCurrent(first))
        assertNull(gate.issue())
        assertTrue(gate.isCurrent(next))
        assertTrue(gate.complete(next))
    }

    @Test fun repeatedInvalidationsDoNotLoseTheActiveOwnersCleanupResponsibility() {
        val gate = AvatarRenderGate()
        val old = gate.issue()!!
        repeat(100) { gate.invalidate() }
        assertNull(gate.issue())
        assertFalse(gate.complete(old))
        assertNotNull(gate.issue())
    }

    @Test fun closeRejectsBothExistingOutputAndFutureOffers() {
        val gate = AvatarRenderGate()
        val active = gate.issue()!!
        gate.close(); gate.close(); gate.invalidate()
        assertFalse(gate.isCurrent(active))
        assertNull(gate.issue())
        assertFalse(gate.complete(active))
        assertNull(gate.issue())
        assertFalse(gate.complete(active))
    }

    @Test fun idleInvalidationStillAllowsTheNextFreshObservation() {
        val gate = AvatarRenderGate()
        gate.invalidate()
        val first = gate.issue()!!
        assertTrue(gate.isCurrent(first))
        assertTrue(gate.complete(first))
    }

    @Test fun foreignGateTokensCannotConsumeAnotherWorkersSlot() {
        val gate = AvatarRenderGate()
        val other = AvatarRenderGate()
        val active = gate.issue()!!
        val foreign = other.issue()!!
        assertFalse(gate.isCurrent(foreign))
        assertFalse(gate.complete(foreign))
        assertNull(gate.issue())
        assertTrue(gate.complete(active))
        assertTrue(other.complete(foreign))
    }
}
