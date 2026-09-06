package io.github.trevarj.motd.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.CachedAudioMetadata
import io.github.trevarj.motd.audio.displayTextForAudioMessage
import io.github.trevarj.motd.audio.extensionlessAudioCandidates
import io.github.trevarj.motd.audio.toAttachment
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferProtocol
import io.github.trevarj.motd.data.db.DccTransferState
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.repo.CachedLinkPreview
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.sync.COMMAND_RESPONSE_PAYLOAD_PREFIX
import io.github.trevarj.motd.data.sync.InvitePayloadV1
import io.github.trevarj.motd.data.sync.NetworkBatchPayloadV1
import io.github.trevarj.motd.dcc.DccEndpointRisk
import io.github.trevarj.motd.dcc.dccEndpointRisk
import io.github.trevarj.motd.dcc.resolveDccAddress
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.components.AudioAttachmentPlayers
import io.github.trevarj.motd.ui.components.DaySeparator
import io.github.trevarj.motd.ui.components.HistoryGapDivider
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.LocalInlineMediaConsent
import io.github.trevarj.motd.ui.components.LocalLinkMediaConsent
import io.github.trevarj.motd.ui.components.LocalLinkPreviewAwaiting
import io.github.trevarj.motd.ui.components.LocalLinkPreviewFailed
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.NewMessagesDivider
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.components.RemoteMediaConsent
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.components.SwipeToReplyContainer
import io.github.trevarj.motd.ui.components.SystemEventPill
import io.github.trevarj.motd.ui.components.dayStart
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import io.github.trevarj.motd.ui.theme.LocalSpacing
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.compose.ui.semantics.testTag as semanticsTestTag

/**
 * Target collapsed system-event count per pill, and the modulus of the identity hash that places
 * chunk boundaries. Bounds the work one composed row does during high-velocity history traversal.
 */
internal const val MAX_COLLAPSED_SYSTEM_EVENTS = 24

/** Refresh identity for expanded line content; changes when Paging extends a tail chunk. */
internal data class SystemRunContentKey(
    val newestId: Long,
    val oldestId: Long,
    val count: Int,
)

/** An expanded run stays expanded when synchronization reshapes its bounded Paging chunk. */
internal fun systemRunExpanded(
    runIds: Collection<Long>,
    expandedEventIds: Set<Long>,
): Boolean = runIds.any(expandedEventIds::contains)

internal fun updateExpandedSystemEvents(
    current: Set<Long>,
    runIds: Collection<Long>,
    expanded: Boolean,
): Set<Long> = if (expanded) current + runIds else current - runIds.toSet()

/** Present a newest-first storage chunk chronologically inside its pill. */
internal fun systemRunPresentationLines(run: List<MessageEntity>): List<String> = run.asReversed().map { it.text }

/** Reuse lazy compositions only across rows with the same structural layout. */
internal enum class MessageContentType {
    SYSTEM,
    NETWORK_BATCH,
    INVITE,
    DCC_TRANSFER,
    ACTION,
    ACTION_FAILED,
    SELF,
    SELF_FAILED,
    OTHER,
    OTHER_FAILED,
}

fun isSystemKind(kind: MessageKind): Boolean =
    when (kind) {
        MessageKind.JOIN,
        MessageKind.PART,
        MessageKind.QUIT,
        MessageKind.KICK,
        MessageKind.NICK,
        MessageKind.AWAY,
        MessageKind.BACK,
        MessageKind.MODE,
        MessageKind.TOPIC,
        MessageKind.SERVER_INFO,
        MessageKind.ERROR,
        -> true

        else -> false
    }

internal fun commandResponseGroup(message: MessageEntity): String? = message.eventPayload?.takeIf { it.startsWith(COMMAND_RESPONSE_PAYLOAD_PREFIX) }

internal fun sameSystemRun(
    first: MessageEntity,
    second: MessageEntity,
): Boolean {
    val firstCommand = commandResponseGroup(first)
    val secondCommand = commandResponseGroup(second)
    return if (firstCommand != null || secondCommand != null) {
        firstCommand != null && firstCommand == secondCommand
    } else {
        isSystemKind(first.kind) && isSystemKind(second.kind)
    }
}

internal fun messageContentType(
    message: MessageEntity,
    collapseSystemEvents: Boolean = true,
): MessageContentType =
    when {
        message.kind == MessageKind.INVITE -> MessageContentType.INVITE
        message.kind == MessageKind.DCC_TRANSFER -> MessageContentType.DCC_TRANSFER
        message.kind == MessageKind.NETSPLIT || message.kind == MessageKind.NETJOIN -> MessageContentType.NETWORK_BATCH
        collapseSystemEvents && isSystemKind(message.kind) -> MessageContentType.SYSTEM
        message.kind == MessageKind.ACTION && message.failed -> MessageContentType.ACTION_FAILED
        message.kind == MessageKind.ACTION -> MessageContentType.ACTION
        message.isSelf && message.failed -> MessageContentType.SELF_FAILED
        message.isSelf -> MessageContentType.SELF
        message.failed -> MessageContentType.OTHER_FAILED
        else -> MessageContentType.OTHER
    }

/** Stable per-message testTag id: server msgid when present, else the local entity id (pending). */

/** Stable UIAutomator/Compose address: server identity wins once an echo has promoted the row. */
internal fun timelineMessageTag(
    msgid: String?,
    eventId: Long,
): String = "chat_message_${msgid ?: eventId}"

private fun messageTag(msg: MessageEntity): String = timelineMessageTag(msg.msgid, msg.id)

internal fun foolCollapseTag(
    msgid: String?,
    eventId: Long,
): String = "chat_fool_collapse_${msgid ?: eventId}"

// `MessageEntity.timelineAnchor()` lives in ChatModels.kt: it had a byte-identical private copy here
// until the seam rule needed to name a row from the screen, at which point one package-level
// definition is the only thing that keeps the timeline's ordering currency singular.

/**
 * True when [current] should show its sender header: it opens a new same-sender ≤3-min group.
 * [olderNeighbor] is the message immediately older in time (index+1 in a reversed list).
 */
fun showsSender(
    current: MessageEntity,
    olderNeighbor: MessageEntity?,
): Boolean {
    if (olderNeighbor == null) return true
    val sameActor =
        if (current.senderAccount != null && olderNeighbor.senderAccount != null) {
            current.senderAccount == olderNeighbor.senderAccount
        } else {
            current.normalizedActor == olderNeighbor.normalizedActor
        }
    if (!sameActor || olderNeighbor.isSelf != current.isSelf) return true
    // An ACTION (/me) is its own utterance: it always opens a new group on either side of the
    // boundary, so a regular message following an ACTION shows its nick again instead of reading
    // as a continuation of the emote.
    if (current.kind == MessageKind.ACTION || olderNeighbor.kind == MessageKind.ACTION) return true
    if (isSystemKind(olderNeighbor.kind) != isSystemKind(current.kind)) return true
    return current.serverTime - olderNeighbor.serverTime > GROUP_WINDOW_MS
}

/**
 * Vertical gap to render before a bubble row. Reuses [showsSender] so the gap tracks the same-sender
 * grouping window: a burst gap while a group continues, a break gap when a new group opens (sender,
 * direction, or system-kind change, an ACTION boundary, or >[GROUP_WINDOW_MS]). Zero when there is
 * no older neighbor. Non-COMFORTABLE densities get 0 for both tokens, so this is a no-op there.
 */
fun bubbleGap(
    showSender: Boolean,
    hasOlder: Boolean,
    spacing: MotdSpacing,
): Dp {
    if (!hasOlder) return 0.dp
    return if (showSender) spacing.bubbleBreakGap else spacing.bubbleBurstGap
}

/**
 * Reverse-layout message list. Index 0 is the newest message (bottom). For each row we peek the
 * next (older) item to compute grouping, day separators, the read-marker divider, and the history
 * seam. All four are drawn inside the row's own composition, so the item count stays exactly the
 * message count.
 */
@Composable
fun MessageList(
    items: LazyPagingItems<MessageEntity>,
    listState: LazyListState,
    networkId: Long?,
    readMarkerTime: TimelineAnchor?,
    modifier: Modifier = Modifier,
    readMarkerLabel: String? = null,
    onLongPress: (MessageEntity) -> Unit,
    onReply: (MessageEntity) -> Unit,
    // React to a message; the whole entity is passed so a still-pending own row (msgid == null) is
    // queued by the VM instead of silently dropped (bug: react on a just-sent message did nothing).
    onReact: (MessageEntity, String) -> Unit,
    onImageClick: (String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    bufferId: Long? = null,
    conversationName: String? = null,
    directMessage: Boolean = false,
    collapseSystemEvents: Boolean = true,
    canRetry: (MessageEntity) -> Boolean = { true },
    loadPreview: suspend (String, Long?) -> LinkPreview?,
    richContentReady: Boolean,
    showImages: Boolean,
    showLinkPreviews: Boolean,
    onOpenLink: (String) -> Unit,
    cachedPreview: (String, Long?) -> CachedLinkPreview? = { _, _ -> null },
    loadAudioMetadata: suspend (String, Long?) -> AudioMetadata? = { _, _ -> null },
    cachedAudioMetadata: (String) -> CachedAudioMetadata? = { null },
    audioPlaybackState: AudioPlaybackState = AudioPlaybackState(),
    audioWaveforms: Map<String, AudioWaveform> = emptyMap(),
    audioCacheStatuses: Map<String, AudioCacheStatus> = emptyMap(),
    onAudioToggle: (AudioPlaybackRequest) -> Unit = {},
    onAudioCacheInspect: (AudioAttachment) -> Unit = {},
    onAudioSeek: (AudioAttachment, Long) -> Unit = { _, _ -> },
    voiceTranscripts: Map<String, VoiceTranscriptState> = emptyMap(),
    voiceTranscriptionEnabled: Boolean = false,
    voiceTranscriptionReady: Boolean = false,
    onVoiceTranscribe: (AudioPlaybackRequest, Boolean) -> Unit = { _, _ -> },
    onVoiceTranscriptionCancel: (String) -> Unit = {},
    liveEntryIds: Set<Long> = emptySet(),
    onLiveEntryConsumed: (Long) -> Unit = {},
    // The send currently travelling from the composer, if any. Its row is hidden and reports its
    // bounds so the ghost knows where to land.
    outgoingFlight: OutgoingFlight? = null,
    // Rows a ghost has already delivered. Their arrival was the flight, so they must never also
    // play the ordinary live-entrance reveal once the flight is gone.
    flownRowIds: Set<Long> = emptySet(),
    // The flight's progress, read in the layout phase so the landing row's gap opens on the same
    // spring that carries the bubble. A lambda, so the value never enters composition here.
    flightProgress: () -> Float = { 1f },
    onFlightRowPositioned: (Long, Rect) -> Unit = { _, _ -> },
    // The send-flight runway: how far the whole list slides up while a ghost is airborne, read
    // in the draw phase like [flightProgress] so a frame moves one layer and composes nothing.
    listShift: () -> Float = { 0f },
    reactionChips: (String) -> List<ReactionChip> = { emptyList() },
    replyPreview: (String) -> StateFlow<ReplyPreviewData?> = { MutableStateFlow(null) },
    onReplyPreviewClick: (String) -> Unit = {},
    onDelete: (MessageEntity) -> Unit = {},
    highlightMsgid: String? = null,
    dccTransfer: (MessageEntity) -> StateFlow<DccTransferEntity?> = { MutableStateFlow(null) },
    onAcceptDccTransfer: (Long, String, Boolean) -> Unit = { _, _, _ -> },
    onRejectDccTransfer: (Long) -> Unit = {},
    onRemoveDccTransfer: (Long) -> Unit = {},
    // Normalized nicks known in the current buffer (member list). Drives @mention coloring in the
    // message bodies; passed straight through to each MessageBubble.
    knownNicks: Set<String> = emptySet(),
    // Behavioral settings threaded from viewModel.settings. Style-only
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
    // Tapping a non-self sender's name/avatar opens the nick sheet.
    onSenderClick: (String) -> Unit = {},
    onAcceptInvite: (Long) -> Unit = {},
    onDismissInvite: (Long) -> Unit = {},
    // Stored history gaps as rendered seams. Carried like readMarkerTime: a raw value each row kind
    // interprets against its own older neighbor, never a list item of its own (see the items block).
    timelineSeams: TimelineSeamState = TimelineSeamState(),
    onLoadGap: (Long) -> Unit = {},
    highlightEventId: Long? = null,
) {
    val scrolling by remember(listState) { derivedStateOf { listState.isScrollInProgress } }
    // Keep the user's expanded JOIN/PART runs above the volatile Paging rows. A history sync may
    // briefly replace or rechunk those rows, but overlapping event identities remain stable.
    var expandedSystemEventIds by remember(bufferId) { mutableStateOf(emptySet<Long>()) }
    // Scrolling postpones only cache misses. Parsed URLs and resolved previews remain renderable so
    // a recycled row does not lose rich content halfway through a fling.
    val canStartNewRichContentWork = richContentReady && !scrolling
    val formatMessageTime = rememberMessageTimeFormatter()
    // A skeleton the size of the rows this conversation actually renders, so a page landing (which
    // regenerates the PagingSource and turns loaded rows back into placeholders) does not reflow
    // everything below each swap. Deferred read: only the composed skeletons see the change.
    val placeholderHeight = rememberTimelineRowHeight(listState, bufferId)
    LazyColumn(
        state = listState,
        reverseLayout = true,
        // Retained rows can predate messages sent by earlier orchestrated journeys. Keep the
        // timeline addressable so the real-stack acceptance test can scroll to an imported row
        // instead of confusing an off-screen row with a missing one. Scroll-driven paging: reaching
        // the older end triggers Paging APPEND via the prefetch window, no gesture plumbing needed.
        modifier =
            modifier
                .fillMaxSize()
                // The runway: while a sent bubble is airborne the whole timeline yields upward so
                // vacated space exists at the foot before the landing row can open its own gap.
                .graphicsLayer { translationY = -listShift() }
                .testTag("chat_timeline"),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        // Stable keys stop paging invalidations (new message / echo confirm / page load) from
        // re-anchoring the viewport by index and reusing per-row state across messages.
        // Placeholder rows fall back to the position key.
        //
        // A seam is drawn INSIDE the newer row's composition, never as its own item(). An extra item
        // would change items.itemCount and shift itemKey, which would in turn break the
        // "countNewerThan == list index" contract ChatJumpResolver documents, the placeholder math,
        // auto-follow, every jump index, and entryAnchorPagingKey — all at once.
        items(
            count = items.itemCount,
            key = items.itemKey { it.id },
            // Own bubbles, other bubbles, ACTION rows, and retry rows have different composition
            // shapes. Keeping separate pools avoids structural churn at exactly the boundaries that
            // previously produced hitches when a fling crossed own messages.
            contentType = items.itemContentType { messageContentType(it, collapseSystemEvents) },
        ) { index ->
            val msg = items[index]
            if (msg == null) {
                MessagePlaceholderRow(placeholderHeight)
                return@items
            }
            val older = if (index + 1 < items.itemCount) items.peek(index + 1) else null
            val newer = if (index - 1 >= 0) items.peek(index - 1) else null

            // The self-contained card/pill kinds below draw no dividers of their own, so they get
            // the seam through a wrapper rather than as a parameter. Skipping them would hide the
            // break whenever a gap happens to be bounded by an invite, a netsplit, or a transfer.
            if (msg.kind == MessageKind.INVITE) {
                TimelineSeamAbove(rowSeam(msg, older, timelineSeams), onLoadGap) {
                    LiveTimelineEntry(
                        liveEntryIds,
                        msg,
                        onLiveEntryConsumed,
                        outgoingFlight,
                        flownRowIds,
                        flightProgress,
                        onFlightRowPositioned,
                    ) {
                        InvitationCard(
                            message = msg,
                            onJoin = { onAcceptInvite(msg.id) },
                            onDismiss = { onDismissInvite(msg.id) },
                        )
                    }
                }
                return@items
            }

            if (msg.kind == MessageKind.NETSPLIT || msg.kind == MessageKind.NETJOIN) {
                TimelineSeamAbove(rowSeam(msg, older, timelineSeams), onLoadGap) {
                    LiveTimelineEntry(
                        liveEntryIds,
                        msg,
                        onLiveEntryConsumed,
                        outgoingFlight,
                        flownRowIds,
                        flightProgress,
                        onFlightRowPositioned,
                    ) {
                        NetworkBatchPill(msg)
                    }
                }
                return@items
            }

            if (msg.kind == MessageKind.DCC_TRANSFER) {
                TimelineSeamAbove(rowSeam(msg, older, timelineSeams), onLoadGap) {
                    LiveTimelineEntry(
                        liveEntryIds,
                        msg,
                        onLiveEntryConsumed,
                        outgoingFlight,
                        flownRowIds,
                        flightProgress,
                        onFlightRowPositioned,
                    ) {
                        val transferFlow = remember(msg.id) { dccTransfer(msg) }
                        val transfer by transferFlow.collectAsStateWithLifecycle()
                        DccTransferCard(
                            message = msg,
                            transfer = transfer,
                            onAccept = onAcceptDccTransfer,
                            onReject = onRejectDccTransfer,
                            onRemove = onRemoveDccTransfer,
                        )
                    }
                }
                return@items
            }

            // System-event collapse: render one pill on the run's *newest* item and
            // skip the rest. In a reversed list the newest of a contiguous system run is the item
            // whose just-newer neighbor is not a system event.
            if (collapseSystemEvents && isSystemKind(msg.kind)) {
                if (!isSystemRunChunkHead(msg.id, newer?.let { sameSystemRun(msg, it) } == true)) return@items
                LiveTimelineEntry(
                    liveEntryIds,
                    msg,
                    onLiveEntryConsumed,
                    outgoingFlight,
                    flownRowIds,
                    flightProgress,
                    onFlightRowPositioned,
                ) {
                    SystemEventRun(
                        items = items,
                        index = index,
                        newest = msg,
                        readMarkerTime = readMarkerTime,
                        readMarkerLabel = readMarkerLabel,
                        timelineSeams = timelineSeams,
                        onLoadGap = onLoadGap,
                        expandedEventIds = expandedSystemEventIds,
                        onExpandedChange = { runIds, expanded ->
                            expandedSystemEventIds =
                                updateExpandedSystemEvents(
                                    expandedSystemEventIds,
                                    runIds,
                                    expanded,
                                )
                        },
                    )
                }
                return@items
            }

            // Fool COLLAPSE: render a tap-to-expand placeholder in place of the
            // bubble until its id is expanded. HIDE mode is filtered upstream so it never reaches
            // here; system-kind rows are handled above and never fool-treated.
            val isFool =
                foolsMode == FoolsMode.COLLAPSE &&
                    isFoolMessage(msg, fools, identityRules)
            if (isFool && !foolExpanded(msg.id)) {
                LiveTimelineEntry(
                    liveEntryIds,
                    msg,
                    onLiveEntryConsumed,
                    outgoingFlight,
                    flownRowIds,
                    flightProgress,
                    onFlightRowPositioned,
                ) {
                    FoolPlaceholderRow(
                        msg = msg,
                        older = older,
                        readMarkerTime = readMarkerTime,
                        readMarkerLabel = readMarkerLabel,
                        seam = rowSeam(msg, older, timelineSeams),
                        onLoadGap = onLoadGap,
                        onExpand = { onToggleFool(msg.id) },
                    )
                }
                return@items
            }

            // Deep-jump pulse: fade a highlight tint in then back out on the target row (~1.6s).
            val highlighted = messageHighlightMatches(msg, highlightMsgid, highlightEventId)
            // Deep jumps are rare. Do not install an animation state object in every ordinary row;
            // only the single target needs one while the highlight is active.
            val highlightColor =
                if (highlighted) {
                    val pulse = remember(msg.id, highlightMsgid, highlightEventId) { Animatable(0f) }
                    LaunchedEffect(msg.id, highlightMsgid, highlightEventId) {
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
                        bufferId = bufferId,
                        conversationName = conversationName,
                        directMessage = directMessage,
                        fallbackSender = conversationName.takeUnless { collapseSystemEvents },
                        older = older,
                        formatTime = formatMessageTime,
                        readMarkerTime = readMarkerTime,
                        readMarkerLabel = readMarkerLabel,
                        seam = rowSeam(msg, older, timelineSeams),
                        onLoadGap = onLoadGap,
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
                        loadAudioMetadata = loadAudioMetadata,
                        cachedAudioMetadata = cachedAudioMetadata,
                        audioPlaybackState = audioPlaybackState,
                        audioWaveforms = audioWaveforms,
                        audioCacheStatuses = audioCacheStatuses,
                        onAudioToggle = onAudioToggle,
                        onAudioCacheInspect = onAudioCacheInspect,
                        onAudioSeek = onAudioSeek,
                        voiceTranscripts = voiceTranscripts,
                        voiceTranscriptionEnabled = voiceTranscriptionEnabled,
                        voiceTranscriptionReady = voiceTranscriptionReady,
                        onVoiceTranscribe = onVoiceTranscribe,
                        onVoiceTranscriptionCancel = onVoiceTranscriptionCancel,
                        onOpenLink = onOpenLink,
                        onSenderClick = onSenderClick,
                        replyPreview = replyPreview,
                        onReplyPreviewClick = onReplyPreviewClick,
                    )
                }
            }
            LiveTimelineEntry(
                liveEntryIds,
                msg,
                onLiveEntryConsumed,
                outgoingFlight,
                flownRowIds,
                flightProgress,
                onFlightRowPositioned,
                rowContent,
            )
        }

        // Append spinner / end-of-history / error affordances. This item sits at the
        // top of the reversed list, i.e. visually above the oldest message where APPEND loads more.
        item(key = "append-state", contentType = "loadstate") {
            ChatHistoryFooter(
                // Debounced here, at the one existing item: PagingSource generation churn flicks the
                // raw state within a few frames and no new list item may be added for it (the jump
                // and placeholder math both count on this itemCount).
                state = rememberFooterUiState(historyUiState),
                onRetry = {
                    onHistoryRetry()
                    items.retry()
                },
            )
        }
    }
}

@Composable
private fun DccTransferCard(
    message: MessageEntity,
    transfer: DccTransferEntity?,
    onAccept: (Long, String, Boolean) -> Unit,
    onReject: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    // Latch the outgoing entity: by the time the card collapses into the compact pill the entity
    // is already gone, and the exiting frames must keep rendering the last real content.
    var lastTransfer by remember { mutableStateOf(transfer) }
    if (transfer != null) lastTransfer = transfer
    AnimatedContent(
        targetState = transfer != null,
        transitionSpec = { cardPillTransform() },
        label = "dcc_render",
    ) { hasTransfer ->
        val entity = lastTransfer
        if (!hasTransfer || entity == null) {
            SystemEventPill(
                summary = message.text,
                lineCount = 1,
                loadLines = { listOf(message.text) },
                contentKey = message.id,
                modifier = Modifier.testTag("chat_dcc_transfer_compact_${message.id}"),
            )
        } else {
            // An exiting card keeps composing through the card->pill collapse; its actions must
            // not fire against the already-retired transfer.
            val current = transfer != null
            ActiveDccTransferCard(
                entity,
                onAccept = { id, name, resume -> if (current) onAccept(id, name, resume) },
                onReject = { id -> if (current) onReject(id) },
                onRemove = { id -> if (current) onRemove(id) },
            )
        }
    }
}

@Composable
private fun ActiveDccTransferCard(
    transfer: DccTransferEntity,
    onAccept: (Long, String, Boolean) -> Unit,
    onReject: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val privateRisk =
        remember(transfer.address, transfer.addressKind) {
            runCatching { dccEndpointRisk(resolveDccAddress(transfer.address, transfer.addressKind)) }
                .getOrDefault(DccEndpointRisk.UNSPECIFIED)
                .takeIf { it != DccEndpointRisk.PUBLIC }
        }
    val progress =
        transfer.sizeBytes?.takeIf { it > 0 }?.let { size ->
            (transfer.bytesTransferred.toFloat() / size.toFloat()).coerceIn(0f, 1f)
        }
    val direction = if (transfer.direction == DccDirection.INCOMING) "Incoming" else "Outgoing"
    val protocol =
        when (transfer.protocol) {
            DccTransferProtocol.SEND -> "Plain DCC SEND"
            DccTransferProtocol.SSEND -> "Secure DCC SSEND · identity unverified"
        }
    val status = dccStatusText(transfer)
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("chat_dcc_transfer_${transfer.id}"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "$direction file",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(transfer.displayFilename, style = MaterialTheme.typography.titleMedium)
            Text(
                text =
                    listOfNotNull(
                        transfer.sizeBytes?.let(::formatDccBytes),
                        protocol,
                        status,
                    ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (privateRisk != null) {
                Text(
                    text = "Private or local endpoint: ${privateRisk.name.lowercase().replace(
                        '_',
                        ' ',
                    )}. Allow only if you trust this peer and network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            progress?.let {
                LinearProgressIndicator(
                    progress = { it },
                    modifier = Modifier.fillMaxWidth().testTag("chat_dcc_progress_${transfer.id}"),
                )
                Text(
                    text = "${formatDccBytes(transfer.bytesTransferred)} / ${formatDccBytes(transfer.sizeBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DccTransferActions(transfer, privateRisk, onAccept, onReject, onRemove)
        }
    }
}

@Composable
private fun DccTransferActions(
    transfer: DccTransferEntity,
    privateRisk: DccEndpointRisk?,
    onAccept: (Long, String, Boolean) -> Unit,
    onReject: (Long) -> Unit,
    onRemove: (Long) -> Unit,
) {
    val incoming = transfer.direction == DccDirection.INCOMING
    val canAccept =
        incoming && transfer.state in
            setOf(
                DccTransferState.OFFERED,
                DccTransferState.PARTIAL,
                DccTransferState.FAILED,
            )
    val terminal =
        transfer.state in
            setOf(
                DccTransferState.COMPLETED,
                DccTransferState.REJECTED,
                DccTransferState.EXPIRED,
                DccTransferState.FAILED,
                DccTransferState.REMOVED,
            )
    if (!canAccept && !terminal) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (canAccept) {
            Button(
                onClick = {
                    onAccept(transfer.id, transfer.displayFilename, privateRisk != null)
                },
                modifier = Modifier.testTag("chat_dcc_accept_${transfer.id}"),
            ) {
                Text(if (privateRisk == null) "Save" else "Allow once & save")
            }
            OutlinedButton(
                onClick = { onReject(transfer.id) },
                modifier = Modifier.testTag("chat_dcc_reject_${transfer.id}"),
            ) {
                Text("Reject")
            }
        }
        if (terminal) {
            TextButton(
                onClick = { onRemove(transfer.id) },
                modifier = Modifier.testTag("chat_dcc_remove_${transfer.id}"),
            ) {
                Text("Remove record")
            }
        }
    }
}

private fun dccStatusText(transfer: DccTransferEntity): String =
    when (transfer.state) {
        DccTransferState.OFFERED -> "Waiting"
        DccTransferState.ACCEPTING -> "Starting"
        DccTransferState.ACTIVE -> "Transferring"
        DccTransferState.PARTIAL -> "Partial"
        DccTransferState.COMPLETED -> "Complete"
        DccTransferState.FAILED -> transfer.error?.let { "Failed: $it" } ?: "Failed"
        DccTransferState.REJECTED -> "Rejected"
        DccTransferState.EXPIRED -> "Expired"
        DccTransferState.REMOVED -> "Removed"
    }

private fun formatDccBytes(bytes: Long): String {
    val units = listOf("B", "KiB", "MiB", "GiB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "$bytes ${units[unit]}" else "%.1f %s".format(value, units[unit])
}

/**
 * Draws the seam belonging to one row's slot, or nothing at all.
 *
 * [seam] is resolved by [rowSeam] at each call site against the SAME older neighbor that site uses
 * for its read-marker divider, so the seam and the read marker can never disagree about where the
 * slot is.
 */
@Composable
private fun TimelineSeamDivider(
    seam: RowSeam?,
    onLoadGap: (Long) -> Unit,
) {
    if (seam == null) return
    HistoryGapDivider(state = seam.state, onLoad = { onLoadGap(seam.gapId) })
}

/**
 * Seam wrapper for the row kinds that render a self-contained card or pill and draw no dividers of
 * their own (INVITE, NETSPLIT/NETJOIN, DCC_TRANSFER).
 *
 * The Column only appears when there IS a seam: with none, the row composes exactly as before, so
 * these kinds keep their current layout on the overwhelmingly common path.
 */
@Composable
private fun TimelineSeamAbove(
    seam: RowSeam?,
    onLoadGap: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    if (seam == null) {
        content()
        return
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        TimelineSeamDivider(seam, onLoadGap)
        content()
    }
}

/**
 * A quiet skeleton, sized like the rows around it.
 *
 * Nonzero height stops a placeholder-only page from measuring as zero rows; a height that tracks the
 * conversation's real rows ([rememberTimelineRowHeight]) stops each skeleton -> real swap from
 * reflowing the list below it. [height] is a lambda so the estimate is read inside this composable:
 * a new estimate then invalidates the composed skeletons rather than the whole timeline.
 */
@Composable
internal fun MessagePlaceholderRow(height: () -> Dp = { DEFAULT_TIMELINE_ROW_HEIGHT }) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(height())
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
    liveEntryIds: Set<Long>,
    message: MessageEntity,
    onConsumed: (Long) -> Unit,
    outgoingFlight: OutgoingFlight?,
    flownRowIds: Set<Long>,
    flightProgress: () -> Float,
    onFlightRowPositioned: (Long, Rect) -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        // A send flight owns this row's entrance: the bubble travelling from the composer *is* the
        // animation, so the row waits underneath it rather than revealing itself as well. Checked
        // before [flownRowIds], which this very row joins the moment it first reports its bounds.
        outgoingFlight?.matches(message) == true -> {
            FlightLandingEntry(message.id, flightProgress, onFlightRowPositioned, content)
        }

        // Already delivered by a ghost. Its entrance is spent, so it renders plainly forever after
        // even though the live-entrance set still names it.
        message.id in flownRowIds -> {
            content()
        }

        message.id in liveEntryIds -> {
            LiveMessageEntry(messageId = message.id, onConsumed = onConsumed, content = content)
        }

        else -> {
            content()
        }
    }
}

/**
 * The row a send flight lands on: the gap it will occupy, opening on the flight's own spring.
 *
 * It reveals from the bottom exactly as [LiveMessageEntry] does, so older rows glide up by the
 * amount the arriving bubble is descending into -- the conversation makes room *with* the bubble
 * instead of jumping open a full row height the instant Room inserts it. The row itself stays
 * transparent because the ghost is drawing those pixels; it is the space that animates here.
 *
 * The progress read happens in the layout lambda, like [LiveMessageEntry]'s, so a frame invalidates
 * this one row's layout and nothing recomposes.
 */
@Composable
private fun FlightLandingEntry(
    messageId: Long,
    progress: () -> Float,
    onPositioned: (Long, Rect) -> Unit,
    content: @Composable () -> Unit,
) {
    val latestOnPositioned = rememberUpdatedState(onPositioned)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    // Clamped, so the spring's overshoot stays on the bubble alone: the gap opens to
                    // exactly one row and the bubble settles back into it.
                    val revealedHeight =
                        (placeable.height * progress().coerceIn(0f, 1f))
                            .toInt()
                            .coerceIn(0, placeable.height)
                    layout(placeable.width, revealedHeight) {
                        placeable.placeRelative(0, revealedHeight - placeable.height)
                    }
                }.graphicsLayer { alpha = 0f }
                .onGloballyPositioned { latestOnPositioned.value(messageId, it.boundsInWindow()) },
    ) {
        content()
    }
}

/**
 * One-shot live-message entrance. The row reveals upward from the bottom so older messages move by
 * the same smooth amount as the new row grows. The content subtree remains mounted after completion
 * so reply, preview, and audio state cannot restart at the end of the animation.
 */
@Composable
private fun LiveMessageEntry(
    messageId: Long,
    onConsumed: (Long) -> Unit,
    content: @Composable () -> Unit,
) {
    val reveal = remember(messageId) { Animatable(0f) }
    var complete by remember(messageId) { mutableStateOf(false) }
    val latestOnConsumed = rememberUpdatedState(onConsumed)

    DisposableEffect(messageId) {
        onDispose { latestOnConsumed.value(messageId) }
    }

    LaunchedEffect(messageId) {
        reveal.animateTo(1f, MotdMotion.fadeIn)
        complete = true
    }

    val motion =
        if (complete) {
            Modifier
        } else {
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val revealedHeight =
                        (placeable.height * reveal.value)
                            .toInt()
                            .coerceIn(0, placeable.height)
                    layout(placeable.width, revealedHeight) {
                        // Bottom alignment makes the bubble grow into the conversation instead of
                        // sliding its full height over the composer.
                        placeable.placeRelative(0, revealedHeight - placeable.height)
                    }
                }.graphicsLayer { alpha = reveal.value }
        }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .clipToBounds()
                .then(motion),
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
    val summary =
        "${payload.nicks.size} ${if (payload.nicks.size == 1) "user" else "users"} $action " +
            "(${payload.serverA} ↔ ${payload.serverB})"
    SystemEventPill(
        summary = summary,
        lineCount = payload.nicks.size,
        loadLines = { payload.nicks },
        contentKey = message.id,
        modifier = Modifier.testTag("chat_network_batch_${message.kind.name.lowercase()}_${message.id}"),
    )
}

/**
 * Card <-> pill swap for the self-contained timeline rows: micro crossfade with the eased height
 * collapse. Single-shot and state-driven, so it stays within the no-decorative-timeline rule.
 */
private fun AnimatedContentTransitionScope<*>.cardPillTransform(): ContentTransform =
    (fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut))
        .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> MotdMotion.contentSize }))

/**
 * Value form of [InvitationCard]'s render fork. Each variant carries the text it renders so the
 * exiting frame keeps its old content while the replacement fades in.
 */
private sealed interface InviteRender {
    data class Pill(
        val summary: String,
        val testTag: String,
    ) : InviteRender

    data class ActiveCard(
        val channel: String,
        val state: InviteState,
    ) : InviteRender
}

@Composable
private fun InvitationCard(
    message: MessageEntity,
    onJoin: () -> Unit,
    onDismiss: () -> Unit,
) {
    val payload = remember(message.eventPayload) { InvitePayloadV1.decode(message.eventPayload) }
    val state = message.inviteState
    val render =
        when {
            payload == null || state == null || state == InviteState.HISTORICAL -> {
                InviteRender.Pill(
                    summary = message.text,
                    testTag = "chat_invite_compact_${message.id}",
                )
            }

            state == InviteState.JOINED || state == InviteState.DISMISSED -> {
                InviteRender.Pill(
                    summary = "${if (state == InviteState.JOINED) "Joined" else "Dismissed"} ${payload.channel}",
                    testTag = "chat_invite_resolved_${message.id}",
                )
            }

            else -> {
                InviteRender.ActiveCard(channel = payload.channel, state = state)
            }
        }
    AnimatedContent(
        targetState = render,
        transitionSpec = { cardPillTransform() },
        // Pill-to-pill changes (resolved -> historical) swap in place; only the card <-> pill
        // height collapse animates.
        contentKey = { it is InviteRender.ActiveCard },
        label = "invite_render",
    ) { target ->
        when (target) {
            is InviteRender.Pill -> {
                SystemEventPill(
                    summary = target.summary,
                    lineCount = 1,
                    loadLines = { listOf(message.text) },
                    contentKey = message.id,
                    modifier = Modifier.testTag(target.testTag),
                )
            }

            is InviteRender.ActiveCard -> {
                Card(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("chat_invite_card_${message.id}"),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Invitation to ${target.channel}", style = MaterialTheme.typography.titleMedium)
                        Text(message.text, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                        if (target.state == InviteState.FAILED) {
                            Text("Could not join. You can retry.", color = MaterialTheme.colorScheme.error)
                        }
                        // An exiting card keeps composing through the card->pill collapse; its
                        // buttons must not fire against the already-resolved invite.
                        val current = target == render
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = onJoin,
                                enabled = current && target.state != InviteState.JOINING,
                                modifier = Modifier.testTag("chat_invite_join_${message.id}"),
                            ) {
                                Text(if (target.state == InviteState.JOINING) "Joining…" else "Join")
                            }
                            OutlinedButton(
                                onClick = onDismiss,
                                enabled = current,
                                modifier = Modifier.testTag("chat_invite_dismiss_${message.id}"),
                            ) {
                                Text("Dismiss")
                            }
                        }
                    }
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
    readMarkerLabel: String?,
    timelineSeams: TimelineSeamState,
    onLoadGap: (Long) -> Unit,
    expandedEventIds: Set<Long>,
    onExpandedChange: (Collection<Long>, Boolean) -> Unit,
) {
    // Gather exactly one chunk: newest first (index), then older neighbors while still system events
    // and not themselves a boundary — a boundary row heads the next chunk, so stopping there is what
    // guarantees every event is rendered by one and only one head. Chunk length is geometric with
    // mean MAX_COLLAPSED_SYSTEM_EVENTS, and the walk terminates at the loaded window edge regardless.
    val run = ArrayList<MessageEntity>()
    run.add(newest)
    var i = index + 1
    while (i < items.itemCount) {
        val m = items.peek(i) ?: break
        if (!sameSystemRun(newest, m) || isSystemRunChunkBoundary(m.id)) break
        run.add(m)
        i++
    }
    val oldest = run.last()
    val runIds = run.map { it.id }
    val olderThanRun = if (index + run.size < items.itemCount) items.peek(index + run.size) else null

    val commandResponse = commandResponseGroup(newest) != null
    val summary =
        if (commandResponse) {
            summarizeCommandResponse(run)
        } else if (run.size == 1) {
            newest.text
        } else {
            summarizeSystemRun(run)
        }

    // Divider before the run when the run's newest crosses the marker and its older neighbor doesn't.
    val showNewDivider =
        readMarkerTime != null &&
            newest.timelineAnchor() > readMarkerTime &&
            (olderThanRun == null || olderThanRun.timelineAnchor() <= readMarkerTime)
    val showDay =
        remember(oldest.serverTime, olderThanRun?.serverTime) {
            olderThanRun == null || dayStart(oldest.serverTime) != dayStart(olderThanRun.serverTime)
        }
    // The whole run occupies one slot, so its seam is bracketed by the run's newest row and the row
    // just older than the ENTIRE run — the same pair the read-marker divider above is derived from.
    // Anything else would let a seam falling inside the run disappear with the collapsed rows.
    val seam = rowSeam(newest, olderThanRun, timelineSeams)

    // Column so the pill and any dividers stack vertically. A bare item slot stacks siblings on top
    // of each other (its MeasurePolicy behaves like a Box), which would overlap the divider text.
    Column(modifier = Modifier.fillMaxWidth()) {
        TimelineSeamDivider(seam, onLoadGap)
        if (showDay) DaySeparator(timeMs = oldest.serverTime)
        if (showNewDivider) {
            NewMessagesDivider(
                label = readMarkerLabel ?: stringResource(R.string.chat_new_messages),
                modifier = Modifier.testTag("chat_read_marker_divider"),
            )
        }
        SystemEventPill(
            summary = summary,
            lineCount = run.size,
            loadLines = { systemRunPresentationLines(run) },
            contentKey = SystemRunContentKey(newest.id, oldest.id, run.size),
            expanded = systemRunExpanded(runIds, expandedEventIds),
            onExpandedChange = { expanded -> onExpandedChange(runIds, expanded) },
            forceCollapsible = commandResponse,
            modifier = Modifier.testTag("chat_system_pill"),
        )
    }
}

/**
 * SplitMix64 finalizer. Ids are a monotonic autoincrement shared by every room, so a buffer's rows
 * can land on an arithmetic progression; avalanching first keeps boundary placement independent of
 * that periodicity, and keeps it deterministic for tests.
 */
private fun mixEventIdentity(id: Long): Long {
    var z = id + -0x61c8864680b583ebL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    return z xor (z ushr 31)
}

/**
 * Chunk boundaries are a property of the row itself, never of where Paging happens to present it.
 * Absolute-index boundaries (`index % 24 == 0`) re-sliced every long presence run each time a newer
 * row landed: a catch-up burst shifts every index, so already-rendered pills changed membership,
 * summary, and content key on every invalidation — the "show all" redraw flicker.
 */
internal fun isSystemRunChunkBoundary(id: Long): Boolean = (mixEventIdentity(id) ushr 1) % MAX_COLLAPSED_SYSTEM_EVENTS == 0L

/**
 * A run begins at its newest row and at each identity boundary inside it. Still O(1) per suppressed
 * row: no neighbor walk while flinging, and each event belongs to exactly one head.
 */
internal fun isSystemRunChunkHead(
    id: Long,
    newerIsSystem: Boolean,
): Boolean = !newerIsSystem || isSystemRunChunkBoundary(id)

private fun summarizeCommandResponse(run: List<MessageEntity>): String {
    val replies = run.count { it.kind != MessageKind.ERROR }
    val errors = run.size - replies
    val counts =
        listOfNotNull(
            replies.takeIf { it > 0 }?.let { "$it ${if (it == 1) "reply" else "replies"}" },
            errors.takeIf { it > 0 }?.let { "$it ${if (it == 1) "error" else "errors"}" },
        ).joinToString(" · ")
    return "${run.first().sender} · $counts"
}

/**
 * Summarize a run of system events by kind: JOIN → "joined", PART/QUIT → "left", others by kind
 * name. Produces "3 joined · 1 left" style text. Counts are grouped preserving first appearance.
 */
internal fun summarizeSystemRun(run: List<MessageEntity>): String {
    val counts = LinkedHashMap<String, Int>()
    for (m in run) {
        val label =
            when (m.kind) {
                MessageKind.JOIN -> "joined"
                MessageKind.PART, MessageKind.QUIT -> "left"
                MessageKind.KICK -> "kicked"
                MessageKind.NICK -> "renamed"
                MessageKind.AWAY -> "away"
                MessageKind.BACK -> "back"
                MessageKind.MODE -> "mode"
                MessageKind.TOPIC -> "topic"
                else -> "events"
            }
        counts[label] = (counts[label] ?: 0) + 1
    }
    return counts.entries.joinToString(" · ") { (label, n) -> "$n $label" }
}

/** Completion-tracked link-preview state; transient failures remain explicitly retryable. */
private sealed interface PreviewState {
    data object Awaiting : PreviewState

    data object Loading : PreviewState

    data object Failed : PreviewState

    data class Done(
        val preview: LinkPreview?,
    ) : PreviewState
}

@Composable
private fun MessageRow(
    msg: MessageEntity,
    networkId: Long?,
    bufferId: Long?,
    conversationName: String?,
    directMessage: Boolean,
    fallbackSender: String?,
    older: MessageEntity?,
    formatTime: (Long) -> String,
    readMarkerTime: TimelineAnchor?,
    readMarkerLabel: String?,
    seam: RowSeam?,
    onLoadGap: (Long) -> Unit,
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
    loadPreview: suspend (String, Long?) -> LinkPreview?,
    showImages: Boolean,
    showLinkPreviews: Boolean,
    canStartNewRichContentWork: Boolean,
    cachedPreview: (String, Long?) -> CachedLinkPreview?,
    loadAudioMetadata: suspend (String, Long?) -> AudioMetadata?,
    cachedAudioMetadata: (String) -> CachedAudioMetadata?,
    audioPlaybackState: AudioPlaybackState,
    audioWaveforms: Map<String, AudioWaveform>,
    audioCacheStatuses: Map<String, AudioCacheStatus>,
    onAudioToggle: (AudioPlaybackRequest) -> Unit,
    onAudioCacheInspect: (AudioAttachment) -> Unit,
    onAudioSeek: (AudioAttachment, Long) -> Unit,
    voiceTranscripts: Map<String, VoiceTranscriptState>,
    voiceTranscriptionEnabled: Boolean,
    voiceTranscriptionReady: Boolean,
    onVoiceTranscribe: (AudioPlaybackRequest, Boolean) -> Unit,
    onVoiceTranscriptionCancel: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onSenderClick: (String) -> Unit,
    replyPreview: (String) -> StateFlow<ReplyPreviewData?>,
    onReplyPreviewClick: (String) -> Unit,
    // Non-null for an expanded fool row: renders a "hide" chip above the bubble that re-collapses it.
    onCollapseFool: (() -> Unit)? = null,
) {
    // The lazy list reverses item order, not a row's children. Render the divider before the first
    // unread bubble so the boundary is visually above that message.
    //
    // A null [older] means two different things in this function, and both are correct. The unread
    // and day rules below treat it as a REAL EDGE and draw: each is derived from this row's own
    // anchor, so an unloaded neighbor cannot change the answer — at worst the divider is redrawn
    // identically once the neighbor materializes. The seam ([rowSeam] → `seamAbove`) treats it as
    // UNKNOWN and abstains: a seam's slot is defined by the pair, so with no lower end the placement
    // is genuinely undecidable and a guess would visibly jump when the placeholder loads. Do not
    // unify them.
    val showNewDivider =
        readMarkerTime != null &&
            msg.timelineAnchor() > readMarkerTime &&
            (older == null || older.timelineAnchor() <= readMarkerTime)

    // Day separator when this message starts a new day relative to the older neighbor.
    val showDay =
        remember(msg.serverTime, older?.serverTime) {
            older == null || dayStart(msg.serverTime) != dayStart(older.serverTime)
        }

    // Telegram-style inter-bubble gap (COMFORTABLE only): a small burst gap while a same-sender
    // group continues, a larger break gap when a new group opens. Hoisted so the same value feeds
    // both the gap spacer and the bubble's grouped-corner/header logic below.
    val spacing = LocalSpacing.current
    val showSender = showsSender(msg, older)
    val displaySender = msg.sender.ifBlank { fallbackSender.orEmpty() }
    val gap = bubbleGap(showSender, older != null, spacing)

    // Outermost boundary of the row: the history break comes before the read marker, because the
    // messages the marker separates all sit on this side of the gap.
    TimelineSeamDivider(seam, onLoadGap)

    // [showDay] is the boundary between this row and its OLDER neighbour, which the reversed list
    // draws ABOVE this one, so the chip belongs before the bubble like every other divider here.
    // Emitting it last put it below the bubble, i.e. against the newer neighbour, one row too low.
    if (showDay) DaySeparator(timeMs = msg.serverTime)

    if (showNewDivider) {
        NewMessagesDivider(
            label = readMarkerLabel ?: stringResource(R.string.chat_new_messages),
            modifier = Modifier.testTag("chat_read_marker_divider"),
        )
    }

    // A row asks Room for its reply target only while it is composed. This avoids timeline-wide
    // loaded-window scans during fast traversal; collection is lifecycle-cancelled off-screen.
    val resolvedReply: ReplyPreviewData? =
        if (msg.replyToMsgid != null) {
            val replyFlow = remember(msg.replyToMsgid) { replyPreview(msg.replyToMsgid) }
            val resolved by replyFlow.collectAsStateWithLifecycle()
            resolved
        } else {
            null
        }
    // A reply relationship remains visible even if its parent is not in local history yet. The
    // reactive lookup above replaces this marker as soon as echo confirmation or history inserts
    // the referenced msgid.
    val reply =
        resolvedReply ?: msg.replyToMsgid?.let {
            ReplyPreviewData(
                sender = stringResource(R.string.chat_action_reply),
                text = stringResource(R.string.chat_reply_target_unavailable),
            )
        }

    // URL discovery is unnecessary for the overwhelming majority of IRC lines. Completed parses
    // come from a bounded process cache first; only a genuine miss waits for the fling to settle.
    val mayContainUrl =
        remember(msg.text) {
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
    val immediateAudio = visibleUrls?.audio.orEmpty()
    val automaticRemoteMedia = LocalAutomaticRemoteMedia.current
    var manualInlineMediaConsent by rememberSaveable(msg.id, imageUrl) { mutableStateOf(false) }
    var manualLinkMediaConsent by rememberSaveable(msg.id, linkUrl) { mutableStateOf(false) }
    val linkMediaAllowed = automaticRemoteMedia || manualLinkMediaConsent
    val inlineMediaConsent =
        remember(msg.id, imageUrl, manualInlineMediaConsent) {
            RemoteMediaConsent(manualInlineMediaConsent) { manualInlineMediaConsent = true }
        }
    val linkMediaConsent =
        remember(msg.id, linkUrl, manualLinkMediaConsent) {
            RemoteMediaConsent(manualLinkMediaConsent) { manualLinkMediaConsent = true }
        }
    val headCandidates =
        remember(msg.id, msg.text, showLinkPreviews) {
            if (showLinkPreviews) extensionlessAudioCandidates(msg.text) else emptyList()
        }
    var headAudio by remember(msg.id, headCandidates) {
        mutableStateOf(
            headCandidates.mapNotNull { cachedAudioMetadata(it)?.metadata?.toAttachment() },
        )
    }
    val latestCachedAudioMetadata by rememberUpdatedState(cachedAudioMetadata)
    val latestLoadAudioMetadata by rememberUpdatedState(loadAudioMetadata)
    LaunchedEffect(msg.id, headCandidates, networkId, linkMediaAllowed) {
        if (headCandidates.isEmpty() || !linkMediaAllowed) return@LaunchedEffect
        snapshotFlow { latestCanStartNewRichContentWork }.first { it }
        val resolved =
            headCandidates
                .take(8)
                .mapNotNull { url ->
                    val cached = latestCachedAudioMetadata(url)
                    if (cached != null) {
                        cached.metadata
                    } else {
                        try {
                            latestLoadAudioMetadata(url, networkId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                }.map { it.toAttachment() }
        headAudio = resolved
    }
    val audioAttachments =
        remember(immediateAudio, headAudio) {
            (immediateAudio + headAudio).distinctBy { it.url }
        }
    val messageText =
        remember(msg.text, audioAttachments) {
            displayTextForAudioMessage(msg.text, audioAttachments)
        }
    val renderedMessageText =
        if (messageText == msg.text) msg.ircFormattedText ?: messageText else messageText
    val standaloneVoice =
        audioAttachments.size == 1 && audioAttachments.single().voice &&
            messageText.isBlank() && reply == null

    // A cached completion is rendered synchronously even while scrolling. A cache miss waits for
    // idle, then joins the repository's process-owned single-flight fetch. Null is a definitive
    // negative result, while transient failures render an explicit tap-to-retry state.
    // Keyed on networkId like the audio HEAD effect: the network identity selects the proxy route,
    // and a fetch made before it is known fails closed without poisoning a later retry.
    val initialCachedPreview = linkUrl?.let { cachedPreview(it, networkId) }
    var previewState by remember(msg.id, linkUrl, networkId) {
        mutableStateOf<PreviewState>(initialCachedPreview?.let { PreviewState.Done(it.preview) } ?: PreviewState.Awaiting)
    }
    var previewRetryToken by remember(msg.id, linkUrl, networkId) { mutableIntStateOf(0) }
    val latestCachedPreview by rememberUpdatedState(cachedPreview)
    LaunchedEffect(msg.id, linkUrl, networkId, linkMediaAllowed, previewRetryToken) {
        val url = linkUrl ?: return@LaunchedEffect
        if (previewState !is PreviewState.Awaiting || !linkMediaAllowed) return@LaunchedEffect
        latestCachedPreview(url, networkId)?.let {
            previewState = PreviewState.Done(it.preview)
            return@LaunchedEffect
        }
        snapshotFlow { latestCanStartNewRichContentWork }.first { it }
        if (previewState !is PreviewState.Awaiting) return@LaunchedEffect
        latestCachedPreview(url, networkId)?.let {
            previewState = PreviewState.Done(it.preview)
            return@LaunchedEffect
        }
        previewState = PreviewState.Loading
        val preview =
            try {
                loadPreview(url, networkId)
            } catch (cancelled: CancellationException) {
                previewState = PreviewState.Awaiting
                throw cancelled
            } catch (_: Exception) {
                previewState = PreviewState.Failed
                return@LaunchedEffect
            }
        previewState = PreviewState.Done(preview)
    }
    val preview = (previewState as? PreviewState.Done)?.preview?.withImageGate(showImages)
    val previewLoading = linkUrl != null && previewState is PreviewState.Loading
    val previewAwaiting = linkUrl != null && previewState is PreviewState.Awaiting
    val previewFailed = linkUrl != null && previewState is PreviewState.Failed
    val previewResolved = linkUrl != null && (previewState is PreviewState.Done || previewFailed)
    val formattedTime = remember(msg.serverTime, formatTime) { formatTime(msg.serverTime) }
    // Ordinary rows stay on the hot scrolling path without even resolving the accessibility
    // string; mention state is immutable for a stored row and only the sparse highlighted rows
    // need it.
    val mentionDescription =
        if (msg.hasMention && !msg.isSelf) {
            stringResource(R.string.chat_message_mentions_you)
        } else {
            null
        }

    // Gap sits on the older-neighbor side (before the bubble) so it separates this row from the
    // previous burst; day separators and read markers live on the newer side (after the bubble), so
    // the two never stack. 0.dp (no older neighbor, or non-COMFORTABLE) collapses the spacer in
    // place — Compose skips zero-height spacers in measurement.
    if (gap > 0.dp) Spacer(Modifier.height(gap))

    onCollapseFool?.let { FoolCollapseChip(sender = msg.sender, tag = foolCollapseTag(msg.msgid, msg.id), onCollapse = it) }

    SwipeToReplyContainer(
        // Keep the stable automation id and mention state on one semantics node. SwipeToReply adds
        // its custom action downstream without changing this message-level accessibility state.
        modifier =
            Modifier.semantics {
                semanticsTestTag = messageTag(msg)
                mentionDescription?.let { stateDescription = it }
            },
        onReply = { onReply(msg) },
    ) { rowModifier ->
        Column(modifier = rowModifier.fillMaxWidth()) {
            val messageBubble: @Composable () -> Unit = {
                CompositionLocalProvider(
                    LocalInlineMediaConsent provides inlineMediaConsent,
                    LocalLinkMediaConsent provides linkMediaConsent,
                    LocalLinkPreviewAwaiting provides previewAwaiting,
                    LocalLinkPreviewFailed provides previewFailed,
                ) {
                    MessageBubble(
                        // Per-message handle for long-press/react/reply/deep-jump. Prefer the stable
                        // server msgid; pending rows fall back to the local id for E2E selection.
                        modifier = Modifier,
                        sender = displaySender,
                        networkId = networkId,
                        senderAccount = msg.senderAccount,
                        text = renderedMessageText,
                        timeMs = msg.serverTime,
                        formattedTime = formattedTime,
                        isSelf = msg.isSelf,
                        isBot = msg.isBot,
                        kind = msg.kind,
                        showSender = showSender,
                        hasMention = msg.hasMention,
                        senderIsFriend = senderIsFriend,
                        failed = msg.failed,
                        // Subtle "sending…" state before the 30s failure flip.
                        pending = msg.pendingLabel != null,
                        reply = reply,
                        onReplyClick =
                            if (resolvedReply != null) {
                                msg.replyToMsgid?.let { parentMsgid -> { onReplyPreviewClick(parentMsgid) } }
                            } else {
                                null
                            },
                        imageUrl = imageUrl,
                        linkPreview = preview,
                        linkPreviewLoading = previewLoading,
                        linkPreviewResolved = previewResolved || previewAwaiting,
                        reactions = reactions,
                        knownNicks = knownNicks,
                        identityRules = identityRules,
                        onLongPress = { onLongPress(msg) },
                        // Pass the entity, not just msgid: the VM handles pending reactions uniformly.
                        onReact = { emoji -> onReact(msg, emoji) },
                        onImageClick = onImageClick,
                        onLinkPreviewClick = {
                            when (previewState) {
                                PreviewState.Awaiting -> {
                                    linkMediaConsent.grant()
                                }

                                PreviewState.Failed -> {
                                    previewState = PreviewState.Awaiting
                                    previewRetryToken++
                                }

                                else -> {
                                    linkUrl?.let(onOpenLink)
                                }
                            }
                        },
                        // Only non-self senders open the nick sheet.
                        onSenderClick = if (msg.isSelf) null else ({ onSenderClick(msg.sender) }),
                    )
                }
            }
            if (headCandidates.isNotEmpty()) {
                // An extensionless URL may resolve into a standalone voice message after HEAD
                // metadata arrives. Shrink the provisional bubble while the player grows.
                AnimatedVisibility(
                    visible = !standaloneVoice,
                    enter =
                        expandVertically(
                            animationSpec = MotdMotion.contentSize,
                            expandFrom = Alignment.Bottom,
                        ) + fadeIn(MotdMotion.microFadeIn),
                    exit =
                        shrinkVertically(
                            animationSpec = MotdMotion.contentSize,
                            shrinkTowards = Alignment.Bottom,
                        ) + fadeOut(MotdMotion.microFadeOut),
                ) {
                    messageBubble()
                }
            } else if (!standaloneVoice) {
                messageBubble()
            }
            val audioOrigin =
                if (
                    audioAttachments.isNotEmpty() &&
                    bufferId != null &&
                    networkId != null &&
                    conversationName != null
                ) {
                    AudioPlaybackOrigin(
                        bufferId = bufferId,
                        networkId = networkId,
                        conversation = conversationName,
                        sender = msg.sender,
                        isSelf = msg.isSelf,
                        directMessage = directMessage,
                        eventId = msg.id,
                        msgid = msg.msgid,
                        serverTime = msg.serverTime,
                    )
                } else {
                    null
                }
            AudioAttachmentPlayers(
                attachments = audioAttachments,
                playbackState = audioPlaybackState,
                derivedWaveforms = audioWaveforms,
                cacheStatuses = audioCacheStatuses,
                networkId = networkId,
                isSelf = msg.isSelf,
                formattedTime = if (standaloneVoice) formattedTime else null,
                pending = msg.pendingLabel != null,
                failed = msg.failed,
                origin = audioOrigin,
                transcripts = voiceTranscripts,
                transcriptionEnabled = voiceTranscriptionEnabled,
                transcriptionReady = voiceTranscriptionReady,
                onTranscribe = onVoiceTranscribe,
                onCancelTranscription = onVoiceTranscriptionCancel,
                onToggle = { attachment, routeNetworkId ->
                    onAudioToggle(AudioPlaybackRequest(attachment, routeNetworkId, audioOrigin))
                },
                onInspectCache = onAudioCacheInspect,
                onSeek = onAudioSeek,
                onLongPress = { onLongPress(msg) },
                reactions = if (standaloneVoice) reactions else emptyList(),
                onReact = { emoji -> onReact(msg, emoji) },
            )
        }
    }
    // The outer gate keeps the Transition holder off ordinary rows: only pending or failed rows
    // compose the AnimatedVisibility, and pending stays in the gate so the pending -> failed flip
    // (and a retry back to pending) animates instead of remounting. A historical failed row first
    // composes with visible = true and renders statically.
    if (msg.failed || msg.pendingLabel != null) {
        AnimatedVisibility(
            visible = msg.failed,
            enter =
                expandVertically(animationSpec = MotdMotion.contentSize) +
                    fadeIn(MotdMotion.microFadeIn),
            exit =
                shrinkVertically(animationSpec = MotdMotion.contentSize) +
                    fadeOut(MotdMotion.microFadeOut),
        ) {
            RetryRow(
                onRetry = if (canRetry) ({ onRetry(msg) }) else null,
                onDelete = { onDelete(msg) },
            )
        }
    }
}

/**
 * COLLAPSE placeholder for a fool's message: a dimmed one-line "nick · hidden" row
 * that expands to the full bubble on tap for the rest of the session. Day-separator and read-marker
 * dividers are drawn exactly as [MessageRow] does so grouping boundaries stay intact whether or not
 * the row is expanded.
 */
@Composable
private fun FoolPlaceholderRow(
    msg: MessageEntity,
    older: MessageEntity?,
    readMarkerTime: TimelineAnchor?,
    readMarkerLabel: String?,
    seam: RowSeam?,
    onLoadGap: (Long) -> Unit,
    onExpand: () -> Unit,
) {
    // Same divided convention as MessageRow: a null [older] is a real edge for the unread and day
    // rules (both derived from this row alone) and an unknown one for the seam (defined by the pair).
    val showNewDivider =
        readMarkerTime != null &&
            msg.timelineAnchor() > readMarkerTime &&
            (older == null || older.timelineAnchor() <= readMarkerTime)
    val showDay =
        remember(msg.serverTime, older?.serverTime) {
            older == null || dayStart(msg.serverTime) != dayStart(older.serverTime)
        }

    // Column so the placeholder row and any dividers stack vertically rather than overlapping (a bare
    // item slot stacks its children like a Box).
    Column(modifier = Modifier.fillMaxWidth()) {
        TimelineSeamDivider(seam, onLoadGap)
        if (showDay) DaySeparator(timeMs = msg.serverTime)
        if (showNewDivider) {
            NewMessagesDivider(
                label = readMarkerLabel ?: stringResource(R.string.chat_new_messages),
                modifier = Modifier.testTag("chat_read_marker_divider"),
            )
        }
        Row(
            modifier =
                Modifier
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
    }
}

/**
 * Tap-to-re-collapse chip drawn above an expanded fool's bubble (bug #9). Mirrors the dimmed
 * placeholder styling of [FoolPlaceholderRow] so the toggle reads as its inverse, and keeps the
 * bubble's own long-press/link taps intact by owning a separate tap target.
 */
@Composable
internal fun FoolCollapseChip(
    sender: String,
    tag: String,
    onCollapse: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(tag)
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
const val CHAT_HISTORY_LOADING_TAG = "chat_history_loading"
const val CHAT_HISTORY_LOAD_OLDER_TAG = "chat_history_load_older"
const val CHAT_HISTORY_MORE_TAG = "chat_history_more"

/** Height of the shimmer footer (12dp padding + 20dp indicator + 12dp padding). */
private val HistoryFooterHeight = 44.dp

/**
 * Debounces the raw append footer state so a PagingSource generation swap cannot flick the footer.
 *
 * Driven off the frame clock rather than a wall clock, which keeps [FooterStatePresenter] pure and
 * lets instrumentation step the windows with `mainClock`. Frames are pulled only while a decision is
 * still pending; once the presenter agrees with the raw state the effect stops.
 */
@Composable
fun rememberFooterUiState(raw: ChatHistoryUiState): ChatHistoryUiState {
    val presenter = remember { FooterStatePresenter() }
    // The presenter starts from Hidden, so an initial Loading must not paint a spinner for the one
    // frame before the effect first resolves it.
    var resolved by remember { mutableStateOf(if (raw == ChatHistoryUiState.Loading) ChatHistoryUiState.Hidden else raw) }
    LaunchedEffect(raw) {
        while (true) {
            val settled = presenter.resolve(raw, withFrameMillis { it })
            resolved = settled
            if (settled == raw) break
        }
    }
    return resolved
}

/**
 * Older-end paging footer. Scroll-driven APPEND drives history automatically, so the footer only
 * renders the shimmer for a page actually in flight, a static "more exists" line for an armed
 * ladder, a retry affordance for recoverable errors, or a terminal status line. Only persisted
 * protocol completion may render the beginning-of-history claim.
 */
@Composable
fun ChatHistoryFooter(
    state: ChatHistoryUiState,
    onRetry: () -> Unit,
) {
    when (state) {
        ChatHistoryUiState.Hidden -> {}

        ChatHistoryUiState.Loading -> {
            androidx.compose.foundation.layout.Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = HistoryFooterHeight)
                        .padding(vertical = 12.dp)
                        .testTag(CHAT_HISTORY_LOADING_TAG),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        // Armed, not fetching: a slim static line, sized exactly like the shimmer box so swapping
        // between the two never reflows the rows above it.
        ChatHistoryUiState.Armed -> {
            HistoryStatusText(
                R.string.chat_history_footer_more,
                modifier = Modifier.heightIn(min = HistoryFooterHeight).testTag(CHAT_HISTORY_MORE_TAG),
            )
        }

        ChatHistoryUiState.Retry -> {
            HistoryRetryFooter(
                text = stringResource(R.string.chat_history_error),
                onRetry = onRetry,
            )
        }

        // Sits directly above the oldest message in the reversed list, where the reader who wants
        // more history is already looking, and re-arms the same APPEND the ladder stopped on.
        ChatHistoryUiState.LoadOlder -> {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(CHAT_HISTORY_LOAD_OLDER_TAG),
                ) {
                    Text(stringResource(R.string.chat_history_load_older))
                }
            }
        }

        is ChatHistoryUiState.Unavailable -> {
            HistoryStatusText(
                if (state.offline) {
                    R.string.chat_history_footer_offline
                } else {
                    R.string.chat_history_footer_negotiating
                },
            )
        }

        ChatHistoryUiState.Unsupported -> {
            HistoryStatusText(R.string.chat_history_footer_unsupported)
        }

        ChatHistoryUiState.ConfirmedStart -> {
            HistoryStatusText(R.string.chat_history_start)
        }
    }
}

@Composable
private fun HistoryStatusText(
    textRes: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
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
private fun HistoryRetryFooter(
    text: String,
    onRetry: () -> Unit,
) {
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
private fun RetryRow(
    onRetry: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier =
            Modifier
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
            androidx.compose.foundation.layout
                .Spacer(Modifier.size(8.dp))
        }
        RetryRowAction(
            icon = Icons.Filled.DeleteOutline,
            label = stringResource(R.string.chat_delete_failed),
            onClick = onDelete,
        )
    }
}

/** One error-tinted, >=48dp-tall tappable label used by [RetryRow]. */
@Composable
private fun RetryRowAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(
        modifier =
            Modifier
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
