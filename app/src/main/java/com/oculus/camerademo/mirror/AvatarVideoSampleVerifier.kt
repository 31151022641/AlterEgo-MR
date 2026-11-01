package com.oculus.camerademo.mirror

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale
import java.util.concurrent.Executors

/** Developer-only public SDK sample pixels -> estimated SDK tracking verification.
 * Callers supply synthetic sample frames, never camera images or wearer information.
 * One owned bitmap is allowed at a time; slow inference cannot build a frame queue.
 * The source SDK producer must stop before replaying these saved public fixtures. */
class AvatarVideoSampleVerifier(
    context: Context,
    private val accelerated: Boolean = false,
    private val onSnapshot: (WearerSnapshot, String, String) -> Boolean,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { work ->
        Thread(work, "AlterEgoSampleVerifier")
    }
    private val main = Handler(Looper.getMainLooper())
    private val callbackToken = Any()
    private val lock = Any()
    private val mapper = AvatarVideoTracking()
    private var faceTracker: AvatarVideoFaceTracker? = null
    private var handTracker: AvatarVideoHandTracker? = null
    @Volatile private var closed = false
    private var busy = false

    fun offer(bitmap: Bitmap, name: String, warmUpOnly: Boolean = false,
        onComplete: ((String) -> Unit)? = null): Boolean {
        val acquiredAt = SystemClock.uptimeMillis()
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
        synchronized(lock) {
            if (closed || busy) return false
            busy = true
            var owned: Bitmap? = null
            try {
                owned = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val copy = requireNotNull(owned)
                // Submission and close are serialized so cleanup follows every accepted frame.
                worker.execute { verify(copy, safeName, acquiredAt, warmUpOnly, onComplete) }
                return true
            } catch (error: Exception) {
                owned?.recycle()
                busy = false
                Log.w(TAG, "Public sample $safeName was not accepted: ${error.javaClass.simpleName}")
                return false
            }
        }
    }

    private fun verify(bitmap: Bitmap, name: String, acquiredAt: Long, warmUpOnly: Boolean,
        onComplete: ((String) -> Unit)?) {
        var warmingUp = false
        var outcome = "$name: not rendered"
        try {
            if (closed) return
            if (faceTracker == null || handTracker == null) {
                warmingUp = true
                if (faceTracker == null) faceTracker = AvatarVideoFaceTracker(appContext,accelerated)
                if (handTracker == null) handTracker = AvatarVideoHandTracker(appContext,accelerated)
            }
            if (!warmUpOnly && !fresh(acquiredAt)) {
                discardStale(name, acquiredAt, warmingUp)
                outcome = "$name: initialization exceeded the input freshness budget"
                return
            }
            // Warm-up uses immutable public pixels, never a live input. Only its
            // disposable synthetic timestamp starts after model creation.
            val inputTime = if (warmUpOnly) SystemClock.uptimeMillis() else acquiredAt
            val face = faceTracker!!.estimate(bitmap, inputTime)
            val hands = handTracker!!.estimate(bitmap, inputTime)
            if (warmUpOnly) {
                mapper.reset()
                outcome = "$name: public model warm-up complete elapsedMs=${SystemClock.uptimeMillis() - acquiredAt}"
                Log.i(TAG, outcome)
                return
            }
            if (!fresh(acquiredAt)) {
                discardStale(name, acquiredAt, warmingUp)
                outcome = "$name: stale inference discarded ageMs=${SystemClock.uptimeMillis() - acquiredAt}"
                return
            }
            val snapshot = mapper.update(face, hands, acquiredAt)
            val metrics = metrics(snapshot, face != null, hands.size) + hands.joinToString("") { hand ->
                fun points(values: List<AvatarVideoLandmark>) = listOf(0,5,9,17).joinToString(";") { n ->
                    val p = values[n]; String.format(Locale.US,"%d:%.4f,%.4f,%.4f",n,p.x,p.y,p.z)
                }
                " ${hand.side} normalized=[${points(hand.landmarks)}] world=[${points(hand.worldLandmarks)}]"
            }
            Log.i(TAG, "Public sample $name: $metrics ageMs=${SystemClock.uptimeMillis() - acquiredAt}")
            // A busy UI also receives only the latest completed inference result.
            main.removeCallbacksAndMessages(callbackToken)
            main.postAtTime({
                // A queued callback must not revive a closed verifier or display an old pose.
                outcome = if (fresh(acquiredAt)) {
                    val rendered = runCatching { onSnapshot(snapshot, name, metrics) }.getOrDefault(false)
                    "$name: rendered=$rendered $metrics"
                } else "$name: queued inference expired"
            }, callbackToken, SystemClock.uptimeMillis())
        } catch (error: Exception) {
            mapper.reset()
            outcome = "$name: verification failed ${error.javaClass.simpleName}"
            Log.e(TAG, "Public sample $name verification failed: ${error.javaClass.simpleName}")
        } finally {
            bitmap.recycle()
            synchronized(lock) { busy = false }
            // FIFO on the main handler ensures the SDK render callback above has
            // finished before a caller can submit the next saved public fixture.
            if (onComplete != null && !closed) main.postAtTime({
                if (!closed) onComplete(outcome)
            }, callbackToken, SystemClock.uptimeMillis())
        }
    }

    private fun fresh(acquiredAt: Long): Boolean =
        !closed && AvatarSampleFramePolicy.fresh(acquiredAt, SystemClock.uptimeMillis())

    private fun discardStale(name: String, acquiredAt: Long, warmingUp: Boolean) {
        mapper.reset()
        val phase = if (warmingUp) "model warm-up produced an unusable frame" else "stale inference discarded"
        Log.i(TAG, "Public sample $name: $phase ageMs=${SystemClock.uptimeMillis() - acquiredAt}")
    }

    private fun metrics(snapshot: WearerSnapshot, detectedFace: Boolean, detectedHands: Int): String {
        fun number(value: Float): String = String.format(Locale.US, "%.3f", if (value.isFinite()) value else 0f)
        val packet = snapshot.nativePacket
        fun wrist(side: Int): String {
            if (packet == null || packet[3 + side] != 1f) return "inactive"
            val at = 438 + side * 104 // OpenXR wrist joint (1), native quaternion xyzw.
            return (at..at + 3).joinToString(",", prefix = "[", postfix = "]") { number(packet[it]) }
        }
        val activeHands = snapshot.hands.map { joints -> joints.any { it != null } }
        return "faceDetected=$detectedFace handsDetected=$detectedHands " +
            "headMapped=${snapshot.head != null} handsMapped=$activeHands " +
            "blink=[${number(snapshot.expression.eyeBlinkLeft)},${number(snapshot.expression.eyeBlinkRight)}] " +
            "jaw=${number(snapshot.expression.jawOpen)} " +
            "gaze=[${number(snapshot.gazeX)},${number(snapshot.gazeY)}] " +
            "wristLeft=${wrist(0)} wristRight=${wrist(1)}"
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            main.removeCallbacksAndMessages(callbackToken)
            worker.execute {
                try { faceTracker?.close() }
                catch (error: Exception) { Log.w(TAG, "Face verifier cleanup: ${error.javaClass.simpleName}") }
                finally { faceTracker = null }
                try { handTracker?.close() }
                catch (error: Exception) { Log.w(TAG, "Hand verifier cleanup: ${error.javaClass.simpleName}") }
                finally { handTracker = null }
                mapper.reset()
            }
            worker.shutdown()
        }
    }

    companion object { private const val TAG = "AlterEgoSampleVideo" }
}
