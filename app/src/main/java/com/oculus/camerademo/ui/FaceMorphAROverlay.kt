package com.oculus.camerademo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oculus.camerademo.ml.FaceTrackingData
import com.oculus.camerademo.model3d.DynamicPersona
import com.oculus.camerademo.model3d.HumanPersona
import com.oculus.camerademo.model3d.Sticker3DType
import kotlin.math.cos
import kotlin.math.sin

/**
 * FaceMorphAROverlay:
 * High-immersion 3D Passthrough AR Face Morph Overlay.
 *
 * Rendered into Meta Spatial SDK's ComposeViewPanelRegistration (FACE_MORPH_PANEL_ID)
 * and attached to the floating avatarEntity in 3D AR space.
 *
 * Positioned in 3D world space directly on top of the real human face detected in front
 * of the Meta Quest headset camera, transforming their face into the active persona
 * with live expression mimicry and 3D holographic stickers.
 */
@Composable
fun FaceMorphAROverlay(
    persona: HumanPersona,
    dynamicPersona: DynamicPersona? = null,
    activeStickers: Set<Sticker3DType> = emptySet(),
    trackingData: FaceTrackingData,
    morphAlpha: Float = 0.90f,
    headScale: Float = 1.0f,
    modifier: Modifier = Modifier
) {
    // Smooth animated values for real-time expression mimicry
    val animSmile by animateFloatAsState(
        targetValue = trackingData.smileScore.coerceIn(0f, 1f),
        animationSpec = tween(70),
        label = "animSmile"
    )
    val animJaw by animateFloatAsState(
        targetValue = trackingData.jawOpen.coerceIn(0f, 1f),
        animationSpec = tween(70),
        label = "animJaw"
    )
    val animBlinkLeft by animateFloatAsState(
        targetValue = trackingData.emotion.eyeBlinkLeft.coerceIn(0f, 1f),
        animationSpec = tween(50),
        label = "blinkL"
    )
    val animBlinkRight by animateFloatAsState(
        targetValue = trackingData.emotion.eyeBlinkRight.coerceIn(0f, 1f),
        animationSpec = tween(50),
        label = "blinkR"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // In direct world AR overlay: pitch, yaw, and roll match head orientation
            val yaw = trackingData.rotationYaw
            val pitch = trackingData.rotationPitch
            val roll = trackingData.rotationRoll

            val headCenterX = width * 0.5f + (yaw * 1.6f)
            val headCenterY = height * 0.50f + (pitch * 1.3f)
            val headRadiusX = width * 0.26f * headScale
            val headRadiusY = height * 0.30f * headScale

            if (persona == HumanPersona.SUNGLASSES) {
                drawStandaloneSunglasses(Offset(headCenterX,headCenterY-headRadiusY*.10f),
                    headRadiusX*1.8f,morphAlpha)
                return@Canvas
            }

            // Color Palette
            val isCustom = dynamicPersona != null
            val isCR7 = dynamicPersona?.id == "ONLINE_CR7"

            val skinColor = if (isCustom) {
                Color(dynamicPersona!!.primaryColorHex).copy(alpha = morphAlpha.coerceIn(0.40f, 1f))
            } else {
                when (persona) {
                    HumanPersona.UNICORN_MEMOJI -> Color(0xFFFDFDFE)
                    HumanPersona.LION_MEMOJI -> Color(0xFFFFB300)
                    HumanPersona.DRAGON_MEMOJI -> Color(0xFF4CAF50)
                    HumanPersona.MESSI_3D -> Color(0xFFDCA884)
                    HumanPersona.CYBER_SYNTH -> Color(0xFF192A3A)
                    HumanPersona.DIGITAL_TWIN -> Color(0xFFE2B296)
                    HumanPersona.GOLDEN_SCULPTURE -> Color(0xFFD4AF37)
                    HumanPersona.HOLO_HUMAN -> Color(0xFF003D2E)
                    HumanPersona.SUNGLASSES -> Color.Transparent
                }.copy(alpha = morphAlpha.coerceIn(0.40f, 1f))
            }

            val accentColor = if (isCustom) {
                Color(dynamicPersona!!.accentColorHex)
            } else {
                when (persona) {
                    HumanPersona.UNICORN_MEMOJI -> Color(0xFFE040FB)
                    HumanPersona.LION_MEMOJI -> Color(0xFFD84315)
                    HumanPersona.DRAGON_MEMOJI -> Color(0xFFFFD600)
                    HumanPersona.MESSI_3D -> Color(0xFF693A1E)
                    HumanPersona.CYBER_SYNTH -> Color(0xFF00E5FF)
                    HumanPersona.DIGITAL_TWIN -> Color(0xFF8D6E63)
                    HumanPersona.GOLDEN_SCULPTURE -> Color(0xFFFFD700)
                    HumanPersona.HOLO_HUMAN -> Color(0xFF00E676)
                    HumanPersona.SUNGLASSES -> Color(0xFF527589)
                }
            }

            // 1. Subtle Outer AR Holographic Aura Glow
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.25f * morphAlpha),
                        accentColor.copy(alpha = 0.05f * morphAlpha),
                        Color.Transparent
                    ),
                    center = Offset(headCenterX, headCenterY),
                    radius = headRadiusX * 1.55f
                ),
                topLeft = Offset(headCenterX - headRadiusX * 1.55f, headCenterY - headRadiusY * 1.55f),
                size = Size(headRadiusX * 3.1f, headRadiusY * 3.1f)
            )

            // 2. Head Silhouette & Base Skin Oval
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        skinColor,
                        skinColor.copy(alpha = (skinColor.alpha * 0.85f))
                    ),
                    center = Offset(headCenterX, headCenterY - headRadiusY * 0.15f),
                    radius = headRadiusX * 1.2f
                ),
                topLeft = Offset(headCenterX - headRadiusX, headCenterY - headRadiusY),
                size = Size(headRadiusX * 2f, headRadiusY * 2f)
            )

            // 3. Holographic Edge Ring / Circuit Tracer
            drawOval(
                color = accentColor.copy(alpha = 0.65f * morphAlpha),
                topLeft = Offset(headCenterX - headRadiusX, headCenterY - headRadiusY),
                size = Size(headRadiusX * 2f, headRadiusY * 2f),
                style = Stroke(width = 2.5f)
            )

            // 4. Persona-Specific Features:
            // === LIONEL MESSI SIGNATURE HAIR & BEARD ===
            if (persona == HumanPersona.MESSI_3D && !isCR7) {
                // Signature Messi Styled Hair Crown
                val hairPath = Path().apply {
                    moveTo(headCenterX - headRadiusX * 1.05f, headCenterY - headRadiusY * 0.40f)
                    cubicTo(
                        headCenterX - headRadiusX * 0.95f, headCenterY - headRadiusY * 1.35f,
                        headCenterX + headRadiusX * 0.95f, headCenterY - headRadiusY * 1.35f,
                        headCenterX + headRadiusX * 1.05f, headCenterY - headRadiusY * 0.40f
                    )
                    cubicTo(
                        headCenterX + headRadiusX * 0.70f, headCenterY - headRadiusY * 0.75f,
                        headCenterX - headRadiusX * 0.70f, headCenterY - headRadiusY * 0.75f,
                        headCenterX - headRadiusX * 1.05f, headCenterY - headRadiusY * 0.40f
                    )
                    close()
                }
                drawPath(
                    path = hairPath,
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF3E2723), Color(0xFF5D4037), Color(0xFF6D4C41))
                    )
                )

                // Signature Messi Sculpted Beard & Goatee
                val beardPath = Path().apply {
                    moveTo(headCenterX - headRadiusX * 0.78f, headCenterY + headRadiusY * 0.15f)
                    cubicTo(
                        headCenterX - headRadiusX * 0.85f, headCenterY + headRadiusY * 0.85f,
                        headCenterX - headRadiusX * 0.40f, headCenterY + headRadiusY * 1.18f,
                        headCenterX, headCenterY + headRadiusY * (1.18f + animJaw * 0.12f)
                    )
                    cubicTo(
                        headCenterX + headRadiusX * 0.40f, headCenterY + headRadiusY * 1.18f,
                        headCenterX + headRadiusX * 0.85f, headCenterY + headRadiusY * 0.85f,
                        headCenterX + headRadiusX * 0.78f, headCenterY + headRadiusY * 0.15f
                    )
                    cubicTo(
                        headCenterX + headRadiusX * 0.50f, headCenterY + headRadiusY * 0.40f,
                        headCenterX - headRadiusX * 0.50f, headCenterY + headRadiusY * 0.40f,
                        headCenterX - headRadiusX * 0.78f, headCenterY + headRadiusY * 0.15f
                    )
                    close()
                }
                drawPath(
                    path = beardPath,
                    brush = Brush.verticalGradient(
                        listOf(Color(0x995D4037), Color(0xFF4E342E), Color(0xFF3E2723))
                    )
                )
            }

            // === CRISTIANO RONALDO (CR7) ATHLETIC FADE & JAW ===
            if (isCR7) {
                // Signature CR7 Swept Haircut with Fade
                val cr7Hair = Path().apply {
                    moveTo(headCenterX - headRadiusX * 1.02f, headCenterY - headRadiusY * 0.35f)
                    cubicTo(
                        headCenterX - headRadiusX * 0.75f, headCenterY - headRadiusY * 1.45f,
                        headCenterX + headRadiusX * 0.85f, headCenterY - headRadiusY * 1.30f,
                        headCenterX + headRadiusX * 1.02f, headCenterY - headRadiusY * 0.30f
                    )
                    cubicTo(
                        headCenterX + headRadiusX * 0.60f, headCenterY - headRadiusY * 0.70f,
                        headCenterX - headRadiusX * 0.60f, headCenterY - headRadiusY * 0.80f,
                        headCenterX - headRadiusX * 1.02f, headCenterY - headRadiusY * 0.35f
                    )
                    close()
                }
                drawPath(
                    path = cr7Hair,
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xFF1A1A1A), Color(0xFF2C221E), Color(0xFF4A3728))
                    )
                )
                // Gold Trim Line along temple
                drawLine(
                    color = Color(0xFFFFD700),
                    start = Offset(headCenterX - headRadiusX * 0.60f, headCenterY - headRadiusY * 0.75f),
                    end = Offset(headCenterX - headRadiusX * 0.20f, headCenterY - headRadiusY * 0.95f),
                    strokeWidth = 3f,
                    cap = StrokeCap.Round
                )
            }

            // === CYBER-SYNTH GLOWING CIRCLIC TRACERS ===
            if (persona == HumanPersona.CYBER_SYNTH) {
                val neonCyan = Color(0xFF00E5FF)
                // Cheeks Cyber Tracks
                drawLine(
                    neonCyan,
                    Offset(headCenterX - headRadiusX * 0.70f, headCenterY),
                    Offset(headCenterX - headRadiusX * 0.35f, headCenterY + headRadiusY * 0.35f),
                    strokeWidth = 2.5f
                )
                drawLine(
                    neonCyan,
                    Offset(headCenterX + headRadiusX * 0.70f, headCenterY),
                    Offset(headCenterX + headRadiusX * 0.35f, headCenterY + headRadiusY * 0.35f),
                    strokeWidth = 2.5f
                )
                // Forehead Hexagon Node
                drawCircle(
                    neonCyan,
                    radius = 4f,
                    center = Offset(headCenterX, headCenterY - headRadiusY * 0.55f)
                )
            }

            // === HOLOGRAPHIC MATRIX WIREFRAME MESH ===
            if (persona == HumanPersona.HOLO_HUMAN) {
                val matrixGreen = Color(0xFF00FFB4)
                for (row in -3..3) {
                    val yLine = headCenterY + (row * headRadiusY * 0.25f)
                    val xSpan = headRadiusX * cos((row * 0.32f).toDouble()).toFloat() * 0.9f
                    drawLine(
                        color = matrixGreen.copy(alpha = 0.5f),
                        start = Offset(headCenterX - xSpan, yLine),
                        end = Offset(headCenterX + xSpan, yLine),
                        strokeWidth = 1.2f
                    )
                }
                for (col in -3..3) {
                    val xLine = headCenterX + (col * headRadiusX * 0.26f)
                    val ySpan = headRadiusY * cos((col * 0.28f).toDouble()).toFloat() * 0.9f
                    drawLine(
                        color = matrixGreen.copy(alpha = 0.5f),
                        start = Offset(xLine, headCenterY - ySpan),
                        end = Offset(xLine, headCenterY + ySpan),
                        strokeWidth = 1.2f
                    )
                }
            }

            // === APPLE-STYLE 3D RAINBOW UNICORN MEMOJI ===
            // (Exact match to user's uploaded reference: rainbow horn, lavender muzzle, expressive anime eyes)
            if (persona == HumanPersona.UNICORN_MEMOJI) {
                // 1. Pointed Unicorn Ears with Pink Cushions
                val leftEar = Path().apply {
                    moveTo(headCenterX - headRadiusX * 0.45f, headCenterY - headRadiusY * 0.82f)
                    lineTo(headCenterX - headRadiusX * 1.15f, headCenterY - headRadiusY * 1.45f)
                    lineTo(headCenterX - headRadiusX * 0.85f, headCenterY - headRadiusY * 0.65f)
                    close()
                }
                drawPath(path = leftEar, color = Color(0xFFEDE7F6))
                val leftInnerEar = Path().apply {
                    moveTo(headCenterX - headRadiusX * 0.52f, headCenterY - headRadiusY * 0.85f)
                    lineTo(headCenterX - headRadiusX * 1.05f, headCenterY - headRadiusY * 1.35f)
                    lineTo(headCenterX - headRadiusX * 0.82f, headCenterY - headRadiusY * 0.72f)
                    close()
                }
                drawPath(path = leftInnerEar, color = Color(0xFFFF80AB))

                val rightEar = Path().apply {
                    moveTo(headCenterX + headRadiusX * 0.45f, headCenterY - headRadiusY * 0.82f)
                    lineTo(headCenterX + headRadiusX * 1.15f, headCenterY - headRadiusY * 1.45f)
                    lineTo(headCenterX + headRadiusX * 0.85f, headCenterY - headRadiusY * 0.65f)
                    close()
                }
                drawPath(path = rightEar, color = Color(0xFFEDE7F6))
                val rightInnerEar = Path().apply {
                    moveTo(headCenterX + headRadiusX * 0.52f, headCenterY - headRadiusY * 0.85f)
                    lineTo(headCenterX + headRadiusX * 1.05f, headCenterY - headRadiusY * 1.35f)
                    lineTo(headCenterX + headRadiusX * 0.82f, headCenterY - headRadiusY * 0.72f)
                    close()
                }
                drawPath(path = rightInnerEar, color = Color(0xFFFF80AB))

                // 2. Sculpted Purple Mane & Forelock flanking the Horn
                val maneLeft = Path().apply {
                    moveTo(headCenterX - headRadiusX * 0.20f, headCenterY - headRadiusY * 0.75f)
                    cubicTo(
                        headCenterX - headRadiusX * 0.75f, headCenterY - headRadiusY * 1.05f,
                        headCenterX - headRadiusX * 0.85f, headCenterY - headRadiusY * 0.40f,
                        headCenterX - headRadiusX * 0.40f, headCenterY - headRadiusY * 0.35f
                    )
                    close()
                }
                drawPath(path = maneLeft, brush = Brush.verticalGradient(listOf(Color(0xFFBA68C8), Color(0xFF8E24AA))))

                val maneRight = Path().apply {
                    moveTo(headCenterX + headRadiusX * 0.20f, headCenterY - headRadiusY * 0.75f)
                    cubicTo(
                        headCenterX + headRadiusX * 0.75f, headCenterY - headRadiusY * 1.05f,
                        headCenterX + headRadiusX * 0.85f, headCenterY - headRadiusY * 0.40f,
                        headCenterX + headRadiusX * 0.40f, headCenterY - headRadiusY * 0.35f
                    )
                    close()
                }
                drawPath(path = maneRight, brush = Brush.verticalGradient(listOf(Color(0xFFBA68C8), Color(0xFF8E24AA))))

                // 3. Glowing Rainbow Spiral Horn
                val hornBaseY = headCenterY - headRadiusY * 0.70f
                val hornTipY = headCenterY - headRadiusY * 1.85f
                val hornHalfW = headRadiusX * 0.28f

                // Horn base shape
                val hornPath = Path().apply {
                    moveTo(headCenterX - hornHalfW, hornBaseY)
                    lineTo(headCenterX, hornTipY)
                    lineTo(headCenterX + hornHalfW, hornBaseY)
                    close()
                }
                drawPath(
                    path = hornPath,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color(0xFFFF1744), // Red tip
                            Color(0xFFFF6D00), // Orange
                            Color(0xFFFFEA00), // Yellow
                            Color(0xFF00E676), // Green
                            Color(0xFF00E5FF), // Cyan
                            Color(0xFFE040FB)  // Purple base
                        )
                    )
                )

                // Spiral gold ridges on horn
                for (s in 1..5) {
                    val frac = s / 6.0f
                    val yLine = hornBaseY + (hornTipY - hornBaseY) * frac
                    val wLine = hornHalfW * (1f - frac) * 0.95f
                    drawLine(
                        color = Color(0xFFFFD700).copy(alpha = 0.85f),
                        start = Offset(headCenterX - wLine, yLine + 3f),
                        end = Offset(headCenterX + wLine, yLine - 3f),
                        strokeWidth = 2.5f,
                        cap = StrokeCap.Round
                    )
                }

                // Magical star shine at horn tip
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = 4f,
                    center = Offset(headCenterX, hornTipY)
                )

                // 4. Pastel Lavender Muzzle Snout
                val muzzleCenterY = headCenterY + headRadiusY * 0.42f + (animJaw * headRadiusY * 0.12f)
                val muzzleW = headRadiusX * 1.10f
                val muzzleH = headRadiusY * 0.70f
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(Color(0xFFCE93D8), Color(0xFFBA68C8))),
                    topLeft = Offset(headCenterX - muzzleW * 0.5f, muzzleCenterY - muzzleH * 0.5f),
                    size = Size(muzzleW, muzzleH),
                    cornerRadius = CornerRadius(muzzleW * 0.40f, muzzleH * 0.45f)
                )

                // Dark purple nostrils
                val nostrilY = muzzleCenterY - muzzleH * 0.12f
                val nostrilDist = muzzleW * 0.22f
                drawOval(
                    color = Color(0xFF4A148C),
                    topLeft = Offset(headCenterX - nostrilDist - 7f, nostrilY - 5f),
                    size = Size(14f, 10f)
                )
                drawOval(
                    color = Color(0xFF4A148C),
                    topLeft = Offset(headCenterX + nostrilDist - 7f, nostrilY - 5f),
                    size = Size(14f, 10f)
                )
            }

            // === LION KING MEMOJI ===
            if (persona == HumanPersona.LION_MEMOJI) {
                // Golden Mane ruff around head
                val maneColor = Color(0xFFD84315)
                for (angleDeg in 0 until 360 step 20) {
                    val rad = Math.toRadians(angleDeg.toDouble())
                    val x = headCenterX + cos(rad).toFloat() * (headRadiusX * 1.15f)
                    val y = headCenterY + sin(rad).toFloat() * (headRadiusY * 1.15f)
                    drawCircle(maneColor, radius = headRadiusX * 0.25f, center = Offset(x, y))
                }
                // Feline Snout
                val snoutY = headCenterY + headRadiusY * 0.35f + (animJaw * headRadiusY * 0.10f)
                drawOval(
                    color = Color(0xFFFFF9C4),
                    topLeft = Offset(headCenterX - headRadiusX * 0.45f, snoutY - headRadiusY * 0.20f),
                    size = Size(headRadiusX * 0.90f, headRadiusY * 0.42f)
                )
                // Black nose triangle
                val noseY = snoutY - headRadiusY * 0.05f
                val lionNose = Path().apply {
                    moveTo(headCenterX, noseY + 8f)
                    lineTo(headCenterX - 12f, noseY - 6f)
                    lineTo(headCenterX + 12f, noseY - 6f)
                    close()
                }
                drawPath(lionNose, Color.Black)
            }

            // 5. Eyebrows (Surprise / Emotion Dynamic Position)
            val browYOffset = headCenterY - headRadiusY * 0.28f
            val browLift = (trackingData.emotion.browInnerUp * 12f)
            // Left Brow
            drawLine(
                color = Color(0xFF2C1E18),
                start = Offset(headCenterX - headRadiusX * 0.65f, browYOffset - browLift),
                end = Offset(headCenterX - headRadiusX * 0.15f, browYOffset - browLift - 4f),
                strokeWidth = 4.5f,
                cap = StrokeCap.Round
            )
            // Right Brow
            drawLine(
                color = Color(0xFF2C1E18),
                start = Offset(headCenterX + headRadiusX * 0.15f, browYOffset - browLift - 4f),
                end = Offset(headCenterX + headRadiusX * 0.65f, browYOffset - browLift),
                strokeWidth = 4.5f,
                cap = StrokeCap.Round
            )

            // 6. Dynamic Left & Right Eyes (Real-Time Blinking & Winking)
            val eyeCenterY = headCenterY - headRadiusY * 0.10f
            val eyeSpacing = headRadiusX * 0.42f
            val eyeWidth = headRadiusX * 0.26f
            val eyeMaxHeight = headRadiusY * 0.18f

            // Left Eye
            val leftEyeH = (eyeMaxHeight * (1f - animBlinkLeft)).coerceAtLeast(2.5f)
            drawOval(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = Offset(headCenterX - eyeSpacing - eyeWidth * 0.5f, eyeCenterY - leftEyeH * 0.5f),
                size = Size(eyeWidth, leftEyeH)
            )
            if (leftEyeH > 4f) {
                // Pupil & Iris
                val irisRadius = leftEyeH * 0.38f
                drawCircle(
                    color = accentColor,
                    radius = irisRadius,
                    center = Offset(headCenterX - eyeSpacing + (yaw * 0.2f), eyeCenterY)
                )
                drawCircle(
                    color = Color.Black,
                    radius = irisRadius * 0.55f,
                    center = Offset(headCenterX - eyeSpacing + (yaw * 0.2f), eyeCenterY)
                )
            }

            // Right Eye
            val rightEyeH = (eyeMaxHeight * (1f - animBlinkRight)).coerceAtLeast(2.5f)
            drawOval(
                color = Color.White.copy(alpha = 0.95f),
                topLeft = Offset(headCenterX + eyeSpacing - eyeWidth * 0.5f, eyeCenterY - rightEyeH * 0.5f),
                size = Size(eyeWidth, rightEyeH)
            )
            if (rightEyeH > 4f) {
                val irisRadius = rightEyeH * 0.38f
                drawCircle(
                    color = accentColor,
                    radius = irisRadius,
                    center = Offset(headCenterX + eyeSpacing + (yaw * 0.2f), eyeCenterY)
                )
                drawCircle(
                    color = Color.Black,
                    radius = irisRadius * 0.55f,
                    center = Offset(headCenterX + eyeSpacing + (yaw * 0.2f), eyeCenterY)
                )
            }

            // 7. Nose Bridge & Tip
            val noseY = headCenterY + headRadiusY * 0.12f
            drawLine(
                color = skinColor.copy(alpha = 0.8f),
                start = Offset(headCenterX, headCenterY - headRadiusY * 0.15f),
                end = Offset(headCenterX, noseY),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
            drawCircle(
                color = skinColor.copy(alpha = 0.95f),
                radius = 5.5f,
                center = Offset(headCenterX, noseY)
            )

            // 8. Dynamic Mouth & Jaw (Real-Time Talking, Smiles, Jaw Open)
            val mouthCenterY = headCenterY + headRadiusY * 0.44f + (animJaw * headRadiusY * 0.24f)
            val mouthWidth = headRadiusX * (0.45f + animSmile * 0.20f)
            val mouthOpening = (headRadiusY * 0.25f * animJaw).coerceAtLeast(3f)
            val smileCurve = animSmile * headRadiusY * 0.14f

            // Oral Cavity (Dark recessed inside when speaking)
            if (animJaw > 0.10f) {
                drawOval(
                    color = Color(0xFF2B0B0E),
                    topLeft = Offset(headCenterX - mouthWidth * 0.5f, mouthCenterY - mouthOpening * 0.5f),
                    size = Size(mouthWidth, mouthOpening)
                )
                // Upper Teeth strip
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.9f),
                    topLeft = Offset(headCenterX - mouthWidth * 0.35f, mouthCenterY - mouthOpening * 0.45f),
                    size = Size(mouthWidth * 0.70f, mouthOpening * 0.32f),
                    cornerRadius = CornerRadius(2f, 2f)
                )
            }

            // Dynamic Lips Contour
            val lipPath = Path().apply {
                moveTo(headCenterX - mouthWidth * 0.5f, mouthCenterY - smileCurve * 0.4f)
                quadraticBezierTo(
                    headCenterX, mouthCenterY + smileCurve,
                    headCenterX + mouthWidth * 0.5f, mouthCenterY - smileCurve * 0.4f
                )
            }
            drawPath(
                path = lipPath,
                color = Color(0xFF9E4747),
                style = Stroke(width = 3.5f, cap = StrokeCap.Round)
            )

            // ==========================================
            // 9. ACTIVE 3D HOLOGRAPHIC STICKERS RENDERING
            // ==========================================
            // STICKER 1: ROYAL HOLOGRAPHIC CROWN
            if (activeStickers.contains(Sticker3DType.NEON_CROWN)) {
                val crownBaseY = headCenterY - headRadiusY * 0.92f
                val crownWidth = headRadiusX * 1.3f
                val crownHeight = headRadiusY * 0.65f
                val crownGold = Color(0xFFFFD700)
                val crownCyan = Color(0xFF00E5FF)

                val crownPath = Path().apply {
                    moveTo(headCenterX - crownWidth * 0.5f, crownBaseY)
                    lineTo(headCenterX - crownWidth * 0.55f, crownBaseY - crownHeight * 0.70f)
                    lineTo(headCenterX - crownWidth * 0.25f, crownBaseY - crownHeight * 0.35f)
                    lineTo(headCenterX, crownBaseY - crownHeight)
                    lineTo(headCenterX + crownWidth * 0.25f, crownBaseY - crownHeight * 0.35f)
                    lineTo(headCenterX + crownWidth * 0.55f, crownBaseY - crownHeight * 0.70f)
                    lineTo(headCenterX + crownWidth * 0.5f, crownBaseY)
                    close()
                }
                drawPath(
                    path = crownPath,
                    brush = Brush.verticalGradient(listOf(crownGold, Color(0xFFFFA000)))
                )
                drawPath(path = crownPath, color = crownCyan, style = Stroke(width = 2.5f))
                // Crown Energy Jewels
                drawCircle(crownCyan, radius = 6f, center = Offset(headCenterX, crownBaseY - crownHeight))
                drawCircle(crownCyan, radius = 4.5f, center = Offset(headCenterX - crownWidth * 0.55f, crownBaseY - crownHeight * 0.70f))
                drawCircle(crownCyan, radius = 4.5f, center = Offset(headCenterX + crownWidth * 0.55f, crownBaseY - crownHeight * 0.70f))
            }

            // STICKER 2: CYBER HUD VISOR
            if (activeStickers.contains(Sticker3DType.CYBER_VISOR)) {
                val visorY = eyeCenterY - 14f
                val visorWidth = headRadiusX * 1.55f
                val visorHeight = headRadiusY * 0.36f
                val visorCyan = Color(0xFF00E5FF)

                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color(0xCC00E5FF), Color(0xCC7C4DFF), Color(0xCCFF007F))
                    ),
                    topLeft = Offset(headCenterX - visorWidth * 0.5f, visorY),
                    size = Size(visorWidth, visorHeight),
                    cornerRadius = CornerRadius(10f, 10f)
                )
                drawRoundRect(
                    color = Color.White,
                    topLeft = Offset(headCenterX - visorWidth * 0.5f, visorY),
                    size = Size(visorWidth, visorHeight),
                    cornerRadius = CornerRadius(10f, 10f),
                    style = Stroke(width = 2.5f)
                )
                // Visor HUD Scanline
                drawLine(
                    color = Color.White.copy(alpha = 0.85f),
                    start = Offset(headCenterX - visorWidth * 0.42f, visorY + visorHeight * 0.5f),
                    end = Offset(headCenterX + visorWidth * 0.42f, visorY + visorHeight * 0.5f),
                    strokeWidth = 2f
                )
            }

            // STICKER 3: NEON CYBER HORNS
            if (activeStickers.contains(Sticker3DType.NEON_HORNS)) {
                val hornRed = Color(0xFFFF1744)
                val hornPurple = Color(0xFF7C4DFF)
                // Left Horn
                val leftHorn = Path().apply {
                    moveTo(headCenterX - headRadiusX * 0.70f, headCenterY - headRadiusY * 0.50f)
                    cubicTo(
                        headCenterX - headRadiusX * 1.35f, headCenterY - headRadiusY * 0.85f,
                        headCenterX - headRadiusX * 1.15f, headCenterY - headRadiusY * 1.40f,
                        headCenterX - headRadiusX * 0.75f, headCenterY - headRadiusY * 1.45f
                    )
                    cubicTo(
                        headCenterX - headRadiusX * 0.95f, headCenterY - headRadiusY * 1.15f,
                        headCenterX - headRadiusX * 0.90f, headCenterY - headRadiusY * 0.70f,
                        headCenterX - headRadiusX * 0.50f, headCenterY - headRadiusY * 0.60f
                    )
                    close()
                }
                drawPath(leftHorn, brush = Brush.verticalGradient(listOf(hornRed, hornPurple)))
                drawPath(leftHorn, color = Color.White, style = Stroke(width = 2f))

                // Right Horn
                val rightHorn = Path().apply {
                    moveTo(headCenterX + headRadiusX * 0.70f, headCenterY - headRadiusY * 0.50f)
                    cubicTo(
                        headCenterX + headRadiusX * 1.35f, headCenterY - headRadiusY * 0.85f,
                        headCenterX + headRadiusX * 1.15f, headCenterY - headRadiusY * 1.40f,
                        headCenterX + headRadiusX * 0.75f, headCenterY - headRadiusY * 1.45f
                    )
                    cubicTo(
                        headCenterX + headRadiusX * 0.95f, headCenterY - headRadiusY * 1.15f,
                        headCenterX + headRadiusX * 0.90f, headCenterY - headRadiusY * 0.70f,
                        headCenterX + headRadiusX * 0.50f, headCenterY - headRadiusY * 0.60f
                    )
                    close()
                }
                drawPath(rightHorn, brush = Brush.verticalGradient(listOf(hornRed, hornPurple)))
                drawPath(rightHorn, color = Color.White, style = Stroke(width = 2f))
            }

            // STICKER 4: NEKO HOLO CAT EARS
            if (activeStickers.contains(Sticker3DType.CAT_EARS)) {
                val pinkGlow = Color(0xFFFF4081)
                // Left Ear
                val leftEar = Path().apply {
                    moveTo(headCenterX - headRadiusX * 0.80f, headCenterY - headRadiusY * 0.70f)
                    lineTo(headCenterX - headRadiusX * 0.95f, headCenterY - headRadiusY * 1.45f)
                    lineTo(headCenterX - headRadiusX * 0.25f, headCenterY - headRadiusY * 0.90f)
                    close()
                }
                drawPath(leftEar, color = pinkGlow)
                drawPath(leftEar, color = Color.White, style = Stroke(width = 2.5f))

                // Right Ear
                val rightEar = Path().apply {
                    moveTo(headCenterX + headRadiusX * 0.80f, headCenterY - headRadiusY * 0.70f)
                    lineTo(headCenterX + headRadiusX * 0.95f, headCenterY - headRadiusY * 1.45f)
                    lineTo(headCenterX + headRadiusX * 0.25f, headCenterY - headRadiusY * 0.90f)
                    close()
                }
                drawPath(rightEar, color = pinkGlow)
                drawPath(rightEar, color = Color.White, style = Stroke(width = 2.5f))
            }

            // STICKER 5: GENTLEMAN MUSTACHE & MONOCLE
            if (activeStickers.contains(Sticker3DType.RETRO_MUSTACHE)) {
                val stacheBrown = Color(0xFF4E342E)
                val stacheGold = Color(0xFFFFD700)
                // Handlebar Mustache
                val stacheY = headCenterY + headRadiusY * 0.32f
                val stachePath = Path().apply {
                    moveTo(headCenterX, stacheY)
                    cubicTo(
                        headCenterX - headRadiusX * 0.40f, stacheY - 8f,
                        headCenterX - headRadiusX * 0.70f, stacheY - 12f,
                        headCenterX - headRadiusX * 0.85f, stacheY - 24f
                    )
                    cubicTo(
                        headCenterX - headRadiusX * 0.65f, stacheY + 12f,
                        headCenterX - headRadiusX * 0.25f, stacheY + 10f,
                        headCenterX, stacheY + 4f
                    )
                    cubicTo(
                        headCenterX + headRadiusX * 0.25f, stacheY + 10f,
                        headCenterX + headRadiusX * 0.65f, stacheY + 12f,
                        headCenterX + headRadiusX * 0.85f, stacheY - 24f
                    )
                    cubicTo(
                        headCenterX + headRadiusX * 0.70f, stacheY - 12f,
                        headCenterX + headRadiusX * 0.40f, stacheY - 8f,
                        headCenterX, stacheY
                    )
                    close()
                }
                drawPath(stachePath, color = stacheBrown)
                drawPath(stachePath, color = Color(0xFF3E2723), style = Stroke(width = 2f))

                // Golden Monocle over right eye
                val monocleRadius = eyeWidth * 0.68f
                drawCircle(
                    color = stacheGold,
                    radius = monocleRadius,
                    center = Offset(headCenterX + eyeSpacing, eyeCenterY),
                    style = Stroke(width = 3.5f)
                )
                // Monocle Chain
                drawLine(
                    color = stacheGold,
                    start = Offset(headCenterX + eyeSpacing + monocleRadius, eyeCenterY),
                    end = Offset(headCenterX + headRadiusX * 0.95f, headCenterY + headRadiusY * 0.65f),
                    strokeWidth = 2f
                )
            }

            // STICKER 6: MATRIX STAR TATTOO
            if (activeStickers.contains(Sticker3DType.CYBER_STAR)) {
                val starGreen = Color(0xFF76FF03)
                val starX = headCenterX - headRadiusX * 0.52f
                val starY = headCenterY + headRadiusY * 0.16f
                val starRadius = 14f

                val starPath = Path()
                for (k in 0..9) {
                    val r = if (k % 2 == 0) starRadius else starRadius * 0.45f
                    val angle = Math.toRadians((k * 36 - 90).toDouble())
                    val px = starX + (r * cos(angle)).toFloat()
                    val py = starY + (r * sin(angle)).toFloat()
                    if (k == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
                }
                starPath.close()
                drawPath(starPath, color = starGreen)
                drawPath(starPath, color = Color.White, style = Stroke(width = 1.5f))
            }
        }

        // Floating AR HUD Biometric Pill at bottom of transformed head
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xDD0D1522),
            border = androidx.compose.foundation.BorderStroke(1.dp, if (trackingData.hasFace) Color(0xFF00E5FF) else Color(0xFFFFD700))
        ) {
            val displayName = dynamicPersona?.displayName ?: persona.displayName
            val stickersIcon = if (activeStickers.isNotEmpty()) " | 3D: ${activeStickers.joinToString(" ") { it.icon }}" else ""
            val statusTag = if (trackingData.hasFace) "👤 TRACKING WORLD FACE" else "🦄 INTERACTIVE PREVIEW"
            Text(
                text = "✨ $displayName$stickersIcon [$statusTag]",
                color = if (trackingData.hasFace) Color(0xFF80DEEA) else Color(0xFFFFE082),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }
}
