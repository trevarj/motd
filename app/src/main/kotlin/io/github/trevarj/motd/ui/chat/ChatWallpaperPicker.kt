package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import kotlin.math.roundToInt

@Composable
fun ChatWallpaperPicker(
    current: WallpaperSelection,
    onChange: (WallpaperSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEditor by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_wallpaper)) },
        supportingContent = {
            Text(
                if (current.preset == ChatWallpaperPreset.NONE) {
                    wallpaperLabel(current.preset)
                } else {
                    "${wallpaperLabel(current.preset)} · ${current.intensity}%"
                },
            )
        },
        leadingContent = { Icon(Icons.Outlined.Image, contentDescription = null) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        modifier = modifier.fillMaxWidth().clickable { showEditor = true }.testTag("settings_wallpaper_picker"),
    )
    if (showEditor) {
        WallpaperEditorSheet(
            current = current,
            onDismiss = { showEditor = false },
            onChange = onChange,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WallpaperEditorSheet(
    current: WallpaperSelection,
    onDismiss: () -> Unit,
    onChange: (WallpaperSelection) -> Unit,
) {
    // Keep the active edit local so delayed preference emissions cannot rewind a slider drag.
    var selection by remember { mutableStateOf(current.normalized()) }

    fun updateSelection(next: WallpaperSelection) {
        selection = next.normalized()
        onChange(selection)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_wallpaper_sheet")) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.settings_wallpaper), style = MaterialTheme.typography.titleLarge)
            WallpaperPreview(selection, Modifier.fillMaxWidth().height(190.dp).padding(top = 14.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(330.dp).padding(top = 14.dp),
            ) {
                items(ChatWallpaperPreset.entries) { preset ->
                    WallpaperCard(
                        preset = preset,
                        intensity = selection.intensity,
                        selected = selection.preset == preset,
                        onClick = { updateSelection(selection.copy(preset = preset)) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_wallpaper_intensity), modifier = Modifier.weight(1f))
                Text("${selection.intensity}%", fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = selection.intensity.toFloat(),
                onValueChange = { updateSelection(selection.copy(intensity = it.roundToInt())) },
                valueRange = 0f..100f,
                steps = 19,
                enabled = selection.preset != ChatWallpaperPreset.NONE,
                modifier = Modifier.testTag("settings_wallpaper_intensity"),
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                Button(onClick = onDismiss, modifier = Modifier.testTag("settings_wallpaper_done")) {
                    Text(stringResource(R.string.settings_wallpaper_done))
                }
            }
        }
    }
}

@Composable
private fun WallpaperPreview(
    selection: WallpaperSelection,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.background)) {
        ChatWallpaperBackground(selection, Modifier.matchParentSize())
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(stringResource(R.string.settings_wallpaper_preview_incoming), Modifier.padding(12.dp))
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.settings_wallpaper_preview_outgoing), Modifier.padding(12.dp))
            }
        }
    }
}

@Composable
private fun WallpaperCard(
    preset: ChatWallpaperPreset,
    intensity: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick).testTag("settings_wallpaper_preset_${preset.name.lowercase()}"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.background)
                .border(if (selected) 3.dp else 1.dp, border, shape),
        ) {
            ChatWallpaperBackground(
                wallpaper = WallpaperSelection(preset, intensity),
                modifier = Modifier.matchParentSize(),
                tilePeriodDp = WALLPAPER_THUMBNAIL_TILE_PERIOD_DP,
            )
            if (selected) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(bottomStart = 10.dp), modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp).padding(2.dp))
                }
            }
        }
        Text(wallpaperLabel(preset), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 5.dp))
        wallpaperDescription(preset)?.let {
            Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun wallpaperLabel(preset: ChatWallpaperPreset): String =
    stringResource(
        when (preset) {
            ChatWallpaperPreset.NONE -> R.string.settings_wallpaper_none
            ChatWallpaperPreset.MOTD -> R.string.settings_wallpaper_motd
            ChatWallpaperPreset.DEEP_SPACE -> R.string.settings_wallpaper_deep_space
            ChatWallpaperPreset.RETRO_GAMING -> R.string.settings_wallpaper_retro_gaming
            ChatWallpaperPreset.RADIO_CLUB -> R.string.settings_wallpaper_radio_club
            ChatWallpaperPreset.INTERNET_ODDITIES -> R.string.settings_wallpaper_internet_oddities
            ChatWallpaperPreset.RETRO_CHAT -> R.string.settings_wallpaper_retro_chat
            ChatWallpaperPreset.MEMES -> R.string.settings_wallpaper_memes
        },
    )

@Composable
private fun wallpaperDescription(preset: ChatWallpaperPreset): String? =
    when (preset) {
        ChatWallpaperPreset.NONE -> null
        ChatWallpaperPreset.MOTD -> stringResource(R.string.settings_wallpaper_motd_desc)
        ChatWallpaperPreset.DEEP_SPACE -> stringResource(R.string.settings_wallpaper_deep_space_desc)
        ChatWallpaperPreset.RETRO_GAMING -> stringResource(R.string.settings_wallpaper_retro_gaming_desc)
        ChatWallpaperPreset.RADIO_CLUB -> stringResource(R.string.settings_wallpaper_radio_club_desc)
        ChatWallpaperPreset.INTERNET_ODDITIES -> stringResource(R.string.settings_wallpaper_internet_oddities_desc)
        ChatWallpaperPreset.RETRO_CHAT -> stringResource(R.string.settings_wallpaper_retro_chat_desc)
        ChatWallpaperPreset.MEMES -> stringResource(R.string.settings_wallpaper_memes_desc)
    }
