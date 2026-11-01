package com.oculus.camerademo.mirror

import android.graphics.Bitmap
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage

internal data class AvatarSampleModelInput(val bitmap: Bitmap,val capturedAt: Long,
    val run: Long,val selection: Long,val preprocessMs: Long) : AutoCloseable {
    override fun close() { if (!bitmap.isRecycled) bitmap.recycle() }
}

internal class AvatarSampleModelResult<R>(val value: R,val capturedAt: Long,
    val run: Long,val selection: Long,val preprocessMs: Long,val inputQueueMs: Long,
    val modelMs: Long,val unavailable: Boolean,preview: Bitmap?) : AutoCloseable {
    private var pixels = preview
    @Synchronized fun takePreview(): Bitmap? = pixels.also { pixels = null }
    override fun close() { takePreview()?.let { if (!it.isRecycled) it.recycle() } }
}

/** A model, including GPU creation/fallback/retry/close, has one persistent owner.
 * Inputs are exclusively owned and latest-only. No MPImage or Bitmap is shared
 * with the other model or the serial retarget coordinator. */
internal class AvatarSampleModelWorker<T : AutoCloseable,R>(private val label: String,
    private val create: () -> T,private val estimate: (T,MPImage,Long) -> R,
    private val missing: () -> R,private val retainPreview: Boolean,
    private val current: (AvatarSampleModelInput) -> Boolean,
    private val onResult: (AvatarSampleModelResult<R>) -> Unit) : AutoCloseable {
    private val thread = HandlerThread("AlterEgoSample$label").apply { start() }
    private val handler = Handler(thread.looper)
    private val queue = AvatarLatestOwnedInput<AvatarSampleModelInput> { input ->
        quietly("pending-close") { input.close() }
    }
    private val scheduleLock = Any()
    private var scheduled = false
    @Volatile private var closed = false
    private var model: T? = null
    private var retryAt = 0L
    private val faultLock = Any()
    private var lastFaultAt = 0L
    private val drain = object : Runnable {
        override fun run() {
            val input = queue.take()
            try { if (input != null) process(input) }
            finally { synchronized(scheduleLock) {
                if (!closed && queue.hasPending()) {
                    if (!postDrain()) { queue.clear(); scheduled = false }
                } else scheduled = false
            } }
        }
    }

    fun offer(input: AvatarSampleModelInput) {
        if (!queue.offer(input)) return
        synchronized(scheduleLock) {
            if (!scheduled && !closed) {
                scheduled = true
                if (!postDrain()) { queue.clear(); scheduled = false }
            }
        }
    }

    fun clearPending() = queue.clear()

    private fun process(input: AvatarSampleModelInput) {
        var image: MPImage? = null
        var preview: Bitmap? = null
        var untransferred: AvatarSampleModelResult<R>? = null
        try {
            if (closed || !current(input)) return
            val startedAt = SystemClock.uptimeMillis()
            // Report the initial failure once. Repeated cooldown inputs cannot
            // create empty revisions and consume SDK GPU time at camera cadence.
            if (model == null && startedAt < retryAt) return
            var unavailable = false
            val result = try {
                val task = model ?: create().also { model = it }
                image = BitmapImageBuilder(input.bitmap).build()
                estimate(task,image!!,input.capturedAt)
            } catch (error: Exception) {
                unavailable = true; failed(error); missing()
            } catch (error: UnsatisfiedLinkError) {
                unavailable = true; failed(error); missing()
            }
            val modelMs = SystemClock.uptimeMillis()-startedAt
            if (closed || !current(input)) return
            if (retainPreview) preview = input.bitmap.copy(Bitmap.Config.ARGB_8888,false)
                ?: error("Could not copy sample preview")
            val output = AvatarSampleModelResult(result,input.capturedAt,input.run,input.selection,
                input.preprocessMs,startedAt-input.capturedAt,modelMs,unavailable,preview)
            untransferred = output
            preview = null // The result owns pixels before attempting publication.
            onResult(output)
            untransferred = null // Ownership transfers only after successful delivery.
        } catch (error: Exception) {
            // A publication/preview fault is not a missing detection. Do not
            // fabricate another result or refresh any channel's capture time.
            fault("publication",error)
        } catch (error: UnsatisfiedLinkError) {
            fault("publication",error)
        } finally {
            quietly("result-close") { untransferred?.close() }
            quietly("preview-close") { preview?.let { if (!it.isRecycled) it.recycle() } }
            quietly("image-close") { image?.close() }
            quietly("input-close") { input.close() }
        }
    }

    private fun failed(error: Throwable) {
        fault("inference",error)
        quietly("model-close") { model?.close() }; model = null
        retryAt = SystemClock.uptimeMillis()+5000L
    }

    private fun postDrain(): Boolean = try { handler.post(drain) }
        catch (error: Exception) { fault("scheduling",error); false }
        catch (error: UnsatisfiedLinkError) { fault("scheduling",error); false }

    private fun quietly(phase: String,action: () -> Unit) {
        try { action() }
        catch (error: Exception) { fault(phase,error) }
        catch (error: UnsatisfiedLinkError) { fault(phase,error) }
    }

    private fun fault(phase: String,error: Throwable) {
        val now = SystemClock.uptimeMillis()
        val report = synchronized(faultLock) {
            if (lastFaultAt == 0L || now-lastFaultAt >= 5000L) { lastFaultAt = now; true } else false
        }
        if (report) Log.w("AlterEgoAvatarVideo","Sample $label worker fault phase=$phase error=${error.javaClass.simpleName}")
    }

    override fun close() {
        synchronized(scheduleLock) {
            if (closed) return
            closed = true; queue.close()
        }
        // An active synchronous inference completes first. No main-thread join
        // and no GPU task lifecycle call happens outside its owning worker.
        quietly("shutdown-scheduling") {
            handler.post {
                try { quietly("model-close") { model?.close() }; model = null }
                finally { thread.quitSafely() }
            }
        }
    }
}
