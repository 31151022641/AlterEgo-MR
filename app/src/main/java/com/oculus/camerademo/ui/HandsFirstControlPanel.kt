package com.oculus.camerademo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oculus.camerademo.ml.FaceTrackingData
import com.oculus.camerademo.model3d.DynamicPersona
import com.oculus.camerademo.model3d.HumanPersona
import com.oculus.camerademo.model3d.OnlineFaceCatalog
import com.oculus.camerademo.model3d.PersonaSource
import com.oculus.camerademo.model3d.Sticker3DType
import com.oculus.camerademo.renderer.ResolutionQuality

@Composable
fun HandsFirstControlPanel(
    trackingData: FaceTrackingData,
    selectedPersona: HumanPersona,
    isMockSource: Boolean,
    isFaceInEyeGaze: Boolean,
    targetingModeLabel: String = "Head direction",
    showTargetingCursor: Boolean = true,
    headScale: Float,
    resolutionQuality: ResolutionQuality,
    trackedHeadsCount: Int = 1,
    gazeLockedHeadId: Int? = null,
    isPartyModeEnabled: Boolean = false,
    isPartyDiversePersonas: Boolean = false,
    isVoiceSpeakerEnabled: Boolean = true,
    lastVoiceAnnouncement: String? = null,
    availablePersonas: List<DynamicPersona> = emptyList(),
    selectedDynamicPersona: DynamicPersona? = null,
    activeStickers: Set<Sticker3DType> = emptySet(),
    spatialInteraction: SpatialMenuInteraction = remember { SpatialMenuInteraction() },
    onPersonaSelected: (HumanPersona) -> Unit,
    onDynamicPersonaSelected: (DynamicPersona) -> Unit = {},
    onOpenAddFace: () -> Unit = {},
    onToggleGazeSource: () -> Unit,
    onToggleCameraSource: () -> Unit,
    onTogglePartyMode: (Boolean) -> Unit = {},
    onTogglePartyDiversePersonas: (Boolean) -> Unit = {},
    onToggleVoiceSpeaker: (Boolean) -> Unit = {},
    onTakeSnapshot: () -> Unit,
    onOpenSettings: () -> Unit,
    onCloseMenu: () -> Unit,
    onToggleMirror: () -> Unit = {},
    isMirrorOpen: Boolean = false,
    onQuitApp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 860.dp)
                .fillMaxWidth(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xEE121824),
            border = BorderStroke(1.dp, Color(0x6600E5FF)),
            shadowElevation = 12.dp
        ) {
            val panelScrollState = rememberScrollState()
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .fillMaxWidth()
                    .verticalScroll(panelScrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Bar with Title, Gaze Status, Settings Button, and Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ALTEREGO MR",
                                color = Color(0xFF00E5FF),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            // Hand & Controller Guidance Badge
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0x3300E5FF),
                                border = BorderStroke(0.5.dp, Color(0x6600E5FF))
                            ) {
                                Text(
                                    text = "🥽 Direct Touch & Pinch / 🎮 Menu Key (≡)",
                                    color = Color(0xFF80DEEA),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "$targetingModeLabel selects faces • Tap buttons directly or pinch",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Gaze Mode Indicator Pill
                        Surface(
                            color = if (isFaceInEyeGaze) Color(0xFF00C853) else Color(0xFF37474F),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.clickable { onToggleGazeSource() }
                        ) {
                            Text(
                                text = "$targetingModeLabel: ${if (isFaceInEyeGaze) "LOCKED" else "AIM AT A FACE"}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        // Voice Speaker Announcer Toggle Pill
                        val isVoiceHovered = spatialInteraction.isHovered("VOICE_SPEAKER_TOGGLE")
                        Surface(
                            color = if (isVoiceSpeakerEnabled) Color(0xFF1E88E5) else Color(0xFF424242),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onToggleVoiceSpeaker(!isVoiceSpeakerEnabled) }
                                .gazePinchSelectable("VOICE_SPEAKER_TOGGLE", spatialInteraction) {
                                    onToggleVoiceSpeaker(!isVoiceSpeakerEnabled)
                                }
                                .then(
                                    if (isVoiceHovered)
                                        Modifier.border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
                                    else Modifier
                                )
                        ) {
                            Text(
                                text = if (isVoiceSpeakerEnabled) "🗣️ VOICE ON" else "🔇 VOICE OFF",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        // Settings Button (Opens Settings Pop-Up Box) - Vision Pro & Controller Enabled
                        val isSettingsHovered = spatialInteraction.isHovered("SETTINGS_BTN")
                        Button(
                            onClick = onOpenSettings,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isSettingsHovered) Color(0xFF00ACC1) else Color(0xFF00838F)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .gazePinchSelectable("SETTINGS_BTN", spatialInteraction) { onOpenSettings() }
                                .then(
                                    if (isSettingsHovered)
                                        Modifier.border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
                                    else Modifier
                                )
                        ) {
                            Text(
                                text = if (isSettingsHovered) "👁️ SETTINGS" else "⚙️ SETTINGS",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        // Quit App Button - Red Danger Pill (Vision Pro & Controller Enabled)
                        val isQuitHovered = spatialInteraction.isHovered("QUIT_BTN")
                        Button(
                            onClick = onQuitApp,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isQuitHovered) Color(0xFFFF1744) else Color(0xFFC62828)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .gazePinchSelectable("QUIT_BTN", spatialInteraction) { onQuitApp() }
                                .then(
                                    if (isQuitHovered)
                                        Modifier.border(2.dp, Color(0xFFFF5252), RoundedCornerShape(12.dp))
                                    else Modifier
                                )
                        ) {
                            Text(
                                text = if (isQuitHovered) "👁️ QUIT" else "🚪 QUIT",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }

                        // Close Menu Button - Vision Pro & Controller Enabled
                        val isCloseHovered = spatialInteraction.isHovered("CLOSE_BTN")
                        IconButton(
                            onClick = onCloseMenu,
                            modifier = Modifier
                                .size(32.dp)
                                .background(
                                    if (isCloseHovered) Color(0x66FF5252) else Color(0x22FFFFFF),
                                    CircleShape
                                )
                                .gazePinchSelectable("CLOSE_BTN", spatialInteraction) { onCloseMenu() }
                                .then(
                                    if (isCloseHovered)
                                        Modifier.border(2.dp, Color(0xFFFF5252), CircleShape)
                                    else Modifier
                                )
                        ) {
                            Text(
                                text = "✕",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Apple Vision Pro & Touch Controller Look-and-Pinch Prompt Banner
                SpatialControlPromptBanner(interaction = spatialInteraction)

                // Voice Speaker Announcement Status Banner
                if (!lastVoiceAnnouncement.isNullOrEmpty() && isVoiceSpeakerEnabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0x3300E5FF),
                        border = BorderStroke(1.dp, Color(0x6600E5FF))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "🗣️", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Spoken: \"$lastVoiceAnnouncement\"",
                                color = Color(0xFF80DEEA),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ==========================================
                // MR TRACKING MODE SWITCHER (FOCUS VS PARTY)
                // ==========================================
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0x33000000),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0x3300E5FF))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "MR TRACKING MODE:",
                                    color = Color.LightGray,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isPartyModeEnabled) "🎉 PARTY (ALL HEADS MORPHED)" else "🎯 FOCUS (1 HEAD GAZE-LOCKED)",
                                    color = if (isPartyModeEnabled) Color(0xFFFFD740) else Color(0xFF00E5FF),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            // Diverse All-Stars chip (visible when Party Mode is enabled)
                            if (isPartyModeEnabled) {
                                val isDiverseHovered = spatialInteraction.isHovered("PARTY_DIVERSE_TOGGLE")
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (isPartyDiversePersonas) Color(0x55E040FB) else Color(0x22FFFFFF),
                                    border = BorderStroke(
                                        if (isDiverseHovered) 1.5.dp else 1.dp,
                                        if (isDiverseHovered) Color(0xFF00E5FF) else if (isPartyDiversePersonas) Color(0xFFE040FB) else Color(0x44FFFFFF)
                                    ),
                                    modifier = Modifier
                                        .clickable { onTogglePartyDiversePersonas(!isPartyDiversePersonas) }
                                        .gazePinchSelectable("PARTY_DIVERSE_TOGGLE", spatialInteraction) {
                                            onTogglePartyDiversePersonas(!isPartyDiversePersonas)
                                        }
                                ) {
                                    Text(
                                        text = if (isPartyDiversePersonas) "🎭 DIVERSE ALL-STARS ON" else "🎭 UNIFORM PERSONA",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPartyDiversePersonas) Color(0xFFFF80AB) else Color.LightGray,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // 1. Focus Mode Button
                            val isFocusHovered = spatialInteraction.isHovered("MODE_FOCUS")
                            val isFocusSelected = !isPartyModeEnabled
                            Button(
                                onClick = { onTogglePartyMode(false) },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        isFocusSelected -> Color(0xFF00838F)
                                        isFocusHovered -> Color(0xFF004D40)
                                        else -> Color(0x33FFFFFF)
                                    }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .gazePinchSelectable("MODE_FOCUS", spatialInteraction) { onTogglePartyMode(false) }
                                    .then(
                                        if (isFocusHovered || isFocusSelected)
                                            Modifier.border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
                                        else Modifier
                                    ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isFocusHovered) "👁️ [PINCH] 🎯 FOCUS" else "🎯 FOCUS (1 HEAD)",
                                    fontSize = 10.sp,
                                    fontWeight = if (isFocusSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.White
                                )
                            }

                            // 2. Party Mode Button
                            val isPartyHovered = spatialInteraction.isHovered("MODE_PARTY")
                            val isPartySelected = isPartyModeEnabled
                            Button(
                                onClick = { onTogglePartyMode(true) },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when {
                                        isPartySelected -> Color(0xFFF57F17)
                                        isPartyHovered -> Color(0xFFE65100)
                                        else -> Color(0x33FFFFFF)
                                    }
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(38.dp)
                                    .gazePinchSelectable("MODE_PARTY", spatialInteraction) { onTogglePartyMode(true) }
                                    .then(
                                        if (isPartyHovered || isPartySelected)
                                            Modifier.border(1.5.dp, Color(0xFFFFD740), RoundedCornerShape(12.dp))
                                        else Modifier
                                    ),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (isPartyHovered) "👁️ [PINCH] 🎉 PARTY" else "🎉 PARTY (ALL HEADS)",
                                    fontSize = 10.sp,
                                    fontWeight = if (isPartySelected) FontWeight.Bold else FontWeight.Normal,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ==========================================
                // CHANGING HEAD FILTER (PERSONA SELECTOR)
                // ==========================================
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "CHANGING HEAD FILTER",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (activeStickers.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0x3300E5FF),
                                border = BorderStroke(0.5.dp, Color(0xFF00E5FF))
                            ) {
                                Text(
                                    text = "✨ 3D: ${activeStickers.joinToString(" ") { it.icon }}",
                                    color = Color(0xFF80DEEA),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "👁️ Look + 👌 Pinch / [A]",
                        color = Color(0xFF80DEEA),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayPersonas = if (availablePersonas.isNotEmpty()) availablePersonas else OnlineFaceCatalog.getDefaultPersonas()
                    displayPersonas.forEach { dynPersona ->
                        val targetId = "PERSONA_${dynPersona.id}"
                        val isGazeFocused = spatialInteraction.isHovered(targetId)
                        val isSelected = (selectedDynamicPersona != null && selectedDynamicPersona.id == dynPersona.id) ||
                                         (selectedDynamicPersona == null && dynPersona.builtinPersona == selectedPersona)
                        val cardScale by animateFloatAsState(
                            if (isGazeFocused) 1.06f else 1.0f,
                            label = "CardScale"
                        )
                        val borderColor by animateColorAsState(
                            when {
                                isGazeFocused -> Color(0xFF00E5FF)
                                isSelected -> Color(0xFF00E5FF)
                                else -> Color(0x33FFFFFF)
                            },
                            label = "BorderColor"
                        )

                        Surface(
                            modifier = Modifier
                                .width(96.dp)
                                .height(78.dp)
                                .scale(cardScale)
                                .clip(RoundedCornerShape(14.dp))
                                .border(if (isGazeFocused) 3.dp else 2.dp, borderColor, RoundedCornerShape(14.dp))
                                .clickable {
                                    onDynamicPersonaSelected(dynPersona)
                                    dynPersona.builtinPersona?.let { onPersonaSelected(it) }
                                }
                                .gazePinchSelectable(targetId, spatialInteraction) {
                                    onDynamicPersonaSelected(dynPersona)
                                    dynPersona.builtinPersona?.let { onPersonaSelected(it) }
                                },
                            color = when {
                                isGazeFocused -> Color(0x6600E5FF)
                                isSelected -> Color(0x4400E5FF)
                                else -> Color(0x22FFFFFF)
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                if (isGazeFocused) {
                                    Text(
                                        text = "👁️ PINCH",
                                        fontSize = 8.sp,
                                        color = Color(0xFF00E5FF),
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(Color(dynPersona.primaryColorHex)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = dynPersona.icon, fontSize = 9.sp)
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (dynPersona.builtinPersona == HumanPersona.MESSI_3D) "⭐ MESSI" else dynPersona.displayName.split(" ").first(),
                                    color = if (dynPersona.builtinPersona == HumanPersona.MESSI_3D) Color(0xFFFFD740) else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected || isGazeFocused) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                                if (dynPersona.source != PersonaSource.BUILTIN) {
                                    Text(
                                        text = dynPersona.source.label.split(" ").first(),
                                        color = Color(dynPersona.source.badgeColor),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // "+" Icon button to Add Face from Online / External or Equip 3D Stickers
                    val isAddHovered = spatialInteraction.isHovered("ADD_FACE_PLUS_BTN")
                    val addScale by animateFloatAsState(if (isAddHovered) 1.06f else 1.0f, label = "AddScale")
                    Surface(
                        modifier = Modifier
                            .width(86.dp)
                            .height(78.dp)
                            .scale(addScale)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                2.dp,
                                if (isAddHovered) Color(0xFFFF007F) else Color(0xFF7C4DFF),
                                RoundedCornerShape(14.dp)
                            )
                            .clickable { onOpenAddFace() }
                            .gazePinchSelectable("ADD_FACE_PLUS_BTN", spatialInteraction) { onOpenAddFace() },
                        color = if (isAddHovered) Color(0x66FF007F) else Color(0x447C4DFF)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = "➕", fontSize = 18.sp)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "ADD FACE",
                                color = Color(0xFF00E5FF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "+ 3D Sticker",
                                color = Color(0xFFFF80AB),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

            Spacer(modifier = Modifier.height(10.dp))

            // Real-Time Eye Tracking, Head Scale & Peripheral Telemetry
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0x44000000),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TelemetryItem(
                        label = "Tracked Objects",
                        value = "${trackedHeadsCount} HEAD${if (trackedHeadsCount > 1) "S" else ""}",
                        color = if (trackedHeadsCount > 0) Color(0xFF00E5FF) else Color.Gray
                    )
                    TelemetryItem(
                        label = "3D Head Mode",
                        value = if (isPartyModeEnabled) "🎉 PARTY (${trackedHeadsCount} MORPHED)" else if (isFaceInEyeGaze) "SELECTED FACE #$gazeLockedHeadId" else "AIM AT A FACE",
                        color = if (isPartyModeEnabled) Color(0xFFFFD740) else if (isFaceInEyeGaze) Color(0xFF00E676) else Color(0xFFFF9100)
                    )
                    TelemetryItem(
                        label = "Head Scale",
                        value = if (kotlin.math.abs(headScale - 1.0f) < 0.05f) "1.0x (OBJECT)" else "${String.format("%.2f", headScale)}x",
                        color = if (kotlin.math.abs(headScale - 1.0f) < 0.05f) Color(0xFF00E676) else Color(0xFFFFD740)
                    )
                    TelemetryItem(
                        label = "Peripheral Res",
                        value = resolutionQuality.displayName.uppercase(),
                        color = when (resolutionQuality) {
                            ResolutionQuality.HIGH -> Color(0xFF00E5FF)
                            ResolutionQuality.BALANCED -> Color(0xFFFFD740)
                            ResolutionQuality.PERFORMANCE -> Color(0xFFFF5252)
                        }
                    )
                    TelemetryItem(
                        label = "Emotion / Face",
                        value = "${trackingData.emotion.dominantEmotion.emoji} ${trackingData.emotion.dominantEmotion.title.uppercase()}",
                        color = when (trackingData.emotion.dominantEmotion) {
                            com.oculus.camerademo.ml.RecognizedEmotion.WINKING_LEFT,
                            com.oculus.camerademo.ml.RecognizedEmotion.WINKING_RIGHT -> Color(0xFFFFD740)
                            com.oculus.camerademo.ml.RecognizedEmotion.HAPPY_SMILE -> Color(0xFF00E676)
                            com.oculus.camerademo.ml.RecognizedEmotion.SURPRISED -> Color(0xFF00E5FF)
                            com.oculus.camerademo.ml.RecognizedEmotion.OPEN_MOUTH -> Color(0xFFFF4081)
                            com.oculus.camerademo.ml.RecognizedEmotion.POUTING_PUCKER -> Color(0xFFE040FB)
                            com.oculus.camerademo.ml.RecognizedEmotion.FROWNING,
                            com.oculus.camerademo.ml.RecognizedEmotion.ANGRY_FOCUSED -> Color(0xFFFF5252)
                            com.oculus.camerademo.ml.RecognizedEmotion.NEUTRAL -> Color.White
                        }
                    )
                }
            }

            // Real-Time Facial Reaction & Emotion Indicator
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                color = Color(0x3300E5FF),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(0.5.dp, Color(0x4400E5FF))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val distMeters = trackingData.headDistanceMeters
                        val distLabel = when {
                            distMeters < 0.9f -> "CLOSE-UP"
                            distMeters > 2.2f -> "FAR AWAY"
                            else -> "NORMAL"
                        }
                        Text(
                            text = "📏 Depth: ${String.format("%.2f", distMeters)}m [$distLabel] | ${trackingData.emotion.dominantEmotion.emoji} ${trackingData.emotion.dominantEmotion.description}",
                            color = Color(0xFF80DEEA),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "L-Eye: ${(trackingData.emotion.eyeBlinkLeft * 100).toInt()}% | R-Eye: ${(trackingData.emotion.eyeBlinkRight * 100).toInt()}% | Jaw: ${(trackingData.emotion.jawOpen * 100).toInt()}%",
                            color = Color.LightGray,
                            fontSize = 9.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    // Live 3D Head Rotation Telemetry (Turn Left/Right, Head Up/Down, Tilt Roll)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val yaw = trackingData.rotationYaw.toInt()
                        val pitch = trackingData.rotationPitch.toInt()
                        val roll = trackingData.rotationRoll.toInt()
                        val yawDir = when {
                            yaw > 8 -> "TURN LEFT"
                            yaw < -8 -> "TURN RIGHT"
                            else -> "CENTER"
                        }
                        val pitchDir = when {
                            pitch > 6 -> "HEAD UP"
                            pitch < -6 -> "HEAD DOWN"
                            else -> "LEVEL"
                        }
                        Text(
                            text = "🔄 3D Pose: Yaw ${if (yaw >= 0) "+$yaw" else "$yaw"}° [$yawDir] | Pitch ${if (pitch >= 0) "+$pitch" else "$pitch"}° [$pitchDir] | Roll ${if (roll >= 0) "+$roll" else "$roll"}°",
                            color = Color(0xFFFFD740),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "6-DoF Rotation: LIVE",
                            color = Color(0xFF00E676),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row & Left Menu shortcut info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isCamHovered = spatialInteraction.isHovered("CAMERA_SOURCE_BTN")
                Button(
                    onClick = onToggleCameraSource,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .gazePinchSelectable("CAMERA_SOURCE_BTN", spatialInteraction) { onToggleCameraSource() }
                        .then(
                            if (isCamHovered)
                                Modifier.border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
                            else Modifier
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isCamHovered) Color(0xFF00ACC1) else if (isMockSource) Color(0xFF4527A0) else Color(0xFF00695C)
                    )
                ) {
                    Text(
                        text = if (isCamHovered) "👁️ [PINCH/A]" else if (isMockSource) "Simulation Stream" else "Live Headset Camera",
                        fontSize = 11.sp
                    )
                }

                val isSnapHovered = spatialInteraction.isHovered("SNAPSHOT_BTN")
                Button(
                    onClick = onTakeSnapshot,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .gazePinchSelectable("SNAPSHOT_BTN", spatialInteraction) { onTakeSnapshot() }
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
                        text = if (isSnapHovered) "📸 PINCH / [A] SNAP" else "📸 SNAP MR PHOTO",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                val isMirrorHovered = spatialInteraction.isHovered("PANEL_MIRROR_BTN")
                Button(
                    onClick = onToggleMirror,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .gazePinchSelectable("PANEL_MIRROR_BTN", spatialInteraction) { onToggleMirror() }
                        .then(
                            if (isMirrorHovered)
                                Modifier.border(2.dp, Color(0xFFE040FB), RoundedCornerShape(12.dp))
                            else Modifier
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isMirrorOpen) Color(0xFF7C4DFF) else if (isMirrorHovered) Color(0xFF8E24AA) else Color(0xFF4A148C)
                    )
                ) {
                    Text(
                        text = if (isMirrorOpen) "🪞 MIRROR ON" else "🪞 MIRROR",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                val isFooterQuitHovered = spatialInteraction.isHovered("FOOTER_QUIT_BTN")
                Button(
                    onClick = onQuitApp,
                    modifier = Modifier
                        .weight(0.75f)
                        .height(44.dp)
                        .gazePinchSelectable("FOOTER_QUIT_BTN", spatialInteraction) { onQuitApp() }
                        .then(
                            if (isFooterQuitHovered)
                                Modifier.border(2.dp, Color(0xFFFF5252), RoundedCornerShape(12.dp))
                            else Modifier
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFooterQuitHovered) Color(0xFFFF1744) else Color(0xFFB71C1C)
                    )
                ) {
                    Text(
                        text = if (isFooterQuitHovered) "🚪 QUIT!" else "🚪 QUIT",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Vision Pro Floating Eye Gaze Cursor Indicator
        if (showTargetingCursor) VisionProGazeCursor(interaction = spatialInteraction)
    }
}
}

/**
 * Quick Action Menu in the Hand / Wrist:
 * Accessible right in front of the hand in passthrough immersion.
 * Features 1-tap/1-pinch access to:
 * 1. 🎮 QUICK MENU (Expands full spatial controls)
 * 2. 🚪 QUIT APP (Instantly exits application)
 */
@Composable
fun HandQuickActionBar(
    spatialInteraction: SpatialMenuInteraction,
    onOpenMenu: () -> Unit,
    onToggleMirror: () -> Unit = {},
    isMirrorOpen: Boolean = false,
    onQuitApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .padding(16.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color(0xEE101622),
        border = BorderStroke(1.5.dp, Color(0xFF00E5FF)),
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Hand Quick Action Menu Button
            val isMenuHovered = spatialInteraction.isHovered("QUICK_MENU_BTN")
            Button(
                onClick = onOpenMenu,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMenuHovered) Color(0xFF00E5FF) else Color(0xFF00838F)
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier
                    .gazePinchSelectable("QUICK_MENU_BTN", spatialInteraction) { onOpenMenu() }
                    .then(
                        if (isMenuHovered)
                            Modifier.border(2.dp, Color.White, RoundedCornerShape(14.dp))
                        else Modifier
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "📋", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = if (isMenuHovered) "👁️ OPEN 2D MENU" else "📋 OPEN 2D MENU",
                            color = if (isMenuHovered) Color.Black else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text(
                            text = "👉 Tap with Finger / Pinch",
                            color = if (isMenuHovered) Color.DarkGray else Color(0xFF00E5FF),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Quick Mirror Toggle Button
            val isMirrorHovered = spatialInteraction.isHovered("QUICK_MIRROR_BTN")
            Button(
                onClick = onToggleMirror,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMirrorOpen) Color(0xFF7C4DFF) else if (isMirrorHovered) Color(0xFF8E24AA) else Color(0xFF4A148C)
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .gazePinchSelectable("QUICK_MIRROR_BTN", spatialInteraction) { onToggleMirror() }
                    .then(
                        if (isMirrorHovered)
                            Modifier.border(2.dp, Color(0xFFE040FB), RoundedCornerShape(14.dp))
                        else Modifier
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🪞", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isMirrorOpen) "MIRROR ON" else "MIRROR",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Quick Recenter In Front of Gaze / Head
            Button(
                onClick = onOpenMenu,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🎯", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "RECENTER",
                        color = Color.Black,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // Quick Quit Button
            val isQuitHovered = spatialInteraction.isHovered("QUICK_QUIT_BTN")
            Button(
                onClick = onQuitApp,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isQuitHovered) Color(0xFFFF1744) else Color(0xFFC62828)
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier
                    .gazePinchSelectable("QUICK_QUIT_BTN", spatialInteraction) { onQuitApp() }
                    .then(
                        if (isQuitHovered)
                            Modifier.border(2.dp, Color(0xFFFF8A80), RoundedCornerShape(14.dp))
                        else Modifier
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🚪", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isQuitHovered) "QUIT APP" else "QUIT",
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
 * Floating Quick Button visible when the main menu is closed (legacy alias).
 */
@Composable
fun FloatingMenuOpenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    HandQuickActionBar(
        spatialInteraction = remember { SpatialMenuInteraction() },
        onOpenMenu = onClick,
        onQuitApp = {},
        modifier = modifier
    )
}

@Composable
private fun TelemetryItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, color = Color.Gray, fontSize = 9.sp)
        Text(text = value, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * HandDockBar:
 * Persistent floating Quick-Action Dock optimized for controller-less hand tracking (poke and pinch).
 * Always accessible in the user's lower field of view.
 */
@Composable
fun HandDockBar(
    isMenuOpen: Boolean,
    isMirrorOpen: Boolean,
    isPartyMode: Boolean,
    activePersonaName: String,
    activePersonaIcon: String,
    spatialInteraction: SpatialMenuInteraction,
    onToggleMenu: () -> Unit,
    onToggleMirror: () -> Unit,
    onRecenter: () -> Unit,
    onCyclePersona: () -> Unit,
    onTogglePartyMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .wrapContentSize()
            .padding(4.dp),
        shape = RoundedCornerShape(24.dp),
        color = Color(0xDD0D1522),
        border = BorderStroke(1.5.dp, Color(0xFF00E5FF)),
        shadowElevation = 16.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Toggle 2D Menu Tablet
            val isMenuHovered = spatialInteraction.isHovered("DOCK_MENU_BTN")
            Button(
                onClick = onToggleMenu,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMenuOpen) Color(0xFF00E5FF) else if (isMenuHovered) Color(0xFF00838F) else Color(0xFF004D5A)
                ),
                border = BorderStroke(1.5.dp, Color(0xFF00E5FF)),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.gazePinchSelectable("DOCK_MENU_BTN", spatialInteraction) { onToggleMenu() }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "📋", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (isMenuOpen) "📋 2D MENU (OPEN)" else "📋 2D MENU",
                        color = if (isMenuOpen) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // 2. Recenter in front of user
            val isRecenterHovered = spatialInteraction.isHovered("DOCK_RECENTER_BTN")
            Button(
                onClick = onRecenter,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecenterHovered) Color(0xFF69F0AE) else Color(0xFF00E676)
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.gazePinchSelectable("DOCK_RECENTER_BTN", spatialInteraction) { onRecenter() }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🎯", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "RECENTER",
                        color = Color.Black,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            // 3. Toggle Magic Vanity Mirror
            val isMirrorHovered = spatialInteraction.isHovered("DOCK_MIRROR_BTN")
            Button(
                onClick = onToggleMirror,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMirrorOpen) Color(0xFFE040FB) else if (isMirrorHovered) Color(0xFF37474F) else Color(0xFF1E293B)
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.gazePinchSelectable("DOCK_MIRROR_BTN", spatialInteraction) { onToggleMirror() }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🪞", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isMirrorOpen) "MIRROR ON" else "MIRROR",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 4. Quick Cycle Persona / Face
            val isPersonaHovered = spatialInteraction.isHovered("DOCK_PERSONA_BTN")
            Button(
                onClick = onCyclePersona,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPersonaHovered) Color(0xFF4527A0) else Color(0xFF311B92)
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.gazePinchSelectable("DOCK_PERSONA_BTN", spatialInteraction) { onCyclePersona() }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = activePersonaIcon, fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = activePersonaName,
                        color = Color(0xFFE1BEE7),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 5. World Mode (Focus 1 Head vs Party All Heads)
            val isPartyHovered = spatialInteraction.isHovered("DOCK_PARTY_BTN")
            Button(
                onClick = onTogglePartyMode,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPartyMode) Color(0xFFFFB300) else if (isPartyHovered) Color(0xFF37474F) else Color(0xFF1E293B)
                ),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.gazePinchSelectable("DOCK_PARTY_BTN", spatialInteraction) { onTogglePartyMode() }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = if (isPartyMode) "👥" else "🎯", fontSize = 15.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isPartyMode) "👥 WORLD PARTY" else "🎯 1-ON-1",
                        color = if (isPartyMode) Color.Black else Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
