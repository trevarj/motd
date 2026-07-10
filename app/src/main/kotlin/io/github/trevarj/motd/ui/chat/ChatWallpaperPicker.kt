package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.ChatWallpaper

/**
 * Horizontal row of selectable wallpaper preview swatches. Each swatch renders its preset via the
 * real [ChatWallpaperBackground] renderer at thumbnail scale using the current theme, so previews
 * always match the live chat background. The selected swatch shows a highlighted border + check
 * badge; a caption labels each option. NONE renders a plain theme-background swatch.
 */
@Composable
fun ChatWallpaperPicker(
    current: ChatWallpaper,
    onSelect: (ChatWallpaper) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = listOf(
        Triple(ChatWallpaper.NONE, R.string.settings_wallpaper_none, R.string.settings_wallpaper_none_desc),
        Triple(ChatWallpaper.CLASSIC, R.string.settings_wallpaper_classic, R.string.settings_wallpaper_classic_desc),
        Triple(ChatWallpaper.NETWORK, R.string.settings_wallpaper_network, R.string.settings_wallpaper_network_desc),
        Triple(ChatWallpaper.PIXEL, R.string.settings_wallpaper_pixel, R.string.settings_wallpaper_pixel_desc),
    )
    LazyRow(
        modifier = modifier.selectableGroup(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(options.size) { i ->
            val (wallpaper, labelRes, descRes) = options[i]
            WallpaperSwatch(
                wallpaper = wallpaper,
                label = stringResource(labelRes),
                // Combine label + description so the whole tile reads as one accessible option.
                description = "${stringResource(labelRes)}. ${stringResource(descRes)}",
                selected = current == wallpaper,
                onClick = { onSelect(wallpaper) },
            )
        }
    }
}

@Composable
private fun WallpaperSwatch(
    wallpaper: ChatWallpaper,
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (selected) 3.dp else 1.dp
    val shape = RoundedCornerShape(14.dp)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(72.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            // The whole tile is one selectable option; label/desc describe it once.
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(shape)
                .border(borderWidth, borderColor, shape),
        ) {
            // The preview swatch is a small, clipped instance of the real renderer.
            ChatWallpaperBackground(wallpaper, modifier = Modifier.size(64.dp))
            if (selected) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(bottomStart = 10.dp),
                    modifier = Modifier.align(Alignment.TopEnd),
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(16.dp).padding(1.dp),
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
