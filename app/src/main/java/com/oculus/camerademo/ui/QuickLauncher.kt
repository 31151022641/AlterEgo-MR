package com.oculus.camerademo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oculus.camerademo.model3d.*
import com.oculus.camerademo.mirror.PresetGallery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object QuickMenuColors {
    val panel=Color(0xFF1B2633); val ink=Color(0xFFE9EFF5); val muted=Color(0xFFB3C6D5)
    val accent=Color(0xFF80DDE4); val soft=Color(0xFF253646)
}

fun Modifier.quickTarget(id: String,interaction: SpatialMenuInteraction?,action: ()->Unit): Modifier =
    if (interaction == null) this else this.gazePinchSelectable("launcher-$id",interaction,action)

@Composable
fun QuickAction(label: String,onClick: ()->Unit,modifier: Modifier=Modifier,primary: Boolean=false,
    enabled: Boolean=true,interaction: SpatialMenuInteraction?=null,id: String=label) {
    val nativeInteraction = remember { MutableInteractionSource() }
    val nativeHovered by nativeInteraction.collectIsHoveredAsState()
    val nativePressed by nativeInteraction.collectIsPressedAsState()
    val hovered=enabled && (nativeHovered || nativePressed || interaction?.isHovered("launcher-$id")==true)
    Button(onClick,enabled=enabled,modifier=modifier.heightIn(min=48.dp).quickTarget(id,
        if(enabled) interaction else null,onClick),shape=RoundedCornerShape(12.dp),
        interactionSource=nativeInteraction,
        border=if(hovered)BorderStroke(2.dp,QuickMenuColors.accent) else null,
        colors=ButtonDefaults.buttonColors(containerColor=if(primary)QuickMenuColors.accent else QuickMenuColors.soft,
            contentColor=if(primary)Color(0xFF10232B) else QuickMenuColors.ink)) { Text(label) }
}

/** One opaque menu surface for the regular OS window and focused XR panel. */
@Composable
fun QuickLauncher(state: QuickMenuNavigation,onNavigate: (QuickMenuNavigation)->Unit,
    modifier: Modifier=Modifier,interaction: SpatialMenuInteraction?=null,
    looks: @Composable ()->Unit,link: @Composable ()->Unit,
    importFace: @Composable ()->Unit,settings: @Composable ()->Unit,
    onReturnToMirror: (() -> Unit)? = null,onResetView: (() -> Unit)? = null,
    status: (@Composable () -> Unit)? = null) {
    Surface(modifier.fillMaxWidth().heightIn(max=560.dp),shape=RoundedCornerShape(20.dp),
        color=QuickMenuColors.panel,contentColor=QuickMenuColors.ink,shadowElevation=12.dp) {
        val scroll=rememberScrollState()
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text(state.page.title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f).padding(top=10.dp))
                onReturnToMirror?.let { QuickAction("Exit passthrough",it,interaction=interaction,id="return-mirror") }
                QuickAction("Close",{onNavigate(state.close())},interaction=interaction,id="close")
            }
            status?.let { Spacer(Modifier.height(8.dp)); it() }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f,false).fillMaxWidth().verticalScroll(scroll),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                when(state.page) {
                    QuickMenuPage.HOME -> {
                        listOf(listOf(QuickMenuPage.FACES to "Choose a face, effect or avatar"),
                            listOf(QuickMenuPage.LINK to "Pair your webcam",QuickMenuPage.IMPORT to "From a PNG/JPG file")).forEach { row ->
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                row.forEach { (page,detail) ->
                                    val click={onNavigate(state.show(page))}
                                    val hovered=interaction?.isHovered("launcher-home-${page.name}")==true
                                    OutlinedButton(click,Modifier.weight(1f).heightIn(min=120.dp)
                                        .quickTarget("home-${page.name}",interaction,click),
                                        shape=RoundedCornerShape(14.dp),border=BorderStroke(if(hovered)2.dp else 1.dp,
                                            if(hovered)QuickMenuColors.accent else Color(0xFF415A6D)),
                                        colors=ButtonDefaults.outlinedButtonColors(contentColor=QuickMenuColors.ink)) {
                                        Column { Text(page.title,style=MaterialTheme.typography.titleMedium)
                                            Spacer(Modifier.height(6.dp));Text(detail,color=QuickMenuColors.muted,fontSize=13.sp) }
                                    }
                                }
                            }
                        }
                    }
                    QuickMenuPage.FACES -> { looks(); PortraitCredits(interaction) }
                    QuickMenuPage.LINK -> link()
                    QuickMenuPage.IMPORT -> importFace()
                    QuickMenuPage.SETTINGS -> { settings(); PortraitCredits(interaction) }
                }
            }
            Spacer(Modifier.height(12.dp)); HorizontalDivider(color=Color(0xFF344858))
            Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                QuickAction("Back",{
                    if(state.backReturnsToMirror && onReturnToMirror!=null)onReturnToMirror()
                    else onNavigate(state.back())
                },interaction=interaction,id="back")
                if(state.page!=QuickMenuPage.SETTINGS)QuickAction("Settings",{onNavigate(state.show(QuickMenuPage.SETTINGS))},interaction=interaction,id="settings")
                onResetView?.let { QuickAction("Reset view",it,interaction=interaction,id="reset-view") }
                Spacer(Modifier.weight(1f))
                Text(if(scroll.canScrollForward)"Scroll for more" else "Point and select",color=QuickMenuColors.muted,
                    fontSize=12.sp,modifier=Modifier.padding(top=14.dp))
            }
        }
    }
}

@Composable
fun QuickFaces(persona: HumanPersona,presetSelected: Boolean,onPreset: (HumanPersona)->Unit,
    photoFaces: PhotoFaceImport?,selectedPhotoId: String?,onPhoto: (PhotoFaceEntry)->Unit,
    onAdd: ()->Unit,photoAllowed: Boolean=true,interaction: SpatialMenuInteraction?=null,
    passthrough: Boolean=false) {
    QuickAction("+ Add face from files",onAdd,primary=true,interaction=interaction,id="add-face")
    PresetGallery(persona,presetSelected,true,onPreset,interaction,
        personas=if(passthrough)HumanPersona.available else HumanPersona.mirrorAvailable)
    if(photoFaces!=null && photoFaces.entries.isNotEmpty()) {
        Text("My photo faces",style=MaterialTheme.typography.titleMedium)
        if(!photoAllowed) Text("Photo masks work in passthrough and the regular My mirror window.",color=QuickMenuColors.muted)
        photoFaces.entries.chunked(3).forEach { row -> Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            row.forEach { entry ->
                val bitmap by produceState<android.graphics.Bitmap?>(null,entry.id) {
                    value=withContext(Dispatchers.IO) { runCatching { photoFaces.library.thumbnail(entry) }.getOrNull() }
                }
                val click={onPhoto(entry)}
                val hovered=photoAllowed && interaction?.isHovered("launcher-photo-${entry.id}")==true
                OutlinedButton(click,modifier=Modifier.weight(1f).heightIn(min=100.dp).quickTarget("photo-${entry.id}",
                    if(photoAllowed && !photoFaces.busy)interaction else null,click),enabled=photoAllowed && !photoFaces.busy,
                    border=BorderStroke(if(hovered)2.dp else 1.dp,if(hovered)QuickMenuColors.accent else QuickMenuColors.muted),
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=QuickMenuColors.ink)) {
                    Column { bitmap?.let { Image(it.asImageBitmap(),entry.name,Modifier.fillMaxWidth().height(80.dp)) }
                        Text((if(selectedPhotoId==entry.id)"✓ " else "")+entry.name);Text("Photo mask",fontSize=12.sp) }
                }
            }
            repeat(3-row.size){Spacer(Modifier.weight(1f))}
        } }
    }
    photoFaces?.message?.takeIf { it.isNotEmpty() }?.let { Text(it,color=QuickMenuColors.muted) }
}

@Composable
fun QuickImport(photoFaces: PhotoFaceImport?,onPick: ()->Unit,onSaved: (PhotoFaceEntry)->Unit,
    interaction: SpatialMenuInteraction?=null) {
    if(photoFaces==null){Text("Open My mirror to add a photo face.");return}
    Text("Choose → Preview → Save",color=QuickMenuColors.muted)
    val draft=photoFaces.draft
    if(draft==null) {
        Text("Choose a close-up face image. The centre square becomes your photo mask.")
        QuickAction(if(photoFaces.busy)"Opening image…" else "Choose PNG/JPG file",onPick,primary=true,
            enabled=!photoFaces.busy,interaction=interaction,id="choose-file")
        Text("PNG/JPG · Up to 16 MB · Saved on this headset",color=QuickMenuColors.muted,fontSize=13.sp)
    } else {
        Image(draft.asImageBitmap(),"Photo mask preview",Modifier.fillMaxWidth().height(180.dp))
        OutlinedTextField(photoFaces.draftName,{photoFaces.draftName=it.take(40)},label={Text("Face name",color=QuickMenuColors.muted)},
            modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!photoFaces.busy,
            colors=OutlinedTextFieldDefaults.colors(focusedTextColor=QuickMenuColors.ink,unfocusedTextColor=QuickMenuColors.ink))
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            QuickAction("Change file",{photoFaces.discard();onPick()},enabled=!photoFaces.busy,interaction=interaction,id="change-file")
            QuickAction(if(photoFaces.busy)"Saving…" else "Add to library",{photoFaces.save(onSaved)},primary=true,
                enabled=!photoFaces.busy,interaction=interaction,id="save-file")
        }
    }
    if(photoFaces.message.isNotEmpty())Text(photoFaces.message,color=QuickMenuColors.muted)
    Text("Photo masks follow the head. A still image does not blink or speak. Animated 3D model import is not available.",
        color=QuickMenuColors.muted,fontSize=13.sp)
}
