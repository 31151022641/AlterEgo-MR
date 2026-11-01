package com.oculus.camerademo.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oculus.camerademo.eyetracking.EyeGazeData

/**
 * Spatial Menu Interaction Controller supporting:
 * 1. Apple Vision Pro paradigm: Look with Eyes (Gaze Focus) + Pinch Hand to Choose.
 * 2. Meta Touch Controller paradigm: Point/Aim or D-Pad + Pull Trigger / Press Button A.
 * 3. Direct Touch / Pointer: Tap directly on menu elements.
 */
class SpatialMenuInteraction {

    data class TargetInfo(
        val id: String,
        var bounds: Rect,
        var onClick: () -> Unit
    )

    val registeredTargets = mutableMapOf<String, TargetInfo>()
    val orderedTargetIds = mutableListOf<String>()

    var focusedTargetId by mutableStateOf<String?>(null)
    var gazeScreenX by mutableStateOf(-100f)
    var gazeScreenY by mutableStateOf(-100f)
    var isGazeActive by mutableStateOf(false)
    var lastInteractionType by mutableStateOf("EYE_PINCH") // "EYE_PINCH", "CONTROLLER", "TOUCH"

    fun registerTarget(id: String, bounds: Rect, onClick: () -> Unit) {
        // boundsInWindow is clipped by the scrolling viewport. An empty target
        // must not remain selectable by gaze, pinch or controller navigation.
        if (!bounds.left.isFinite() || !bounds.top.isFinite() ||
            !bounds.right.isFinite() || !bounds.bottom.isFinite() ||
            bounds.width <= 0f || bounds.height <= 0f) {
            unregisterTarget(id)
            return
        }
        val existing = registeredTargets[id]
        if (existing != null) {
            existing.bounds = bounds
            existing.onClick = onClick
        } else {
            registeredTargets[id] = TargetInfo(id, bounds, onClick)
            if (!orderedTargetIds.contains(id)) {
                orderedTargetIds.add(id)
            }
        }
    }

    fun unregisterTarget(id: String) {
        registeredTargets.remove(id)
        orderedTargetIds.remove(id)
        if (focusedTargetId == id) {
            focusedTargetId = null
        }
    }

    /**
     * Updates eye gaze position on screen and tests against registered targets.
     */
    fun updateGaze(gazeData: EyeGazeData, screenWidth: Float, screenHeight: Float) {
        if (!gazeData.isValid) {
            clearGaze()
            return
        }
        updatePointer(gazeData.gazeX, gazeData.gazeY, screenWidth, screenHeight, "EYE_PINCH")
    }

    fun updatePointer(x: Float, y: Float, screenWidth: Float, screenHeight: Float, inputType: String) {
        if (!x.isFinite() || !y.isFinite() || !screenWidth.isFinite() || !screenHeight.isFinite() ||
            screenWidth <= 0f || screenHeight <= 0f || x !in 0f..1f || y !in 0f..1f) {
            clearGaze()
            return
        }

        gazeScreenX = (x * screenWidth).coerceIn(0f, screenWidth)
        gazeScreenY = (y * screenHeight).coerceIn(0f, screenHeight)
        isGazeActive = true

        val gazePoint = Offset(gazeScreenX, gazeScreenY)

        // Find which target contains the user's eye gaze point
        var foundId: String? = null
        for ((id, info) in registeredTargets) {
            // Expand hit bounds slightly (10px) for comfortable eye targeting
            val hitRect = Rect(
                info.bounds.left - 10f,
                info.bounds.top - 10f,
                info.bounds.right + 10f,
                info.bounds.bottom + 10f
            )
            if (hitRect.contains(gazePoint)) {
                foundId = id
                break
            }
        }

        focusedTargetId = foundId
        if (foundId != null) {
            lastInteractionType = inputType
        }
    }

    fun clearGaze() {
        isGazeActive = false
        gazeScreenX = -100f
        gazeScreenY = -100f
        if (lastInteractionType == "EYE_PINCH" || lastInteractionType == "HEAD_DIRECTION") focusedTargetId = null
    }
    fun clearFocus() {clearGaze();focusedTargetId=null}

    /**
     * Navigates to the next target using Touch Controller thumbstick / D-pad.
     */
    fun navigateNext() {
        if (orderedTargetIds.isEmpty()) return
        lastInteractionType = "CONTROLLER"
        val currentIndex = orderedTargetIds.indexOf(focusedTargetId)
        val nextIndex = if (currentIndex < 0 || currentIndex >= orderedTargetIds.size - 1) 0 else currentIndex + 1
        focusedTargetId = orderedTargetIds[nextIndex]
    }

    /**
     * Navigates to the previous target using Touch Controller thumbstick / D-pad.
     */
    fun navigatePrevious() {
        if (orderedTargetIds.isEmpty()) return
        lastInteractionType = "CONTROLLER"
        val currentIndex = orderedTargetIds.indexOf(focusedTargetId)
        val prevIndex = if (currentIndex <= 0) orderedTargetIds.size - 1 else currentIndex - 1
        focusedTargetId = orderedTargetIds[prevIndex]
    }

    /**
     * Confirms and clicks the currently focused target via Hand Pinch or Touch Controller Trigger / Button A.
     */
    fun triggerSelection(): Boolean {
        val targetId = focusedTargetId ?: return false
        val target = registeredTargets[targetId] ?: return false
        target.onClick()
        return true
    }

    fun isHovered(id: String): Boolean {
        return focusedTargetId == id
    }
}

val LocalSpatialInteraction = compositionLocalOf { SpatialMenuInteraction() }

/**
 * Modifier that connects any Compose component into the Vision Pro Look & Pinch
 * and Touch Controller interaction pipeline.
 */
fun Modifier.gazePinchSelectable(
    targetId: String,
    interaction: SpatialMenuInteraction,
    onClick: () -> Unit
): Modifier = composed {
    val currentClick by rememberUpdatedState(onClick)
    DisposableEffect(interaction, targetId) {
        onDispose { interaction.unregisterTarget(targetId) }
    }
    Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        interaction.registerTarget(targetId, bounds) { currentClick() }
    }
}

/**
 * Apple Vision Pro style animated gaze reticle dot that smoothly follows the user's eyes
 * across the popup menu screen, indicating active gaze point and pinch readiness.
 */
@Composable
fun VisionProGazeCursor(
    interaction: SpatialMenuInteraction,
    modifier: Modifier = Modifier
) {
    if (!interaction.isGazeActive || interaction.gazeScreenX < 0f || interaction.gazeScreenY < 0f) return

    val isTargetFocused = interaction.focusedTargetId != null
    val targetScale by animateFloatAsState(if (isTargetFocused) 1.25f else 1.0f, label = "CursorScale")
    val ringColor by animateColorAsState(
        if (isTargetFocused) Color(0xFF00E5FF) else Color(0x88FFFFFF),
        label = "CursorColor"
    )

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (interaction.gazeScreenX - 14.dp.toPx()).toInt(),
                    (interaction.gazeScreenY - 14.dp.toPx()).toInt()
                )
            }
            .size(28.dp)
            .scale(targetScale)
            .border(2.dp, ringColor, CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        if (isTargetFocused) Color(0x6600E5FF) else Color(0x22FFFFFF),
                        Color.Transparent
                    )
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        // Inner focal dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(if (isTargetFocused) Color(0xFF00E5FF) else Color.White, CircleShape)
        )
    }
}

/**
 * Vision Pro & Touch Controller prompt banner explaining Look & Pinch controls.
 */
@Composable
fun SpatialControlPromptBanner(
    interaction: SpatialMenuInteraction,
    modifier: Modifier = Modifier
) {
    val isFocused = interaction.focusedTargetId != null
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (isFocused) Color(0x4400E5FF) else Color(0x221A2332),
        border = BorderStroke(1.dp, if (isFocused) Color(0xFF00E5FF) else Color(0x4400E5FF))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isFocused) "👁️ FOCUSED:" else "👁️ LOOK:",
                    color = Color(0xFF00E5FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (isFocused) "${interaction.focusedTargetId}" else "Look at target to highlight",
                    color = if (isFocused) Color(0xFFFFD740) else Color.LightGray,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Hand Pinch badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (isFocused) Color(0xFF00C853) else Color(0x33FFFFFF),
                    modifier = Modifier.clickable {
                        interaction.triggerSelection()
                    }
                ) {
                    Text(
                        text = "👌 PINCH TO CHOOSE",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }

                // Touch Controller badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0x3300E5FF),
                    border = BorderStroke(0.5.dp, Color(0x6600E5FF))
                ) {
                    Text(
                        text = "🎮 Trigger / [A]",
                        color = Color(0xFF80DEEA),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}
