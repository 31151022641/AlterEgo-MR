package com.oculus.camerademo.mirror

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.oculus.camerademo.model3d.*
import com.oculus.camerademo.ui.*

@Composable
fun MirrorScreen(persona: HumanPersona, onPersona: (HumanPersona) -> Unit, onPassthrough: () -> Unit,
    onHome: (() -> Unit)? = null, onLive: (() -> Unit)? = null, onRecenter: () -> Unit = {},
    onShare: () -> Unit = {}, onStopSharing: () -> Unit = {}, cameraMode: Boolean = false,
    onCameraHorizon: () -> Unit = {}, onCameraRetry: () -> Unit = {},
    onCameraZoom: (Float) -> Unit = {}, onCameraSample: () -> Unit = {}, onForgetPairing: () -> Unit = {},
    onFindPc: () -> Unit = {}, onPairPc: (DiscoveredPc) -> Unit = {}, onConfirmPc: (Boolean) -> Unit = {},
    onQuality: (AvatarVideoQuality) -> Unit = {}, onAlignment: (Float,Float,Float) -> Unit = { _,_,_ -> },
    onProtectHands: (Boolean) -> Unit = {}, onSampleDetail: (AvatarSampleDetail) -> Unit = {},
    menuState: QuickMenuNavigation? = null,onMenuState: (QuickMenuNavigation)->Unit = {},
    photoFaces: PhotoFaceImport?=null,selectedPhoto: PhotoFace?=null,onPhoto: (PhotoFaceEntry)->Unit={},
    onPickPhoto: ()->Unit={},interaction: SpatialMenuInteraction?=null) {
    var internalMenu by remember { mutableStateOf(QuickMenuNavigation()) }
    val menu=menuState ?: internalMenu
    val navigate: (QuickMenuNavigation)->Unit={if(menuState==null)internalMenu=it else onMenuState(it)}
    if (MirrorState.pairingConfirmation.isNotEmpty()) AlertDialog(
        onDismissRequest={onConfirmPc(false)},title={Text("Pair with ${MirrorState.pairingPc}")},
        text={Column {Text(MirrorState.pairingConfirmation,style=MaterialTheme.typography.headlineMedium)
            Text("Compare this exact code with the PC. Approve on both devices only if the codes match.")}},
        confirmButton={TextButton({onConfirmPc(true)}){Text("Codes match")}},
        dismissButton={TextButton({onConfirmPc(false)}){Text("Cancel")}})
    val avatarLabel=when {
        selectedPhoto!=null -> "${selectedPhoto.entry.name} · Photo mask"
        MirrorState.avatarSource==MirrorAvatarSource.META_SAMPLE -> "Meta sample"
        MirrorState.avatarSource==MirrorAvatarSource.PRESET -> persona.displayName
        else -> "My Horizon avatar"
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF101925)).imePadding()) {
        Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                Text("AlterEgo MR · My mirror",color=QuickMenuColors.ink,modifier=Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                if(!cameraMode)QuickAction("Reset view",onRecenter)
                QuickAction("Passthrough",onPassthrough)
            }
            Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
                MirrorState.frame?.let {Image(it.asImageBitmap(),avatarLabel,Modifier.fillMaxSize()
                    .graphicsLayer(scaleX=if(cameraMode)-1f else 1f))}
                    ?: Text(if(cameraMode)"Waiting for your avatar camera…" else "Waiting for your avatar…",color=QuickMenuColors.muted)
            }
            Text(avatarLabel,color=QuickMenuColors.ink)
            Text(if(MirrorState.active)"${if(MirrorState.sharing)"Webcam sharing · " else ""}${if(cameraMode)MirrorState.cameraStatus else MirrorState.tracking.message}"
                else if(cameraMode)MirrorState.cameraStatus else "Tracking paused",color=QuickMenuColors.muted,
                style=MaterialTheme.typography.bodySmall,maxLines=2)
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                QuickAction("☰ Menu",{navigate(menu.toggle())},primary=true)
                if(MirrorState.sharing)QuickAction("Stop sharing",onStopSharing)
            }
        }
        if(menu.open) Box(Modifier.fillMaxSize().padding(12.dp),contentAlignment=Alignment.Center) {
            QuickLauncher(menu,navigate,interaction=interaction,
                looks={
                    Text("Choose the look shown in your mirror.")
                    val horizonSelected=selectedPhoto==null && MirrorState.avatarSource==MirrorAvatarSource.HORIZON
                    val sampleSelected=selectedPhoto==null && MirrorState.avatarSource==MirrorAvatarSource.META_SAMPLE
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        QuickAction((if(horizonSelected)"✓ " else "")+"My Horizon avatar",
                            {if(cameraMode)onCameraHorizon() else MirrorControls.connectHorizon?.invoke()},
                            modifier=Modifier.weight(1f),primary=horizonSelected,
                            enabled=cameraMode || MirrorControls.connected,interaction=interaction,id="horizon")
                        QuickAction((if(sampleSelected)"✓ " else "")+"Meta sample",
                            {if(cameraMode)onCameraSample() else MirrorControls.showMetaSample?.invoke()},
                            modifier=Modifier.weight(1f),primary=sampleSelected,
                            enabled=cameraMode || MirrorControls.connected,interaction=interaction,id="sample")
                    }
                    if(cameraMode && MirrorState.avatarSource==MirrorAvatarSource.META_SAMPLE)
                        Text("Regular 2D sample movement is experimental. Live mirror uses direct headset tracking.",color=QuickMenuColors.muted)
                    QuickFaces(persona,MirrorState.avatarSource==MirrorAvatarSource.PRESET && selectedPhoto==null,
                        {MirrorState.avatarSource=MirrorAvatarSource.PRESET;MirrorState.horizonRendered=false;onPersona(it)},
                        photoFaces,selectedPhoto?.entry?.id,onPhoto,
                        {navigate(menu.show(QuickMenuPage.IMPORT))},photoAllowed=cameraMode,interaction=interaction)
                    HorizontalDivider(color=Color(0xFF344858))
                    Text("View controls",style=MaterialTheme.typography.titleMedium)
                    if(cameraMode) {
                        Text("Framing",color=QuickMenuColors.muted)
                        Slider(MirrorState.cameraZoom,onCameraZoom,valueRange=1f..2.2f,
                            enabled=MirrorState.avatarSource!=MirrorAvatarSource.META_SAMPLE)
                        QuickAction("Retry camera",onCameraRetry,interaction=interaction,id="retry-camera")
                    }
                    QuickAction("Centre view",onRecenter,interaction=interaction,id="centre")
                    onLive?.let {QuickAction(if(selectedPhoto==null)"Open live mirror panel" else "Live panel does not support photo masks",
                        it,enabled=selectedPhoto==null,interaction=interaction,id="live-panel")}
                    onHome?.let {QuickAction("Open regular mirror window",it,interaction=interaction,id="regular-window")}
                },
                link={MirrorLinkPage(cameraMode,onShare,onStopSharing,onFindPc,onPairPc,onForgetPairing,interaction)},
                importFace={QuickImport(photoFaces,onPickPhoto,{navigate(menu.show(QuickMenuPage.FACES))},interaction)},
                settings={MirrorQualityPage(cameraMode,onQuality,onSampleDetail,onAlignment,onProtectHands,interaction)})
        }
    }
}

@Composable
fun MirrorLinkPage(cameraMode: Boolean,onShare: ()->Unit,onStopSharing: ()->Unit,onFindPc: ()->Unit,
    onPairPc: (DiscoveredPc)->Unit,onForgetPairing: ()->Unit,interaction: SpatialMenuInteraction?=null) {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    Text("PC webcam",style=MaterialTheme.typography.titleMedium)
    Text(MirrorState.connection,color=QuickMenuColors.muted)
    if(cameraMode) {
        QuickAction(if(MirrorState.findingPc)"Finding PCs…" else "Find PC",onFindPc,
            enabled=!MirrorState.sharing && !MirrorState.findingPc,interaction=interaction,id="find-pc")
        MirrorState.nearbyPcs.forEach {pc->QuickAction("Pair with ${pc.name}",{onPairPc(pc)},
            enabled=!MirrorState.sharing,interaction=interaction,id="pc-${pc.endpoint.key}")}
    }
    var manual by remember {mutableStateOf(false)}
    QuickAction(if(manual)"Hide manual pairing" else "Enter address and code",{manual=!manual},interaction=interaction,id="manual-pairing")
    if(manual || MirrorState.endpoint.isNotBlank()) {
        OutlinedTextField(MirrorState.endpoint,{MirrorState.endpoint=it},label={Text("PC address",color=QuickMenuColors.muted)},
            modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!MirrorState.sharing,
            colors=OutlinedTextFieldDefaults.colors(focusedTextColor=QuickMenuColors.ink,unfocusedTextColor=QuickMenuColors.ink))
        if(manual)OutlinedTextField(MirrorState.pairCode,{MirrorState.pairCode=AvatarPairing.filterInput(it)},
            label={Text("Full secure code from PC",color=QuickMenuColors.muted)},modifier=Modifier.fillMaxWidth(),
            singleLine=true,enabled=!MirrorState.sharing,
            visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),
            colors=OutlinedTextFieldDefaults.colors(focusedTextColor=QuickMenuColors.ink,unfocusedTextColor=QuickMenuColors.ink))
    }
    QuickAction(if(MirrorState.sharing)"Stop sharing" else "Start webcam",{
        focus.clearFocus();keyboard?.hide();if(MirrorState.sharing)onStopSharing() else onShare()
    },primary=true,enabled=MirrorState.active || MirrorState.sharing,interaction=interaction,id="share-webcam")
    if(!MirrorState.active)Text("Keep the headset on with a live mirror image before sharing.",color=QuickMenuColors.muted)
    QuickAction("Forget pairing",onForgetPairing,enabled=MirrorState.endpoint.isNotBlank(),interaction=interaction,id="forget-pairing")
}

@Composable
private fun MirrorQualityPage(cameraMode: Boolean,onQuality: (AvatarVideoQuality)->Unit,
    onSampleDetail: (AvatarSampleDetail)->Unit,onAlignment: (Float,Float,Float)->Unit,
    onProtectHands: (Boolean)->Unit,interaction: SpatialMenuInteraction?) {
    Text("Picture quality",style=MaterialTheme.typography.titleMedium)
    AvatarVideoQuality.values().forEach {quality->QuickAction(
        (if(MirrorState.videoQuality==quality)"✓ " else "")+quality.label,{onQuality(quality)},
        enabled=cameraMode,interaction=interaction,id="quality-${quality.name}")}
    if(cameraMode && MirrorState.avatarSource==MirrorAvatarSource.META_SAMPLE) {
        Text("Sample movement",style=MaterialTheme.typography.titleMedium)
        AvatarSampleDetail.values().forEach {detail->QuickAction((if(MirrorState.sampleDetail==detail)"✓ " else "")+detail.label,
            {onSampleDetail(detail)},interaction=interaction,id="detail-${detail.name}")}
        Text("Fast response leaves more power for tracking. Maximum detail can add delay.",color=QuickMenuColors.muted)
    }
    var fit by remember {mutableStateOf(false)}
    if(cameraMode)QuickAction(if(fit)"Hide face fit" else "Face fit and alignment",{fit=!fit},interaction=interaction,id="face-fit")
    if(cameraMode && fit) {
        Text("Size");Slider(MirrorState.faceScale,{onAlignment(it,MirrorState.faceOffsetX,MirrorState.faceOffsetY)},valueRange=.8f..1.25f)
        Text("Horizontal position");Slider(MirrorState.faceOffsetX,{onAlignment(MirrorState.faceScale,it,MirrorState.faceOffsetY)},valueRange=-.12f.. .12f)
        Text("Vertical position");Slider(MirrorState.faceOffsetY,{onAlignment(MirrorState.faceScale,MirrorState.faceOffsetX,it)},valueRange=-.12f.. .12f)
        QuickAction("Reset face fit",{onAlignment(1f,0f,0f)},interaction=interaction,id="reset-fit")
        Row(verticalAlignment=Alignment.CenterVertically){Switch(MirrorState.protectHands,onProtectHands);Text("Keep natural hand overlap")}
    }
    var diagnostics by remember {mutableStateOf(false)}
    QuickAction(if(diagnostics)"Hide tracking details" else "Tracking details",{diagnostics=!diagnostics},interaction=interaction,id="tracking-details")
    if(diagnostics) {
        Text(if(cameraMode)MirrorState.cameraStatus else MirrorState.tracking.message,color=QuickMenuColors.muted)
        Text(MirrorState.horizonStatus,color=QuickMenuColors.muted)
        Text("Camera ${MirrorState.sourceResolution} · Output ${MirrorState.outputResolution}",color=QuickMenuColors.muted)
        Text("${MirrorState.sampleFps} sample pictures/sec · ${MirrorState.videoFps} tracking updates/sec",color=QuickMenuColors.muted)
    }
    Text("Eye tracking is optional. The app uses the tracking available on this headset.",color=QuickMenuColors.muted)
}
