package io.github.trevarj.motd.ui.chat

import androidx.paging.LoadState
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.history.HistoryLadderStalled
import io.github.trevarj.motd.data.history.TimelineSeam
import io.github.trevarj.motd.data.history.seamAbove
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.sync.GapFillProgress
import io.github.trevarj.motd.data.visibility.CONVERSATION_KINDS
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.hasMessageRedactionCap
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.ui.components.HistoryGapState
import io.github.trevarj.motd.ui.components.ReactionChip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull

// --- timeline message filtering ---

/**
 * Behavioral filter spec derived from observed Settings and passed into each repository Pager.
 */
typealias MessageFilterSpec = MessageVisibilitySpec

/**
 * Every seam the room currently has, plus everything needed to say what each one is doing.
 *
 * They travel together because the divider's state is a function of all of it: the seam supplies the
 * gap's identity and recoverability, [filling] supplies whether a fetch is on the wire for it right
 * now, and [historyUnavailable]/[failed] supply the only two reasons a resting seam reads as broken
 * rather than merely idle.
 */
data class TimelineSeamState(
    val seams: List<TimelineSeam> = emptyList(),
    val filling: Set<Long> = emptySet(),
    /**
     * There is no history transport at all, so nothing can load across any seam. True by default so
     * a caller that does not model the rule gets a tappable seam rather than a spinner that will
     * never resolve.
     */
    val historyUnavailable: Boolean = true,
    /** Gaps whose last load attempt failed, rendered as a retry rather than a plain idle tap. */
    val failed: Set<Long> = emptySet(),
) {
    /**
     * How one seam's divider renders.
     *
     * Recoverability is checked FIRST. An unrecoverable gap has nothing left to fetch, so it can
     * never be in flight; ordering the other way would let a stale in-flight id paint a spinner on
     * a seam that will never move.
     *
     * [HistoryGapState.Idle] is the DEFAULT for a recoverable seam — deliberately reversed from this
     * class's earlier rule. A seam being composed only proves the viewport has reached it, not that
     * anything is fetching across it, so defaulting to [HistoryGapState.Loading] painted a spinner
     * over gaps nothing was ever going to move: a perpetual, dishonest "in progress". The spinner is
     * now earned strictly: [HistoryGapState.Loading] renders ONLY while [seam]'s gap id is actually
     * present in [filling]. Everywhere else recoverable, the divider says exactly what is true —
     * nothing is happening right now — and offers the same tap [HistoryGapState.Failed] does, so the
     * reader can start the fetch [SeamLoadingRule] would otherwise wait for a scroll to trigger.
     *
     * [filling] is checked before [failed] so a retry that is already running shows its progress
     * rather than the error it is retrying.
     */
    fun stateFor(seam: TimelineSeam): HistoryGapState =
        when {
            !seam.recoverable -> HistoryGapState.Unrecoverable
            seam.gapId in filling -> HistoryGapState.Loading
            historyUnavailable || seam.gapId in failed -> HistoryGapState.Failed
            else -> HistoryGapState.Idle
        }
}

/** One seam as a row renders it: which gap to retry on tap, and the state its divider draws. */
data class RowSeam(
    val gapId: Long,
    val state: HistoryGapState,
)

/**
 * How close a seam has to be to the older edge of the viewport before history is loaded across it,
 * in rows.
 *
 * The seam is the end of the list as far as the reader is concerned, so it uses the end of the
 * list's rule. `MESSAGE_PAGING_CONFIG` pages older history at `prefetchDistance = 25` with
 * `pageSize = 50`, and matching that number is what makes the two behave identically — including
 * the property the whole design rests on: a page inserts MORE rows than the trigger distance, so one
 * fetch always pushes the seam back out of its own trigger zone. A stationary viewport therefore
 * fetches once and stops, and only more scrolling brings the seam back within reach.
 */
const val SEAM_PREFETCH_DISTANCE: Int = 25

/**
 * What the viewport currently says about history: which seams are close enough to load across, and
 * WHERE THE READER IS.
 *
 * [viewportAnchor] is the identity of the NEWEST row on screen — the row `LazyList` pins its scroll
 * position to, since `MessageList` gives every row a stable `itemKey`. No insertion and no
 * re-measure can move it, which is the whole reason it is here; the one residual is a row REMOVED
 * newer than it (a dedup, a redaction, a visibility-policy change), which shifts the same index onto
 * a strictly older row and reads as one row of scroll. That costs at most one extra quantum per
 * removal and cannot cascade, which is strictly narrower than the index it replaced. Null means the
 * viewport could
 * not name its position (the newer edge is the footer item or an unmaterialized placeholder), and
 * [SeamLoadingRule] treats that as "cannot prove the reader moved", never as a fresh approach.
 *
 * It replaced a raw older-edge index, and that swap is the fix for a real runaway. The older edge is
 * a layout OUTPUT, not a scroll signal: it grows when rows land under the fold, when the seam's own
 * divider enters its loading state and re-measures the row it is drawn inside, when a placeholder
 * resolves to a different height, when the viewport itself gets taller. [olderEdgeIndex] survives
 * ONLY as journal evidence — watching it move 49 -> 50 -> 51 across an unmoved viewport is what
 * identified this — and must never be compared. Reach still takes the older edge (see
 * [seamsWithinPrefetch]); only the DEPTH memory changed unit, because reach asks "what is near" and
 * depth asks "where is the reader", and the two were conflated into one integer.
 */
internal data class SeamPrefetch(
    val viewportAnchor: TimelineAnchor? = null,
    val gapIds: Set<Long> = emptySet(),
    val olderEdgeIndex: Int = -1,
)

/**
 * The gap ids close enough to the older edge of the viewport that history should be loading across
 * them.
 *
 * A seam is drawn INSIDE the composition of the row on its newer side ([seamAbove]). Rather than
 * re-deriving that per row — which would have to reproduce `MessageList`'s chunking, including a
 * collapsed system run resolving its seam against the neighbor of the WHOLE run — this takes the
 * union of the slots covered by the visible rows PLUS the next [prefetchDistance] rows below them:
 * the half-open anchor interval from the older neighbor of that extended end up to and including the
 * NEWEST visible row.
 *
 * Extending only at the older end is deliberate and is the direction of travel. A seam the reader
 * has already scrolled past sits NEWER than everything on screen, falls outside the interval, and
 * stops being loaded — the same way Paging stops appending once you scroll back up.
 *
 * [firstVisibleIndex]/[lastVisibleIndex] are Paging indices from `LazyListState.layoutInfo`, so
 * `first` is the newest row on screen. Unmaterialized rows are skipped; when the extended end has no
 * materialized older neighbor the interval closes at the oldest materialized row's own anchor, which
 * matches [seamAbove] abstaining rather than guessing a position that would move the moment the
 * placeholder loads.
 */
fun seamsWithinPrefetch(
    firstVisibleIndex: Int,
    lastVisibleIndex: Int,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    seams: List<TimelineSeam>,
    prefetchDistance: Int = SEAM_PREFETCH_DISTANCE,
): Set<Long> {
    if (seams.isEmpty() || itemCount <= 0) return emptySet()
    val first = firstVisibleIndex.coerceAtLeast(0)
    if (first > itemCount - 1) return emptySet()
    val reach = (lastVisibleIndex + prefetchDistance).coerceIn(first, itemCount - 1)
    val newestRow = (first..reach).firstNotNullOfOrNull(peek) ?: return emptySet()
    val oldestIndex = (reach downTo first).firstOrNull { peek(it) != null } ?: return emptySet()
    val oldestRow = peek(oldestIndex) ?: return emptySet()
    val olderNeighbor = if (oldestIndex + 1 < itemCount) peek(oldestIndex + 1) else null
    val lowerExclusive = (olderNeighbor ?: oldestRow).timelineAnchor()
    val upperInclusive = newestRow.timelineAnchor()
    return seams
        .asSequence()
        .filter { it.position > lowerExclusive && it.position <= upperInclusive }
        .map { it.gapId }
        .toSet()
}

/**
 * Where the reader is: the identity of the newest row on screen, or null when the viewport cannot
 * say — [firstVisibleIndex] is the footer item (`itemCount`) or a Paging placeholder.
 *
 * Deliberately NOT "the newest MATERIALIZED row in reach", the way [seamsWithinPrefetch] picks its
 * upper bound. That scan slides OLDER when the rows at the newer edge fall back to placeholders,
 * which a page landing does routinely — and an anchor that slides older reads as the reader having
 * scrolled deeper, which would reintroduce the runaway in the exact window a fill occupies.
 * Abstaining is the safe direction: [SeamLoadingRule] refuses re-entry it cannot justify rather than
 * granting one it cannot.
 */
internal fun viewportAnchorAt(
    firstVisibleIndex: Int,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
): TimelineAnchor? =
    firstVisibleIndex
        .takeIf { it in 0 until itemCount }
        ?.let(peek)
        ?.timelineAnchor()

internal fun MessageEntity.timelineAnchor() = TimelineAnchor(serverTime, id, timelineOrder)

/** Whether the screen is in a state where history can be loaded across a seam at all. */
internal data class SeamLoadingGate(
    val onScreen: Boolean,
    val historyReady: Boolean,
    val entrySettled: Boolean,
    /**
     * History is not merely unresolved but out of reach — the network is offline or does not serve
     * CHATHISTORY — so a seam should say so rather than spin. Distinct from `!historyReady`, which
     * is also true for the second it takes a fresh connection to negotiate.
     */
    val historyUnreachable: Boolean = false,
) {
    val armable: Boolean get() = onScreen && historyReady && entrySettled
}

/** Is history out of reach for good enough reasons to tell the reader about? */
internal fun historyUnreachable(
    availability: HistoryAvailability,
    connectionState: IrcClientState?,
): Boolean =
    when (availability) {
        HistoryAvailability.Unsupported -> true
        HistoryAvailability.NegotiatingOrOffline -> isHistoryOffline(connectionState)
        is HistoryAvailability.Ready -> false
    }

/** A tap on a failed seam's divider. [token] makes each tap a distinct event over a StateFlow. */
internal data class GapTapRequest(
    val gapId: Long,
    val token: Long,
)

/** One load the timeline has decided to start, and where the demand came from. */
internal data class GapFillRequest(
    val roomId: Long,
    val gapId: Long,
    val fromTap: Boolean,
)

/**
 * What [SeamLoadingRule.next] decided, and — when it decided nothing — why.
 *
 * The rule used to answer with a bare nullable request, which made five unrelated refusals produce
 * byte-identical evidence: a closed gate, a viewport with nothing in reach, a demand already spent
 * at this scroll depth, a candidate parked behind a failure, and a proven-empty interval all looked
 * like "no fill ran". A required-E2E failure whose whole symptom IS "no fill ran" then said nothing
 * about which half of the path broke, and every diagnosis had to be reconstructed by hand from
 * surrounding records.
 *
 * Naming them costs no logic — [SeamLoadingRule.next] already computes every distinction below
 * inline — and it is what lets one failing run be read in one pass.
 */
internal sealed interface SeamDecision {
    /** Start this load. Exactly one [SeamLoadingRule.settle] must follow. */
    data class Start(
        val request: GapFillRequest,
    ) : SeamDecision

    /** [SeamLoadingGate.armable] is false: the screen may not load history at all right now. */
    data object GateClosed : SeamDecision

    /** The viewport reported no seam within loading reach, so there is nothing to start. */
    data object NoDemand : SeamDecision

    /**
     * Every demanded seam already failed and is waiting on a retry tap. Scrolling will NOT offer it
     * again, so this is the one refusal whose recovery is the reader's, and keeping it apart from
     * [AlreadyTriedAtDepth] is the difference between "the viewport has not moved yet" and "the
     * divider is sitting in its error state".
     */
    data object AwaitingRetryTap : SeamDecision

    /**
     * Every demanded seam was already fetched at this scroll depth — the reader is anchored on the
     * same row, or on a newer one, or on a row the viewport cannot name. Not "not ever": putting a
     * strictly OLDER row at the top of the viewport offers the same seam again on the next emission.
     */
    data object AlreadyTriedAtDepth : SeamDecision

    /** Every demanded seam is a server-proven-empty interval with nothing left to fetch. */
    data object NotRecoverable : SeamDecision
}

/** Fixed classification string for the journal; never a free-form or user-derived value. */
internal val SeamDecision.journalName: String
    get() =
        when (this) {
            is SeamDecision.Start -> if (request.fromTap) "start_tap" else "start"
            SeamDecision.GateClosed -> "gate_closed"
            SeamDecision.NoDemand -> "no_demand"
            SeamDecision.AwaitingRetryTap -> "awaiting_retry_tap"
            SeamDecision.AlreadyTriedAtDepth -> "already_tried_at_depth"
            SeamDecision.NotRecoverable -> "not_recoverable"
        }

/**
 * The timeline's one history rule: **a seam behaves exactly like the end of the list.**
 *
 * Scrolling toward a seam loads history across it, with a spinner, for as long as the user keeps
 * scrolling toward it — no per-seam allowance to run out of, and no special case for the newest gap:
 * after a reconnect the catch-up gap simply happens to be where the room opens, so hands-free
 * catch-up falls out of the general rule rather than being a mechanism of its own. A resting seam
 * ALSO offers a tap — [HistoryGapState.Idle] when nothing has gone wrong, [HistoryGapState.Failed]
 * as a retry once it has — but either way the tap is the same [GapTapRequest], and [next] does not
 * care which divider issued it.
 *
 * ## Why this cannot run away
 *
 * The bound is the reader's attention, not a counter, and it comes from two facts that hold together:
 *
 *  1. **A fetch pushes its own seam out of the trigger zone.** Filling recedes the gap's newer edge,
 *     so the seam's slot moves further into history by however many rows landed. One page is 50 rows
 *     against a [SEAM_PREFETCH_DISTANCE] of 25, so a stationary viewport gets one fetch and then the
 *     seam is out of reach. This is the same pageSize-beats-prefetchDistance relationship that makes
 *     Paging's own APPEND fire once per deliberate scroll step rather than cascading.
 *  2. **A gap does not re-fire until the reader scrolls further toward it.** [next] remembers the
 *     [SeamPrefetch.viewportAnchor] — the IDENTITY of the newest row on screen — each gap was last
 *     fetched at, and re-entry needs the reader to be anchored on a strictly OLDER row than that.
 *     That closes fact 1's remaining hole — a page that lands FEWER rows than the trigger distance
 *     would otherwise leave the seam inside its own zone and loop against an idle viewport.
 *
 *     The unit is load-bearing, and getting it wrong is how this class ran away in the required E2E.
 *     Depth used to be `visibleItemsInfo.last().index`, the OLDEST row's list index. That is a
 *     layout output, not a scroll signal: it grows when rows land under the fold, when the seam's
 *     own divider enters its loading state and re-measures the row it is drawn inside, when a
 *     placeholder resolves to a different height, when the viewport gets taller. The journal from
 *     that run shows it moving 49 -> 50 -> 51 with the NEWER edge pinned at index 39 and nobody
 *     touching the screen, and the rule read those three re-measures as three scrolls: one
 *     hands-free approach drained 262 rows where the design allows exactly one 150-row quantum. A
 *     row identity cannot be re-measured, and it cannot be renumbered by an insertion either, which
 *     an index at EITHER edge can.
 *
 *     The memory is dropped when the seam leaves the zone AND the reader has actually moved, so
 *     scrolling away and coming back loads again, which is what the reader expects. The second half
 *     of that condition is the other half of the same runaway: a fill RECEDES its own seam out of
 *     reach, so an unconditional forget-on-leave let a fill authorise its own successor. The journal
 *     shows demand for one gap going 1 -> 0 -> 1 inside a single fill with a stationary viewport.
 *
 *     A viewport that cannot name its position ([SeamPrefetch.viewportAnchor] null) refuses rather
 *     than grants: an unprovable move is not a move.
 *
 * So a 10k-message gap cannot drain unprompted: every page costs one deliberate scroll toward it,
 * and stopping stops the fetching. Nothing is fetched for a seam the reader never scrolled to, which
 * is the property the retired page budget used to protect by asking for a tap.
 *
 * ## Contention is not failure
 *
 * `b71d0c34`'s rule survives, and is simpler here than it was: a fill that ends empty-handed — the
 * anti-livelock stop with zero inserts, or one that never pinned the gap because the room's single
 * flight was taken — says nothing about the seam. It must NOT mark the gap failed, because the
 * interval is still owed and the divider would be advertising an error that never happened. It just
 * does not re-fire at this depth; the next scroll toward the seam tries again. The old
 * `RELEASE_BUDGET` counter that bounded those retries is gone because the scroll depth already
 * bounds them.
 *
 * ## The gate
 *
 * [SeamLoadingGate.onScreen] is "the room is on screen". [SeamLoadingGate.historyReady] is "there is
 * a transport to page against", and the next Ready emission re-evaluates, so a room opened while its
 * connection negotiates still loads once it settles; [SeamLoadingGate.historyUnreachable] is the
 * separate question of whether to SAY so, and it is what stops an offline seam spinning at a
 * transport that is not coming back. [SeamLoadingGate.entrySettled]
 * is an ordering constraint and is not optional: normal entry FREEZES what was unread when the room
 * opened, from the store, and a fill that rewrites the store first would land that frozen boundary
 * on rows this class had just fetched. Entry resolves first, then history loads underneath it.
 *
 * A tap bypasses the gate. The user is looking at an idle or failed divider; nothing about a pending
 * entry or a stale availability snapshot makes their tap wrong.
 */
internal class SeamLoadingRule {
    // gapId -> the viewport anchor the gap was last fetched at. Presence means "fetched during this
    // approach"; a null VALUE means it was fetched at a position the viewport could not name, which
    // nothing can prove the reader has moved away from, so it refuses until the prune clears it.
    private val fetchedAtDepth = mutableMapOf<Long, TimelineAnchor?>()
    private val failures = mutableSetOf<Long>()
    private var consumedTapToken = 0L

    /** Gaps whose last attempt failed, for the divider that has to offer the retry. */
    val failedGapIds: Set<Long> get() = failures.toSet()

    /**
     * The load to start now, or the named reason there is none. Exactly one [settle] must follow
     * each [SeamDecision.Start].
     */
    fun next(
        roomId: Long,
        gate: SeamLoadingGate,
        seams: List<TimelineSeam>,
        prefetch: SeamPrefetch,
        tap: GapTapRequest?,
    ): SeamDecision {
        // A seam that has left the zone forgets where it was last fetched, so scrolling away and
        // back is a fresh approach rather than a depth already spent — but ONLY if the reader
        // actually went away. A seam that left the zone because its OWN fill receded it, under a
        // viewport that never moved, is the same approach still running, and forgetting there hands
        // the next evaluation an unconditional grant. That is half of how one quantum turned into
        // three in the required E2E: demand went 1 -> 0 -> 1 inside a single fill with nobody
        // touching the screen. A viewport that cannot name its position prunes nothing, for the same
        // reason it cannot re-fire: it has proved no movement.
        prefetch.viewportAnchor?.let { here ->
            // A fetch recorded at a position the viewport could not name has no depth to compare
            // against, and the prune below would read that null as "not where the reader is now" and
            // forget the fetch entirely — which is an unconditional grant on the next emission, the
            // very runaway this rule exists to stop. The first nameable position after such a fetch
            // is the earliest place the reader can be PROVEN to have been, so adopt it as that
            // fetch's depth instead. Refusing outright would be the other extreme: the gap could
            // then never re-fire however far the reader scrolled.
            fetchedAtDepth.replaceAll { _, at -> at ?: here }
            fetchedAtDepth.entries.retainAll { (gapId, at) -> gapId in prefetch.gapIds || at == here }
        }
        if (tap != null && tap.token > consumedTapToken) {
            consumedTapToken = tap.token
            failures -= tap.gapId
            fetchedAtDepth[tap.gapId] = prefetch.viewportAnchor
            return SeamDecision.Start(GapFillRequest(roomId, tap.gapId, fromTap = true))
        }
        if (!gate.armable) return SeamDecision.GateClosed
        // The refusals below are split apart rather than folded into one predicate ONLY so each can
        // be named; the surviving set is the same conjunction it has always been. See [SeamDecision]
        // for why the distinction has to survive as far as the journal.
        val demanded = seams.filter { it.gapId in prefetch.gapIds }
        if (demanded.isEmpty()) return SeamDecision.NoDemand
        val fillable = demanded.filter { it.recoverable }
        if (fillable.isEmpty()) return SeamDecision.NotRecoverable
        // A failed seam and an unmoved viewport both leave nothing to start, but only one of them
        // clears itself when the reader scrolls, so they are separate answers rather than one.
        val approachable = fillable.filterNot { it.gapId in failures }
        if (approachable.isEmpty()) return SeamDecision.AwaitingRetryTap
        // Newest first when two seams share the zone: the reader is scrolling INTO history, so the
        // nearer hole is the one they are reading toward. Only one is started either way — the other
        // is offered on the next emission, and serializing them is what keeps a second fetch from
        // computing its boundary against a store this one is halfway through moving.
        val candidate =
            approachable
                .filter { seam ->
                    // Never fetched during this approach: nothing to be deeper than. Afterwards the
                    // reader must be anchored on a STRICTLY OLDER row than the one they were on when the
                    // last page was fetched — scrolling into history makes the newest visible row older,
                    // so `here < at` IS "the reader got deeper", and drifting back toward the present can
                    // never grant. Either anchor being unnameable refuses, because an unprovable move is
                    // not a move.
                    if (seam.gapId !in fetchedAtDepth) return@filter true
                    val at = fetchedAtDepth[seam.gapId] ?: return@filter false
                    val here = prefetch.viewportAnchor ?: return@filter false
                    here < at
                }.maxWithOrNull(compareBy({ it.position }, { it.gapId }))
                ?: return SeamDecision.AlreadyTriedAtDepth
        fetchedAtDepth[candidate.gapId] = prefetch.viewportAnchor
        return SeamDecision.Start(GapFillRequest(roomId, candidate.gapId, fromTap = false))
    }

    /**
     * Record what [request]'s load attempt achieved.
     *
     * Only [GapFillProgress.FAILED][io.github.trevarj.motd.data.sync.GapFillProgress] raises the one
     * affordance this design still has: the wire errored, or there was no transport. Everything else
     * leaves the seam loading. [GapFillProgress.MOVED] additionally clears a previous failure, since
     * a page that landed is the retry having worked.
     */
    fun settle(
        request: GapFillRequest,
        progress: GapFillProgress,
    ) {
        when (progress) {
            GapFillProgress.FAILED -> failures += request.gapId

            GapFillProgress.MOVED -> failures -= request.gapId

            // Contention, not failure: the interval is still owed and the next scroll retries it.
            GapFillProgress.STALLED, GapFillProgress.DROPPED -> Unit
        }
    }
}

/**
 * The seam drawn above [row]'s content in the reversed timeline, or null when that slot draws
 * nothing.
 *
 * Every seam call site in `MessageList` goes through this, so the composables are one-line wrappers
 * around it and a unit test asserting on it is asserting on the rendered slot.
 *
 * [olderNeighbor] must be the same neighbor the caller's own dividers are computed against — for a
 * collapsed system run that is the row just older than the WHOLE run, not the next index.
 */
fun rowSeam(
    row: MessageEntity,
    olderNeighbor: MessageEntity?,
    seams: TimelineSeamState,
): RowSeam? =
    seamAbove(row, olderNeighbor, seams.seams)
        ?.let { RowSeam(it.gapId, seams.stateFor(it)) }

/** Frozen normal-entry boundary; [lowerBound] means older unread rows are not loaded yet. */
data class UnreadEntrySnapshot(
    val marker: TimelineAnchor,
    val loadedCount: Int,
    val lowerBound: Boolean,
)

enum class AgentContextPreparation { READY, NO_CONTEXT, TOO_LARGE, FAILED, PENDING_SHARE }

internal fun canPrepareCatchUpContext(
    agentwireEnabled: Boolean,
    isServerBuffer: Boolean,
    unreadEntrySnapshot: UnreadEntrySnapshot?,
): Boolean = agentwireEnabled && !isServerBuffer && unreadEntrySnapshot != null

internal fun canPrepareThreadContext(
    agentwireEnabled: Boolean,
    isServerBuffer: Boolean,
    message: MessageEntity,
    visibilityPolicy: MessageVisibilityPolicy,
): Boolean = agentwireEnabled && !isServerBuffer && message.kind in CONVERSATION_KINDS && visibilityPolicy.preview(message)

/**
 * Rebuild the frozen entry boundary from its flat SavedState projection.
 *
 * [computed] is state in its own right, not a null check: it separates "this visit never froze a
 * boundary" from "this visit froze the absence of one". Recomputing the second case after process
 * death would raise a divider for messages that arrived AFTER entry, which is precisely what
 * freezing on entry exists to prevent, so a restored absence is returned as an absence.
 */
internal fun restoredUnreadEntrySnapshot(
    computed: Boolean,
    markerServerTime: Long,
    markerEventId: Long,
    markerTimelineOrder: Long,
    loadedCount: Int,
    lowerBound: Boolean,
): UnreadEntrySnapshot? {
    if (!computed || markerServerTime <= 0) return null
    return UnreadEntrySnapshot(
        marker = TimelineAnchor(markerServerTime, markerEventId, markerTimelineOrder),
        loadedCount = loadedCount.coerceAtLeast(1),
        lowerBound = lowerBound,
    )
}

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
): Boolean =
    message.kind in CONVERSATION_KINDS &&
        !message.isSelf &&
        message.matchesConfiguredActor(fools, identityRules)

/**
 * Policy predicate: drops presence rows when hidden, and drops fool rows only in HIDE mode.
 * System-event kinds are never fool-treated (presence visibility governs those). COLLAPSE keeps the row
 * so it can render as a tap-to-expand placeholder in the timeline.
 */
fun keepMessage(
    msg: MessageEntity,
    spec: MessageFilterSpec,
    identityRules: IrcIdentityRules = IrcIdentityRules(),
): Boolean = MessageVisibilityPolicy(spec, identityRules).timeline(msg)

/** Grouping window: consecutive same-sender messages within this span share one header. */
const val GROUP_WINDOW_MS: Long = 3 * 60 * 1000

/** Tone bucket for the latency readout (#34); pure so the thresholds are unit-testable. */
enum class LagTone { GOOD, DEGRADED, BAD }

/** Classify a PING/PONG round-trip into a display tone. Thresholds chosen for IRC-scale latency. */
fun lagTone(lagMs: Long): LagTone =
    when {
        lagMs < 300 -> LagTone.GOOD
        lagMs < 1_500 -> LagTone.DEGRADED
        else -> LagTone.BAD
    }

/**
 * Scroll-offset slack (px) within which the reverse list still counts as "at bottom" for autoscroll.
 * Small so a barely-nudged newest row keeps auto-following, but the user is not pinned once they
 * deliberately scroll up. Compose scroll offsets are in raw pixels.
 */
const val AUTOSCROLL_BOTTOM_TOLERANCE_PX: Int = 64
internal const val MAX_PLACEHOLDER_PROBES: Int = 500
internal const val TARGET_MATERIALIZATION_TIMEOUT_MS = 30_000L
internal const val TOP_ALIGNMENT_TOLERANCE_PX = 1

/**
 * Upper bound on how long the timeline stays veiled while entry positioning settles. Entry work is
 * asynchronous (anchor resolution, a suspending scroll, then the top-alignment passes), so showing
 * the list immediately flashes the bottom of the buffer before the divider lands. The bound keeps a
 * slow or offline entry degrading to today's visible correction instead of a stuck blank pane, and
 * stays well under the ENTRY_HISTORY_READY_TIMEOUT_MS ceiling the ViewModel applies to its own hold.
 */
internal const val ENTRY_VEIL_TIMEOUT_MS = 1_500L

/**
 * Reveal rule for the entry veil. Settlement is the happy path; an unresolved entry has no target
 * left to land on, and the timeout is the safety valve. Pure so the routing is unit-testable.
 */
internal fun shouldLiftEntryVeil(
    initialPositionSettled: Boolean,
    entryUnresolved: Boolean,
    timedOut: Boolean,
): Boolean = initialPositionSettled || entryUnresolved || timedOut

/**
 * Upper bound on measure-correct passes when snapping the entry row to the viewport top. One pass
 * suffices on a quiet layout; a pass whose scroll a racing Paging generation presentation clamps is
 * observed on the next frame and re-corrected. The cap keeps a layout that legitimately cannot
 * align (content shorter than the viewport) from spinning until the materialization timeout.
 */
internal const val TOP_ALIGNMENT_MAX_PASSES = 8

/**
 * Decide whether an incoming message should pin the reverse list to the newest row (index 0). Only
 * autoscroll when the user is already at/near the bottom ([atBottom]) AND an already-populated
 * window grew ([newCount] > [oldCount]) — never yank a user who has scrolled up to read history.
 * The first Paging page is deliberately excluded: a reverse list starts at index 0 already, and
 * animating it to index 0 while the enter transition is running adds needless layout work.
 * Own-send scrolls unconditionally at the call site and does not route through this helper.
 */
fun shouldAutoscrollToNewest(
    atBottom: Boolean,
    oldCount: Int,
    newCount: Int,
): Boolean = atBottom && oldCount > 0 && newCount > oldCount

/** Which jump the scroll-to-bottom FAB performs. */
sealed interface ScrollToBottomFabJump {
    /** Jump to the nearest unread @mention below the viewport (the FAB's mention walk). */
    data class Mention(
        val target: ChatPositionTarget,
    ) : ScrollToBottomFabJump

    /** Jump straight to the newest row. */
    object Newest : ScrollToBottomFabJump
}

/**
 * Resolves the scroll-to-bottom FAB action. A long-press always skips the mention walk and goes to
 * newest; a tap follows the nearest unread [mentionTarget] when one is pending below the viewport,
 * otherwise it also goes to newest. Pure so the routing is unit-testable without composition.
 */
fun scrollToBottomFabJump(
    longPress: Boolean,
    mentionTarget: ChatPositionTarget?,
): ScrollToBottomFabJump =
    if (longPress || mentionTarget == null) {
        ScrollToBottomFabJump.Newest
    } else {
        ScrollToBottomFabJump.Mention(mentionTarget)
    }

/**
 * Tracks the user's decision to follow live arrivals independently from the reverse list's
 * transient physical position. Paging inserts and programmatic scrolls can both move index zero
 * without representing user intent, so deriving this state directly from the current bottom
 * position is racy.
 */
internal class AutoFollowTracker(
    initialItemCount: Int,
) {
    var following: Boolean = true
        private set

    private var itemCount: Int = initialItemCount
    private var newestEffectiveId: Long? = null

    val presentedItemCount: Int
        get() = itemCount

    /** Consume the first post-entry Paging snapshot without treating it as a live arrival. */
    fun reset(
        itemCount: Int,
        atBottom: Boolean,
        newestEffectiveId: Long? = null,
    ) {
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
    fun onScrollStateChanged(
        scrolling: Boolean,
        programmatic: Boolean,
        atBottom: Boolean,
    ) {
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
    fun onTimelineChanged(
        newItemCount: Int,
        newNewestEffectiveId: Long?,
    ): Boolean = onTimelineChangedWithEntry(newItemCount, newNewestEffectiveId).shouldFollow

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
        val shouldFollow =
            following && previousNewestId != null &&
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

/** Timeline invalidations must retain in-flight entries while independent burst rows arrive. */
internal fun appendLiveEntryId(
    current: Set<Long>,
    arrived: Long?,
): Set<Long> = if (arrived == null || arrived in current) current else current + arrived

/** A disposed row consumes only its own entrance identity. */
internal fun consumeLiveEntryId(
    current: Set<Long>,
    consumed: Long,
): Set<Long> = current - consumed

/**
 * A send in progress, presented as a bubble travelling from the composer into the timeline.
 *
 * [token] identifies one tap for the whole life of the flight. The durable row identity is only
 * known once the send is accepted, so a flight launches with [eventIds] empty and adopts them a
 * moment later; until then the ghost simply waits at the composer.
 */
data class OutgoingFlight(
    val token: Long,
    val text: String,
    // Carried from the ViewModel, which still owns the reply at the tap instant. A reply's pending
    // row draws its quoted mini-bubble from the first frame, so a ghost without it is the wrong
    // height in flight and pops at the handoff.
    val replySender: String? = null,
    val replyText: String? = null,
    val replyIrcFormattedText: String? = null,
    /**
     * The tap instant. A pending row inserted before it belongs to an earlier send however
     * identical its text, so sending the same line twice in a row cannot make the second ghost
     * capture the first one's still-unconfirmed row.
     */
    val launchedAtMs: Long,
    val eventIds: Set<Long> = emptySet(),
) {
    /**
     * Whether [message] is the row this ghost is standing in for.
     *
     * The pending row is written to Room before the send is accepted, so waiting for [eventIds]
     * would let the row appear and play its own arrival animation first, then be yanked back
     * under the ghost. Until the identities land, the row is recognised by what it looks like: an
     * unconfirmed message of ours carrying exactly the text in flight.
     */
    fun matches(message: MessageEntity): Boolean =
        when {
            eventIds.isNotEmpty() -> {
                message.id in eventIds
            }

            // Pending rows are stamped with the wall clock at insert, which is always at or after the
            // tap that produced them. Once confirmed a row can have its time rewritten by the server,
            // but by then it has no pending label and only the identity branch above applies.
            else -> {
                message.isSelf && message.pendingLabel != null &&
                    message.serverTime >= launchedAtMs && (message.ircFormattedText ?: message.text) == text
            }
        }
}

/**
 * Whether submitted text will be rewritten into a CTCP ACTION rather than sent as it stands.
 *
 * Mirrors the connection manager's own `startsWith("/me ")` rule, which strips the prefix and
 * stores the row as an ACTION. A flight over that text would match no row and land on a bubble it
 * does not resemble, so the send gesture skips the ghost for emotes.
 */
internal fun isActionCommand(text: String): Boolean = text.startsWith("/me ")

/**
 * Whether an accepted send produced exactly the one row a ghost is standing in for.
 *
 * The send pipeline may rewrite a submission before storing it -- a reply gains a `nick: ` prefix
 * when the client tag is unavailable, and physical newlines split one submission into several rows.
 * A ghost that claimed those would pull a row that has already begun its own entrance back to a
 * closed gap, while showing text the row does not have. An unreported set of texts (a fake, or an
 * older seam) is treated as matching.
 */
internal fun acceptedRowMatchesFlight(
    accepted: SendAcceptance.Accepted,
    sentText: String,
): Boolean =
    accepted.eventIds.size == 1 &&
        (accepted.storedTexts.isEmpty() || accepted.storedTexts.singleOrNull() == sentText)

/** Begin a flight for one tap. A tap always replaces any earlier flight rather than queueing. */
internal fun launchOutgoingFlight(
    token: Long,
    text: String,
    replyTo: MessageEntity?,
    nowMs: Long,
): OutgoingFlight =
    OutgoingFlight(
        token = token,
        text = text,
        replySender = replyTo?.sender,
        replyText = replyTo?.text,
        replyIrcFormattedText = replyTo?.ircFormattedText,
        launchedAtMs = nowMs,
    )

/**
 * Predict [showsSender] for the row a flight is about to become, before that row exists.
 *
 * [newest] is the row that will be its older neighbor: the newest timeline entry the flight is not
 * itself standing in for. The real grouping rule is run against a synthetic pending row rather than
 * reimplemented, so the ghost's silhouette and the landing row's cannot resolve differently and
 * a second message in a burst gets the tightened corner both before and after the handoff.
 */
internal fun predictFlightShowsSender(
    newest: MessageEntity?,
    selfNick: String,
    normalizedSelf: String,
    nowMs: Long,
): Boolean =
    showsSender(
        current =
            MessageEntity(
                bufferId = newest?.bufferId ?: -1L,
                serverTime = nowMs,
                sender = selfNick,
                normalizedActor = normalizedSelf,
                kind = MessageKind.PRIVMSG,
                text = "",
                isSelf = true,
                dedupKey = "",
            ),
        olderNeighbor = newest,
    )

/**
 * Adopt the durable row identities an accepted send produced. A stale token is ignored so a slow
 * accept cannot retarget a newer tap's ghost, and an empty result settles the flight instead of
 * leaving a ghost aimed at nothing.
 */
internal fun attachOutgoingFlightEvents(
    current: OutgoingFlight?,
    token: Long,
    eventIds: Collection<Long>,
): OutgoingFlight? =
    when {
        current == null || current.token != token -> current
        eventIds.isEmpty() -> null
        else -> current.copy(eventIds = eventIds.toSet())
    }

/**
 * End a flight by token after it lands, is rejected or replaced, or leaves the screen. The
 * timeline reveals the landing row the moment its flight is gone, so this is the only way a row
 * hidden behind a ghost comes back.
 */
internal fun settleOutgoingFlight(
    current: OutgoingFlight?,
    token: Long,
): OutgoingFlight? = if (current?.token == token) null else current

/** Replacing a collapsed system-run head is an in-place summary update, not a new visual row. */
internal fun extendsSystemRun(
    liveEntryId: Long?,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
): Boolean {
    liveEntryId ?: return false
    val index =
        (0 until minOf(itemCount, MAX_PLACEHOLDER_PROBES))
            .firstOrNull { peek(it)?.id == liveEntryId } ?: return false
    if (index + 1 >= itemCount) return false
    val current = peek(index) ?: return false
    val older = peek(index + 1) ?: return false
    return isSystemKind(current.kind) && isSystemKind(older.kind)
}

fun newestEffectiveMessageId(
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): Long? =
    (0 until minOf(itemCount, MAX_PLACEHOLDER_PROBES)).firstNotNullOfOrNull { index ->
        peek(index)?.takeIf(policy::effectiveBottom)?.id
    }

/**
 * Reverse-list bottom with any raw tail ignored by policy treated as already settled.
 *
 * "Ignored" means MATERIALIZED AND IGNORED. Every index below the viewport must be readable and
 * must be a row this [policy] does not treat as the effective bottom; an unloaded placeholder
 * (`peek == null`) blocks the bottom outright. This is the whole safety property of the predicate,
 * because its consumer acknowledges the ROOM's newest anchor: unknown is not "already read", and
 * skipping nulls would let a viewport parked deep in history — with the newest pages dropped by
 * `maxSize` and therefore null underneath it — claim the conversation's bottom and upload a
 * MARKREAD for messages that were never displayed. A deep jump lands in exactly that state.
 *
 * Live following is untouched by the stricter rule. A user genuinely at the bottom sits at index 0,
 * so [belowViewport] is empty and the loop cannot reject anything; a user sitting above a short
 * ignored tail is within Paging's prefetch window, so those rows are loaded. Anything further than
 * that errs toward "not at the bottom", which only ever withholds an acknowledgement, shows the
 * newest FAB, and saves a scroll position.
 */
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
    for (index in 0 until belowViewport) {
        val row = peek(index) ?: return false
        if (policy.effectiveBottom(row)) return false
    }
    return true
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

/**
 * What leaving the timeline should record about the viewport. The distinction that matters is
 * between the two "nothing to save" cases: [ParkAtBottom] ASSERTS the reader left at the live
 * bottom (see [ChatScrollPositionStore.markParkedAtBottom] — the next entry goes to the newest row
 * unless unread arrived in the meantime), while [Forget] only states that this snapshot proves
 * nothing and the saved viewport is dropped without any claim about where the reader was.
 */
internal sealed interface ScrollPositionOutcome {
    /** The viewport provably rests at the effective bottom; record the bottom park. */
    data object ParkAtBottom : ScrollPositionOutcome

    /** A resolvable anchor row; save it as the viewport to restore. */
    data class Save(
        val anchorIndex: Int,
        val row: MessageEntity,
    ) : ScrollPositionOutcome

    /** Indeterminate snapshot: forget the saved viewport without asserting anything. */
    data object Forget : ScrollPositionOutcome
}

/**
 * Classify the viewport at save time (scroll settle / dispose).
 *
 * An empty snapshot is [ScrollPositionOutcome.Forget], and it must be ruled out BEFORE the bottom
 * probe: Paging can swap the outgoing buffer's snapshot for the incoming buffer's empty QUERY
 * snapshot between the caller's index reads and onDispose, and against `itemCount == 0` the
 * effective-bottom loop has nothing below the viewport to reject, so it would vacuously "prove" a
 * bottom the reader may never have been at. The same reasoning covers [nearestAnchorRow] returning
 * null on a non-empty snapshot (nothing but placeholders in probe reach): that path only proves no
 * anchor was found, not a bottom park, so it forgets rather than claims.
 */
internal fun scrollPositionOutcome(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): ScrollPositionOutcome {
    if (itemCount <= 0) return ScrollPositionOutcome.Forget
    if (isAtEffectiveBottom(firstVisibleIndex, firstVisibleOffset, itemCount, peek, policy)) {
        return ScrollPositionOutcome.ParkAtBottom
    }
    val (anchorIndex, row) =
        nearestAnchorRow(firstVisibleIndex, itemCount, peek, policy)
            ?: return ScrollPositionOutcome.Forget
    return ScrollPositionOutcome.Save(anchorIndex, row)
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
    /**
     * This entry target is an intentional destination (e.g. the buffer's last-read marker), so it
     * must displace the viewport even when the retained list state already sits at the bottom.
     */
    val forceScrollOnEntry: Boolean = false,
    /**
     * On entry, place the first-unread row at the TOP of the viewport (mature-chat open-at-unread),
     * with the remaining unread continuing below it. The placement is realized in [ChatScreen]:
     * load the target off-screen (no scroll), measure how many rows fit, then snap the viewport so
     * the first unread tops the window. Only the read-marker entry target sets this.
     */
    val placeAtTop: Boolean = false,
    /** Opaque ViewModel request identity; stale UI completions must not consume a newer jump. */
    val requestToken: Long = 0,
    val highlightEventId: Long? = null,
)

internal fun messageHighlightMatches(
    message: MessageEntity,
    highlightMsgid: String?,
    highlightEventId: Long?,
): Boolean =
    (highlightMsgid != null && message.msgid == highlightMsgid) ||
        (highlightEventId != null && message.id == highlightEventId)

/**
 * Whether a normal open lands on the first unread row rather than on the saved viewport.
 *
 * The first unread row wins only when it is DEEPER than anything the reader has actually had on
 * screen — older than every row this process displayed in the room. Otherwise the saved viewport
 * wins.
 *
 * Depth alone cannot decide this, because "park newer than first-unread" describes two opposite
 * situations that are identical in index space:
 *
 *  - A reader who ENTERED at the divider 300 rows back, read forward to row 100, and left. The read
 *    marker does not advance mid-history — [shouldMarkReadFromViewport] requires the effective
 *    bottom — so the first unread row is still the divider they started from, and reopening there
 *    resets 200 rows of reading, every time, until they once reach the bottom.
 *  - A reader parked 48 rows back for whom a BACKFILL then landed unread history at row 198, older
 *    than anything they have seen. Restoring the park strands that run above the viewport, unseen
 *    and unreachable without scrolling backwards.
 *
 * [furthestDisplayedIndex] is what tells them apart, because it is the only input that reports what
 * the reader's eyes reached rather than what the timeline contains: the first case has a watermark
 * AT the divider, the second one far newer than the backfilled unread. It is emphatically NOT the
 * read marker — see [ChatScrollPositionStore] — and nothing here is ever written back into read
 * state or broadcast as MARKREAD.
 *
 * With no watermark (nothing displayed yet in this process) the rule falls back to depth, which is
 * the previous behavior: the deeper anchor wins, so everything between the two lies BELOW the
 * restored viewport in the reader's forward scroll direction and neither candidate is skipped past.
 *
 * A deep jump (notification or search) precedes both and never reaches here: it is an explicit
 * destination, so it owns positioning outright. Ties in the fallback go to the unread target, which
 * carries the same row plus its top placement.
 */
internal fun firstUnreadWinsEntry(
    savedIndex: Int,
    firstUnreadIndex: Int,
    furthestDisplayedIndex: Int?,
): Boolean =
    when (furthestDisplayedIndex) {
        null -> firstUnreadIndex >= savedIndex

        // A park is by construction a row that was displayed, so the watermark is normally at least as
        // deep as it. The max is what keeps the rule honest if a watermark is ever recorded late.
        else -> firstUnreadIndex > maxOf(savedIndex, furthestDisplayedIndex)
    }

/** [firstUnreadWinsEntry] applied to the two entry targets. */
internal fun preferredEntryTarget(
    saved: ChatPositionTarget?,
    firstUnread: ChatPositionTarget?,
    furthestDisplayedIndex: Int?,
): ChatPositionTarget? =
    when {
        saved == null -> firstUnread
        firstUnread == null -> saved
        firstUnreadWinsEntry(saved.index, firstUnread.index, furthestDisplayedIndex) -> firstUnread
        else -> saved
    }

/**
 * Whether a re-resolved entry anchor names a different place than the one entry actually landed on.
 *
 * Both halves matter. Identity moves when catch-up delivers an older unread row than the one the
 * at-rest resolution could see, and depth moves when it delivers rows behind an anchor whose
 * identity is unchanged — a divider that is still the same message but is now 150 rows further from
 * the bottom. Either one leaves the reader in the wrong place, and nothing else is worth a
 * correction: an unmoved anchor must NOT republish, or the placement already in progress would be
 * restarted against the same row for nothing.
 */
internal fun entryAnchorMoved(
    entered: ChatPositionTarget,
    repaired: ChatPositionTarget,
): Boolean =
    entered.index != repaired.index ||
        entered.expectedEventId != repaired.expectedEventId ||
        entered.serverTime != repaired.serverTime

/**
 * Entry landed at the live bottom with no divider to show for it: nothing resolved as unread at
 * rest, so the target names no row (index 0, no identity) and the visit froze its boundary as
 * ABSENT. Such an entry has produced no outcome that a post-catch-up correction could yank — there
 * is no shown divider to move and no chosen row to abandon — so it stays correctable even after the
 * screen settles it, unlike a placed divider or a restored viewport ([fromSavedPosition]), both of
 * which the reader has already seen.
 */
internal fun nullDividerBottomEntry(
    firstUnread: TimelineAnchor?,
    entered: ChatPositionTarget,
): Boolean =
    firstUnread == null &&
        entered.index == 0 &&
        entered.expectedEventId == null &&
        entered.expectedMsgid == null &&
        !entered.fromSavedPosition

/**
 * [firstUnreadWinsEntry] applied to the two entry indices, for the Pager key.
 *
 * The key must name the anchor entry will LAND on, not merely one deep enough to cover both:
 * keying deeper than the chosen target pushes that target out of the initial window and back into
 * the placeholder scroll this key exists to avoid.
 */
internal fun preferredEntryIndex(
    savedIndex: Int?,
    firstUnreadIndex: Int?,
    furthestDisplayedIndex: Int?,
): Int? =
    when {
        savedIndex == null -> firstUnreadIndex
        firstUnreadIndex == null -> savedIndex
        firstUnreadWinsEntry(savedIndex, firstUnreadIndex, furthestDisplayedIndex) -> firstUnreadIndex
        else -> savedIndex
    }

/**
 * Deepest row the timeline has actually placed on screen, or null while that cannot be proven.
 *
 * Reverse layout: [deepestVisibleIndex] is the LAST entry of `visibleItemsInfo`, the row at the top
 * of the window. The scan walks NEWER only, which is the direction that cannot over-claim — a
 * placeholder at the top edge was displayed as a blank skeleton, not as a row anyone could read, so
 * the watermark falls back to the newest anchorable row at or below it. Under-claiming costs a
 * fallback to depth-only entry; over-claiming would silently suppress a genuine unread entry, so
 * the asymmetry is deliberate.
 */
internal fun displayedDepthAnchor(
    deepestVisibleIndex: Int,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): TimelineAnchor? {
    if (deepestVisibleIndex < 0 || itemCount <= 0) return null
    val start = minOf(deepestVisibleIndex, itemCount - 1)
    val newerEnd = maxOf(0, start - MAX_PLACEHOLDER_PROBES)
    for (index in start downTo newerEnd) {
        val row = peek(index) ?: continue
        if (policy.anchor(row)) return TimelineAnchor(row.serverTime, row.id, row.timelineOrder)
    }
    return null
}

/** Identity-free targets describe an insertion point, which may sit just past the last row. */
internal fun materializableTargetIndex(
    requestedIndex: Int,
    itemCount: Int,
    hasExactIdentity: Boolean,
): Int? =
    when {
        requestedIndex in 0 until itemCount -> requestedIndex
        !hasExactIdentity && requestedIndex == itemCount && itemCount > 0 -> itemCount - 1
        else -> null
    }

/** Find a materialized row by the stable LazyColumn key, never by its pre-layout index. */
internal fun materializedTargetVisibleIndex(
    visibleItems: List<Pair<Any, Int>>,
    eventId: Long,
): Int? = visibleItems.firstOrNull { (key, _) -> key == eventId }?.second

internal fun shouldShowNewestFab(
    atBottom: Boolean,
    autoScrolling: Boolean,
): Boolean = !atBottom && !autoScrolling

/**
 * Viewport acknowledgement is only honest when the viewport's bottom is the conversation's bottom.
 *
 * The mark-read effect reads at-bottom from the CURRENT paging snapshot but acknowledges the room's
 * newest stored row, so everything rests on [atBottom] meaning "there is provably nothing unseen
 * below me". It used to carry a second gate for the one case where those two disagreed: a bounded
 * deep-jump island, whose index 0 was the island's bottom rather than the room's, so reaching it
 * marked newer messages read and uploaded a MARKREAD to every other client.
 *
 * Bounded islands are retired — the timeline is one unbounded list — and that gate is deliberately
 * NOT replaced by a constant. The same disagreement now appears as unloaded rows below the viewport,
 * and it is [isAtEffectiveBottom] that rules them out: a null placeholder below the viewport is not
 * a row the user has seen, so it blocks the bottom. Weakening that predicate re-opens this defect,
 * with no second gate left to catch it.
 */
internal fun shouldMarkReadFromViewport(
    atBottom: Boolean,
    initialPositionSettled: Boolean,
    viewportReadEnabled: Boolean,
): Boolean = viewportReadEnabled && initialPositionSettled && atBottom

/**
 * Newest row the timeline has actually placed on screen, or null while that cannot be proven.
 *
 * [renderedIndex]/[renderedKey] come from the last measure pass; [peek] reads the CURRENT Paging
 * snapshot. Those two disagree whenever rows were presented without being measured — Paging keeps
 * presenting while the screen is paused, and a prepend shifts every index — so an index on its own
 * can name a row that was never displayed. The laid-out row carries its own key, and requiring it
 * to still be the row at that index is what makes the pairing trustworthy. From there the scan
 * walks OLDER only: rows below the rendered position are the ignored tail the effective bottom
 * already treats as settled, and they were not on screen either.
 */
internal fun renderedBottomAnchor(
    renderedIndex: Int,
    renderedKey: Any?,
    itemCount: Int,
    peek: (Int) -> MessageEntity?,
    policy: MessageVisibilityPolicy,
): TimelineAnchor? {
    if (renderedIndex < 0 || renderedIndex >= itemCount) return null
    if (peek(renderedIndex)?.id != renderedKey) return null
    val olderEnd = minOf(itemCount, renderedIndex + MAX_PLACEHOLDER_PROBES)
    for (index in renderedIndex until olderEnd) {
        val row = peek(index) ?: continue
        if (policy.anchor(row)) return TimelineAnchor(row.serverTime, row.id, row.timelineOrder)
    }
    return null
}

/**
 * The anchor one run of the viewport mark-read effect may acknowledge.
 *
 * A steady-state run acknowledges [rawNewest], the room's newest stored row, and has to keep doing
 * so: an ignored raw tail below the viewport is already settled and only that anchor retires it.
 *
 * A resumed run is not steady state. `viewportReadEnabled` keys the effect, so the pause -> resume
 * flip restarts it, and by then everything that arrived while the screen was away is already in
 * [rawNewest] and in the Paging snapshot while nothing has measured it — the acknowledgement would
 * be driven by arrival rather than by display, uploading a MARKREAD for a backlog the user never
 * saw. Clamping that one run to [renderedNewest] lets a resume confirm only rows the timeline
 * actually put on screen. It cannot over-acknowledge either: the clamp only ever moves the anchor
 * older, so [shouldMarkReadFromViewport] still decides whether anything is acknowledged at all.
 */
internal fun viewportMarkReadAnchor(
    rawNewest: TimelineAnchor?,
    renderedNewest: TimelineAnchor?,
    resumed: Boolean,
): TimelineAnchor? {
    val raw = rawNewest ?: return null
    if (!resumed) return raw
    val rendered = renderedNewest ?: return null
    return minOf(rendered, raw)
}

data class ChatScrollPosition(
    val index: Int,
    val offset: Int,
    val msgid: String?,
    val serverTime: Long,
    val rowId: Long,
)

/**
 * Normal entry scroll: an explicit saved viewport or last-read marker always restores. A plain
 * unsaved target only repairs list state retained physically off-bottom; it must not displace an
 * already-bottom conversation.
 */
fun shouldScrollToInitialTarget(
    target: ChatPositionTarget,
    atBottom: Boolean,
): Boolean = target.fromSavedPosition || target.forceScrollOnEntry || !atBottom

/**
 * Index to bring to the bottom (start) of a reversed viewport so that [firstUnreadIndex] lands
 * `rowsFit - 1` rows above it, i.e. at the top of the viewport. Clamps to 0 when fewer than `rowsFit`
 * unread rows exist below the target (the list cannot scroll past index 0, so the first unread
 * then stays in view within the lower viewport with read history above it). Caller must guard
 * `rowsFit >= 1`; an empty measurement would otherwise scroll past the first unread.
 */
internal fun firstUnreadTopAnchorIndex(
    firstUnreadIndex: Int,
    rowsFit: Int,
): Int = (firstUnreadIndex - (rowsFit - 1)).coerceAtLeast(0)

/** Canonical local identity is checked before the case-sensitive opaque wire msgid. */
fun positionTargetMatches(
    target: ChatPositionTarget,
    actual: MessageEntity?,
): Boolean {
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

/** Re-request cadence for a target whose Paging load hint produced no observable load. */
internal const val TARGET_REHINT_INTERVAL_MS = 1_000L

/** Request exactly one placeholder and wait for that position, without scanning the dataset. */
internal suspend fun <T> requestAndAwaitTarget(
    index: Int,
    request: suspend (Int) -> Boolean,
    snapshots: Flow<TargetMaterialization<T>>,
    rehintIntervalMs: Long = TARGET_REHINT_INTERVAL_MS,
): T? {
    val before = snapshots.first()
    if (!request(index)) return null
    var observedLoading = before.loading
    return withTimeoutOrNull(TARGET_MATERIALIZATION_TIMEOUT_MS) {
        while (true) {
            var streamEnded = false
            val terminal =
                withTimeoutOrNull(rehintIntervalMs) {
                    snapshots
                        .firstOrNull { snapshot ->
                            observedLoading = observedLoading || snapshot.loading
                            val replaced = snapshot.generation != before.generation
                            val newFailure = snapshot.failed && (!before.failed || observedLoading || replaced)
                            snapshot.item != null || newFailure ||
                                (!snapshot.addressable && !snapshot.loading) ||
                                ((observedLoading || replaced) && !snapshot.loading)
                        }.also { streamEnded = it == null }
                }
            when {
                terminal != null -> return@withTimeoutOrNull terminal.item

                streamEnded -> return@withTimeoutOrNull null

                // A whole interval passed with no terminal snapshot and no load ever observed for a
                // parked placeholder viewport: Paging can drop the single viewport hint when it
                // races the generation's initial prepend/refresh, and nothing else will ever load
                // the target. Re-issue the idempotent request so the hint is re-recorded instead of
                // sitting quiescent until the outer cap.
                else -> if (!request(index)) return@withTimeoutOrNull null
            }
        }
        // withTimeoutOrNull cancels the loop at the materialization cap.
        @Suppress("UNREACHABLE_CODE")
        null
    }
}

data class ReplyJumpRequest(
    val msgid: String,
)

private val CLIENT_REDACTABLE_MESSAGE_KINDS: Set<MessageKind> =
    setOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)

internal fun canRedactMessage(
    message: MessageEntity,
    bufferType: BufferType?,
    connectionState: IrcClientState?,
): Boolean =
    message.msgid != null &&
        message.kind in CLIENT_REDACTABLE_MESSAGE_KINDS &&
        bufferType != BufferType.SERVER &&
        (connectionState as? IrcClientState.Ready)?.let { hasMessageRedactionCap(it.caps) } == true

sealed interface ChatUiEvent {
    data object InvalidCommand : ChatUiEvent

    data object ReactionBlocked : ChatUiEvent

    data object ReactionTargetUnavailable : ChatUiEvent

    data object ReactionSendFailed : ChatUiEvent

    data object RedactionSendFailed : ChatUiEvent

    data object SendRejected : ChatUiEvent

    /** A submission never reached the wire because the reserved draft no longer matched it. */
    data object SendDropped : ChatUiEvent

    data object NotInChannel : ChatUiEvent

    data class InviteRequestSent(
        val nick: String,
        val channel: String,
    ) : ChatUiEvent

    data object InviteSendFailed : ChatUiEvent

    data class ReplyJumpUnavailable(
        val request: ReplyJumpRequest,
    ) : ChatUiEvent

    data object ConversationLayoutWriteFailed : ChatUiEvent

    data object PresenceModeWriteFailed : ChatUiEvent
}

/** Database-backed conversation layout and the global or buffer-specific default it may inherit. */
data class ConversationLayoutState(
    val global: LayoutDensity = LayoutDensity.COMFORTABLE,
    val override: LayoutDensity? = null,
    val bufferDefault: LayoutDensity? = null,
) {
    val effective: LayoutDensity get() = override ?: bufferDefault ?: global
}

data class ConversationPresenceState(
    val global: PresenceMode = PresenceMode.SMART,
    val override: PresenceMode? = null,
) {
    val effective: PresenceMode get() = override ?: global
}

data class QueuedChatUiEvent(
    val id: Long,
    val value: ChatUiEvent,
)

/** StateFlow-backed FIFO so recreation replays every unacknowledged event exactly once. */
internal class ChatUiEventQueue {
    private val lock = Any()
    private var nextId = 0L
    private val _pending = MutableStateFlow<List<QueuedChatUiEvent>>(emptyList())
    val pending = _pending.asStateFlow()

    fun enqueue(value: ChatUiEvent): QueuedChatUiEvent =
        synchronized(lock) {
            QueuedChatUiEvent(++nextId, value).also { event ->
                _pending.value = _pending.value + event
            }
        }

    fun acknowledge(id: Long) =
        synchronized(lock) {
            _pending.value = _pending.value.filterNot { it.id == id }
        }
}

internal fun ChatUiEvent.hasRetryAction(): Boolean = this is ChatUiEvent.ReplyJumpUnavailable

/** Run a snackbar action before acknowledging its replay-safe queued event. */
internal fun handleChatUiEventResult(
    event: QueuedChatUiEvent,
    actionPerformed: Boolean,
    retryReplyJump: (ReplyJumpRequest) -> Unit,
    acknowledge: (Long) -> Unit,
) {
    if (actionPerformed) {
        when (val value = event.value) {
            is ChatUiEvent.ReplyJumpUnavailable -> retryReplyJump(value.request)
            else -> Unit
        }
    }
    acknowledge(event.id)
}

/**
 * Footer state for the older end of the reverse timeline — the BOTTOM of the list, past the oldest
 * retained row. Scroll-driven paging drives APPEND automatically, so the footer only reflects the
 * current [LoadState.append] plus the connection's history availability; there is no explicit "load
 * older" affordance here.
 *
 * Interior history gaps are not this footer's business. The timeline is presented unbounded, so a
 * gap is a seam drawn between two materialized rows with its own tappable [HistoryGapState] divider
 * ([rowSeam]); it never reaches the bottom of the list and never shows up in [LoadState.append].
 */
sealed interface ChatHistoryUiState {
    /** Nothing to show: server/no buffer, or a Ready timeline mid-history. */
    data object Hidden : ChatHistoryUiState

    /** An APPEND page is in flight (shimmer). */
    data object Loading : ChatHistoryUiState

    /**
     * More history exists and the ladder is armed at this footer, but nothing is on the wire yet.
     * A static status line rather than a spinner: promising motion that is not happening is what
     * made the old permanent shimmer uninformative.
     */
    data object Armed : ChatHistoryUiState

    /** A recoverable append error; the footer offers `items.retry()`. */
    data object Retry : ChatHistoryUiState

    /**
     * The ladder stopped short of the start of history without failing: the last page achieved
     * nothing and nothing may re-issue it unprompted. The footer offers the reader the fetch, which
     * re-arms the same APPEND through `items.retry()`.
     */
    data object LoadOlder : ChatHistoryUiState

    /** History is unreachable: [offline] true when disconnected/fatal, false while negotiating. */
    data class Unavailable(
        val offline: Boolean,
    ) : ChatHistoryUiState

    /** The network does not advertise CHATHISTORY. */
    data object Unsupported : ChatHistoryUiState

    /** Persisted protocol completion: the true start of history. */
    data object ConfirmedStart : ChatHistoryUiState
}

internal fun chatHistoryUiState(
    bufferType: BufferType?,
    connectionState: IrcClientState?,
    availability: HistoryAvailability,
    append: LoadState,
    historyComplete: Boolean,
): ChatHistoryUiState {
    if (bufferType == null || bufferType == BufferType.SERVER) return ChatHistoryUiState.Hidden
    // A final capability decision supersedes a stale mediator error/loading state.
    if (availability == HistoryAvailability.Unsupported) return ChatHistoryUiState.Unsupported
    if (append is LoadState.Loading) return ChatHistoryUiState.Loading
    if (append is LoadState.Error) {
        return when {
            // A stall is not a wire failure and must not be dressed as one: the request went out and
            // was answered, it just could not move the ladder. What the reader wants there is the
            // fetch itself, so offer it rather than an error they did not cause.
            append.error is HistoryLadderStalled -> {
                ChatHistoryUiState.LoadOlder
            }

            availability == HistoryAvailability.NegotiatingOrOffline -> {
                ChatHistoryUiState.Unavailable(offline = isHistoryOffline(connectionState))
            }

            else -> {
                ChatHistoryUiState.Retry
            }
        }
    }
    if (append.endOfPaginationReached && historyComplete) {
        return ChatHistoryUiState.ConfirmedStart
    }
    return when (availability) {
        HistoryAvailability.Unsupported -> {
            ChatHistoryUiState.Unsupported
        }

        HistoryAvailability.NegotiatingOrOffline -> {
            ChatHistoryUiState.Unavailable(offline = isHistoryOffline(connectionState))
        }

        // This footer is an item at the far end of the timeline, so it is composed only once the
        // reader has actually scrolled past the oldest retained row — the exact position where the
        // ladder's APPEND fires. An armed ladder therefore says so, but it says it honestly: only
        // `LoadState.Loading` may render the shimmer. Prefetch fires the APPEND a screenful before
        // the reader arrives, so a real fetch still shows the spinner; what disappears is the
        // permanent spinner that spun over an idle ladder.
        //
        // End-of-pagination without persisted completion stays silent: the direction is finished for
        // this Pager and a status line there would promise a page that is never coming.
        is HistoryAvailability.Ready -> {
            if (append.endOfPaginationReached) ChatHistoryUiState.Hidden else ChatHistoryUiState.Armed
        }
    }
}

/**
 * What the chat title bar reports about history: Paging's own newer-end work folded over the
 * coordinator's reconciliation status, so one spinner covers every way messages can be in flight.
 *
 * REFRESH is included only while [itemCount] is zero. That is exactly the cold open — the generation
 * has no first page yet, the append footer only mirrors APPEND, and the empty state stays hidden
 * until the buffer proves terminally empty, so nothing else on screen would report it. The guard
 * matters because Room invalidates on every inserted message and each invalidation re-runs REFRESH:
 * unguarded, the title would blink on every incoming line while the reader is caught up and reading.
 *
 * APPEND is deliberately absent: the older end has its own footer shimmer at the exact place the
 * reader is looking, and reporting it twice would only make the title busier.
 */
internal fun timelineHistoryStatus(
    refresh: LoadState,
    prepend: LoadState,
    itemCount: Int,
    syncStatus: HistorySyncStatus,
): HistorySyncStatus =
    when {
        itemCount == 0 && refresh is LoadState.Loading -> {
            HistorySyncStatus.Syncing
        }

        prepend is LoadState.Loading -> {
            HistorySyncStatus.Syncing
        }

        prepend is LoadState.Error -> {
            HistorySyncStatus.Failed(
                prepend.error.message ?: "Unable to load newer history",
            )
        }

        else -> {
            syncStatus
        }
    }

/** Appearance grace before the append shimmer may show, matching the title spinner's 140 ms fade. */
internal const val FOOTER_APPEARANCE_DELAY_MS = 140L

/** Once shown, the shimmer holds this long so a page that lands immediately is not a flash. */
internal const val FOOTER_MIN_VISIBLE_MS = 500L

/**
 * Debounces [ChatHistoryUiState] for the append footer. Pure and clock-free: the caller injects
 * [nowMs], so the whole policy is testable without a wall clock or a real frame clock.
 *
 * Two windows, both aimed at the same defect — a footer that flicks as PagingSource generations
 * churn Loading/Hidden/Armed within a few frames:
 * - [FOOTER_APPEARANCE_DELAY_MS] before Loading may appear, so a page that resolves inside the
 *   window never paints a spinner at all.
 * - [FOOTER_MIN_VISIBLE_MS] before a shown Loading may collapse, so a spinner that did appear stays
 *   readable instead of blinking out one frame later.
 *
 * Terminal and actionable states ([ChatHistoryUiState.ConfirmedStart], [ChatHistoryUiState.Unsupported],
 * [ChatHistoryUiState.Retry], [ChatHistoryUiState.LoadOlder]) pass through immediately: they are
 * answers, not transitions, and delaying them only withholds what the reader can act on.
 */
internal class FooterStatePresenter {
    private var presented: ChatHistoryUiState = ChatHistoryUiState.Hidden
    private var loadingSinceMs: Long? = null
    private var lastLoadingSeenMs: Long? = null
    private var shownSinceMs: Long? = null

    fun resolve(
        candidate: ChatHistoryUiState,
        nowMs: Long,
    ): ChatHistoryUiState {
        if (isImmediate(candidate)) return settle(candidate)
        if (candidate == ChatHistoryUiState.Loading) {
            // A Loading that returns within the flick window continues the same burst and keeps the
            // original appearance deadline; a genuinely new fetch (a long quiet gap) earns its own.
            val gap = lastLoadingSeenMs?.let { nowMs - it }
            if (loadingSinceMs == null || (gap != null && gap >= FOOTER_MIN_VISIBLE_MS)) {
                loadingSinceMs = nowMs
            }
            lastLoadingSeenMs = nowMs
            if (presented == ChatHistoryUiState.Loading) return ChatHistoryUiState.Loading
            val since = nowMs - (loadingSinceMs ?: nowMs)
            if (since < FOOTER_APPEARANCE_DELAY_MS) return presented
            shownSinceMs = nowMs
            presented = ChatHistoryUiState.Loading
            return presented
        }
        // Collapsing away from a visible shimmer waits out its minimum-visible window.
        val shownFor = shownSinceMs?.let { nowMs - it }
        if (presented == ChatHistoryUiState.Loading && shownFor != null && shownFor < FOOTER_MIN_VISIBLE_MS) {
            return ChatHistoryUiState.Loading
        }
        shownSinceMs = null
        presented = candidate
        return presented
    }

    /** Adopts [candidate] outright, clearing every pending window. */
    private fun settle(candidate: ChatHistoryUiState): ChatHistoryUiState {
        loadingSinceMs = null
        lastLoadingSeenMs = null
        shownSinceMs = null
        presented = candidate
        return presented
    }

    private fun isImmediate(candidate: ChatHistoryUiState): Boolean =
        when (candidate) {
            ChatHistoryUiState.ConfirmedStart,
            ChatHistoryUiState.Unsupported,
            ChatHistoryUiState.Retry,
            ChatHistoryUiState.LoadOlder,
            -> true

            else -> false
        }
}

internal fun isHistoryOffline(connectionState: IrcClientState?): Boolean =
    when (connectionState) {
        IrcClientState.Disconnected -> true
        is IrcClientState.Failed -> connectionState.fatal
        else -> false
    }

/** Retries each offline mediator failure once when its connection generation is Ready. */
internal class HistoryReadyRetryGate {
    private var retriedError: Throwable? = null

    fun update(
        availability: HistoryAvailability,
        append: LoadState,
    ): Boolean {
        if (availability == HistoryAvailability.Unsupported) return false
        val error = (append as? LoadState.Error)?.error ?: return false
        if (error !is io.github.trevarj.motd.irc.client.IrcDisconnectedException) return false
        if (availability !is HistoryAvailability.Ready || retriedError === error) return false
        retriedError = error
        return true
    }
}

/**
 * Aggregate raw [ReactionEntity] rows into per-msgid chip lists: one chip per emoji with its count,
 * ownership, and first-seen display spelling for each deduplicated reactor. Ordered by first
 * appearance for stability.
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
    val myActorKeys =
        buildSet {
            myAccount?.takeUnless { it.isEmpty() || it == "*" }?.let { add("account:$it") }
            myNick?.let { nick ->
                add(identityRules.actorKey(nick, account = null))
                if (myAccount != null) add(identityRules.actorKey(nick, myAccount))
            }
        }
    val myNormalizedNick = myNick?.let(identityRules::normalize)
    // msgid -> emoji -> aggregated ownership and display nicks
    val byMsg = LinkedHashMap<String, LinkedHashMap<String, MutableReactionAgg>>()
    for (r in reactions) {
        val emojiMap = byMsg.getOrPut(r.targetMsgid) { LinkedHashMap() }
        val agg = emojiMap.getOrPut(r.emoji) { MutableReactionAgg() }
        val normalizedSender = identityRules.normalize(r.sender)
        // Account-tag availability can differ between live, push, and history copies of one event.
        // Keep one first-seen display spelling for account/nick aliases of the same casemapped nick.
        agg.reactorDisplayNames.putIfAbsent(normalizedSender, r.sender)
        if (
            r.actorKey in myActorKeys ||
            (
                myAccount == null && myNormalizedNick != null &&
                    normalizedSender == myNormalizedNick
            )
        ) {
            agg.mine = true
        }
    }
    return byMsg.mapValues { (_, emojiMap) ->
        emojiMap.map { (emoji, agg) ->
            ReactionChip(
                emoji = emoji,
                count = agg.reactorDisplayNames.size,
                mine = agg.mine,
                reactorDisplayNames = agg.reactorDisplayNames.values.toList(),
            )
        }
    }
}

private class MutableReactionAgg(
    var mine: Boolean = false,
    val reactorDisplayNames: LinkedHashMap<String, String> = LinkedHashMap(),
)
