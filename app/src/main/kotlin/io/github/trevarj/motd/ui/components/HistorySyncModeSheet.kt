package io.github.trevarj.motd.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.ui.settings.ChoiceOption
import io.github.trevarj.motd.ui.settings.SingleChoiceSheet

@Composable
fun HistorySyncModeSheet(
    selected: HistorySyncMode?,
    global: HistorySyncMode,
    includeInherit: Boolean,
    unsupported: Boolean = false,
    onSelect: (HistorySyncMode?) -> Unit,
    onDismiss: () -> Unit,
    tag: String,
) {
    val disclosure =
        if (unsupported) {
            stringResource(R.string.settings_history_sync_disclosure) +
                "\n\n" +
                stringResource(R.string.settings_history_sync_unsupported)
        } else {
            stringResource(R.string.settings_history_sync_disclosure)
        }
    val prefix = if (includeInherit) "chat_history_sync" else "settings_history_sync"
    val options =
        buildList<ChoiceOption<HistorySyncMode?>> {
            if (includeInherit) {
                add(
                    ChoiceOption(
                        null,
                        stringResource(R.string.chat_history_sync_inherit, stringResource(historySyncModeLabel(global))),
                        tag = "chat_history_sync_inherit",
                    ),
                )
            }
            HistorySyncMode.entries.forEach { mode ->
                add(
                    ChoiceOption(
                        mode,
                        stringResource(historySyncModeLabel(mode)),
                        stringResource(historySyncModeDescription(mode)),
                        "${prefix}_${mode.name.lowercase()}",
                    ),
                )
            }
        }
    SingleChoiceSheet(
        title = stringResource(R.string.settings_history_sync_mode),
        selected = selected,
        options = options,
        onSelect = onSelect,
        onDismiss = onDismiss,
        tag = tag,
        footer = {
            Text(
                text = disclosure,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        },
    )
}

@StringRes
fun historySyncModeLabel(mode: HistorySyncMode): Int =
    when (mode) {
        HistorySyncMode.BALANCED -> R.string.settings_history_sync_balanced
        HistorySyncMode.AGGRESSIVE -> R.string.settings_history_sync_aggressive
        HistorySyncMode.LAZY -> R.string.settings_history_sync_lazy
    }

@StringRes
fun historySyncModeDescription(mode: HistorySyncMode): Int =
    when (mode) {
        HistorySyncMode.BALANCED -> R.string.settings_history_sync_balanced_desc
        HistorySyncMode.AGGRESSIVE -> R.string.settings_history_sync_aggressive_desc
        HistorySyncMode.LAZY -> R.string.settings_history_sync_lazy_desc
    }
