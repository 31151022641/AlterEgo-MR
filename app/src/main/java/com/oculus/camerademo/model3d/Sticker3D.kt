package com.oculus.camerademo.model3d

import androidx.compose.ui.graphics.Color

/**
 * 3D Holographic Stickers & AR Accessories that can be attached to the user's face in real-time.
 */
enum class Sticker3DType(
    val id: String,
    val displayName: String,
    val icon: String,
    val description: String,
    val primaryColor: Long,
    val accentColor: Long
) {
    NEON_CROWN(
        id = "STICKER_NEON_CROWN",
        displayName = "Neon Laser Crown",
        icon = "👑",
        description = "Floating golden/cyan laser crown with pulsing energy gems",
        primaryColor = 0xFFFFD700,
        accentColor = 0xFF00E5FF
    ),
    CYBER_VISOR(
        id = "STICKER_CYBER_VISOR",
        displayName = "Cyber HUD Visor",
        icon = "🕶️",
        description = "Holographic laser eye visor with real-time target telemetry",
        primaryColor = 0xFF00E5FF,
        accentColor = 0xFFFF007F
    ),
    NEON_HORNS(
        id = "STICKER_NEON_HORNS",
        displayName = "Neon Cyber Horns",
        icon = "😈",
        description = "Curved luminescent biomechanical horns pulsing with red plasma",
        primaryColor = 0xFFFF1744,
        accentColor = 0xFF7C4DFF
    ),
    CAT_EARS(
        id = "STICKER_CAT_EARS",
        displayName = "Neko Holo Ears",
        icon = "🐱",
        description = "Cute holographic robotic cat ears responsive to head tilt",
        primaryColor = 0xFFFF4081,
        accentColor = 0xFFFFFFFF
    ),
    RETRO_MUSTACHE(
        id = "STICKER_RETRO_MUSTACHE",
        displayName = "Gentleman Stache & Monocle",
        icon = "🥸",
        description = "Distinguished 3D handlebar mustache with brass optical monocle",
        primaryColor = 0xFF5D4037,
        accentColor = 0xFFFFD700
    ),
    CYBER_STAR(
        id = "STICKER_CYBER_STAR",
        displayName = "Matrix Star Tattoo",
        icon = "⭐",
        description = "Luminous cybernetic star matrix marking on the cheekbone",
        primaryColor = 0xFF76FF03,
        accentColor = 0xFF00E5FF
    )
}
