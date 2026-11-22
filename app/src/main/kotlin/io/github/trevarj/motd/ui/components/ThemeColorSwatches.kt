package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.chat.wallpaperColorHex
import io.github.trevarj.motd.ui.theme.LocalNickColors

@Composable
internal fun ThemeColorSwatches(
    selected: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val identity = LocalNickColors.current.identityPalette
    val swatches =
        remember(scheme, identity) {
            buildList {
                add(R.string.palette_editor_primary to scheme.primary.toArgb())
                add(R.string.palette_editor_secondary to scheme.secondary.toArgb())
                add(R.string.palette_editor_tertiary to scheme.tertiary.toArgb())
                identity.forEach { add(null to it.toArgb()) }
                add(R.string.settings_bubble_color_primary_container to scheme.primaryContainer.toArgb())
                add(R.string.settings_bubble_color_secondary_container to scheme.secondaryContainer.toArgb())
                add(R.string.settings_bubble_color_tertiary_container to scheme.tertiaryContainer.toArgb())
                add(R.string.palette_editor_background to scheme.background.toArgb())
                add(R.string.palette_editor_surface to scheme.surface.toArgb())
                add(R.string.palette_editor_container to scheme.surfaceContainer.toArgb())
                add(R.string.palette_editor_on_background to scheme.onBackground.toArgb())
                add(R.string.palette_editor_on_surface to scheme.onSurface.toArgb())
                add(R.string.palette_editor_supporting to scheme.onSurfaceVariant.toArgb())
                add(R.string.palette_editor_outline to scheme.outlineVariant.toArgb())
            }.distinctBy { it.second }
        }
    Column(modifier.fillMaxWidth()) {
        EmojiText(stringResource(R.string.theme_color_swatches), style = MaterialTheme.typography.titleSmall)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            swatches.forEach { (label, argb) ->
                val hex = remember(argb) { wallpaperColorHex(argb) }
                val description = if (label == null) hex else stringResource(R.string.theme_color_swatch_description, stringResource(label), hex)
                val isSelected = argb == selected
                Box(
                    Modifier
                        .size(48.dp)
                        .then(if (isSelected) Modifier.border(2.dp, scheme.onSurface, CircleShape) else Modifier)
                        .clip(CircleShape)
                        .testTag("theme_color_${hex.drop(1)}")
                        .semantics { contentDescription = description }
                        .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(argb) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(32.dp).background(Color(argb), CircleShape).border(1.dp, scheme.outline, CircleShape))
                }
            }
        }
    }
}
