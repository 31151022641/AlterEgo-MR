package com.oculus.camerademo.mirror

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.oculus.camerademo.R
import java.io.File
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream

internal object NativeHorizonBridge {
    init {
        System.loadLibrary("ovrplatformloader")
        System.loadLibrary("ovravatar2")
        System.loadLibrary("alterego_eye_gaze")
    }
    external fun start(activity: Activity, appId: String, corePath: String)
    external fun poll(pro: Boolean): IntArray
    external fun sample(corePath: String, sampleUri: String, pro: Boolean)
    external fun render(bitmap: Bitmap, tracking: FloatArray?, gaze: FloatArray, videoInput: Boolean, videoDeltaSeconds: Float, renderSamples: Int): Boolean
    external fun recenter()
    external fun close()
}

object HorizonAccountStatus {
    fun message(stage: Int, code: Int = 0): String {
        val detail = if (code != 0) " · Error $code" else ""
        return when (stage) {
            0 -> "Horizon avatar is not connected"
            1 -> "Connecting to your headset's Meta account…"
            2 -> "Checking access to AlterEgo MR…"
            3 -> "Reading your Meta account identity…"
            4 -> "Authorizing Horizon avatar access…"
            5 -> "Checking your Horizon avatar…"
            6 -> "Horizon avatar access confirmed · Loading the model"
            7 -> "Loading your Horizon avatar…"
            8 -> "Your Horizon avatar is ready"
            20 -> "Loading Meta's sample avatar…"
            21 -> "Meta sample avatar · This is not your personal Horizon avatar"
            -1 -> if (code == -9) "Meta refused app access · Add your account to this app's test release"
                else "Meta account connection failed · Check App ID and app configuration$detail"
            -2 -> "Meta refused app access · Check the test release and your account entitlement$detail"
            -3 -> "Meta refused account identity access · Check developer verification and User ID / User Profile approval$detail"
            -4 -> "Avatar authorization unavailable · Check Meta account access$detail"
            -5 -> "The Meta Avatars service could not start$detail"
            -6 -> "Meta Avatars rejected the account authorization$detail"
            -7 -> "Horizon avatar access failed · Check Avatars Data Use approval and internet access$detail"
            -8 -> "No Horizon avatar was found for this account · Create one in Horizon settings"
            -9 -> "Meta account connection timed out · Check Wi-Fi, then try again"
            -10 -> "Avatar model could not load · Try connecting again$detail"
            else -> "Horizon avatar connection unavailable$detail"
        }
    }
    fun isPending(stage: Int) = stage in 1..5 || stage == 7 || stage == 20
}

/** Uses the headset account; no password entry, embedded secret, or exported user token. */
class HorizonAccount(private val activity: Activity, private val publishPreview: Boolean = true) : AutoCloseable {
    companion object {
        private var owner: HorizonAccount? = null
        private val assetCopyLock = Any()
    }
    private val handler = Handler(Looper.getMainLooper())
    private val pro = Build.MODEL.contains("Pro", true) || Build.DEVICE == "seacliff"
    private var startedAt = 0L
    private var nativeStarted = false
    private var lastVideoDrawAt = 0L
    private var closed = false
    private var stage = 0
    private var diagnosticFrames = 0
    private val diagnostic = activity.javaClass.name == "com.oculus.camerademo.MirrorActivity" &&
        activity.intent.getBooleanExtra("avatar_render_diagnostic", false)
    private val gapDiagnostic = diagnostic && activity.intent.getBooleanExtra("avatar_sample_gap_test", false)
    private data class GapPixels(val face: FloatArray, val eyes: FloatArray, val hands: FloatArray)
    private val gapCaptures = mutableMapOf<AvatarSampleGapSequence.Phase, GapPixels>()
    private val gapRenderSchedule = AvatarSampleRenderSchedule()
    private var gapChannelsValid = true
    private val diagnosticFixtures = mutableListOf<File>()
    private var fixtureVerificationStarted = false
    private var fixtureReplayGeneration = -1
    private var preparation = 0
    private val assetExecutor = Executors.newSingleThreadExecutor()
    private val videoVerifier = if (diagnostic && activity.intent.getBooleanExtra("avatar_video_test",false))
        AvatarVideoFixtureVerifier(activity) else null
    private val sampleVerifier = if (diagnostic && activity.intent.getBooleanExtra("avatar_2d_sample_test",false))
        AvatarVideoSampleVerifier(activity,activity.intent.getBooleanExtra("avatar_gpu_sample_test",false)) sampleRender@{ snapshot,name,metrics ->
            if (closed || !fixtureVerificationStarted || fixtureReplayGeneration != preparation || diagnosticFrames < 90)
                return@sampleRender false
            val frame = draw(snapshot,videoInput = true)
            android.util.Log.i("AlterEgoSampleVideo","Retargeted $name: rendered=${frame != null} $metrics")
            frame?.let {
                File(activity.getExternalFilesDir(null),"retarget-$name").outputStream().use { output ->
                    it.compress(Bitmap.CompressFormat.PNG,100,output)
                }
                it.recycle()
            }
            frame != null
        } else null
    private val pump = object : Runnable {
        override fun run() {
            if (closed || !nativeStarted) return
            // After loading, the gap check uses the same render cadence as the
            // production sample. Polling the SDK would secretly advance cached
            // animation between draws and conceal settling problems.
            val result = if (gapDiagnostic && stage == 21) intArrayOf(21,0)
                else runCatching { NativeHorizonBridge.poll(pro) }.getOrNull()
            if (result == null || result.size != 2) {
                MirrorState.horizonStatus = HorizonAccountStatus.message(-1)
                MirrorState.horizonConnecting = false
                return
            }
            stage = result[0]
            MirrorState.horizonStatus = HorizonAccountStatus.message(stage, result[1])
            MirrorState.horizonConnecting = HorizonAccountStatus.isPending(stage)
            if (stage == 21 && (publishPreview || diagnostic)) {
                if (diagnostic) diagnosticFrames++
                val fixture = if (gapDiagnostic) {
                    val now = SystemClock.uptimeMillis()
                    val active = diagnosticSnapshot(false, true, opticalHands = true, curl = true)
                    val packet = active.nativePacket!!.copyOf().apply {
                        this[364 + 12] = .85f; this[364 + 13] = .15f
                    }
                    val synthetic = WearerPacketDecoder.decode(packet, now).copy(hasPhysicalFace = false,
                        hasVideoFace = true, hasEyes = true, gazeX = .6f, gazeY = -.3f,
                        message = "Public sample synthetic tracking diagnostic")
                    AvatarSampleGapSequence.input(diagnosticFrames, synthetic, now)
                } else if (diagnostic) diagnosticSnapshot(diagnosticFrames > 15, diagnosticFrames > 30,
                    diagnosticFrames > 45, diagnosticFrames > 60, diagnosticFrames > 75) else WearerSnapshot()
                if (gapDiagnostic) AvatarSampleGapSequence.phase(diagnosticFrames)?.let { phase ->
                    gapChannelsValid = gapChannelsValid && AvatarSampleGapSequence.channelsMatch(phase, fixture)
                }
                val renderNow = SystemClock.uptimeMillis()
                val renderedFrame = if (gapDiagnostic && !gapRenderSchedule.needsRender(fixture,renderNow)) MirrorState.frame
                    else draw(fixture, videoInput = gapDiagnostic).also { frame ->
                        if (gapDiagnostic && frame != null) gapRenderSchedule.rendered(fixture,SystemClock.uptimeMillis())
                    }
                if (gapDiagnostic && renderedFrame == null && AvatarSampleGapSequence.capture(diagnosticFrames)) {
                    android.util.Log.e("AlterEgoSampleGap", "Phase ${AvatarSampleGapSequence.phase(diagnosticFrames)} did not render")
                    if (diagnosticFrames == AvatarSampleGapSequence.totalFrames) verifyGapCaptures()
                }
                renderedFrame?.let { frame ->
                    MirrorState.frame = frame
                    if (gapDiagnostic) captureGap(frame, fixture)
                    // Diagnostic fixture contains the SDK's public sample with synthetic inputs,
                    // never a personal avatar, camera frame, or wearer sensor recording.
                    val name = when (diagnosticFrames) {
                        15 -> "horizon-sample-preview.png"
                        30 -> "horizon-sample-motion.png"
                        45 -> "horizon-sample-expression.png"
                        60 -> "horizon-sample-open-hands.png"
                        75 -> "horizon-sample-curled-hands.png"
                        90 -> "horizon-sample-blink-gaze.png"
                        else -> null
                    }
                    if (diagnostic && !gapDiagnostic && name != null) {
                        val file = File(activity.getExternalFilesDir(null) ?: activity.cacheDir, name)
                        val checksum = CRC32()
                        CheckedOutputStream(file.outputStream(), checksum).use {
                            check(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        diagnosticFixtures += file
                        val p = fixture.nativePacket
                        android.util.Log.i("AlterEgoSampleVideo", "Source fixture $name: rawSynthetic=true " +
                            "face=${p?.get(2)} eyes=${fixture.hasEyes} hands=[${p?.get(3)},${p?.get(4)}] " +
                            "blink=[${fixture.expression.eyeBlinkLeft},${fixture.expression.eyeBlinkRight}] " +
                            "jaw=${fixture.expression.jawOpen} pngCrc=${checksum.value}")
                    }
                }
                if (diagnostic && !gapDiagnostic && diagnosticFrames == 90) startFixtureVerification()
            }
            if (MirrorState.horizonConnecting && SystemClock.uptimeMillis() - startedAt > 90_000L) {
                NativeHorizonBridge.close()
                nativeStarted = false
                MirrorState.horizonConnecting = false
                MirrorState.horizonStatus = HorizonAccountStatus.message(-9)
            } else if (MirrorState.horizonConnecting || stage == 21 && diagnostic &&
                diagnosticFrames < if (gapDiagnostic) AvatarSampleGapSequence.totalFrames else 90) {
                handler.postDelayed(this, 100L)
            }
        }
    }

    fun connect() {
        MirrorState.avatarSource = MirrorAvatarSource.HORIZON
        prepare(false)
    }

    fun showSample() {
        MirrorState.avatarSource = MirrorAvatarSource.META_SAMPLE
        prepare(true)
    }

    private fun prepare(sample: Boolean) {
        if (closed) return
        if (owner !== this) owner?.close()
        owner = this
        handler.removeCallbacks(pump)
        stage = 0
        lastVideoDrawAt = 0L
        diagnosticFrames = 0
        gapCaptures.clear(); gapChannelsValid = true
        gapRenderSchedule.reset()
        diagnosticFixtures.clear(); fixtureVerificationStarted = false; fixtureReplayGeneration = -1
        MirrorState.horizonRendered = false
        startedAt = SystemClock.uptimeMillis()
        MirrorState.horizonStatus = if (sample) "Preparing Meta's sample avatar…" else HorizonAccountStatus.message(1)
        MirrorState.horizonConnecting = true
        val generation = ++preparation
        assetExecutor.execute {
            val paths = synchronized(assetCopyLock) { runCatching {
                val directory = File(activity.filesDir, "horizon-sdk-40.0.1").apply { mkdirs() }
                fun copy(name: String, installedName: String = name): File {
                    val destination = File(directory, installedName)
                    if (!destination.exists() || destination.length() == 0L) {
                        val temp = File(directory, "$installedName.tmp")
                        activity.assets.open("avatars/$name").use { input -> temp.outputStream().use { input.copyTo(it) } }
                        check(temp.renameTo(destination)) { "Avatar asset copy failed" }
                    }
                    return destination
                }
                // SDK initialization expects a directory containing this exact
                // core archive name, rather than the archive's pathname.
                copy("core.zip", "OvrAvatar2Assets.zip")
                directory.absolutePath to if (sample) "file://${copy("sample.glb").absolutePath}" else ""
            }.getOrNull() }
            handler.post {
                if (closed || preparation != generation) return@post
                nativeStarted = paths != null && runCatching {
                    if (sample) NativeHorizonBridge.sample(paths.first, paths.second, pro)
                    else NativeHorizonBridge.start(activity, activity.getString(R.string.meta_app_id), paths.first)
                    true
                }.getOrDefault(false)
                if (nativeStarted) handler.post(pump)
                else {
                    MirrorState.horizonConnecting = false
                    MirrorState.horizonStatus = "Meta avatar components are unavailable · Reinstall the latest app"
                }
            }
        }
    }

    fun draw(snapshot: WearerSnapshot, videoInput: Boolean = false,
        detail: AvatarSampleDetail = AvatarSampleDetail.MAXIMUM): Bitmap? {
        if (closed || !nativeStarted || stage != 8 && stage != 21 ||
            MirrorState.avatarSource == MirrorAvatarSource.PRESET) return null
        val now = SystemClock.uptimeMillis()
        val fresh = if (videoInput) AvatarSampleFramePolicy.fresh(snapshot.sampledAtMs,now) && snapshot.head != null
            else snapshot.isFresh(now)
        // Video input always requires an explicit current packet. Tracking gaps use
        // AvatarSamplePresentation's fresh neutral packet rather than cached motion.
        if (videoInput && (!fresh || snapshot.nativePacket?.size != 1060 || snapshot.nativePacket[0] != 1f)) return null
        val profile = if (videoInput) detail else AvatarSampleDetail.MAXIMUM
        val frame = Bitmap.createBitmap(profile.width, profile.height, Bitmap.Config.ARGB_8888)
        val packet = if (fresh) snapshot.nativePacket else null
        val gaze = floatArrayOf(if (fresh && snapshot.hasEyes) 1f else 0f, snapshot.gazeX, snapshot.gazeY)
        val deltaSeconds = if (videoInput) AvatarSampleAnimationTiming.deltaSeconds(lastVideoDrawAt,now)
            else AvatarSampleAnimationTiming.FIRST_STEP_SECONDS
        val rendered = runCatching { NativeHorizonBridge.render(frame, packet, gaze, videoInput,deltaSeconds,profile.samples) }.getOrDefault(false)
        lastVideoDrawAt = if (rendered && videoInput) now else 0L
        MirrorState.horizonRendered = rendered
        if (!rendered) { frame.recycle(); return null }
        return frame
    }

    fun recenter() { if (nativeStarted) runCatching { NativeHorizonBridge.recenter() } }

    /** The producer has reached its final source frame and will no longer post
     * pump callbacks. Replaying immutable public files now causes one raw->video
     * skeleton transition, instead of alternating bindings during production. */
    private fun startFixtureVerification() {
        if (fixtureVerificationStarted || closed) return
        check(Looper.myLooper() == Looper.getMainLooper())
        fixtureVerificationStarted = true
        fixtureReplayGeneration = preparation
        val generation = preparation
        val files = diagnosticFixtures.toList()
        android.util.Log.i("AlterEgoSampleVideo", "Source generation finished: frames=$diagnosticFrames fixtures=${files.size}; starting isolated image inference")
        if (sampleVerifier == null) {
            if (videoVerifier != null) startPresetFixtureReplay(files, generation)
            return
        }
        val outcomes = mutableListOf<String>()
        val finalOutcomes = mutableMapOf<Int,String>()
        val deadline = SystemClock.uptimeMillis() + 60_000L
        var index = -1 // Disposable public model warm-up, followed by all six fixtures.
        var attempt = 1
        val attemptsPerFixture = 4
        fun record(outcome: String, warmUp: Boolean) {
            outcomes += if (warmUp) outcome else "attempt=$attempt/$attemptsPerFixture $outcome"
            if (warmUp) {
                index = 0
                attempt = 1
            } else if (attempt < attemptsPerFixture) {
                attempt++
            } else {
                finalOutcomes[index] = outcome
                index++
                attempt = 1
            }
        }
        fun finish() {
            if (closed || preparation != generation) return
            val rendered = finalOutcomes.values.count { it.contains("rendered=true") }
            val report = "Public SDK samples only; source production stopped before inference; " +
                "$attemptsPerFixture newly acquired observations per held fixture\n" +
                outcomes.joinToString("\n") + "\nCOMPLETED: sourceFixtures=${files.size} retargeted=$rendered/6"
            File(activity.getExternalFilesDir(null) ?: activity.cacheDir, "sample-video-report.txt").writeText(report)
            android.util.Log.i("AlterEgoSampleVideo", report)
            if (videoVerifier != null) startPresetFixtureReplay(files, generation)
        }
        fun next() {
            if (closed || preparation != generation) return
            if (files.isEmpty() || index >= files.size || SystemClock.uptimeMillis() > deadline) {
                if (index < files.size) outcomes += "Replay deadline or missing source fixtures"
                finish(); return
            }
            val warmUp = index == -1
            val file = files[if (warmUp) 0 else index]
            assetExecutor.execute {
                val camera = runCatching {
                    val source = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                        ?: return@runCatching null
                    try {
                        Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                            // SDK output is a mirror; Camera2 input is unmirrored.
                            android.graphics.Canvas(bitmap).apply {
                                translate(source.width.toFloat(), 0f); scale(-1f, 1f); drawBitmap(source, 0f, 0f, null)
                            }
                        }
                    } finally { source.recycle() }
                }.getOrNull()
                handler.post {
                    if (closed || preparation != generation) { camera?.recycle(); return@post }
                    if (camera == null) {
                        record("${file.name}: decode failed",warmUp)
                        next(); return@post
                    }
                    val name = if (warmUp) "public-model-warmup" else file.name
                    // Each offer gets its own acquisition timestamp and inference.
                    // Holding public pixels across observations lets the SDK hand
                    // solver settle without reviving an old live tracking packet.
                    // Repeated exports replace the same public PNG with its last pose.
                    val accepted = sampleVerifier.offer(camera, name, warmUpOnly = warmUp) { outcome ->
                        if (closed || preparation != generation) return@offer
                        record(outcome,warmUp)
                        next()
                    }
                    camera.recycle()
                    if (!accepted) {
                        record("$name: verifier rejected submission",warmUp)
                        next()
                    }
                }
            }
        }
        next()
    }

    /** The software-preset diagnostic also starts after source production. When
     * both checks were requested, run it after SDK retarget replay to avoid model
     * initialization competing with the timed inference test. */
    private fun startPresetFixtureReplay(files: List<File>, generation: Int) {
        val verifier = videoVerifier ?: return
        var index = 0
        fun next() {
            if (closed || preparation != generation || index >= files.size) return
            val file = files[index++]
            assetExecutor.execute {
                val bitmap = runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
                handler.post {
                    if (closed || preparation != generation) { bitmap?.recycle(); return@post }
                    if (bitmap != null) { verifier.offer(bitmap, file.name); bitmap.recycle() }
                    handler.postDelayed({ next() }, 1500L)
                }
            }
        }
        next()
    }

    /** Captures only the bundled public sample under synthetic input. Regions
     * measure rendered controls, so neutral packet checks alone cannot hide a
     * stale expression or hand state cached inside the SDK. */
    private fun captureGap(frame: Bitmap, fixture: WearerSnapshot) {
        if (diagnosticFrames == AvatarSampleGapSequence.FRAMES_PER_PHASE*2+1) {
            File(activity.getExternalFilesDir(null) ?: activity.cacheDir,"sample-gap-first-neutral.png").outputStream().use {
                frame.compress(Bitmap.CompressFormat.PNG,100,it)
            }
        }
        if (!AvatarSampleGapSequence.capture(diagnosticFrames)) return
        val phase = AvatarSampleGapSequence.phase(diagnosticFrames) ?: return
        fun region(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
            val columns = 64; val rows = 40
            return FloatArray(columns * rows * 3).also { pixels ->
                for (y in 0 until rows) for (x in 0 until columns) {
                    val px = ((left + (right - left) * (x + .5f) / columns) * frame.width).toInt().coerceIn(0, frame.width - 1)
                    val py = ((top + (bottom - top) * (y + .5f) / rows) * frame.height).toInt().coerceIn(0, frame.height - 1)
                    val color = frame.getPixel(px, py); val at = (y * columns + x) * 3
                    pixels[at] = ((color ushr 16) and 255).toFloat()
                    pixels[at + 1] = ((color ushr 8) and 255).toFloat()
                    pixels[at + 2] = (color and 255).toFloat()
                }
            }
        }
        gapCaptures[phase] = GapPixels(region(.43f, .19f, .57f, .43f),
            region(.445f, .225f, .545f, .30f), region(.15f, .35f, .85f, 1f))
        File(activity.getExternalFilesDir(null) ?: activity.cacheDir, phase.fileName).outputStream().use {
            frame.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        android.util.Log.i("AlterEgoSampleGap", "Captured $phase: frame=$diagnosticFrames " +
            "channelChecks=$gapChannelsValid face=${fixture.hasVideoFace} eyes=${fixture.hasEyes} " +
            "hands=${fixture.hands.map { hand -> hand.any { it != null } }}")
        if (diagnosticFrames == AvatarSampleGapSequence.totalFrames) verifyGapCaptures()
    }

    private fun verifyGapCaptures() {
        val metrics = StringBuilder("Public SDK sample only; synthetic input; no wearer recording\n")
        fun difference(a: FloatArray, b: FloatArray) = a.indices.sumOf {
            kotlin.math.abs(a[it] - b[it]).toDouble()
        }.toFloat() / a.size
        val result = runCatching {
            check(gapChannelsValid) { "Input channel clearing failed" }
            check(gapCaptures.size == AvatarSampleGapSequence.Phase.values().size) { "A phase did not render" }
            val base = gapCaptures.getValue(AvatarSampleGapSequence.Phase.BASELINE)
            val active = gapCaptures.getValue(AvatarSampleGapSequence.Phase.ACTIVE)
            val gap = gapCaptures.getValue(AvatarSampleGapSequence.Phase.GAP)
            val recovery = gapCaptures.getValue(AvatarSampleGapSequence.Phase.RECOVERED)
            val handsOnly = gapCaptures.getValue(AvatarSampleGapSequence.Phase.HANDS_WITHOUT_FACE)
            val final = gapCaptures.getValue(AvatarSampleGapSequence.Phase.FINAL_NEUTRAL)
            val activeFace = difference(active.face, gap.face)
            val activeEyes = difference(active.eyes, gap.eyes)
            val activeHands = difference(active.hands, gap.hands)
            val recoveryFace = difference(recovery.face, gap.face)
            val recoveryHands = difference(recovery.hands, gap.hands)
            val neutralFace = difference(final.face, base.face)
            val neutralEyes = difference(final.eyes, base.eyes)
            val neutralHands = difference(final.hands, base.hands)
            val partialFace = difference(handsOnly.face, gap.face)
            val partialHands = difference(handsOnly.hands, active.hands)
            metrics.append("RGB mean absolute differences (0..255): active/gap face=$activeFace eyes=$activeEyes hands=$activeHands\n")
            metrics.append("recovery/gap face=$recoveryFace hands=$recoveryHands; final/baseline face=$neutralFace eyes=$neutralEyes hands=$neutralHands\n")
            metrics.append("hands-only/gap face=$partialFace; hands-only/active hands=$partialHands\n")
            check(activeFace > .5f && activeEyes > .25f && activeHands > .5f) { "Gap retained active rendered controls" }
            check(recoveryFace > .5f && recoveryHands > .5f) { "Tracking did not visibly recover" }
            check(neutralFace < maxOf(1f, activeFace * .25f) && neutralEyes < maxOf(.75f, activeEyes * .3f)) {
                "Neutral face/eye controls did not return to baseline"
            }
            check(neutralHands < maxOf(2f, activeHands * .35f)) { "Neutral hands did not return toward baseline" }
            check(partialFace < maxOf(1f, activeFace * .25f) && partialHands < maxOf(2f, activeHands * .35f)) {
                "Face loss did not retain hands independently"
            }
        }
        val status = if (result.isSuccess) "PASSED" else "FAILED: ${result.exceptionOrNull()?.message}"
        metrics.append(status)
        File(activity.getExternalFilesDir(null) ?: activity.cacheDir, "sample-gap-report.txt").writeText(metrics.toString())
        if (result.isSuccess) android.util.Log.i("AlterEgoSampleGap", metrics.toString())
        else android.util.Log.e("AlterEgoSampleGap", metrics.toString())
    }

    // Explicit public-sample test fixture; never samples any wearer sensors.
    internal fun publicSampleTestInput(active: Boolean, now: Long, faceOnly: Boolean = false): WearerSnapshot? {
        // Only an SDK public sample entity is eligible; a personal avatar can
        // never be exported by the camera-load diagnostic.
        if (closed || !nativeStarted || stage != 21) return null
        if (!active) return AvatarSamplePresentation.neutral(now)
        val packet = diagnosticSnapshot(false,true,opticalHands=true,curl=true).nativePacket!!.copyOf().apply {
            this[364+12] = .85f; this[364+13] = .15f
            if (faceOnly) {
                // Match a live face-only packet: no body/wrist/finger input,
                // with head, facial controls and explicit gaze still current.
                this[1] = 0f; this[3] = 0f; this[4] = 0f
                fill(0f,12,364); fill(0f,434,size)
            }
        }
        return WearerPacketDecoder.decode(packet,now).copy(hasPhysicalFace=false,hasVideoFace=true,
            hasEyes=true,gazeX=.6f,gazeY=-.3f,message="Automatic public sample test · Not wearer tracking")
    }

    private fun diagnosticSnapshot(motion: Boolean, expression: Boolean, opticalHands: Boolean = false,
        curl: Boolean = false, blink: Boolean = false): WearerSnapshot {
        val packet = FloatArray(1060)
        packet[0] = 1f; packet[1] = 1f
        packet[6] = 1.6f; packet[9] = if (motion) .17f else 0f
        packet[11] = kotlin.math.sqrt(1f - packet[9] * packet[9])
        fun wrist(joint: Int, x: Float, y: Float) {
            val at = 12 + joint * 8; packet[at] = 1f
            packet[at + 1] = x; packet[at + 2] = y; packet[at + 3] = -.3f; packet[at + 7] = 1f
        }
        wrist(12, if (motion) -.35f else -.25f, if (motion) 1.45f else 1.05f)
        wrist(17, .3f, 1.05f)
        if (opticalHands) {
            val parents = intArrayOf(1, -1, 1, 2, 3, 4, 1, 6, 7, 8, 9, 1, 11, 12, 13, 14, 1, 16, 17, 18, 19, 1, 21, 22, 23, 24)
            val bind = Array(26) { floatArrayOf(0f, 0f, 0f) }
            bind[0] = floatArrayOf(0f, 0f, -.025f)
            for (j in 2..5) bind[j] = floatArrayOf(-.020f - .013f * (j - 2), 0f, -.020f - .021f * (j - 2))
            for ((finger, base) in listOf(6, 11, 16, 21).withIndex()) {
                for (j in 0..4) bind[base + j] = floatArrayOf(-.027f + finger * .018f, 0f, -.025f - .023f * j)
            }
            for (side in 0..1) {
                // Opposing hands must also oppose their radial/thumb direction.
                val sideBind = Array(26) { j -> bind[j].copyOf().also { if (side == 1) it[0] = -it[0] } }
                packet[3 + side] = 1f; packet[642 + side] = 1f
                val angles = FloatArray(26) { (Math.PI / 2).toFloat() }
                val positions = Array(26) { floatArrayOf(0f, 0f, 0f) }
                positions[1] = floatArrayOf(if (side == 0) -.35f else .35f, 1.45f, -.3f)
                for (j in listOf(0) + (2..25)) {
                    val parent = parents[j]
                    val flex = if (curl && j in listOf(3, 4, 7, 8, 9, 12, 13, 14, 17, 18, 19, 22, 23, 24)) .85f else 0f
                    angles[j] = angles[parent] + flex
                    val dy = sideBind[j][1] - sideBind[parent][1]; val dz = sideBind[j][2] - sideBind[parent][2]
                    positions[j] = floatArrayOf(positions[parent][0] + sideBind[j][0] - sideBind[parent][0],
                        positions[parent][1] + kotlin.math.cos(angles[parent]) * dy - kotlin.math.sin(angles[parent]) * dz,
                        positions[parent][2] + kotlin.math.sin(angles[parent]) * dy + kotlin.math.cos(angles[parent]) * dz)
                }
                for (j in 0..25) {
                    val at = 156 + side * 104 + j * 4; val q = 434 + side * 104 + j * 4
                    packet[at] = 1f
                    positions[j].copyInto(packet, at + 1)
                    packet[q] = kotlin.math.sin(angles[j] / 2); packet[q + 3] = kotlin.math.cos(angles[j] / 2)
                    val rest = 644 + side * 208 + j * 8
                    packet[rest] = parents[j].toFloat(); sideBind[j].copyInto(packet, rest + 1); packet[rest + 7] = 1f
                }
            }
        }
        if (expression) {
            packet[2] = 1f
            packet[364 + 24] = .7f
            packet[364 + 22] = .5f; packet[364 + 23] = .5f
            if (blink) { packet[364 + 12] = .95f; packet[364 + 13] = .95f }
        }
        return WearerPacketDecoder.decode(packet, SystemClock.uptimeMillis())
            .copy(hasEyes = expression, gazeX = if (blink) -.5f else .25f, gazeY = .15f)
    }

    override fun close() {
        closed = true
        videoVerifier?.close()
        sampleVerifier?.close()
        preparation++
        assetExecutor.shutdownNow()
        handler.removeCallbacks(pump)
        if (nativeStarted) NativeHorizonBridge.close()
        if (owner === this) owner = null
        nativeStarted = false
        MirrorState.horizonConnecting = false
        MirrorState.horizonRendered = false
    }
}
