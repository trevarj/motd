package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.visibility.JOIN_PART_QUIT_KINDS
import io.github.trevarj.motd.data.visibility.CONVERSATION_KINDS
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.ui.components.ReactionChip
import androidx.paging.LoadState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

// --- timeline message filtering (plans/13 §2.4/§2.5) ---

/** JOIN/PART/QUIT kinds hidden when `showJoinPartQuit == false`. */
val JPQ_KINDS: Set<MessageKind> = JOIN_PART_QUIT_KINDS

/**
 * Behavioral filter spec derived from observed Settings and passed into each repository Pager.
 */
typealias MessageFilterSpec = MessageVisibilitySpec

/** Match a stored actor using its persisted account/casemapped identity, never display spelling. */
fun MessageEntity.matchesConfiguredActor(
    configured: Set<String>,
    identityRules: IrcIdentityRules,
): Boolean {
    if (configured.isEmpty()) return false
    val normalized = configured.mapTo(hashSetOf()) { identityRules.normalize(it.trim()) }
    val accounts = configured.mapTo(hashSetOf()) { it.trim() }
    return normalizedActor in normalized ||
        senderAccount?.let { it in accounts } == true
}

/** Fool treatment is limited to incoming conversation rows. */
fun isFoolMessage(
    message: MessageEntity,
    fools: Set<String>,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): Boolean = message.kind in CONVERSATION_KINDS &&
    !message.isSelf &&
    message.matchesConfiguredActor(fools, identityRules)

/**
 * Policy predicate: drops JPQ rows when hidden, and drops fool rows only in HIDE mode.
 * System-event kinds are never fool-treated (JPQ visibility governs those). COLLAPSE keeps the row
 * so it can render as a tap-to-expand placeholder in the timeline.
 */
fun keepMessage(
    msg: MessageEntity,
    spec: MessageFilterSpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): Boolean = MessageVisibilityPolicy(spec, identityRules).timeline(msg)

/** Grouping window: consecutive same-sender messages within this span share one header. */
const val GROUP_WINDOW_MS: Long = 3 * 60 * 1000

/**
 * Scroll-offset slack (px) within which the reverse list still counts as "at bottom" for autoscroll.
 * Small so a barely-nudged newest row keeps auto-following, but the user is not pinned once they
 * deliberately scroll up. Compose scroll offsets are in raw pixels.
 */
const val AUTOSCROLL_BOTTOM_TOLERANCE_PX: Int = 64
internal const val MAX_PLACEHOLDER_PROBES: Int = 500
internal const val TARGET_MATERIALIZATION_TIMEOUT_MS = 30_000L

/**
 * Decide whether an incoming message should pin the reverse list to the newest row (index 0). Only
 * autoscroll when the user is already at/near the bottom ([atBottom]) AND an already-populated
 * window grew ([newCount] > [oldCount]) — never yank a user who has scrolled up to read history.
 * The first Paging page is deliberately excluded: a reverse list starts at index 0 already, and
 * animating it to index 0 while the enter transition is running adds needless layout work.
 * Own-send scrolls unconditionally at the call site and does not route through this helper.
 */
fun shouldAutoscrollToNewest(atBottom: Boolean, oldCount: Int, newCount: Int): Boolean =
    atBottom && oldCount > 0 && newCount > oldCount

/**
 * Tracks the user's decision to follow live arrivals independently from the reverse list's
 * transient physical position. Paging inserts and programmatic scrolls can both move index zero
 * without representing user intent, so deriving this state directly from the current bottom
 * position is racy.
 */
internal class AutoFollowTracker(initialItemCount: Int) {
    var following: Boolean = true
        private set

    private var itemCount: Int = initialItemCount
    private var newestEffectiveId: Long? = null

    val presentedItemCount: Int
        get() = itemCount

    /** Consume the first post-entry Paging snapshot without treating it as a live arrival. */
    fun reset(itemCount: Int, atBottom: Boolean, newestEffectiveId: Long? = null) {
        this.itemCount = itemCount
        this.newestEffectiveId = newestEffectiveId
        following = atBottom
    }

    /** Explicit send/FAB actions opt back into following the newest row. */
    fun requestFollow() {
        following = true
    }

    /**
     * Update follow intent only for real user scrolling. Programmatic motion and Paging anchor
     * shifts must not disable it. A user scroll that settles back at the bottom opts in again.
     */
    fun onScrollStateChanged(scrolling: Boolean, programmatic: Boolean, atBottom: Boolean) {
        if (programmatic) return
        following = if (scrolling) false else atBottom
    }

    /** Record a new presented count and return whether the viewport should pin to index zero. */
    fun onItemCountChanged(newItemCount: Int): Boolean {
        val shouldFollow = shouldAutoscrollToNewest(following, itemCount, newItemCount)
        itemCount = newItemCount
        return shouldFollow
    }

    /**
     * Follow a newly inserted meaningful row by identity, not by the volatile Paging item count.
     * Room invalidation may briefly publish an empty snapshot, and a bounded loaded window may
     * replace one old row with one new row without changing its count. Neither transition should
     * break live following. Auto-generated row ids are monotonic, so a lower identity (for example
     * exposing an older row after deletion) is not mistaken for a live arrival.
     */
    fun onTimelineChanged(newItemCount: Int, newNewestEffectiveId: Long?): Boolean {
        return onTimelineChangedWithEntry(newItemCount, newNewestEffectiveId).shouldFollow
    }

    /**
     * Classify a timeline update for both viewport following and the one-shot entrance animation.
     * The entry id is only exposed while the user is following the newest row; initial/history
     * updates and changes made while reading older messages must remain visually quiet.
     */
    fun onTimelineChangedWithEntry(
        newItemCount: Int,
        newNewestEffectiveId: Long?,
    ): TimelineChange {
        val previousNewestId = newestEffectiveId
        val shouldFollow = following && previousNewestId != null &&
            newNewestEffectiveId != null && newNewestEffectiveId > previousNewestId
        itemCount = newItemCount
        // An empty invalidation snapshot is not a real timeline transition. Retain the last
        // meaningful identity so the repopulated snapshot can still be classified as live/old.
        if (newNewestEffectiveId != null &&
            (previousNewestId == null || newNewestEffectiveId > previousNewestId)
        ) {
            newestEffectiveId = newNewestEffectiveId
        }
        return TimelineChange(
            shouldFollow = shouldFollow,
            liveEntryId = newNewestEffectiveId.takeIf { shouldFollow },
        )
    }
}

/** The small piece of timeline state that is allowed to cross into row rendering. */
internal data class TimelineChange(
    val shouldFollow: Boolean,
    val liveEntryId: Long?,
)

/** An older animation completion must not consume a newer live-entry identity. */
internal fun consumeLiveEntryId(current: Long?, consumed: Long): Long? =
    current.takeUnless { it == consumed }

fun newestEffectiveMessageId(
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): Long? = (0 until minOf(itemCount, MAX_PLACEHOLDER_PROBES)).firstNotNullOfOrNull { index ->
    peek(index)?.takeIf(policy::effectiveBottom)?.id
}

/** Reverse-list bottom with any raw tail ignored by policy treated as already settled. */
fun isAtEffectiveBottom(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): Boolean {
    if (firstVisibleOffset > AUTOSCROLL_BOTTOM_TOLERANCE_PX) return false
    val belowViewport = minOf(firstVisibleIndex, itemCount)
    if (belowViewport > MAX_PLACEHOLDER_PROBES) return false
    return (0 until belowViewport).none { index ->
        peek(index)?.let(policy::effectiveBottom) == true
    }
}

/** Prefer an eligible row at or older than the viewport; used to avoid saving fool anchors. */
fun nearestAnchorRow(
    firstVisibleIndex: Int,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): Pair<Int, MessageEntity>? {
    val olderEnd = minOf(itemCount, firstVisibleIndex + MAX_PLACEHOLDER_PROBES)
    for (index in firstVisibleIndex until olderEnd) {
        val row = peek(index) ?: continue
        if (policy.anchor(row)) return index to row
    }
    val newerEnd = maxOf(0, firstVisibleIndex - MAX_PLACEHOLDER_PROBES)
    for (index in minOf(firstVisibleIndex - 1, itemCount - 1) downTo newerEnd) {
        val row = peek(index) ?: continue
        if (policy.anchor(row)) return index to row
    }
    return null
}

/** One exact destination model shared by deep links and saved positions. */
data class ChatPositionTarget(
    val index: Int,
    val offset: Int = 0,
    val expectedEventId: Long? = null,
    val expectedMsgid: String? = null,
    val serverTime: Long = 0,
    val highlightMsgid: String? = null,
    val fromSavedPosition: Boolean = false,
    /** Opaque ViewModel request identity; stale UI completions must not consume a newer jump. */
    val requestToken: Long = 0,
)

data class ChatScrollPosition(
    val index: Int,
    val offset: Int,
    val msgid: String?,
    val serverTime: Long,
    val rowId: Long,
)

/**
 * Normal entry scroll: an explicit saved viewport always restores. An unsaved target only repairs
 * list state retained physically off-bottom; it must not displace an already-bottom conversation.
 */
fun shouldScrollToInitialTarget(target: ChatPositionTarget, atBottom: Boolean): Boolean =
    target.fromSavedPosition || !atBottom

/** Canonical local identity is checked before the case-sensitive opaque wire msgid. */
fun positionTargetMatches(target: ChatPositionTarget, actual: MessageEntity?): Boolean {
    actual ?: return false
    if (target.expectedEventId != null && actual.id != target.expectedEventId) return false
    if (target.expectedMsgid != null && actual.msgid != target.expectedMsgid) return false
    return true
}

internal data class TargetMaterialization<T>(
    val item: T?,
    val loading: Boolean,
    val addressable: Boolean = true,
    val failed: Boolean = false,
    /** Changes when Paging replaces or materially shifts the loaded snapshot. */
    val generation: Any? = null,
)

/** Request exactly one placeholder and wait for that position, without scanning the dataset. */
internal suspend fun <T> requestAndAwaitTarget(
    index: Int,
    request: suspend (Int) -> Boolean,
    snapshots: Flow<TargetMaterialization<T>>,
): T? {
    val before = snapshots.first()
    if (!request(index)) return null
    var observedLoading = before.loading
    return withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
        snapshots.firstOrNull { snapshot ->
            observedLoading = observedLoading || snapshot.loading
            val replaced = snapshot.generation != before.generation
            val newFailure = snapshot.failed && (!before.failed || observedLoading || replaced)
            snapshot.item != null || newFailure ||
                (!snapshot.addressable && !snapshot.loading) ||
                ((observedLoading || replaced) && !snapshot.loading)
        }
    }?.item
}

data class ReplyJumpRequest(val msgid: String)

sealed interface ChatUiEvent {
    data object InvalidCommand : ChatUiEvent
    data object ReactionBlocked : ChatUiEvent
    data object ReactionTargetUnavailable : ChatUiEvent
    data object ReactionSendFailed : ChatUiEvent
    data object SendRejected : ChatUiEvent
    data object HistoryOffline : ChatUiEvent
    data class HistoryUpdated(val inserted: Int) : ChatUiEvent
    data object HistoryUpToDate : ChatUiEvent
    data object HistoryUnsupported : ChatUiEvent
    data object HistoryFailed : ChatUiEvent
    data class HistoryIncomplete(val inserted: Int) : ChatUiEvent
    data class HistoryCapped(val inserted: Int, val limit: Int) : ChatUiEvent
    data class ReplyJumpUnavailable(val request: ReplyJumpRequest) : ChatUiEvent
    data object ConversationLayoutWriteFailed : ChatUiEvent
}

/** Database-backed conversation layout and the global setting it may inherit. */
data class ConversationLayoutState(
    val global: LayoutDensity = LayoutDensity.COMFORTABLE,
    val override: LayoutDensity? = null,
) {
    val effective: LayoutDensity get() = override ?: global
}

data class QueuedChatUiEvent(val id: Long, val value: ChatUiEvent)

/** StateFlow-backed FIFO so recreation replays every unacknowledged event exactly once. */
internal class ChatUiEventQueue {
    private val lock = Any()
    private var nextId = 0L
    private val _pending = MutableStateFlow<List<QueuedChatUiEvent>>(emptyList())
    val pending = _pending.asStateFlow()

    fun enqueue(value: ChatUiEvent): QueuedChatUiEvent = synchronized(lock) {
        QueuedChatUiEvent(++nextId, value).also { event ->
            _pending.value = _pending.value + event
        }
    }

    fun acknowledge(id: Long) = synchronized(lock) {
        _pending.value = _pending.value.filterNot { it.id == id }
    }
}

internal fun ChatUiEvent.hasRetryAction(): Boolean =
    this is ChatUiEvent.ReplyJumpUnavailable ||
        this is ChatUiEvent.HistoryFailed ||
        this is ChatUiEvent.HistoryIncomplete ||
        this is ChatUiEvent.HistoryCapped

/** Run a snackbar action before acknowledging its replay-safe queued event. */
internal fun handleChatUiEventResult(
    event: QueuedChatUiEvent,
    actionPerformed: Boolean,
    retryReplyJump: (ReplyJumpRequest) -> Unit,
    retryMissingHistory: () -> Unit,
    acknowledge: (Long) -> Unit,
) {
    if (actionPerformed) {
        when (val value = event.value) {
            is ChatUiEvent.ReplyJumpUnavailable -> retryReplyJump(value.request)
            ChatUiEvent.HistoryFailed,
            is ChatUiEvent.HistoryIncomplete,
            is ChatUiEvent.HistoryCapped,
            -> retryMissingHistory()
            else -> Unit
        }
    }
    acknowledge(event.id)
}

sealed interface ChatHistoryUiState {
    data object Hidden : ChatHistoryUiState
    data object Loading : ChatHistoryUiState
    data object Offline : ChatHistoryUiState
    data object Negotiating : ChatHistoryUiState
    data object Unsupported : ChatHistoryUiState
    data class Incomplete(val inserted: Int = 0) : ChatHistoryUiState
    data class Capped(val inserted: Int, val limit: Int) : ChatHistoryUiState
    data object Error : ChatHistoryUiState
    data object ConfirmedStart : ChatHistoryUiState
}

internal fun chatHistoryUiState(
    bufferType: BufferType?,
    connectionState: IrcClientState?,
    availability: HistoryAvailability,
    append: LoadState,
    historyComplete: Boolean,
    resync: HistoryResyncState,
): ChatHistoryUiState {
    if (bufferType == null || bufferType == BufferType.SERVER) return ChatHistoryUiState.Hidden
    when (resync) {
        is HistoryResyncState.Incomplete -> return ChatHistoryUiState.Incomplete(resync.inserted)
        is HistoryResyncState.Capped -> return ChatHistoryUiState.Capped(resync.inserted, resync.limit)
        is HistoryResyncState.Failed -> return if (availability == HistoryAvailability.Unsupported) {
            ChatHistoryUiState.Unsupported
        } else {
            ChatHistoryUiState.Error
        }
        else -> Unit
    }
    // A final capability decision supersedes a stale mediator error/loading state.
    if (availability == HistoryAvailability.Unsupported) return ChatHistoryUiState.Unsupported
    if (append is LoadState.Loading) return ChatHistoryUiState.Loading
    if (append is LoadState.Error) {
        return when (availability) {
            HistoryAvailability.NegotiatingOrOffline -> historyUnavailableState(connectionState)
            else -> ChatHistoryUiState.Error
        }
    }
    if (append.endOfPaginationReached && historyComplete) {
        return ChatHistoryUiState.ConfirmedStart
    }
    return when (availability) {
        HistoryAvailability.Unsupported -> ChatHistoryUiState.Unsupported
        HistoryAvailability.NegotiatingOrOffline -> historyUnavailableState(connectionState)
        is HistoryAvailability.Ready -> if (append.endOfPaginationReached) {
            ChatHistoryUiState.Incomplete()
        } else {
            ChatHistoryUiState.Hidden
        }
    }
}

private fun historyUnavailableState(connectionState: IrcClientState?): ChatHistoryUiState =
    when (connectionState) {
        IrcClientState.Disconnected -> ChatHistoryUiState.Offline
        is IrcClientState.Failed -> if (connectionState.fatal) {
            ChatHistoryUiState.Offline
        } else {
            ChatHistoryUiState.Negotiating
        }
        else -> ChatHistoryUiState.Negotiating
    }

/** Retries each offline mediator failure once when its connection generation is Ready. */
internal class HistoryReadyRetryGate {
    private var retriedError: Throwable? = null

    fun update(availability: HistoryAvailability, append: LoadState): Boolean {
        if (availability == HistoryAvailability.Unsupported) return false
        val error = (append as? LoadState.Error)?.error ?: return false
        if (error !is io.github.trevarj.motd.irc.client.IrcDisconnectedException) return false
        if (availability !is HistoryAvailability.Ready || retriedError === error) return false
        retriedError = error
        return true
    }
}

/**
 * Aggregate raw [ReactionEntity] rows into per-msgid chip lists: one chip per emoji with its count
 * and whether [myNick] is among the reactors. Ordered by first appearance for stability.
 *
 * Ownership compares persisted actor keys. The authenticated account wins when known; otherwise
 * the supplied network rules produce the same casemapped nick key as EventProcessor.
 */
fun aggregateReactions(
    reactions: List<ReactionEntity>,
    myNick: String?,
    myAccount: String? = null,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): Map<String, List<ReactionChip>> {
    val myActorKeys = buildSet {
        myAccount?.takeUnless { it.isEmpty() || it == "*" }?.let { add("account:$it") }
        myNick?.let { nick ->
            add(identityRules.actorKey(nick, account = null))
            if (myAccount != null) add(identityRules.actorKey(nick, myAccount))
        }
    }
    val myNormalizedNick = myNick?.let(identityRules::normalize)
    // msgid -> emoji -> (count, mine)
    val byMsg = LinkedHashMap<String, LinkedHashMap<String, MutableReactionAgg>>()
    for (r in reactions) {
        val emojiMap = byMsg.getOrPut(r.targetMsgid) { LinkedHashMap() }
        val agg = emojiMap.getOrPut(r.emoji) { MutableReactionAgg() }
        agg.count++
        if (
            r.actorKey in myActorKeys ||
            (myAccount == null && myNormalizedNick != null &&
                identityRules.normalize(r.sender) == myNormalizedNick)
        ) {
            agg.mine = true
        }
    }
    return byMsg.mapValues { (_, emojiMap) ->
        emojiMap.map { (emoji, agg) -> ReactionChip(emoji, agg.count, agg.mine) }
    }
}

private class MutableReactionAgg(var count: Int = 0, var mine: Boolean = false)
