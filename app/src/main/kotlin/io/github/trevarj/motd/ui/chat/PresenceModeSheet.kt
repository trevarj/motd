package io.github.trevarj.motd.ui.chat

import androidx.annotation.StringRes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.ui.components.PresenceExample
import io.github.trevarj.motd.ui.settings.ChoiceOption
import io.github.trevarj.motd.ui.settings.SingleChoiceSheet

@Composable
fun PresenceModeSheet(
    state: ConversationPresenceState,
    onSelect: (PresenceMode?) -> Unit,
    onDismiss: () -> Unit,
) {
    SingleChoiceSheet(
        title = stringResource(R.string.chat_presence_title),
        selected = state.override,
        options =
            listOf(ChoiceOption<PresenceMode?>(null, stringResource(R.string.chat_presence_use_global), stringResource(R.string.chat_presence_global_summary, stringResource(presenceModeLabel(state.global))), "chat_presence_global")) +
                PresenceMode.entries.map { ChoiceOption<PresenceMode?>(it, stringResource(presenceModeLabel(it)), stringResource(presenceModeDescription(it)), "chat_presence_${it.name.lowercase()}") },
        onSelect = onSelect,
        onDismiss = onDismiss,
        tag = "chat_presence_sheet",
        dismissOnSelect = false,
        preview = {
            Text(stringResource(if (state.override == null) R.string.settings_presence_inherited_scope else R.string.settings_presence_chat_scope))
            PresenceExample(state.effective)
        },
    )
}

@StringRes
internal fun presenceModeLabel(mode: PresenceMode): Int =
    when (mode) {
        PresenceMode.ALL -> R.string.settings_presence_all
        PresenceMode.SMART -> R.string.settings_presence_smart
        PresenceMode.HIDDEN -> R.string.settings_presence_hidden
    }

@StringRes
internal fun presenceModeDescription(mode: PresenceMode): Int =
    when (mode) {
        PresenceMode.ALL -> R.string.settings_presence_all_desc
        PresenceMode.SMART -> R.string.settings_presence_smart_desc
        PresenceMode.HIDDEN -> R.string.settings_presence_hidden_desc
    }
