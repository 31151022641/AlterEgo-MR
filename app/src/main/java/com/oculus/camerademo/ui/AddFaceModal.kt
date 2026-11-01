package com.oculus.camerademo.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oculus.camerademo.model3d.DynamicPersona
import com.oculus.camerademo.model3d.HumanPersona
import com.oculus.camerademo.model3d.OnlineFaceCatalog
import com.oculus.camerademo.model3d.PersonaSource
import com.oculus.camerademo.model3d.Sticker3DType

/**
 * AddFaceModal: Spatial dialog for importing faces from Online catalog / URLs,
 * external device storage, and equipping 3D AR Stickers.
 */
@Composable
fun AddFaceModal(
    activePersonas: List<DynamicPersona>,
    activeStickers: Set<Sticker3DType>,
    spatialInteraction: SpatialMenuInteraction = remember { SpatialMenuInteraction() },
    onImportPersona: (DynamicPersona) -> Unit,
    onToggleSticker: (Sticker3DType) -> Unit,
    onClearAllStickers: () -> Unit,
    onLaunchFilePicker: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(0) } // 0 = Online, 1 = Storage, 2 = 3D Stickers
    var customUrl by remember { mutableStateOf("https://models.meta.com/personas/futuristic_hero.png") }
    var customName by remember { mutableStateOf("Cyber Hero X") }

    Surface(
        modifier = modifier
            .fillMaxWidth(0.92f)
            .widthIn(max = 780.dp)
            .padding(16.dp),
        shape = RoundedCornerShape(26.dp),
        color = Color(0xF50D1524),
        border = BorderStroke(
            2.dp,
            Brush.linearGradient(
                listOf(
                    Color(0xFF00E5FF),
                    Color(0xFF7C4DFF),
                    Color(0xFFFF007F)
                )
            )
        ),
        shadowElevation = 24.dp
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "➕", fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "ADD FACE & 3D STICKERS",
                            color = Color(0xFF00E5FF),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Online Catalog • External Storage • 3D Holographic Stickers",
                            color = Color.LightGray,
                            fontSize = 10.sp
                        )
                    }
                }

                // Close Button
                val isCloseHovered = spatialInteraction.isHovered("ADD_FACE_CLOSE_BTN")
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(34.dp)
                        .background(if (isCloseHovered) Color(0x66FF5252) else Color(0x22FFFFFF), CircleShape)
                        .gazePinchSelectable("ADD_FACE_CLOSE_BTN", spatialInteraction) { onDismiss() }
                        .then(
                            if (isCloseHovered) Modifier.border(2.dp, Color(0xFFFF5252), CircleShape) else Modifier
                        )
                ) {
                    Text(text = "✕", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Navigation Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF162032), RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val tabs = listOf(
                    "🌐 Online Catalog" to 0,
                    "📁 External Storage" to 1,
                    "✨ 3D Stickers (${activeStickers.size})" to 2
                )
                tabs.forEach { (title, tabIdx) ->
                    val isSelected = selectedTab == tabIdx
                    val tabTag = "TAB_$tabIdx"
                    val isHovered = spatialInteraction.isHovered(tabTag)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(38.dp)
                            .clickable { selectedTab = tabIdx }
                            .gazePinchSelectable(tabTag, spatialInteraction) { selectedTab = tabIdx },
                        shape = RoundedCornerShape(10.dp),
                        color = when {
                            isSelected -> Color(0xFF7C4DFF)
                            isHovered -> Color(0x557C4DFF)
                            else -> Color.Transparent
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = title,
                                color = if (isSelected || isHovered) Color.White else Color.Gray,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Tab Content Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
            ) {
                when (selectedTab) {
                    0 -> OnlineCatalogTab(
                        activePersonas = activePersonas,
                        spatialInteraction = spatialInteraction,
                        customUrl = customUrl,
                        customName = customName,
                        onCustomUrlChange = { customUrl = it },
                        onCustomNameChange = { customName = it },
                        onImportPersona = onImportPersona
                    )
                    1 -> ExternalStorageTab(
                        spatialInteraction = spatialInteraction,
                        onLaunchFilePicker = onLaunchFilePicker,
                        onImportPersona = onImportPersona
                    )
                    2 -> StickersTab(
                        activeStickers = activeStickers,
                        spatialInteraction = spatialInteraction,
                        onToggleSticker = onToggleSticker,
                        onClearAllStickers = onClearAllStickers
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineCatalogTab(
    activePersonas: List<DynamicPersona>,
    spatialInteraction: SpatialMenuInteraction,
    customUrl: String,
    customName: String,
    onCustomUrlChange: (String) -> Unit,
    onCustomNameChange: (String) -> Unit,
    onImportPersona: (DynamicPersona) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "FEATURED ONLINE FACES (1-CLICK IMPORT)",
                color = Color(0xFF80DEEA),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        }

        items(OnlineFaceCatalog.curatedOnlineFaces) { preset ->
            val isAlreadyImported = activePersonas.any { it.id == preset.id }
            val btnTag = "IMPORT_${preset.id}"
            val isHovered = spatialInteraction.isHovered(btnTag)

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF131D2D),
                border = BorderStroke(1.dp, if (isAlreadyImported) Color(0xFF00E676) else Color(0x3300E5FF))
            ) {
                Row(
                    modifier = Modifier
                        .padding(10.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = CircleShape,
                            color = Color(preset.primaryColorHex)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(text = preset.icon, fontSize = 20.sp)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = preset.displayName,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0x337C4DFF)
                                ) {
                                    Text(
                                        text = "🌐 Cloud",
                                        color = Color(0xFFB388FF),
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = preset.description,
                                color = Color.Gray,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }

                    Button(
                        onClick = { onImportPersona(preset) },
                        enabled = !isAlreadyImported,
                        modifier = Modifier
                            .height(34.dp)
                            .gazePinchSelectable(btnTag, spatialInteraction) {
                                if (!isAlreadyImported) onImportPersona(preset)
                            }
                            .then(
                                if (isHovered && !isAlreadyImported)
                                    Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp))
                                else Modifier
                            ),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isAlreadyImported) Color(0xFF2E7D32) else Color(0xFF00E5FF)
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (isAlreadyImported) "✓ ACTIVE" else "+ ADD FACE",
                            color = if (isAlreadyImported) Color.White else Color.Black,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "IMPORT CUSTOM FACE FROM WEB URL",
                color = Color(0xFF80DEEA),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF131D2D),
                border = BorderStroke(1.dp, Color(0x447C4DFF))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "Face Image / Texture URL:",
                        color = Color.LightGray,
                        fontSize = 10.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF0A0F1A)
                    ) {
                        Text(
                            text = customUrl,
                            color = Color(0xFF80DEEA),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val newPersona = DynamicPersona(
                                id = "CUSTOM_URL_${System.currentTimeMillis()}",
                                displayName = customName,
                                description = "Custom online face downloaded from URL",
                                primaryColorHex = 0xFF00B0FF,
                                accentColorHex = 0xFFFF4081,
                                source = PersonaSource.ONLINE,
                                builtinPersona = HumanPersona.DIGITAL_TWIN,
                                icon = "🌐",
                                imageUrlOrPath = customUrl
                            )
                            onImportPersona(newPersona)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF))
                    ) {
                        Text(text = "⬇️ DOWNLOAD & ADD FACE TO MENU", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExternalStorageTab(
    spatialInteraction: SpatialMenuInteraction,
    onLaunchFilePicker: () -> Unit,
    onImportPersona: (DynamicPersona) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "IMPORT FROM META QUEST HEADSET STORAGE",
            color = Color(0xFF80DEEA),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF131D2D),
            border = BorderStroke(1.dp, Color(0x4400E676))
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "📁 Quest Internal Storage / Downloads",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select any PNG/JPG face photo or 3D texture from /sdcard/Download or your Quest photo gallery to morph onto your face.",
                    color = Color.LightGray,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                val isPickerHovered = spatialInteraction.isHovered("STORAGE_PICKER_BTN")
                Button(
                    onClick = onLaunchFilePicker,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .gazePinchSelectable("STORAGE_PICKER_BTN", spatialInteraction) { onLaunchFilePicker() }
                        .then(
                            if (isPickerHovered) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier
                        ),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853))
                ) {
                    Text(text = "📂 OPEN DEVICE FILE PICKER", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Text(
            text = "QUICK EXTERNAL DEVICE PRESETS",
            color = Color(0xFF80DEEA),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )

        val quickPresets = listOf(
            Triple("📸 Quest Camera Snapshot", "Photo taken in Passthrough", 0xFF00B0FF),
            Triple("🖼️ /sdcard/Download/custom_face.png", "Downloaded texture asset", 0xFFFF9100),
            Triple("🎭 User 3D Avatar Texture", "Scanned 3D face mesh", 0xFFE040FB)
        )

        quickPresets.forEachIndexed { idx, (title, desc, colorHex) ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF101724)
            ) {
                Row(
                    modifier = Modifier
                        .padding(8.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(text = title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(text = desc, color = Color.Gray, fontSize = 9.sp)
                    }
                    Button(
                        onClick = {
                            val imported = DynamicPersona(
                                id = "LOCAL_PRESET_$idx",
                                displayName = title.split(" ").take(2).joinToString(" "),
                                description = desc,
                                primaryColorHex = colorHex,
                                accentColorHex = 0xFFFFFFFF,
                                source = PersonaSource.LOCAL_STORAGE,
                                builtinPersona = HumanPersona.DIGITAL_TWIN,
                                icon = "📁"
                            )
                            onImportPersona(imported)
                        },
                        shape = RoundedCornerShape(6.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                    ) {
                        Text(text = "+ IMPORT", color = Color(0xFF80DEEA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun StickersTab(
    activeStickers: Set<Sticker3DType>,
    spatialInteraction: SpatialMenuInteraction,
    onToggleSticker: (Sticker3DType) -> Unit,
    onClearAllStickers: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "3D HOLOGRAPHIC AR STICKERS",
                color = Color(0xFF80DEEA),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            if (activeStickers.isNotEmpty()) {
                Surface(
                    modifier = Modifier.clickable { onClearAllStickers() },
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0x33FF5252)
                ) {
                    Text(
                        text = "✕ Clear All (${activeStickers.size})",
                        color = Color(0xFFFF8A80),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(Sticker3DType.values()) { sticker ->
                val isEquipped = activeStickers.contains(sticker)
                val stickerTag = "STICKER_${sticker.name}"
                val isHovered = spatialInteraction.isHovered(stickerTag)

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleSticker(sticker) }
                        .gazePinchSelectable(stickerTag, spatialInteraction) { onToggleSticker(sticker) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isEquipped) Color(0xFF1E2438) else Color(0xFF111722),
                    border = BorderStroke(
                        1.5.dp,
                        when {
                            isEquipped -> Color(sticker.primaryColor)
                            isHovered -> Color(0xFF00E5FF)
                            else -> Color(0x22FFFFFF)
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .padding(10.dp)
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = sticker.icon, fontSize = 22.sp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = sticker.displayName,
                                    color = if (isEquipped) Color(sticker.primaryColor) else Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = sticker.description,
                                    color = Color.Gray,
                                    fontSize = 9.sp,
                                    maxLines = 1
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isEquipped) Color(sticker.primaryColor) else Color(0x33FFFFFF)
                        ) {
                            Text(
                                text = if (isEquipped) "✓ EQUIPPED" else "+ EQUIP",
                                color = if (isEquipped) Color.Black else Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
