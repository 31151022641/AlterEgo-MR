package com.oculus.camerademo.mirror

/** One pending owned value. Taking transfers ownership to the active consumer;
 * replacement, clear and close dispose only pending values, never active work. */
internal class AvatarLatestOwnedInput<T>(private val dispose: (T) -> Unit) {
    private var pending: T? = null
    private var closed = false

    @Synchronized fun offer(input: T): Boolean {
        if (closed) { dispose(input); return false }
        pending?.let(dispose)
        pending = input
        return true
    }

    @Synchronized fun take(): T? = pending.also { pending = null }
    @Synchronized fun hasPending() = pending != null
    @Synchronized fun clear() { pending?.let(dispose); pending = null }
    @Synchronized fun close() { closed = true; clear() }
}
