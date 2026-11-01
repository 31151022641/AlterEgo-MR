package com.oculus.camerademo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.activity.OnBackPressedCallback
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.oculus.camerademo.mirror.*
import com.oculus.camerademo.model3d.HumanPersona
import com.oculus.camerademo.model3d.AuthoredPortraitStore
import com.oculus.camerademo.model3d.PhotoFace
import com.oculus.camerademo.model3d.PhotoFaceEntry
import com.oculus.camerademo.model3d.PhotoFaceImport
import com.oculus.camerademo.ui.QuickMenuNavigation
import com.oculus.camerademo.ui.MenuPressGate
import com.oculus.camerademo.ui.QuickMenuPage
import com.oculus.camerademo.model3d.PhotoFaceLibrary
import com.oculus.camerademo.model3d.PhotoFaceDiagnostic
import com.oculus.camerademo.ui.theme.CameraDemoTheme
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** An ordinary Horizon OS window, driven by its system avatar camera. */
class MirrorActivity : ComponentActivity() {
    private var launcher by mutableStateOf(QuickMenuNavigation())
    private val menuPress=MenuPressGate()
    private val backPress=MenuPressGate()
    private val photoFaces by lazy {PhotoFaceImport(this)}
    private var selectedPhoto by mutableStateOf<PhotoFace?>(null)
    private var layoutQa=false
    private var layoutQaPhoto:PhotoFaceImport?=null
    private val menuBack=object: OnBackPressedCallback(false) {
        override fun handleOnBackPressed(){navigateLauncher(launcher.back())}
    }
    private fun navigateLauncher(next: QuickMenuNavigation){launcher=next;menuBack.isEnabled=next.open}
    private fun clearPhotoSelection(){photoFaces.cancelSelection();selectedPhoto=null
        getSharedPreferences("mirror",MODE_PRIVATE).edit().remove("photo_face").apply()}
    private fun selectPhoto(entry: PhotoFaceEntry){photoFaces.select(entry){photo ->
        selectedPhoto=photo;selectedPersona=HumanPersona.DIGITAL_TWIN;usePreset=true;useSample=false
        getSharedPreferences("mirror",MODE_PRIVATE).edit().putString("photo_face",entry.id)
            .putString("persona",selectedPersona.name).putBoolean("camera_preset",true).putBoolean("camera_sample",false).apply()
        applySelection()
    }}
    private var diagnosticAvatar: HorizonAccount? = null
    private var sampleAvatar: HorizonAccount? = null
    private var camera: AvatarCameraMirror? = null
    private lateinit var sender: AvatarLanSender
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    @Volatile private var resumed = false
    @Volatile private var lastFrame = 0L
    private var cameraStartedAt = 0L
    private var cameraRetryAt = 0L
    private var retryDelay = AvatarStreamTiming.CAMERA_RETRY_MS
    private var recoverAutomatically = true
    private val pairingExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var easyPairing: AvatarEasyPairing? = null
    private var pairingGeneration = 0L
    private val lastSlowFrameLogAt = AtomicLong(0L)
    private data class Preview(val bitmap: Bitmap, val timestamp: Long, val processedAt: Long, val status: String,
        val overlay: Boolean, val generation: Long, val selection: Long, val cameraRun: Long,
        val sample: Boolean, val tracking: WearerSnapshot?)
    private val pendingPreview = AtomicReference<Preview?>()
    private val previewPosted = AtomicBoolean(false)
    private data class SampleInput(val tracking: WearerSnapshot?,val status: String,val timestamp: Long,val generation: Long,
        val selection: Long,val cameraRun: Long)
    private val pendingSampleInput = AtomicReference<SampleInput?>()
    private val sampleInputPosted = AtomicBoolean(false)
    // Main-thread state. The sample renderer never receives the camera bitmap.
    private var sampleInput: SampleInput? = null
    private var sampleCameraIssue: String? = null
    private var sampleVideoCleared = false
    private var sampleRenderedFrames = 0L
    private var sampleRateStarted = 0L
    private var sampleRateFrames = 0
    private val sampleWireSchedule = AvatarSampleWireSchedule()
    private val sampleRenderSchedule = AvatarSampleRenderSchedule()
    private var sampleNextTickAt = 0L
    private var publicSampleTestEnabled = false
    private var publicSampleTestRequestedAt = 0L
    private var publicSampleTestStartedAt = 0L
    private val publicSampleTestCaptures = mutableSetOf<String>()
    private val publicSampleTestStatus = "Automatic public sample test · Not wearer tracking"
    private val sampleLoop = object : Runnable {
        override fun run() {
            if (!resumed || !useSample || camera == null) return
            val now = SystemClock.uptimeMillis()
            val source = sampleInput?.takeIf { it.generation == generation && it.selection == camera?.selectionEpoch &&
                it.cameraRun == camera?.runEpoch && it.timestamp >= cameraStartedAt }
            val acquiredAt = camera?.lastAcquiredAt ?: 0L
            val cameraFresh = acquiredAt > 0L && now >= acquiredAt && now-acquiredAt <= 1000L
            val currentInput = source != null && AvatarStreamProtocol.fresh(source.timestamp,now)
            var syntheticInput: WearerSnapshot? = null
            var syntheticElapsed = 0L
            var syntheticActive = false
            var syntheticFaceOnly = false
            if (publicSampleTestEnabled) {
                if (publicSampleTestStartedAt == 0L && now-publicSampleTestRequestedAt >= 60_000L) {
                    publicSampleTestEnabled = false; sampleRenderSchedule.reset(); MirrorState.frame = null
                    Log.w("AlterEgoCameraLoadQA","ABORTED: camera/inference/public SDK setup did not become ready in 60s; cameraFresh=$cameraFresh currentInput=$currentInput")
                } else if (publicSampleTestStartedAt == 0L && cameraFresh && currentInput &&
                    sampleAvatar?.publicSampleTestInput(false,now) != null) {
                    publicSampleTestStartedAt = now
                    sampleRenderSchedule.reset(); MirrorState.frame = null; clearSampleVideo()
                    Log.i("AlterEgoCameraLoadQA","STARTED: public SDK inputs only; cameraFresh=$cameraFresh currentInput=$currentInput; cameraPixelsExported=0")
                }
                if (publicSampleTestEnabled && publicSampleTestStartedAt > 0L) {
                    syntheticElapsed = now-publicSampleTestStartedAt
                    if (syntheticElapsed >= 24_000L) {
                        publicSampleTestEnabled = false; sampleRenderSchedule.reset(); MirrorState.frame = null
                        clearSampleVideo()
                        val expectedCaptures = if (intent.getBooleanExtra("avatar_camera_sample_face_only",false)) 4 else 3
                        Log.i("AlterEgoCameraLoadQA","COMPLETED: publicCaptures=${publicSampleTestCaptures.size}/$expectedCaptures; cameraFresh=$cameraFresh currentInput=$currentInput; normal camera estimates resumed")
                    } else {
                        syntheticActive = syntheticElapsed/2000L % 2L == 1L
                        syntheticFaceOnly = intent.getBooleanExtra("avatar_camera_sample_face_only",false) &&
                            syntheticElapsed in 16_000L until 20_000L
                        syntheticInput = sampleAvatar?.publicSampleTestInput(syntheticActive,now,syntheticFaceOnly)
                    }
                }
            }
            // During this developer test, wearer observations only impose processing
            // load. Every SDK input/output eligible for export is explicitly synthetic.
            val automaticTest = publicSampleTestEnabled
            val input = if (automaticTest) syntheticInput ?: AvatarSamplePresentation.neutral(now)
                else AvatarSamplePresentation.input(source?.tracking,source?.timestamp ?: 0L,now)
            val observed = input.hasVideoFace || input.hasEyes || input.hands.any { hand -> hand.any { it != null } }
            val renderDetail = if (automaticTest && publicSampleTestStartedAt > 0L &&
                intent.getBooleanExtra("avatar_camera_sample_profiles",false)) when {
                    syntheticElapsed < 10_000L -> AvatarSampleDetail.FAST
                    syntheticElapsed < 14_000L -> AvatarSampleDetail.BALANCED
                    else -> AvatarSampleDetail.MAXIMUM
                } else MirrorState.sampleDetail
            val refreshInterval = AvatarSampleCadence.intervalMs(renderDetail,
                getSystemService(android.os.PowerManager::class.java).currentThermalStatus)
            sampleRenderSchedule.activeRefreshMs = if (automaticTest ||
                intent.getBooleanExtra("avatar_camera_sample_animation_baseline",false)) 0L else refreshInterval
            val needsDraw = sampleRenderSchedule.needsRender(input,now)
            // Fresh held controls advance SDK easing between observations. The
            // profile and thermal budget bound rendering without renewing input.
            val frame = if (needsDraw) sampleAvatar?.draw(input,videoInput = true,detail = renderDetail) else MirrorState.frame
            val finishedAt = SystemClock.uptimeMillis()
            if (frame != null) {
                if (needsDraw) sender.budget.processing(finishedAt-now)
                if (observed && !AvatarSampleFramePolicy.fresh(input.sampledAtMs,finishedAt)) {
                    if (needsDraw) frame.recycle()
                    sampleRenderSchedule.invalidate()
                    MirrorState.tracking = WearerSnapshot(); MirrorState.active = false
                    MirrorState.cameraStatus = "Meta sample · Waiting for fresh tracking"
                    clearSampleVideo()
                } else {
                    // A gap changes tracking status, never the selected avatar identity.
                    MirrorState.frame = frame; MirrorState.cameraSample = true; MirrorState.cameraOverlay = false
                    MirrorState.horizonRendered = true; MirrorState.tracking = input
                    if (needsDraw) {
                        sampleRenderSchedule.rendered(input,finishedAt,now); sampleRenderedFrames++; sampleRateFrames++
                    }
                    if (automaticTest && syntheticInput != null && needsDraw &&
                        AvatarSampleFramePolicy.fresh(input.sampledAtMs,finishedAt)) {
                        val name = when {
                            !syntheticActive && syntheticElapsed in 9500L until 10_000L -> "camera-load-public-neutral.png"
                            syntheticActive && syntheticElapsed in 11_500L until 12_000L -> "camera-load-public-active.png"
                            syntheticActive && syntheticElapsed in 15_500L until 16_000L -> "camera-load-public-recovered.png"
                            syntheticActive && syntheticFaceOnly && syntheticElapsed in 19_500L until 20_000L -> "camera-load-public-face-only.png"
                            else -> null
                        }
                        if (name != null && !publicSampleTestCaptures.contains(name)) {
                            runCatching {
                                File(getExternalFilesDir(null) ?: cacheDir,name).outputStream().use {
                                    check(frame.compress(Bitmap.CompressFormat.PNG,100,it))
                                }
                            }.onSuccess {
                                publicSampleTestCaptures += name
                                Log.i("AlterEgoCameraLoadQA","Captured $name: syntheticActive=$syntheticActive faceOnly=$syntheticFaceOnly cameraFresh=$cameraFresh currentInput=$currentInput detail=${renderDetail.name}; cameraPixelsExported=0")
                            }.onFailure { Log.w("AlterEgoCameraLoadQA","Public SDK export failed: ${it.javaClass.simpleName}") }
                        }
                    }
                    val sourceFresh = source != null && AvatarSampleFramePolicy.fresh(source.timestamp,finishedAt)
                    MirrorState.active = sourceFresh
                    val currentTrackingStatus = if (input.videoChannels != null && source != null) {
                        val issues = source.status.split(" · ").filter {
                            it == "Face estimates temporarily unavailable" || it == "Hand estimates temporarily unavailable"
                        }
                        (listOf("Meta sample",input.message)+issues).joinToString(" · ")
                    } else source?.status
                    MirrorState.cameraStatus = when {
                        sampleCameraIssue != null -> "Meta sample · $sampleCameraIssue"
                        !sourceFresh -> "Meta sample · Tracking paused · Waiting for camera"
                        !observed -> if (source != null && AvatarSampleFramePolicy.fresh(source.timestamp,finishedAt)) currentTrackingStatus!!
                            else "Meta sample · Tracking unavailable · Waiting for fresh observations"
                        else -> currentTrackingStatus!!
                    }
                    MirrorState.outputResolution = "${frame.width} × ${frame.height}"
                    // Network frames retain the stricter 750 ms transport budget;
                    // the local estimate window must not extend LAN freshness.
                    // An early face update may retain an older, still-valid hand.
                    // The image clock uses the oldest active channel, never the
                    // newest publication time. Neutral output uses its actual
                    // camera acquisition rather than the neutral draw's clock.
                    val wireCapture = if (observed) input.sampledAtMs else source?.timestamp ?: 0L
                    // SDK easing can produce a new picture from unchanged input.
                    // Order those successful renders without refreshing capture.
                    val wireRevision = sampleRenderedFrames
                    val wireMask = AvatarSamplePresentation.activeMask(input)
                    val networkFresh = source != null && AvatarStreamProtocol.fresh(wireCapture,finishedAt)
                    if (needsDraw && networkFresh &&
                        sampleWireSchedule.accepts(wireCapture,wireRevision,wireMask,finishedAt)) {
                        sender.offer(frame,wireCapture); sampleVideoCleared = false
                        sampleWireSchedule.offered(wireCapture,wireRevision,wireMask)
                    } else if (!networkFresh) clearSampleVideo()
                    if (needsDraw && (sampleRenderedFrames == 1L || sampleRenderedFrames % 60L == 0L)) {
                        val clocks = input.videoChannels
                        val faceAge = if (input.hasVideoFace && clocks != null) finishedAt-clocks.faceCapturedAtMs else -1L
                        val leftAge = if ((wireMask and 4) != 0 && clocks != null) finishedAt-clocks.leftHandCapturedAtMs else -1L
                        val rightAge = if ((wireMask and 8) != 0 && clocks != null) finishedAt-clocks.rightHandCapturedAtMs else -1L
                        Log.i("AlterEgoSample2D","Sample frames=$sampleRenderedFrames; observed=$observed; face=${input.hasVideoFace}; eyes=${input.hasEyes}; hands=${input.hands.map { hand -> hand.any { it != null } }}; cameraFresh=$sourceFresh; sdkDrawMs=${finishedAt-now}; trackingAgeMs=${if (observed) finishedAt-input.sampledAtMs else 0L}; faceAgeMs=$faceAge leftHandAgeMs=$leftAge rightHandAgeMs=$rightAge revision=${clocks?.revision ?: 0L} renderRevision=$wireRevision; detail=${renderDetail.name}; cameraPixelsPublished=0")
                    }
                }
            } else {
                MirrorState.active = false; MirrorState.tracking = WearerSnapshot()
                MirrorState.cameraStatus = "${MirrorState.horizonStatus} · Meta sample remains selected"
                clearSampleVideo()
            }
            if (sampleRateStarted == 0L) sampleRateStarted = finishedAt
            if (finishedAt-sampleRateStarted >= 1000L) {
                MirrorState.sampleFps = (sampleRateFrames*1000L/(finishedAt-sampleRateStarted)).toInt()
                sampleRateStarted = finishedAt; sampleRateFrames = 0
            }
            if (automaticTest) MirrorState.cameraStatus = publicSampleTestStatus +
                if (publicSampleTestStartedAt == 0L) " · Waiting for camera and avatar" else ""
            val delay = if (frame == null || source == null && !automaticTest) 250L
                else AvatarSampleCadence.delayMs(now,SystemClock.uptimeMillis(),refreshInterval)
            sampleNextTickAt = SystemClock.uptimeMillis()+delay
            handler.postDelayed(this,delay)
        }
    }
    private fun clearSampleVideo() {
        if (!sampleVideoCleared) { sender.clearPreview(); sampleVideoCleared = true }
    }
    private fun resetSampleInput() {
        pendingSampleInput.set(null); sampleNextTickAt = 0L
        sampleInput = null; sampleCameraIssue = null; sampleVideoCleared = false
        sampleRenderSchedule.reset(); sampleWireSchedule.reset()
        sampleRateStarted = 0L; sampleRateFrames = 0; MirrorState.sampleFps = 0
        MirrorState.tracking = WearerSnapshot()
    }
    private fun sampleInputValid(input: SampleInput, now: Long): Boolean =
        resumed && useSample && input.generation == generation && input.selection == camera?.selectionEpoch &&
            input.cameraRun == camera?.runEpoch && input.timestamp >= cameraStartedAt &&
            AvatarSampleFramePolicy.fresh(input.timestamp,now)

    private fun requestSampleTick(immediate: Boolean = false) {
        handler.removeCallbacks(sampleLoop)
        if (immediate) sampleNextTickAt = 0L
        handler.postDelayed(sampleLoop,(sampleNextTickAt-SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    private val presentSampleInput = object : Runnable {
        override fun run() {
            val input = pendingSampleInput.getAndSet(null)
            if (input != null && sampleInputValid(input,SystemClock.uptimeMillis())) {
                val wakeNow = MirrorState.frame == null || sampleInput?.let {
                    sampleInputValid(it,SystemClock.uptimeMillis())
                } != true
                sampleInput = input; sampleCameraIssue = null
                lastFrame = maxOf(lastFrame,input.timestamp)
                retryDelay = AvatarStreamTiming.CAMERA_RETRY_MS
                requestSampleTick(wakeNow)
            }
            sampleInputPosted.set(false)
            if (pendingSampleInput.get() != null && sampleInputPosted.compareAndSet(false,true)) handler.post(this)
        }
    }
    private fun pauseCameraPreview(message: String) {
        MirrorState.active = false; MirrorState.tracking = WearerSnapshot()
        if (useSample) {
            sampleInput = null; sampleCameraIssue = message
            // Keep the SDK sample visible; its next tick renders neutral controls.
            MirrorState.cameraSample = true
        } else {
            MirrorState.frame = null; MirrorState.cameraOverlay = false; MirrorState.cameraSample = false
        }
        MirrorState.cameraStatus = if (useSample) "Meta sample · $message" else message
        sender.clearPreview(); sampleVideoCleared = true
    }
    private val presentPreview = object : Runnable {
        override fun run() {
            val preview = pendingPreview.getAndSet(null)
            if (preview != null) {
                val now = SystemClock.uptimeMillis()
                logSlowFrame("uiQueueMs",now-preview.processedAt,now)
                if (preview.generation == generation && preview.selection == camera?.selectionEpoch &&
                    preview.cameraRun == camera?.runEpoch && resumed &&
                    preview.timestamp >= cameraStartedAt &&
                    AvatarSampleFramePolicy.previewFresh(preview.sample,preview.timestamp,now)) {
                    if (preview.sample) {
                        val wakeNow = MirrorState.frame == null || sampleInput?.let { sampleInputValid(it,now) } != true
                        sampleInput = SampleInput(preview.tracking,preview.status,preview.timestamp,preview.generation,preview.selection,preview.cameraRun)
                        sampleCameraIssue = null
                        preview.bitmap.recycle()
                        // A completed estimate should not wait for the next timer.
                        requestSampleTick(wakeNow)
                    } else {
                        sender.offer(preview.bitmap,preview.timestamp)
                        MirrorState.frame = preview.bitmap; MirrorState.active = true
                        MirrorState.cameraStatus = preview.status; MirrorState.cameraOverlay = preview.overlay
                        MirrorState.cameraSample = false
                        MirrorState.horizonRendered = !preview.overlay
                        MirrorState.outputResolution = "${preview.bitmap.width} × ${preview.bitmap.height}"
                    }
                    retryDelay = AvatarStreamTiming.CAMERA_RETRY_MS
                } else preview.bitmap.recycle()
            }
            finishPreview()
        }
        private fun finishPreview() {
            previewPosted.set(false)
            if (pendingPreview.get() != null && previewPosted.compareAndSet(false,true)) handler.post(this)
        }
    }
    private var selectedPersona by mutableStateOf(HumanPersona.DIGITAL_TWIN)
    private var usePreset = false
    @Volatile private var useSample = false
    private var diagnosticSaved = false
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && resumed) startCamera() else if (camera != null) {
            MirrorState.cameraStatus = "Allow avatar camera access, then choose Retry camera."
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!resumed || camera == null) return
            val now = SystemClock.uptimeMillis()
            if (MirrorState.active && AvatarStreamTiming.imageExpired(lastFrame,now)) {
                pauseCameraPreview("Avatar camera paused · Recovering while webcam stays paired")
                Log.w("AlterEgoWebcam","Camera image gap; pixels cleared, pairing retained")
            }
            // Model warmup is not camera loss. Acquisition stays alive even
            // when its first inference is too old to publish safely.
            val lastEvent = maxOf(lastFrame,camera?.lastAcquiredAt ?: 0L,cameraStartedAt)
            if (recoverAutomatically && ((cameraRetryAt > 0 && now >= cameraRetryAt) ||
                    (cameraRetryAt == 0L && AvatarStreamTiming.restartDue(lastEvent,now)))) {
                Log.i("AlterEgoWebcam","Restarting foreground avatar camera after gap")
                restartCamera()
            }
            handler.postDelayed(this,250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val portraitAssets = applicationContext.assets
        AuthoredPortraitStore.installLoader { path -> portraitAssets.open(path) }
        onBackPressedDispatcher.addCallback(this,menuBack)
        if(intent.getBooleanExtra("quick_launcher_qa",false)){startLayoutQa();return}
        if (intent.getBooleanExtra("avatar_quality_diagnostic",false)) {
            pairingExecutor.execute { runCatching { AvatarPixelDiagnostics.run() }
                .onFailure { Log.e("AlterEgoVideoQA","Synthetic pixel test failed",it) } }
            setContent { CameraDemoTheme { MirrorScreen(HumanPersona.DIGITAL_TWIN,{}, {}) } }
            return
        }
        if (intent.getBooleanExtra("avatar_preset_diagnostic",false)) {
            pairingExecutor.execute {
                HumanPersona.mirrorAvailable.forEach { persona ->
                    val bitmap = AvatarPresetPreview.render(persona,0f)
                    val output = File(getExternalFilesDir(null),"preset-${persona.name}.png")
                    output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
                    bitmap.recycle()
                }
                Log.i("AlterEgoPresetQA","Five synthetic preset previews exported; no camera or wearer sampled")
            }
            setContent { CameraDemoTheme { MirrorScreen(HumanPersona.DIGITAL_TWIN,{}, {}) } }
            return
        }
        if (intent.getBooleanExtra("avatar_render_diagnostic",false)) {
            diagnosticAvatar = HorizonAccount(this).also { it.showSample() }
            setContent { CameraDemoTheme { MirrorScreen(HumanPersona.DIGITAL_TWIN,{}, {}) } }
            return
        }
        val prefs = getSharedPreferences("mirror",MODE_PRIVATE)
        selectedPersona = HumanPersona.restoreMirror(prefs.getString("persona",""))
        usePreset = prefs.getBoolean("camera_preset",false)
        useSample = prefs.getBoolean("camera_sample",false) && !usePreset
        // Explicit debug selection still opens this ordinary 2D activity.
        if (intent.getBooleanExtra("avatar_camera_sample",false)) { useSample = true; usePreset = false }
        if (intent.getBooleanExtra("avatar_camera_horizon",false)) { useSample = false; usePreset = false }
        intent.getStringExtra("avatar_camera_preset")?.let { value ->
            selectedPersona = HumanPersona.restoreMirror(value); usePreset = true; useSample = false
        }
        if (intent.getBooleanExtra("avatar_camera_sample_synthetic",false)) {
            useSample = true; usePreset = false; publicSampleTestEnabled = true
            publicSampleTestRequestedAt = SystemClock.uptimeMillis()
            for (name in listOf("camera-load-public-neutral.png","camera-load-public-active.png","camera-load-public-recovered.png"))
                File(getExternalFilesDir(null) ?: cacheDir,name).delete()
            Log.i("AlterEgoCameraLoadQA","REQUESTED: camera + inference remain active; public SDK exports only")
        }
        AvatarPairingStore(this).restore()
        sender = AvatarLanSender(this) { message, sharing ->
            val statusGeneration = sender.statusGeneration
            handler.post {
            if (camera != null && statusGeneration == sender.statusGeneration) {
                MirrorState.connection = message
                MirrorState.sharing = sharing
            }
        } }
        camera = AvatarCameraMirror(this, { bitmap,timestamp,status,overlay,tracking,selection,cameraRun ->
            val request = generation
            if (!resumed || selection != camera?.selectionEpoch || cameraRun != camera?.runEpoch ||
                timestamp < cameraStartedAt) { bitmap.recycle(); return@AvatarCameraMirror }
            val now = SystemClock.uptimeMillis()
            val age = now-timestamp
            logSlowFrame("acquisitionToProcessingMs",age,now)
            val sample = useSample
            if (!AvatarSampleFramePolicy.previewFresh(sample,timestamp,now)) { bitmap.recycle(); return@AvatarCameraMirror }
            lastFrame = timestamp
            if (intent.getBooleanExtra("avatar_camera_diagnostic",false) &&
                !intent.getBooleanExtra("avatar_camera_sample_synthetic",false) && !diagnosticSaved) {
                val file = File(getExternalFilesDir(null),"avatar-camera-check.png")
                runCatching { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
                diagnosticSaved = true
            }
            pendingPreview.getAndSet(Preview(bitmap,timestamp,now,status,overlay,request,selection,cameraRun,sample,tracking))?.bitmap?.recycle()
            if (previewPosted.compareAndSet(false,true)) handler.post(presentPreview)
        }, { error, recoverable ->
            val failedGeneration = generation
            val failedCameraRun = camera?.runEpoch
            handler.post {
            if (resumed && failedGeneration == generation && failedCameraRun == camera?.runEpoch) {
                pauseCameraPreview(error)
                pendingPreview.getAndSet(null)?.bitmap?.recycle()
                recoverAutomatically = recoverable
                cameraRetryAt = if (recoverable) SystemClock.uptimeMillis()+retryDelay else 0L
                retryDelay = (retryDelay*2).coerceAtMost(15000L)
                Log.w("AlterEgoWebcam","Camera stream error; autoRetry=$recoverable, pairing retained")
            }
        } }, onSampleObservation = { tracking,timestamp,status,selection,cameraRun ->
            val input = SampleInput(tracking,status,timestamp,generation,selection,cameraRun)
            if (sampleInputValid(input,SystemClock.uptimeMillis())) {
                pendingSampleInput.set(input)
                if (sampleInputPosted.compareAndSet(false,true)) handler.post(presentSampleInput)
            }
        })
        camera?.parallelSample = !intent.getBooleanExtra("avatar_camera_sample_serial_baseline",false)
        camera?.budget = sender.budget
        if (prefs.getInt("detail_upgrade",0)<1) prefs.edit().putString("quality","MAXIMUM").putInt("detail_upgrade",1).apply()
        MirrorState.videoQuality = runCatching { AvatarVideoQuality.valueOf(prefs.getString("quality","MAXIMUM")!!) }.getOrDefault(AvatarVideoQuality.MAXIMUM)
        sender.budget.quality = MirrorState.videoQuality
        MirrorState.sampleDetail = runCatching {
            AvatarSampleDetail.valueOf(prefs.getString("sample_detail","FAST")!!)
        }.getOrDefault(AvatarSampleDetail.FAST)
        camera?.sourceSize = { width,height -> handler.post { if (resumed) MirrorState.sourceResolution = "$width × $height" } }
        camera?.outputSize = { width,height ->
            val value = "$width × $height"
            if (MirrorState.outputResolution != value) handler.post { if (resumed && !useSample) MirrorState.outputResolution = value }
        }
        MirrorState.faceScale = prefs.getFloat("face_scale",1f); MirrorState.faceOffsetX = prefs.getFloat("face_x",0f); MirrorState.faceOffsetY = prefs.getFloat("face_y",0f)
        camera?.faceScale = MirrorState.faceScale; camera?.faceOffsetX = MirrorState.faceOffsetX; camera?.faceOffsetY = MirrorState.faceOffsetY
        MirrorState.protectHands = prefs.getBoolean("protect_hands",true); camera?.protectHands = MirrorState.protectHands
        camera?.frameRate = { fps,interval -> handler.post {
            if (resumed) { MirrorState.videoFps = fps; MirrorState.qualityStatus = "${MirrorState.videoQuality.label} · Target ${1000/interval} fps" }
        } }
        MirrorState.cameraMirror = true
        MirrorState.frame = null; MirrorState.active = false; MirrorState.cameraZoom = 1f
        applySelection()
        setContent { CameraDemoTheme {
            MirrorScreen(selectedPersona, { persona ->
                clearPhotoSelection()
                selectedPersona = persona; usePreset = true; useSample = false
                prefs.edit().putString("persona",persona.name).putBoolean("camera_preset",true).putBoolean("camera_sample",false).apply()
                applySelection()
            }, onPassthrough = { launchImmersive(false) }, onLive = { launchImmersive(true,useSample) },
                onRecenter = { MirrorState.cameraZoom = 1f; camera?.zoom = 1f; sampleAvatar?.recenter() },
                onShare = { connectWebcam() }, onStopSharing = { sender.stop(); MirrorState.sharing = false },
                onForgetPairing = { forgetWebcamPairing() }, onFindPc = { findPcs() },
                onPairPc = { pairPc(it) }, onConfirmPc = { easyPairing?.confirm(it); MirrorState.pairingConfirmation = "" },
                cameraMode = true, onCameraHorizon = {
                    clearPhotoSelection()
                    usePreset = false; useSample = false
                    prefs.edit().putBoolean("camera_preset",false).putBoolean("camera_sample",false).apply(); applySelection()
                }, onCameraRetry = { recoverAutomatically = true; retryDelay = AvatarStreamTiming.CAMERA_RETRY_MS
                    if (useSample) { sampleAvatar?.close(); sampleAvatar = null; ensureSample() }
                    if (resumed) restartCamera() },
                onCameraZoom = { MirrorState.cameraZoom = it; camera?.zoom = it },
                onQuality = { quality ->
                    MirrorState.videoQuality = quality; sender.budget.quality = quality
                    prefs.edit().putString("quality",quality.name).apply()
                    if (resumed) restartCamera()
                }, onProtectHands = {
                    MirrorState.protectHands = it; camera?.protectHands = it
                    prefs.edit().putBoolean("protect_hands",it).apply()
                }, onAlignment = { scale,x,y ->
                    MirrorState.faceScale = scale; MirrorState.faceOffsetX = x; MirrorState.faceOffsetY = y
                    camera?.faceScale = scale; camera?.faceOffsetX = x; camera?.faceOffsetY = y
                    prefs.edit().putFloat("face_scale",scale).putFloat("face_x",x).putFloat("face_y",y).apply()
                },
                onSampleDetail = { detail ->
                    MirrorState.sampleDetail = detail
                    prefs.edit().putString("sample_detail",detail.name).apply()
                    sampleRenderSchedule.reset(); MirrorState.frame = null; clearSampleVideo()
                },
                onCameraSample = {
                    clearPhotoSelection()
                    useSample = true; usePreset = false
                    prefs.edit().putBoolean("camera_sample",true).putBoolean("camera_preset",false).apply()
                    applySelection()
                },menuState=launcher,onMenuState={navigateLauncher(it)},photoFaces=photoFaces,
                selectedPhoto=selectedPhoto,onPhoto={selectPhoto(it)},onPickPhoto={
                    try{pickPhoto.launch(arrayOf("image/png","image/jpeg"))}catch(_:Exception){photoFaces.unavailable()}
                })
        } }
        if(usePreset && !useSample && !intent.getBooleanExtra("avatar_camera_horizon",false)) {
            photoFaces.restore(prefs.getString("photo_face","") ?: "") {photo ->
                selectedPhoto=photo;applySelection()
            }
        }
    }

    private fun applySelection() {
        handler.removeCallbacks(sampleLoop); resetSampleInput()
        pendingPreview.getAndSet(null)?.bitmap?.recycle()
        if (::sender.isInitialized) sender.clearPreview()
        val previousRun = camera?.runEpoch
        camera?.select(if (usePreset) selectedPersona else null,useSample,if(usePreset)selectedPhoto else null)
        if (resumed && previousRun != camera?.runEpoch) {
            generation++; lastFrame = 0L; cameraStartedAt = SystemClock.uptimeMillis(); cameraRetryAt = 0L
        }
        MirrorState.avatarSource = when { useSample -> MirrorAvatarSource.META_SAMPLE; usePreset -> MirrorAvatarSource.PRESET
            else -> MirrorAvatarSource.HORIZON }
        MirrorState.cameraOverlay = false; MirrorState.cameraSample = useSample; MirrorState.tracking = WearerSnapshot()
        MirrorState.frame = null; MirrorState.active = false
        MirrorState.cameraStatus = when {
            useSample -> "Meta sample · Preparing video estimates…"
            usePreset -> "${selectedPersona.displayName} · Opening Horizon camera…"
            else -> "My Horizon avatar · Opening system camera…"
        }
        if (useSample) { MirrorState.cameraZoom = 1f; camera?.zoom = 1f; ensureSample() }
        else { sampleAvatar?.close(); sampleAvatar = null }
    }

    private fun ensureSample() {
        if (!resumed || !useSample) return
        if (sampleAvatar == null) sampleAvatar = HorizonAccount(this,publishPreview = false).also { it.showSample() }
        handler.removeCallbacks(sampleLoop); handler.post(sampleLoop)
    }

    private fun logSlowFrame(metric: String, milliseconds: Long, now: Long) {
        val previous = lastSlowFrameLogAt.get()
        if (milliseconds >= 350L && now-previous >= 5000L && lastSlowFrameLogAt.compareAndSet(previous,now))
            Log.w("AlterEgoWebcam","Camera $metric=$milliseconds")
    }

    private fun startCamera() {
        if (!resumed || camera == null) return
        val allowed = listOf(Manifest.permission.CAMERA,AVATAR_PERMISSION).any {
            ContextCompat.checkSelfPermission(this,it) == PackageManager.PERMISSION_GRANTED
        }
        if (!allowed) {
            val permission = try { packageManager.getPermissionInfo(AVATAR_PERMISSION,0); AVATAR_PERMISSION }
                catch (_: PackageManager.NameNotFoundException) { Manifest.permission.CAMERA }
            requestCamera.launch(permission)
            return
        }
        generation++; lastFrame = 0L; cameraStartedAt = SystemClock.uptimeMillis(); cameraRetryAt = 0L
        resetSampleInput()
        MirrorState.cameraStatus = if (useSample) "Meta sample · Opening animation input…" else "Opening your Horizon avatar camera…"
        camera?.start()
        handler.removeCallbacks(watchdog); handler.postDelayed(watchdog,250L)
    }

    private fun restartCamera() {
        camera?.stop()
        sampleInput = null
        pendingPreview.getAndSet(null)?.bitmap?.recycle()
        sender.clearPreview()
        if (resumed) startCamera()
    }

    private fun stopCamera() {
        generation++; camera?.stop(); handler.removeCallbacks(watchdog); handler.removeCallbacks(sampleLoop)
        resetSampleInput()
        pendingPreview.getAndSet(null)?.bitmap?.recycle()
        if (::sender.isInitialized) sender.stop()
        MirrorState.sharing = false; MirrorState.active = false; MirrorState.frame = null
        MirrorState.videoFps = 0
        MirrorState.cameraOverlay = false
        MirrorState.cameraSample = false; MirrorState.tracking = WearerSnapshot()
    }

    private fun launchImmersive(live: Boolean, sample: Boolean = false) {
        stopCamera()
        MirrorState.cameraMirror = false
        startActivity(Intent(this,MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra("presentation_mode",if (live) "LIVE_MIRROR" else "PASSTHROUGH")
            .putExtra("live_mirror_panel",live).putExtra("show_meta_sample",sample))
        finishAndRemoveTask()
    }

    private fun connectWebcam() {
        try {
            sender.start(MirrorState.endpoint,MirrorState.pairCode)
            MirrorState.sharing = true; MirrorState.connection = "Connecting to Windows…"
        } catch (error: Exception) { MirrorState.connection = error.message ?: "Check the PC address and pairing code" }
    }
    private fun findPcs() {
        if (MirrorState.findingPc || MirrorState.sharing) return
        val request = ++pairingGeneration
        MirrorState.findingPc = true; MirrorState.nearbyPcs = emptyList()
        MirrorState.connection = "Looking for PCs on this Wi-Fi…"
        pairingExecutor.execute {
            val result = runCatching { AvatarPcDiscovery.scan() }
            handler.post {
                if (resumed && request == pairingGeneration) {
                    MirrorState.findingPc = false; MirrorState.nearbyPcs = result.getOrDefault(emptyList())
                    MirrorState.connection = if (MirrorState.nearbyPcs.isEmpty()) "No PC found · Start its receiver on the same Private network, or enter its address manually"
                        else "Choose your PC to pair · Compare the code on both devices"
                }
            }
        }
    }
    private fun pairPc(pc: DiscoveredPc) {
        easyPairing?.close()
        val request = ++pairingGeneration
        val pairing = AvatarEasyPairing(); easyPairing = pairing
        MirrorState.pairingPc = pc.name; MirrorState.connection = "Requesting pairing with ${pc.name}…"
        pairingExecutor.execute {
            val result = runCatching { pairing.pair(pc) { code -> handler.post {
                if (resumed && request == pairingGeneration) MirrorState.pairingConfirmation = code
            } } }
            handler.post {
                if (resumed && request == pairingGeneration) {
                    MirrorState.pairingConfirmation = ""; easyPairing = null
                    result.onSuccess { key ->
                        AvatarPairingStore(this).remember(pc.endpoint,key)
                        MirrorState.endpoint = pc.endpoint.key; MirrorState.pairCode = key.code
                        MirrorState.nearbyPcs = emptyList(); connectWebcam()
                    }.onFailure { MirrorState.connection = it.message ?: "Pairing failed · Try again" }
                }
            }
            pairing.close()
        }
    }

    private fun forgetWebcamPairing() {
        sender.stop()
        try {
            AvatarPairingStore(this).forget(MirrorState.endpoint)
            MirrorState.connection = "Pairing forgotten · Paste the current secure code displayed on the PC"
        } catch (error: Exception) { MirrorState.connection = error.message ?: "Enter the PC address first" }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if(camera!=null && (intent.getBooleanExtra("avatar_camera_horizon",false) || intent.getBooleanExtra("avatar_camera_sample",false))) {
            clearPhotoSelection();usePreset=false;useSample=intent.getBooleanExtra("avatar_camera_sample",false)
            getSharedPreferences("mirror",MODE_PRIVATE).edit().putBoolean("camera_preset",false).putBoolean("camera_sample",useSample).apply()
            applySelection()
        }
    }

    override fun onResume() {
        super.onResume(); resumed = true
        if(layoutQa)return
        photoFaces.refresh()
        recoverAutomatically = true; retryDelay = AvatarStreamTiming.CAMERA_RETRY_MS
        MirrorState.windowShown(this)
        if (camera != null) { MirrorState.cameraMirror = true; ensureSample(); startCamera() }
        Log.i("AlterEgoAvatarCamera","2D camera mirror resumed")
    }
    override fun onPause() {
        resumed = false
        menuPress.focusLost();backPress.focusLost()
        pairingGeneration++; easyPairing?.close(); easyPairing = null
        MirrorState.findingPc = false; MirrorState.pairingConfirmation = ""; MirrorState.nearbyPcs = emptyList()
        if (camera != null) stopCamera()
        sampleAvatar?.close(); sampleAvatar = null
        MirrorState.windowHidden(this)
        super.onPause()
    }
    override fun onDestroy() {
        resumed = false
        camera?.close(); camera = null
        if (::sender.isInitialized) sender.close()
        diagnosticAvatar?.close(); diagnosticAvatar = null
        sampleAvatar?.close(); sampleAvatar = null
        handler.removeCallbacksAndMessages(null)
        pairingExecutor.shutdownNow()
        layoutQaPhoto?.close();if(!layoutQa)photoFaces.close()
        MirrorState.windowHidden(this)
        super.onDestroy()
    }
    companion object { private const val AVATAR_PERMISSION = "horizonos.permission.AVATAR_CAMERA" }

    private val pickPhoto=registerForActivityResult(ActivityResultContracts.OpenDocument()){uri ->photoFaces.picked(uri)}

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val focused=resumed && hasWindowFocus()
        val down=event.action==KeyEvent.ACTION_DOWN;val now=event.eventTime
        when(event.keyCode) {
            KeyEvent.KEYCODE_MENU,KeyEvent.KEYCODE_BUTTON_START -> {
                if(menuPress.key(down,event.repeatCount,focused,now))navigateLauncher(launcher.toggle())
                return focused || super.dispatchKeyEvent(event)
            }
            KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BUTTON_Y -> {
                val handled=launcher.open || backPress.recentlyAccepted(now)
                if((handled || !down) && backPress.key(down,event.repeatCount,focused,now) && launcher.open)
                    navigateLauncher(launcher.back())
                if(handled && focused)return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Offscreen app-view captures are eligible only in this isolated synthetic
     * mode. It returns before opening cameras/accounts or restoring pairing. */
    private fun startLayoutQa() {
        layoutQa=true
        val pattern=PhotoFaceDiagnostic.pattern()
        MirrorState.frame=pattern;MirrorState.active=false;MirrorState.sharing=false
        MirrorState.endpoint="";MirrorState.pairCode="";MirrorState.nearbyPcs=emptyList()
        MirrorState.pairingConfirmation="";MirrorState.connection="Automatic pairing UI check"
        MirrorState.tracking=WearerSnapshot();MirrorState.avatarSource=MirrorAvatarSource.META_SAMPLE
        MirrorState.cameraStatus="Automatic layout check · Not wearer tracking"
        MirrorState.horizonStatus="Synthetic layout preview";MirrorState.sourceResolution="512 × 512 synthetic"
        MirrorState.outputResolution="512 × 512 synthetic";MirrorState.sampleFps=0;MirrorState.videoFps=0
        val photo=PhotoFaceImport(this,PhotoFaceLibrary(this,"photo_faces_qa"));layoutQaPhoto=photo
        photo.syntheticPreview(pattern)
        navigateLauncher(launcher.show())
        setContent {CameraDemoTheme {
            MirrorScreen(HumanPersona.DIGITAL_TWIN,{}, {},cameraMode=true,
                menuState=launcher,onMenuState={navigateLauncher(it)},
                photoFaces=if(launcher.page==QuickMenuPage.IMPORT)photo else null)
        }}
        Log.i("AlterEgoLauncherQA","STARTED: synthetic colour blocks only; cameras=0 wearerInputs=0 savedPairingRead=0")
        pairingExecutor.execute {
            val checked=runCatching {PhotoFaceDiagnostic.run(this)}
            handler.post {
                checked.onSuccess {Log.i("AlterEgoLauncherQA","Photo import checks=$it/7; synthetic=true")}
                    .onFailure {Log.e("AlterEgoLauncherQA","Photo check failed: ${it.javaClass.simpleName}")}
            }
        }
        val pages=QuickMenuPage.values().toList();var index=0;var captures=0
        fun next() {
            if(index>=pages.size){Log.i("AlterEgoLauncherQA","COMPLETED: syntheticViews=$captures/${pages.size}");return}
            val page=pages[index++];navigateLauncher(launcher.show(page))
            handler.postDelayed({
                runCatching {
                    val view=window.decorView
                    if(view.width<=0 || view.height<=0){
                        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(960,android.view.View.MeasureSpec.EXACTLY),
                            android.view.View.MeasureSpec.makeMeasureSpec(720,android.view.View.MeasureSpec.EXACTLY))
                        view.layout(0,0,960,720)
                    }
                    val image=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(image))
                    File(getExternalFilesDir(null) ?: cacheDir,"quick-launcher-${page.name.lowercase()}.png").outputStream().use {
                        check(image.compress(Bitmap.CompressFormat.PNG,100,it))
                    }
                    image.recycle();captures++
                    Log.i("AlterEgoLauncherQA","Captured synthetic ${page.name} app view")
                }.onFailure {Log.e("AlterEgoLauncherQA","Synthetic view capture failed: ${it.javaClass.simpleName}")}
                next()
            },1300L)
        }
        handler.postDelayed({next()},1800L)
    }
}
