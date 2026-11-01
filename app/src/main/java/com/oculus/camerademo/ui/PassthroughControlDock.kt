package com.oculus.camerademo.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Keep content ready while the scene entity is hidden or waiting for placement.
 * Entity Visible controls presentation; a plain scene flag cannot invalidate Compose.
 * ISDK delivers hand ray/pinch and direct-touch events to the normal buttons. */
@Composable
fun PassthroughControlDock(
    personaName: String,
    onMenu: () -> Unit,
    onResetView: () -> Unit,
    onBackToMirror: () -> Unit,
    onInput: (String) -> Unit = {},
) {
    LaunchedEffect(Unit) { onInput("content-ready") }
    Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(20.dp),
        color = QuickMenuColors.panel, contentColor = QuickMenuColors.ink) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            .pointerInput(onInput) {
                awaitPointerEventScope {
                    while (true) {
                        // Observe before the buttons, without consuming their input.
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        when (event.type) {
                            PointerEventType.Press -> onInput("press")
                            PointerEventType.Release -> onInput("release")
                        }
                    }
                }
            }, verticalArrangement = Arrangement.Center) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                QuickAction("☰ Menu", onMenu, Modifier.heightIn(min = 64.dp), primary = true)
                QuickAction("Reset view", onResetView, Modifier.heightIn(min = 64.dp))
                QuickAction("Exit passthrough", onBackToMirror, Modifier.heightIn(min = 64.dp))
                Text(personaName, color = QuickMenuColors.muted)
            }
            Text("Point and pinch, or touch a button", color = QuickMenuColors.muted,
                fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
