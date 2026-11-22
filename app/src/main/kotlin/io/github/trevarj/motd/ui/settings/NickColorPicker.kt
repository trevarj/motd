package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.NickColorOverride
import io.github.trevarj.motd.ui.components.ThemeColorSwatches
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import io.github.trevarj.motd.ui.components.EmojiText as Text

/** The 12 fixed hues shown in the swatch grid (30° apart around the wheel). */
private val PICKER_HUES = listOf(0, 30, 60, 90, 120, 150, 180, 210, 240, 270, 300, 330)

/**
 * Offers the current theme colors and 12 fixed hues rendered through the active nickname palette.
 * [onPick] receives the stored override (tagged exact color or hue), or null for "Auto".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NickColorPickerDialog(
    nick: String,
    currentColor: Int?,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = LocalNickColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(nick) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ThemeColorSwatches(
                    selected = currentColor?.let(NickColorOverride::toArgb),
                    onSelect = { onPick(NickColorOverride.fromArgb(it)) },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.selectableGroup(),
                ) {
                    PICKER_HUES.forEach { hue ->
                        val selected = hue == currentColor
                        val description = stringResource(R.string.manage_color_hue, hue)
                        // Swatch filled with the resolved palette color; selected swatch gets a border.
                        Box(
                            modifier =
                                Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(scheme.hue(hue))
                                    .then(
                                        if (selected) {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                        } else {
                                            Modifier
                                        },
                                    ).testTag("nick_hue_$hue")
                                    .semantics { contentDescription = description }
                                    .selectable(selected = selected, role = Role.RadioButton) { onPick(hue) },
                        )
                    }
                }
                TextButton(
                    colors = readableTextButtonColors(),
                    onClick = { onPick(null) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.manage_color_auto))
                }
            }
        },
        confirmButton = {
            TextButton(colors = readableTextButtonColors(), onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

@Preview
@Composable
private fun NickColorPickerDialogPreview() {
    MotdTheme {
        NickColorPickerDialog(nick = "alice", currentColor = 210, onPick = {}, onDismiss = {})
    }
}
