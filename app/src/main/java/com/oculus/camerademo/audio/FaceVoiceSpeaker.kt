package com.oculus.camerademo.audio

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Real-time Voice Speaker for AlterEgo MR.
 *
 * When the user's eye tracking detects and fixates on a face in physical space,
 * this speaker calculates the total number of faces present in front of the user
 * and speaks aloud through the Meta Quest spatial audio speakers:
 *   "<the number totally> faces you are looking at" (e.g. "1 face you are looking at" or "2 faces you are looking at")
 *
 * Designed with intelligent debouncing and cooldown protection to avoid audio stutter or loop spam.
 */
class FaceVoiceSpeaker(
    private val context: Context,
    private val onAnnouncement: ((String) -> Unit)? = null
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isTtsReady: Boolean = false
        private set

    var isVoiceEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                stop()
            }
        }

    var lastAnnouncementText: String? = null
        private set

    // Debounce & state tracking
    private var lastSpokenTime: Long = 0L
    private var lastLockedHeadId: Int? = null
    private var lastSpokenCount: Int = 0
    private var wasInGaze: Boolean = false

    // Minimum cooldown between spoken announcements (prevents audio spam)
    private val MIN_COOLDOWN_MS = 3500L

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize TextToSpeech", e)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.US)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Locale.US not supported, falling back to default locale: ${Locale.getDefault()}")
                tts?.language = Locale.getDefault()
            }
            tts?.setSpeechRate(1.05f) // Crisp, natural speech cadence
            tts?.setPitch(1.0f)
            isTtsReady = true
            Log.i(TAG, "TextToSpeech successfully initialized and ready for Quest audio output")
            onAnnouncement?.invoke("Speaker Ready")
        } else {
            Log.e(TAG, "TextToSpeech initialization failed with status $status")
            isTtsReady = false
            onAnnouncement?.invoke("Speaker Unavailable")
        }
    }

    /**
     * Evaluates real-time gaze and face tracking to determine if an audio announcement should be made.
     *
     * @param isFaceInGaze True if the user's eye tracking currently fixates on a face
     * @param lockedHeadId The ID of the specific tracked head being looked at, or null if none
     * @param totalFacesInFront The total number of faces present in the mixed reality camera view
     */
    fun onFaceGazeEvaluated(
        isFaceInGaze: Boolean,
        lockedHeadId: Int?,
        totalFacesInFront: Int
    ) {
        if (!isVoiceEnabled || !isTtsReady) return

        val now = SystemClock.uptimeMillis()
        val count = totalFacesInFront.coerceAtLeast(if (isFaceInGaze) 1 else 0)

        if (isFaceInGaze && count > 0) {
            val timeSinceLast = now - lastSpokenTime

            // Trigger criteria:
            // 1. User was looking away and just fixated on a face
            val justEnteredGaze = !wasInGaze
            // 2. User shifted their gaze to a different person/face ID
            val switchedHead = (lockedHeadId != null && lockedHeadId != lastLockedHeadId)
            // 3. The count of faces in front of the user changed while fixating
            val countChanged = (count != lastSpokenCount)
            // 4. Continuous dwell reminder if staring for > 7.5 seconds
            val dwellReminder = (timeSinceLast >= 7500L)

            val canSpeak = timeSinceLast >= MIN_COOLDOWN_MS

            if ((justEnteredGaze || switchedHead || countChanged || dwellReminder) && canSpeak) {
                lastSpokenTime = now
                lastLockedHeadId = lockedHeadId
                lastSpokenCount = count
                wasInGaze = true

                val message = if (count == 1) {
                    "1 face you are looking at"
                } else {
                    "$count faces you are looking at"
                }

                speak(message)
            } else if (justEnteredGaze) {
                wasInGaze = true
            }
        } else {
            // User looked away from all faces
            wasInGaze = false
            // After looking away for 1.5 seconds, reset last locked head so looking back triggers promptly
            if (now - lastSpokenTime >= 1500L) {
                lastLockedHeadId = null
            }
        }
    }

    /**
     * Directly speaks the given text string via the headset's speakers.
     */
    fun speak(text: String, isTest: Boolean = false) {
        if (!isTtsReady) {
            Log.w(TAG, "Cannot speak '$text': TTS not ready")
            return
        }
        if (!isVoiceEnabled && !isTest) {
            return
        }

        try {
            Log.i(TAG, "🗣️ FaceVoiceSpeaker announcement: '$text'")
            lastAnnouncementText = text
            onAnnouncement?.invoke(text)

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "FaceVoice_${SystemClock.uptimeMillis()}")
        } catch (t: Throwable) {
            Log.e(TAG, "Error speaking text '$text': ${t.message}", t)
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping TTS: ${t.message}")
        }
    }

    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
            isTtsReady = false
            Log.i(TAG, "TextToSpeech successfully shut down")
        } catch (t: Throwable) {
            Log.w(TAG, "Error shutting down TTS: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "FaceVoiceSpeaker"
    }
}
