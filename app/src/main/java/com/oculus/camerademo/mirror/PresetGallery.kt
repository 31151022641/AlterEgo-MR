package com.oculus.camerademo.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.oculus.camerademo.ml.FaceEmotionState
import com.oculus.camerademo.model3d.HumanPersona
import com.oculus.camerademo.model3d.PortraitSunglasses
import com.oculus.camerademo.model3d.PortraitSurface
import com.oculus.camerademo.renderer.PortraitRasterizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.*
import com.oculus.camerademo.ui.SpatialMenuInteraction
import com.oculus.camerademo.ui.quickTarget
import com.oculus.camerademo.ui.QuickMenuColors

/** Offline style samples with synthetic expressions, never the wearer's camera. */
@Composable
fun PresetGallery(selected: HumanPersona,presetSelected: Boolean,animate: Boolean,onSelect: (HumanPersona) -> Unit,
    interaction: SpatialMenuInteraction?=null,personas: List<HumanPersona> = HumanPersona.mirrorAvailable) {
    var previews by remember { mutableStateOf<Map<HumanPersona,Bitmap>>(emptyMap()) }
    var animated by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(personas) {
        previews = withContext(Dispatchers.Default) {
            personas.associateWith { AvatarPresetPreview.render(it,0f) }
        }
    }
    LaunchedEffect(selected,animate) {
        var time = 0f
        do {
            animated = withContext(Dispatchers.Default) { AvatarPresetPreview.render(selected,time) }
            time += .125f; delay(125)
        } while (animate)
    }
    Text("Faces and effects",style = MaterialTheme.typography.titleMedium)
    Text(if(personas.any { it.isPassthroughOnly }) "Choose a face or an effect for passthrough" else "Animated style preview · Live fit appears in your mirror")
    animated?.let { Image(it.asImageBitmap(),"Animated ${selected.displayName} style",Modifier.fillMaxWidth().height(180.dp)) }
    personas.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { persona ->
                val hovered=interaction?.isHovered("launcher-preset-${persona.name}")==true
                OutlinedButton(onClick = { onSelect(persona) },modifier = Modifier.weight(1f)
                    .quickTarget("preset-${persona.name}",interaction) {onSelect(persona)},
                    border=BorderStroke(if(hovered)2.dp else 1.dp,if(hovered)QuickMenuColors.accent else QuickMenuColors.muted),
                    colors=ButtonDefaults.outlinedButtonColors(contentColor=QuickMenuColors.ink)) {
                    Column {
                        previews[persona]?.let { Image(it.asImageBitmap(),persona.displayName,Modifier.fillMaxWidth().height(96.dp)) }
                        Text((if (selected == persona && presetSelected) "✓ " else "")+persona.displayName)
                    }
                }
            }
            repeat(3-row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

object AvatarPresetPreview {
private val painter = AvatarVideoFacePainter()
private val accessoryRasterizer = PortraitRasterizer()
@Synchronized
fun render(persona: HumanPersona,time: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888)
    Canvas(bitmap).drawColor(AndroidColor.rgb(20,29,43))
    val angle = sin(time*.8f)*.28f
    if (persona == HumanPersona.SUNGLASSES) {
        val geometry = PortraitSunglasses.standalone()
        val points = FloatArray(geometry.colors.size*2)
        val depths = FloatArray(geometry.colors.size)
        val colors = IntArray(geometry.colors.size)
        val co=cos(angle); val si=sin(angle); val unit=1400f
        for(vertex in geometry.colors.indices) {
            val at=vertex*3
            val x=co*geometry.positions[at]+si*geometry.positions[at+2]
            val z=-si*geometry.positions[at]+co*geometry.positions[at+2]
            points[vertex*2]=192f+x*unit
            points[vertex*2+1]=192f-(geometry.positions[at+1]-.025f)*unit
            depths[vertex]=z
            colors[vertex]=PortraitSurface.shade(geometry.colors[vertex],
                co*geometry.normals[at]+si*geometry.normals[at+2],geometry.normals[at+1],
                -si*geometry.normals[at]+co*geometry.normals[at+2],geometry.surfaces[vertex],false)
        }
        accessoryRasterizer.begin(bitmap.width,bitmap.height)
        accessoryRasterizer.draw(bitmap,points,depths,colors,geometry.indices)
        return bitmap
    }
    val rotation = floatArrayOf(cos(angle),0f,-sin(angle),0f, 0f,1f,0f,0f, sin(angle),0f,cos(angle),0f, 0f,0f,0f,1f)
    val blink = if (time%3f > 2.75f) .95f else 0f
    val face = AvatarVideoFace(.20f,.22f,.80f,.85f,rotation,FaceEmotionState(
        jawOpen = (.15f+.15f*sin(time*2f)).coerceAtLeast(0f),mouthSmileLeft = .4f,mouthSmileRight = .4f,
        eyeBlinkLeft = blink,eyeBlinkRight = blink),sin(time)*.25f,0f)
    painter.draw(bitmap,face,persona)
    return bitmap
}
}
