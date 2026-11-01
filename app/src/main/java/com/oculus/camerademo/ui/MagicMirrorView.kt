package com.oculus.camerademo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.oculus.camerademo.model3d.OnlineFaceCatalog
import com.oculus.camerademo.model3d.Sticker3DType
import kotlin.math.cos
import kotlin.math.sin

/**
 * MagicMirrorView: Floating Interactive AR Selfie Mirror
 *
 * Designed for Meta Quest Pro Mixed Reality.
 * Spawns a floating holographic vanity mirror in 3D AR space so the user can:
 * 1. View their own morphed persona reflecting back at them in real time.
 * 2. See live facial expression mimicry (talking, smiling, blinking, head tilt).
 * 3. Switch personas directly on the mirror frame with 1 tap or add new ones via [+] icon.
 * 4. Snap spatial selfie photos with flash effects.
 * 5. Equip live 3D AR Stickers (Crown, Cyber Visor, Horns, Cat Ears, etc.)!
 */
@Composable
fun MagicMirrorView(
    selectedPersona: HumanPersona,
    trackingData: FaceTrackingData,
    morphAlpha: Float,
    availablePersonas: List<DynamicPersona> = emptyList(),
    selectedDynamicPersona: DynamicPersona? = null,
    activeStickers: Set<Sticker3DType> = emptySet(),
    spatialInteraction: SpatialMenuInteraction = remember { SpatialMenuInteraction() },
    onPersonaSelected: (HumanPersona) -> Unit,
    onDynamicPersonaSelected: (DynamicPersona) -> Unit = {},
    onOpenAddFace: () -> Unit = {},
    onMorphAlphaChanged: (Float) -> Unit,
    onTakeSnapshot: () -> Unit,
    onCloseMirror: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showFlashEffect by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .widthIn(min = 420.dp, max = 500.dp)
            .padding(12.dp),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xF20F1626),
        border = BorderStroke(
            2.dp,
            Brush.linearGradient(
                listOf(
                    Color(0xFF00E5FF),
                    Color(0xFF7C4DFF),
                    Color(0xFFE040FB)
                )
            )
        ),
        shadowElevation = 18.dp
    ) {
        Column(
            modifier = Modifier
                .padding(18.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- TOP HEADER BAR ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🪞", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "MAGIC SELFIE MIRROR",
                                color = Color(0xFF00E5FF),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            // Pulsing Live Indicator
                            Surface(
                                shape = CircleShape,
                                color = Color(0xFF00E676),
                                modifier = Modifier.size(8.dp)
                            ) {}
                        }
                        Text(
                            text = "Real-Time Passthrough Self-Morph Reflection",
                            color = Color.LightGray,
                            fontSize = 10.sp
                        )
                    }
                }

                // Close Button
                val isCloseHovered = spatialInteraction.isHovered("MIRROR_CLOSE_BTN")
                IconButton(
                    onClick = onCloseMirror,
                    modifier = Modifier
                        .size(32.dp)
                        .background(if (isCloseHovered) Color(0x66FF5252) else Color(0x22FFFFFF), CircleShape)
                        .gazePinchSelectable("MIRROR_CLOSE_BTN", spatialInteraction) { onCloseMirror() }
                        .then(
                            if (isCloseHovered)
                                Modifier.border(2.dp, Color(0xFFFF5252), CircleShape)
                            else Modifier
                        )
                ) {
                    Text(text = "✕", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- HOLOGRAPHIC MIRROR CANVAS ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(290.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF070B14))
                    .border(1.dp, Color(0x5500E5FF), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Dynamic Real-Time 3D Stylized Face Reflection
                MirrorFaceReflectionCanvas(
                    persona = selectedPersona,
                    dynamicPersona = selectedDynamicPersona,
                    activeStickers = activeStickers,
                    trackingData = trackingData,
                    morphAlpha = morphAlpha,
                    modifier = Modifier.fillMaxSize()
                )

                // Shutter Flash Animation
                if (showFlashEffect) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White.copy(alpha = 0.85f))
                    )
                    LaunchedEffect(Unit) {
                        kotlinx.coroutines.delay(120)
                        showFlashEffect = false
                    }
                }

                // Floating Persona Badge at top-left of mirror
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xCC111B2B),
                    border = BorderStroke(0.5.dp, Color(0xFF00E5FF))
                ) {
                    val personaTitle = selectedDynamicPersona?.let { "${it.icon} ${it.displayName}" } ?: "🎭 ${selectedPersona.displayName}"
                    Text(
                        text = personaTitle,
                        color = Color(0xFF80DEEA),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }

                // Biometric Reticle & Distance Tag at top-right of mirror
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(10.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xCC111B2B),
                    border = BorderStroke(0.5.dp, Color(0x887C4DFF))
                ) {
                    val dist = trackingData.headDistanceMeters
                    Text(
                        text = "📏 ${String.format("%.1fm", dist)} | 👁️ ${(morphAlpha * 100).toInt()}%",
                        color = Color(0xFFB388FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- REAL-TIME EXPRESSION TELEMETRY STRIP ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x55111B2B), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ExpressionPill(label = "🗣️ Jaw", value = "${(trackingData.jawOpen * 100).toInt()}%")
                ExpressionPill(label = "😊 Smile", value = "${(trackingData.smileScore * 100).toInt()}%")
                ExpressionPill(label = "👁️ Blink", value = "${(trackingData.eyeBlinkScore * 100).toInt()}%")
                ExpressionPill(
                    label = "🧭 Yaw",
                    value = "${trackingData.rotationYaw.toInt()}°"
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- MORPH BLEND INTENSITY SELECTOR ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MORPH BLEND: ${(morphAlpha * 100).toInt()}%",
                    color = Color(0xFFB388FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.50f, 0.75f, 0.90f, 1.0f).forEach { alphaPreset ->
                        val isPresetActive = kotlin.math.abs(morphAlpha - alphaPreset) < 0.05f
                        val presetTag = "ALPHA_${(alphaPreset * 100).toInt()}"
                        Surface(
                            modifier = Modifier
                                .clickable { onMorphAlphaChanged(alphaPreset) }
                                .gazePinchSelectable(presetTag, spatialInteraction) { onMorphAlphaChanged(alphaPreset) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isPresetActive) Color(0xFF7C4DFF) else Color(0x33FFFFFF)
                        ) {
                            Text(
                                text = "${(alphaPreset * 100).toInt()}%",
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- QUICK PERSONA SWITCHER STRIP ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "SWITCH PERSONA IN MIRROR",
                    color = Color(0xFF80DEEA),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                if (activeStickers.isNotEmpty()) {
                    Text(
                        text = "✨ 3D: ${activeStickers.joinToString(" ") { it.icon }}",
                        color = Color(0xFFFF80AB),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val displayList = if (availablePersonas.isNotEmpty()) availablePersonas else OnlineFaceCatalog.getDefaultPersonas()
                displayList.forEach { dynPersona ->
                    val isSelected = (selectedDynamicPersona != null && selectedDynamicPersona.id == dynPersona.id) ||
                                     (selectedDynamicPersona == null && dynPersona.builtinPersona == selectedPersona)
                    val btnId = "MIRROR_PERSONA_${dynPersona.id}"
                    val isHovered = spatialInteraction.isHovered(btnId)

                    Surface(
                        modifier = Modifier
                            .width(86.dp)
                            .height(34.dp)
                            .clickable {
                                onDynamicPersonaSelected(dynPersona)
                                dynPersona.builtinPersona?.let { onPersonaSelected(it) }
                            }
                            .gazePinchSelectable(btnId, spatialInteraction) {
                                onDynamicPersonaSelected(dynPersona)
                                dynPersona.builtinPersona?.let { onPersonaSelected(it) }
                            },
                        shape = RoundedCornerShape(8.dp),
                        color = when {
                            isSelected -> Color(0xFF00E5FF)
                            isHovered -> Color(0xFF26C6DA)
                            else -> Color(0xFF1E2838)
                        },
                        border = if (isSelected || isHovered) BorderStroke(1.dp, Color.White) else null
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "${dynPersona.icon} ${dynPersona.displayName.split(" ").first()}",
                                color = if (isSelected) Color.Black else Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                    }
                }

                // "+" Add Face / 3D Sticker Button
                val isAddHovered = spatialInteraction.isHovered("MIRROR_ADD_BTN")
                Surface(
                    modifier = Modifier
                        .width(76.dp)
                        .height(34.dp)
                        .clickable { onOpenAddFace() }
                        .gazePinchSelectable("MIRROR_ADD_BTN", spatialInteraction) { onOpenAddFace() },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isAddHovered) Color(0x66FF007F) else Color(0xFF7C4DFF),
                    border = BorderStroke(1.dp, if (isAddHovered) Color(0xFFFF007F) else Color(0xFF00E5FF))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "➕ ADD",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- BOTTOM ACTIONS: SNAP PHOTO & CLOSE ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isSnapHovered = spatialInteraction.isHovered("MIRROR_SNAP_BTN")
                Button(
                    onClick = {
                        showFlashEffect = true
                        onTakeSnapshot()
                    },
                    modifier = Modifier
                        .weight(1.2f)
                        .height(42.dp)
                        .gazePinchSelectable("MIRROR_SNAP_BTN", spatialInteraction) {
                            showFlashEffect = true
                            onTakeSnapshot()
                        }
                        .then(
                            if (isSnapHovered)
                                Modifier.border(2.dp, Color(0xFFFFD740), RoundedCornerShape(12.dp))
                            else Modifier
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSnapHovered) Color(0xFFFFD740) else Color(0xFF00E5FF)
                    )
                ) {
                    Text(
                        text = if (isSnapHovered) "📸 SNAP! [PINCH]" else "📸 SNAP SELFIE",
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                val isCloseActionHovered = spatialInteraction.isHovered("MIRROR_DISMISS_BTN")
                Button(
                    onClick = onCloseMirror,
                    modifier = Modifier
                        .weight(0.8f)
                        .height(42.dp)
                        .gazePinchSelectable("MIRROR_DISMISS_BTN", spatialInteraction) { onCloseMirror() }
                        .then(
                            if (isCloseActionHovered)
                                Modifier.border(2.dp, Color(0xFFFF5252), RoundedCornerShape(12.dp))
                            else Modifier
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCloseActionHovered) Color(0xFFFF5252) else Color(0xFF37474F)
                    )
                ) {
                    Text(
                        text = "✕ CLOSE",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * MirrorFaceReflectionCanvas:
 * Renders an animated holographic 3D stylized face in the mirror
 * responding in real time to the user's yaw, pitch, roll, smile, jaw open, and blinks.
 */
@Composable
private fun MirrorFaceReflectionCanvas(
    persona: HumanPersona,
    dynamicPersona: DynamicPersona? = null,
    activeStickers: Set<Sticker3DType> = emptySet(),
    trackingData: FaceTrackingData,
    morphAlpha: Float,
    modifier: Modifier = Modifier
) {
    // Smooth animated values for expressions
    val animSmile by animateFloatAsState(
        targetValue = trackingData.smileScore.coerceIn(0f, 1f),
        animationSpec = tween(80),
        label = "smile"
    )
    val animJaw by animateFloatAsState(
        targetValue = trackingData.jawOpen.coerceIn(0f, 1f),
        animationSpec = tween(80),
        label = "jaw"
    )
    val animBlink by animateFloatAsState(
        targetValue = trackingData.eyeBlinkScore.coerceIn(0f, 1f),
        animationSpec = tween(60),
        label = "blink"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height

        // In a mirror, horizontal motion is flipped (mirrored)
        val mirrorYaw = -trackingData.rotationYaw
        val pitch = trackingData.rotationPitch
        val roll = trackingData.rotationRoll

        // Projected head center based on 3D rotation & roll tilt
        val headCenterX = width * 0.5f + (mirrorYaw * 1.8f) + (roll * 0.4f)
        val headCenterY = height * 0.48f + (pitch * 1.4f)
        val headRadiusX = width * 0.22f
        val headRadiusY = height * 0.28f

        if (persona == HumanPersona.SUNGLASSES) {
            drawStandaloneSunglasses(Offset(headCenterX,headCenterY-headRadiusY*.15f),
                headRadiusX*1.8f,morphAlpha)
            return@Canvas
        }

        // 1. Draw subtle holographic grid backdrop
        val gridColor = Color(0x1800E5FF)
        for (i in 0..6) {
            val y = height * (i / 6f)
            drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
        }
        for (i in 0..8) {
            val x = width * (i / 8f)
            drawLine(gridColor, Offset(x, 0f), Offset(x, height), strokeWidth = 1f)
        }

        // 2. Persona Base Colors
        val skinColor = if (dynamicPersona != null) {
            Color(dynamicPersona.primaryColorHex).copy(alpha = morphAlpha.coerceIn(0.35f, 1f))
        } else {
            when (persona) {
                HumanPersona.UNICORN_MEMOJI -> Color(0xFFFDFDFE)
                HumanPersona.LION_MEMOJI -> Color(0xFFFFB300)
                HumanPersona.DRAGON_MEMOJI -> Color(0xFF4CAF50)
                HumanPersona.MESSI_3D -> Color(0xFFDCA884)
                HumanPersona.CYBER_SYNTH -> Color(0xFF1E2E3E)
                HumanPersona.DIGITAL_TWIN -> Color(0xFFE2B296)
                HumanPersona.GOLDEN_SCULPTURE -> Color(0xFFD4AF37)
                HumanPersona.HOLO_HUMAN -> Color(0xFF00382B)
                HumanPersona.SUNGLASSES -> Color.Transparent
            }.copy(alpha = morphAlpha.coerceIn(0.35f, 1f))
        }
        val accentColor = if (dynamicPersona != null) {
            Color(dynamicPersona.accentColorHex)
        } else {
            when (persona) {
                HumanPersona.UNICORN_MEMOJI -> Color(0xFFE040FB)
                HumanPersona.LION_MEMOJI -> Color(0xFFBF360C)
                HumanPersona.DRAGON_MEMOJI -> Color(0xFFFFD600)
                HumanPersona.MESSI_3D -> Color(0xFF5D4037)
                HumanPersona.CYBER_SYNTH -> Color(0xFF00E5FF)
                HumanPersona.DIGITAL_TWIN -> Color(0xFF8D6E63)
                HumanPersona.GOLDEN_SCULPTURE -> Color(0xFFFFD700)
                HumanPersona.HOLO_HUMAN -> Color(0xFF00E676)
                HumanPersona.SUNGLASSES -> Color(0xFF527589)
            }
        }

        // 3. Head Oval
        drawOval(
            color = skinColor,
            topLeft = Offset(headCenterX - headRadiusX, headCenterY - headRadiusY),
            size = Size(headRadiusX * 2, headRadiusY * 2)
        )
        drawOval(
            color = accentColor,
            topLeft = Offset(headCenterX - headRadiusX, headCenterY - headRadiusY),
            size = Size(headRadiusX * 2, headRadiusY * 2),
            style = Stroke(width = 2.5f)
        )

        // 4. Persona Hair Silhouette
        when (persona) {
            HumanPersona.MESSI_3D -> {
                // Lionel Messi iconic hair and beard
                val hairPath = Path().apply {
                    moveTo(headCenterX - headRadiusX * 1.05f, headCenterY - headRadiusY * 0.3f)
                    cubicTo(
                        headCenterX - headRadiusX * 0.8f, headCenterY - headRadiusY * 1.35f,
                        headCenterX + headRadiusX * 0.8f, headCenterY - headRadiusY * 1.35f,
                        headCenterX + headRadiusX * 1.05f, headCenterY - headRadiusY * 0.3f
                    )
                    close()
                }
                drawPath(hairPath, color = Color(0xFF3E2723))

                // Messi signature beard
                val beardPath = Path().apply {
                    moveTo(headCenterX - headRadiusX * 0.7f, headCenterY + headRadiusY * 0.25f)
                    lineTo(headCenterX - headRadiusX * 0.55f, headCenterY + headRadiusY * 0.95f)
                    lineTo(headCenterX, headCenterY + headRadiusY * 1.08f)
                    lineTo(headCenterX + headRadiusX * 0.55f, headCenterY + headRadiusY * 0.95f)
                    lineTo(headCenterX + headRadiusX * 0.7f, headCenterY + headRadiusY * 0.25f)
                    close()
                }
                drawPath(beardPath, color = Color(0xBB4E342E))
            }
            HumanPersona.CYBER_SYNTH -> {
                // Cyber implants and circuitry lines
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = Offset(headCenterX - headRadiusX * 0.9f, headCenterY - headRadiusY * 0.5f),
                    end = Offset(headCenterX - headRadiusX * 0.4f, headCenterY - headRadiusY * 0.9f),
                    strokeWidth = 3f
                )
                drawLine(
                    color = Color(0xFF00E5FF),
                    start = Offset(headCenterX + headRadiusX * 0.9f, headCenterY - headRadiusY * 0.5f),
                    end = Offset(headCenterX + headRadiusX * 0.4f, headCenterY - headRadiusY * 0.9f),
                    strokeWidth = 3f
                )
            }
            HumanPersona.HOLO_HUMAN -> {
                // Matrix wireframe lines across face
                for (step in 1..4) {
                    val offsetY = headRadiusY * (step / 5f)
                    drawLine(
                        color = Color(0x9900E676),
                        start = Offset(headCenterX - headRadiusX * 0.8f, headCenterY - offsetY),
                        end = Offset(headCenterX + headRadiusX * 0.8f, headCenterY - offsetY),
                        strokeWidth = 1.5f
                    )
                    drawLine(
                        color = Color(0x9900E676),
                        start = Offset(headCenterX - headRadiusX * 0.8f, headCenterY + offsetY),
                        end = Offset(headCenterX + headRadiusX * 0.8f, headCenterY + offsetY),
                        strokeWidth = 1.5f
                    )
                }
            }
            else -> {}
        }

        // 5. Dynamic Eyes (Responding to Real-Time Blinking)
        val eyeEyeDist = headRadiusX * 0.45f
        val eyeY = headCenterY - headRadiusY * 0.15f
        val eyeRadiusX = headRadiusX * 0.20f
        val eyeOpenness = (1f - animBlink).coerceIn(0.1f, 1f)
        val eyeRadiusY = headRadiusY * 0.13f * eyeOpenness

        val eyePupilColor = when (persona) {
            HumanPersona.CYBER_SYNTH -> Color(0xFFFF1744) // Red laser optic
            HumanPersona.HOLO_HUMAN -> Color(0xFF00E676)
            HumanPersona.GOLDEN_SCULPTURE -> Color(0xFF5D4037)
            else -> Color(0xFF2E1C0C)
        }

        // Left Eye
        drawOval(
            color = Color.White,
            topLeft = Offset(headCenterX - eyeEyeDist - eyeRadiusX, eyeY - eyeRadiusY),
            size = Size(eyeRadiusX * 2, eyeRadiusY * 2)
        )
        if (eyeOpenness > 0.25f) {
            drawCircle(
                color = eyePupilColor,
                radius = eyeRadiusX * 0.6f * eyeOpenness,
                center = Offset(headCenterX - eyeEyeDist, eyeY)
            )
        }

        // Right Eye
        drawOval(
            color = Color.White,
            topLeft = Offset(headCenterX + eyeEyeDist - eyeRadiusX, eyeY - eyeRadiusY),
            size = Size(eyeRadiusX * 2, eyeRadiusY * 2)
        )
        if (eyeOpenness > 0.25f) {
            drawCircle(
                color = eyePupilColor,
                radius = eyeRadiusX * 0.6f * eyeOpenness,
                center = Offset(headCenterX + eyeEyeDist, eyeY)
            )
        }

        // 6. Dynamic Mouth (Responding to Jaw Open & Smile Score)
        val mouthY = headCenterY + headRadiusY * 0.45f
        val mouthWidth = headRadiusX * (0.55f + animSmile * 0.25f)
        val mouthOpenHeight = headRadiusY * (0.04f + animJaw * 0.35f)

        if (animJaw > 0.12f) {
            // Open mouth cavity
            drawRoundRect(
                color = Color(0xFF4A121A),
                topLeft = Offset(headCenterX - mouthWidth * 0.5f, mouthY),
                size = Size(mouthWidth, mouthOpenHeight),
                cornerRadius = CornerRadius(10f, 10f)
            )
        } else {
            // Closed/smiling mouth line
            val smileCurve = animSmile * 12f
            val mouthPath = Path().apply {
                moveTo(headCenterX - mouthWidth * 0.5f, mouthY - smileCurve)
                quadraticTo(
                    headCenterX, mouthY + smileCurve,
                    headCenterX + mouthWidth * 0.5f, mouthY - smileCurve
                )
            }
            drawPath(mouthPath, color = Color(0xFF5C1B23), style = Stroke(width = 3.5f, cap = StrokeCap.Round))
        }

        // 7. Holographic Target Reticle
        val reticleRadius = headRadiusY * 1.25f
        drawCircle(
            color = Color(0x3300E5FF),
            radius = reticleRadius,
            center = Offset(headCenterX, headCenterY),
            style = Stroke(width = 1.5f)
        )

        // 8. 3D Holographic Stickers & AR Accessories
        activeStickers.forEach { sticker ->
            when (sticker) {
                Sticker3DType.NEON_CROWN -> {
                    // Floating golden/cyan laser crown with 5 spikes above head
                    val crownBaseY = headCenterY - headRadiusY * 0.95f
                    val crownTopY = headCenterY - headRadiusY * 1.35f
                    val crownHalfWidth = headRadiusX * 0.85f

                    // Crown base ring
                    drawOval(
                        color = Color(0xFFFFD700),
                        topLeft = Offset(headCenterX - crownHalfWidth, crownBaseY - 6f),
                        size = Size(crownHalfWidth * 2, 12f),
                        style = Stroke(width = 3f)
                    )

                    // 5 Crown Spikes
                    val spikeCount = 5
                    for (i in 0 until spikeCount) {
                        val t = i.toFloat() / (spikeCount - 1)
                        val sx = headCenterX - crownHalfWidth + (crownHalfWidth * 2 * t)
                        val isCenterSpike = i == 2
                        val sy = if (isCenterSpike) crownTopY - 10f else if (i == 1 || i == 3) crownTopY else crownTopY + 12f

                        val spikePath = Path().apply {
                            moveTo(sx - 10f, crownBaseY)
                            lineTo(sx, sy)
                            lineTo(sx + 10f, crownBaseY)
                            close()
                        }
                        drawPath(spikePath, color = Color(0x99FFD700))
                        drawPath(spikePath, color = Color(0xFF00E5FF), style = Stroke(width = 2f))

                        // Glowing gem on spike tip
                        drawCircle(
                            color = Color(0xFF00E5FF),
                            radius = if (isCenterSpike) 5.5f else 4f,
                            center = Offset(sx, sy)
                        )
                    }
                }

                Sticker3DType.CYBER_VISOR -> {
                    // Futuristic cybernetic eye visor with laser scanlines
                    val visorWidth = headRadiusX * 1.55f
                    val visorHeight = headRadiusY * 0.38f
                    val visorY = eyeY - visorHeight * 0.45f

                    val visorPath = Path().apply {
                        moveTo(headCenterX - visorWidth * 0.5f, visorY)
                        lineTo(headCenterX + visorWidth * 0.5f, visorY)
                        lineTo(headCenterX + visorWidth * 0.42f, visorY + visorHeight)
                        lineTo(headCenterX - visorWidth * 0.42f, visorY + visorHeight)
                        close()
                    }
                    drawPath(visorPath, color = Color(0xCC001824))
                    drawPath(visorPath, color = Color(0xFF00E5FF), style = Stroke(width = 2.5f))

                    // Center target reticle line
                    drawLine(
                        color = Color(0xFFFF007F),
                        start = Offset(headCenterX - visorWidth * 0.45f, eyeY),
                        end = Offset(headCenterX + visorWidth * 0.45f, eyeY),
                        strokeWidth = 2f
                    )
                    drawCircle(color = Color(0xFFFF007F), radius = 6f, center = Offset(headCenterX, eyeY), style = Stroke(width = 2f))
                }

                Sticker3DType.NEON_HORNS -> {
                    // Curved luminescent horns curving up from temples
                    val hornBaseLeftX = headCenterX - headRadiusX * 0.75f
                    val hornBaseRightX = headCenterX + headRadiusX * 0.75f
                    val hornBaseY = headCenterY - headRadiusY * 0.65f

                    // Left Horn
                    val leftHorn = Path().apply {
                        moveTo(hornBaseLeftX, hornBaseY)
                        quadraticTo(
                            hornBaseLeftX - headRadiusX * 0.7f, hornBaseY - headRadiusY * 0.4f,
                            hornBaseLeftX - headRadiusX * 0.5f, hornBaseY - headRadiusY * 0.9f
                        )
                        quadraticTo(
                            hornBaseLeftX - headRadiusX * 0.3f, hornBaseY - headRadiusY * 0.4f,
                            hornBaseLeftX + 12f, hornBaseY - 6f
                        )
                        close()
                    }
                    drawPath(leftHorn, color = Color(0xCCB71C1C))
                    drawPath(leftHorn, color = Color(0xFFFF1744), style = Stroke(width = 2f))

                    // Right Horn
                    val rightHorn = Path().apply {
                        moveTo(hornBaseRightX, hornBaseY)
                        quadraticTo(
                            hornBaseRightX + headRadiusX * 0.7f, hornBaseY - headRadiusY * 0.4f,
                            hornBaseRightX + headRadiusX * 0.5f, hornBaseY - headRadiusY * 0.9f
                        )
                        quadraticTo(
                            hornBaseRightX + headRadiusX * 0.3f, hornBaseY - headRadiusY * 0.4f,
                            hornBaseRightX - 12f, hornBaseY - 6f
                        )
                        close()
                    }
                    drawPath(rightHorn, color = Color(0xCCB71C1C))
                    drawPath(rightHorn, color = Color(0xFFFF1744), style = Stroke(width = 2f))
                }

                Sticker3DType.CAT_EARS -> {
                    // Holographic robotic/neko cat ears
                    val earBaseY = headCenterY - headRadiusY * 0.85f
                    val earTopY = headCenterY - headRadiusY * 1.35f

                    // Left Ear
                    val leftEar = Path().apply {
                        moveTo(headCenterX - headRadiusX * 0.85f, earBaseY)
                        lineTo(headCenterX - headRadiusX * 0.65f, earTopY)
                        lineTo(headCenterX - headRadiusX * 0.25f, earBaseY - 10f)
                        close()
                    }
                    drawPath(leftEar, color = Color(0xCCFF4081))
                    drawPath(leftEar, color = Color.White, style = Stroke(width = 2f))

                    // Right Ear
                    val rightEar = Path().apply {
                        moveTo(headCenterX + headRadiusX * 0.85f, earBaseY)
                        lineTo(headCenterX + headRadiusX * 0.65f, earTopY)
                        lineTo(headCenterX + headRadiusX * 0.25f, earBaseY - 10f)
                        close()
                    }
                    drawPath(rightEar, color = Color(0xCCFF4081))
                    drawPath(rightEar, color = Color.White, style = Stroke(width = 2f))
                }

                Sticker3DType.RETRO_MUSTACHE -> {
                    // Handlebar mustache below nose & golden monocle over right eye
                    val stacheY = headCenterY + headRadiusY * 0.35f
                    val stacheWidth = headRadiusX * 0.7f

                    val stacheLeft = Path().apply {
                        moveTo(headCenterX, stacheY)
                        quadraticTo(
                            headCenterX - stacheWidth * 0.6f, stacheY - 6f,
                            headCenterX - stacheWidth, stacheY - 14f
                        )
                        quadraticTo(
                            headCenterX - stacheWidth * 0.5f, stacheY + 12f,
                            headCenterX, stacheY + 4f
                        )
                        close()
                    }
                    val stacheRight = Path().apply {
                        moveTo(headCenterX, stacheY)
                        quadraticTo(
                            headCenterX + stacheWidth * 0.6f, stacheY - 6f,
                            headCenterX + stacheWidth, stacheY - 14f
                        )
                        quadraticTo(
                            headCenterX + stacheWidth * 0.5f, stacheY + 12f,
                            headCenterX, stacheY + 4f
                        )
                        close()
                    }
                    drawPath(stacheLeft, color = Color(0xFF3E2723))
                    drawPath(stacheRight, color = Color(0xFF3E2723))

                    // Monocle over right eye
                    val monocleCenter = Offset(headCenterX + eyeEyeDist, eyeY)
                    drawCircle(color = Color(0x5500E5FF), radius = eyeRadiusX * 1.5f, center = monocleCenter)
                    drawCircle(color = Color(0xFFFFD700), radius = eyeRadiusX * 1.5f, center = monocleCenter, style = Stroke(width = 2.5f))
                }

                Sticker3DType.CYBER_STAR -> {
                    // Glowing cybernetic 4-pointed star matrix tattoo on left cheek
                    val starCenter = Offset(headCenterX - headRadiusX * 0.55f, headCenterY + headRadiusY * 0.22f)
                    val starSize = 16f
                    val starPath = Path().apply {
                        moveTo(starCenter.x, starCenter.y - starSize)
                        quadraticTo(starCenter.x, starCenter.y, starCenter.x + starSize, starCenter.y)
                        quadraticTo(starCenter.x, starCenter.y, starCenter.x, starCenter.y + starSize)
                        quadraticTo(starCenter.x, starCenter.y, starCenter.x - starSize, starCenter.y)
                        quadraticTo(starCenter.x, starCenter.y, starCenter.x, starCenter.y - starSize)
                        close()
                    }
                    drawPath(starPath, color = Color(0xCC76FF03))
                    drawPath(starPath, color = Color(0xFF00E5FF), style = Stroke(width = 1.5f))
                }
            }
        }
    }
}

@Composable
private fun ExpressionPill(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = Color.Gray, fontSize = 10.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = value, color = Color(0xFF00E5FF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
