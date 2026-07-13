package io.github.trevarj.motd.ui.chat

import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import io.github.trevarj.motd.ui.components.SwipeToReplyContainer
import io.github.trevarj.motd.ui.components.DaySeparator
import io.github.trevarj.motd.ui.components.dayStart
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/** Limit collapsed system-event work per composed row during high-velocity history traversal. */
internal const val MAX_COLLAPSED_SYSTEM_EVENTS = 24

/** Stable identity for remembered expanded pill state; changes when Paging extends a tail chunk. */
internal data class SystemRunContentKey(val newestId: Long, val oldestId: Long, val count: Int)

/** Reuse lazy compositions only across rows with the same structural layout. */
internal enum class MessageContentType {
    SYSTEM,
    ACTION,
    ACTION_FAILED,
    SELF,
    SELF_FAILED,
    OTHER,
    OTHER_FAILED,
}

fun isSystemKind(kind: MessageKind): Boolean = when (kind) {
    MessageKind.JOIN,
    MessageKind.PART,
    MessageKind.QUIT,
    MessageKind.KICK,
    MessageKind.NICK,
    MessageKind.MODE,
    MessageKind.TOPIC,
    MessageKind.SERVER_INFO,
    MessageKind.ERROR,
    -> true
    else -> false
}

internal fun messageContentType(message: MessageEntity): MessageContentType = when {
    isSystemKind(message.kind) -> MessageContentType.SYSTEM
    message.kind == MessageKind.ACTION && message.failed -> MessageContentType.ACTION_FAILED
    message.kind == MessageKind.ACTION -> MessageContentType.ACTION
    message.isSelf && message.failed -> MessageContentType.SELF_FAILED
    message.isSelf -> MessageContentType.SELF
    message.failed -> MessageContentType.OTHER_FAILED
    else -> MessageContentType.OTHER
}

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
    networkId: Long?,
    readMarkerTime: Long?,
    onLongPress: (MessageEntity) -> Unit,
    onReply: (MessageEntity) -> Unit,
    // React to a message; the whole entity is passed so a still-pending own row (msgid == null) is
    // queued by the VM instead of silently dropped (bug: react on a just-sent message did nothing).
    onReact: (MessageEntity, String) -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    loadPreview: suspend (String) -> LinkPreview?,
    richContentReady: Boolean,
    showImages: Boolean,
    showLinkPreviews: Boolean,
    onOpenLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    reactionChips: (String) -> List<ReactionChip> = { emptyList() },
    replyPreview: (String) -> Flow<ReplyPreviewData?> = { flowOf(null) },
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
    val scrolling by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
    val richContentEnabled = richContentReady && !scrolling && (showImages || showLinkPreviews)
    val formatMessageTime = rememberMessageTimeFormatter()
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
            // Own bubbles, other bubbles, ACTION rows, and retry rows have different composition
            // shapes. Keeping separate pools avoids structural churn at exactly the boundaries that
            // previously produced hitches when a fling crossed own messages.
            contentType = items.itemContentType(::messageContentType),
        ) { index ->
            val msg = items[index] ?: return@items
            val older = if (index + 1 < items.itemCount) items.peek(index + 1) else null
            val newer = if (index - 1 >= 0) items.peek(index - 1) else null

            // System-event collapse (plans/15 #15): render one pill on the run's *newest* item and
            // skip the rest. In a reversed list the newest of a contiguous system run is the item
            // whose just-newer neighbor is not a system event.
            if (isSystemKind(msg.kind)) {
                if (!isSystemRunChunkHead(index, newer?.let { isSystemKind(it.kind) } == true)) return@items
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
            // Deep jumps are rare. Do not install an animation state object in every ordinary row;
            // only the single target needs one while the highlight is active.
            val highlightColor = if (highlighted) {
                val pulse = remember(msg.id, highlightMsgid) { Animatable(0f) }
                LaunchedEffect(msg.id, highlightMsgid) {
                    pulse.animateTo(1f, tween(durationMillis = 800))
                    pulse.animateTo(0f, tween(durationMillis = 800))
                }
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f * pulse.value)
            } else {
                Color.Transparent
            }

            // Column (not Box): MessageRow emits several vertical siblings — the collapse chip,
            // bubble, retry row, and read-marker/day dividers. A Box would stack them on top of one
            // another (dividers over message text; the fool-collapse chip trapped behind the bubble).
            // A Column lays them out top-to-bottom so each affordance owns its own space and taps.
            Column(modifier = Modifier.fillMaxWidth().background(highlightColor)) {
            MessageRow(
                msg = msg,
                networkId = networkId,
                older = older,
                formatTime = formatMessageTime,
                readMarkerTime = readMarkerTime,
                // An expanded fool row shows a small tap-to-re-collapse chip above its bubble so the
                // toggle is bidirectional without stealing the bubble's long-press/link taps (#9).
                onCollapseFool = if (isFool) ({ onToggleFool(msg.id) }) else null,
                senderIsFriend = !msg.isSelf && normalizeNick(msg.sender) in friends,
                reactions = msg.msgid?.let(reactionChips).orEmpty(),
                knownNicks = knownNicks,
                onLongPress = onLongPress,
                onReply = onReply,
                onReact = onReact,
                onImageClick = onImageClick,
                onRetry = onRetry,
                onDelete = onDelete,
                loadPreview = loadPreview,
                showImages = showImages,
                showLinkPreviews = showLinkPreviews,
                richContentEnabled = richContentEnabled,
                onOpenLink = onOpenLink,
                onSenderClick = onSenderClick,
                replyPreview = replyPreview,
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
 * Render one bounded chunk of a collapsed system-event run. Very long bursts are split into
 * adjacent pills, so scrolling never scans or allocates the entire run. Lines remain lazy until
 * expansion. The
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
    // Gather at most one chunk: newest first (index), then older neighbors while still system events.
    val run = ArrayList<MessageEntity>()
    run.add(newest)
    var i = index + 1
    val chunkLimit = systemRunChunkLimit(index)
    while (i < items.itemCount && run.size < chunkLimit) {
        val m = items.peek(i) ?: break
        if (!isSystemKind(m.kind)) break
        run.add(m)
        i++
    }
    val oldest = run.last()
    val olderThanRun = if (index + run.size < items.itemCount) items.peek(index + run.size) else null

    val summary = if (run.size == 1) newest.text else summarizeSystemRun(run)

    // Divider below the run when the run's newest crosses the marker and its older neighbor doesn't.
    val showNewDivider = readMarkerTime != null &&
        newest.serverTime > readMarkerTime &&
        (olderThanRun == null || olderThanRun.serverTime <= readMarkerTime)
    val showDay = remember(oldest.serverTime, olderThanRun?.serverTime) {
        olderThanRun == null || dayStart(oldest.serverTime) != dayStart(olderThanRun.serverTime)
    }

    // Column so the pill and any dividers stack vertically. A bare item slot stacks siblings on top
    // of each other (its MeasurePolicy behaves like a Box), which would overlap the divider text.
    Column(modifier = Modifier.fillMaxWidth()) {
        SystemEventPill(
            summary = summary,
            lineCount = run.size,
            loadLines = { run.map { it.text } },
            contentKey = SystemRunContentKey(newest.id, oldest.id, run.size),
            modifier = Modifier.testTag("chat_system_pill"),
        )
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
 * A run begins at its newest row and at fixed absolute-index chunk boundaries. This is deliberately
 * O(1): suppressed rows do no neighbor walk while flinging, and each event belongs to one head.
 */
internal fun isSystemRunChunkHead(index: Int, newerIsSystem: Boolean): Boolean =
    !newerIsSystem || index % MAX_COLLAPSED_SYSTEM_EVENTS == 0

/** Number of rows from [index] through the next absolute chunk boundary (at most 24). */
internal fun systemRunChunkLimit(index: Int): Int =
    MAX_COLLAPSED_SYSTEM_EVENTS - (index % MAX_COLLAPSED_SYSTEM_EVENTS)

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

/** Completion-tracked link-preview state so a failed/null fetch stops the loading skeleton. */
private sealed interface PreviewState {
    data object Idle : PreviewState
    data object Loading : PreviewState
    data class Done(val preview: LinkPreview?) : PreviewState
}

@Composable
private fun MessageRow(
    msg: MessageEntity,
    networkId: Long?,
    older: MessageEntity?,
    formatTime: (Long) -> String,
    readMarkerTime: Long?,
    senderIsFriend: Boolean,
    reactions: List<ReactionChip>,
    knownNicks: Set<String>,
    onLongPress: (MessageEntity) -> Unit,
    onReply: (MessageEntity) -> Unit,
    onReact: (MessageEntity, String) -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    onDelete: (MessageEntity) -> Unit,
    loadPreview: suspend (String) -> LinkPreview?,
    showImages: Boolean,
    showLinkPreviews: Boolean,
    richContentEnabled: Boolean,
    onOpenLink: (String) -> Unit,
    onSenderClick: (String) -> Unit,
    replyPreview: (String) -> Flow<ReplyPreviewData?>,
    // Non-null for an expanded fool row: renders a "hide" chip above the bubble that re-collapses it.
    onCollapseFool: (() -> Unit)? = null,
) {
    // Read-marker divider sits below the first message newer than the marker (drawn after the
    // bubble because the list is reversed => "above" the newer message visually).
    val showNewDivider = readMarkerTime != null &&
        msg.serverTime > readMarkerTime &&
        (older == null || older.serverTime <= readMarkerTime)

    // Day separator when this message starts a new day relative to the older neighbor.
    val showDay = remember(msg.serverTime, older?.serverTime) {
        older == null || dayStart(msg.serverTime) != dayStart(older.serverTime)
    }

    // A row asks Room for its reply target only while it is composed. This avoids timeline-wide
    // loaded-window scans during fast traversal; collection is lifecycle-cancelled off-screen.
    val reply: ReplyPreviewData? = if (msg.replyToMsgid != null) {
        val replyFlow = remember(msg.replyToMsgid) { replyPreview(msg.replyToMsgid) }
        val resolved by replyFlow.collectAsStateWithLifecycle(initialValue = null)
        resolved
    } else {
        null
    }

    // URL discovery is unnecessary for the overwhelming majority of IRC lines. For the rows that
    // can contain a URL, wait until the fling settles and do the single regex pass on Default.
    val mayContainUrl = remember(msg.text) {
        msg.text.contains("http://") || msg.text.contains("https://")
    }
    var richUrls by remember(msg.id, msg.text) {
        mutableStateOf<MessageUrls?>(if (mayContainUrl) null else MessageUrls.Empty)
    }
    val latestRichContentEnabled by rememberUpdatedState(richContentEnabled)
    LaunchedEffect(msg.id, msg.text, mayContainUrl) {
        if (!mayContainUrl || richUrls != null) return@LaunchedEffect
        snapshotFlow { latestRichContentEnabled }.first { it }
        richUrls = withContext(Dispatchers.Default) { messageUrls(msg.text) }
    }
    val visibleUrls = richUrls?.gated(showImages, showLinkPreviews)
    val imageUrl = visibleUrls?.imageUrl
    val linkUrl = visibleUrls?.linkUrl

    // Lazily fetch a link preview for the first non-image URL once the timeline is idle. The
    // completion state retains a null result (fetch failed / not HTML), so it does not retry or
    // leave a loading skeleton behind (plans/15 #3).
    var previewState by remember(msg.id, linkUrl) { mutableStateOf<PreviewState>(PreviewState.Idle) }
    val latestPreviewsEnabled by rememberUpdatedState(richContentEnabled)
    LaunchedEffect(msg.id, linkUrl) {
        val url = linkUrl ?: return@LaunchedEffect
        snapshotFlow { latestPreviewsEnabled }.first { it }
        if (previewState !is PreviewState.Idle) return@LaunchedEffect
        previewState = PreviewState.Loading
        val preview = try {
            loadPreview(url)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        previewState = PreviewState.Done(preview)
    }
    val preview = (previewState as? PreviewState.Done)?.preview?.withImageGate(showImages)
    val previewLoading = linkUrl != null && previewState is PreviewState.Loading
    val formattedTime = remember(msg.serverTime, formatTime) { formatTime(msg.serverTime) }

    onCollapseFool?.let { FoolCollapseChip(sender = msg.sender, onCollapse = it) }

    SwipeToReplyContainer(
        modifier = Modifier.testTag(messageTag(msg)),
        onReply = { onReply(msg) },
    ) { rowModifier ->
        MessageBubble(
            // Per-message handle for long-press/react/reply/deep-jump. Prefer the stable server
            // msgid; pending rows fall back to the local id for stable E2E selection.
            modifier = rowModifier,
            sender = msg.sender,
            networkId = networkId,
            senderAccount = msg.senderAccount,
            text = msg.text,
            timeMs = msg.serverTime,
            formattedTime = formattedTime,
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
            // Pass the entity, not just msgid: the VM also handles a pending reaction uniformly.
            onReact = { emoji -> onReact(msg, emoji) },
            onImageClick = onImageClick,
            onLinkPreviewClick = { linkUrl?.let(onOpenLink) },
            // Only non-self senders open the nick sheet (self has no social/moderation actions).
            onSenderClick = if (msg.isSelf) null else ({ onSenderClick(msg.sender) }),
        )
    }
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
    val showDay = remember(msg.serverTime, older?.serverTime) {
        older == null || dayStart(msg.serverTime) != dayStart(older.serverTime)
    }

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
