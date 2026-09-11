package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GroupAdd
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.dickord.dickordNickLabel
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.ui.channelinfo.BanTargetDialog
import io.github.trevarj.motd.ui.channelinfo.ModeCatalog
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.ReasonPresetChips
import io.github.trevarj.motd.ui.components.avatarsHidden
import io.github.trevarj.motd.ui.theme.SheetSystemBars

/**
 * Shared nick bottom sheet, used from the chat timeline and ChannelInfo. Stateless:
 * the header normally shows WHOIS details when available; the moderation block appears only when
 * [canModerate] and the nick is not self (Confirmed #7). [relayIdentity] keeps only actions Motd
 * can honor for a synthetic relay nick.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
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
    onIgnoreNetwork: () -> Unit = {},
    onInviteToChannel: (() -> Unit)? = null,
    onOp: (grant: Boolean) -> Unit,
    onVoice: (grant: Boolean) -> Unit,
    onKick: (reason: String?) -> Unit,
    onBan: (mask: String, alsoKick: Boolean) -> Unit,
    showMention: Boolean = true,
    /** ISUPPORT vocabulary, for KICKLEN. Null when the network is not Ready. */
    modeCatalog: ModeCatalog? = null,
    /** [nick]'s address, when WHOIS or the cache knows it, for the address-scoped ban. */
    resolvedHost: String? = null,
    hostLoading: Boolean = false,
    onNickSelected: (String?) -> Unit = {},
    conversationModel: String? = null,
    canEditConversationAvatar: Boolean = false,
    onEditConversationAvatar: () -> Unit = {},
    relayIdentity: Boolean = false,
) {
    var kickTarget by remember { mutableStateOf(false) }
    var banTarget by remember { mutableStateOf(false) }
    val canEditAvatar = canEditConversationAvatar && !relayIdentity

    // Root tag disambiguates from MessageActionSheet when both could be open.
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("nick_sheet")) {
        SheetSystemBars()
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            // Header: avatar + nick + whois summary (or the fallback line when whois is unavailable).
            ListItem(
                headlineContent = { Text(dickordNickLabel(nick, relayIdentity)) },
                supportingContent = {
                    if (relayIdentity) {
                        Text(stringResource(R.string.dickord_relay_identity))
                    } else {
                        WhoisSummary(whois, presence)
                    }
                },
                leadingContent =
                    if (avatarsHidden()) {
                        null
                    } else {
                        {
                            Avatar(
                                name = nick,
                                size = 40.dp,
                                networkId = networkId,
                                conversationModel = conversationModel,
                                modifier =
                                    if (canEditAvatar) {
                                        Modifier.testTag("nick_sheet_avatar").clickable(onClick = onEditConversationAvatar)
                                    } else {
                                        Modifier
                                    },
                            )
                        }
                    },
            )
            if (canEditAvatar) {
                NickAction(
                    Icons.Outlined.Edit,
                    stringResource(R.string.avatar_editor_action),
                    onEditConversationAvatar,
                    tag = "nick_sheet_edit_avatar",
                )
            }
            HorizontalDivider()

            // Purpose-built nick-sheet labels shared by chat timeline and ChannelInfo.
            if (!relayIdentity) {
                NickAction(Icons.AutoMirrored.Outlined.Message, stringResource(R.string.nick_sheet_message), onMessage)
            }
            if (showMention) {
                NickAction(Icons.Outlined.AlternateEmail, stringResource(R.string.nick_sheet_mention), onMention)
            }
            if (!relayIdentity && !isSelf) {
                onInviteToChannel?.let { invite ->
                    NickAction(
                        Icons.Outlined.GroupAdd,
                        stringResource(R.string.nick_sheet_invite_to_channel),
                        invite,
                        tag = "nick_sheet_invite_to_channel",
                    )
                }
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
                NickAction(Icons.Outlined.Block, stringResource(R.string.nick_sheet_ignore_network), onIgnoreNetwork)
            }

            if (!relayIdentity && canModerate && !isSelf) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                NickAction(Icons.Filled.Shield, stringResource(R.string.nick_sheet_give_op), onClick = { onOp(true) })
                NickAction(Icons.Filled.Shield, stringResource(R.string.nick_sheet_take_op), onClick = { onOp(false) })
                NickAction(Icons.Filled.RecordVoiceOver, stringResource(R.string.nick_sheet_give_voice), onClick = { onVoice(true) })
                NickAction(Icons.Filled.RecordVoiceOver, stringResource(R.string.nick_sheet_take_voice), onClick = { onVoice(false) })
                NickAction(Icons.Filled.Gavel, stringResource(R.string.nick_sheet_kick), onClick = { kickTarget = true })
                NickAction(Icons.Filled.Block, stringResource(R.string.nick_sheet_ban), onClick = { banTarget = true })
            }
        }
    }

    if (!relayIdentity && kickTarget) {
        NickKickDialog(
            nick = nick,
            kickLen = modeCatalog?.kickLen,
            onDismiss = { kickTarget = false },
            onKick = { reason ->
                kickTarget = false
                onKick(reason)
            },
        )
    }

    // The old dialog had a title and no body: it never said the ban persists, and never showed the
    // nick!*@* mask a nick change trivially evades. The shared picker states both.
    if (!relayIdentity && banTarget) {
        BanTargetDialog(
            dialogTag = "nick_sheet_ban_dialog",
            // Naming the target beats the generic "Ban someone" when the sheet already has a nick.
            title = stringResource(R.string.nick_sheet_ban_title, nick),
            members = listOf(nick),
            resolvedHost = resolvedHost,
            hostLoading = hostLoading,
            onNickSelected = onNickSelected,
            preselectedNick = nick,
            onDismiss = {
                banTarget = false
                onNickSelected(null)
            },
            onBan = { _, mask, alsoKick ->
                banTarget = false
                onNickSelected(null)
                onBan(mask, alsoKick)
            },
        )
    }
}

@Composable
internal fun NickKickDialog(
    nick: String,
    kickLen: Int?,
    onDismiss: () -> Unit,
    onKick: (String?) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag("nick_sheet_kick_dialog"),
        title = { Text(stringResource(R.string.nick_sheet_kick_title, nick)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReasonPresetChips(
                    current = reason,
                    onSelect = { reason = it },
                    tagPrefix = "nick_sheet_kick_chip",
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { if (kickLen == null || it.length <= kickLen) reason = it },
                    label = { Text(stringResource(R.string.nick_sheet_kick_reason_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("nick_sheet_kick_reason"),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onKick(reason.ifBlank { null }) },
                modifier = Modifier.testTag("nick_sheet_kick_confirm"),
            ) { Text(stringResource(R.string.nick_sheet_kick)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** WHOIS summary lines, or the "details in server messages" fallback while whois is null. */
@Composable
private fun WhoisSummary(
    whois: WhoisInfo?,
    presence: PresenceState?,
) {
    Column {
        if (presence != null) {
            Text(
                text =
                    stringResource(
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
        val awayMessage = whois.awayMessage?.takeIf { it.isNotBlank() }
        if (awayMessage != null) {
            Text(stringResource(R.string.whois_away, awayMessage), style = MaterialTheme.typography.bodySmall)
        } else if (whois.away == true) {
            Text(stringResource(R.string.nick_sheet_away), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    tag: String? = null,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(label) },
            leadingContent = { Icon(icon, contentDescription = null) },
            modifier = Modifier.then(tag?.let(Modifier::testTag) ?: Modifier).clickable(onClick = onClick),
        )
    }
}
