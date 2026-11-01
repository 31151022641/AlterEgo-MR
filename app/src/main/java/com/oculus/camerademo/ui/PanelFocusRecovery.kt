package com.oculus.camerademo.ui

/** A system Reset View may place panels while its overlay still owns focus.
 * Refine that placement once when app input focus returns, using fresh poses.
 * This remembers intent only; it does not place panels or synthesize focus. */
class PanelFocusRecovery {
    private var requestedAtMs: Long? = null

    /** Repeated resets coalesce into one request, timed from the latest reset. */
    @Synchronized fun request(nowMs: Long) {
        requestedAtMs = nowMs.takeIf { it >= 0L }
    }

    /** True consumes one recent request. Unfocused callbacks keep it pending. */
    @Synchronized fun onFocusChanged(focused: Boolean, nowMs: Long): Boolean {
        val requestedAt = requestedAtMs ?: return false
        if (nowMs < requestedAt || nowMs - requestedAt > MAX_AGE_MS) {
            requestedAtMs = null
            return false
        }
        if (!focused) return false
        requestedAtMs = null
        return true
    }

    @Synchronized fun clear() { requestedAtMs = null }

    private companion object { const val MAX_AGE_MS = 30_000L }
}
