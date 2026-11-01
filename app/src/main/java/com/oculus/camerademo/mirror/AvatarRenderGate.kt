package com.oculus.camerademo.mirror

/** One owned render through its output handoff. Invalidating never releases an
 * active painter: only its matching completion can make room for another job. */
class AvatarRenderGate {
    class Token internal constructor(internal val generation: Long)
    private var generation = 0L
    private var active: Token? = null
    private var closed = false

    @Synchronized fun issue(): Token? {
        if (closed || active != null) return null
        return Token(generation).also { active = it }
    }

    @Synchronized fun isCurrent(token: Token): Boolean =
        !closed && active === token && token.generation == generation

    /** Releases only the active owner's slot. The return value says whether its
     * result still belonged to the current generation at completion. */
    @Synchronized fun complete(token: Token): Boolean {
        if (active !== token) return false
        val current = !closed && token.generation == generation
        active = null
        return current
    }

    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun close() { if (!closed) { closed = true; generation++ } }
}
