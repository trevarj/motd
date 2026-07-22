package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.LayoutDensity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationLayoutSheet(
    state: ConversationLayoutState,
    onSelect: (LayoutDensity?) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("chat_layout_sheet")) {
        Text(text = stringResource(R.string.chat_layout_title))
        androidx.compose.foundation.layout.Column(Modifier.selectableGroup()) {
            LayoutOption(
                value = null,
                selected = state.override == null,
                tag = "chat_layout_global",
                label = stringResource(R.string.chat_layout_use_global),
                supporting = stringResource(
                    R.string.chat_layout_global_summary,
                    densityLabel(state.global),
                ),
                onSelect = onSelect,
            )
            LayoutDensity.entries.forEach { density ->
                LayoutOption(
                    value = density,
                    selected = state.override == density,
                    tag = "chat_layout_${density.name.lowercase()}",
                    label = densityLabel(density),
                    supporting = null,
                    onSelect = onSelect,
                )
            }
        }
    }
}

@Composable
private fun LayoutOption(
    value: LayoutDensity?,
    selected: Boolean,
    tag: String,
    label: String,
    supporting: String?,
    onSelect: (LayoutDensity?) -> Unit,
) {
    ListItem(
        modifier = Modifier
            .testTag(tag)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { onSelect(value) },
            ),
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { text -> { Text(text) } },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    )
}

@Composable
internal fun densityLabel(density: LayoutDensity): String = stringResource(
    when (density) {
        LayoutDensity.COMPACT -> R.string.settings_density_compact
        LayoutDensity.COMFORTABLE -> R.string.settings_density_comfortable
        LayoutDensity.TWO_LINE -> R.string.settings_density_two_line
    },
)
