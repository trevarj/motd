package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.notes.NickNoteKey
import io.github.trevarj.motd.data.notes.NickNoteKind
import io.github.trevarj.motd.dickord.dickordNickLabel
import io.github.trevarj.motd.dickord.isDickordRelayNick
import io.github.trevarj.motd.profile.ProfileKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.ui.channelinfo.BanTargetDialog
import io.github.trevarj.motd.ui.channelinfo.ModeCatalog
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.ReasonPresetChips
import io.github.trevarj.motd.ui.components.avatarsHidden
import io.github.trevarj.motd.ui.components.botDisplayName
import io.github.trevarj.motd.ui.components.openChatLinkInBrowser
import io.github.trevarj.motd.ui.profile.LocalIrcProfileRepository
import io.github.trevarj.motd.ui.profile.ircIdentityLabels
import io.github.trevarj.motd.ui.profile.safeProfileHomepage
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.readableForeground
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import kotlinx.coroutines.launch
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet as ModalBottomSheet
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

internal data class NickIgnoreActions(
    val state: NickIgnoreState,
    val onToggle: () -> Unit,
    val onManage: (() -> Unit)?,
)

@Composable
internal fun rememberNickNoteTarget(bufferId: Long) = rememberSaveable(bufferId, stateSaver = NickNoteTargetSaver) { mutableStateOf<NickNoteTarget?>(null) }

private val NickNoteTargetSaver =
    listSaver<NickNoteTarget?, Any>(
        save = { target -> target?.let { listOf(it.key.networkId, it.key.kind.name, it.key.value, it.displayIdentity) }.orEmpty() },
        restore = {
            if (it.isEmpty()) {
                null
            } else {
                NickNoteTarget(NickNoteKey(it[0] as Long, NickNoteKind.valueOf(it[1] as String), it[2] as String), it[3] as String)
            }
        },
    )

@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
internal fun NickActionSheet(
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
    onOpenFoolSettings: () -> Unit,
    ignoreActions: NickIgnoreActions? = null,
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
    onPrivateNote: (suspend () -> Unit)? = null,
) {
    var kickTarget by remember { mutableStateOf(false) }
    var banTarget by remember { mutableStateOf(false) }
    val canEditAvatar = canEditConversationAvatar && !relayIdentity
    val profileNetworkId = networkId.takeUnless { relayIdentity || isDickordRelayNick(nick) }
    val repository = LocalIrcProfileRepository.current
    val profile =
        if (profileNetworkId != null && repository != null) {
            key(repository, profileNetworkId, nick) {
                val values = remember { repository.observeUserProfile(profileNetworkId, nick) }
                values.collectAsStateWithLifecycle(initialValue = null).value.orEmpty()
            }
        } else {
            emptyMap()
        }
    val (primaryName, secondaryName) = ircIdentityLabels(dickordNickLabel(nick, relayIdentity), profile[ProfileKey.DISPLAY_NAME])
    val isBot = whois?.isBot == true || ProfileKey.BOT in profile
    val colorValue = profile[ProfileKey.COLOR]
    val profileColor = remember(colorValue) { colorValue?.let(::parseColorHex)?.let { Color(it) } }
    val homepage = profile[ProfileKey.HOMEPAGE]
    val link = remember(homepage) { homepage?.let(::safeProfileHomepage) }
    val hasProfileDetails =
        profileColor != null || link != null ||
            !profile[ProfileKey.STATUS].isNullOrBlank() || !profile[ProfileKey.PRONOUNS].isNullOrBlank() ||
            !profile[ProfileKey.BOT].isNullOrBlank()
    val prominentIdentity = hasProfileDetails || secondaryName != null

    // Root tag disambiguates from MessageActionSheet when both could be open.
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("nick_sheet"),
    ) {
        SheetSystemBars()
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            // Avatar, both names if supplied, and profile/WHOIS details.
            ListItem(
                headlineContent = {
                    Text(
                        botDisplayName(primaryName, isBot),
                        style = if (prominentIdentity) MaterialTheme.typography.titleLarge else LocalTextStyle.current,
                    )
                },
                supportingContent = {
                    if (relayIdentity) {
                        Text(stringResource(R.string.dickord_relay_identity))
                    } else {
                        Column {
                            secondaryName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                            if (!hasProfileDetails) WhoisSummary(whois, presence)
                        }
                    }
                },
                leadingContent =
                    if (avatarsHidden()) {
                        null
                    } else {
                        {
                            Avatar(
                                name = nick,
                                size = if (prominentIdentity) 56.dp else 40.dp,
                                networkId = networkId,
                                conversationModel = conversationModel,
                                profileColor = profileColor,
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
            if (hasProfileDetails) {
                key(profileNetworkId, nick) {
                    NickProfileDetails(profile, profileColor, link, whois, presence)
                }
            }
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
            if (!relayIdentity && !isDickordRelayNick(nick) && !isSelf && onPrivateNote != null) {
                var verifying by remember(nick, networkId) { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                NickAction(
                    Icons.Outlined.Description,
                    stringResource(if (verifying) R.string.nick_private_note_verifying else R.string.nick_private_note),
                    onClick = {
                        if (!verifying) {
                            verifying = true
                            scope.launch {
                                try {
                                    onPrivateNote()
                                } finally {
                                    verifying = false
                                }
                            }
                        }
                    },
                    tag = "nick_sheet_private_note",
                    enabled = !verifying,
                )
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
                if (isFool) {
                    NickAction(
                        Icons.Outlined.Edit,
                        stringResource(R.string.nick_sheet_fool_settings),
                        onOpenFoolSettings,
                        tag = "nick_sheet_fool_settings",
                    )
                }
            }
            if (!relayIdentity && !isSelf && ignoreActions != null) {
                val ignore = ignoreActions.state
                NickAction(
                    Icons.Outlined.Block,
                    stringResource(if (ignore.ignored) R.string.nick_sheet_unignore_network else R.string.nick_sheet_ignore_network),
                    ignoreActions.onToggle,
                    tag = if (ignore.ignored) "nick_sheet_unignore_network" else "nick_sheet_ignore_network",
                    enabled = !ignore.saving,
                )
                Text(
                    stringResource(R.string.nick_sheet_ignore_help),
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (ignore.failed) {
                    Text(
                        stringResource(R.string.nick_sheet_ignore_failed),
                        modifier =
                            Modifier
                                .padding(horizontal = 16.dp)
                                .testTag("nick_sheet_ignore_failed")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ignoreActions.onManage?.let { manage ->
                    TextButton(
                        colors = readableTextButtonColors(),
                        onClick = manage,
                        modifier = Modifier.padding(horizontal = 8.dp).testTag("nick_sheet_manage_ignore_rules"),
                    ) { Text(stringResource(R.string.nick_sheet_manage_ignore_rules)) }
                }
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
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                colors = readableTextButtonColors(),
                onClick = { onKick(reason.ifBlank { null }) },
                modifier = Modifier.testTag("nick_sheet_kick_confirm"),
            ) { Text(stringResource(R.string.nick_sheet_kick)) }
        },
        dismissButton = {
            TextButton(colors = readableTextButtonColors(), onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun NickProfileDetails(
    profile: Map<ProfileKey, String>,
    profileColor: Color?,
    homepage: String?,
    whois: WhoisInfo?,
    presence: PresenceState?,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                stringResource(R.string.nick_profile_heading),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            NickProfileSummary(profile, profileColor, homepage)
        }
    }
    val expansionState = stringResource(if (expanded) R.string.new_sheet_expanded else R.string.new_sheet_collapsed)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("nick_profile_irc_details")
                .semantics { stateDescription = expansionState }
                .clickable(role = Role.Button) { expanded = !expanded }
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.nick_profile_irc_details),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
    }
    if (expanded) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            WhoisSummary(whois, presence)
        }
    }
}

@Composable
private fun NickProfileSummary(
    profile: Map<ProfileKey, String>,
    profileColor: Color?,
    link: String?,
) {
    profile[ProfileKey.STATUS]?.takeIf { it.isNotBlank() }?.let {
        NickProfileField(R.string.profile_editor_status, it, "nick_profile_status")
    }
    profile[ProfileKey.PRONOUNS]?.takeIf { it.isNotBlank() }?.let {
        NickProfileField(R.string.profile_editor_pronouns, it, "nick_profile_pronouns")
    }
    profile[ProfileKey.BOT]?.takeIf { it.isNotBlank() }?.let {
        NickProfileField(R.string.profile_editor_bot, it, "nick_profile_bot")
    }
    if (profileColor != null) {
        NickProfileField(R.string.profile_editor_color, profile[ProfileKey.COLOR].orEmpty(), "nick_profile_color", swatch = profileColor)
    }
    if (link != null) {
        val context = LocalContext.current
        NickProfileField(R.string.profile_editor_homepage, link, "nick_profile_homepage") { context.openChatLinkInBrowser(link) }
    }
}

@Composable
private fun NickProfileField(
    label: Int,
    value: String,
    tag: String,
    swatch: Color? = null,
    onOpen: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = scheme.surfaceContainerHighest
    val action =
        if (onOpen == null) {
            Modifier.semantics(mergeDescendants = true) {}
        } else {
            Modifier
                .clickable(onClickLabel = stringResource(R.string.chat_link_open_browser), role = Role.Button, onClick = onOpen)
                .heightIn(min = 48.dp)
                .padding(vertical = 12.dp)
        }
    val valueColor = if (onOpen == null) Color.Unspecified else scheme.readableForeground(scheme.primary, fill)
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag).then(action),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(label),
            color = scheme.readableForeground(scheme.onSurfaceVariant, fill),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
        Row(
            modifier = Modifier.weight(2f).alignByBaseline(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (swatch != null) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(swatch, CircleShape)
                        .border(1.dp, scheme.outline, CircleShape),
                )
            }
            Text(value, color = valueColor, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (onOpen != null) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp), tint = valueColor)
            }
        }
    }
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
    enabled: Boolean = true,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors =
                androidx.compose.material3.ListItemDefaults
                    .colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            headlineContent = { Text(label) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary, minimumContrast = 3.0)) },
            modifier = Modifier.then(tag?.let(Modifier::testTag) ?: Modifier).clickable(enabled = enabled, onClick = onClick),
        )
    }
}
