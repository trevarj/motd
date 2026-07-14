package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.service.PresenceState

/**
 * Shared nick bottom sheet (plans/16 §5.8), used from the chat timeline and ChannelInfo. Stateless:
 * the header shows WHOIS details when available; the moderation block appears only when
 * [canModerate] and the nick is not self (Confirmed #7). Kick/Ban open a confirm dialog.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NickActionSheet(
    nick: String,
    networkId: Long? = null,
    isSelf: Boolean,
    isFriend: Boolean,
    isFool: Boolean,
    canModerate: Boolean,
    whois: WhoisInfo?,
    presence: PresenceState? = null,
    onDismiss: () -> Unit,
    onMessage: () -> Unit,
    onMention: () -> Unit,
    onToggleFriend: () -> Unit,
    onToggleFool: () -> Unit,
    onOp: (grant: Boolean) -> Unit,
    onVoice: (grant: Boolean) -> Unit,
    onKick: (reason: String?) -> Unit,
    onBan: () -> Unit,
    showMention: Boolean = true,
) {
    var kickTarget by remember { mutableStateOf(false) }
    var banTarget by remember { mutableStateOf(false) }

    // Root tag disambiguates from MessageActionSheet when both could be open.
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("nick_sheet")) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            // Header: avatar + nick + whois summary (or the fallback line when whois is unavailable).
            ListItem(
                headlineContent = { Text(nick) },
                supportingContent = { WhoisSummary(whois, presence) },
                leadingContent = { Avatar(name = nick, size = 40.dp, networkId = networkId) },
            )
            HorizontalDivider()

            // Purpose-built nick-sheet labels shared by chat timeline and ChannelInfo.
            NickAction(Icons.AutoMirrored.Outlined.Message, stringResource(R.string.nick_sheet_message), onMessage)
            if (showMention) {
                NickAction(Icons.Outlined.AlternateEmail, stringResource(R.string.nick_sheet_mention), onMention)
            }
            if (!isSelf) {
                NickAction(
                    if (isFriend) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    stringResource(if (isFriend) R.string.nick_sheet_remove_friend else R.string.nick_sheet_add_friend),
                    onToggleFriend,
                )
                NickAction(
                    if (isFool) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    stringResource(if (isFool) R.string.nick_sheet_remove_fool else R.string.nick_sheet_add_fool),
                    onToggleFool,
                )
            }

            if (canModerate && !isSelf) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                NickAction(Icons.Filled.Shield, stringResource(R.string.nick_sheet_give_op)) { onOp(true) }
                NickAction(Icons.Filled.Shield, stringResource(R.string.nick_sheet_take_op)) { onOp(false) }
                NickAction(Icons.Filled.RecordVoiceOver, stringResource(R.string.nick_sheet_give_voice)) { onVoice(true) }
                NickAction(Icons.Filled.RecordVoiceOver, stringResource(R.string.nick_sheet_take_voice)) { onVoice(false) }
                NickAction(Icons.Filled.Gavel, stringResource(R.string.nick_sheet_kick)) { kickTarget = true }
                NickAction(Icons.Filled.Block, stringResource(R.string.nick_sheet_ban)) { banTarget = true }
            }
        }
    }

    if (kickTarget) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { kickTarget = false },
            title = { Text(stringResource(R.string.nick_sheet_kick_title, nick)) },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text(stringResource(R.string.nick_sheet_kick_reason_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    kickTarget = false
                    onKick(reason.ifBlank { null })
                }) { Text(stringResource(R.string.nick_sheet_kick)) }
            },
            dismissButton = {
                TextButton(onClick = { kickTarget = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (banTarget) {
        AlertDialog(
            onDismissRequest = { banTarget = false },
            title = { Text(stringResource(R.string.nick_sheet_ban_title, nick)) },
            confirmButton = {
                TextButton(onClick = { banTarget = false; onBan() }) {
                    Text(stringResource(R.string.nick_sheet_ban))
                }
            },
            dismissButton = {
                TextButton(onClick = { banTarget = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** WHOIS summary lines, or the "details in server messages" fallback while whois is null. */
@Composable
private fun WhoisSummary(whois: WhoisInfo?, presence: PresenceState?) {
    Column {
        if (presence != null) {
            Text(
                text = stringResource(
                    when (presence) {
                        PresenceState.ONLINE -> R.string.presence_online
                        PresenceState.OFFLINE -> R.string.presence_offline
                        PresenceState.UNKNOWN -> R.string.presence_unknown
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("nick_sheet_presence_${presence.name.lowercase()}"),
            )
        }
        if (whois == null) {
            Text(
                text = stringResource(R.string.nick_sheet_details_in_server),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        whois.realname?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (whois.username != null && whois.host != null) {
            Text(stringResource(R.string.whois_userhost, whois.username, whois.host), style = MaterialTheme.typography.bodySmall)
        }
        whois.account?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.whois_account, it), style = MaterialTheme.typography.bodySmall)
        }
        whois.channels.takeIf { it.isNotEmpty() }?.let {
            Text(stringResource(R.string.whois_channels, it.joinToString(" ")), style = MaterialTheme.typography.bodySmall)
        }
        whois.server?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.whois_server, it), style = MaterialTheme.typography.bodySmall)
        }
        whois.idleSecs?.let {
            Text(stringResource(R.string.whois_idle, "${it}s"), style = MaterialTheme.typography.bodySmall)
        }
        whois.awayMessage?.takeIf { it.isNotBlank() }?.let {
            Text(stringResource(R.string.whois_away, it), style = MaterialTheme.typography.bodySmall)
        } ?: if (whois.away == true) {
            Text(stringResource(R.string.nick_sheet_away), style = MaterialTheme.typography.bodySmall)
        } else {
            Unit
        }
    }
}

@Composable
private fun NickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(label) },
            leadingContent = { Icon(icon, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }
}
