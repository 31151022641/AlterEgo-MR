package com.oculus.camerademo.mirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.oculus.camerademo.model3d.HumanPersona
import com.oculus.camerademo.model3d.PhotoFace
import java.util.concurrent.atomic.AtomicBoolean

/** Camera conversion and retargeting use one serial coordinator. Sample models have
 * independent persistent owners and latest-only inputs; presets retain their single worker. */
class AvatarCameraMirror(private val context: Context,
    private val onFrame: (Bitmap, Long, String, Boolean, WearerSnapshot?, Long, Long) -> Unit,
    private val onError: (String, Boolean) -> Unit,
    private val onSampleObservation: ((WearerSnapshot,Long,String,Long,Long) -> Unit)? = null) {
    private val camera = AvatarCameraSource(context)
    private val painter = AvatarVideoFacePainter()
    private val photoPainter=AvatarPhotoMaskPainter()
    private data class Selection(val persona: HumanPersona?,val sample: Boolean,val epoch: Long,val photo: PhotoFace?=null)
    private data class QueuedFrame(val bitmap: Bitmap,val timestamp: Long,val selection: Selection,val runEpoch: Long,
        val parallelSample: Boolean)
    @Volatile private var selection = Selection(null,false,0L)
    val selectionEpoch get() = selection.epoch
    @Synchronized fun select(persona: HumanPersona?,sample: Boolean,photo: PhotoFace?=null): Long {
        val restart = running && selection.sample != sample
        selection = Selection(persona,sample,selection.epoch+1,photo)
        Log.i(TAG,"Mirror selection: sample=$sample preset=${persona != null} epoch=${selection.epoch}")
        // The sample uses camera pixels only for inference, while personal and
        // preset mirrors retain the requested video detail. Reopen the capture
        // stream when this distinction changes so its source size follows it.
        if (restart) { stop(); start() }
        return selection.epoch
    }
    @Volatile var zoom = 1f
    /** Freeze before start; used only for controlled sample latency comparisons. */
    @Volatile var parallelSample = true
    var budget = AvatarVideoBudget()
    @Volatile var faceScale = 1f
    @Volatile var faceOffsetX = 0f
    @Volatile var faceOffsetY = 0f
    private val power = context.getSystemService(android.os.PowerManager::class.java)
    var frameRate: ((Int,Long) -> Unit)? = null
    var sourceSize: ((Int,Int) -> Unit)? = null
    var outputSize: ((Int,Int) -> Unit)? = null
    private var rateStarted = 0L
    private var rateFrames = 0
    private var tracker: AvatarVideoFaceTracker? = null
    private var trackerFailed = false
    private var trackerRetryAt = 0L
    private val stability = AvatarVideoStability()
    private var hands: AvatarHandOcclusion? = null
    private var handsFailed = false
    private var sampleHands: AvatarVideoHandTracker? = null
    private val sampleTracking = AvatarSampleChannelTracking()
    private val sampleMotion = AvatarVideoMotionDiagnostics()
    private val parallelFaceMotion = AvatarVideoMotionDiagnostics()
    private val parallelHandMotion = AvatarVideoMotionDiagnostics()
    @Volatile private var sampleFaceWorker: AvatarSampleModelWorker<AvatarVideoFaceTracker,AvatarVideoFace?>? = null
    @Volatile private var sampleHandWorker: AvatarSampleModelWorker<AvatarVideoHandTracker,List<AvatarVideoHand>>? = null
    private var parallelFaceUnavailable = false
    private var parallelHandsUnavailable = false
    private var faceMetadataAt = 0L
    private var handMetadataAt = 0L
    private var sampleHandsRetryAt = 0L
    private var workingSelection = -1L
    private var workingRun = -1L
    @Volatile var protectHands = true
    private var detectedFrames = 0L
    private var missedFrames = 0L
    private val inferenceThread = HandlerThread("AlterEgoAvatarInference").apply { start() }
    private val inferenceHandler = Handler(inferenceThread.looper)
    private val faceResults = AvatarLatestOwnedInput<AvatarSampleModelResult<AvatarVideoFace?>> { it.close() }
    private val handResults = AvatarLatestOwnedInput<AvatarSampleModelResult<List<AvatarVideoHand>>> { it.close() }
    private val faceResultPosted = AtomicBoolean(false)
    private val handResultPosted = AtomicBoolean(false)
    private val faceResultDrain = Runnable {
        try { faceResults.take()?.let { acceptSampleFace(it) } }
        finally { faceResultPosted.set(false); if (faceResults.hasPending()) scheduleFaceResult() }
    }
    private val handResultDrain = Runnable {
        try { handResults.take()?.let { acceptSampleHands(it) } }
        finally { handResultPosted.set(false); if (handResults.hasPending()) scheduleHandResult() }
    }
    private val queueLock = Any()
    private var pendingFrame: QueuedFrame? = null
    private var drainScheduled = false
    private var metadataAt = 0L
    private val drainFrame = object : Runnable {
        override fun run() {
            val next = synchronized(queueLock) { pendingFrame.also { pendingFrame = null } }
            try { if (next != null) process(next) }
            finally {
                synchronized(queueLock) {
                    // Repost one frame at a time so lifecycle cleanup cannot be starved by input.
                    if (!closed && pendingFrame != null) {
                        if (!inferenceHandler.post(this)) { clearPendingFrameLocked(); drainScheduled = false }
                    } else drainScheduled = false
                }
            }
        }
    }
    @Volatile private var closed = false
    @Volatile private var generation = 0L
    val runEpoch get() = generation
    @Volatile var lastAcquiredAt = 0L; private set
    private var running = false

    init {
        camera.onSize = { width,height -> sourceSize?.invoke(width,height) }
    }

    @Synchronized fun start() {
        if (closed || running) return
        running = true
        camera.quality = if (selection.sample) AvatarVideoQuality.PERFORMANCE else budget.quality
        // Keep the system avatar stream's previous detail/framing policy while
        // comparing the live regression after selecting native 640 × 360.
        // Models still receive 640 × 360; capture cadence is independent below.
        camera.inferenceOnly = false
        camera.intervalMs = if (selection.sample) AvatarSampleCapturePolicy.intervalMs(power.currentThermalStatus)
            else budget.intervalMs
        val request = ++generation
        val parallel = parallelSample
        lastAcquiredAt = 0L
        Log.i(TAG,"Avatar camera request=$request sample=${selection.sample} inputQuality=${camera.quality} parallelSample=$parallel")
        clearPendingFrame()
        // Source transfers ownership of its independent bitmap. Do not perform inference or
        // wait for the worker here: ImageReader must release its camera image immediately.
        camera.start({ source,timestamp ->
            if (!closed && request == generation) lastAcquiredAt = timestamp
            enqueue(QueuedFrame(source,timestamp,selection,request,parallel))
        },
            { error,recoverable ->
                synchronized(this) {
                    if (!closed && running && generation == request) {
                        running = false; generation++
                        clearPendingFrame()
                        // Keep validation and delivery bound to this run. Source
                        // has released its lock before entering this callback.
                        onError(error,recoverable)
                    }
                }
            })
    }

    private fun enqueue(input: QueuedFrame) {
        synchronized(queueLock) {
            if (closed || input.runEpoch != generation || input.selection.epoch != selection.epoch) {
                input.bitmap.recycle(); return
            }
            clearPendingFrameLocked()
            pendingFrame = input
            if (!drainScheduled) {
                drainScheduled = true
                if (!inferenceHandler.post(drainFrame)) { clearPendingFrameLocked(); drainScheduled = false }
            }
        }
    }

    private fun clearPendingFrameLocked() {
        pendingFrame?.bitmap?.recycle(); pendingFrame = null
    }

    private fun clearPendingFrame() = synchronized(queueLock) { clearPendingFrameLocked() }

    private fun process(input: QueuedFrame) {
        val source = input.bitmap
        val selected = input.selection
        val timestamp = input.timestamp
        var output: Bitmap? = null
        try {
            if (closed || input.runEpoch != generation || selected.epoch != selection.epoch) return
            val workStartedAt = SystemClock.uptimeMillis()
            val inputQueueMs = workStartedAt-timestamp
            if (workingSelection != selected.epoch || workingRun != input.runEpoch) {
                workingSelection = selected.epoch; workingRun = input.runEpoch
                stability.update(null,0); sampleTracking.reset(input.runEpoch,selected.epoch); sampleMotion.reset()
                parallelFaceMotion.reset(); parallelHandMotion.reset()
                parallelFaceUnavailable = false; parallelHandsUnavailable = false
                faceMetadataAt = 0L; handMetadataAt = 0L
                trackerFailed = false; trackerRetryAt = 0L; handsFailed = false; sampleHandsRetryAt = 0L
                rateStarted = 0L; rateFrames = 0; metadataAt = 0L
            }
            // Sample reconstruction uses the wide image. Zooming its observations
            // would change hand positions rather than just framing the output.
            val preprocessStartedAt = SystemClock.uptimeMillis()
            val frame = frame(source,if (selected.sample) 1f else zoom,inferenceOnly=selected.sample)
            output = frame
            source.recycle()
            var preprocessMs = SystemClock.uptimeMillis()-preprocessStartedAt
            if (selected.sample && input.parallelSample) {
                dispatchSample(frame,input,preprocessStartedAt)
                if (!closed && input.runEpoch == generation && selected.epoch == selection.epoch)
                    camera.intervalMs = AvatarSampleCapturePolicy.intervalMs(power.currentThermalStatus)
                return // Both workers own copies; this coordinator recycles its frame in finally.
            }
            var faceMs = 0L
            var handsMs = 0L
            var retargetMs = 0L
            var facePublicationAgeMs = 0L
            var overlay = false
            var snapshot: WearerSnapshot? = null
            var faceCount = 0
            var handCandidates = 0
            var acceptedHands = 0
            val status = if (selected.sample) {
                // Both estimators receive each frame independently. A covered face or a face
                // model retry must not stop hands that are still visible in the OS video.
                val imageStartedAt = SystemClock.uptimeMillis()
                val image = AvatarInferenceImage.create(frame)
                preprocessMs += SystemClock.uptimeMillis()-imageStartedAt
                // The independent owned bitmap remains alive for both synchronous
                // model calls. Neither borrowed-image overload closes or mutates it.
                try {
                    val faceStartedAt = SystemClock.uptimeMillis()
                    val face = estimateSampleFace(image,timestamp)
                    faceMs = SystemClock.uptimeMillis()-faceStartedAt
                    val faceRetargetStartedAt = SystemClock.uptimeMillis()
                    val faceSnapshot = sampleTracking.updateFace(face,timestamp,faceRetargetStartedAt,input.runEpoch,selected.epoch)
                    retargetMs += SystemClock.uptimeMillis()-faceRetargetStartedAt
                    // Publish face/gaze before the hand model blocks this worker.
                    // The UI owns this copy; the original and the independently
                    // owned MPImage remain alive for the second synchronous model.
                    if (faceSnapshot != null && !closed && input.runEpoch == generation && selected.epoch == selection.epoch) {
                        if (onSampleObservation != null) onSampleObservation.invoke(faceSnapshot,faceSnapshot.sampledAtMs,
                            sampleStatus(faceSnapshot),selected.epoch,input.runEpoch)
                        else {
                            var firstOutput: Bitmap? = frame.copy(Bitmap.Config.ARGB_8888,false)
                                ?: error("Could not copy sample observation frame")
                            try {
                                onFrame(firstOutput!!,faceSnapshot.sampledAtMs,sampleStatus(faceSnapshot),false,faceSnapshot,selected.epoch,input.runEpoch)
                                firstOutput = null
                            } finally { firstOutput?.recycle() }
                        }
                        facePublicationAgeMs = SystemClock.uptimeMillis()-timestamp
                    }
                    if (closed || input.runEpoch != generation || selected.epoch != selection.epoch) return
                    val handsStartedAt = SystemClock.uptimeMillis()
                    val observedHands = estimateSampleHands(image,timestamp)
                    handsMs = SystemClock.uptimeMillis()-handsStartedAt
                    val retargetStartedAt = SystemClock.uptimeMillis()
                    val sampleSnapshot = sampleTracking.updateHands(face,observedHands,timestamp,retargetStartedAt,
                        input.runEpoch,selected.epoch) ?: return
                    retargetMs += SystemClock.uptimeMillis()-retargetStartedAt
                    snapshot = sampleSnapshot
                    faceCount = if (sampleSnapshot.hasVideoFace) 1 else 0
                    handCandidates = observedHands.size
                    acceptedHands = sampleSnapshot.hands.count { hand -> hand.any { it != null } }
                    sampleStatus(sampleSnapshot)
                } finally { image.close() }
            } else if (selected.persona == null) "Horizon avatar · Live system camera" else {
                try {
                    if (tracker == null && android.os.SystemClock.uptimeMillis() >= trackerRetryAt) {
                        tracker = AvatarVideoFaceTracker(context,accelerated=true); trackerFailed = false
                    }
                    val face = stability.update(tracker?.estimate(frame, timestamp),timestamp)
                    if (face != null) {
                        faceCount = 1
                        if (protectHands && hands == null && !handsFailed) hands = runCatching { AvatarHandOcclusion(context) }.getOrElse { handsFailed = true; null }
                        val overlap = if (protectHands) runCatching { hands?.overlaps(frame,face,timestamp) ?: false }.getOrElse {
                            handsFailed = true; runCatching { hands?.close() }; hands = null; false
                        } else false
                        if (overlap) "Hand near face · Original Horizon avatar keeps natural overlap" else {
                        val fitted=AvatarFaceFit.adjust(face,faceScale,faceOffsetX,faceOffsetY)
                        if(selected.photo!=null)photoPainter.draw(frame,fitted,selected.photo)
                        else painter.draw(frame,fitted,selected.persona!!)
                        overlay = true; detectedFrames++
                        if (detectedFrames == 1L || detectedFrames % 100L == 0L)
                            Log.i(TAG, "Video face detected; overlay frames=$detectedFrames")
                        (if(selected.photo!=null) "${selected.photo.entry.name} · Photo mask · Horizon body and hands"
                            else "${selected.persona.displayName} · Expressions estimated from avatar video") +
                            if (protectHands && handsFailed) " · Hand overlap check unavailable" else ""
                        }
                    } else {
                        sampleTracking.reset(input.runEpoch,selected.epoch)
                        missedFrames++
                        if (missedFrames == 1L || missedFrames % 100L == 0L)
                            Log.i(TAG, "No usable face in avatar video; showing original avatar")
                        if (trackerFailed) "Face overlay unavailable · Original Horizon avatar shown"
                        else "Face not detected · Original Horizon avatar shown"
                    }
                } catch (error: Exception) {
                    trackerFailed = true
                    trackerRetryAt = android.os.SystemClock.uptimeMillis()+5000L
                    sampleTracking.reset(input.runEpoch,selected.epoch)
                    runCatching { tracker?.close() }; tracker = null
                    Log.w(TAG, "Avatar-video inference unavailable: ${error.message}")
                    "Face overlay unavailable · Original Horizon avatar shown"
                }
            }
            val processedAt = SystemClock.uptimeMillis()
            if (selected.sample) sampleMotion.update(snapshot,processedAt)
            val processingMs = processedAt-workStartedAt
            budget.processing(processingMs); budget.thermalStatus = power.currentThermalStatus
            if (!closed && input.runEpoch == generation && selected.epoch == selection.epoch) {
                camera.intervalMs = if (selected.sample) AvatarSampleCapturePolicy.intervalMs(budget.thermalStatus)
                    else budget.intervalMs
            }
            if (metadataAt == 0L || processedAt-metadataAt >= 5000L) {
                metadataAt = processedAt
                val motion = sampleMotion.takeWindow()
                Log.i(TAG,"Video inference processingMs=$processingMs frameAgeMs=${processedAt-timestamp} sample=${selected.sample} faceCount=$faceCount handCandidates=$handCandidates acceptedHands=$acceptedHands" +
                    " parallel=false" +
                    " inputQueueMs=$inputQueueMs preprocessMs=$preprocessMs faceMs=$faceMs handsMs=$handsMs retargetMs=$retargetMs" +
                    " facePublicationAgeMs=$facePublicationAgeMs" +
                    " faceExpressionChanged=${motion.faceExpressionChanged} gazeChanged=${motion.gazeChanged}" +
                    " leftHandChanged=${motion.leftHandChanged} rightHandChanged=${motion.rightHandChanged}")
            }
            rateFrames++
            if (rateStarted == 0L) rateStarted = processedAt
            if (processedAt-rateStarted >= 1000L) {
                frameRate?.invoke((rateFrames*1000L/(processedAt-rateStarted)).toInt(),camera.intervalMs)
                rateFrames = 0; rateStarted = processedAt
            }
            if (!closed && input.runEpoch == generation && selected.epoch == selection.epoch) {
                if (selected.sample && onSampleObservation != null && snapshot != null)
                    onSampleObservation.invoke(snapshot,snapshot.sampledAtMs,status,selected.epoch,input.runEpoch)
                else {
                    // Ownership passes to the UI callback; stale outputs are recycled in finally.
                    output = null
                    onFrame(frame,timestamp,status,overlay,snapshot,selected.epoch,input.runEpoch)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG,"Avatar inference frame failed: ${error.message}")
            synchronized(this) {
                if (!closed && running && input.runEpoch == generation && selected.epoch == selection.epoch) {
                    // Detach this run before reporting its first processing failure.
                    // Further frames cannot continually postpone the scheduled retry,
                    // and a stale failure cannot be attributed to a replacement run.
                    stop()
                    onError("Avatar preview processing failed. Choose Retry camera.",true)
                }
            }
        } finally {
            if (!source.isRecycled) source.recycle()
            output?.recycle()
        }
    }

    private fun dispatchSample(frame: Bitmap,input: QueuedFrame,preprocessStartedAt: Long) {
        if (!currentSample(input.runEpoch,input.selection.epoch)) return
        val faceWorker = sampleFaceWorker ?: AvatarSampleModelWorker(
            "Face",{ AvatarVideoFaceTracker(context,accelerated = true) },
            { task,image,timestamp -> task.estimate(image,timestamp) },{ null as AvatarVideoFace? },
            onSampleObservation == null,{ currentSample(it.run,it.selection) },
            { result -> if (faceResults.offer(result)) scheduleFaceResult() }
        ).also { sampleFaceWorker = it }
        val handWorker = sampleHandWorker ?: AvatarSampleModelWorker(
            "Hands",{ AvatarVideoHandTracker(context,accelerated = true) },
            { task,image,timestamp -> task.estimate(image,timestamp) },{ emptyList<AvatarVideoHand>() },
            onSampleObservation == null,{ currentSample(it.run,it.selection) },
            { result -> if (handResults.offer(result)) scheduleHandResult() }
        ).also { sampleHandWorker = it }
        var facePixels: Bitmap? = null
        var handPixels: Bitmap? = null
        try {
            facePixels = frame.copy(Bitmap.Config.ARGB_8888,false) ?: error("Could not copy face input")
            handPixels = frame.copy(Bitmap.Config.ARGB_8888,false) ?: error("Could not copy hand input")
            if (!currentSample(input.runEpoch,input.selection.epoch)) return
            val preprocessing = SystemClock.uptimeMillis()-preprocessStartedAt
            faceWorker.offer(AvatarSampleModelInput(facePixels!!,input.timestamp,input.runEpoch,input.selection.epoch,preprocessing))
            facePixels = null
            handWorker.offer(AvatarSampleModelInput(handPixels!!,input.timestamp,input.runEpoch,input.selection.epoch,preprocessing))
            handPixels = null
        } finally {
            facePixels?.let { if (!it.isRecycled) it.recycle() }
            handPixels?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun currentSample(run: Long,epoch: Long) = !closed && run == generation &&
        epoch == selection.epoch && selection.sample

    private fun scheduleFaceResult() {
        if (faceResultPosted.compareAndSet(false,true) && !inferenceHandler.post(faceResultDrain)) {
            faceResults.clear(); faceResultPosted.set(false)
        }
    }

    private fun scheduleHandResult() {
        if (handResultPosted.compareAndSet(false,true) && !inferenceHandler.post(handResultDrain)) {
            handResults.clear(); handResultPosted.set(false)
        }
    }

    private fun acceptSampleFace(result: AvatarSampleModelResult<AvatarVideoFace?>) {
        try {
            if (!currentSample(result.run,result.selection) || workingRun != result.run || workingSelection != result.selection) return
            val startedAt = SystemClock.uptimeMillis()
            parallelFaceUnavailable = result.unavailable
            val face = stability.update(result.value,result.capturedAt)
            val snapshot = sampleTracking.updateFace(face,result.capturedAt,startedAt,result.run,result.selection) ?: return
            val retargetMs = SystemClock.uptimeMillis()-startedAt
            // Compare only this face channel at its real capture time. Retained
            // hands and a monotonic composite header cannot become new motion.
            val facePacket = snapshot.nativePacket!!.copyOf().apply { this[1] = 0f; this[3] = 0f; this[4] = 0f }
            parallelFaceMotion.update(snapshot.copy(sampledAtMs = result.capturedAt,nativePacket = facePacket),startedAt)
            publishSampleResult(snapshot,result,true,if (face != null) 1 else 0,retargetMs)
        } catch (error: Exception) { sampleCoordinatorFailed(result.run,result.selection,error) }
        finally { result.close() }
    }

    private fun acceptSampleHands(result: AvatarSampleModelResult<List<AvatarVideoHand>>) {
        try {
            if (!currentSample(result.run,result.selection) || workingRun != result.run || workingSelection != result.selection) return
            val startedAt = SystemClock.uptimeMillis()
            parallelHandsUnavailable = result.unavailable
            val snapshot = sampleTracking.updateHands(result.value,result.capturedAt,startedAt,result.run,result.selection) ?: return
            val retargetMs = SystemClock.uptimeMillis()-startedAt
            parallelHandMotion.update(snapshot.copy(sampledAtMs = result.capturedAt,hasVideoFace = false,hasEyes = false),startedAt)
            publishSampleResult(snapshot,result,false,result.value.size,retargetMs)
        } catch (error: Exception) { sampleCoordinatorFailed(result.run,result.selection,error) }
        finally { result.close() }
    }

    private fun <R> publishSampleResult(snapshot: WearerSnapshot,result: AvatarSampleModelResult<R>,
        face: Boolean,candidates: Int,retargetMs: Long) {
        if (!currentSample(result.run,result.selection)) return
        val status = sampleStatus(snapshot,parallel = true)
        if (onSampleObservation != null) onSampleObservation.invoke(snapshot,snapshot.sampledAtMs,status,result.selection,result.run)
        else {
            // Compatibility pixels are observations only; the selected sample
            // is still rendered from controls. Each result owns its own bitmap.
            var pixels: Bitmap? = result.takePreview() ?: error("Sample preview missing")
            try {
                onFrame(pixels!!,snapshot.sampledAtMs,status,false,snapshot,result.selection,result.run)
                pixels = null
            } finally { pixels?.let { if (!it.isRecycled) it.recycle() } }
        }
        val publishedAt = SystemClock.uptimeMillis()
        if (!currentSample(result.run,result.selection)) return
        budget.processing(result.modelMs+result.preprocessMs+retargetMs)
        budget.thermalStatus = power.currentThermalStatus
        camera.intervalMs = AvatarSampleCapturePolicy.intervalMs(budget.thermalStatus)
        rateFrames++
        if (rateStarted == 0L) rateStarted = publishedAt
        if (publishedAt-rateStarted >= 1000L) {
            frameRate?.invoke((rateFrames*1000L/(publishedAt-rateStarted)).toInt(),camera.intervalMs)
            rateFrames = 0; rateStarted = publishedAt
        }
        val previousMetadata = if (face) faceMetadataAt else handMetadataAt
        if (previousMetadata == 0L || publishedAt-previousMetadata >= 5000L) {
            if (face) faceMetadataAt = publishedAt else handMetadataAt = publishedAt
            val motion = if (face) parallelFaceMotion.takeWindow() else parallelHandMotion.takeWindow()
            val acceptedHands = snapshot.hands.count { hand -> hand.any { it != null } }
            val age = publishedAt-result.capturedAt
            Log.i(TAG,"Video inference processingMs=${result.modelMs+retargetMs} frameAgeMs=$age sample=true parallel=true channel=${if (face) "face" else "hands"}" +
                " faceCount=${if (snapshot.hasVideoFace) 1 else 0} handCandidates=${if (face) 0 else candidates} acceptedHands=$acceptedHands" +
                " inputQueueMs=${result.inputQueueMs} preprocessMs=${result.preprocessMs} modelMs=${result.modelMs} retargetMs=$retargetMs" +
                " publicationAgeMs=$age channelCandidates=$candidates modelUnavailable=${result.unavailable}" +
                " faceExpressionChanged=${motion.faceExpressionChanged} gazeChanged=${motion.gazeChanged}" +
                " leftHandChanged=${motion.leftHandChanged} rightHandChanged=${motion.rightHandChanged}")
        }
    }

    private fun sampleCoordinatorFailed(run: Long,epoch: Long,error: Exception) {
        Log.w(TAG,"Sample observation failed: ${error.javaClass.simpleName}")
        synchronized(this) {
            if (currentSample(run,epoch)) {
                stop(); onError("Avatar preview processing failed. Choose Retry camera.",true)
            }
        }
    }

    private fun sampleStatus(snapshot: WearerSnapshot,parallel: Boolean = false) = "Meta sample · ${snapshot.message}" +
        (if (if (parallel) parallelFaceUnavailable else trackerFailed) " · Face estimates temporarily unavailable" else "") +
        (if (if (parallel) parallelHandsUnavailable else sampleHands == null) " · Hand estimates temporarily unavailable" else "")

    private fun estimateSampleFace(image: MPImage,timestamp: Long): AvatarVideoFace? {
        val now = android.os.SystemClock.uptimeMillis()
        return try {
            if (tracker == null && now >= trackerRetryAt) {
                tracker = AvatarVideoFaceTracker(context,accelerated=true); trackerFailed = false
            }
            stability.update(tracker?.estimate(image,timestamp),timestamp)
        } catch (error: Exception) {
            trackerFailed = true; trackerRetryAt = now+5000L
            runCatching { tracker?.close() }; tracker = null
            stability.update(null,timestamp)
            Log.w(TAG,"Sample face estimates unavailable; hands remain independent: ${error.message}")
            null
        }
    }

    private fun estimateSampleHands(image: MPImage,timestamp: Long): List<AvatarVideoHand> {
        val now = android.os.SystemClock.uptimeMillis()
        return runCatching {
            if (sampleHands == null && now >= sampleHandsRetryAt) sampleHands = AvatarVideoHandTracker(context,accelerated=true)
            sampleHands?.estimate(image,timestamp).orEmpty()
        }.getOrElse { error ->
            runCatching { sampleHands?.close() }; sampleHands = null
            sampleHandsRetryAt = now+5000L
            Log.w(TAG,"Sample hand estimates unavailable; face remains independent: ${error.message}")
            emptyList()
        }
    }

    private fun frame(source: Bitmap,framing: Float,inferenceOnly: Boolean = false): Bitmap {
        val size = if (inferenceOnly) {
            // This bitmap is discarded after sample observations are queued.
            // Rendered sample/webcam detail is controlled separately by the SDK.
            val blocks = minOf(source.width/16,source.height/9,640/16).coerceAtLeast(2)
            AvatarVideoSize(blocks*16,blocks*9)
        } else AvatarVideoResolution.output(source.width,source.height,budget.quality)
        val bitmap = Bitmap.createBitmap(size.width,size.height,Bitmap.Config.ARGB_8888)
        return try {
            outputSize?.invoke(size.width,size.height)
            val crop = AvatarFrameFraming.crop(source.width,source.height,framing)
            Canvas(bitmap).drawBitmap(source,Rect(crop.left.toInt(),crop.top.toInt(),
                (crop.left+crop.width).toInt(),(crop.top+crop.height).toInt()),
                RectF(0f,0f,size.width.toFloat(),size.height.toFloat()),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            bitmap
        } catch (error: Exception) { bitmap.recycle(); throw error }
    }

    @Synchronized fun stop() {
        running = false; generation++
        clearPendingFrame()
        sampleFaceWorker?.clearPending(); sampleHandWorker?.clearPending()
        faceResults.clear(); handResults.clear()
        camera.stop()
    }
    @Synchronized fun close() {
        if (closed) return
        closed = true
        stop()
        faceResults.close(); handResults.close()
        // GPU tasks must close on their creation/inference thread. Do not join or wait on the
        // main thread: an active frame discards its result, then cleanup runs on this worker.
        inferenceHandler.post {
            try {
                sampleFaceWorker?.close(); sampleFaceWorker = null
                sampleHandWorker?.close(); sampleHandWorker = null
                runCatching { tracker?.close() }; tracker = null
                runCatching { hands?.close() }; hands = null
                runCatching { sampleHands?.close() }; sampleHands = null
                sampleTracking.reset(generation,selection.epoch); sampleMotion.reset(); stability.update(null,0)
            } finally { inferenceThread.quitSafely() }
        }
    }
    companion object { private const val TAG = "AlterEgoAvatarVideo" }
}
