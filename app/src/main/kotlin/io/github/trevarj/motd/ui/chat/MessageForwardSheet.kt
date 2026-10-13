package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.irc.format.containsIrcFormatting
import io.github.trevarj.motd.irc.format.plainIrcText
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import io.github.trevarj.motd.ui.share.filterShareTargets
import io.github.trevarj.motd.ui.theme.SheetSystemBars

internal enum class MessageForwardError {
    SOURCE_UNAVAILABLE,
    TARGET_UNAVAILABLE,
    SEND_REJECTED,
    SEND_FAILED,
}

internal data class MessageForwardState(
    val source: MessageEntity,
    val destination: ChatListRow? = null,
    val includeSender: Boolean = true,
    val sending: Boolean = false,
    val error: MessageForwardError? = null,
    val acceptance: SendAcceptance.Accepted? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageForwardSheet(
    state: MessageForwardState,
    targets: List<ChatListRow>,
    onSelectDestination: (ChatListRow) -> Unit,
    onChangeDestination: () -> Unit,
    onIncludeSenderChanged: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sending by rememberUpdatedState(state.sending)
    val sheetState =
        rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !sending },
        )
    var query by remember(state.source.id) { mutableStateOf("") }
    val accepted = state.acceptance != null
    val editable =
        !state.sending && !accepted &&
            state.error != MessageForwardError.SOURCE_UNAVAILABLE &&
            state.error != MessageForwardError.SEND_FAILED
    val error =
        when {
            accepted -> R.string.chat_forward_saved_not_sent
            state.error == MessageForwardError.SOURCE_UNAVAILABLE -> R.string.chat_forward_source_unavailable
            state.error == MessageForwardError.TARGET_UNAVAILABLE -> R.string.chat_forward_target_unavailable
            state.error == MessageForwardError.SEND_REJECTED -> R.string.chat_send_rejected
            state.error == MessageForwardError.SEND_FAILED -> R.string.chat_forward_send_failed
            else -> null
        }
    ModalBottomSheet(
        onDismissRequest = { if (!sending) onDismiss() },
        sheetState = sheetState,
        modifier = Modifier.testTag("message_forward_sheet"),
    ) {
        SheetSystemBars()
        Column(
            modifier = Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.chat_forward_title), style = MaterialTheme.typography.titleLarge)
            if (error != null) {
                Text(
                    stringResource(error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("message_forward_error").semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            val destination = state.destination
            if (destination == null) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    enabled = editable,
                    singleLine = true,
                    label = { Text(stringResource(R.string.share_picker_search)) },
                    modifier = Modifier.fillMaxWidth().testTag("message_forward_search"),
                )
                val visibleTargets = remember(targets, query) { filterShareTargets(targets, query) }
                if (visibleTargets.isEmpty() || !editable) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        if (visibleTargets.isEmpty()) Text(stringResource(R.string.chat_forward_empty))
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(visibleTargets, key = { it.bufferId }) { row ->
                            ChatListRowItem(
                                row = row,
                                showNetworkChip = true,
                                onClick = { onSelectDestination(row) },
                                onLongClick = {},
                                modifier = Modifier.testTag("message_forward_target_${row.bufferId}"),
                            )
                        }
                    }
                }
            } else {
                Text(destination.displayName, style = MaterialTheme.typography.titleMedium)
                Text(destination.networkName, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    Text(
                        text = remember(state.source, state.includeSender) { forwardMessageText(state.source, state.includeSender) },
                        modifier = Modifier.fillMaxWidth().testTag("message_forward_preview"),
                    )
                }
                if (!accepted) {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag("message_forward_include_sender")
                                .toggleable(
                                    value = state.includeSender,
                                    enabled = editable,
                                    role = Role.Checkbox,
                                    onValueChange = onIncludeSenderChanged,
                                ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = state.includeSender, onCheckedChange = null, enabled = editable)
                        Text(stringResource(R.string.chat_forward_include_sender), modifier = Modifier.padding(start = 8.dp))
                    }
                    TextButton(
                        onClick = onChangeDestination,
                        enabled = editable,
                        modifier = Modifier.testTag("message_forward_change_destination"),
                    ) {
                        Text(stringResource(R.string.chat_forward_change_destination))
                    }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !state.sending,
                    modifier = Modifier.testTag("message_forward_cancel"),
                ) {
                    Text(stringResource(if (accepted || !editable && !state.sending) R.string.ai_text_close else R.string.action_cancel))
                }
                if (destination != null && !accepted) {
                    Button(
                        onClick = onConfirm,
                        enabled = editable,
                        modifier = Modifier.testTag("message_forward_confirm"),
                    ) {
                        if (state.sending) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.chat_action_forward))
                        }
                    }
                }
            }
        }
    }
}

internal fun canForwardMessage(
    message: MessageEntity,
    bufferType: BufferType?,
): Boolean {
    if (bufferType != BufferType.CHANNEL && bufferType != BufferType.QUERY) return false
    when (message.kind) {
        MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION -> Unit
        else -> return false
    }
    return cleanForwardText(message.text).isNotBlank() && cleanForwardText(message.sender, singleLine = true).isNotBlank()
}

internal fun forwardMessageText(
    message: MessageEntity,
    includeSender: Boolean,
): String {
    val text = cleanForwardText(message.text)
    val prefix = if (includeSender) "> ${cleanForwardText(message.sender, singleLine = true)}: " else "> "
    // The visible quote also keeps leading /me and other command-like content literal on send.
    return buildString(text.length + prefix.length) {
        append(prefix)
        for (char in text) {
            append(char)
            if (char == '\n') append(prefix)
        }
    }
}

private fun cleanForwardText(
    text: String,
    singleLine: Boolean = false,
): String {
    val plain = if (containsIrcFormatting(text)) plainIrcText(text) else text
    if (plain.none { it < ' ' || it == '\u007f' }) return plain
    return buildString(plain.length) {
        for (index in plain.indices) {
            when (val char = plain[index]) {
                '\r' -> append(if (singleLine) ' ' else '\n')
                '\n' -> if (index == 0 || plain[index - 1] != '\r') append(if (singleLine) ' ' else '\n')
                '\t' -> append(' ')
                else -> if (char >= ' ' && char != '\u007f') append(char)
            }
        }
    }
}
