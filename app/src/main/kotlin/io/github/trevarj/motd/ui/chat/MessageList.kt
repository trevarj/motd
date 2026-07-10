package io.github.trevarj.motd.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.NewMessagesDivider
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.components.SystemEventPill
import io.github.trevarj.motd.ui.components.DaySeparator
import io.github.trevarj.motd.ui.components.dayStart

/** System-event message kinds rendered as pills rather than bubbles. */
private val SYSTEM_KINDS = setOf(
    MessageKind.JOIN, MessageKind.PART, MessageKind.QUIT, MessageKind.KICK,
    MessageKind.NICK, MessageKind.MODE, MessageKind.TOPIC, MessageKind.SERVER_INFO,
    MessageKind.ERROR,
)

fun isSystemKind(kind: MessageKind): Boolean = kind in SYSTEM_KINDS

/** Stable per-message testTag id: server msgid when present, else the local entity id (pending). */
private fun messageTag(msg: MessageEntity): String = "chat_message_${msg.msgid ?: msg.id}"

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
    onDelete: (MessageEntity) -> Unit = {},
    highlightMsgid: String? = null,
    // Normalized nicks known in the current buffer (member list). Drives @mention coloring in the
    // message bodies (plans/17); passed straight through to each MessageBubble.
    knownNicks: Set<String> = emptySet(),
    // Behavioral settings threaded from viewModel.settings (plans/13 §2.3/§2.4). Style-only
    // concerns (density, nick color) flow through CompositionLocals instead.
    friends: Set<String> = emptySet(),
    fools: Set<String> = emptySet(),
    foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    // Effective per-row expansion (global expand-all + per-row overrides live in the caller); toggle
    // flips a single fool row either way so expand/re-collapse is bidirectional (bug #9).
    foolExpanded: (Long) -> Boolean = { false },
    onToggleFool: (Long) -> Unit = {},
    // Tapping a non-self sender's name/avatar opens the nick sheet (plans/16 §5.8).
    onSenderClick: (String) -> Unit = {},
) {
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // Stable keys stop paging invalidations (new message / echo confirm / page load) from
        // re-anchoring the viewport by index and reusing per-row state across messages (plans/15
        // #4). Placeholder rows fall back to the position key.
        items(
            count = items.itemCount,
            key = items.itemKey { it.id },
            contentType = items.itemContentType { if (isSystemKind(it.kind)) "system" else "msg" },
        ) { index ->
            val msg = items[index] ?: return@items
            val older = if (index + 1 < items.itemCount) items.peek(index + 1) else null
            val newer = if (index - 1 >= 0) items.peek(index - 1) else null

            // System-event collapse (plans/15 #15): render one pill on the run's *newest* item and
            // skip the rest. In a reversed list the newest of a contiguous system run is the item
            // whose just-newer neighbor is not a system event.
            if (isSystemKind(msg.kind)) {
                if (newer != null && isSystemKind(newer.kind)) return@items // absorbed by a newer pill
                SystemEventRun(items = items, index = index, newest = msg, readMarkerTime = readMarkerTime)
                return@items
            }

            // Fool COLLAPSE (plans/13 §2.4): render a tap-to-expand placeholder in place of the
            // bubble until its id is expanded. HIDE mode is filtered upstream so it never reaches
            // here; system-kind rows are handled above and never fool-treated.
            val isFool = foolsMode == FoolsMode.COLLAPSE && isFoolSender(msg.sender, msg.isSelf, fools)
            if (isFool && !foolExpanded(msg.id)) {
                FoolPlaceholderRow(
                    msg = msg,
                    older = older,
                    readMarkerTime = readMarkerTime,
                    onExpand = { onToggleFool(msg.id) },
                )
                return@items
            }

            // Deep-jump pulse: fade a highlight tint in then back out on the target row (~1.6s).
            val highlighted = highlightMsgid != null && msg.msgid == highlightMsgid
            val highlightColor by animateColorAsState(
                targetValue = if (highlighted) {
                    androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    Color.Transparent
                },
                animationSpec = tween(durationMillis = 800),
                label = "jumpHighlight",
            )

            // Column (not Box): MessageRow emits several vertical siblings — the collapse chip,
            // bubble, retry row, and read-marker/day dividers. A Box would stack them on top of one
            // another (dividers over message text; the fool-collapse chip trapped behind the bubble).
            // A Column lays them out top-to-bottom so each affordance owns its own space and taps.
            Column(modifier = Modifier.fillMaxWidth().background(highlightColor)) {
            MessageRow(
                msg = msg,
                older = older,
                readMarkerTime = readMarkerTime,
                // An expanded fool row shows a small tap-to-re-collapse chip above its bubble so the
                // toggle is bidirectional without stealing the bubble's long-press/link taps (#9).
                onCollapseFool = if (isFool) ({ onToggleFool(msg.id) }) else null,
                senderIsFriend = !msg.isSelf && normalizeNick(msg.sender) in friends,
                reactions = msg.msgid?.let(reactionChips).orEmpty(),
                knownNicks = knownNicks,
                onLongPress = onLongPress,
                onReact = onReact,
                onImageClick = onImageClick,
                onRetry = onRetry,
                onDelete = onDelete,
                loadPreview = loadPreview,
                onOpenLink = onOpenLink,
                onSenderClick = onSenderClick,
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

        // Append spinner / end-of-history / error affordances (plans/15 #27). This item sits at the
        // top of the reversed list, i.e. visually above the oldest message where APPEND loads more.
        item(key = "append-state", contentType = "loadstate") {
            LoadStateFooter(items.loadState.append)
        }
    }
}

/**
 * Render a collapsed run of consecutive system events whose newest item is at [index]. Walks older
 * neighbors while they stay contiguous system events, summarizing counts by kind ("3 joined · 1
 * left"); the [SystemEventPill] expands to the per-event lines on tap (plans/15 #15). The
 * read-marker/day separators are computed against the oldest item of the run and the neighbor just
 * older than the whole run, matching the reversed-list boundary rules used for bubbles.
 */
@Composable
private fun SystemEventRun(
    items: LazyPagingItems<MessageEntity>,
    index: Int,
    newest: MessageEntity,
    readMarkerTime: Long?,
) {
    // Gather the run: newest first (index), then older neighbors while still system events.
    val run = ArrayList<MessageEntity>()
    run.add(newest)
    var i = index + 1
    while (i < items.itemCount) {
        val m = items.peek(i) ?: break
        if (!isSystemKind(m.kind)) break
        run.add(m)
        i++
    }
    val oldest = run.last()
    val olderThanRun = if (index + run.size < items.itemCount) items.peek(index + run.size) else null

    val lines = run.map { it.text }
    val summary = if (run.size == 1) newest.text else summarizeSystemRun(run)

    // Divider below the run when the run's newest crosses the marker and its older neighbor doesn't.
    val showNewDivider = readMarkerTime != null &&
        newest.serverTime > readMarkerTime &&
        (olderThanRun == null || olderThanRun.serverTime <= readMarkerTime)
    val showDay = olderThanRun == null || dayStart(oldest.serverTime) != dayStart(olderThanRun.serverTime)

    // Column so the pill and any dividers stack vertically. A bare item slot stacks siblings on top
    // of each other (its MeasurePolicy behaves like a Box), which would overlap the divider text.
    Column(modifier = Modifier.fillMaxWidth()) {
        SystemEventPill(summary = summary, lines = lines, modifier = Modifier.testTag("chat_system_pill"))
        if (showNewDivider) {
            NewMessagesDivider(
                label = stringResource(R.string.chat_new_messages),
                modifier = Modifier.testTag("chat_read_marker_divider"),
            )
        }
        if (showDay) DaySeparator(timeMs = oldest.serverTime)
    }
}

/**
 * Summarize a run of system events by kind: JOIN → "joined", PART/QUIT → "left", others by kind
 * name. Produces "3 joined · 1 left" style text. Counts are grouped preserving first appearance.
 */
private fun summarizeSystemRun(run: List<MessageEntity>): String {
    val counts = LinkedHashMap<String, Int>()
    for (m in run) {
        val label = when (m.kind) {
            MessageKind.JOIN -> "joined"
            MessageKind.PART, MessageKind.QUIT -> "left"
            MessageKind.KICK -> "kicked"
            MessageKind.NICK -> "renamed"
            MessageKind.MODE -> "mode"
            MessageKind.TOPIC -> "topic"
            else -> "events"
        }
        counts[label] = (counts[label] ?: 0) + 1
    }
    return counts.entries.joinToString(" · ") { (label, n) -> "$n $label" }
}

/** Completion-tracked link-preview state so a failed/null fetch stops the shimmer (plans/15 #3). */
private sealed interface PreviewState {
    data object Loading : PreviewState
    data class Done(val preview: LinkPreview?) : PreviewState
}

@Composable
private fun MessageRow(
    msg: MessageEntity,
    older: MessageEntity?,
    readMarkerTime: Long?,
    senderIsFriend: Boolean,
    reactions: List<ReactionChip>,
    knownNicks: Set<String>,
    onLongPress: (MessageEntity) -> Unit,
    onReact: (String, String) -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    onDelete: (MessageEntity) -> Unit,
    loadPreview: suspend (String) -> LinkPreview?,
    onOpenLink: (String) -> Unit,
    onSenderClick: (String) -> Unit,
    resolveReply: (String) -> ReplyPreviewData?,
    // Non-null for an expanded fool row: renders a "hide" chip above the bubble that re-collapses it.
    onCollapseFool: (() -> Unit)? = null,
) {
    // Read-marker divider sits below the first message newer than the marker (drawn after the
    // bubble because the list is reversed => "above" the newer message visually).
    val showNewDivider = readMarkerTime != null &&
        msg.serverTime > readMarkerTime &&
        (older == null || older.serverTime <= readMarkerTime)

    // Day separator when this message starts a new day relative to the older neighbor.
    val showDay = older == null || dayStart(msg.serverTime) != dayStart(older.serverTime)

    val reply = msg.replyToMsgid?.let(resolveReply)
    val imageUrl = firstImageUrl(msg.text)
    val linkUrl = firstLinkUrl(msg.text)

    // Lazily fetch a link preview for the first non-image URL. produceState carries a completion
    // flag so a null result (fetch failed / not HTML) resolves to Done(null) and stops the
    // skeleton shimmer instead of shimmering forever (plans/15 #3).
    val previewState by produceState<PreviewState>(PreviewState.Loading, linkUrl) {
        value = if (linkUrl == null) {
            PreviewState.Done(null)
        } else {
            PreviewState.Done(runCatching { loadPreview(linkUrl) }.getOrNull())
        }
    }
    val preview = (previewState as? PreviewState.Done)?.preview
    val previewLoading = linkUrl != null && previewState is PreviewState.Loading

    onCollapseFool?.let { FoolCollapseChip(sender = msg.sender, onCollapse = it) }

    MessageBubble(
        // Per-message handle for long-press/react/reply/deep-jump. Prefer the stable server msgid;
        // pending rows (null msgid) fall back to the local entity id (plans/18 §4).
        modifier = Modifier.testTag(messageTag(msg)),
        sender = msg.sender,
        text = msg.text,
        timeMs = msg.serverTime,
        isSelf = msg.isSelf,
        kind = msg.kind,
        showSender = showsSender(msg, older),
        senderIsFriend = senderIsFriend,
        failed = msg.failed,
        // Subtle "sending…" state before the 30s failure flip (plans/15 #21).
        pending = msg.pendingLabel != null,
        reply = reply,
        imageUrl = imageUrl,
        linkPreview = preview,
        linkPreviewLoading = previewLoading,
        reactions = reactions,
        knownNicks = knownNicks,
        onLongPress = { onLongPress(msg) },
        onReact = { emoji -> msg.msgid?.let { onReact(it, emoji) } },
        onImageClick = onImageClick,
        onLinkPreviewClick = { linkUrl?.let(onOpenLink) },
        // Only non-self senders open the nick sheet (self has no social/moderation actions).
        onSenderClick = if (msg.isSelf) null else ({ onSenderClick(msg.sender) }),
    )
    if (msg.failed) {
        RetryRow(onRetry = { onRetry(msg) }, onDelete = { onDelete(msg) })
    }

    if (showNewDivider) {
        NewMessagesDivider(
            label = stringResource(R.string.chat_new_messages),
            modifier = Modifier.testTag("chat_read_marker_divider"),
        )
    }
    if (showDay) DaySeparator(timeMs = msg.serverTime)
}

/**
 * COLLAPSE placeholder for a fool's message (plans/13 §2.4): a dimmed one-line "nick · hidden" row
 * that expands to the full bubble on tap for the rest of the session. Day-separator and read-marker
 * dividers are drawn exactly as [MessageRow] does so grouping boundaries stay intact whether or not
 * the row is expanded.
 */
@Composable
private fun FoolPlaceholderRow(
    msg: MessageEntity,
    older: MessageEntity?,
    readMarkerTime: Long?,
    onExpand: () -> Unit,
) {
    val showNewDivider = readMarkerTime != null &&
        msg.serverTime > readMarkerTime &&
        (older == null || older.serverTime <= readMarkerTime)
    val showDay = older == null || dayStart(msg.serverTime) != dayStart(older.serverTime)

    // Column so the placeholder row and any dividers stack vertically rather than overlapping (a bare
    // item slot stacks its children like a Box).
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Collapsed fool row is still a message container; keep it selectable/tappable.
                .testTag(messageTag(msg))
                .clickable { onExpand() }
                .alpha(0.7f)
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.chat_fool_hidden, msg.sender),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (showNewDivider) {
            NewMessagesDivider(
                label = stringResource(R.string.chat_new_messages),
                modifier = Modifier.testTag("chat_read_marker_divider"),
            )
        }
        if (showDay) DaySeparator(timeMs = msg.serverTime)
    }
}

/**
 * Tap-to-re-collapse chip drawn above an expanded fool's bubble (bug #9). Mirrors the dimmed
 * placeholder styling of [FoolPlaceholderRow] so the toggle reads as its inverse, and keeps the
 * bubble's own long-press/link taps intact by owning a separate tap target.
 */
@Composable
private fun FoolCollapseChip(sender: String, onCollapse: () -> Unit) {
    Row(
        modifier = Modifier
            .clickable { onCollapse() }
            .alpha(0.7f)
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.VisibilityOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = stringResource(R.string.chat_fool_collapse, sender),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Append-load footer: spinner while loading older history, error, or end-of-history (plans/15 #27). */
@Composable
private fun LoadStateFooter(append: LoadState) {
    when (append) {
        is LoadState.Loading -> androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        is LoadState.Error -> androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.material3.Text(
                text = stringResource(R.string.chat_history_error),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            )
        }
        is LoadState.NotLoading -> if (append.endOfPaginationReached) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.chat_history_start),
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Right-aligned "Tap to retry" + delete affordance under a failed own-message bubble (>=48dp). */
@Composable
private fun RetryRow(onRetry: () -> Unit, onDelete: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        RetryRowAction(
            icon = Icons.Filled.Refresh,
            label = stringResource(R.string.chat_retry),
            onClick = onRetry,
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        RetryRowAction(
            icon = Icons.Filled.DeleteOutline,
            label = stringResource(R.string.chat_delete_failed),
            onClick = onDelete,
        )
    }
}

/** One error-tinted, >=48dp-tall tappable label used by [RetryRow] (plans/15 #24). */
@Composable
private fun RetryRowAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .wrapContentHeight()
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            icon,
            contentDescription = null,
            tint = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
        androidx.compose.material3.Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
