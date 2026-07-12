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
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import kotlin.math.roundToInt

@Composable
fun ChatWallpaperPicker(
    current: WallpaperSelection,
    onApply: (WallpaperSelection) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEditor by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_wallpaper)) },
        supportingContent = {
            Text(
                if (current.preset == ChatWallpaperPreset.NONE) wallpaperLabel(current.preset)
                else "${wallpaperLabel(current.preset)} · ${current.intensity}%",
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
            onApply = { onApply(it); showEditor = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WallpaperEditorSheet(
    current: WallpaperSelection,
    onDismiss: () -> Unit,
    onApply: (WallpaperSelection) -> Unit,
) {
    var staged by remember(current) { mutableStateOf(current.normalized()) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_wallpaper_sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.settings_wallpaper), style = MaterialTheme.typography.titleLarge)
            WallpaperPreview(staged, Modifier.fillMaxWidth().height(190.dp).padding(top = 14.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(330.dp).padding(top = 14.dp),
            ) {
                items(ChatWallpaperPreset.entries) { preset ->
                    WallpaperCard(
                        preset = preset,
                        intensity = staged.intensity,
                        selected = staged.preset == preset,
                        onClick = { staged = staged.copy(preset = preset) },
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_wallpaper_intensity), modifier = Modifier.weight(1f))
                Text("${staged.intensity}%", fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = staged.intensity.toFloat(),
                onValueChange = { staged = staged.copy(intensity = it.roundToInt()) },
                valueRange = 0f..100f,
                steps = 19,
                enabled = staged.preset != ChatWallpaperPreset.NONE,
                modifier = Modifier.testTag("settings_wallpaper_intensity"),
            )
            Row(
                Modifier.fillMaxWidth().padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_wallpaper_cancel)) }
                Button(onClick = { onApply(staged.normalized()) }, modifier = Modifier.testTag("settings_wallpaper_apply")) {
                    Text(stringResource(R.string.settings_wallpaper_apply))
                }
            }
        }
    }
}

@Composable
private fun WallpaperPreview(selection: WallpaperSelection, modifier: Modifier = Modifier) {
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
        Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(84.dp).clip(shape).background(MaterialTheme.colorScheme.background).border(if (selected) 3.dp else 1.dp, border, shape)) {
            ChatWallpaperBackground(WallpaperSelection(preset, intensity), Modifier.matchParentSize())
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
private fun wallpaperLabel(preset: ChatWallpaperPreset): String = stringResource(
    when (preset) {
        ChatWallpaperPreset.NONE -> R.string.settings_wallpaper_none
        ChatWallpaperPreset.CHATTER -> R.string.settings_wallpaper_chatter
        ChatWallpaperPreset.CHANNELS -> R.string.settings_wallpaper_channels
        ChatWallpaperPreset.TERMINAL -> R.string.settings_wallpaper_terminal
        ChatWallpaperPreset.RELAY -> R.string.settings_wallpaper_relay
        ChatWallpaperPreset.SIGNALS -> R.string.settings_wallpaper_signals
        ChatWallpaperPreset.PIXELS -> R.string.settings_wallpaper_pixels
    },
)

@Composable
private fun wallpaperDescription(preset: ChatWallpaperPreset): String? = when (preset) {
    ChatWallpaperPreset.NONE -> null
    ChatWallpaperPreset.CHATTER -> stringResource(R.string.settings_wallpaper_chatter_desc)
    ChatWallpaperPreset.CHANNELS -> stringResource(R.string.settings_wallpaper_channels_desc)
    ChatWallpaperPreset.TERMINAL -> stringResource(R.string.settings_wallpaper_terminal_desc)
    ChatWallpaperPreset.RELAY -> stringResource(R.string.settings_wallpaper_relay_desc)
    ChatWallpaperPreset.SIGNALS -> stringResource(R.string.settings_wallpaper_signals_desc)
    ChatWallpaperPreset.PIXELS -> stringResource(R.string.settings_wallpaper_pixels_desc)
}
