package com.oculus.camerademo.renderer

/**
 * Native avatar mesh and expression update quality. gridFactor is retained for the legacy preview.
 * These settings do not alter passthrough or enable compositor eye-tracked foveation.
 */
enum class ResolutionQuality(
    val displayName: String,
    val gridFactor: Float,
    val description: String
) {
    HIGH(
        displayName = "High Quality",
        gridFactor = 64.0f,
        description = "Detailed avatars; expressions update with each tracked frame"
    ),
    BALANCED(
        displayName = "Balanced",
        gridFactor = 32.0f,
        description = "Balanced avatar detail; expressions update up to 30 times per second"
    ),
    PERFORMANCE(
        displayName = "Performance",
        gridFactor = 16.0f,
        description = "Lighter avatar meshes; expressions update up to 15 times per second"
    )
}
