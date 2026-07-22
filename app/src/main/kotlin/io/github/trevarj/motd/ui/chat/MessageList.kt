package io.github.trevarj.motd.ui.chat

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.data.sync.NetworkBatchPayloadV1
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.repo.CachedLinkPreview
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.NewMessagesDivider
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.components.SystemEventPill
import io.github.trevarj.motd.ui.components.SwipeToReplyContainer
import io.github.trevarj.motd.ui.components.DaySeparator
import io.github.trevarj.motd.ui.components.dayStart
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.LocalSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Limit collapsed system-event work per composed row during high-velocity history traversal. */
internal const val MAX_COLLAPSED_SYSTEM_EVENTS = 24

/** Stable identity for remembered expanded pill state; changes when Paging extends a tail chunk. */
internal data class SystemRunContentKey(val newestId: Long, val oldestId: Long, val count: Int)

/** Reuse lazy compositions only across rows with the same structural layout. */
internal enum class MessageContentType {
    SYSTEM,
    NETWORK_BATCH,
    INVITE,
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
    message.kind == MessageKind.INVITE -> MessageContentType.INVITE
    message.kind == MessageKind.NETSPLIT || message.kind == MessageKind.NETJOIN -> MessageContentType.NETWORK_BATCH
    isSystemKind(message.kind) -> MessageContentType.SYSTEM
    message.kind == MessageKind.ACTION && message.failed -> MessageContentType.ACTION_FAILED
    message.kind == MessageKind.ACTION -> MessageContentType.ACTION
    message.isSelf && message.failed -> MessageContentType.SELF_FAILED
    message.isSelf -> MessageContentType.SELF
    message.failed -> MessageContentType.OTHER_FAILED
    else -> MessageContentType.OTHER
}

/** Stable per-message testTag id: server msgid when present, else the local entity id (pending). */
/** Stable UIAutomator/Compose address: server identity wins once an echo has promoted the row. */
internal fun timelineMessageTag(msgid: String?, eventId: Long): String =
    "chat_message_${msgid ?: eventId}"

private fun messageTag(msg: MessageEntity): String = timelineMessageTag(msg.msgid, msg.id)

private fun MessageEntity.timelineAnchor(): TimelineAnchor = TimelineAnchor(serverTime, id)

/**
 * True when [current] should show its sender header: it opens a new same-sender ≤3-min group.
 * [olderNeighbor] is the message immediately older in time (index+1 in a reversed list).
 */
fun showsSender(current: MessageEntity, olderNeighbor: MessageEntity?): Boolean {
    if (olderNeighbor == null) return true
    val sameActor = if (current.senderAccount != null && olderNeighbor.senderAccount != null) {
        current.senderAccount == olderNeighbor.senderAccount
    } else {
        current.normalizedActor == olderNeighbor.normalizedActor
    }
    if (!sameActor || olderNeighbor.isSelf != current.isSelf) return true
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
    readMarkerTime: TimelineAnchor?,
    onLongPress: (MessageEntity) -> Unit,
    onReply: (MessageEntity) -> Unit,
    // React to a message; the whole entity is passed so a still-pending own row (msgid == null) is
    // queued by the VM instead of silently dropped (bug: react on a just-sent message did nothing).
    onReact: (MessageEntity, String) -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    modifier: Modifier = Modifier,
    canRetry: (MessageEntity) -> Boolean = { true },
    loadPreview: suspend (String) -> LinkPreview?,
    richContentReady: Boolean,
    showImages: Boolean,
    showLinkPreviews: Boolean,
    onOpenLink: (String) -> Unit,
    cachedPreview: (String) -> CachedLinkPreview? = { null },
    liveEntryId: Long? = null,
    onLiveEntryConsumed: (Long) -> Unit = {},
    reactionChips: (String) -> List<ReactionChip> = { emptyList() },
    replyPreview: (String) -> StateFlow<ReplyPreviewData?> = { MutableStateFlow(null) },
    onReplyPreviewClick: (String) -> Unit = {},
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
    identityRules: IrcIdentityRules = IrcIdentityRules(),
    historyUiState: ChatHistoryUiState = ChatHistoryUiState.Hidden,
    onHistoryRetry: () -> Unit = {},
    // Effective per-row expansion (global expand-all + per-row overrides live in the caller); toggle
    // flips a single fool row either way so expand/re-collapse is bidirectional (bug #9).
    foolExpanded: (Long) -> Boolean = { false },
    onToggleFool: (Long) -> Unit = {},
    // Tapping a non-self sender's name/avatar opens the nick sheet (plans/16 §5.8).
    onSenderClick: (String) -> Unit = {},
    onAcceptInvite: (Long) -> Unit = {},
    onDismissInvite: (Long) -> Unit = {},
) {
    val scrolling by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
    // Scrolling postpones only cache misses. Parsed URLs and resolved previews remain renderable so
    // a recycled row does not lose rich content halfway through a fling.
    val canStartNewRichContentWork = richContentReady && !scrolling && (showImages || showLinkPreviews)
    val formatMessageTime = rememberMessageTimeFormatter()
    LazyColumn(
        state = listState,
        reverseLayout = true,
        // Retained rows can predate messages sent by earlier orchestrated journeys. Keep the
        // timeline addressable so the real-stack acceptance test can scroll to an imported row
        // instead of confusing an off-screen row with a missing one.
        modifier = modifier.fillMaxSize().testTag("chat_timeline"),
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
            val msg = items[index]
            if (msg == null) {
                MessagePlaceholderRow()
                return@items
            }
            val older = if (index + 1 < items.itemCount) items.peek(index + 1) else null
            val newer = if (index - 1 >= 0) items.peek(index - 1) else null

            if (msg.kind == MessageKind.INVITE) {
                LiveTimelineEntry(liveEntryId, msg.id, onLiveEntryConsumed) {
                    InvitationCard(
                        message = msg,
                        onJoin = { onAcceptInvite(msg.id) },
                        onDismiss = { onDismissInvite(msg.id) },
                    )
                }
                return@items
            }

            if (msg.kind == MessageKind.NETSPLIT || msg.kind == MessageKind.NETJOIN) {
                LiveTimelineEntry(liveEntryId, msg.id, onLiveEntryConsumed) {
                    NetworkBatchPill(msg)
                }
                return@items
            }

            // System-event collapse (plans/15 #15): render one pill on the run's *newest* item and
            // skip the rest. In a reversed list the newest of a contiguous system run is the item
            // whose just-newer neighbor is not a system event.
            if (isSystemKind(msg.kind)) {
                if (!isSystemRunChunkHead(index, newer?.let { isSystemKind(it.kind) } == true)) return@items
                LiveTimelineEntry(liveEntryId, msg.id, onLiveEntryConsumed) {
                    SystemEventRun(
                        items = items,
                        index = index,
                        newest = msg,
                        readMarkerTime = readMarkerTime,
                    )
                }
                return@items
            }

            // Fool COLLAPSE (plans/13 §2.4): render a tap-to-expand placeholder in place of the
            // bubble until its id is expanded. HIDE mode is filtered upstream so it never reaches
            // here; system-kind rows are handled above and never fool-treated.
            val isFool = foolsMode == FoolsMode.COLLAPSE &&
                isFoolMessage(msg, fools, identityRules)
            if (isFool && !foolExpanded(msg.id)) {
                LiveTimelineEntry(liveEntryId, msg.id, onLiveEntryConsumed) {
                    FoolPlaceholderRow(
                        msg = msg,
                        older = older,
                        readMarkerTime = readMarkerTime,
                        onExpand = { onToggleFool(msg.id) },
                    )
                }
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
            val rowContent: @Composable () -> Unit = {
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
                        senderIsFriend = !msg.isSelf && msg.matchesConfiguredActor(friends, identityRules),
                        reactions = msg.msgid?.let(reactionChips).orEmpty(),
                        knownNicks = knownNicks,
                        identityRules = identityRules,
                        onLongPress = onLongPress,
                        onReply = onReply,
                        onReact = onReact,
                        onImageClick = onImageClick,
                        onRetry = onRetry,
                        canRetry = canRetry(msg),
                        onDelete = onDelete,
                        loadPreview = loadPreview,
                        showImages = showImages,
                        showLinkPreviews = showLinkPreviews,
                        canStartNewRichContentWork = canStartNewRichContentWork,
                        cachedPreview = cachedPreview,
                        onOpenLink = onOpenLink,
                        onSenderClick = onSenderClick,
                        replyPreview = replyPreview,
                        onReplyPreviewClick = onReplyPreviewClick,
                    )
                }
            }
            LiveTimelineEntry(liveEntryId, msg.id, onLiveEntryConsumed, rowContent)
        }

        // Append spinner / end-of-history / error affordances (plans/15 #27). This item sits at the
        // top of the reversed list, i.e. visually above the oldest message where APPEND loads more.
        item(key = "append-state", contentType = "loadstate") {
            ChatHistoryFooter(historyUiState) {
                onHistoryRetry()
                items.retry()
            }
        }
    }
}

/** A quiet, stable-height skeleton prevents placeholder-only pages from measuring as zero rows. */
@Composable
internal fun MessagePlaceholderRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clearAndSetSemantics {},
        contentAlignment = Alignment.CenterStart,
    ) {
        Spacer(
            Modifier
                .padding(horizontal = LocalSpacing.current.messageOuterHPad)
                .fillMaxWidth(0.38f)
                .height(10.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    RoundedCornerShape(5.dp),
                ),
        )
    }
}

/** Applies the one-shot entrance uniformly to every kind of rendered, meaningful timeline row. */
@Composable
private fun LiveTimelineEntry(
    liveEntryId: Long?,
    messageId: Long,
    onConsumed: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    if (liveEntryId == messageId) {
        LiveMessageEntry(messageId = messageId, onConsumed = onConsumed, content = content)
    } else {
        content()
    }
}

/**
 * One-shot live-message entrance. Transforming the already-measured row avoids changing the
 * reverse list's height while it is following a burst of IRC arrivals.
 */
@Composable
private fun LiveMessageEntry(
    messageId: Long,
    onConsumed: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    val initialOffset = with(LocalDensity.current) { 8.dp.toPx() }
    val alpha = remember(messageId) { Animatable(0f) }
    val scale = remember(messageId) { Animatable(0.96f) }
    val translationY = remember(messageId, initialOffset) { Animatable(initialOffset) }

    LaunchedEffect(messageId) {
        coroutineScope {
            launch { alpha.animateTo(1f, MotdMotion.fadeIn) }
            launch { scale.animateTo(1f, MotdMotion.softSpring) }
            launch { translationY.animateTo(0f, MotdMotion.softSpring) }
        }
        onConsumed(messageId)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha.value
                scaleX = scale.value
                scaleY = scale.value
                this.translationY = translationY.value
            },
    ) {
        content()
    }
}

@Composable
private fun NetworkBatchPill(message: MessageEntity) {
    val payload = remember(message.eventPayload) { NetworkBatchPayloadV1.decode(message.eventPayload) }
    if (payload == null) {
        SystemEventPill(
            summary = message.text,
            lineCount = 1,
            loadLines = { listOf(message.text) },
            contentKey = message.id,
            modifier = Modifier.testTag("chat_network_batch_${message.id}"),
        )
        return
    }
    val action = if (message.kind == MessageKind.NETSPLIT) "split" else "rejoined"
    val summary = "${payload.nicks.size} ${if (payload.nicks.size == 1) "user" else "users"} $action " +
        "(${payload.serverA} ↔ ${payload.serverB})"
    SystemEventPill(
        summary = summary,
        lineCount = payload.nicks.size,
        loadLines = { payload.nicks },
        contentKey = message.id,
        modifier = Modifier.testTag("chat_network_batch_${message.kind.name.lowercase()}_${message.id}"),
    )
}

@Composable
private fun InvitationCard(
    message: MessageEntity,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val payload = remember(message.eventPayload) { InvitePayloadV1.decode(message.eventPayload) }
    val state = message.inviteState
    if (payload == null || state == null || state == InviteState.HISTORICAL) {
        SystemEventPill(
            summary = message.text,
            lineCount = 1,
            loadLines = { listOf(message.text) },
            contentKey = message.id,
            modifier = Modifier.testTag("chat_invite_compact_${message.id}"),
        )
        return
    }
    if (state == InviteState.JOINED || state == InviteState.DISMISSED) {
        val resolution = if (state == InviteState.JOINED) "Joined" else "Dismissed"
        SystemEventPill(
            summary = "$resolution ${payload.channel}",
            lineCount = 1,
            loadLines = { listOf(message.text) },
            contentKey = message.id,
            modifier = Modifier.testTag("chat_invite_resolved_${message.id}"),
        )
        return
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("chat_invite_card_${message.id}"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Invitation to ${payload.channel}", style = MaterialTheme.typography.titleMedium)
            Text(message.text, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            if (state == InviteState.FAILED) {
                Text("Could not join. You can retry.", color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onJoin,
                    enabled = state != InviteState.JOINING,
                    modifier = Modifier.testTag("chat_invite_join_${message.id}"),
                ) {
                    Text(if (state == InviteState.JOINING) "Joining…" else "Join")
                }
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("chat_invite_dismiss_${message.id}"),
                ) {
                    Text("Dismiss")
                }
            }
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
    readMarkerTime: TimelineAnchor?,
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
        newest.timelineAnchor() > readMarkerTime &&
        (olderThanRun == null || olderThanRun.timelineAnchor() <= readMarkerTime)
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
    readMarkerTime: TimelineAnchor?,
    senderIsFriend: Boolean,
    reactions: List<ReactionChip>,
    knownNicks: Set<String>,
    identityRules: IrcIdentityRules,
    onLongPress: (MessageEntity) -> Unit,
    onReply: (MessageEntity) -> Unit,
    onReact: (MessageEntity, String) -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    canRetry: Boolean,
    onDelete: (MessageEntity) -> Unit,
    loadPreview: suspend (String) -> LinkPreview?,
    showImages: Boolean,
    showLinkPreviews: Boolean,
    canStartNewRichContentWork: Boolean,
    cachedPreview: (String) -> CachedLinkPreview?,
    onOpenLink: (String) -> Unit,
    onSenderClick: (String) -> Unit,
    replyPreview: (String) -> StateFlow<ReplyPreviewData?>,
    onReplyPreviewClick: (String) -> Unit,
    // Non-null for an expanded fool row: renders a "hide" chip above the bubble that re-collapses it.
    onCollapseFool: (() -> Unit)? = null,
) {
    // Read-marker divider sits below the first message newer than the marker (drawn after the
    // bubble because the list is reversed => "above" the newer message visually).
    val showNewDivider = readMarkerTime != null &&
        msg.timelineAnchor() > readMarkerTime &&
        (older == null || older.timelineAnchor() <= readMarkerTime)

    // Day separator when this message starts a new day relative to the older neighbor.
    val showDay = remember(msg.serverTime, older?.serverTime) {
        older == null || dayStart(msg.serverTime) != dayStart(older.serverTime)
    }

    // A row asks Room for its reply target only while it is composed. This avoids timeline-wide
    // loaded-window scans during fast traversal; collection is lifecycle-cancelled off-screen.
    val resolvedReply: ReplyPreviewData? = if (msg.replyToMsgid != null) {
        val replyFlow = remember(msg.replyToMsgid) { replyPreview(msg.replyToMsgid) }
        val resolved by replyFlow.collectAsStateWithLifecycle()
        resolved
    } else {
        null
    }
    // A reply relationship remains visible even if its parent is not in local history yet. The
    // reactive lookup above replaces this marker as soon as echo confirmation or history inserts
    // the referenced msgid.
    val reply = resolvedReply ?: msg.replyToMsgid?.let {
        ReplyPreviewData(
            sender = stringResource(R.string.chat_action_reply),
            text = stringResource(R.string.chat_reply_target_unavailable),
        )
    }

    // URL discovery is unnecessary for the overwhelming majority of IRC lines. Completed parses
    // come from a bounded process cache first; only a genuine miss waits for the fling to settle.
    val mayContainUrl = remember(msg.text) {
        msg.text.contains("http://") || msg.text.contains("https://")
    }
    var richUrls by remember(msg.id, msg.text) {
        mutableStateOf(if (mayContainUrl) MessageUrlCache.get(msg.text) else MessageUrls.Empty)
    }
    val latestCanStartNewRichContentWork by rememberUpdatedState(canStartNewRichContentWork)
    LaunchedEffect(msg.id, msg.text, mayContainUrl) {
        if (!mayContainUrl || richUrls != null) return@LaunchedEffect
        snapshotFlow { latestCanStartNewRichContentWork }.first { it }
        val parsed = withContext(Dispatchers.Default) { messageUrls(msg.text) }
        MessageUrlCache.put(msg.text, parsed)
        richUrls = parsed
    }
    val visibleUrls = richUrls?.gated(showImages, showLinkPreviews)
    val imageUrl = visibleUrls?.imageUrl
    val linkUrl = visibleUrls?.linkUrl

    // A cached completion is rendered synchronously even while scrolling. A cache miss waits for
    // idle, then joins the repository's process-owned single-flight fetch. Null is a completed
    // negative result, not a loading state, so recycling does not restart a skeleton indefinitely.
    val initialCachedPreview = linkUrl?.let(cachedPreview)
    var previewState by remember(msg.id, linkUrl) {
        mutableStateOf<PreviewState>(initialCachedPreview?.let { PreviewState.Done(it.preview) } ?: PreviewState.Idle)
    }
    val latestCachedPreview by rememberUpdatedState(cachedPreview)
    LaunchedEffect(msg.id, linkUrl) {
        val url = linkUrl ?: return@LaunchedEffect
        if (previewState !is PreviewState.Idle) return@LaunchedEffect
        latestCachedPreview(url)?.let {
            previewState = PreviewState.Done(it.preview)
            return@LaunchedEffect
        }
        snapshotFlow { latestCanStartNewRichContentWork }.first { it }
        if (previewState !is PreviewState.Idle) return@LaunchedEffect
        latestCachedPreview(url)?.let {
            previewState = PreviewState.Done(it.preview)
            return@LaunchedEffect
        }
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
    val previewResolved = linkUrl != null && previewState is PreviewState.Done
    val formattedTime = remember(msg.serverTime, formatTime) { formatTime(msg.serverTime) }
    // Ordinary rows stay on the hot scrolling path without even resolving the accessibility
    // string; mention state is immutable for a stored row and only the sparse highlighted rows
    // need it.
    val mentionDescription = if (msg.hasMention && !msg.isSelf) {
        stringResource(R.string.chat_message_mentions_you)
    } else {
        null
    }

    onCollapseFool?.let { FoolCollapseChip(sender = msg.sender, onCollapse = it) }

    SwipeToReplyContainer(
        // Keep the stable automation id and mention state on one semantics node. SwipeToReply adds
        // its custom action downstream without changing this message-level accessibility state.
        modifier = Modifier.semantics {
            semanticsTestTag = messageTag(msg)
            mentionDescription?.let { stateDescription = it }
        },
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
            hasMention = msg.hasMention,
            senderIsFriend = senderIsFriend,
            failed = msg.failed,
            // Subtle "sending…" state before the 30s failure flip (plans/15 #21).
            pending = msg.pendingLabel != null,
            reply = reply,
            onReplyClick = if (resolvedReply != null) {
                msg.replyToMsgid?.let { parentMsgid -> { onReplyPreviewClick(parentMsgid) } }
            } else {
                null
            },
            imageUrl = imageUrl,
            linkPreview = preview,
            linkPreviewLoading = previewLoading,
            linkPreviewResolved = previewResolved,
            reactions = reactions,
            knownNicks = knownNicks,
            identityRules = identityRules,
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
        RetryRow(
            onRetry = if (canRetry) ({ onRetry(msg) }) else null,
            onDelete = { onDelete(msg) },
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
    readMarkerTime: TimelineAnchor?,
    onExpand: () -> Unit,
) {
    val showNewDivider = readMarkerTime != null &&
        msg.timelineAnchor() > readMarkerTime &&
        (older == null || older.timelineAnchor() <= readMarkerTime)
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
                .padding(horizontal = LocalSpacing.current.messageOuterHPad, vertical = 2.dp),
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
            .padding(horizontal = LocalSpacing.current.messageOuterHPad, vertical = 2.dp),
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

const val CHAT_HISTORY_RETRY_TAG = "chat_history_retry"

/** Only persisted protocol completion may render the beginning-of-history claim. */
@Composable
fun ChatHistoryFooter(state: ChatHistoryUiState, onRetry: () -> Unit) {
    when (state) {
        ChatHistoryUiState.Hidden -> Unit
        ChatHistoryUiState.Loading -> androidx.compose.foundation.layout.Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        }
        ChatHistoryUiState.Offline -> HistoryStatusText(R.string.chat_history_footer_offline)
        ChatHistoryUiState.Negotiating -> HistoryStatusText(R.string.chat_history_footer_negotiating)
        ChatHistoryUiState.Unsupported -> HistoryStatusText(R.string.chat_history_footer_unsupported)
        ChatHistoryUiState.ConfirmedStart -> HistoryStatusText(R.string.chat_history_start)
        is ChatHistoryUiState.Incomplete -> HistoryRetryFooter(
            text = stringResource(R.string.chat_history_incomplete),
            onRetry = onRetry,
        )
        is ChatHistoryUiState.Capped -> HistoryRetryFooter(
            text = pluralStringResource(R.plurals.chat_history_capped, state.limit, state.limit),
            onRetry = onRetry,
        )
        ChatHistoryUiState.Error -> HistoryRetryFooter(
            text = stringResource(R.string.chat_history_error),
            onRetry = onRetry,
        )
    }
}

@Composable
private fun HistoryStatusText(textRes: Int) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(textRes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryRetryFooter(text: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(
            onClick = onRetry,
            modifier = Modifier.heightIn(min = 48.dp).testTag(CHAT_HISTORY_RETRY_TAG),
        ) {
            Text(stringResource(R.string.chat_retry))
        }
    }
}

/** Right-aligned retry (when safe) and delete affordances under a failed message bubble. */
@Composable
private fun RetryRow(onRetry: (() -> Unit)?, onDelete: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LocalSpacing.current.messageOuterHPad, vertical = 2.dp),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        if (onRetry != null) {
            RetryRowAction(
                icon = Icons.Filled.Refresh,
                label = stringResource(R.string.chat_retry),
                onClick = onRetry,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        }
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
