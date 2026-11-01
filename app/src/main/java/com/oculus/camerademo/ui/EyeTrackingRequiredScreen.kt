package com.oculus.camerademo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oculus.camerademo.eyetracking.EyeTrackingDeviceStatus

@Composable
fun EyeTrackingRequiredScreen(
    status: EyeTrackingDeviceStatus,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E17))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF141C2B),
            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFFF5252))
        ) {
            Column(
                modifier = Modifier
                    .padding(32.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Eye Icon / Warning Indicator
                Text(
                    text = "👁️",
                    fontSize = 54.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = if (status == EyeTrackingDeviceStatus.NO_HARDWARE) {
                        "HEAD-DIRECTION SELECTION"
                    } else {
                        "OPTIONAL EYE TRACKING"
                    },
                    color = Color(0xFFFF5252),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (status == EyeTrackingDeviceStatus.NO_HARDWARE) {
                        "Eye tracking is recommended, but it is not required. Quest 3 and Quest 3S select faces using head direction. Live face replacement still requires access to outward camera images."
                    } else {
                        "Head direction remains available. For eye-based selection, enable Eye Tracking in headset settings and grant permission. Camera access is separately required for live face replacement."
                    },
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (status == EyeTrackingDeviceStatus.PERMISSION_DENIED) {
                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
                    ) {
                        Text(
                            text = "GRANT EYE TRACKING PERMISSION",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0x33FF5252),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF5252)),
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = "HEAD DIRECTION AVAILABLE",
                            color = Color(0xFFFF8A80),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
