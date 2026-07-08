package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.MentionBadge
import io.github.trevarj.motd.ui.components.NetworkChip
import io.github.trevarj.motd.ui.components.UnreadBadge
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * One chat-list row: avatar, display name (+ network chip when multi-network), last-message
 * one-liner, relative time, unread/mention badges. Muted rows render dimmed with a bell-off glyph.
 */
@Composable
fun ChatListRowItem(
    row: ChatListRow,
    showNetworkChip: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .alpha(if (row.muted) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name = row.displayName,
            isChannel = row.type == BufferType.CHANNEL,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
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
                if (row.mentionCount > 0) MentionBadge(count = row.mentionCount)
                if (row.unreadCount > 0) UnreadBadge(count = row.unreadCount)
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
