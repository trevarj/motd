package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.MentionBadge
import io.github.trevarj.motd.ui.components.NetworkChip
import io.github.trevarj.motd.ui.components.UnreadBadge
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.service.PresenceState

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
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Per-buffer handle so the harness selects a specific row (display names collide).
            .testTag("chatlist_row_${row.bufferId}")
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (row.muted) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name = row.displayName,
            isChannel = row.type == BufferType.CHANNEL,
            networkId = row.networkId,
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
                if (presence != null && row.type == BufferType.QUERY) {
                    val description = stringResource(
                        when (presence) {
                            PresenceState.ONLINE -> R.string.presence_online
                            PresenceState.OFFLINE -> R.string.presence_offline
                            PresenceState.UNKNOWN -> R.string.presence_unknown
                        },
                    )
                    Text(
                        text = "●",
                        color = when (presence) {
                            PresenceState.ONLINE -> MaterialTheme.colorScheme.primary
                            PresenceState.OFFLINE -> MaterialTheme.colorScheme.error
                            PresenceState.UNKNOWN -> MaterialTheme.colorScheme.outline
                        },
                        modifier = Modifier
                            .padding(start = 5.dp)
                            .testTag("chatlist_presence_${presence.name.lowercase()}")
                            .semantics { contentDescription = description },
                    )
                }
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
                if (row.mentionCount > 0) {
                    MentionBadge(count = row.mentionCount, modifier = Modifier.testTag("chatlist_row_mention_badge"))
                }
                if (row.unreadCount > 0) {
                    UnreadBadge(count = row.unreadCount, modifier = Modifier.testTag("chatlist_row_unread_badge"))
                }
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

@Preview
@Composable
private fun ChatListRowItemPreview() {
    MotdTheme {
        Column {
            ChatListRowItem(
                row = ChatListRow(
                    bufferId = 1, networkId = 1, networkName = "Libera",
                    displayName = "#libera", type = BufferType.CHANNEL,
                    pinned = true, muted = false,
                    lastMessageText = "welcome to the channel", lastMessageSender = "alice",
                    lastMessageTime = System.currentTimeMillis() - 120_000,
                    unreadCount = 12, mentionCount = 2,
                ),
                showNetworkChip = true,
                onClick = {}, onLongClick = {},
            )
            ChatListRowItem(
                row = ChatListRow(
                    bufferId = 2, networkId = 1, networkName = "Libera",
                    displayName = "bob", type = BufferType.QUERY,
                    pinned = false, muted = true,
                    lastMessageText = "see you later", lastMessageSender = "bob",
                    lastMessageTime = System.currentTimeMillis() - 3_600_000 * 26,
                    unreadCount = 0, mentionCount = 0,
                ),
                showNetworkChip = false,
                onClick = {}, onLongClick = {},
            )
        }
    }
}
