package com.oculus.camerademo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.oculus.camerademo.renderer.ResolutionQuality

@Composable
fun SettingsDialog(
    resolutionQuality: ResolutionQuality,
    isFoveatedEnabled: Boolean,
    showGazeRing: Boolean,
    headScale: Float,
    isHairPhysicsEnabled: Boolean = true,
    isPartyModeEnabled: Boolean = false,
    isPartyDiversePersonas: Boolean = false,
    isVoiceSpeakerEnabled: Boolean = true,
    lastVoiceAnnouncement: String? = null,
    morphAlpha: Float,
    spatialInteraction: SpatialMenuInteraction = remember { SpatialMenuInteraction() },
    onResolutionQualityChanged: (ResolutionQuality) -> Unit,
    onFoveatedToggled: (Boolean) -> Unit,
    onGazeRingToggled: (Boolean) -> Unit,
    onHeadScaleChanged: (Float) -> Unit,
    onResetHeadScaleToObjectDefault: () -> Unit,
    onHairPhysicsToggled: (Boolean) -> Unit = {},
    onPartyModeToggled: (Boolean) -> Unit = {},
    onPartyDiversePersonasToggled: (Boolean) -> Unit = {},
    onVoiceSpeakerToggled: (Boolean) -> Unit = {},
    onTestVoiceSpeaker: () -> Unit = {},
    onMorphAlphaChanged: (Float) -> Unit,
    onDismiss: () -> Unit,
    onQuitApp: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Dimmed background backdrop covering entire screen
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xBB0A0E17))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        // Modal Pop-Up Box
        Surface(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.92f)
                .padding(16.dp)
                .clickable(enabled = false) {}, // Prevent dismiss when tapping dialog content
            shape = RoundedCornerShape(24.dp),
            color = Color(0xF2121826),
            border = BorderStroke(1.5.dp, Color(0xFF00E5FF)),
            shadowElevation = 16.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "⚙️",
                                fontSize = 20.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "SETTINGS",
                                color = Color(0xFF00E5FF),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Quality Resolution & Head Scale Control",
                            color = Color.LightGray,
                            fontSize = 11.sp
                        )
                    }

                    // Close Button - Vision Pro & Controller Enabled
                    val isCloseHovered = spatialInteraction.isHovered("SETTINGS_CLOSE_BTN")
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .background(if (isCloseHovered) Color(0x66FF5252) else Color(0x33FFFFFF), CircleShape)
                            .gazePinchSelectable("SETTINGS_CLOSE_BTN", spatialInteraction) { onDismiss() }
                            .then(
                                if (isCloseHovered)
                                    Modifier.border(2.dp, Color(0xFFFF5252), CircleShape)
                                else Modifier
                            )
                    ) {
                        Text(
                            text = "✕",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Apple Vision Pro & Touch Controller Look-and-Pinch Prompt Banner
                SpatialControlPromptBanner(interaction = spatialInteraction)

                Spacer(modifier = Modifier.height(8.dp))

                // Left Hand Menu Button Shortcut Banner
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0x3300E5FF),
                    border = BorderStroke(1.dp, Color(0x6600E5FF))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🎮",
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Press Left Controller Menu Button to toggle this menu anytime",
                            color = Color(0xFFE0F7FA),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // ==========================================
                // SECTION 0: MR TRACKING & PARTY MODE
                // ==========================================
                SectionHeader(title = "1. MR TRACKING MODE (FOCUS VS PARTY)")

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Option A: Single Gaze Focus
                    val isFocusHovered = spatialInteraction.isHovered("SETTINGS_MODE_FOCUS")
                    val isFocusSelected = !isPartyModeEnabled
                    val focusScale by animateFloatAsState(if (isFocusHovered) 1.04f else 1.0f, label = "FocusScale")
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .scale(focusScale)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                if (isFocusHovered) 2.5.dp else if (isFocusSelected) 1.5.dp else 1.dp,
                                if (isFocusHovered || isFocusSelected) Color(0xFF00E5FF) else Color(0x33FFFFFF),
                                RoundedCornerShape(14.dp)
                            )
                            .gazePinchSelectable("SETTINGS_MODE_FOCUS", spatialInteraction) { onPartyModeToggled(false) },
                        color = when {
                            isFocusHovered -> Color(0x5500E5FF)
                            isFocusSelected -> Color(0x3300E5FF)
                            else -> Color(0x18FFFFFF)
                        }
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🎯 FOCUS MODE",
                                    color = if (isFocusSelected) Color(0xFF00E5FF) else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isFocusSelected) {
                                    Text(
                                        text = "ACTIVE",
                                        color = Color(0xFF00E676),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Stick 3D head only to the person you are looking at. Other people keep their natural face in passthrough.",
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }

                    // Option B: Party Mode (All Heads Morphed)
                    val isPartyHovered = spatialInteraction.isHovered("SETTINGS_MODE_PARTY")
                    val isPartySelected = isPartyModeEnabled
                    val partyScale by animateFloatAsState(if (isPartyHovered) 1.04f else 1.0f, label = "PartyScale")
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .scale(partyScale)
                            .clip(RoundedCornerShape(14.dp))
                            .border(
                                if (isPartyHovered) 2.5.dp else if (isPartySelected) 1.5.dp else 1.dp,
                                if (isPartyHovered || isPartySelected) Color(0xFFFFD740) else Color(0x33FFFFFF),
                                RoundedCornerShape(14.dp)
                            )
                            .gazePinchSelectable("SETTINGS_MODE_PARTY", spatialInteraction) { onPartyModeToggled(true) },
                        color = when {
                            isPartyHovered -> Color(0x55FFD740)
                            isPartySelected -> Color(0x33FFD740)
                            else -> Color(0x18FFFFFF)
                        }
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🎉 PARTY MODE",
                                    color = if (isPartySelected) Color(0xFFFFD740) else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (isPartySelected) {
                                    Text(
                                        text = "ACTIVE",
                                        color = Color(0xFFFFD740),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Give detected people animated 3D cartoon faces with live expressions.",
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }

                // Sub-Option: Diverse Personas (All-Stars Crowd)
                if (isPartyModeEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val isDiverseHovered = spatialInteraction.isHovered("SETTINGS_DIVERSE_TOGGLE")
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                if (isDiverseHovered) 2.dp else 1.dp,
                                if (isDiverseHovered) Color(0xFF00E5FF) else if (isPartyDiversePersonas) Color(0xFFE040FB) else Color(0x33FFFFFF),
                                RoundedCornerShape(12.dp)
                            )
                            .gazePinchSelectable("SETTINGS_DIVERSE_TOGGLE", spatialInteraction) {
                                onPartyDiversePersonasToggled(!isPartyDiversePersonas)
                            },
                        color = if (isPartyDiversePersonas) Color(0x33E040FB) else Color(0x15FFFFFF)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 14.dp, vertical = 10.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "🎭 Diverse Personas (All-Stars Crowd)",
                                    color = if (isPartyDiversePersonas) Color(0xFFFF80AB) else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Assign distinct personas across the room (Messi, CyberSynth, Golden Bust, HoloHuman).",
                                    color = Color.LightGray,
                                    fontSize = 9.sp
                                )
                            }
                            Switch(
                                checked = isPartyDiversePersonas,
                                onCheckedChange = { onPartyDiversePersonasToggled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFFFF80AB),
                                    checkedTrackColor = Color(0xFF880E4F)
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // ==========================================
                // SECTION 2: EYE-GAZE FACE VOICE SPEAKER
                // ==========================================
                SectionHeader(title = "2. FACE SELECTION VOICE SPEAKER")

                Spacer(modifier = Modifier.height(8.dp))

                val isVoiceToggleHovered = spatialInteraction.isHovered("SETTINGS_VOICE_TOGGLE")
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            if (isVoiceToggleHovered) 2.dp else 1.dp,
                            if (isVoiceToggleHovered) Color(0xFF00E5FF) else if (isVoiceSpeakerEnabled) Color(0xFF1E88E5) else Color(0x33FFFFFF),
                            RoundedCornerShape(14.dp)
                        )
                        .gazePinchSelectable("SETTINGS_VOICE_TOGGLE", spatialInteraction) {
                            onVoiceSpeakerToggled(!isVoiceSpeakerEnabled)
                        },
                    color = if (isVoiceSpeakerEnabled) Color(0x221E88E5) else Color(0x18FFFFFF)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "🗣️ Announce Face Count on Selection",
                                        color = if (isVoiceSpeakerEnabled) Color(0xFF80D8FF) else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (isVoiceSpeakerEnabled) {
                                        Text(
                                            text = "ACTIVE",
                                            color = Color(0xFF00E676),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                Text(
                                    text = "Announces the face count when you select a person using eye tracking or head direction.",
                                    color = Color.LightGray,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp
                                )
                            }
                            Switch(
                                checked = isVoiceSpeakerEnabled,
                                onCheckedChange = onVoiceSpeakerToggled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF00E5FF),
                                    checkedTrackColor = Color(0xFF0277BD)
                                )
                            )
                        }

                        // Test Voice Button & Live status row
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isTestVoiceHovered = spatialInteraction.isHovered("SETTINGS_TEST_VOICE_BTN")
                            Button(
                                onClick = onTestVoiceSpeaker,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isTestVoiceHovered) Color(0xFF00B0FF) else Color(0xFF0277BD)
                                ),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .gazePinchSelectable("SETTINGS_TEST_VOICE_BTN", spatialInteraction) {
                                        onTestVoiceSpeaker()
                                    }
                                    .then(
                                        if (isTestVoiceHovered)
                                            Modifier.border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(10.dp))
                                        else Modifier
                                    )
                            ) {
                                Text(
                                    text = "🔊 Test Voice Output",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }

                            if (!lastVoiceAnnouncement.isNullOrEmpty()) {
                                Text(
                                    text = "Last: \"$lastVoiceAnnouncement\"",
                                    fontSize = 10.sp,
                                    color = Color(0xFF80DEEA),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // ==========================================
                // SECTION 3: QUALITY RESOLUTION
                // ==========================================
                SectionHeader(title = "3. AVATAR DETAIL AND PERFORMANCE")

                Spacer(modifier = Modifier.height(8.dp))

                // 3 Resolution Quality Selection Cards
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ResolutionQuality.values().forEach { quality ->
                        val targetId = "QUALITY_${quality.name}"
                        val isGazeFocused = spatialInteraction.isHovered(targetId)
                        val isSelected = quality == resolutionQuality
                        val scale by animateFloatAsState(if (isGazeFocused) 1.05f else 1.0f, label = "QualityScale")
                        val borderColor by animateColorAsState(
                            when {
                                isGazeFocused -> Color(0xFF00E5FF)
                                isSelected -> Color(0xFF00E5FF)
                                else -> Color(0x22FFFFFF)
                            },
                            label = "QualityBorder"
                        )

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .scale(scale)
                                .clip(RoundedCornerShape(12.dp))
                                .border(if (isGazeFocused) 2.5.dp else 1.5.dp, borderColor, RoundedCornerShape(12.dp))
                                .gazePinchSelectable(targetId, spatialInteraction) { onResolutionQualityChanged(quality) },
                            color = when {
                                isGazeFocused -> Color(0x5500E5FF)
                                isSelected -> Color(0x3300E5FF)
                                else -> Color(0x18FFFFFF)
                            }
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .fillMaxWidth(),
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
                                Text(
                                    text = quality.displayName,
                                    color = if (isSelected || isGazeFocused) Color(0xFF00E5FF) else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected || isGazeFocused) FontWeight.Bold else FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = when (quality) {
                                        ResolutionQuality.HIGH -> "Full detail"
                                        ResolutionQuality.BALANCED -> "Balanced detail"
                                        ResolutionQuality.PERFORMANCE -> "Light detail"
                                    },
                                    color = Color.Gray,
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = resolutionQuality.description,
                    color = Color(0xFFB0BEC5),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.Start)
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Dynamic Foveated Toggle & Eye Reticle Ring Toggle - Look & Pinch Enabled
                val isFoveatedHovered = spatialInteraction.isHovered("FOVEATED_TOGGLE")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .gazePinchSelectable("FOVEATED_TOGGLE", spatialInteraction) { onFoveatedToggled(!isFoveatedEnabled) }
                        .then(
                            if (isFoveatedHovered)
                                Modifier.border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(10.dp)).background(Color(0x2200E5FF))
                            else Modifier
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Prioritize face selected by eye gaze",
                        color = if (isFoveatedHovered) Color(0xFF00E5FF) else Color.White,
                        fontSize = 12.sp
                    )
                    Switch(
                        checked = isFoveatedEnabled,
                        onCheckedChange = onFoveatedToggled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E5FF),
                            checkedTrackColor = Color(0xFF006064)
                        )
                    )
                }

                val isGazeRingHovered = spatialInteraction.isHovered("GAZE_RING_TOGGLE")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .gazePinchSelectable("GAZE_RING_TOGGLE", spatialInteraction) { onGazeRingToggled(!showGazeRing) }
                        .then(
                            if (isGazeRingHovered)
                                Modifier.border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(10.dp)).background(Color(0x2200E5FF))
                            else Modifier
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Show targeting cursor",
                        color = if (isGazeRingHovered) Color(0xFF00E5FF) else Color.White,
                        fontSize = 12.sp
                    )
                    Switch(
                        checked = showGazeRing,
                        onCheckedChange = onGazeRingToggled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E5FF),
                            checkedTrackColor = Color(0xFF006064)
                        )
                    )
                }

                Text("Eye tracking is optional. When available, the selected face receives more detail while other faces use lighter updates.",
                    color = Color.LightGray, fontSize = 10.sp)

                Spacer(modifier = Modifier.height(16.dp))

                // ==========================================
                // SECTION 4: HEAD SIZING (SIZE / OBJECT FIT)
                // ==========================================
                SectionHeader(title = "4. HEAD SIZE / SCALING")

                Spacer(modifier = Modifier.height(8.dp))

                // Head Scale Status & Default Reset Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Current Scale: ${String.format("%.2f", headScale)}x",
                            color = if (kotlin.math.abs(headScale - 1.0f) < 0.05f) Color(0xFF00E676) else Color(0xFFFFD740),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (kotlin.math.abs(headScale - 1.0f) < 0.05f) {
                                "Default size (matches real person / object)"
                            } else if (headScale > 1.0f) {
                                "Enlarged head / Bobblehead mode"
                            } else {
                                "Compact fit mode"
                            },
                            color = Color.Gray,
                            fontSize = 10.sp
                        )
                    }

                    // Reset to Object Default (1.0x) Button - Vision Pro & Controller Enabled
                    val isResetHovered = spatialInteraction.isHovered("RESET_SCALE_BTN")
                    Button(
                        onClick = onResetHeadScaleToObjectDefault,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isResetHovered) Color(0xFF00E676) else if (kotlin.math.abs(headScale - 1.0f) < 0.05f) {
                                Color(0xFF00695C)
                            } else {
                                Color(0xFF00C853)
                            }
                        ),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .gazePinchSelectable("RESET_SCALE_BTN", spatialInteraction) { onResetHeadScaleToObjectDefault() }
                            .then(
                                if (isResetHovered)
                                    Modifier.border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(12.dp))
                                else Modifier
                            )
                    ) {
                        Text(
                            text = if (isResetHovered) "👁️ FIT OBJECT (1.0x)" else "🎯 FIT OBJECT (1.0x)",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Quick Preset Chips for Head Sizing - Look & Pinch Enabled
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        0.80f to "0.8x Compact",
                        1.00f to "1.0x Default",
                        1.40f to "1.4x Large",
                        2.00f to "2.0x Bobble"
                    ).forEach { (presetScale, label) ->
                        val targetId = "PRESET_${(presetScale * 10).toInt()}"
                        val isPresetHovered = spatialInteraction.isHovered(targetId)
                        val isPresetActive = kotlin.math.abs(headScale - presetScale) < 0.04f
                        val chipScale by animateFloatAsState(if (isPresetHovered) 1.05f else 1.0f, label = "ChipScale")
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .scale(chipScale)
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    if (isPresetHovered) 2.dp else 1.dp,
                                    if (isPresetHovered) Color(0xFF00E5FF) else if (isPresetActive) Color(0xFF00E5FF) else Color(0x33FFFFFF),
                                    RoundedCornerShape(8.dp)
                                )
                                .gazePinchSelectable(targetId, spatialInteraction) { onHeadScaleChanged(presetScale) },
                            color = when {
                                isPresetHovered -> Color(0x5500E5FF)
                                isPresetActive -> Color(0x3300E5FF)
                                else -> Color(0x18FFFFFF)
                            }
                        ) {
                            Text(
                                text = if (isPresetHovered) "👁️ $label" else label,
                                color = if (isPresetActive || isPresetHovered) Color(0xFF00E5FF) else Color.LightGray,
                                fontSize = 10.sp,
                                fontWeight = if (isPresetActive || isPresetHovered) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .padding(vertical = 6.dp)
                                    .wrapContentWidth(Alignment.CenterHorizontally)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Continuous Head Scale Slider (0.6x to 2.5x)
                Slider(
                    value = headScale,
                    onValueChange = onHeadScaleChanged,
                    valueRange = 0.60f..2.50f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFFD740),
                        activeTrackColor = Color(0xFFFFD740)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Hairstyle visibility - Look & Pinch Enabled
                val isHairHovered = spatialInteraction.isHovered("HAIR_PHYSICS_TOGGLE")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .gazePinchSelectable("HAIR_PHYSICS_TOGGLE", spatialInteraction) { onHairPhysicsToggled(!isHairPhysicsEnabled) }
                        .then(
                            if (isHairHovered)
                                Modifier.border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(10.dp)).background(Color(0x2200E5FF))
                            else Modifier
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (isHairHovered) "👁️ 3D Hairstyle [PINCH]" else "3D Hairstyle",
                            color = if (isHairHovered) Color(0xFF00E5FF) else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Show the sculpted hairstyle on your cartoon face",
                            color = Color(0xFF80DEEA),
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = isHairPhysicsEnabled,
                        onCheckedChange = onHairPhysicsToggled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF00E5FF),
                            checkedTrackColor = Color(0xFF006064)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // ==========================================
                // SECTION 5: MORPH BLEND INTENSITY
                // ==========================================
                SectionHeader(title = "5. 3D FACE MORPH BLEND")

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Real Person (0%)",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${(morphAlpha * 100).toInt()}% 3D Avatar",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Full 3D (100%)",
                        color = Color.Gray,
                        fontSize = 10.sp
                    )
                }

                Slider(
                    value = morphAlpha,
                    onValueChange = onMorphAlphaChanged,
                    valueRange = 0.0f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF00E5FF),
                        activeTrackColor = Color(0xFF00E5FF)
                    )
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Close / Done Button - Vision Pro & Controller Enabled
                val isDoneHovered = spatialInteraction.isHovered("SETTINGS_DONE_BTN")
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .gazePinchSelectable("SETTINGS_DONE_BTN", spatialInteraction) { onDismiss() }
                        .then(
                            if (isDoneHovered)
                                Modifier.border(2.dp, Color(0xFF00E5FF), RoundedCornerShape(14.dp))
                            else Modifier
                        ),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDoneHovered) Color(0xFF00E5FF) else Color(0xFF00B0FF)
                    )
                ) {
                    Text(
                        text = if (isDoneHovered) "👁️ DONE / APPLY SETTINGS [PINCH]" else "DONE / APPLY SETTINGS",
                        color = Color.Black,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quit Application Button - Danger Red (Vision Pro & Controller Enabled)
                val isSettingsQuitHovered = spatialInteraction.isHovered("SETTINGS_QUIT_BTN")
                Button(
                    onClick = onQuitApp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .gazePinchSelectable("SETTINGS_QUIT_BTN", spatialInteraction) { onQuitApp() }
                        .then(
                            if (isSettingsQuitHovered)
                                Modifier.border(2.dp, Color(0xFFFF5252), RoundedCornerShape(14.dp))
                            else Modifier
                        ),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSettingsQuitHovered) Color(0xFFFF1744) else Color(0xFFC62828)
                    )
                ) {
                    Text(
                        text = if (isSettingsQuitHovered) "👁️ QUIT APPLICATION [PINCH]" else "🚪 QUIT APPLICATION",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // Apple Vision Pro Floating Eye Reticle Cursor
        if (showGazeRing) VisionProGazeCursor(interaction = spatialInteraction)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFFFFD740),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.fillMaxWidth()
    )
}
