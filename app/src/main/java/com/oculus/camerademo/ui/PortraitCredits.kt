package com.oculus.camerademo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

/** Visible attribution for the adapted artist-created built-in human portraits. */
@Composable
fun PortraitCredits(interaction: SpatialMenuInteraction? = null) {
    val links = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Character credits", style = MaterialTheme.typography.titleSmall)
        Text("Rain Rig © Blender Foundation\nSnow Rig © Blender Foundation",
            color = QuickMenuColors.muted, style = MaterialTheme.typography.bodySmall)
        Text("Adapted for AlterEgo MR: head geometry, materials and facial animation.",
            color = QuickMenuColors.muted, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickAction("Blender Studio", { links.openUri("https://studio.blender.org") },
                interaction = interaction, id = "portrait-source-credit")
            QuickAction("CC BY 4.0", { links.openUri("https://creativecommons.org/licenses/by/4.0/") },
                interaction = interaction, id = "portrait-license-credit")
        }
    }
}
