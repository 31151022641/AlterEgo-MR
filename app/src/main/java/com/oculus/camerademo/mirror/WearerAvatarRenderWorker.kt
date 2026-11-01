package com.oculus.camerademo.mirror

import android.graphics.Bitmap
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.oculus.camerademo.model3d.HumanPersona
import java.util.concurrent.Executors

/** Bounded software-avatar rendering, separate from Android/hand interaction.
 * Busy offers are skipped; the next mirror tick supplies the newest observation.
 * A true onFrame return transfers its Bitmap to the main-thread consumer. */
class WearerAvatarRenderWorker(
    private val painter: WearerAvatarPainter,
    private val handler: Handler,
    private val onRenderTime: (Long) -> Unit = {},
    private val onFrame: (Bitmap, WearerSnapshot, HumanPersona, Boolean, Long) -> Boolean,
) : AutoCloseable {
    private val lock = Any()
    private val gate = AvatarRenderGate()
    private val executor = Executors.newSingleThreadExecutor { work ->
        Thread({
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            catch (_: SecurityException) { /* Rendering still stays off main. */ }
            work.run()
        }, "AlterEgoPresetRender").apply { isDaemon = true }
    }
    private var closed = false
    private var recenterQueued = false
    private var pending: Output? = null
    private val faultLock = Any()
    private var lastFaultAt: Long? = null

    private inner class Output(val token: AvatarRenderGate.Token, val bitmap: Bitmap,
        val snapshot: WearerSnapshot, val persona: HumanPersona, val hairEnabled: Boolean,
        val renderElapsedMs: Long) {
        val callback = Runnable { deliver(this) }
    }

    fun offer(snapshot: WearerSnapshot, persona: HumanPersona, hairEnabled: Boolean,
              quality: AvatarVideoQuality) {
        if (!snapshot.isFresh(SystemClock.uptimeMillis())) return
        synchronized(lock) {
            if (closed) return
            val token = gate.issue() ?: return
            try {
                executor.execute { render(token, snapshot, persona, hairEnabled, quality) }
            } catch (error: Throwable) {
                gate.complete(token)
                fault("schedule", error)
                rethrowFatal(error)
            }
        }
    }

    fun invalidate(recenter: Boolean = false) {
        val dispose = synchronized(lock) {
            if (closed) return
            gate.invalidate()
            val output = pending
            if (output != null) {
                pending = null
                handler.removeCallbacks(output.callback)
                gate.complete(output.token)
            }
            if (recenter && !recenterQueued) {
                recenterQueued = true
                try {
                    executor.execute {
                        try {
                            val allowed = synchronized(lock) { !closed }
                            if (allowed) painter.recenter()
                        } catch (error: Throwable) {
                            fault("recenter", error)
                            rethrowFatal(error)
                        } finally { synchronized(lock) { recenterQueued = false } }
                    }
                } catch (error: Throwable) {
                    recenterQueued = false
                    fault("recenter-schedule", error)
                    rethrowFatal(error)
                }
            }
            output
        }
        dispose?.bitmap?.let(::recycle)
    }

    private fun render(token: AvatarRenderGate.Token, snapshot: WearerSnapshot,
                       persona: HumanPersona, hairEnabled: Boolean, quality: AvatarVideoQuality) {
        var unowned: Bitmap? = null
        var handedToMain = false
        try {
            if (!gate.isCurrent(token) || !snapshot.isFresh(SystemClock.uptimeMillis())) return
            val startedAt = SystemClock.uptimeMillis()
            val bitmap = painter.draw(snapshot, persona, hairEnabled, quality)
            unowned = bitmap
            val elapsed = (SystemClock.uptimeMillis() - startedAt).coerceAtLeast(0L)
            // Even an image that became stale must feed the cadence budget;
            // otherwise a slow device could repeatedly render without adapting.
            handler.post {
                if (synchronized(lock) { !closed }) onRenderTime(elapsed)
            }
            synchronized(lock) {
                if (closed || !gate.isCurrent(token) || !snapshot.isFresh(SystemClock.uptimeMillis())) return@synchronized
                val output = Output(token, bitmap, snapshot, persona, hairEnabled, elapsed)
                check(pending == null) { "One render owner cannot publish multiple pending outputs" }
                if (!handler.post(output.callback)) {
                    return@synchronized
                }
                // A posted callback cannot enter deliver until this lock is
                // released, so publish ownership only after posting succeeds.
                pending = output
                // Invalidation/close now owns disposal, or deliver transfers it.
                handedToMain = true
                unowned = null
            }
        } catch (error: Throwable) {
            fault("render", error)
            rethrowFatal(error)
        } finally {
            try { unowned?.let(::recycle) }
            finally { if (!handedToMain) gate.complete(token) }
        }
    }

    private fun deliver(output: Output) {
        var transferred = false
        var claimed = false
        try {
            // Serialize validity and the ownership decision with close/reset.
            // Parent actions may reenter this monitor on the main thread.
            synchronized(lock) {
                if (pending !== output) return@synchronized
                claimed = true
                pending = null
                if (closed || !gate.isCurrent(output.token) ||
                    !output.snapshot.isFresh(SystemClock.uptimeMillis())) return@synchronized
                transferred = onFrame(output.bitmap, output.snapshot, output.persona,
                    output.hairEnabled, output.renderElapsedMs)
            }
        } catch (error: Throwable) {
            fault("publish", error)
            rethrowFatal(error)
        } finally {
            // A callback removed by invalidate/close no longer owns either the
            // pixels or the slot. It must not race that owner's disposal.
            if (claimed) {
                try { if (!transferred) recycle(output.bitmap) }
                finally { gate.complete(output.token) }
            }
        }
    }

    override fun close() {
        val dispose = synchronized(lock) {
            if (closed) return
            closed = true; gate.close()
            pending.also { output ->
                pending = null
                if (output != null) {
                    handler.removeCallbacks(output.callback)
                    gate.complete(output.token)
                }
            }
        }
        try { dispose?.bitmap?.let(::recycle) }
        finally {
            // Do not wait on the UI thread. An already-running painter finishes,
            // observes the closed generation, and disposes its own result.
            executor.shutdownNow()
        }
    }

    private fun recycle(bitmap: Bitmap) {
        try { if (!bitmap.isRecycled) bitmap.recycle() }
        catch (error: Throwable) {
            fault("dispose", error)
            rethrowFatal(error)
        }
    }

    private fun fault(phase: String, error: Throwable) {
        val now = SystemClock.uptimeMillis()
        synchronized(faultLock) {
            if (lastFaultAt?.let { now - it in 0L until 5000L } == true) return
            lastFaultAt = now
            // Fixed phase and error class only; no avatar, pose or endpoint data.
            Log.w("AlterEgoPresetRender", "$phase failed (${error.javaClass.simpleName})")
        }
    }

    private fun rethrowFatal(error: Throwable) {
        if (error is VirtualMachineError || error is ThreadDeath) throw error
    }
}
