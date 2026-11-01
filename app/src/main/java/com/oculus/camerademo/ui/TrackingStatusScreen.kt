package com.oculus.camerademo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TrackingStatusScreen(message: String, onRetry: () -> Unit, onClose: () -> Unit, onMirror: (() -> Unit)? = null) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF101925)).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("AlterEgo MR", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Text(message, color = Color(0xFFFFD59A), fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text("Live face replacement needs outward camera access. Eye tracking is recommended; head direction works without it.",
            color = Color.LightGray, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onRetry) { Text("Check again") }
            if (onMirror != null) Button(onClick = onMirror) { Text("My avatar mirror") }
            Button(onClick = onClose) { Text("Close app") }
        }
    }
}
