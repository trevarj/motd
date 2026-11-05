package io.github.trevarj.motd.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.ui.components.PresenceExample
import io.github.trevarj.motd.ui.settings.ChoiceOption
import io.github.trevarj.motd.ui.settings.SingleChoiceSheet

@Composable
fun PresenceModeSheet(
    state: ConversationPresenceState,
    onSelect: (PresenceMode?) -> Unit,
    onCustomKindSelected: (MessageKind) -> Unit,
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
            Text(
                stringResource(if (state.override == null) R.string.settings_presence_inherited_scope else R.string.settings_presence_chat_scope),
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                fontStyle = if (state.override == null) androidx.compose.ui.text.font.FontStyle.Italic else androidx.compose.ui.text.font.FontStyle.Normal,
            )
            PresenceExample(state.effective, state.effectiveCustomKinds)
        },
        footer = {
            if (state.override == PresenceMode.CUSTOM) {
                PresenceKindChoices(state.effectiveCustomKinds, onCustomKindSelected, "chat_presence_custom")
            }
        },
    )
}

@StringRes
internal fun presenceModeLabel(mode: PresenceMode): Int =
    when (mode) {
        PresenceMode.ALL -> R.string.settings_presence_all
        PresenceMode.SMART -> R.string.settings_presence_smart
        PresenceMode.CUSTOM -> R.string.settings_presence_custom
        PresenceMode.HIDDEN -> R.string.settings_presence_hidden
    }

@StringRes
internal fun presenceModeDescription(mode: PresenceMode): Int =
    when (mode) {
        PresenceMode.ALL -> R.string.settings_presence_all_desc
        PresenceMode.SMART -> R.string.settings_presence_smart_desc
        PresenceMode.CUSTOM -> R.string.settings_presence_custom_desc
        PresenceMode.HIDDEN -> R.string.settings_presence_hidden_desc
    }

@Composable
internal fun PresenceKindChoices(
    selected: Set<MessageKind>,
    onSelected: (MessageKind) -> Unit,
    tagPrefix: String,
) {
    for (kind in listOf(MessageKind.JOIN, MessageKind.PART, MessageKind.QUIT, MessageKind.NICK, MessageKind.AWAY, MessageKind.BACK, MessageKind.NETSPLIT, MessageKind.NETJOIN)) {
        val checked = kind in selected
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("${tagPrefix}_${kind.name.lowercase()}")
                .toggleable(checked, role = Role.Checkbox) {
                    onSelected(kind)
                }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Text(stringResource(presenceKindLabel(kind)), Modifier.weight(1f).padding(start = 12.dp))
        }
    }
}

@StringRes
internal fun presenceKindLabel(kind: MessageKind): Int =
    when (kind) {
        MessageKind.JOIN -> R.string.settings_presence_kind_join
        MessageKind.PART -> R.string.settings_presence_kind_part
        MessageKind.QUIT -> R.string.settings_presence_kind_quit
        MessageKind.NICK -> R.string.settings_presence_kind_nick
        MessageKind.AWAY -> R.string.settings_presence_kind_away
        MessageKind.BACK -> R.string.settings_presence_kind_back
        MessageKind.NETSPLIT -> R.string.settings_presence_kind_netsplit
        MessageKind.NETJOIN -> R.string.settings_presence_kind_netjoin
        else -> error("Not a presence kind: $kind")
    }
