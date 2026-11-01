// (c) Meta Platforms, Inc. and affiliates.
// AlterEgo MR: Real-Time Passthrough Face Morph for Meta Quest
// Standalone mixed reality; eye tracking is recommended and head direction is available on Quest 3/3S.

package com.oculus.camerademo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Box
import com.meta.spatial.toolkit.DpDisplayOptions
import com.meta.spatial.toolkit.Mesh
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.runtime.PointerEventType
import com.meta.spatial.isdk.IsdkSystem
import com.meta.spatial.vr.VRFeature
import com.oculus.camerademo.camera.CameraFrame
import com.oculus.camerademo.camera.CameraSource
import com.oculus.camerademo.camera.CameraSourceErrorCode
import com.oculus.camerademo.audio.FaceVoiceSpeaker
import com.oculus.camerademo.camera.QuestPassthroughCameraSource
import com.oculus.camerademo.eyetracking.EyeTrackingDeviceStatus
import com.oculus.camerademo.eyetracking.EyeTrackingManager
import com.oculus.camerademo.ml.FaceLandmarkDetector
import com.oculus.camerademo.ml.FaceTrackingData
import com.oculus.camerademo.ml.MultiFaceTrackingData
import com.oculus.camerademo.model3d.HumanPersona
import com.oculus.camerademo.model3d.AuthoredPortraitStore
import com.oculus.camerademo.renderer.ResolutionQuality
import android.content.Intent
import com.oculus.camerademo.model3d.DynamicPersona
import com.oculus.camerademo.model3d.OnlineFaceCatalog
import com.oculus.camerademo.model3d.PersonaSource
import com.oculus.camerademo.model3d.Sticker3DType
import com.oculus.camerademo.ui.AddFaceModal
import com.oculus.camerademo.ui.TrackingStatusScreen
import com.oculus.camerademo.eyetracking.GazeHeadTarget
import com.oculus.camerademo.eyetracking.GazeVector
import com.oculus.camerademo.eyetracking.GazeRotation
import com.oculus.camerademo.renderer.SpatialAvatarRenderer
import com.oculus.camerademo.renderer.ViewerPoseHistory
import com.oculus.camerademo.renderer.AvatarVisibilityPolicy
import com.oculus.camerademo.renderer.SceneCoordinateMapping
import com.oculus.camerademo.renderer.HeadDirectionReticle
import com.oculus.camerademo.targeting.FaceTargetingController
import com.oculus.camerademo.targeting.TargetingMode
import com.oculus.camerademo.ui.FaceMorphAROverlay
import com.oculus.camerademo.ui.FloatingMenuOpenButton
import com.oculus.camerademo.ui.HandQuickActionBar
import com.oculus.camerademo.ui.HandDockBar
import com.oculus.camerademo.ui.HandsFirstControlPanel
import com.oculus.camerademo.ui.FlatPanelLayout
import com.oculus.camerademo.ui.FlatPanelReset
import com.oculus.camerademo.ui.PanelFocusRecovery
import com.oculus.camerademo.ui.MagicMirrorView
import com.oculus.camerademo.ui.SettingsDialog
import com.oculus.camerademo.ui.SpatialMenuInteraction
import com.oculus.camerademo.ui.theme.CameraDemoTheme
import com.oculus.camerademo.ui.QuickLauncher
import com.oculus.camerademo.ui.QuickMenuNavigation
import com.oculus.camerademo.ui.QuickMenuPage
import com.oculus.camerademo.ui.MenuPressGate
import com.oculus.camerademo.ui.QuickFaces
import com.oculus.camerademo.ui.QuickImport
import com.oculus.camerademo.ui.QuickAction
import com.oculus.camerademo.ui.QuickMenuColors
import com.oculus.camerademo.ui.FlatPanelPlacement
import com.oculus.camerademo.ui.PassthroughControlDock
import com.oculus.camerademo.ui.ImmersiveInputSystem
import com.oculus.camerademo.diagnostics.HandPerformanceLog
import com.oculus.camerademo.diagnostics.SceneFrameWindow
import com.oculus.camerademo.model3d.PhotoFace
import com.oculus.camerademo.model3d.PhotoFaceEntry
import com.oculus.camerademo.model3d.PhotoFaceImport
import com.meta.spatial.runtime.SessionState
import com.oculus.camerademo.mirror.*

/**
 * AlterEgo MR: True Passthrough Immersion AR Application
 * Built with Meta Spatial SDK for Meta Horizon OS.
 *
 * Switches between immersive outward-camera passthrough and a flat wearer-avatar mirror.
 * Live animation uses a movable flat panel; the separate OS mirror window remains available.
 */
class MainActivity : AppSystemActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val MENU_PANEL_ID = 2001
        private const val MIRROR_PANEL_ID = 2002
        private const val FACE_MORPH_PANEL_ID = 2003
        private const val DOCK_PANEL_ID = 2004
        private const val FACE_MORPH_PANEL_ID_2 = 2005
        private const val PERMISSIONS_REQUEST_CODE = 101
        private const val REQUEST_CODE_PICK_IMAGE = 202
    }

    private var isSceneReady = false
    private var isAppPaused = false
    private var handPerformance: HandPerformanceLog? = null

    private var activeCameraSource: CameraSource? = null
    private var passthroughSource: QuestPassthroughCameraSource? = null
    private var cameraFailure by mutableStateOf<String?>(null)
    private var cameraFailureCode by mutableStateOf<CameraSourceErrorCode?>(null)
    private var cameraReady by mutableStateOf(false)
    private var avatarRenderer: SpatialAvatarRenderer? = null
    private var headDirectionReticle: HeadDirectionReticle? = null
    private val viewerHistory = ViewerPoseHistory()
    private val faceTargeting = FaceTargetingController()
    private var targetingMode by mutableStateOf(TargetingMode.HEAD_DIRECTION)
    private var presentationMode by mutableStateOf(PresentationMode.PASSTHROUGH)
    private lateinit var wearerTracking: WearerTrackingManager
    private lateinit var horizonAccount: HorizonAccount
    private val wearerPainter = WearerAvatarPainter()
    private var wearerRenderWorker: WearerAvatarRenderWorker? = null
    private lateinit var avatarLanSender: AvatarLanSender
    private var mirrorWindowRequested = false
    private var mirrorWindowLaunched = false
    private var trackingSessionVisible = false
    private var trackingInputFocused = false
    private var liveMirrorPanel = false
    private var headsetMounted = true
    private val mirrorLoop = object : Runnable {
        override fun run() {
            if (destroyed || presentationMode != PresentationMode.LIVE_MIRROR) return
            updateMirrorFrame()
            mainHandler.postDelayed(this, avatarLanSender.budget.intervalMs)
        }
    }
    private val detectorExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var detectorRetrying by mutableStateOf(false)
    private var permissionsRequested = false
    @Volatile private var destroyed = false
    @Volatile private var cameraGeneration = 0L
    private var lastTrackingUiTime = 0L
    private var lastAvatarUiTime = 0L
    private var hadOutwardFrames = false
    private val blockingReason: String?
        get() = when {
            cameraFailure != null -> cameraFailure
            latestMultiTrackingData.status == com.oculus.camerademo.ml.FaceTrackingStatus.UNCALIBRATED -> "The camera calibration could not be used. Live face replacement is paused."
            latestMultiTrackingData.status == com.oculus.camerademo.ml.FaceTrackingStatus.UNAVAILABLE -> "The face detection model is unavailable. Use Retry face detection to try loading it again."
            latestMultiTrackingData.status == com.oculus.camerademo.ml.FaceTrackingStatus.ERROR -> "Face detection stopped responding. Close and reopen AlterEgo to restart it safely."
            latestMultiTrackingData.status == com.oculus.camerademo.ml.FaceTrackingStatus.AMBIGUOUS -> "Faces overlap · Waiting for a clear view to avoid changing the wrong person"
            !cameraReady -> "Waiting for access to the outward cameras."
            else -> null
        }

    private lateinit var faceDetector: FaceLandmarkDetector
    private lateinit var eyeTrackingManager: EyeTrackingManager
    private lateinit var faceVoiceSpeaker: FaceVoiceSpeaker
    private var isVoiceSpeakerEnabled by mutableStateOf(true)
    private var lastVoiceAnnouncement by mutableStateOf<String?>(null)

    // Optional eye sensor status; camera availability independently controls face tracking.
    private var eyeTrackingStatus by mutableStateOf(EyeTrackingDeviceStatus.NO_HARDWARE)

    private var morphTrackingData by mutableStateOf(FaceTrackingData(hasFace = false))
    private var secondMorphTrackingData by mutableStateOf(FaceTrackingData(hasFace = false))
    private var mirrorTrackingData by mutableStateOf(FaceTrackingData(hasFace = false))
    private var menuTelemetryData by mutableStateOf(FaceTrackingData(hasFace = false))
    private var latestTrackingData by mutableStateOf(FaceTrackingData(hasFace = false))
    private var latestMultiTrackingData by mutableStateOf(MultiFaceTrackingData())
    private var gazeLockedHeadId by mutableStateOf<Int?>(null)
    private var selectedPersona by mutableStateOf(HumanPersona.UNICORN_MEMOJI)
    private var morphAlpha by mutableStateOf(0.90f)
    private var isMockState by mutableStateOf(false)
    private var isFaceInGazeState by mutableStateOf(false)

    // Left Controller Menu Button (≡) & 2D Menu Tablet State: Closed by default on launch (pops up via ≡ or Hand Control)
    private var launcher by mutableStateOf(QuickMenuNavigation())
    private var isMenuVisible: Boolean
        get()=launcher.open
        set(value){launcher=launcher.copy(open=value)}
    private val photoFaces by lazy { PhotoFaceImport(this) }
    private var selectedPhoto by mutableStateOf<PhotoFace?>(null)
    private val menuPress=MenuPressGate()
    private val backPress=MenuPressGate()
    private val mirrorPress=MenuPressGate()
    private var isSettingsDialogOpen by mutableStateOf(false)
    private var headScale by mutableStateOf(1.0f)
    private var resolutionQuality by mutableStateOf(ResolutionQuality.BALANCED)
    private var isFoveatedEnabled by mutableStateOf(true)
    private var showGazeIndicatorRing by mutableStateOf(true)
    private var isHairPhysicsEnabled by mutableStateOf(true)
    private var isPartyModeEnabled by mutableStateOf(false)
    private var isPartyDiversePersonas by mutableStateOf(false)
    private val spatialInteraction = SpatialMenuInteraction()

    // Spatial Entities
    private var menuEntity: Entity? = null
    private var avatarEntity: Entity? = null
    private var avatarEntity2: Entity? = null
    private var isMirrorVisible by mutableStateOf(false)
    private var mirrorEntity: Entity? = null
    private var dockEntity: Entity? = null
    private val panelInputObservers = mutableListOf<Pair<IsdkSystem, IsdkSystem.InteractableObserverHandle>>()

    private fun cycleNextPersona() {
        val choices=if(presentationMode==PresentationMode.PASSTHROUGH)HumanPersona.available else HumanPersona.mirrorAvailable
        val next = choices[(choices.indexOf(selectedPersona)+1).mod(choices.size)]
        selectPreset(next)
        Toast.makeText(this, "🎭 Switched to ${next.displayName}", Toast.LENGTH_SHORT).show()
    }

    // Dynamic Personas & 3D Stickers
    private val availablePersonas = mutableStateListOf<DynamicPersona>().apply {
        addAll(OnlineFaceCatalog.getDefaultPersonas())
    }
    private var selectedDynamicPersona by mutableStateOf<DynamicPersona?>(
        DynamicPersona.fromBuiltin(HumanPersona.UNICORN_MEMOJI)
    )
    private var activeStickers by mutableStateOf(setOf<Sticker3DType>())
    private var isAddFaceDialogOpen by mutableStateOf(false)

    private fun launchFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("image/png","image/jpeg"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE)
        } catch (e: Exception) {
            photoFaces.unavailable()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == RESULT_OK) {
            photoFaces.picked(data?.data)
        }
    }

    private fun selectPreset(persona: HumanPersona) {
        if(presentationMode!=PresentationMode.PASSTHROUGH && persona.isPassthroughOnly)return
        photoFaces.cancelSelection(); selectedPhoto=null; selectedPersona=persona
        selectedDynamicPersona=DynamicPersona.fromBuiltin(persona)
        getSharedPreferences(if(presentationMode==PresentationMode.PASSTHROUGH)"passthrough" else "mirror",MODE_PRIVATE)
            .edit().putString("persona",persona.name).remove("photo_face").apply()
    }
    private fun selectPhoto(entry: PhotoFaceEntry) {
        photoFaces.select(entry) { photo ->
            selectedPhoto=photo;selectedPersona=HumanPersona.DIGITAL_TWIN
            selectedDynamicPersona=null
            getSharedPreferences("passthrough",MODE_PRIVATE).edit().putString("photo_face",entry.id)
                .putString("persona",HumanPersona.DIGITAL_TWIN.name).apply()
        }
    }

    private fun navigateLauncher(next: QuickMenuNavigation) {
        launcher=next
        spatialInteraction.clearFocus()
        if(isSceneReady && !isAppPaused)updateFlatPanelVisibility()
    }

    @Composable
    private fun PassthroughDetectionStatus() {
        val reason=blockingReason ?: return
        val detectorStatus=latestMultiTrackingData.status
        val unavailable=cameraFailureCode==CameraSourceErrorCode.UNSUPPORTED_DEVICE ||
            cameraFailureCode==CameraSourceErrorCode.CAMERA_UNAVAILABLE ||
            (cameraFailure==null && detectorStatus==com.oculus.camerademo.ml.FaceTrackingStatus.UNAVAILABLE)
        androidx.compose.material3.Text(if(unavailable)"Face detection unavailable" else "Face detection paused",
            color=QuickMenuColors.accent)
        androidx.compose.material3.Text(reason,color=QuickMenuColors.muted)
        when {
            cameraFailureCode==CameraSourceErrorCode.UNSUPPORTED_DEVICE -> Unit
            cameraFailure!=null -> QuickAction("Retry camera access",{checkPermissionsAndStart()},
                interaction=spatialInteraction,id="retry-outward")
            detectorStatus==com.oculus.camerademo.ml.FaceTrackingStatus.UNAVAILABLE ->
                QuickAction(if(detectorRetrying)"Loading face detection…" else "Retry face detection",{retryFaceDetection()},
                    enabled=!detectorRetrying,interaction=spatialInteraction,id="retry-face-detection")
            detectorStatus==com.oculus.camerademo.ml.FaceTrackingStatus.ERROR -> Unit
            detectorStatus==com.oculus.camerademo.ml.FaceTrackingStatus.UNCALIBRATED || !cameraReady ->
                QuickAction("Retry camera access",{checkPermissionsAndStart()},interaction=spatialInteraction,id="retry-outward")
        }
    }

    private fun retryFaceDetection() {
        if(destroyed || isAppPaused || presentationMode!=PresentationMode.PASSTHROUGH || detectorRetrying)return
        detectorRetrying=true
        try {
            detectorExecutor.execute {
                if(!destroyed) {
                    faceDetector.init()
                    val result=faceDetector.latestMultiTrackingData
                    mainHandler.post {
                        detectorRetrying=false
                        if(!destroyed && !isAppPaused && presentationMode==PresentationMode.PASSTHROUGH) {
                            latestMultiTrackingData=result
                            latestTrackingData=result.toLegacyFaceTrackingData()
                            // Starting the camera clears tracking. Keep a failed
                            // model load visible instead of erasing its status.
                            if(result.status!=com.oculus.camerademo.ml.FaceTrackingStatus.UNAVAILABLE)checkPermissionsAndStart()
                        }
                    }
                } else mainHandler.post {detectorRetrying=false}
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            detectorRetrying=false
        }
    }

    @Composable
    private fun LauncherContent() {
        if(presentationMode==PresentationMode.LIVE_MIRROR) {
            MirrorScreen(selectedPersona,{selectPreset(it)},onPassthrough={changePresentation(PresentationMode.PASSTHROUGH)},
                onHome={openMirrorHome()},onRecenter={resetMirrorView()},
                onShare={connectAvatarWebcam()},onStopSharing={avatarLanSender.stop();MirrorState.sharing=false},
                onForgetPairing={forgetAvatarWebcamPairing()},menuState=launcher,onMenuState={navigateLauncher(it)},
                photoFaces=photoFaces,onPickPhoto={launchFilePicker()},interaction=spatialInteraction)
        } else if(isMenuVisible) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                androidx.compose.material3.Text("AlterEgo · Passthrough",
                    color=QuickMenuColors.accent,fontSize=20.sp,
                    modifier=Modifier.align(Alignment.TopStart).padding(start=24.dp,top=16.dp))
                QuickLauncher(launcher,{navigateLauncher(it)},Modifier.widthIn(max=720.dp).padding(12.dp),spatialInteraction,
                    looks={
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            QuickAction("My Horizon avatar",{openRegularAvatar(false)},modifier=Modifier.weight(1f),
                                interaction=spatialInteraction,id="own-avatar")
                            QuickAction("Meta sample",{openRegularAvatar(true)},modifier=Modifier.weight(1f),
                                interaction=spatialInteraction,id="sample-avatar")
                        }
                        QuickFaces(selectedPersona,selectedPhoto==null,{selectPreset(it)},photoFaces,selectedPhoto?.entry?.id,
                            {selectPhoto(it)},{navigateLauncher(launcher.show(QuickMenuPage.IMPORT))},interaction=spatialInteraction,
                            passthrough=true)
                        QuickAction("Live mirror panel",{liveMirrorPanel=true;changePresentation(PresentationMode.LIVE_MIRROR)
                            if(MirrorState.avatarSource==MirrorAvatarSource.META_SAMPLE)horizonAccount.showSample()},interaction=spatialInteraction,id="live-avatar")
                    },
                    link={
                        androidx.compose.material3.Text("Share your mirror avatar as a Windows webcam.")
                        QuickAction("Open My mirror to pair PC",{openMirrorHome()},primary=true,interaction=spatialInteraction,id="open-link")
                    },
                    importFace={QuickImport(photoFaces,{launchFilePicker()},
                        {navigateLauncher(launcher.show(QuickMenuPage.FACES))},spatialInteraction)},
                    settings={
                        androidx.compose.material3.Text("Passthrough faces")
                        QuickAction(if(isPartyModeEnabled)"All people: on" else "All people: off",{isPartyModeEnabled=!isPartyModeEnabled},interaction=spatialInteraction,id="party")
                        QuickAction(if(isPartyDiversePersonas)"Different faces: on" else "Different faces: off",{isPartyDiversePersonas=!isPartyDiversePersonas},interaction=spatialInteraction,id="diverse")
                        QuickAction(if(isVoiceSpeakerEnabled)"Voice announcements: on" else "Voice announcements: off",{
                            isVoiceSpeakerEnabled=!isVoiceSpeakerEnabled;faceVoiceSpeaker.isVoiceEnabled=isVoiceSpeakerEnabled},interaction=spatialInteraction,id="voice")
                        com.oculus.camerademo.renderer.ResolutionQuality.values().forEach {quality->
                            QuickAction((if(resolutionQuality==quality)"✓ " else "")+quality.name,{resolutionQuality=quality},interaction=spatialInteraction,id="render-${quality.name}")}
                        androidx.compose.material3.Text("Face size")
                        androidx.compose.material3.Slider(headScale,{headScale=it},valueRange=.6f..2.5f)
                        androidx.compose.material3.Text("Face opacity")
                        androidx.compose.material3.Slider(morphAlpha,{morphAlpha=it},valueRange=.2f..1f)
                        QuickAction(if(isHairPhysicsEnabled)"Hair: on" else "Hair: off",{isHairPhysicsEnabled=!isHairPhysicsEnabled},interaction=spatialInteraction,id="hair")
                        QuickAction(if(showGazeIndicatorRing)"Target marker: on" else "Target marker: off",{showGazeIndicatorRing=!showGazeIndicatorRing},interaction=spatialInteraction,id="target-marker")
                        QuickAction("Reset view",{resetMirrorView()},interaction=spatialInteraction,id="centre-panels")
                        androidx.compose.material3.Text("${targetingMode.label} selects faces. Eye tracking is optional.",color=QuickMenuColors.muted)
                    },onReturnToMirror={openMirrorHome()},onResetView={resetMirrorView()},
                    status={PassthroughDetectionStatus()})
                androidx.compose.material3.Text("Reset view brings this window in front of you",
                    color=QuickMenuColors.muted,fontSize=14.sp,
                    modifier=Modifier.align(Alignment.BottomCenter).padding(bottom=12.dp))
            }
        }
    }

    private fun openRegularAvatar(sample: Boolean) {
        stopLiveTracking()
        startActivity(Intent(this,MirrorActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("avatar_camera_sample",sample)
            .putExtra("avatar_camera_horizon",!sample))
        finishAndRemoveTask()
    }

    private fun importPersona(persona: DynamicPersona) {
        if (availablePersonas.none { it.id == persona.id }) {
            availablePersonas.add(persona)
        }
        selectedDynamicPersona = persona
        persona.builtinPersona?.let { selectedPersona = it }
        if (persona.defaultStickers.isNotEmpty()) {
            activeStickers = activeStickers + persona.defaultStickers
        }
        val msg = "✨ Active: ${persona.displayName} (${persona.source.label})"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun toggleSticker(sticker: Sticker3DType) {
        activeStickers = if (activeStickers.contains(sticker)) {
            activeStickers - sticker
        } else {
            activeStickers + sticker
        }
        val action = if (activeStickers.contains(sticker)) "Equipped" else "Removed"
        Toast.makeText(this, "✨ $action 3D Sticker: ${sticker.displayName}", Toast.LENGTH_SHORT).show()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun registerFeatures(): List<SpatialFeature> {
        return listOf(
            VRFeature(this, handMicrogestureLocomotion=false),
            ComposeFeature()      // Enables Spatial Jetpack Compose Panels
        )
    }

    override fun registerPanels(): List<PanelRegistration> {
        return listOf(
            ComposeViewPanelRegistration(
                registrationId = MENU_PANEL_ID,
                composeViewCreator = { _, ctx ->
                    ComposeView(ctx).apply { setContent { CameraDemoTheme {
                        Surface(Modifier.fillMaxSize(),shape=RoundedCornerShape(24.dp),
                            color=QuickMenuColors.panel,border=BorderStroke(2.dp,QuickMenuColors.accent)) {
                            LauncherContent()
                        }
                    } } }
                },
                settingsCreator = { _ -> UIPanelSettings(
                    shape = QuadShapeOptions(width = FlatPanelPlacement.WINDOW_WIDTH, height = FlatPanelPlacement.WINDOW_HEIGHT),
                    display = DpDisplayOptions(width = 940f, height = 680f, dpi = 160)) }
            ),

            ComposeViewPanelRegistration(
                registrationId = MIRROR_PANEL_ID,
                composeViewCreator = { _, ctx ->
                    ComposeView(ctx).apply {
                        setContent {
                            CameraDemoTheme {
                                if (isMirrorVisible && presentationMode == PresentationMode.PASSTHROUGH) {
                                    MagicMirrorView(
                                        selectedPersona = selectedPersona,
                                        trackingData = mirrorTrackingData,
                                        morphAlpha = morphAlpha,
                                        availablePersonas = availablePersonas,
                                        selectedDynamicPersona = selectedDynamicPersona,
                                        activeStickers = activeStickers,
                                        spatialInteraction = spatialInteraction,
                                        onPersonaSelected = { persona ->
                                            selectedPersona = persona
                                            selectedDynamicPersona = DynamicPersona.fromBuiltin(persona)
                                        },
                                        onDynamicPersonaSelected = { dynPersona ->
                                            importPersona(dynPersona)
                                        },
                                        onOpenAddFace = {
                                            isMenuVisible = true
                                            isAddFaceDialogOpen = true
                                        },
                                        onMorphAlphaChanged = { alpha ->
                                            morphAlpha = alpha
                                        },
                                        onTakeSnapshot = {
                                            Toast.makeText(this@MainActivity, "Photo capture is not available in this build.", Toast.LENGTH_SHORT).show()
                                        },
                                        onCloseMirror = {
                                            toggleMirror(false)
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                settingsCreator = { _ ->
                    UIPanelSettings(
                        shape = QuadShapeOptions(width = 0.50f, height = 0.70f),
                        display = DpDisplayOptions(width = 500f, height = 700f, dpi = 160)
                    )
                }
            ),
            ComposeViewPanelRegistration(
                registrationId = FACE_MORPH_PANEL_ID,
                composeViewCreator = { _, ctx ->
                    ComposeView(ctx).apply {
                        setContent {
                            CameraDemoTheme {
                                FaceMorphAROverlay(
                                    persona = selectedPersona,
                                    dynamicPersona = selectedDynamicPersona,
                                    activeStickers = activeStickers,
                                    trackingData = morphTrackingData,
                                    morphAlpha = morphAlpha,
                                    headScale = headScale
                                )
                            }
                        }
                    }
                },
                settingsCreator = { _ ->
                    UIPanelSettings(
                        shape = QuadShapeOptions(width = 0.48f, height = 0.58f),
                        display = DpDisplayOptions(width = 480f, height = 580f, dpi = 160)
                    )
                }
            ),
            ComposeViewPanelRegistration(
                registrationId = DOCK_PANEL_ID,
                composeViewCreator = { _, ctx -> ComposeView(ctx).apply {setContent {CameraDemoTheme {
                    PassthroughControlDock(
                        selectedPhoto?.entry?.name ?: selectedPersona.displayName,
                        onMenu={openMenuInFrontOfUser("hand-button")},
                        onResetView={resetMirrorView()}, onBackToMirror={openMirrorHome()},
                        onInput={Log.i("AlterEgoMenu", "Dock Compose $it")})
                }}} },
                settingsCreator = { _ -> UIPanelSettings(
                    shape = QuadShapeOptions(width = FlatPanelPlacement.DOCK_WIDTH, height = FlatPanelPlacement.DOCK_HEIGHT),
                    display = DpDisplayOptions(width = 1050f, height = 160f, dpi = 160)) }
            ),

            ComposeViewPanelRegistration(
                registrationId = FACE_MORPH_PANEL_ID_2,
                composeViewCreator = { _, ctx ->
                    ComposeView(ctx).apply {
                        setContent {
                            CameraDemoTheme {
                                val secondPersona = if (isPartyDiversePersonas) HumanPersona.next(selectedPersona) else selectedPersona
                                FaceMorphAROverlay(
                                    persona = secondPersona,
                                    dynamicPersona = null,
                                    activeStickers = activeStickers,
                                    trackingData = secondMorphTrackingData,
                                    morphAlpha = morphAlpha,
                                    headScale = headScale
                                )
                            }
                        }
                    }
                },
                settingsCreator = { _ ->
                    UIPanelSettings(
                        shape = QuadShapeOptions(width = 0.48f, height = 0.58f),
                        display = DpDisplayOptions(width = 480f, height = 580f, dpi = 160)
                    )
                }
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handPerformance = HandPerformanceLog(this)
        systemManager.registerEarlySystem(ImmersiveInputSystem(
            focused={isSceneReady && trackingInputFocused && headsetMounted && !isAppPaused},
            menuGate=menuPress, backGate=backPress, mirrorGate=mirrorPress,
            onMenu={openMenuInFrontOfUser(it)},
            onBack={mainHandler.post {handleImmersiveBack()}},
            onMirror={toggleMirror()}, performance=handPerformance))
        val portraitAssets = applicationContext.assets
        AuthoredPortraitStore.installLoader { path -> portraitAssets.open(path) }

        // Initialize Face Landmark Detector with Multi-Head Object Tracking (runs on background executor)
        faceDetector = FaceLandmarkDetector(
            context = this,
            onFaceDetected = null,
            onMultiFaceDetected = null
        )
        detectorExecutor.execute { if (!destroyed) faceDetector.init() }

        // Initialize Eye Tracking Manager (sensors will be started safely when scene is ready)
        eyeTrackingManager = EyeTrackingManager(this)
        wearerTracking = WearerTrackingManager(this)
        AvatarPairingStore(this).restore()
        avatarLanSender = AvatarLanSender(this) { message, sharing ->
            val statusGeneration = avatarLanSender.statusGeneration
            mainHandler.post { if (!destroyed && statusGeneration == avatarLanSender.statusGeneration) {
            MirrorState.connection = message
            MirrorState.sharing = sharing
        } } }
        presentationMode = intent.getStringExtra("presentation_mode")?.let { runCatching { PresentationMode.valueOf(it) }.getOrNull() }
            ?: PresentationMode.PASSTHROUGH
        liveMirrorPanel = intent.getBooleanExtra("live_mirror_panel", false)
        MirrorState.cameraMirror = false
        if (presentationMode == PresentationMode.LIVE_MIRROR) {
            mirrorWindowRequested = true
            selectedPersona = HumanPersona.restoreMirror(getSharedPreferences("mirror", MODE_PRIVATE).getString("persona", ""))
        } else {
            selectedPersona = HumanPersona.restore(getSharedPreferences("passthrough", MODE_PRIVATE).getString("persona", ""), HumanPersona.UNICORN_MEMOJI)
        }
        selectedDynamicPersona=DynamicPersona.fromBuiltin(selectedPersona)
        horizonAccount = HorizonAccount(this)
        wearerRenderWorker = WearerAvatarRenderWorker(wearerPainter,mainHandler,onRenderTime={elapsed ->
            if(!destroyed && presentationMode==PresentationMode.LIVE_MIRROR &&
                (MirrorState.avatarSource==MirrorAvatarSource.PRESET || !MirrorState.horizonRendered))
                avatarLanSender.budget.processing(elapsed)
        }) { frame,snapshot,persona,hair,renderMs ->
            val allowed = !destroyed && !isAppPaused && isSceneReady && headsetMounted && trackingSessionVisible &&
                presentationMode==PresentationMode.LIVE_MIRROR && !MirrorState.cameraMirror &&
                selectedPersona==persona && isHairPhysicsEnabled==hair &&
                (MirrorState.avatarSource==MirrorAvatarSource.PRESET || !MirrorState.horizonRendered)
            if(allowed) publishMirrorFrame(frame,snapshot,renderMs,customBody=true)
            allowed
        }
        MirrorControls.bind(this, { resetMirrorView() }, { connectAvatarWebcam() },
            { avatarLanSender.stop(); MirrorState.sharing = false }, { persona ->
                selectedPersona = persona; selectedDynamicPersona = DynamicPersona.fromBuiltin(persona)
                getSharedPreferences("mirror", MODE_PRIVATE).edit().putString("persona", persona.name).apply()
            }, { horizonAccount.connect() }, { horizonAccount.showSample() })
        if (intent.getBooleanExtra("show_meta_sample",false)) horizonAccount.showSample()
        else horizonAccount.connect()

        // Initialize Face Voice Speaker (Android TextToSpeech for Gaze Face Count Announcements)
        faceVoiceSpeaker = FaceVoiceSpeaker(this) { announcement ->
            mainHandler.post {
                lastVoiceAnnouncement = announcement
            }
        }
        checkPermissionsAndStart()
    }

    override fun onSceneReady() {
        super.onSceneReady()
        isSceneReady = true
        // STAGE is room-fixed and does not perform system recentering. Use
        // the SDK's recenterable floor space for the app's flat-panel layout.
        scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
        Log.i("AlterEgoPanels", "Recenterable panel reference configured")

        // The sensor activity leaves the real environment visible behind the mirror window.
        scene.enablePassthrough(true)

        avatarRenderer = SpatialAvatarRenderer(scene)
        headDirectionReticle = HeadDirectionReticle(scene)
        scene.setLightingEnvironment(Vector3(0.55f), Vector3(0.2f), Vector3(0f, -1f, 1f), 1f)

        // 4. Spawn Floating Hand Dock Bar in comfortable lower field of view
        dockEntity = Entity.create(
            listOf(
                Panel(DOCK_PANEL_ID),
                Transform(Pose()),
                Visible(false)
            )
        )

        // 5. Spawn 2D Flat Screen Menu panel floating directly in front of user
        isMenuVisible = true
        menuEntity = Entity.create(
            listOf(
                Panel(MENU_PANEL_ID),
                Transform(Pose()),
                // Stable controls: a hand pinch must select a button, not drag
                // the whole window away. Reset view controls placement.
                Visible(false)
            )
        )

        // Reserved legacy surface, kept hidden. The current live mirror uses
        // the same central window as the launcher (MENU_PANEL_ID).
        mirrorEntity = Entity.create(
            listOf(
                Panel(MIRROR_PANEL_ID),
                Transform(Pose()),
                Visible(false)
            )
        )

        observePanelInput(menuEntity, "menu")
        observePanelInput(dockEntity, "dock")

        onPermissionsGranted()
        if (presentationMode == PresentationMode.LIVE_MIRROR) {
            isMenuVisible = false
            startMirrorLoop()
        }
        recenterAllInFrontOfUser()
    }

    override fun onSessionStateChanged(state: SessionState) {
        super.onSessionStateChanged(state)
        Log.i("AlterEgoWearer", "Sensor session: ${state.name}")
        trackingSessionVisible = state == SessionState.VISIBLE || state == SessionState.FOCUSED
        trackingInputFocused = state == SessionState.FOCUSED
        if(!trackingInputFocused)wearerRenderWorker?.invalidate()
        if (panelFocusRecovery.onFocusChanged(trackingInputFocused, SystemClock.uptimeMillis())) {
            panelReset.request(SystemClock.uptimeMillis(),waitForReferenceChange=false)
            Log.i("AlterEgoPanels", "Refining reset placement after system input focus returned")
        }
        if(!trackingInputFocused){menuPress.focusLost();backPress.focusLost();mirrorPress.focusLost();spatialInteraction.clearFocus();panelReset.invalidateSamples()}
        MirrorState.inputFocused = trackingInputFocused
        if (trackingInputFocused && !panelsPlaced) recenterAllInFrontOfUser()
        if (trackingSessionVisible && mirrorWindowRequested && !mirrorWindowLaunched) {
            mainHandler.post { showMirrorWindow() }
        }
        if (!trackingSessionVisible) {
            handPerformance?.suspend("session-hidden")
            MirrorState.active = false
            if (::avatarLanSender.isInitialized) { avatarLanSender.stop(); MirrorState.sharing = false }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra("presentation_mode")?.let { mode ->
            runCatching { PresentationMode.valueOf(mode) }.getOrNull()?.let {
                MirrorState.cameraMirror = false
                liveMirrorPanel = intent.getBooleanExtra("live_mirror_panel", false)
                changePresentation(it)
                if (intent.getBooleanExtra("show_meta_sample",false)) horizonAccount.showSample()
            }
        }
    }

    private val panelReset = FlatPanelReset()
    private val panelFocusRecovery = PanelFocusRecovery()
    private var panelsPlaced = false

    override fun onRecenter(isUserInitiated: Boolean) {
        super.onRecenter(isUserInitiated)
        viewerHistory.clear()
        if (::faceDetector.isInitialized) faceDetector.clearTracking()
        faceTargeting.reset()
        // Quick Settings pauses the activity before dispatching Reset view.
        // Retain the request and apply it to fresh scene frames after resume.
        panelFocusRecovery.request(SystemClock.uptimeMillis())
        recoverPanelView("system-reset",revealMenu=false,waitForReferenceChange=true,
            resetContent=isUserInitiated)
        Log.i("AlterEgoPanels", "System panel reset requested; userInitiated=$isUserInitiated")
    }

    override fun onHMDMounted() {
        super.onHMDMounted()
        headsetMounted = true
        if (!panelReset.pending) recenterAllInFrontOfUser()
    }

    override fun onHMDUnmounted() {
        headsetMounted = false
        wearerRenderWorker?.invalidate()
        handPerformance?.suspend("unmounted")
        panelReset.invalidateSamples()
        MirrorState.inputFocused = false
        MirrorState.active = false
        if (::avatarLanSender.isInitialized) { avatarLanSender.stop(); MirrorState.sharing = false }
        super.onHMDUnmounted()
    }

    private fun startMirrorLoop() {
        mainHandler.removeCallbacks(mirrorLoop)
        mainHandler.post(mirrorLoop)
    }

    private fun updateMirrorFrame() {
        if (MirrorState.cameraMirror) return
        if (!MirrorTrackingPolicy.canSample(isSceneReady, trackingSessionVisible && headsetMounted,
                presentationMode == PresentationMode.LIVE_MIRROR, !isAppPaused, MirrorState.windowVisible)) {
            if (MirrorState.active) {
                MirrorState.active = false
                MirrorState.tracking = WearerSnapshot(message = "Wearer tracking is paused")
                avatarLanSender.stop(); MirrorState.sharing = false
            }
            return
        }
        eyeTrackingManager.updateFromScene(scene)
        var snapshot = wearerTracking.update(scene)
        val viewer = try { scene.getViewerPose() } catch (_: Throwable) { null }
        val gaze = eyeTrackingManager.gazeData
        if (trackingInputFocused && viewer != null && gaze.isValid && gaze.worldRay != null) {
            val direction = gaze.worldRay.direction
            val localGaze = viewer.q.inverse() * Vector3(direction.x, direction.y, direction.z)
            snapshot = snapshot.copy(hasEyes = true, gazeX = localGaze.x, gazeY = localGaze.y)
        }
        if (!trackingInputFocused) {
            val packet = snapshot.nativePacket?.copyOf()?.apply { for (i in 1..4) this[i] = 0f }
            snapshot = snapshot.copy(body = List(18) { null }, hands = List(2) { List(26) { null } },
                expression = com.oculus.camerademo.ml.FaceEmotionState(), hasBody = false, hasPhysicalFace = false,
                hasEyes = false, message = "Head tracking only · Choose Animate mirror for live movement", nativePacket = packet)
        }
        val frameStarted = SystemClock.uptimeMillis()
        avatarLanSender.budget.quality = MirrorState.videoQuality
        val horizonFrame = horizonAccount.draw(snapshot)
        if (horizonFrame != null) {
            wearerRenderWorker?.invalidate()
            publishMirrorFrame(horizonFrame,snapshot,SystemClock.uptimeMillis()-frameStarted)
        } else {
            MirrorState.horizonRendered = false
            wearerRenderWorker?.offer(snapshot,selectedPersona,isHairPhysicsEnabled,MirrorState.videoQuality)
        }
    }

    private fun publishMirrorFrame(frame: android.graphics.Bitmap,snapshot: WearerSnapshot,
                                   renderMs: Long,customBody: Boolean = false) {
        if(!customBody)avatarLanSender.budget.processing(renderMs)
        avatarLanSender.budget.thermalStatus = getSystemService(android.os.PowerManager::class.java).currentThermalStatus
        // The worker transfers this bitmap only once. A LAN error must not make
        // its owner recycle pixels already published to the mirror UI.
        try { avatarLanSender.offer(frame,snapshot.sampledAtMs) }
        catch(error:Exception) { Log.w(TAG,"Mirror video publication unavailable",error) }
        MirrorState.tracking = if(customBody && !snapshot.hasBody) snapshot.copy(
            message="3D upper body · Neutral pose where body tracking is unavailable") else snapshot
        MirrorState.frame = frame
        MirrorState.active = snapshot.isFresh(SystemClock.uptimeMillis())
    }

    override fun onSceneTick() {
        val frameStartedNs = SystemClock.elapsedRealtimeNanos()
        super.onSceneTick()
        val sdkFinishedNs = SystemClock.elapsedRealtimeNanos()
        try {
            updateSceneFrame()
        } finally {
            var flags = 0
            if (presentationMode == PresentationMode.LIVE_MIRROR) flags = flags or SceneFrameWindow.LIVE_MIRROR
            if (cameraReady && cameraFailure == null) flags = flags or SceneFrameWindow.CAMERA
            if (isMenuVisible) flags = flags or SceneFrameWindow.MENU_VISIBLE
            if (trackingInputFocused) flags = flags or SceneFrameWindow.FOCUSED
            handPerformance?.frame(frameStartedNs, sdkFinishedNs, SystemClock.elapsedRealtimeNanos(),
                isSceneReady && !isAppPaused && trackingSessionVisible && headsetMounted, flags)
        }
    }

    private fun updateSceneFrame() {
        if (!isSceneReady || isAppPaused) return
        val viewer = try { scene.getViewerPose() } catch (_: Throwable) { null }
        // Rendering/repositioning is valid while VISIBLE; it must not wait for
        // controller focus to return after the system's Reset View overlay.
        panelReset.sample(SystemClock.uptimeMillis(), trackingSessionVisible && headsetMounted, viewer)?.let {
            placeFlatPanels(it)
        }

        val now = SystemClock.uptimeMillis()
        if (presentationMode == PresentationMode.LIVE_MIRROR) {
            // The mirror loop already samples its eye/face/body inputs.
            avatarRenderer?.hideAll()
            headDirectionReticle?.render(viewer, false, false)
            return
        }
        val outwardAvailable = cameraReady && cameraFailure == null
        val trackingUiDue = now - lastTrackingUiTime >= 33L
        if (hadOutwardFrames && !outwardAvailable) {
            avatarRenderer?.hideAll()
            headDirectionReticle?.render(viewer, false, false)
            viewerHistory.clear()
            faceTargeting.reset()
            hadOutwardFrames = false
            gazeLockedHeadId = null
            isFaceInGazeState = false
        }
        // SDK hands and buttons still run at display rate. Without an outward
        // camera, only the visible menu's 30 Hz gaze overlay needs app tracking.
        if (!outwardAvailable && (!isMenuVisible || !trackingUiDue)) return
        if (outwardAvailable && viewer != null) viewerHistory.record(now, viewer)
        eyeTrackingManager.updateFromScene(scene)
        faceTargeting.update(eyeTrackingManager.gazeData, FaceTargetingController.headRay(viewer, now), now)
        if (outwardAvailable) {
            hadOutwardFrames = true
            updateAvatarPosition(faceDetector.latestMultiTrackingData)
            headDirectionReticle?.render(viewer,
                showGazeIndicatorRing && !isMenuVisible &&
                    faceTargeting.sample.mode == TargetingMode.HEAD_DIRECTION && faceTargeting.sample.isValid,
                gazeLockedHeadId != null)
        }

        if (trackingUiDue) {
            lastTrackingUiTime = now
            val gaze = eyeTrackingManager.gazeData
            val pointerSample = faceTargeting.sample
            val panelPose = try { menuEntity?.getComponent<Transform>()?.transform } catch (_: Throwable) { null }
            val panelPoint = if (isMenuVisible && panelPose != null) {
                faceTargeting.projectOnPanel(panelPose, 0.94f, 0.68f, now)
            } else null
            mainHandler.post {
                if (!destroyed && !isAppPaused && isSceneReady) {
                    eyeTrackingStatus = gaze.status
                    targetingMode = pointerSample.mode
                    if (panelPoint != null && trackingInputFocused && isMenuVisible) {
                        spatialInteraction.updatePointer(panelPoint.first, panelPoint.second, 940f, 680f,
                            if (pointerSample.mode == TargetingMode.EYE_TRACKING) "EYE_PINCH" else "HEAD_DIRECTION")
                    } else spatialInteraction.clearGaze()
                }
            }
        }

        // Keep the recovered window anchored between resets/app returns.
    }

    private fun recenterAllInFrontOfUser() {
        if (!destroyed) panelReset.request(SystemClock.uptimeMillis(),waitForReferenceChange=false)
    }

    private fun resetMirrorView() {
        recoverPanelView("reset-button",revealMenu=false,waitForReferenceChange=false,resetContent=true)
    }

    private fun recoverPanelView(source: String,revealMenu: Boolean,waitForReferenceChange: Boolean,
                                 resetContent: Boolean = false) {
        mainHandler.post {
            if(destroyed || isFinishing)return@post
            // A newer focused recovery supersedes an older OS refinement. Keep
            // it while the system still owns focus and may change coordinates.
            if(!waitForReferenceChange && trackingInputFocused)panelFocusRecovery.clear()
            if(resetContent)launcher=launcher.recoverWindow(presentationMode==PresentationMode.LIVE_MIRROR)
            else if(revealMenu)launcher=launcher.show()
            spatialInteraction.clearFocus()
            wearerRenderWorker?.invalidate(recenter=true) ?: wearerPainter.recenter()
            if(::horizonAccount.isInitialized)horizonAccount.recenter()
            panelReset.request(SystemClock.uptimeMillis(),waitForReferenceChange)
            if(isSceneReady && !isAppPaused)updateFlatPanelVisibility()
            Log.i("AlterEgoMenu","Recover requested source=$source mode=$presentationMode menu=$isMenuVisible placed=$panelsPlaced focused=$trackingInputFocused")
        }
    }

    private fun updateFlatPanelVisibility() {
        // Repositioning must never hide an otherwise usable menu or depend on
        // outward camera frames. Keep its last valid placement during tracking gaps.
        menuEntity?.setComponent(Visible(panelsPlaced &&
            (if(presentationMode==PresentationMode.LIVE_MIRROR)liveMirrorPanel else isMenuVisible)))
        dockEntity?.setComponent(Visible(panelsPlaced &&
            (presentationMode == PresentationMode.PASSTHROUGH || liveMirrorPanel)))
    }

    private fun placeFlatPanels(layout: FlatPanelLayout) {
        try {
            menuEntity?.setComponent(Transform(layout.menu))
            dockEntity?.setComponent(Transform(layout.dock))
            mirrorEntity?.setComponent(Transform(layout.mirror))
            // Reset also restores a readable default size after any earlier resize.
            menuEntity?.setComponent(Scale(Vector3(1f)))
            dockEntity?.setComponent(Scale(Vector3(1f)))
            mirrorEntity?.setComponent(Scale(Vector3(1f)))
            panelsPlaced = true
            updateFlatPanelVisibility()
            Log.i("AlterEgoPanels", "Window reset on current sightline; mode=$presentationMode menu=$isMenuVisible distance=${FlatPanelPlacement.WINDOW_DISTANCE}m front=-Z")
        } catch (e: Throwable) {
            recenterAllInFrontOfUser()
            Log.w(TAG, "Flat panel placement deferred: ${e.message}")
        }
    }

    private fun openMenuInFrontOfUser(source: String) {
        // Recover the entire layout: moving only the menu leaves the mirror and
        // its controls behind the wearer, requiring another hard-to-reach reset.
        recoverPanelView(source,revealMenu=true,waitForReferenceChange=false)
    }

    private fun observePanelInput(entity: Entity?, label: String) {
        if (entity == null) return
        val input = systemManager.tryFindSystem<IsdkSystem>() ?: return
        val handle = input.registerInteractableObserver(entity) { event ->
            if (event.type != PointerEventType.Move.id) {
                val name = PointerEventType.values().firstOrNull { it.id == event.type }?.name ?: "unknown"
                Log.i("AlterEgoMenu", "Panel $label pointer=$name kind=${event.pointerType}")
            }
        }
        panelInputObservers.add(input to handle)
    }

    private fun clearPanelInputObservers() {
        panelInputObservers.forEach { (input, handle) -> input.unregisterInteractableObserver(handle) }
        panelInputObservers.clear()
    }

    private fun toggleMirror(visible: Boolean? = null) {
        val show = visible ?: (presentationMode != PresentationMode.LIVE_MIRROR)
        mainHandler.post {
            // A mirror requested inside passthrough must remain a recenterable
            // immersive panel. Exit passthrough owns the regular-window handoff.
            liveMirrorPanel = show
            changePresentation(if (show) PresentationMode.LIVE_MIRROR else PresentationMode.PASSTHROUGH)
        }
    }

    private fun changePresentation(mode: PresentationMode) {
        panelFocusRecovery.clear()
        wearerRenderWorker?.invalidate(recenter=true)
        stopLiveTracking()
        avatarLanSender.stop(); MirrorState.sharing = false; MirrorState.active = false
        wearerTracking.close()
        presentationMode = mode; isMirrorVisible = mode == PresentationMode.LIVE_MIRROR
        // Each view keeps its own face choice. A passthrough photo must not
        // survive as an invisible selection when the live mirror uses a rig.
        photoFaces.cancelSelection();selectedPhoto=null
        val facePrefs=getSharedPreferences(if(mode==PresentationMode.PASSTHROUGH)"passthrough" else "mirror",MODE_PRIVATE)
        selectedPersona=if(mode==PresentationMode.PASSTHROUGH)
            HumanPersona.restore(facePrefs.getString("persona",""),HumanPersona.UNICORN_MEMOJI)
            else HumanPersona.restoreMirror(facePrefs.getString("persona",""))
        selectedDynamicPersona=DynamicPersona.fromBuiltin(selectedPersona)
        if(mode==PresentationMode.PASSTHROUGH)photoFaces.restore(facePrefs.getString("photo_face","") ?: "") {photo ->
            if(presentationMode==PresentationMode.PASSTHROUGH)selectedPhoto=photo
        }
        launcher=launcher.close()
        // Do not expose the prior OS-window-era layout during a mode switch.
        // The first focused scene frames establish the new panel placement.
        panelsPlaced = false
        updateFlatPanelVisibility()
        mirrorWindowRequested = mode == PresentationMode.LIVE_MIRROR
        mirrorWindowLaunched = false
        mainHandler.removeCallbacks(mirrorLoop)
        mirrorEntity?.setComponent(Visible(false))
        if (isSceneReady && !isAppPaused) {
            scene.enablePassthrough(true)
            avatarRenderer?.hideAll()
        }
        recenterAllInFrontOfUser()
        checkPermissionsAndStart()
        if (mode == PresentationMode.LIVE_MIRROR) { startMirrorLoop(); showMirrorWindow() }
    }

    private fun openMirrorHome() {
        if(destroyed || isFinishing || mirrorWindowLaunched)return
        liveMirrorPanel = false
        mirrorWindowRequested = true
        showMirrorWindow(userRequested = true)
    }

    private fun handleImmersiveBack() {
        if(destroyed || isFinishing)return
        if(launcher.backReturnsToMirror)openMirrorHome()
        else navigateLauncher(launcher.back())
    }

    private fun showMirrorWindow(userRequested: Boolean = false) {
        // An explicit exit must work even if camera permission, scene startup or
        // XR tracking never becomes ready. Only automatic handoff waits for it.
        val automaticReady = !liveMirrorPanel && isSceneReady && trackingSessionVisible &&
            mirrorWindowRequested && !permissionsRequested
        if (!MirrorWindowLaunchPolicy.allowed(userRequested,automaticReady,mirrorWindowLaunched,
                destroyed || isFinishing))return
        mirrorWindowLaunched = true
        wearerRenderWorker?.invalidate()
        menuEntity?.setComponent(Visible(false)); dockEntity?.setComponent(Visible(false))
        // The ordinary OS mirror uses the system avatar camera. Close this
        // immersive task so Home can render the avatar without an XR session.
        mainHandler.removeCallbacks(mirrorLoop)
        stopLiveTracking()
        wearerTracking.close()
        avatarLanSender.stop(); MirrorState.sharing = false; MirrorState.active = false
        try {
            startActivity(Intent(this, MirrorActivity::class.java).setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            mirrorWindowLaunched = false; mirrorWindowRequested = false
            launcher = launcher.show()
            if(isSceneReady)updateFlatPanelVisibility()
            Log.e(TAG,"Could not return to the mirror",error)
            Toast.makeText(this,"Could not open the mirror. Try Back again.",Toast.LENGTH_LONG).show()
            return
        }
        finishAndRemoveTask()
    }

    private fun connectAvatarWebcam() {
        try { avatarLanSender.start(MirrorState.endpoint, MirrorState.pairCode); MirrorState.sharing = true
            MirrorState.connection = "Connecting to Windows…"
        } catch (error: Exception) { MirrorState.connection = error.message ?: "Check the PC address and pairing code" }
    }

    private fun forgetAvatarWebcamPairing() {
        avatarLanSender.stop()
        try {
            AvatarPairingStore(this).forget(MirrorState.endpoint)
            MirrorState.connection = "Pairing forgotten · Paste the current secure code displayed on the PC"
        } catch (error: Exception) { MirrorState.connection = error.message ?: "Enter the PC address first" }
    }

    private fun quitApplication() {
        Log.i(TAG, "Quit requested by user. Finishing AlterEgo MR...")
        finishAndRemoveTask()
    }

    private fun updateAvatarPosition(
        multiData: MultiFaceTrackingData = faceDetector.latestMultiTrackingData
    ) {
        if (!isSceneReady || isAppPaused) return
        val now = SystemClock.uptimeMillis()
        val cameraAvailable = cameraReady && cameraFailure == null
        val capturedViewer = if (multiData.poseTimestampReliable) viewerHistory.at(multiData.capturedAtUptimeMs) else null
        val extrinsics = multiData.cameraExtrinsics
        val usable = cameraAvailable && multiData.isFresh(now) && capturedViewer != null && extrinsics != null
        val worldHeads = if (usable) multiData.trackedHeads.map { head ->
            head to SceneCoordinateMapping.cameraHeadToWorld(capturedViewer!!, extrinsics!!, head)
        } else emptyList()
        val focusId = faceTargeting.selectHeads(worldHeads.map { (head, pose) ->
            GazeHeadTarget(head.id, GazeVector(pose.t.x, pose.t.y, pose.t.z),
                GazeVector(0.08f * headScale.coerceIn(0.6f, 2.5f), 0.10f * headScale.coerceIn(0.6f, 2.5f), 0.10f * headScale.coerceIn(0.6f, 2.5f)),
                GazeRotation(pose.q.w, pose.q.x, pose.q.y, pose.q.z))
        }, now).lockedId
        val visibleIds = AvatarVisibilityPolicy.visibleIds(worldHeads.map { it.first.id }, focusId,
            isPartyModeEnabled, cameraAvailable, usable, morphAlpha)
        avatarRenderer?.render(worldHeads, focusId, isPartyModeEnabled, isPartyDiversePersonas,
            selectedPersona, morphAlpha, headScale, isHairPhysicsEnabled, multiData.capturedAtUptimeMs,
            selectedDynamicPersona?.primaryColorHex?.toInt(), visibleIds, resolutionQuality, isFoveatedEnabled,
            faceTargeting.sample.mode == TargetingMode.EYE_TRACKING && faceTargeting.sample.isValid,photo=selectedPhoto)
        val target = worldHeads.firstOrNull { it.first.id == focusId }?.first
        val selected = multiData.copy(trackedHeads = worldHeads.map { it.first },
            totalTrackedHeads = worldHeads.size, gazeLockedHead = target, hasGazeLockedHead = target != null)
        if (now - lastAvatarUiTime < 33L) return
        lastAvatarUiTime = now
        mainHandler.post {
            if (!destroyed && !isAppPaused && isSceneReady) {
                gazeLockedHeadId = focusId
                isFaceInGazeState = focusId != null
                latestMultiTrackingData = selected
                latestTrackingData = selected.toLegacyFaceTrackingData()
                menuTelemetryData = latestTrackingData
                mirrorTrackingData = latestTrackingData
                faceVoiceSpeaker.onFaceGazeEvaluated(focusId != null, focusId, worldHeads.size)
            }
        }
    }

    /**
     * Intercepts all controller and device KeyEvents BEFORE they are consumed by views:
     * - Left Controller Menu Button on Meta Quest:
     *   - KEYCODE_BUTTON_START: Left Controller Menu key
     *   - KEYCODE_MENU (82): Standard Android / Horizon OS Menu key
     *   - KEYCODE_BUTTON_B: Back through submenus, then return to My mirror
     *   - Button X is NEVER used!
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val focused=trackingInputFocused && headsetMounted && !isAppPaused
        val now=event.eventTime
        val down=event.action==KeyEvent.ACTION_DOWN
        when(event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_START,KeyEvent.KEYCODE_MENU -> {
                val canHandle=focused || !isAppPaused
                if(down && event.repeatCount==0)Log.i("AlterEgoMenu","Android Menu code=${event.keyCode} focused=$focused")
                if(menuPress.key(down,event.repeatCount,canHandle,now))openMenuInFrontOfUser("android-key")
                return canHandle || super.dispatchKeyEvent(event)
            }
            KeyEvent.KEYCODE_BUTTON_B,KeyEvent.KEYCODE_BACK -> {
                val canHandle=focused || (event.keyCode==KeyEvent.KEYCODE_BACK && !isAppPaused)
                if(backPress.key(down,event.repeatCount,canHandle,now))handleImmersiveBack()
                if(canHandle || backPress.recentlyAccepted(now))return true
                return super.dispatchKeyEvent(event)
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if(mirrorPress.key(down,event.repeatCount,focused,now))toggleMirror()
                return focused || super.dispatchKeyEvent(event)
            }
        }
        if (down && event.repeatCount == 0 && focused && isMenuVisible) {
            val code = event.keyCode
            when (code) {
                KeyEvent.KEYCODE_BUTTON_A -> {
                    spatialInteraction.triggerSelection()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    spatialInteraction.navigateNext()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    spatialInteraction.navigatePrevious()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if(!destroyed && !isAppPaused)handleImmersiveBack() else super.onBackPressed()
    }

    override fun registerRequiredOpenXRExtensions(): List<String> =
        (super.registerRequiredOpenXRExtensions() +
            (if (EyeTrackingManager.hasEyeTrackingHardware(this)) EyeTrackingManager.OPENXR_EXTENSIONS else emptyList()) +
            WearerTrackingManager.supportedExtensions()).distinct()

    override fun onSpatialShutdown() {
        wearerRenderWorker?.invalidate(recenter=true)
        clearPanelInputObservers()
        isSceneReady = false
        hadOutwardFrames = false
        panelsPlaced = false
        panelReset.clear()
        panelFocusRecovery.clear()
        cameraGeneration++
        cameraReady = false
        activeCameraSource?.stop()
        if (::eyeTrackingManager.isInitialized) eyeTrackingManager.close()
        viewerHistory.clear()
        faceTargeting.reset()
        if (::faceDetector.isInitialized) faceDetector.clearTracking()
        avatarRenderer?.close()
        avatarRenderer = null
        headDirectionReticle?.close()
        headDirectionReticle = null
        if (::wearerTracking.isInitialized) wearerTracking.close()
        MirrorState.active = false
        super.onSpatialShutdown()
    }

    private fun checkPermissionsAndStart() {
        if (destroyed || permissionsRequested) return
        cameraFailure = null
        cameraFailureCode = null
        val hasCameraPermission = listOf(Manifest.permission.CAMERA, "horizonos.permission.HEADSET_CAMERA").any {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val permissions = (if (hasCameraPermission || presentationMode == PresentationMode.LIVE_MIRROR) emptyList() else listOf("horizonos.permission.HEADSET_CAMERA")) +
            eyeTrackingManager.requiredPermissions + listOf("com.oculus.permission.HAND_TRACKING") +
            (if (presentationMode == PresentationMode.LIVE_MIRROR) wearerTracking.requiredPermissions else emptyList())
        val missing = permissions.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionsRequested = true
            requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        } else onPermissionsGranted()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSIONS_REQUEST_CODE) return
        permissionsRequested = false
        if (!hasCameraPermission() && presentationMode == PresentationMode.PASSTHROUGH) {
            cameraFailure = "Allow Headset Camera access to use live face replacement. Eye tracking is optional."
            cameraFailureCode = CameraSourceErrorCode.PERMISSION_DENIED
            eyeTrackingStatus = eyeTrackingManager.checkDeviceCompatibility()
            stopLiveTracking()
            return
        }
        onPermissionsGranted()
    }

    private fun onPermissionsGranted() {
        if (!isSceneReady || isAppPaused || destroyed) return
        if (!hasCameraPermission() && presentationMode == PresentationMode.PASSTHROUGH) {
            cameraFailure = "Allow Headset Camera access to use live face replacement."
            cameraFailureCode = CameraSourceErrorCode.PERMISSION_DENIED
            stopLiveTracking()
            return
        }
        // No eye hardware, permission denial, and runtime loss all allow head-direction targeting.
        eyeTrackingStatus = eyeTrackingManager.start()
        if (presentationMode == PresentationMode.PASSTHROUGH) initCameraSources()
        else mainHandler.post { showMirrorWindow() }
    }

    private fun hasCameraPermission() = listOf(Manifest.permission.CAMERA, "horizonos.permission.HEADSET_CAMERA").any {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun initCameraSources() {
        if (!isSceneReady || isAppPaused || destroyed) return
        val generation = ++cameraGeneration
        activeCameraSource?.stop()
        faceDetector.clearTracking()
        cameraReady = false
        cameraFailure = null
        cameraFailureCode = null
        val source = passthroughSource ?: QuestPassthroughCameraSource(this).also { passthroughSource = it }
        source.canAcceptFrame = { faceDetector.canAcceptFrame }
        source.onError = { error ->
            mainHandler.post {
                if (!destroyed && generation == cameraGeneration && !isAppPaused) {
                    cameraFailure = error.message
                    cameraFailureCode = error.code
                    cameraReady = false
                    faceDetector.clearTracking()
                    isMenuVisible = true
                    updateFlatPanelVisibility()
                }
            }
        }
        val problem = source.availabilityError()
        if (problem != null) {
            Log.w(TAG, problem.message)
            cameraFailure = problem.message
            cameraFailureCode = problem.code
            isMenuVisible = true
            updateFlatPanelVisibility()
            return
        }
        cameraFailure = null
        cameraFailureCode = null
        activeCameraSource = source
        source.start { frame ->
            if (generation == cameraGeneration && !destroyed && !isAppPaused) faceDetector.processFrame(frame)
        }
        cameraReady = source.isRunning
    }

    private fun toggleCameraSource() = checkPermissionsAndStart()

    private fun stopLiveTracking() {
        cameraGeneration++
        cameraReady = false
        activeCameraSource?.stop()
        faceDetector.clearTracking()
        viewerHistory.clear()
        faceTargeting.reset()
        spatialInteraction.clearGaze()
    }

    override fun onPause() {
        isAppPaused = true
        wearerRenderWorker?.invalidate()
        handPerformance?.suspend("paused")
        panelReset.invalidateSamples()
        stopLiveTracking()
        if (!mirrorWindowRequested || presentationMode != PresentationMode.LIVE_MIRROR) {
            eyeTrackingManager.stop()
            wearerTracking.close()
            avatarLanSender.stop(); MirrorState.sharing = false; MirrorState.active = false
        }
        faceVoiceSpeaker.stop()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        isAppPaused = false
        photoFaces.refresh()
        if(presentationMode==PresentationMode.PASSTHROUGH && selectedPhoto==null) {
            photoFaces.restore(getSharedPreferences("passthrough",MODE_PRIVATE).getString("photo_face","") ?: "") {selectedPhoto=it}
        }
        // Returning to the app/headset should not strand its window at an old
        // room position. An outstanding system reset retains its own timing.
        if (!panelReset.pending) recenterAllInFrontOfUser()
        if (isSceneReady && !permissionsRequested) onPermissionsGranted()
        if (presentationMode == PresentationMode.LIVE_MIRROR) startMirrorLoop()
    }

    override fun onDestroy() {
        clearPanelInputObservers()
        destroyed = true
        isAppPaused = true
        wearerRenderWorker?.close(); wearerRenderWorker = null
        handPerformance?.close()
        isSceneReady = false
        activeCameraSource?.stop()
        faceDetector.close()
        eyeTrackingManager.close()
        wearerTracking.close()
        avatarLanSender.close(); MirrorState.sharing = false; MirrorState.active = false
        horizonAccount.close()
        photoFaces.close()
        MirrorControls.unbind(this)
        avatarRenderer?.close()
        headDirectionReticle?.close()
        detectorExecutor.shutdownNow()
        faceVoiceSpeaker.shutdown()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
