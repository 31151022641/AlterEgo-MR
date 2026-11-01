package com.oculus.camerademo.mirror

import android.util.Log
import com.google.mediapipe.tasks.core.Delegate

/** A task and its GPU context belong to the persistent inference worker. Call
 * creation, inference and close on that same thread. Acceleration is opt-in so
 * public fixture tools retain their existing CPU ownership. */
internal class AvatarInferenceTask<T : AutoCloseable>(private val label: String,
    accelerated: Boolean, private val create: (Delegate) -> T) : AutoCloseable {
    private val owner = Thread.currentThread()
    private var gpu = accelerated && !disabledGpu.contains(label)
    private var task: T = if (gpu) try {
        create(Delegate.GPU).also { Log.i(TAG,"$label estimates: GPU") }
    } catch (failure: RuntimeException) {
        gpu = false
        disabledGpu.add(label)
        Log.w(TAG,"$label acceleration unavailable; using CPU: ${failure.javaClass.simpleName}")
        create(Delegate.CPU)
    } catch (failure: UnsatisfiedLinkError) {
        gpu = false
        disabledGpu.add(label)
        Log.w(TAG,"$label acceleration unavailable; using CPU: ${failure.javaClass.simpleName}")
        create(Delegate.CPU)
    } else create(Delegate.CPU)

    fun <R> run(inference: (T) -> R): R {
        if (gpu) check(Thread.currentThread() === owner) { "Avatar inference moved off its GPU owner" }
        return try { inference(task) } catch (failure: RuntimeException) {
            if (!gpu) throw failure
            // Recreate the video graph on this owner. No timestamp or previous
            // GPU result is reused; the CPU graph receives the current image.
            runCatching { task.close() }
            gpu = false
            disabledGpu.add(label)
            task = create(Delegate.CPU)
            Log.w(TAG,"$label acceleration failed; continued on CPU: ${failure.javaClass.simpleName}")
            inference(task)
        }
    }

    override fun close() {
        if (gpu) check(Thread.currentThread() === owner) { "Avatar GPU task closed off its owner" }
        task.close()
    }
    companion object {
        private const val TAG = "AlterEgoAvatarVideo"
        private val disabledGpu = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    }
}
