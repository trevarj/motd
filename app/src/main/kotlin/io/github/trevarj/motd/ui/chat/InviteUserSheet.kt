package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GroupAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.normalizedInviteNick
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.avatarsHidden
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

sealed interface InviteSheetTarget {
    data class Nick(
        val nick: String,
        val excludedBufferId: Long? = null,
    ) : InviteSheetTarget

    data class Channel(
        val channel: JoinedChannelRow,
    ) : InviteSheetTarget
}

/** Shared outgoing IRC INVITE picker for both nick-first and channel-first entry points. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InviteUserSheet(
    target: InviteSheetTarget,
    joinedChannels: List<JoinedChannelRow>,
    friends: Set<String>,
    presence: Map<PresenceKey, PresenceState>,
    memberNicks: List<String>,
    selfNick: String?,
    identityRules: IrcIdentityRules,
    connected: Boolean,
    onDismiss: () -> Unit,
    onInvite: (channel: JoinedChannelRow, nick: String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("invite_user_sheet"),
    ) {
        SheetSystemBars()
        Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
            when (target) {
                is InviteSheetTarget.Nick -> {
                    ChannelDestinationPicker(
                        nick = target.nick,
                        channels = joinedChannels.filterNot { it.bufferId == target.excludedBufferId },
                        connected = connected,
                        onInvite = onInvite,
                    )
                }

                is InviteSheetTarget.Channel -> {
                    NickDestinationPicker(
                        channel = target.channel,
                        friends = friends,
                        presence = presence,
                        memberNicks = memberNicks,
                        selfNick = selfNick,
                        identityRules = identityRules,
                        connected = connected,
                        onInvite = onInvite,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelDestinationPicker(
    nick: String,
    channels: List<JoinedChannelRow>,
    connected: Boolean,
    onInvite: (JoinedChannelRow, String) -> Unit,
) {
    SheetHeader(
        name = nick,
        networkId = channels.firstOrNull()?.networkId,
        title = stringResource(R.string.irc_invite_nick_title, nick),
        subtitle = stringResource(R.string.irc_invite_nick_subtitle),
    )
    if (!connected) InviteUnavailableText(stringResource(R.string.irc_invite_disconnected))
    if (channels.isEmpty()) {
        InviteUnavailableText(stringResource(R.string.irc_invite_no_other_channels), "invite_no_channels")
    } else {
        LazyColumn(modifier = Modifier.heightIn(max = 420.dp).padding(bottom = 16.dp)) {
            items(channels, key = JoinedChannelRow::bufferId) { channel ->
                val label = stringResource(R.string.irc_invite_channel_action, nick, channel.displayName)
                ListItem(
                    headlineContent = {
                        Text(
                            channel.displayName,
                            color = LocalNickColors.current.nick(channel.displayName, MaterialTheme.colorScheme.onSurface),
                            fontWeight = FontWeight.Medium,
                        )
                    },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (connected) R.string.irc_invite_tap_channel else R.string.irc_invite_disconnected,
                            ),
                        )
                    },
                    leadingContent =
                        if (avatarsHidden()) {
                            null
                        } else {
                            {
                                Avatar(
                                    name = channel.displayName,
                                    isChannel = true,
                                    networkId = channel.networkId,
                                    conversationModel = channel.avatarOverrideModel,
                                    size = 40.dp,
                                )
                            }
                        },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .testTag("invite_channel_${channel.bufferId}")
                            .clickable(enabled = connected, onClickLabel = label) { onInvite(channel, nick) },
                )
            }
        }
    }
}

@Composable
private fun NickDestinationPicker(
    channel: JoinedChannelRow,
    friends: Set<String>,
    presence: Map<PresenceKey, PresenceState>,
    memberNicks: List<String>,
    selfNick: String?,
    identityRules: IrcIdentityRules,
    connected: Boolean,
    onInvite: (JoinedChannelRow, String) -> Unit,
) {
    var nick by rememberSaveable(channel.bufferId) { mutableStateOf("") }
    val targetNick = normalizedInviteNick(nick)
    val present =
        remember(memberNicks, selfNick, identityRules) {
            (memberNicks + listOfNotNull(selfNick)).mapTo(hashSetOf(), identityRules::normalize)
        }
    val alreadyPresent = targetNick?.let { identityRules.normalize(it) in present } == true
    val canInvite = connected && targetNick != null && !alreadyPresent
    val visibleFriends =
        remember(friends, presence, nick, channel.networkId, identityRules) {
            friends
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinctBy(identityRules::normalize)
                .filter { candidate -> nick.isBlank() || identityRules.normalize(candidate).contains(identityRules.normalize(nick.trim())) }
                .sortedWith(
                    compareByDescending<String> {
                        presence[PresenceKey(channel.networkId, identityRules.normalize(it))] == PresenceState.ONLINE
                    }.thenBy(identityRules::normalize),
                ).toList()
        }

    SheetHeader(
        name = channel.displayName,
        networkId = channel.networkId,
        isChannel = true,
        conversationModel = channel.avatarOverrideModel,
        title = stringResource(R.string.irc_invite_user_title),
        subtitle = channel.displayName,
    )
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = nick,
            onValueChange = { nick = it },
            label = { Text(stringResource(R.string.irc_invite_nick_label)) },
            supportingText = {
                when {
                    !connected -> Text(stringResource(R.string.irc_invite_disconnected))
                    alreadyPresent -> Text(stringResource(R.string.irc_invite_already_here))
                    nick.isNotBlank() && targetNick == null -> Text(stringResource(R.string.irc_invite_invalid_nick))
                    else -> Text(stringResource(R.string.irc_invite_nick_help))
                }
            },
            isError = alreadyPresent || (nick.isNotBlank() && targetNick == null),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canInvite) onInvite(channel, targetNick) }),
            modifier = Modifier.fillMaxWidth().testTag("invite_nick_input"),
        )
        Button(
            onClick = { targetNick?.let { onInvite(channel, it) } },
            enabled = canInvite,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("invite_nick_confirm"),
        ) {
            Icon(Icons.Outlined.GroupAdd, contentDescription = null)
            Text(stringResource(R.string.irc_invite_action), modifier = Modifier.padding(start = 8.dp))
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.irc_invite_friends),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }

    if (friends.isEmpty()) {
        InviteUnavailableText(stringResource(R.string.irc_invite_no_friends), "invite_no_friends")
    } else if (visibleFriends.isNotEmpty()) {
        LazyColumn(modifier = Modifier.heightIn(max = 300.dp).padding(bottom = 16.dp)) {
            items(visibleFriends, key = identityRules::normalize) { friend ->
                val friendPresent = identityRules.normalize(friend) in present
                val friendPresence = presence[PresenceKey(channel.networkId, identityRules.normalize(friend))]
                FriendInviteRow(
                    nick = friend,
                    networkId = channel.networkId,
                    presence = friendPresence,
                    alreadyPresent = friendPresent,
                    enabled = connected && !friendPresent,
                    onClick = { onInvite(channel, friend) },
                )
            }
        }
    }
}

@Composable
private fun FriendInviteRow(
    nick: String,
    networkId: Long,
    presence: PresenceState?,
    alreadyPresent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val status =
        when {
            alreadyPresent -> stringResource(R.string.irc_invite_already_here)
            presence == PresenceState.ONLINE -> stringResource(R.string.presence_online)
            presence == PresenceState.OFFLINE -> stringResource(R.string.presence_offline)
            else -> stringResource(R.string.presence_unknown)
        }
    ListItem(
        headlineContent = {
            Text(
                nick,
                color = LocalNickColors.current.nick(nick, MaterialTheme.colorScheme.onSurface),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(status) },
        leadingContent =
            if (avatarsHidden()) {
                null
            } else {
                { Avatar(name = nick, networkId = networkId, size = 40.dp) }
            },
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .testTag("invite_friend_$nick")
                .clickable(
                    enabled = enabled,
                    onClickLabel = stringResource(R.string.irc_invite_friend_action, nick),
                    onClick = onClick,
                ),
    )
}

@Composable
private fun SheetHeader(
    name: String,
    networkId: Long?,
    title: String,
    subtitle: String,
    isChannel: Boolean = false,
    conversationModel: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!avatarsHidden()) {
            Avatar(
                name = name,
                networkId = networkId,
                isChannel = isChannel,
                conversationModel = conversationModel,
                size = 48.dp,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InviteUnavailableText(
    text: String,
    tag: String = "invite_disconnected",
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp).testTag(tag),
    )
}
