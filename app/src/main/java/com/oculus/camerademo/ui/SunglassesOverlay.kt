package com.oculus.camerademo.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** Fallback panel artwork for the standalone effect; the stereo scene uses its 3D mesh. */
internal fun DrawScope.drawStandaloneSunglasses(center: Offset,width: Float,alpha: Float) {
    val opacity=alpha.coerceIn(0f,1f)
    val frame=Color(0xFF111B25).copy(alpha=opacity)
    val lensWidth=width*.40f; val lensHeight=width*.30f
    val eyeOffset=width*.235f; val corner=CornerRadius(lensWidth*.26f,lensHeight*.31f)
    drawLine(frame,center+Offset(-width*.55f,0f),center+Offset(width*.55f,0f),
        strokeWidth=width*.036f,cap=StrokeCap.Round)
    for(side in listOf(-1f,1f)) {
        val left=center+Offset(side*eyeOffset-lensWidth*.5f,-lensHeight*.5f)
        drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF294555).copy(alpha=opacity),
            Color(0xFF080F19).copy(alpha=opacity)),startY=left.y,endY=left.y+lensHeight),
            left,Size(lensWidth,lensHeight),corner)
        drawRoundRect(frame,left,Size(lensWidth,lensHeight),corner,style=Stroke(width=width*.026f))
        drawLine(Color(0xFF527589).copy(alpha=opacity),left+Offset(lensWidth*.18f,lensHeight*.20f),
            left+Offset(lensWidth*.56f,lensHeight*.29f),strokeWidth=width*.009f,cap=StrokeCap.Round)
    }
}
