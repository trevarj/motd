package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.hueColor

/** The 12 fixed hues shown in the swatch grid (30° apart around the wheel). */
private val PICKER_HUES = listOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)

/**
 * Hue swatch picker built from Compose primitives (no color-picker dependency). Renders 12 swatches
 * using the active palette's saturation/lightness so the preview matches how the override will
 * actually render. [onPick] receives the chosen hue, or null for "Auto" (clear the override).
 */
@Composable
fun NickHuePickerDialog(
    nick: String,
    currentHue: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = LocalNickColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(nick) },
        text = {
            Column {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(PICKER_HUES, key = { it }) { hue ->
                        val selected = hue == currentHue
                        // Swatch filled with the resolved palette color; selected swatch gets a border.
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(hueColor(hue, scheme.isDark, scheme.palette))
                                .then(
                                    if (selected) {
                                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { onPick(hue) },
                        )
                    }
                }
                TextButton(
                    onClick = { onPick(null) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.manage_color_auto))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.onboarding_back))
            }
        },
    )
}

@Preview
@Composable
private fun NickHuePickerDialogPreview() {
    MotdTheme {
        NickHuePickerDialog(nick = "alice", currentHue = 210, onPick = {}, onDismiss = {})
    }
}
