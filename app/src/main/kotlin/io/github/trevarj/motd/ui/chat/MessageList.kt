package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.NewMessagesDivider
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.components.SystemEventPill
import io.github.trevarj.motd.ui.components.DaySeparator
import io.github.trevarj.motd.ui.components.dayLabel
import io.github.trevarj.motd.ui.components.dayStart

/** System-event message kinds rendered as pills rather than bubbles. */
private val SYSTEM_KINDS = setOf(
    MessageKind.JOIN, MessageKind.PART, MessageKind.QUIT, MessageKind.KICK,
    MessageKind.NICK, MessageKind.MODE, MessageKind.TOPIC, MessageKind.SERVER_INFO,
    MessageKind.ERROR,
)

fun isSystemKind(kind: MessageKind): Boolean = kind in SYSTEM_KINDS

/**
 * True when [current] should show its sender header: it opens a new same-sender ≤3-min group.
 * [olderNeighbor] is the message immediately older in time (index+1 in a reversed list).
 */
fun showsSender(current: MessageEntity, olderNeighbor: MessageEntity?): Boolean {
    if (olderNeighbor == null) return true
    if (olderNeighbor.sender != current.sender) return true
    if (isSystemKind(olderNeighbor.kind) != isSystemKind(current.kind)) return true
    return current.serverTime - olderNeighbor.serverTime > GROUP_WINDOW_MS
}

/**
 * Reverse-layout message list. Index 0 is the newest message (bottom). For each row we peek the
 * next (older) item to compute grouping, day separators, and the read-marker divider.
 */
@Composable
fun MessageList(
    items: LazyPagingItems<MessageEntity>,
    listState: LazyListState,
    readMarkerTime: Long?,
    onLongPress: (MessageEntity) -> Unit,
    onReact: (String, String) -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    loadPreview: suspend (String) -> LinkPreview?,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    reactionChips: (String) -> List<ReactionChip> = { emptyList() },
) {
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(count = items.itemCount) { index ->
            val msg = items[index] ?: return@items
            val older = if (index + 1 < items.itemCount) items.peek(index + 1) else null

            MessageRow(
                msg = msg,
                older = older,
                readMarkerTime = readMarkerTime,
                reactions = msg.msgid?.let(reactionChips).orEmpty(),
                onLongPress = onLongPress,
                onReact = onReact,
                onImageClick = onImageClick,
                onRetry = onRetry,
                loadPreview = loadPreview,
                onOpenLink = onOpenLink,
                resolveReply = { msgid ->
                    // Resolve reply target within the loaded window only (plans/07).
                    (0 until items.itemCount)
                        .asSequence()
                        .mapNotNull { items.peek(it) }
                        .firstOrNull { it.msgid == msgid }
                        ?.let { ReplyPreviewData(it.sender, it.text) }
                },
            )
        }
    }
}

@Composable
private fun MessageRow(
    msg: MessageEntity,
    older: MessageEntity?,
    readMarkerTime: Long?,
    reactions: List<ReactionChip>,
    onLongPress: (MessageEntity) -> Unit,
    onReact: (String, String) -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    loadPreview: suspend (String) -> LinkPreview?,
    onOpenLink: (String) -> Unit,
    resolveReply: (String) -> ReplyPreviewData?,
) {
    // Read-marker divider sits below the first message newer than the marker (drawn after the
    // bubble because the list is reversed => "above" the newer message visually).
    val showNewDivider = readMarkerTime != null &&
        msg.serverTime > readMarkerTime &&
        (older == null || older.serverTime <= readMarkerTime)

    // Day separator when this message starts a new day relative to the older neighbor.
    val showDay = older == null || dayStart(msg.serverTime) != dayStart(older.serverTime)

    if (isSystemKind(msg.kind)) {
        SystemEventPill(summary = msg.text, lines = listOf(msg.text))
    } else {
        val reply = msg.replyToMsgid?.let(resolveReply)
        val imageUrl = firstImageUrl(msg.text)
        val linkUrl = firstLinkUrl(msg.text)

        // Lazily fetch a link preview for the first non-image URL.
        val preview by produceState<LinkPreview?>(initialValue = null, linkUrl) {
            value = linkUrl?.let { runCatching { loadPreview(it) }.getOrNull() }
        }
        val previewLoading = linkUrl != null && preview == null

        MessageBubble(
            sender = msg.sender,
            text = msg.text,
            timeMs = msg.serverTime,
            isSelf = msg.isSelf,
            kind = msg.kind,
            showSender = showsSender(msg, older),
            failed = msg.failed,
            reply = reply,
            imageUrl = imageUrl,
            linkPreview = preview,
            linkPreviewLoading = previewLoading,
            reactions = reactions,
            onLongPress = { onLongPress(msg) },
            onReact = { emoji -> msg.msgid?.let { onReact(it, emoji) } },
            onImageClick = onImageClick,
            onLinkPreviewClick = { linkUrl?.let(onOpenLink) },
        )
        if (msg.failed) {
            RetryRow(onRetry = { onRetry(msg) })
        }
    }

    if (showNewDivider) NewMessagesDivider(label = "— New messages —")
    if (showDay) DaySeparator(label = dayLabel(msg.serverTime))
}

/** Right-aligned "Tap to retry" affordance under a failed own-message bubble. */
@Composable
private fun RetryRow(onRetry: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRetry() }
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            tint = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.size(14.dp),
        )
        androidx.compose.material3.Text(
            text = "Tap to retry",
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
