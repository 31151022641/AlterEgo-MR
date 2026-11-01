package com.oculus.camerademo.mirror

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.oculus.camerademo.model3d.HumanPersona

enum class PresentationMode { PASSTHROUGH, LIVE_MIRROR }
enum class MirrorAvatarSource { HORIZON, PRESET, META_SAMPLE }

/** Same-process live preview shared by the sensor activity and the OS-managed 2D window. */
object MirrorState {
    var frame by mutableStateOf<Bitmap?>(null)
    var tracking by mutableStateOf(WearerSnapshot())
    var active by mutableStateOf(false)
    var inputFocused by mutableStateOf(false)
    var connection by mutableStateOf("Webcam sharing is off")
    var endpoint by mutableStateOf("")
    var pairCode by mutableStateOf("")
    var sharing by mutableStateOf(false)
    var horizonStatus by mutableStateOf("Your Horizon avatar is not connected yet · App ID configured")
    var horizonConnecting by mutableStateOf(false)
    var avatarSource by mutableStateOf(MirrorAvatarSource.HORIZON)
    var horizonRendered by mutableStateOf(false)
    var cameraMirror by mutableStateOf(false)
    var cameraStatus by mutableStateOf("Opening your Horizon avatar camera…")
    var cameraOverlay by mutableStateOf(false)
    var cameraSample by mutableStateOf(false)
    var cameraZoom by mutableStateOf(1f)
    var nearbyPcs by mutableStateOf<List<DiscoveredPc>>(emptyList())
    var findingPc by mutableStateOf(false)
    var pairingConfirmation by mutableStateOf("")
    var pairingPc by mutableStateOf("")
    var videoQuality by mutableStateOf(AvatarVideoQuality.MAXIMUM)
    var videoFps by mutableStateOf(0)
    var sampleFps by mutableStateOf(0)
    var sampleDetail by mutableStateOf(AvatarSampleDetail.FAST)
    var qualityStatus by mutableStateOf("Maximum detail")
    var sourceResolution by mutableStateOf("Opening camera")
    var outputResolution by mutableStateOf("")
    var faceScale by mutableStateOf(1f)
    var faceOffsetX by mutableStateOf(0f)
    var faceOffsetY by mutableStateOf(0f)
    var protectHands by mutableStateOf(true)
    private var windowOwner by mutableStateOf<Any?>(null)
    val windowVisible get() = windowOwner != null
    fun windowShown(owner: Any) { windowOwner = owner }
    fun windowHidden(owner: Any) { if (windowOwner === owner) windowOwner = null }
}

/** Bound only for the lifetime of the sensor activity. No Activity is held after its destruction. */
object MirrorControls {
    private var owner: Any? = null
    var recenter: (() -> Unit)? = null; private set
    var share: (() -> Unit)? = null; private set
    var stopSharing: (() -> Unit)? = null; private set
    var selectPersona: ((HumanPersona) -> Unit)? = null; private set
    var connectHorizon: (() -> Unit)? = null; private set
    var showMetaSample: (() -> Unit)? = null; private set
    val connected get() = owner != null

    fun bind(owner: Any, recenter: () -> Unit, share: () -> Unit, stopSharing: () -> Unit,
        selectPersona: (HumanPersona) -> Unit, connectHorizon: () -> Unit, showMetaSample: () -> Unit) {
        this.owner = owner; this.recenter = recenter; this.share = share
        this.stopSharing = stopSharing; this.selectPersona = selectPersona
        this.connectHorizon = connectHorizon
        this.showMetaSample = showMetaSample
    }
    fun unbind(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null; recenter = null; share = null; stopSharing = null; selectPersona = null; connectHorizon = null
        showMetaSample = null
    }
}
