package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.MentionBadge
import io.github.trevarj.motd.ui.components.MutedActivityBadge
import io.github.trevarj.motd.ui.components.NetworkChip
import io.github.trevarj.motd.ui.components.UnreadBadge
import io.github.trevarj.motd.ui.components.isAppliedThemeDark
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.presenceOnlineColor

internal data class ChatListBadgeState(
    val mutedActivity: Int? = null,
    val mentions: Int? = null,
    val unread: Int? = null,
)

internal fun chatListBadgeState(row: ChatListRow): ChatListBadgeState =
    if (row.muted) {
        ChatListBadgeState(mutedActivity = row.unreadCount.takeIf { it > 0 })
    } else {
        ChatListBadgeState(
            mentions = row.mentionCount.takeIf { it > 0 },
            unread = row.unreadCount.takeIf { it > 0 },
        )
    }

/**
 * One chat-list row: avatar, display name (+ network chip when multi-network), last-message
 * one-liner, relative time, unread/mention badges. Muted rows render dimmed with a bell-off glyph.
 * Pinned rows carry a small inline [Icons.Outlined.PushPin] beside the name (there is no separate
 * "Pinned" section; pinning gives the row global list priority).
 *
 * Round 4 (plans/13 §3.5, Confirmed decision #4): a friend row gets a trailing [Icons.Filled.Star]
 * plus a subtle primary-tinted rounded background behind the display name (theme-aware, layered
 * under the nick color).
 */
@Composable
fun ChatListRowItem(
    row: ChatListRow,
    showNetworkChip: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    isFriend: Boolean = false,
    presence: PresenceState? = null,
) {
    // Resolved per-nick color (also used to tint the friend star), matching sender coloring.
    val nickColor = LocalNickColors.current.nick(row.displayName, MaterialTheme.colorScheme.onSurfaceVariant)
    val queryPresence = presence.takeIf { row.type == BufferType.QUERY }
    val badges = chatListBadgeState(row)
    val presenceDescription = queryPresence?.let {
        stringResource(
            when (it) {
                PresenceState.ONLINE -> R.string.presence_online
                PresenceState.OFFLINE -> R.string.presence_offline
                PresenceState.UNKNOWN -> R.string.presence_unknown
            },
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Per-buffer handle so the harness selects a specific row (display names collide).
            .testTag("chatlist_row_${row.bufferId}")
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .then(
                if (presenceDescription != null) {
                    Modifier.semantics { stateDescription = presenceDescription }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (row.muted) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PresenceAvatar(
            name = row.displayName,
            isChannel = row.type == BufferType.CHANNEL,
            networkId = row.networkId,
            presence = queryPresence,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Subtle theme-aware primary tint behind a friend's name (Confirmed decision #4).
                val nameModifier = if (isFriend) {
                    Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                } else {
                    Modifier.weight(1f, fill = false)
                }
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isFriend) nickColor else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = nameModifier,
                )
                if (isFriend) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(14.dp),
                        tint = nickColor,
                    )
                }
                if (row.pinned) {
                    // Inline pin marker (replaces the former "Pinned" section); subtle, unread-neutral.
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = stringResource(R.string.chatlist_pinned),
                        modifier = Modifier
                            .testTag("chatlist_row_pin")
                            .padding(start = 4.dp)
                            .size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.muted) {
                    Icon(
                        imageVector = Icons.Filled.NotificationsOff,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (showNetworkChip) {
                    Spacer(Modifier.width(6.dp))
                    NetworkChip(name = row.networkName)
                }
            }
            Spacer(Modifier.size(2.dp))
            Text(
                text = lastMessageLine(row),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            row.lastMessageTime?.let { time ->
                Text(
                    text = relativeChatTime(time),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                badges.mutedActivity?.let { count ->
                    MutedActivityBadge(
                        count = count,
                        modifier = Modifier.testTag("chatlist_row_muted_activity_badge"),
                    )
                }
                badges.mentions?.let { count ->
                    MentionBadge(count = count, modifier = Modifier.testTag("chatlist_row_mention_badge"))
                }
                badges.unread?.let { count ->
                    UnreadBadge(count = count, modifier = Modifier.testTag("chatlist_row_unread_badge"))
                }
            }
        }
    }
}

internal enum class PresenceBadgeVisual { FILLED, HOLLOW, UNKNOWN }

internal fun presenceBadgeVisual(presence: PresenceState): PresenceBadgeVisual = when (presence) {
    PresenceState.ONLINE -> PresenceBadgeVisual.FILLED
    PresenceState.OFFLINE -> PresenceBadgeVisual.HOLLOW
    PresenceState.UNKNOWN -> PresenceBadgeVisual.UNKNOWN
}

@Composable
private fun PresenceAvatar(
    name: String,
    isChannel: Boolean,
    networkId: Long,
    presence: PresenceState?,
) {
    Box(modifier = Modifier.size(44.dp)) {
        Avatar(
            name = name,
            isChannel = isChannel,
            networkId = networkId,
            modifier = Modifier.align(Alignment.Center),
        )
        presence?.let { state ->
            PresenceBadge(
                visual = presenceBadgeVisual(state),
                tag = "chatlist_presence_${state.name.lowercase()}",
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

@Composable
private fun PresenceBadge(
    visual: PresenceBadgeVisual,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val online = presenceOnlineColor(isAppliedThemeDark())
    Box(
        modifier = modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(scheme.background)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        when (visual) {
            PresenceBadgeVisual.FILLED -> Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(online)
                    .clearAndSetSemantics {},
            )
            PresenceBadgeVisual.HOLLOW -> Box(
                Modifier
                    .size(10.dp)
                    .border(2.dp, scheme.onSurfaceVariant, CircleShape)
                    .clearAndSetSemantics {},
            )
            PresenceBadgeVisual.UNKNOWN -> Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "?",
                    color = scheme.onSurfaceVariant,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 8.sp,
                )
            }
        }
    }
}

/** "sender: text" one-liner; falls back to plain text (queries) or empty. */
private fun lastMessageLine(row: ChatListRow): String {
    val text = row.lastMessageText ?: return ""
    // In channels, prefix with the sender; queries read cleaner without it.
    return if (row.type == BufferType.CHANNEL && row.lastMessageSender != null) {
        "${row.lastMessageSender}: $text"
    } else {
        text
    }
}

@PreviewLightDark
@Composable
private fun ChatListPresencePreview() {
    MotdTheme {
        Column {
            ChatListRowItem(
                row = ChatListRow(
                    bufferId = 1, networkId = 1, networkName = "Libera",
                    displayName = "alice", type = BufferType.QUERY,
                    pinned = true, muted = false,
                    lastMessageText = "I am around", lastMessageSender = "alice",
                    lastMessageTime = System.currentTimeMillis() - 120_000,
                    unreadCount = 12, mentionCount = 2,
                ),
                showNetworkChip = true,
                onClick = {}, onLongClick = {},
                isFriend = true,
                presence = PresenceState.ONLINE,
            )
            ChatListRowItem(
                row = ChatListRow(
                    bufferId = 2, networkId = 1, networkName = "Libera",
                    displayName = "bob", type = BufferType.QUERY,
                    pinned = false, muted = false,
                    lastMessageText = "see you later", lastMessageSender = "bob",
                    lastMessageTime = System.currentTimeMillis() - 3_600_000 * 26,
                    unreadCount = 0, mentionCount = 0,
                ),
                showNetworkChip = false,
                onClick = {}, onLongClick = {},
                presence = PresenceState.OFFLINE,
            )
            ChatListRowItem(
                row = ChatListRow(
                    bufferId = 3, networkId = 1, networkName = "Libera",
                    displayName = "carol", type = BufferType.QUERY,
                    pinned = false, muted = false,
                    lastMessageText = "reconnecting", lastMessageSender = "carol",
                    lastMessageTime = System.currentTimeMillis() - 60_000,
                    unreadCount = 0, mentionCount = 0,
                ),
                showNetworkChip = false,
                onClick = {}, onLongClick = {},
                presence = PresenceState.UNKNOWN,
            )
        }
    }
}
