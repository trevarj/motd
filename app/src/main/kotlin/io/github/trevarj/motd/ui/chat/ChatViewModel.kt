package io.github.trevarj.motd.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.AudioMetadataRepository
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.CachedAudioMetadata
import io.github.trevarj.motd.audio.DirectMediaPolicy
import io.github.trevarj.motd.avatar.AvatarController
import io.github.trevarj.motd.avatar.ConversationAvatarOutcome
import io.github.trevarj.motd.avatar.NoopAvatarController
import io.github.trevarj.motd.bouncer.isBouncerConsole
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ComposerDraftEntity
import io.github.trevarj.motd.data.db.DccTransferDao
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.UserDao
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.AccountReminderStore
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NoopAccountReminderStore
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.repo.NetworkIgnoreRepository
import io.github.trevarj.motd.data.repo.NoopNetworkIgnoreRepository
import io.github.trevarj.motd.data.repo.entryAnchorPagingKey
import io.github.trevarj.motd.data.sync.HistoryGapFiller
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.data.sync.NoopHistoryGapFiller
import io.github.trevarj.motd.data.sync.historySource
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.dcc.DccTransferController
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.canSendReactionTags
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.service.SendRejectionReason
import io.github.trevarj.motd.service.TypingTracker
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.nav.ChatRoute
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.share.PendingShareStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** CTCP wraps its request text in this delimiter inside an ordinary PRIVMSG. */
private const val CTCP_DELIMITER = "\u0001"

private const val MAX_REPLY_PREVIEW_CACHE = 128
private const val MAX_REACTION_WINDOW_MSGIDS = 500

/**
 * Single UI state for the chat screen. `pagingFlow` is the cached message stream;
 * `replyTo`/`typingNicks`/`connState` drive the composer + header. `members` feeds autocomplete.
 */
data class ChatState(
    val buffer: BufferEntity? = null,
    val memberCount: Int? = null,
    val typingNicks: List<String> = emptyList(),
    val replyTo: MessageEntity? = null,
    // Null means the buffer/connection snapshot has not loaded yet. Do not use Disconnected as a
    // loading sentinel: it briefly paints a false status while entering an already-connected chat.
    val connState: IrcClientState? = null,
    val presence: Map<PresenceKey, PresenceState> = emptyMap(),
    val conversationLayout: ConversationLayoutState = ConversationLayoutState(),
    val conversationPresence: ConversationPresenceState = ConversationPresenceState(),
    // True for a CHANNEL buffer we are no longer a member of (server-confirmed or reflected self-PART).
    // Drives the "You're not in #channel — Rejoin" banner and disables the composer.
    val parted: Boolean = false,
    val isSidecar: Boolean = false,
    val sidecarsEnabled: Boolean = true,
)

data class ComposerDraftState(
    val text: String = "",
    val hydrated: Boolean = false,
    val revision: Long = 0,
)

private data class DraftSnapshot(
    val roomId: Long,
    val text: String,
    val replyToEventId: Long?,
    val revision: Long,
)

/** One user-visible draft revision may produce at most one outbound submission. */
private data class DraftSubmissionKey(
    val roomId: Long,
    val revision: Long,
)

private sealed interface DraftCommand {
    data class Persist(
        val snapshot: DraftSnapshot,
    ) : DraftCommand

    data class PrepareSubmission(
        val snapshot: DraftSnapshot,
        val result: CompletableDeferred<ComposerDraftEntity?>,
    ) : DraftCommand

    data class ClearSubmission(
        val snapshot: DraftSnapshot,
        val persisted: ComposerDraftEntity,
        val result: CompletableDeferred<Boolean>,
    ) : DraftCommand
}

private data class PreparedDraftSubmission(
    val snapshot: DraftSnapshot,
    val persisted: ComposerDraftEntity?,
)

/**
 * Why a submission did or did not reserve the rendered draft.
 *
 * A bare null used to cover both refusals, which made a genuinely lost send indistinguishable from
 * the deliberate duplicate-tap guard: the message never went out, the composer never cleared, and
 * nothing was reported. [Duplicate] stays silent because the first tap is already on its way;
 * [Stale] is a real loss and must put the text back and say so.
 */
private sealed interface DraftSubmissionOutcome {
    data class Prepared(
        val submission: PreparedDraftSubmission,
    ) : DraftSubmissionOutcome

    data object Duplicate : DraftSubmissionOutcome

    data object Stale : DraftSubmissionOutcome
}

internal fun MessageEntity.toReplyPreviewData(): ReplyPreviewData = ReplyPreviewData(sender, text, ircFormattedText)

/**
 * Wire text for resending a failed row. An ACTION is stored with its `/me ` prefix stripped, so
 * re-prefix it; the manager rewrites `/me ` back into a CTCP ACTION. Non-ACTION kinds resend
 * verbatim.
 */
fun resendText(
    kind: io.github.trevarj.motd.data.db.MessageKind,
    text: String,
): String = if (kind == io.github.trevarj.motd.data.db.MessageKind.ACTION) "/me $text" else text

/**
 * Recreate the repository Paging generation when a behavioral filter changes. Transforming the
 * same PagingData from `combine` would emit its single-collector pageEventFlow a second time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun repositoryMessagePages(
    source: (MessageVisibilitySpec) -> Flow<PagingData<MessageEntity>>,
    specs: Flow<MessageVisibilitySpec>,
): Flow<PagingData<MessageEntity>> = specs.flatMapLatest(source)

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel
    @Inject
    constructor(
        private val savedStateHandle: SavedStateHandle,
        private val messageRepository: MessageRepository,
        private val bufferRepository: BufferRepository,
        private val networkIdentityDao: NetworkIdentityDao,
        private val dccTransferDao: DccTransferDao,
        private val dccTransferController: DccTransferController,
        private val connectionManager: ConnectionManager,
        private val typingTracker: TypingTracker,
        private val foregroundBufferTracker: ForegroundBufferTracker,
        private val linkPreviewRepository: LinkPreviewRepository,
        private val audioMetadataRepository: AudioMetadataRepository,
        private val audioPlaybackController: AudioPlaybackController,
        private val draftStore: ComposerDraftStore,
        private val pendingShareStore: PendingShareStore,
        // Gesture-orb hand-off; the plain default keeps hand-built call sites free of another store.
        private val attachmentRequestStore: AttachmentRequestStore = AttachmentRequestStore(),
        private val scrollPositionStore: ChatScrollPositionStore,
        // The single wire-fetch primitive; the jump AROUND prefetch admits on its per-network wire gate.
        private val historyPageLoader: HistoryPageLoader,
        private val settingsRepository: SettingsRepository,
        private val replyPrefs: ReplyPrefs,
        private val visibilityReader: MessageVisibilityReader,
        private val historyResyncCoordinator: HistoryResyncController,
        private val userDao: UserDao,
        // Narrow seam over HistoryGapFillCoordinator; the Noop default keeps hand-built call sites
        // (tests) free of a live history transport, exactly as networkIgnoreRepository does below.
        private val gapFiller: HistoryGapFiller = NoopHistoryGapFiller,
        private val networkIgnoreRepository: NetworkIgnoreRepository = NoopNetworkIgnoreRepository,
        private val avatarController: AvatarController = NoopAvatarController,
        private val accountReminderStore: AccountReminderStore = NoopAccountReminderStore,
        // The app's own decision journal, handed to the screen so the timeline's Paging generations can
        // be journalled alongside the history-fetch decisions that cause them. Public because the
        // consumer is the composable, not this class; Noop default for hand-built call sites, as
        // gapFiller above.
        val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
        // Fail-closed default for hand-built call sites: the global image/video stacks cannot be
        // routed through a network proxy, so unknown transport policy means no direct media fetches.
        private val directMediaPolicy: DirectMediaPolicy = DirectMediaPolicy { false },
        contentPreviewPrefs: ContentPreviewPrefs,
    ) : ViewModel() {
        val contentPreviews: StateFlow<ContentPreviewConfig> =
            contentPreviewPrefs.config
                // Start closed until DataStore emits, so a persisted opt-out cannot race initial composition.
                .stateIn(
                    viewModelScope,
                    SharingStarted.Eagerly,
                    ContentPreviewConfig(showImages = false, showLinkPreviews = false),
                )
        val replyConfig: StateFlow<ReplyConfig> =
            replyPrefs.config
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReplyConfig())

        private val route: ChatRoute = savedStateHandle.toRoute<ChatRoute>()
        val bufferId: Long = route.bufferId

        /** This screen was opened by a deep link/notification tap rather than by a plain room open. */
        private val routeHasDeepJump: Boolean =
            route.jumpToTime > 0 || route.jumpToEventId != null || route.jumpToMsgid != null

        // Behavioral filter (JPQ visibility + fools HIDE). Distinct so unrelated settings edits don't
        // re-emit the paging stream. ChatState is untouched — the screen collects
        // [settings] separately (mirrors R1 keeping the 5-ary combine stable).
        private val _hiddenFoolsRevealed = MutableStateFlow(false)
        val hiddenFoolsRevealed: StateFlow<Boolean> = _hiddenFoolsRevealed.asStateFlow()

        // The conversation's own presence choice participates in the paging spec: it changes which rows
        // the PagingSource emits, so switching it re-queries exactly like a global settings change does.
        private val filterSpecs =
            combine(
                settingsRepository.settings,
                _hiddenFoolsRevealed,
                bufferRepository.observeBuffer(bufferId).map { it?.presenceModeOverride }.distinctUntilChanged(),
            ) { settings, revealHiddenFools, presenceOverride ->
                MessageVisibilitySpec.from(settings, presenceOverride).copy(revealHiddenFools = revealHiddenFools)
            }.distinctUntilChanged()
        private val filterSpec =
            filterSpecs
                .stateIn(viewModelScope, SharingStarted.Eagerly, MessageVisibilitySpec())

        // Published by [runHistoryGapFills]. Both start conservative — no transport, nothing failed —
        // so a seam composed before the rule has evaluated anything offers its retry rather than a
        // spinner that would never resolve.
        private val historyUnavailable = MutableStateFlow(true)
        private val failedGapIds = MutableStateFlow<Set<Long>>(emptySet())

        /**
         * The room's history seams, joined with what each one is doing: the gaps a load is running for,
         * whether there is any transport to load with, and the gaps whose last attempt failed.
         *
         * A seam's position comes from the gap itself, so this list does not depend on where the
         * viewport is. Which seams land in a rendered slot is decided per row by [rowSeam] against the
         * neighbors Paging actually materialized.
         *
         * It DOES depend on the behavioral filter, and on the same [filterSpecs] the Pager is keyed
         * from: a position is only comparable against rows the same spec admits, so changing the filter
         * re-resolves the seams exactly as it re-creates the paging generation.
         */
        val timelineSeams: StateFlow<TimelineSeamState> =
            combine(
                filterSpecs.flatMapLatest { messageRepository.observeTimelineSeams(bufferId, it) },
                gapFiller.fillsInFlight,
                historyUnavailable,
                failedGapIds,
            ) { seams, filling, unavailable, failed ->
                TimelineSeamState(seams, filling, unavailable, failed)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineSeamState())

        /** The viewport's demand for history, reported by the timeline. See [setSeamPrefetch]. */
        private val seamPrefetch = MutableStateFlow(SeamPrefetch())

        private var nextGapTapToken = 0L
        private val gapTapRequest = MutableStateFlow<GapTapRequest?>(null)

        /**
         * What the viewport says about history, debounced by the caller: the seams within loading reach
         * of its older edge, and where the reader is.
         *
         * This is the rule's only demand signal, so an empty set is a statement too (nothing is within
         * reach) and must be reported. Both halves matter: the set says WHICH seams may load,
         * [viewportAnchor] — the identity of the NEWEST row on screen — says whether the reader has
         * moved since the last load, which is what stops a stationary viewport fetching twice.
         * [olderEdgeIndex] is carried for the journal only; see [SeamPrefetch] for why comparing an
         * index there was the runaway.
         */
        fun setSeamPrefetch(
            viewportAnchor: TimelineAnchor?,
            olderEdgeIndex: Int,
            gapIds: Set<Long>,
        ) {
            val next = SeamPrefetch(viewportAnchor, gapIds, olderEdgeIndex)
            if (seamPrefetch.value != next) seamPrefetch.value = next
        }

        /**
         * Tap on a failed seam's divider: the retry, and the only tap the timeline still has.
         *
         * Routed through the same request stream and the same [SeamLoadingRule] as a scroll-driven
         * load, deliberately. Calling the coordinator directly from here would put a second fetch in
         * flight beside the timeline's own, which is the contention the mediator's boundary floor and the
         * loader's flight key exist to keep off the wire.
         */
        fun fillGap(gapId: Long) {
            gapTapRequest.value = GapTapRequest(gapId, ++nextGapTapToken)
        }

        /**
         * Run the timeline's history rule: scrolling toward a seam loads across it, exactly as scrolling
         * to the end of the list appends.
         *
         * ONE sequential collector owns every load this screen starts, and that is load-bearing rather
         * than tidy. `combine` runs its transform and this collector in the same coroutine, so
         * [SeamLoadingRule.next] cannot decide a new load while another is suspended here: each
         * request is settled before the next is offered, no two seams are ever loading at once, and the
         * retry tap cannot race the scroll-driven path into the coordinator's single flight.
         *
         * Started here rather than in [init] because it needs [timelineSeams] and [entryState], and
         * collecting the seam flow is also what keeps the seam observer alive.
         */
        private fun runHistoryGapFills() =
            viewModelScope.launch {
                val rule = SeamLoadingRule()
                val gate =
                    combine(
                        visibleSession,
                        historyAvailability,
                        connState,
                        entryState,
                    ) { session, availability, connection, entry ->
                        SeamLoadingGate(
                            onScreen = session != null,
                            historyReady = availability is HistoryAvailability.Ready,
                            entrySettled = entry !is EntryPositionState.Pending,
                            // NOT `!historyReady`: a fresh connection spends a moment negotiating, and painting
                            // "couldn't load" across every seam for that moment is an error the reader never had.
                            historyUnreachable = historyUnreachable(availability, connection),
                        )
                    }.distinctUntilChanged().onEach { historyUnavailable.value = it.historyUnreachable }
                combine(
                    operationalBufferId,
                    gate,
                    timelineSeams,
                    seamPrefetch,
                    gapTapRequest,
                ) { roomId, currentGate, seams, prefetch, tap ->
                    val decision = rule.next(roomId, currentGate, seams.seams, prefetch, tap)
                    // One line per DECISION, not per frame: the combine's inputs are all distinct-until-
                    // changed, so this is a handful of records per room open. It is the only place the gate's
                    // three inputs, the seam list and the viewport's demand are all in scope at once, which
                    // is what makes "demand never reported", "gate never armed" and "rule refused" separable
                    // in a journal instead of producing the identical silence.
                    journalSeamDecision(roomId, currentGate, seams, prefetch, tap, decision)
                    (decision as? SeamDecision.Start)?.request
                }.filterNotNull().collect { request ->
                    // Publish before as well as after: a retry tap clears its gap's failure, and the divider
                    // must show the load it started rather than the error it is already retrying.
                    failedGapIds.value = rule.failedGapIds
                    val progress = gapFiller.fillGap(request.roomId, request.gapId)
                    rule.settle(request, progress)
                    failedGapIds.value = rule.failedGapIds
                    // Pairs with the coordinator's own gap_fill_started/gap_fill_ended: those say what the
                    // fetch did, this says what the timeline's rule made of it, so a fill that ran and
                    // achieved nothing is distinguishable from one that was never asked for.
                    diagnostics.record("chat_history", "seam_fill_settled") {
                        mapOf(
                            "room_id" to request.roomId,
                            "gap_id" to request.gapId,
                            "from_tap" to request.fromTap,
                            "progress" to progress.name,
                            "failed_count" to rule.failedGapIds.size,
                        )
                    }
                }
            }

        /**
         * Journal one [SeamDecision] with every input that could have produced it.
         *
         * Fields are ids, counts, booleans and the decision's own fixed classification string — nothing
         * user-derived, per [DiagnosticLogger]'s contract. `decision` rather than `reason` deliberately:
         * the journal redacts any field literally named `reason`.
         */
        private fun journalSeamDecision(
            roomId: Long,
            gate: SeamLoadingGate,
            seams: TimelineSeamState,
            prefetch: SeamPrefetch,
            tap: GapTapRequest?,
            decision: SeamDecision,
        ) = diagnostics.record("chat_history", "seam_rule_evaluated") {
            mapOf(
                "room_id" to roomId,
                "decision" to decision.journalName,
                "gap_id" to (decision as? SeamDecision.Start)?.request?.gapId,
                "on_screen" to gate.onScreen,
                "history_ready" to gate.historyReady,
                "entry_settled" to gate.entrySettled,
                "history_unreachable" to gate.historyUnreachable,
                "seam_count" to seams.seams.size,
                "recoverable_count" to seams.seams.count { it.recoverable },
                "demand_count" to prefetch.gapIds.size,
                // The rule compares `viewport_event_id`, never the index: the older edge is evidence
                // that a re-measure is not a scroll, and the two must stay separable in the journal.
                "older_edge_index" to prefetch.olderEdgeIndex,
                "viewport_event_id" to prefetch.viewportAnchor?.eventId,
                "failed_count" to seams.failed.size,
                "has_tap" to (tap != null),
            )
        }

        /** Every visibility change cancels the old generation and creates a positionally exact Pager. */
        val messages: Flow<PagingData<MessageEntity>> =
            filterSpecs
                .flatMapLatest { spec ->
                    flow {
                        // Resolve the open-at-first-unread anchor BEFORE creating the Pager so a deep
                        // entry collects ONE generation keyed from birth. Keying the already-collected
                        // stream by swapping in a second Pager mid-presentation parks the new
                        // generation's first page behind the cachedIn handoff, which left the reopened
                        // timeline stuck on the stale generation with refresh loading (blank reopen).
                        emitAll(messageRepository.messages(bufferId, spec, entryPagingKey(spec)))
                    }
                }.cachedIn(viewModelScope)

        /**
         * Pager initial key for the one-shot normal entry, taken from the SAME at-rest resolution the
         * entry decision publishes ([entryAnchors]). Null unless a pending entry sits beyond the default
         * newest load, so first-open backfill, escapes and mentions keep their unkeyed newest-first load.
         * A deep jump is excluded outright: its destination is the jump target, not the entry anchor, and
         * it reaches that row by requesting the placeholder inside this SAME generation rather than by
         * rebuilding the Pager around it.
         *
         * BOTH entry anchors are considered, and the key names the one [preferredEntryTarget] will
         * actually land on — resolved through [preferredEntryIndex], the same rule, on the same inputs
         * — because a saved viewport parked 400 rows into history is exactly as far outside the newest
         * load as a deep unread anchor, and reaching it by scrolling to an unloaded placeholder is the
         * churn this key exists to avoid.
         *
         * The entry pipeline still positions precisely; state that converges after this snapshot at
         * worst yields an unkeyed-style placeholder scroll, never a wrong position.
         */
        private suspend fun entryPagingKey(spec: MessageVisibilitySpec): Int? {
            if (routeHasDeepJump) return null
            if (_entryState.value !is EntryPositionState.Pending) return null
            return try {
                entryAnchors(spec).pagingKey
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: RuntimeException) {
                // The key is an optimization; the timeline must present regardless.
                null
            }
        }

        /**
         * Everything the one-shot normal entry decides, resolved from at-rest state alone.
         *
         * [hasDurableContent] is what separates "enter now" from "there is genuinely nothing to show":
         * a stored read/mute anchor, a saved viewport, or a single retained row is already enough to
         * place a viewport without asking the network for anything.
         */
        private data class EntryAnchors(
            val room: BufferEntity?,
            val firstUnread: TimelineAnchor?,
            val target: ChatPositionTarget,
            val pagingKey: Int?,
            val hasDurableContent: Boolean,
        )

        private val entryAnchorsLock = Mutex()
        private var resolvedEntryAnchors: Pair<MessageVisibilitySpec, EntryAnchors>? = null

        /**
         * The single at-rest entry resolution, computed on first demand and then shared.
         *
         * The Pager key and the entry decision used to run these 6-8 indexed queries independently, and
         * agreed only because both applied the same rule to the same inputs. Resolving once makes that
         * identity structural and halves what an open costs.
         *
         * Cached against its spec rather than outright: a behavioral filter change rebuilds the Pager
         * around a different set of rows, and an index counted under the previous spec would name a
         * different row in it.
         */
        private suspend fun entryAnchors(spec: MessageVisibilitySpec): EntryAnchors =
            entryAnchorsLock.withLock {
                resolvedEntryAnchors?.takeIf { it.first == spec }?.second
                    ?: resolveEntryAnchors(spec, discardUnresolvedSaved = false)
                        .also { resolvedEntryAnchors = spec to it }
            }

        /**
         * Resolve the entry anchors against the store exactly as it stands.
         *
         * A normal open chooses between two anchors — the oldest unread row and the viewport this room
         * was last left at — with the bare read marker as the fallback for a room that offers neither.
         * A deep link owns its own target and never reaches here.
         *
         * Leaving at the live bottom retires the second anchor rather than the first: it is a statement
         * about where the reader stopped, and nothing about what arrived afterwards. So a park decides
         * only the caught-up case, and the unread divider keeps entry whenever this visit has one.
         *
         * The read marker used to divert entry on its own, which made the saved viewport unreachable:
         * every room anyone has opened HAS a read anchor, so the restore branch ran only for rooms that
         * had never been read. That is the "leaving and re-entering does not restore my position"
         * defect; [preferredEntryTarget] states the rule that replaces it, and the furthest-displayed
         * watermark is the input that keeps that rule from resetting a reader who is working FORWARD
         * through an unread backlog they have not yet finished.
         *
         * All three indices are comparable because they are the same count: `countNewerThan` and
         * `countTimelineNewer` are both `countTimelineNewerQuery` over this room and spec — pinned by
         * MessageRepositoryPagingTest so neither can grow a predicate the other lacks.
         *
         * [discardUnresolvedSaved] forgets a saved viewport whose anchor no longer resolves, which is
         * right once history has caught up (the row is gone and nothing will bring it back) and wrong
         * for the immediate entry, which can run before catch-up has finished writing: there, "cannot
         * resolve" usually means "not stored yet".
         */
        private suspend fun resolveEntryAnchors(
            spec: MessageVisibilitySpec,
            discardUnresolvedSaved: Boolean,
        ): EntryAnchors {
            val room = bufferRepository.observeBuffer(bufferId).firstOrNull()
            val marker = room?.let { visibilityReader.effectiveLocalReadAnchor(it) }
            // The timeline is unbounded, so this resolves against every retained row. That is the same
            // answer a bounded call would give: a window bound could only ever hide rows the client
            // already holds, and a gap's far side is by definition rows it does not.
            //
            // A never-read room has no marker to resolve from, but the chat-list cue still counts every
            // visible row it holds as unread (its SQL COALESCEs the missing anchor to time zero), so the
            // badge promises a divider. Resolving from an epoch anchor keeps that promise: first open of
            // a room someone else has already written to lands at the divider instead of silently
            // parking at the bottom the cue said had N new messages above it. SERVER buffers are outside
            // that promise — the chat list never shows them — so their first open keeps the bottom.
            val epochAnchor =
                TimelineAnchor(0, 0, Long.MIN_VALUE)
                    .takeIf { room?.type != BufferType.SERVER }
            val firstUnread =
                (marker ?: epochAnchor)?.let {
                    visibilityReader.firstVisibleUnreadAnchor(bufferId, it, spec)
                }
            // A park cleared the saved viewport, so a follower has exactly one possible anchor: the
            // unread divider, which they land on when unread arrived while they were away.
            val parked = parkedAtBottom()
            val saved = if (parked) null else restoredScrollPosition(spec, discardUnresolvedSaved)
            val furthestDisplayedIndex = furthestDisplayedEntryIndex(spec)
            val unreadTarget =
                firstUnread?.let {
                    readMarkerEntryTarget(it, spec, requireExactIdentity = true)
                }
            val target =
                if (parked) {
                    // A reader who left at the live bottom was following the conversation, so they return to
                    // it — but only when there is nothing unread to return to instead. Messages that arrived
                    // while they were away are exactly what the divider exists to announce, and entering at
                    // the newest row buries it somewhere above the viewport, where finding out what was
                    // missed means scrolling BACKWARDS past the very messages the divider was drawn for.
                    //
                    // Only the unread anchor may divert a follower, never the bare read marker: every room
                    // anyone has read HAS a marker, so consulting it here would park them behind a divider
                    // on a room that has nothing new at all.
                    unreadTarget ?: ChatPositionTarget(index = 0)
                } else {
                    preferredEntryTarget(saved, unreadTarget, furthestDisplayedIndex)
                        ?: marker?.let { readMarkerEntryTarget(it, spec, requireExactIdentity = false) }
                        ?: ChatPositionTarget(index = 0)
                }
            return EntryAnchors(
                room = room,
                firstUnread = firstUnread,
                target = target,
                // The key must name the anchor entry LANDS on, which is why it runs the same rule over
                // the same indices rather than picking the deeper of the two.
                pagingKey =
                    preferredEntryIndex(
                        savedIndex = saved?.index,
                        firstUnreadIndex = unreadTarget?.index,
                        furthestDisplayedIndex = furthestDisplayedIndex,
                    )?.let(::entryAnchorPagingKey),
                // Ordered cheapest-first: the extra row lookup only runs for a room with no stored
                // anchor and no saved viewport at all.
                hasDurableContent =
                    marker != null ||
                        saved != null ||
                        visibilityReader.latestRawAnchor(bufferId) != null,
            )
        }

        /**
         * True when the reader left this room at the live bottom, using the same room-with-buffer
         * fallback keying as the rest of the viewport memory.
         */
        private fun parkedAtBottom(): Boolean {
            val roomId = operationalBufferId.value
            return scrollPositionStore.isParkedAtBottom(roomId) ||
                (bufferId != roomId && scrollPositionStore.isParkedAtBottom(bufferId))
        }

        /**
         * Timeline index of the deepest row this process has displayed in the room, or null if none.
         *
         * Counted with the same `countTimelineNewerQuery` the two entry anchors use, so all three live
         * in one coordinate system. The identity-resolving overload is deliberate: a coalesced
         * watermark row follows its winner rather than counting against a retired event id.
         */
        private suspend fun furthestDisplayedEntryIndex(spec: MessageVisibilitySpec): Int? {
            val roomId = operationalBufferId.value
            val seen =
                scrollPositionStore.furthestDisplayed(roomId)
                    ?: bufferId.takeIf { it != roomId }?.let(scrollPositionStore::furthestDisplayed)
                    ?: return null
            return visibilityReader.countTimelineNewer(bufferId, seen.serverTime, seen.eventId, spec)
        }

        /** Newest stored wire row, including ignored tails; effective bottom may acknowledge it. */
        val rawNewestAnchor: StateFlow<TimelineAnchor?> =
            visibilityReader
                .observeLatestRawAnchor(bufferId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        /** Full settings for the timeline (friends/fools/foolsMode/nick styling); collected in the screen. */
        val settings: StateFlow<Settings> =
            settingsRepository.settings
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

        fun setHiddenFoolsRevealed(revealed: Boolean) {
            _hiddenFoolsRevealed.value = revealed
        }

        private val uiEventQueue = ChatUiEventQueue()
        val uiEvents: StateFlow<List<QueuedChatUiEvent>> = uiEventQueue.pending

        fun acknowledgeUiEvent(id: Long) = uiEventQueue.acknowledge(id)

        private val replyTo = MutableStateFlow<MessageEntity?>(null)
        private val draftStateLock = Any()
        private val draftCommands = Channel<DraftCommand>(Channel.UNLIMITED)
        private val draftHydrated = CompletableDeferred<Unit>()
        private val _composerDraft = MutableStateFlow(ComposerDraftState())
        val composerDraft: StateFlow<ComposerDraftState> = _composerDraft.asStateFlow()
        private var currentDraftText = ""
        private var currentReplyToEventId: Long? = null
        private var nextDraftRevision = 0L
        private var draftTextEdited = false
        private var draftReplyEdited = false

        // A duplicate click can arrive before the UI has observed the accepted draft clear. Keep the
        // reservation with the draft state so every entry point gets the same exactly-once behavior.
        private val inFlightDraftSubmissions = mutableSetOf<DraftSubmissionKey>()
        private val _outgoingFlight = MutableStateFlow<OutgoingFlight?>(null)

        /**
         * The send currently travelling from the composer into the timeline, or null. The screen reads
         * this to fly a ghost bubble and to hold the landing row hidden until the ghost arrives.
         */
        val outgoingFlight: StateFlow<OutgoingFlight?> = _outgoingFlight.asStateFlow()
        private var nextFlightToken = 0L
        private val _members = MutableStateFlow<List<MemberEntity>>(emptyList())
        private val _memberNicks = MutableStateFlow<List<String>>(emptyList())
        val memberNicks: StateFlow<List<String>> = _memberNicks.asStateFlow()
        private val _memberCount = MutableStateFlow<Int?>(null)
        private var membersJob: Job? = null

        private val buffer: StateFlow<BufferEntity?> =
            bufferRepository
                .observeBuffer(bufferId)
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        val joinedChannels: StateFlow<List<JoinedChannelRow>> =
            buffer
                .flatMapLatest { room ->
                    room?.let { bufferRepository.observeJoinedChannels(it.networkId) } ?: flowOf(emptyList())
                }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

        /**
         * Whether the app-global image/video stacks (Coil, ExoPlayer) may fetch network content for
         * this buffer. Starts false so a proxied network cannot leak a direct fetch during the initial
         * lookup; flips once the network row confirms it uses no obfuscated transport, or once the user
         * opts into direct media on proxied networks. Recomputes on that opt-in too, so flipping the
         * setting reveals previews in the open conversation without a buffer switch.
         */
        val directMediaAllowed: StateFlow<Boolean> =
            combine(
                buffer.mapNotNull { it?.networkId }.distinctUntilChanged(),
                contentPreviews.map { it.directMediaOnProxiedNetworks }.distinctUntilChanged(),
            ) { networkId, _ -> networkId }
                .map { networkId -> directMediaPolicy.directMediaAllowed(networkId) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, false)

        /** The database remains authoritative: a completed write is reflected only after Room emits. */
        val conversationLayout: StateFlow<ConversationLayoutState> =
            combine(
                buffer,
                settingsRepository.settings,
            ) { room, settings ->
                ConversationLayoutState(
                    global = settings.layoutDensity,
                    override = room?.layoutDensityOverride,
                    bufferDefault = LayoutDensity.TWO_LINE.takeIf { room?.type == BufferType.SERVER },
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationLayoutState())

        /** Presence-event presentation for this conversation; null override inherits the global mode. */
        val conversationPresence: StateFlow<ConversationPresenceState> =
            combine(
                buffer,
                settingsRepository.settings,
            ) { room, settings ->
                ConversationPresenceState(
                    global = settings.presenceMode,
                    override = room?.presenceModeOverride,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationPresenceState())

        /** The route id may be a durable redirect; all live screen behavior uses the winner id. */
        private val operationalBufferId: StateFlow<Long> =
            buffer
                .map { it?.id ?: bufferId }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.Eagerly, bufferId)

        private val connState: StateFlow<IrcClientState?> =
            buffer
                .combine(connectionManager.connectionActivity) { buffer, activity ->
                    buffer?.let { activity.states[it.networkId] ?: IrcClientState.Disconnected }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        private val persistedIdentity =
            buffer
                .flatMapLatest { current ->
                    current?.let { room ->
                        networkIdentityDao.observe(room.networkId)
                    } ?: flowOf(null)
                }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** Live ISUPPORT wins while connected; persisted rules keep offline rendering deterministic. */
        val identityRules: StateFlow<IrcIdentityRules> =
            combine(
                buffer,
                connState,
                persistedIdentity,
            ) { current, connection, persisted ->
                if (current != null && connection is IrcClientState.Ready) {
                    connectionManager.clientFor(current.networkId)?.isupport?.identityRules
                        ?: persisted?.identityRules
                        ?: IrcIdentityRules()
                } else {
                    persisted?.identityRules ?: IrcIdentityRules()
                }
            }.distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IrcIdentityRules())

        /**
         * Normalized nicks whose @mentions are colored inside message bodies.
         *
         * Deliberately independent of [ensureMembersObserved] and of [RosterLoadState]: mention coloring
         * must land on the same frame as the message text, so it reads the locally cached roster eagerly
         * and never waits for an authoritative NAMES/WHOX round trip. A stale cached nick colors
         * correctly; the autocomplete/moderation roster keeps its authoritative gating below. The
         * nick-only projection plus the Default-dispatched normalize keep the eager read cheap on busy
         * channels.
         */
        val knownNicks: StateFlow<Set<String>> =
            bufferRepository
                .observeMemberNicks(bufferId)
                .distinctUntilChanged()
                .combine(identityRules) { nicks, rules -> nicks.mapTo(mutableSetOf(), rules::normalize) }
                .distinctUntilChanged()
                .flowOn(Dispatchers.Default)
                .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

        val historyAvailability: StateFlow<HistoryAvailability> =
            combine(buffer, connState) { current, connection ->
                when {
                    current == null -> HistoryAvailability.Unsupported

                    // The bouncer console is a real CHATHISTORY target; every other SERVER room is not.
                    current.type == BufferType.SERVER && !current.isBouncerConsole -> HistoryAvailability.Unsupported

                    connection !is IrcClientState.Ready -> HistoryAvailability.NegotiatingOrOffline

                    // Through the service seam rather than reaching into the live client: the UI has no
                    // business reading a protocol object's CAP/ISUPPORT state, and the accessor is what
                    // lets a test model a negotiated history wire without standing up a transport.
                    else -> connectionManager.historyAvailabilityFor(current.networkId)
                }
            }.distinctUntilChanged().stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                HistoryAvailability.NegotiatingOrOffline,
            )

        private data class OwnIdentityLookup(
            val networkId: Long,
            val nick: String,
            val normalizedNick: String,
        )

        private data class OwnIdentity(
            val nick: String?,
            val account: String?,
        )

        private val ownIdentity =
            combine(
                buffer,
                connState,
                identityRules,
                persistedIdentity,
            ) { current, connection, rules, persisted ->
                val nick = (connection as? IrcClientState.Ready)?.nick ?: persisted?.selfNick
                if (current == null || nick == null) {
                    null
                } else {
                    OwnIdentityLookup(
                        current.networkId,
                        nick,
                        rules.normalize(nick),
                    )
                }
            }.distinctUntilChanged().flatMapLatest { lookup ->
                lookup?.let {
                    userDao
                        .observeByNick(it.networkId, it.normalizedNick)
                        .map { user -> OwnIdentity(it.nick, user?.account) }
                } ?: flowOf(OwnIdentity(null, null))
            }

        private var nextVisibleSession = 0L
        private val visibleSession = MutableStateFlow<Long?>(null)

        init {
            viewModelScope.launch { runDraftWriter() }
            viewModelScope.launch {
                combine(buffer, visibleSession) { currentBuffer, session ->
                    currentBuffer?.id?.takeIf { session != null }
                }.distinctUntilChanged().collect(foregroundBufferTracker::set)
            }
        }

        val historySyncStatus: StateFlow<HistorySyncStatus> =
            operationalBufferId
                .flatMapLatest(historyResyncCoordinator::syncStatus)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistorySyncStatus.Idle)

        /**
         * Manual retry behind the failed-sync pill. It reuses the ordinary reconciliation entry point so
         * a tap racing the reconnect pass collapses onto that flight instead of opening a second one;
         * HistoryPageLoader still serializes the wire request and EventProcessor still writes Room.
         * Without a buffer or a live client there is nothing to reconcile, so the tap is a no-op.
         */
        fun retryHistorySync() =
            viewModelScope.launch {
                val currentBuffer = buffer.value ?: return@launch
                val client = connectionManager.clientFor(currentBuffer.networkId) ?: return@launch
                historyResyncCoordinator.reconcileBuffer(
                    buffer = currentBuffer,
                    client = client,
                    isCurrent = { connectionManager.clientFor(currentBuffer.networkId) === client },
                )
            }

        /** Dismisses the settled "history may be incomplete" chip and its chat-list badge. */
        fun dismissHistorySyncStatus() {
            val bufferId = buffer.value?.id ?: return
            historyResyncCoordinator.dismissSyncStatus(bufferId)
        }

        private val typingNicks =
            operationalBufferId
                .flatMapLatest(typingTracker::typingNicks)

        val state: StateFlow<ChatState> =
            combine(
                buffer,
                _memberCount,
                typingNicks,
                replyTo,
                connState.combine(connectionManager.presenceStates) { conn, presence -> conn to presence },
            ) { buffer, memberCount, typing, reply, connAndPresence ->
                val (conn, presence) = connAndPresence
                ChatState(
                    buffer = buffer,
                    memberCount = memberCount,
                    typingNicks = typing,
                    replyTo = reply,
                    connState = conn,
                    presence = presence,
                    parted = buffer?.type == BufferType.CHANNEL && !buffer.joined && buffer.pendingCloseAt == null,
                    isSidecar = buffer?.networkId?.let(connectionManager::isSidecarNetwork) == true,
                    sidecarsEnabled = connectionManager.sidecarsEnabled(),
                )
            }.combine(conversationLayout) { current, layout ->
                current.copy(conversationLayout = layout)
            }.combine(conversationPresence) { current, presence ->
                current.copy(conversationPresence = presence)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatState())

        val accountSetupReminder: StateFlow<Boolean> =
            combine(state, accountReminderStore.accountReminders) { chat, ids ->
                chat.buffer?.networkId in ids
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        fun dismissAccountSetupReminder() {
            state.value.buffer?.networkId?.let { networkId ->
                viewModelScope.launch { accountReminderStore.setAccountReminder(networkId, false) }
            }
        }

        /** Persist to the canonical id captured at the time of selection; Room then drives the UI. */
        fun setConversationLayoutOverride(override: LayoutDensity?) =
            viewModelScope.launch {
                val requestedId = operationalBufferId.value
                val written =
                    try {
                        bufferRepository.setLayoutDensityOverride(requestedId, override)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                if (!written) uiEventQueue.enqueue(ChatUiEvent.ConversationLayoutWriteFailed)
            }

        /** Persist to the canonical id captured at the time of selection; Room then drives the UI. */
        fun setPresenceModeOverride(override: PresenceMode?) =
            viewModelScope.launch {
                val requestedId = operationalBufferId.value
                val written =
                    try {
                        bufferRepository.setPresenceModeOverride(requestedId, override)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        false
                    }
                if (!written) uiEventQueue.enqueue(ChatUiEvent.PresenceModeWriteFailed)
            }

        /**
         * Member rosters can be large on public IRC channels. Do not collect the full rows as part of
         * the first chat-state emission. The screen requests them only when autocomplete or nick
         * moderation needs the roster, so opening a busy channel never rebuilds every visible message
         * merely to show a member count. Mention coloring does not come through here: it reads the
         * nick-only [knownNicks] projection so it never waits on this explicit request.
         */
        fun ensureMembersObserved() {
            if (membersJob == null) {
                membersJob =
                    viewModelScope.launch {
                        combine(
                            bufferRepository.observeMembers(bufferId).distinctUntilChanged(),
                            connectionManager.rosterStates,
                            operationalBufferId,
                        ) { members, rosterStates, roomId -> members to rosterStates[roomId] }
                            .distinctUntilChanged()
                            .collect { (members, rosterState) ->
                                val authoritative = rosterState == RosterLoadState.LOADED
                                val nicks =
                                    withContext(Dispatchers.Default) {
                                        if (authoritative) members.map { it.nick } else emptyList()
                                    }
                                _members.value = if (authoritative) members else emptyList()
                                _memberNicks.value = nicks
                                _memberCount.value = members.size.takeIf { authoritative }
                            }
                    }
            }
            viewModelScope.launch { connectionManager.requestMembers(operationalBufferId.value) }
        }

        // --- reactions aggregation ---

        /** Msgids from the bounded loaded Paging window, supplied by the screen on page changes. */
        private val visibleMsgids = MutableStateFlow<List<String>>(emptyList())

        fun setVisibleMsgids(ids: List<String>) {
            if (visibleMsgids.value != ids) visibleMsgids.value = ids
        }

        /**
         * Reaction chips keyed by msgid, aggregated in the VM so the value survives across message
         * arrivals (no blank frame from an emptyList re-seed) and picks up echo-confirm msgid swaps as
         * reactions arrive. The bounded loaded-window msgid list stays below SQLite's bind-variable
         * limit and avoids aggregating historical reaction rows when a populated chat opens.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        val reactionChips: StateFlow<Map<String, List<ReactionChip>>> =
            combine(
                visibleMsgids
                    .map { it.take(MAX_REACTION_WINDOW_MSGIDS) }
                    .distinctUntilChanged()
                    .flatMapLatest { ids ->
                        if (ids.isEmpty()) flowOf(emptyList()) else messageRepository.reactions(bufferId, ids)
                    },
                ownIdentity,
                identityRules,
            ) { visibleReactions, identity, rules ->
                aggregateReactions(visibleReactions, identity.nick, identity.account, rules)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

        // Reply previews are requested only by composed rows. The bounded cache shares an in-flight
        // Room lookup across recompositions and its WhileSubscribed policy cancels unused collection.
        private val replyPreviewCache =
            object : LinkedHashMap<String, StateFlow<ReplyPreviewData?>>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, StateFlow<ReplyPreviewData?>>): Boolean = size > MAX_REPLY_PREVIEW_CACHE
            }
        private val dccTransferCache =
            object : LinkedHashMap<Long, StateFlow<DccTransferEntity?>>() {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, StateFlow<DccTransferEntity?>>): Boolean = size > MAX_REPLY_PREVIEW_CACHE
            }

        fun replyPreview(msgid: String): StateFlow<ReplyPreviewData?> =
            synchronized(replyPreviewCache) {
                replyPreviewCache.getOrPut(msgid) {
                    createReplyPreviewFlow(msgid, initialValue = null)
                }
            }

        fun dccTransfer(message: MessageEntity): StateFlow<DccTransferEntity?> =
            synchronized(dccTransferCache) {
                dccTransferCache.getOrPut(message.id) {
                    dccTransferDao
                        .observeByTimelineEventId(message.id)
                        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
                }
            }

        fun acceptDccTransfer(
            transferId: Long,
            destination: Uri,
            allowPrivateEndpoint: Boolean,
        ) {
            viewModelScope.launch {
                dccTransferController.acceptIncoming(transferId, destination, allowPrivateEndpoint)
            }
        }

        fun rejectDccTransfer(transferId: Long) {
            viewModelScope.launch { dccTransferController.reject(transferId) }
        }

        fun removeDccTransfer(transferId: Long) {
            viewModelScope.launch { dccTransferController.removeRecord(transferId) }
        }

        fun sendDccFile(source: Uri) {
            viewModelScope.launch {
                dccTransferController.sendFile(operationalBufferId.value, source)
            }
        }

        private fun createReplyPreviewFlow(
            msgid: String,
            initialValue: ReplyPreviewData?,
        ): StateFlow<ReplyPreviewData?> =
            messageRepository
                .observeByMsgid(bufferId, msgid)
                .map { it?.toReplyPreviewData() }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue)

        // --- read marker snapshot ---

        // Frozen on buffer entry so the "New messages" divider keeps a stable boundary instead of
        // flashing/vanishing as markRead advances the live marker. Used ONLY for the divider now.
        //
        // The freeze is per VISIT, and until it was persisted only a live ViewModel carried it: rotation
        // kept the divider but a process death dropped it, and the init freeze below then re-derived it
        // from a read anchor that had already advanced past everything the user had not read on entry.
        // SavedStateHandle belongs to this destination's NavBackStackEntry — the same handle that
        // already carries the entry-position latches — so persisting here gives the boundary exactly the
        // visit's lifetime: it survives process death, and popping the destination discards it so a
        // deliberate re-entry recomputes.
        private val unreadEntrySnapshotRestored =
            savedStateHandle.get<Boolean>(UNREAD_SNAPSHOT_COMPUTED_KEY) == true
        private val _unreadEntrySnapshot =
            MutableStateFlow(
                restoredUnreadEntrySnapshot(
                    computed = unreadEntrySnapshotRestored,
                    markerServerTime = savedStateHandle.get<Long>(UNREAD_SNAPSHOT_TIME_KEY) ?: 0L,
                    markerEventId = savedStateHandle.get<Long>(UNREAD_SNAPSHOT_EVENT_KEY) ?: 0L,
                    markerTimelineOrder = savedStateHandle.get<Long>(UNREAD_SNAPSHOT_ORDER_KEY) ?: 0L,
                    loadedCount = savedStateHandle.get<Int>(UNREAD_SNAPSHOT_COUNT_KEY) ?: 0,
                    lowerBound = savedStateHandle.get<Boolean>(UNREAD_SNAPSHOT_LOWER_BOUND_KEY) == true,
                ),
            )
        val unreadEntrySnapshot: StateFlow<UnreadEntrySnapshot?> = _unreadEntrySnapshot.asStateFlow()

        /**
         * Write the frozen boundary through to SavedState, absence included: the computed flag is what
         * stops a later process from re-freezing this visit against advanced durable state.
         */
        private fun persistUnreadEntrySnapshot(snapshot: UnreadEntrySnapshot?) {
            savedStateHandle[UNREAD_SNAPSHOT_COMPUTED_KEY] = true
            savedStateHandle[UNREAD_SNAPSHOT_TIME_KEY] = snapshot?.marker?.serverTime ?: 0L
            savedStateHandle[UNREAD_SNAPSHOT_EVENT_KEY] = snapshot?.marker?.eventId ?: 0L
            savedStateHandle[UNREAD_SNAPSHOT_ORDER_KEY] = snapshot?.marker?.timelineOrder ?: 0L
            savedStateHandle[UNREAD_SNAPSHOT_COUNT_KEY] = snapshot?.loadedCount ?: 0
            savedStateHandle[UNREAD_SNAPSHOT_LOWER_BOUND_KEY] = snapshot?.lowerBound == true
        }

        // Live read marker for the scroll-to-bottom FAB badge: unlike the frozen snapshot, this tracks
        // the buffer's real read marker, so once markRead advances it (at bottom) the badge count drops
        // to 0 and stays 0 when scrolling back up — instead of counting already-read messages until
        // re-entry (bug: badge doesn't clear until leaving the chat).
        val localReadAnchor: StateFlow<TimelineAnchor?> =
            buffer
                .map { room -> room?.let { visibilityReader.effectiveLocalReadAnchor(it) } }
                .distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        suspend fun countUnreadBelowViewport(
            firstVisibleIndex: Int,
            marker: TimelineAnchor,
        ): Int =
            visibilityReader.countVisibleUnreadInTimelinePrefix(
                bufferId = bufferId,
                beforeIndex = firstVisibleIndex,
                after = marker,
                maxCount = 100,
                spec = filterSpec.value,
            )

        /** Exact nearest unread mention below the viewport, as a global timeline index. */
        suspend fun nearestUnreadMentionBelow(
            firstVisibleIndex: Int,
            marker: TimelineAnchor,
        ): ChatPositionTarget? {
            val target =
                visibilityReader.nearestUnreadMentionBelow(
                    bufferId = bufferId,
                    beforeIndex = firstVisibleIndex,
                    after = marker,
                    spec = filterSpec.value,
                ) ?: return null
            return ChatPositionTarget(
                index =
                    messageRepository.countNewerThan(
                        bufferId,
                        target.serverTime,
                        target.id,
                        filterSpec.value,
                    ),
                expectedEventId = target.id,
                expectedMsgid = target.msgid,
                serverTime = target.serverTime,
            )
        }

        // --- lifecycle: foreground tracker + mark-read ---

        fun onResume() {
            AutoFollowTrace.record("chat_resume", operationalBufferId.value)
            foregroundBufferTracker.set(operationalBufferId.value)
            if (visibleSession.value == null) visibleSession.value = ++nextVisibleSession
        }

        fun onPause() {
            AutoFollowTrace.record("chat_pause", operationalBufferId.value)
            foregroundBufferTracker.set(null)
            visibleSession.value = null
            // Leaving the chat acknowledges a settled history error: the user has had the in-chat
            // detail (stale chip / retry pill) on screen, so the chat-list badge must not keep
            // nagging. Transient Queued/Syncing states are left untouched.
            val bufferId = operationalBufferId.value ?: return
            val status = historyResyncCoordinator.syncStatuses.value[bufferId]
            if (status is HistorySyncStatus.Partial || status is HistorySyncStatus.Failed) {
                historyResyncCoordinator.dismissSyncStatus(bufferId)
            }
        }

        /**
         * Mark read up to [anchor] while this chat destination is resumed. Navigation can retain or
         * precompose the screen while another portrait destination is visible, so viewport callbacks
         * outside the visible session must not advance local or remote read state.
         */
        fun markRead(anchor: TimelineAnchor) {
            if (visibleSession.value == null || anchor.serverTime <= 0 || anchor.eventId <= 0) return
            val roomId = operationalBufferId.value
            AutoFollowTrace.record("markread_request", roomId) {
                "marker=${anchor.serverTime}:${anchor.eventId}"
            }
            viewModelScope.launch { connectionManager.markRead(roomId, anchor) }
        }

        fun acceptInvite(messageId: Long) =
            viewModelScope.launch {
                connectionManager.acceptInvite(messageId)
            }

        fun dismissInvite(messageId: Long) =
            viewModelScope.launch {
                connectionManager.dismissInvite(messageId)
            }

        // --- composer actions ---

        fun setReply(message: MessageEntity?) {
            // The selected parent is already in memory. Seed its lookup so the optimistic outgoing row
            // renders the real quote on its first frame instead of flashing the unresolved placeholder.
            message?.msgid?.let { msgid ->
                synchronized(replyPreviewCache) {
                    if (replyPreviewCache[msgid]?.value == null) {
                        replyPreviewCache[msgid] =
                            createReplyPreviewFlow(
                                msgid = msgid,
                                initialValue = message.toReplyPreviewData(),
                            )
                    }
                }
            }
            synchronized(draftStateLock) {
                draftReplyEdited = true
                replyTo.value = message
                currentReplyToEventId = message?.id
                draftCommands.trySend(DraftCommand.Persist(advanceDraftRevisionLocked()))
            }
        }

        fun sendTyping(active: Boolean) =
            viewModelScope.launch {
                connectionManager.sendTyping(operationalBufferId.value, if (active) "active" else "done")
            }

        /**
         * React to [message] with [emoji]. A confirmed message sends immediately. An own message still
         * pending its echo has `msgid == null`, so the tap MUST NOT be silently dropped (bug: reacting to
         * a just-sent message "sometimes did nothing"): defer the send until the row's msgid lands, then
         * fire the TAGMSG. The optimistic own-reaction chip is upserted inside sendReact, so it appears
         * instantly and reconciles with the server echo without duplicating. On queue timeout (echo never
         * arrived) surface a snackbar rather than failing silently.
         */
        fun react(
            message: MessageEntity,
            emoji: String,
        ) = viewModelScope.launch {
            val ready = connState.value as? IrcClientState.Ready
            val removing =
                message.msgid?.let { msgid ->
                    reactionChips.value[msgid]?.firstOrNull { it.emoji == emoji }?.mine
                } == true
            if (ready == null || !canSendReactionTags(ready.caps, ready.isupport, removing)) {
                uiEventQueue.enqueue(ChatUiEvent.ReactionBlocked)
                return@launch
            }
            val msgid = message.msgid ?: resolveReactionMsgid(message.id)
            if (msgid == null) {
                uiEventQueue.enqueue(ChatUiEvent.ReactionTargetUnavailable)
                return@launch
            }
            try {
                connectionManager.sendReact(operationalBufferId.value, msgid, emoji)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                uiEventQueue.enqueue(ChatUiEvent.ReactionSendFailed)
            }
        }

        fun redact(message: MessageEntity) =
            viewModelScope.launch {
                val msgid = message.msgid ?: return@launch
                if (!connectionManager.redactMessage(operationalBufferId.value, msgid)) {
                    uiEventQueue.enqueue(ChatUiEvent.RedactionSendFailed)
                }
            }

        /**
         * A bouncer without labeled-response can echo our send before its durable msgid is available.
         * The normal chat-open reconciliation may already own the network-wide history gate for target
         * discovery or gap repair. Use the coordinator's urgent newest-page path so msgid recovery only
         * waits for the client's current wire request, not the whole network pass. The Room observer
         * runs concurrently so either a socket echo or that history page resolves the reaction.
         */
        private suspend fun resolveReactionMsgid(messageId: Long): String? =
            coroutineScope {
                val reconciliation =
                    launch {
                        val currentBuffer = buffer.value
                        val client = currentBuffer?.let { connectionManager.clientFor(it.networkId) }
                        if (currentBuffer != null && client != null) {
                            historyResyncCoordinator.reconcilePendingMessage(
                                buffer = currentBuffer,
                                client = client,
                                isCurrent = {
                                    connectionManager.clientFor(currentBuffer.networkId) === client
                                },
                            )
                        }
                    }
                try {
                    messageRepository.awaitMsgid(messageId, REACT_QUEUE_TIMEOUT_MS)
                } finally {
                    reconciliation.cancelAndJoin()
                }
            }

        /** Retry mutates the same durable row; no replacement deletion is involved. */
        fun retry(message: MessageEntity) =
            viewModelScope.launch {
                val rejection = connectionManager.retryMessage(message.id) as? SendAcceptance.Rejected
                if (rejection != null) {
                    val event =
                        if (rejection.reason == SendRejectionReason.NOT_IN_CHANNEL) {
                            ChatUiEvent.NotInChannel
                        } else {
                            ChatUiEvent.SendRejected
                        }
                    uiEventQueue.enqueue(event)
                }
            }

        /** Delete a failed local row without resending (action-sheet delete affordance). */
        fun deleteFailed(message: MessageEntity) =
            viewModelScope.launch {
                messageRepository.deleteMessage(message.id)
            }

        /** Re-join the current channel after a self-PART (local or reflected from a bouncer). */
        fun rejoinChannel() =
            viewModelScope.launch {
                val currentBuffer = buffer.value ?: return@launch
                if (currentBuffer.type != BufferType.CHANNEL) return@launch
                connectionManager.joinChannel(currentBuffer.networkId, currentBuffer.ircTarget)
            }

        suspend fun linkPreview(
            url: String,
            networkId: Long?,
        ): LinkPreview? = if (contentPreviews.value.showLinkPreviews) linkPreviewRepository.preview(url, networkId) else null

        fun cachedLinkPreview(
            url: String,
            networkId: Long?,
        ) = if (contentPreviews.value.showLinkPreviews) linkPreviewRepository.cachedPreview(url, networkId) else null

        suspend fun audioMetadata(
            url: String,
            networkId: Long?,
        ): AudioMetadata? = if (contentPreviews.value.showLinkPreviews) audioMetadataRepository.metadata(url, networkId) else null

        fun cachedAudioMetadata(url: String): CachedAudioMetadata? = if (contentPreviews.value.showLinkPreviews) audioMetadataRepository.cached(url) else null

        val audioPlaybackState = audioPlaybackController.state
        val audioWaveforms = audioPlaybackController.waveforms
        val audioCacheStatuses = audioPlaybackController.cacheStatuses

        fun toggleAudio(request: AudioPlaybackRequest) = audioPlaybackController.toggle(request)

        fun inspectAudioCache(attachment: AudioAttachment) = audioPlaybackController.inspectCache(attachment)

        fun seekAudio(
            attachment: AudioAttachment,
            positionMs: Long,
        ) = audioPlaybackController.seekTo(attachment.playbackId, positionMs)

        fun setAudioSpeed(
            attachment: AudioAttachment,
            speed: Float,
        ) = audioPlaybackController.setSpeed(attachment.playbackId, speed)

        fun toggleActiveAudio() = audioPlaybackController.toggleActive()

        fun cancelAudioLoading() = audioPlaybackController.cancelLoading()

        fun retryActiveAudio() = audioPlaybackController.retryActive()

        fun dismissActiveAudio() =
            audioPlaybackController.state.value.activeId
                ?.let(audioPlaybackController::dismiss)

        /**
         * Parse [raw] and execute the resulting [ChatCommand]. `onOpenBuffer` navigates for /join,
         * /msg, and /query; `onOpenChannelList` navigates for /list. Clears the reply and stops typing
         * on a normal send.
         *
         * A SERVER buffer is a raw-send surface: every submission is sent as a raw IRC
         * line to the network (one leading `/` stripped) — [parseCommand] is bypassed, and a PRIVMSG to
         * `"*"` is never sent.
         */
        fun submit(
            raw: String,
            onOpenBuffer: (Long) -> Unit,
            onOpenChannelList: (Long) -> Unit = {},
        ) = viewModelScope.launch {
            val current = state.value.buffer
            val networkId = current?.networkId
            // The bouncer console is a PRIVMSG surface, not a raw-line one: search can open it, and a
            // raw send would put `sasl set-password <secret>` on the wire and store it unredacted.
            if (current?.type == BufferType.SERVER && !current.isBouncerConsole) {
                submitRawLine(networkId, raw)
                return@launch
            }
            when (val cmd = parseCommand(raw)) {
                is ChatCommand.None -> {
                    Unit
                }

                is ChatCommand.Message -> {
                    // Sending parks the author at the live bottom: the screen scrolls there as part of
                    // the same gesture. Entry positioning can still be pending here (an empty room is
                    // still waiting on history, or the screen has not materialized the target yet), and
                    // while it is pending the screen keeps the whole auto-follow machinery disarmed, so
                    // the echoed message would land with the viewport anchored one row above it.
                    // Abandon the stale one-shot positioning exactly as the newest FAB does — including
                    // the post-catch-up correction — since a divider entry is moot for a reader already
                    // conversing at the bottom.
                    // Launch the ghost before anything suspends, so it leaves the composer on the same
                    // frame as the tap that cleared it rather than after the durable write.
                    //
                    // An emote is deliberately not flown: the manager rewrites it into an ACTION row
                    // whose text has lost the `/me ` prefix, so the ghost would neither match its
                    // landing row nor look anything like the tinted action bubble it becomes.
                    val flight = if (isActionCommand(cmd.text)) null else launchFlight(cmd.text)
                    jumpToNewest()
                    val roomId = operationalBufferId.value
                    val submission =
                        reserveDraftForSend(raw) ?: run {
                            flight?.let(::abandonFlight)
                            return@launch
                        }
                    try {
                        val result =
                            connectionManager.sendMessage(
                                roomId,
                                cmd.text,
                                submission.snapshot.replyToEventId,
                            )
                        if (result is SendAcceptance.Accepted) {
                            // The ghost can only aim at a row once the send has durable identities,
                            // and only if the pipeline stored what the ghost is showing: a reply can
                            // gain a `nick: ` prefix and newlines split one submission into several
                            // rows. Claiming those would yank a row that is already animating in.
                            flight?.let {
                                if (acceptedRowMatchesFlight(result, cmd.text)) {
                                    attachFlightEvents(it, result.eventIds)
                                } else {
                                    abandonFlight(it)
                                }
                            }
                            clearDraftSubmission(submission)
                            connectionManager.sendTyping(roomId, "done")
                        } else if (result is SendAcceptance.Rejected) {
                            flight?.let(::abandonFlight)
                            republishDraft()
                            val event =
                                if (result.reason == SendRejectionReason.NOT_IN_CHANNEL) {
                                    ChatUiEvent.NotInChannel
                                } else {
                                    ChatUiEvent.SendRejected
                                }
                            uiEventQueue.enqueue(event)
                        }
                    } finally {
                        releaseDraftSubmission(submission.snapshot)
                    }
                }

                is ChatCommand.Join -> {
                    networkId?.let { nid ->
                        sendCommand(nid, IrcMessage(command = "JOIN", params = listOfNotNull(cmd.channels, cmd.keys)))
                        val rules = identityRules.value
                        val target = rules.normalize(cmd.channels.substringBefore(','))
                        withTimeoutOrNull(30_000L) {
                            bufferRepository
                                .observeChatList()
                                .mapNotNull { rows ->
                                    rows
                                        .firstOrNull {
                                            it.networkId == nid &&
                                                it.type == BufferType.CHANNEL &&
                                                rules.normalize(it.displayName) == target
                                        }?.bufferId
                                }.first()
                        }?.let(onOpenBuffer)
                    }
                }

                is ChatCommand.Part -> {
                    networkId?.let { nid ->
                        val channel =
                            state.value.buffer
                                ?.takeIf { it.type == BufferType.CHANNEL }
                                ?.ircTarget
                                ?: return@let
                        sendCommand(nid, IrcMessage(command = "PART", params = listOfNotNull(channel, cmd.reason)))
                    }
                }

                is ChatCommand.Hop -> {
                    networkId?.let { nid ->
                        val channel =
                            state.value.buffer
                                ?.takeIf { it.type == BufferType.CHANNEL }
                                ?.ircTarget
                                ?: return@let
                        sendCommand(nid, IrcMessage(command = "PART", params = listOfNotNull(channel, cmd.reason)))
                        sendCommand(nid, IrcMessage(command = "JOIN", params = listOf(channel)))
                    }
                }

                is ChatCommand.Msg -> {
                    networkId?.let { nid ->
                        val target = connectionManager.ensureQueryBuffer(nid, cmd.nick)
                        val channelContext =
                            state.value.buffer
                                ?.takeIf { it.type == BufferType.CHANNEL }
                                ?.ircTarget
                        val submission = reserveDraftForSend(raw) ?: return@let
                        try {
                            when (connectionManager.sendMessage(target, cmd.text, channelContext = channelContext)) {
                                is SendAcceptance.Accepted -> {
                                    clearDraftSubmission(submission)
                                    onOpenBuffer(target)
                                }

                                is SendAcceptance.Rejected -> {
                                    republishDraft()
                                    uiEventQueue.enqueue(ChatUiEvent.SendRejected)
                                }
                            }
                        } finally {
                            releaseDraftSubmission(submission.snapshot)
                        }
                    }
                }

                is ChatCommand.Query -> {
                    networkId?.let { nid ->
                        onOpenBuffer(connectionManager.ensureQueryBuffer(nid, cmd.nick))
                    }
                }

                is ChatCommand.Nick -> {
                    networkId?.let { nid ->
                        sendCommand(nid, IrcMessage(command = "NICK", params = listOf(cmd.nick)))
                    }
                }

                is ChatCommand.Topic -> {
                    networkId?.let { nid ->
                        val channel = state.value.buffer?.ircTarget ?: return@launch
                        sendCommand(nid, IrcMessage(command = "TOPIC", params = listOf(channel, cmd.topic)))
                    }
                }

                is ChatCommand.Away -> {
                    networkId?.let { nid ->
                        sendCommand(nid, IrcMessage(command = "AWAY", params = listOfNotNull(cmd.message)))
                    }
                }

                is ChatCommand.Notice -> {
                    networkId?.let { nid ->
                        sendCommand(
                            nid,
                            IrcMessage(command = "NOTICE", params = listOf(cmd.target, cmd.text)),
                            channelContext =
                                state.value.buffer
                                    ?.takeIf { it.type == BufferType.CHANNEL }
                                    ?.ircTarget,
                        )
                    }
                }

                is ChatCommand.SetName -> {
                    networkId?.let { nid ->
                        sendCommand(nid, IrcMessage(command = "SETNAME", params = listOf(cmd.realname)))
                    }
                }

                // An omitted target addresses the conversation itself, so `/mode +m` works in a channel
                // and `/mode` alone queries the modes already set.
                is ChatCommand.Mode -> {
                    networkId?.let { nid ->
                        val target = cmd.target ?: state.value.buffer?.ircTarget ?: return@let
                        // MODE's flags and their arguments are separate parameters. Passing them as one
                        // string would serialize to a single trailing param (`MODE #c :+o alice`), which a
                        // server reads as one nonsensical mode token.
                        val modeParams =
                            cmd.modes
                                ?.split(' ')
                                ?.filter { it.isNotEmpty() }
                                .orEmpty()
                        sendCommand(nid, IrcMessage(command = "MODE", params = listOf(target) + modeParams))
                    }
                }

                is ChatCommand.Invite -> {
                    networkId?.let { nid ->
                        val channel =
                            cmd.channel
                                ?: state.value.buffer
                                    ?.takeIf { it.type == BufferType.CHANNEL }
                                    ?.ircTarget
                                ?: return@let
                        sendCommand(nid, IrcMessage(command = "INVITE", params = listOf(cmd.nick, channel)))
                    }
                }

                is ChatCommand.Knock -> {
                    networkId?.let { nid ->
                        sendCommand(
                            nid,
                            IrcMessage(command = "KNOCK", params = listOfNotNull(cmd.channel, cmd.reason)),
                        )
                    }
                }

                // A CTCP request is a PRIVMSG whose text is wrapped in the 0x01 delimiter; any
                // reply arrives as a NOTICE and lands in that nick's query buffer.
                is ChatCommand.Ctcp -> {
                    networkId?.let { nid ->
                        sendCommand(
                            nid,
                            IrcMessage(
                                command = "PRIVMSG",
                                params = listOf(cmd.nick, "$CTCP_DELIMITER${cmd.request}$CTCP_DELIMITER"),
                            ),
                        )
                    }
                }

                is ChatCommand.Motd -> {
                    networkId?.let { nid ->
                        sendCommand(nid, IrcMessage(command = "MOTD", params = listOfNotNull(cmd.server)))
                    }
                }

                is ChatCommand.Whois -> {
                    openNickSheet(cmd.nick)
                }

                is ChatCommand.ChannelList -> {
                    networkId?.let(onOpenChannelList)
                }

                // Moderation guarded to CHANNEL buffers; a no-op elsewhere.
                is ChatCommand.Kick -> {
                    if (state.value.buffer?.type == BufferType.CHANNEL) {
                        networkId?.let { nid ->
                            val channel = state.value.buffer?.ircTarget ?: return@let
                            sendCommand(
                                nid,
                                IrcMessage(command = "KICK", params = listOfNotNull(channel, cmd.nick, cmd.reason)),
                            )
                        }
                    }
                }

                is ChatCommand.Ban -> {
                    if (state.value.buffer?.type == BufferType.CHANNEL) {
                        networkId?.let { nid ->
                            val channel = state.value.buffer?.ircTarget ?: return@let
                            sendCommand(
                                nid,
                                IrcMessage(
                                    command = "MODE",
                                    params =
                                        listOf(
                                            channel,
                                            "+b",
                                            io.github.trevarj.motd.ui.channelinfo
                                                .banMask(cmd.nick),
                                        ),
                                ),
                            )
                        }
                    }
                }

                is ChatCommand.RawLine -> {
                    networkId?.let { nid -> sendCommand(nid, IrcMessage.parse(cmd.line)) }
                }
            }
        }

        /** Raw-send for the SERVER buffer: strip one leading `/`, parse, send. Parse failure snackbars. */
        private suspend fun submitRawLine(
            networkId: Long?,
            raw: String,
        ) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return
            val nid = networkId ?: return
            val line = if (trimmed.startsWith("/")) trimmed.substring(1) else trimmed
            val msg = runCatching { IrcMessage.parse(line) }.getOrNull()
            if (msg == null || msg.command.isBlank()) {
                uiEventQueue.enqueue(ChatUiEvent.InvalidCommand)
                return
            }
            if (connectionManager.clientFor(nid) == null) return
            val submission = reserveDraftForSend(raw) ?: return
            try {
                sendCommand(nid, msg)
                clearDraftSubmission(submission)
            } finally {
                releaseDraftSubmission(submission.snapshot)
            }
        }

        private suspend fun sendCommand(
            networkId: Long,
            message: IrcMessage,
            channelContext: String? = null,
        ) {
            connectionManager.sendCommand(networkId, operationalBufferId.value, message, channelContext)
        }

        // --- nick sheet + whois ---

        private val _nickSheet = MutableStateFlow<NickSheetState?>(null)
        val nickSheet: StateFlow<NickSheetState?> = _nickSheet.asStateFlow()
        private var nickDetailsJob: Job? = null

        /**
         * Open the nick sheet for [nick]. WHOX populates cached identity while WHOIS supplies richer
         * request-scoped details. Both labeled and serialized unlabeled WHOIS stay sheet-only.
         */
        fun openNickSheet(nick: String) {
            // Moderation visibility depends on our prefixes in the channel roster. Load it on this
            // explicit interaction, not on every channel entry.
            ensureMembersObserved()
            _nickSheet.value = NickSheetState(nick = nick)
            val networkId = state.value.buffer?.networkId ?: return
            val client = connectionManager.clientFor(networkId)
            val normalizedNick = identityRules.value.normalize(nick)
            nickDetailsJob?.cancel()
            nickDetailsJob =
                viewModelScope.launch {
                    userDao.observeByNick(networkId, normalizedNick).collect { cached ->
                        val current = _nickSheet.value
                        if (current?.nick == nick) _nickSheet.value = current.copy(cached = cached)
                    }
                }
            if (client == null) return
            if (client.isupport["WHOX"] != null) {
                // WhoxRow events still flow through EventProcessor while the correlated request waits,
                // so UserEntity and this sheet's userDao collector converge through the normal path.
                viewModelScope.launch { runCatching { client.whox(nick) } }
            }
            viewModelScope.launch {
                val lines = runCatching { client.whois(nick) }.getOrNull().orEmpty()
                val info = parseWhois(lines)
                // Only fold in if the sheet is still open for this nick.
                if (info != null && _nickSheet.value?.nick == nick) {
                    _nickSheet.value = _nickSheet.value?.copy(whois = info)
                }
            }
        }

        fun dismissNickSheet() {
            nickDetailsJob?.cancel()
            nickDetailsJob = null
            _nickSheet.value = null
        }

        private val _avatarEvents = MutableSharedFlow<ConversationAvatarOutcome>(extraBufferCapacity = 1)
        val avatarEvents: SharedFlow<ConversationAvatarOutcome> = _avatarEvents.asSharedFlow()

        fun importAvatar(uri: android.net.Uri) = avatarAction { avatarController.importConversationAvatar(it, uri) }

        fun setAvatarUrl(url: String) = avatarAction { avatarController.setConversationAvatar(it, url) }

        fun resetAvatar() = avatarAction(avatarController::resetConversationAvatar)

        private fun avatarAction(action: suspend (Long) -> ConversationAvatarOutcome) =
            viewModelScope.launch {
                _avatarEvents.emit(action(operationalBufferId.value))
            }

        // --- moderation executors, CHANNEL buffers only ---

        /** MODE <channel> +o/-o/+v/-v <nick>. */
        fun setMemberMode(
            nick: String,
            mode: Char,
            grant: Boolean,
        ) = viewModelScope.launch {
            val nid = state.value.buffer?.networkId ?: return@launch
            val channel = state.value.buffer?.ircTarget ?: return@launch
            val flag = (if (grant) "+" else "-") + mode
            connectionManager.clientFor(nid)?.send(IrcMessage(command = "MODE", params = listOf(channel, flag, nick)))
        }

        /** KICK <channel> <nick> [:reason]. */
        fun kick(
            nick: String,
            reason: String?,
        ) = viewModelScope.launch {
            val nid = state.value.buffer?.networkId ?: return@launch
            val channel = state.value.buffer?.ircTarget ?: return@launch
            val params = if (reason.isNullOrBlank()) listOf(channel, nick) else listOf(channel, nick, reason)
            connectionManager.clientFor(nid)?.send(IrcMessage(command = "KICK", params = params))
        }

        /** MODE <channel> +b <banMask(nick)>. */
        fun ban(nick: String) =
            viewModelScope.launch {
                val nid = state.value.buffer?.networkId ?: return@launch
                val channel = state.value.buffer?.ircTarget ?: return@launch
                connectionManager
                    .clientFor(nid)
                    ?.send(
                        IrcMessage(
                            command = "MODE",
                            params =
                                listOf(
                                    channel,
                                    "+b",
                                    io.github.trevarj.motd.ui.channelinfo
                                        .banMask(nick),
                                ),
                        ),
                    )
            }

        /** Ban an explicit [mask] built by the nick sheet's picker, optionally kicking [nick] too. */
        fun banWithMask(
            nick: String?,
            mask: String,
            alsoKick: Boolean,
        ) = viewModelScope.launch {
            val nid = state.value.buffer?.networkId ?: return@launch
            val channel = state.value.buffer?.ircTarget ?: return@launch
            val trimmed = mask.trim().takeIf(String::isNotBlank) ?: return@launch
            val client = connectionManager.clientFor(nid) ?: return@launch
            client.send(IrcMessage(command = "MODE", params = listOf(channel, "+b", trimmed)))
            if (alsoKick && !nick.isNullOrBlank()) {
                client.send(IrcMessage(command = "KICK", params = listOf(channel, nick)))
            }
        }

        /** Toggle [nick]'s friend/fool membership (reuses SettingsRepository semantics). */
        fun toggleFriend(nick: String) =
            viewModelScope.launch {
                val settings = settingsRepository.settings.firstOrNull() ?: return@launch
                val rules = identityRules.value
                val exists =
                    settings.friends.any {
                        rules.normalize(it.trim()) == rules.normalize(nick.trim())
                    }
                settingsRepository.setFriend(nick, !exists, rules)
            }

        fun toggleFool(nick: String) =
            viewModelScope.launch {
                val settings = settingsRepository.settings.firstOrNull() ?: return@launch
                val rules = identityRules.value
                val exists =
                    settings.fools.any {
                        rules.normalize(it.trim()) == rules.normalize(nick.trim())
                    }
                settingsRepository.setFool(nick, !exists, rules)
            }

        fun ignoreNickOnNetwork(nick: String) =
            viewModelScope.launch {
                val networkId = state.value.buffer?.networkId ?: return@launch
                networkIgnoreRepository.addIgnore(networkId, nick)
                dismissNickSheet()
            }

        fun inviteToChannel(
            channel: JoinedChannelRow,
            nick: String,
            onResult: (Boolean) -> Unit = {},
        ) = viewModelScope.launch {
            val accepted =
                try {
                    connectionManager.inviteToChannel(channel.bufferId, nick)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            uiEventQueue.enqueue(
                if (accepted) {
                    ChatUiEvent.InviteRequestSent(nick.trim(), channel.displayName)
                } else {
                    ChatUiEvent.InviteSendFailed
                },
            )
            onResult(accepted)
        }

        /**
         * True when the viewer holds op in the current CHANNEL buffer (drives moderation visibility,
         * Confirmed #7). Own prefixes come from the members list; prefix order from ISUPPORT.
         */
        fun canModerate(): Boolean {
            val buffer = state.value.buffer ?: return false
            if (buffer.type != BufferType.CHANNEL) return false
            val myNick = (connState.value as? IrcClientState.Ready)?.nick ?: return false
            val normalize = nickNormalizer()
            val me = _members.value.firstOrNull { normalize(it.nick) == normalize(myNick) } ?: return false
            val order =
                buffer.networkId
                    .let { connectionManager.clientFor(it) }
                    ?.let {
                        io.github.trevarj.motd.ui.channelinfo
                            .prefixOrderFrom(it.isupport.prefixModes)
                    }
                    ?: io.github.trevarj.motd.ui.channelinfo.DEFAULT_PREFIX_ORDER
            return io.github.trevarj.motd.ui.channelinfo
                .canModerate(me.prefixes, order)
        }

        // --- composer prefill (mention → draft) ---

        /** Consume-once composer prefill queued by ChannelInfo before popping back; null when none. */
        fun consumePrefill(): String? = draftStore.consume(operationalBufferId.value)

        /**
         * Prefills queued for this conversation *while it is already open*.
         *
         * [consumePrefill] above covers the queue-then-navigate case, which is every prefill that comes
         * with a screen entry. The gesture orb can also aim one at the chat under it, and navigating
         * there is `launchSingleTop`: no entry effect re-runs, so nothing would ever drain the queue.
         * Cold on purpose — the text stays queued until a screen is actually collecting and can apply it.
         */
        val composerPrefills: Flow<String> =
            draftStore.prefillPushes
                .filter { it == operationalBufferId.value }
                .mapNotNull { draftStore.consume(it) }

        /** Consume-once shared file routed here by the share picker; the screen opens the upload sheet. */
        fun consumeSharedFile(): PendingShare.File? = pendingShareStore.consumeFile(operationalBufferId.value)

        /** Consume-once attachment-sheet request queued for this conversation before it was entered. */
        fun consumeAttachmentRequest(): Boolean = attachmentRequestStore.consume(operationalBufferId.value)

        /** Attachment-sheet requests aimed at this conversation while it is already open. */
        val attachmentSheetRequests: Flow<Unit> =
            attachmentRequestStore.requests
                .filter { it == operationalBufferId.value && attachmentRequestStore.consume(it) }
                .map { }

        fun saveDraft(text: String) {
            synchronized(draftStateLock) {
                draftTextEdited = true
                currentDraftText = text
                draftCommands.trySend(DraftCommand.Persist(advanceDraftRevisionLocked()))
            }
        }

        private suspend fun runDraftWriter() {
            val loaded = runCatching { draftStore.loadDraft(operationalBufferId.value) }.getOrNull()
            val loadedReply =
                loaded?.replyToEventId?.let {
                    runCatching { messageRepository.byId(it) }.getOrNull()
                }
            val editedBeforeHydration: DraftSnapshot? =
                synchronized(draftStateLock) {
                    val wasEdited = draftTextEdited || draftReplyEdited
                    if (!draftTextEdited) currentDraftText = loaded?.text.orEmpty()
                    if (!draftReplyEdited) {
                        currentReplyToEventId = loadedReply?.id ?: loaded?.replyToEventId
                        replyTo.value = loadedReply
                    }
                    val snapshot = advanceDraftRevisionLocked(hydrated = true)
                    snapshot.takeIf { wasEdited }
                }
            editedBeforeHydration?.let {
                runCatching { draftStore.saveDraft(it.roomId, it.text, it.replyToEventId) }
            }
            draftHydrated.complete(Unit)

            for (command in draftCommands) {
                when (command) {
                    is DraftCommand.Persist -> {
                        val current =
                            synchronized(draftStateLock) {
                                _composerDraft.value.revision == command.snapshot.revision
                            }
                        if (current) {
                            runCatching {
                                draftStore.saveDraft(
                                    command.snapshot.roomId,
                                    command.snapshot.text,
                                    command.snapshot.replyToEventId,
                                )
                            }
                        }
                    }

                    is DraftCommand.PrepareSubmission -> {
                        val unchanged =
                            synchronized(draftStateLock) {
                                _composerDraft.value.revision == command.snapshot.revision
                            }
                        val persisted =
                            if (unchanged) {
                                runCatching {
                                    draftStore.saveDraft(
                                        command.snapshot.roomId,
                                        command.snapshot.text,
                                        command.snapshot.replyToEventId,
                                    )
                                }.getOrNull()
                            } else {
                                null
                            }
                        command.result.complete(persisted)
                    }

                    is DraftCommand.ClearSubmission -> {
                        val unchangedBeforeDelete =
                            synchronized(draftStateLock) {
                                _composerDraft.value.revision == command.snapshot.revision
                            }
                        val deleted =
                            unchangedBeforeDelete &&
                                runCatching {
                                    draftStore.clearIfUnchanged(command.persisted)
                                }.getOrDefault(false)
                        val cleared =
                            if (deleted) {
                                synchronized(draftStateLock) {
                                    if (_composerDraft.value.revision != command.snapshot.revision) {
                                        false
                                    } else {
                                        currentDraftText = ""
                                        currentReplyToEventId = null
                                        replyTo.value = null
                                        advanceDraftRevisionLocked(hydrated = true)
                                        true
                                    }
                                }
                            } else {
                                false
                            }
                        command.result.complete(cleared)
                    }
                }
            }
        }

        private fun advanceDraftRevisionLocked(hydrated: Boolean = _composerDraft.value.hydrated): DraftSnapshot {
            val revision = ++nextDraftRevision
            _composerDraft.value = ComposerDraftState(currentDraftText, hydrated, revision)
            return DraftSnapshot(
                roomId = operationalBufferId.value,
                text = currentDraftText,
                replyToEventId = currentReplyToEventId,
                revision = revision,
            )
        }

        /**
         * Reserve the currently rendered draft before any persistence or wire work begins.
         *
         * The composer owns edits through [saveDraft]. Treating a stale callback's [raw] text as a
         * new edit used to recreate a draft just after an accepted send, which turned one rapid UI
         * activation into multiple real IRC messages.
         */
        private suspend fun prepareDraftSubmission(raw: String): DraftSubmissionOutcome {
            draftHydrated.await()
            val result = CompletableDeferred<ComposerDraftEntity?>()
            var duplicate = false
            val snapshot =
                synchronized(draftStateLock) {
                    if (currentDraftText != raw) return@synchronized null
                    val candidate =
                        DraftSnapshot(
                            roomId = operationalBufferId.value,
                            text = currentDraftText,
                            replyToEventId = currentReplyToEventId,
                            revision = _composerDraft.value.revision,
                        )
                    val key = DraftSubmissionKey(candidate.roomId, candidate.revision)
                    if (!inFlightDraftSubmissions.add(key)) {
                        duplicate = true
                        return@synchronized null
                    }
                    if (draftCommands.trySend(DraftCommand.PrepareSubmission(candidate, result)).isFailure) {
                        inFlightDraftSubmissions.remove(key)
                        return@synchronized null
                    }
                    candidate
                } ?: return if (duplicate) DraftSubmissionOutcome.Duplicate else DraftSubmissionOutcome.Stale
            return try {
                DraftSubmissionOutcome.Prepared(PreparedDraftSubmission(snapshot, result.await()))
            } catch (cancelled: CancellationException) {
                releaseDraftSubmission(snapshot)
                throw cancelled
            }
        }

        /**
         * Reserve the rendered draft for a send, or report the refusal. Returns null when the caller
         * must abandon the submission, having already restored the composer and told the reader why.
         *
         * The composer empties on the tap frame rather than waiting for the durable clear, so a
         * refusal that leaves the text only in this ViewModel would look like the message vanished.
         */
        private suspend fun reserveDraftForSend(raw: String): PreparedDraftSubmission? =
            when (val outcome = prepareDraftSubmission(raw)) {
                is DraftSubmissionOutcome.Prepared -> {
                    outcome.submission
                }

                // The first tap owns this revision and is already on the wire; restoring the text here
                // would undo the clear that send is about to earn.
                DraftSubmissionOutcome.Duplicate -> {
                    null
                }

                DraftSubmissionOutcome.Stale -> {
                    republishDraft()
                    uiEventQueue.enqueue(ChatUiEvent.SendDropped)
                    null
                }
            }

        /**
         * Re-publish the retained draft under a fresh revision so the composer restores text it
         * optimistically cleared. The value is unchanged; only the revision the screen keys on moves.
         */
        private fun republishDraft() {
            synchronized(draftStateLock) { advanceDraftRevisionLocked(hydrated = true) }
        }

        // --- outgoing send flight (composer bubble travelling into the timeline) ---

        /** Begin a ghost for one tap, replacing any earlier one. Returns the token identifying it. */
        private fun launchFlight(text: String): Long {
            val token = ++nextFlightToken
            // Read the reply here rather than in the UI: an accepted send clears it moments later, and
            // the composer has already emptied, so the tap instant is the only place it is still true.
            _outgoingFlight.value =
                launchOutgoingFlight(token, text, replyTo.value, System.currentTimeMillis())
            return token
        }

        private fun attachFlightEvents(
            token: Long,
            eventIds: Collection<Long>,
        ) {
            _outgoingFlight.update { attachOutgoingFlightEvents(it, token, eventIds) }
        }

        private fun abandonFlight(token: Long) {
            _outgoingFlight.update { settleOutgoingFlight(it, token) }
        }

        /**
         * The ghost is done: it landed, was rejected or replaced, or its screen closed. Either way
         * its row is revealed, so a stalled send can never hide behind flight state.
         */
        fun onFlightSettled(token: Long) = abandonFlight(token)

        private fun releaseDraftSubmission(snapshot: DraftSnapshot) {
            synchronized(draftStateLock) {
                inFlightDraftSubmissions.remove(DraftSubmissionKey(snapshot.roomId, snapshot.revision))
            }
        }

        private suspend fun clearDraftSubmission(submission: PreparedDraftSubmission): Boolean {
            val persisted = submission.persisted ?: return false
            val result = CompletableDeferred<Boolean>()
            synchronized(draftStateLock) {
                draftCommands.trySend(
                    DraftCommand.ClearSubmission(submission.snapshot, persisted, result),
                )
            }
            return result.await()
        }

        // --- search deep-jump ---

        private val jumpMsgid: String? = route.jumpToMsgid
        private val jumpTime: Long = route.jumpToTime
        private val jumpEventId: Long? = route.jumpToEventId

        private class JumpRequest(
            val token: Long,
            val msgid: String?,
            val time: Long,
            val eventId: Long?,
            val settlesEntryPosition: Boolean,
        ) {
            var reresolveUsed: Boolean = false
        }

        private var nextJumpToken = 0L

        private var activeJumpRequest: JumpRequest? =
            if (
                jumpTime > 0 || jumpEventId != null || jumpMsgid != null
            ) {
                JumpRequest(++nextJumpToken, jumpMsgid, jumpTime, jumpEventId, settlesEntryPosition = true)
            } else {
                null
            }
        private var jumpResolveJob: Job? = null

        /**
         * CHATHISTORY AROUND fetch used by [ChatJumpResolver] when a msgid target is not yet local.
         *
         * Routed through [HistoryPageLoader] like every other history request: it shares the per-network
         * wire gate (so a jump cannot race a catch-up page into the same timeline), coalesces with a
         * concurrent jump to the same message, applies the msgid→timestamp fallback, and persists
         * through the sole IRC→Room writer. A failure of any kind is reported to the resolver as "not
         * fetched" so the jump resolves NotFound instead of stalling the entry gate.
         */
        private val resolver =
            ChatJumpResolver(
                messages = messageRepository,
                fetchAround = fetch@{ name, msgid, timeMs, limit ->
                    val buffer = state.value.buffer ?: return@fetch false
                    val networkId = buffer.networkId
                    val client = connectionManager.clientFor(networkId) ?: return@fetch false
                    try {
                        historyPageLoader.loadAround(
                            networkId = networkId,
                            roomId = buffer.id,
                            target = name,
                            msgid = msgid,
                            timeMs = timeMs,
                            limit = limit,
                            source = client.historySource(),
                        ) is HistoryPageLoader.PageResult.Loaded
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                },
                countNewer = { targetBufferId, serverTime, id ->
                    messageRepository.countNewerThan(targetBufferId, serverTime, id, filterSpecs.first())
                },
            )

        private val _jumpTarget = MutableStateFlow<ChatPositionTarget?>(null)

        /** Identity-bearing target; the screen acknowledges it only after placeholder validation. */
        val jumpTarget: StateFlow<ChatPositionTarget?> = _jumpTarget.asStateFlow()

        // Normal channel entry is also a one-shot position operation. Unlike a search deep-link it
        // has no highlight, but it must settle before read state can advance.
        private val _initialTarget = MutableStateFlow<ChatPositionTarget?>(null)
        val initialTarget: StateFlow<ChatPositionTarget?> = _initialTarget.asStateFlow()

        // While the post-catch-up correction may still move a settled bottom entry that froze no
        // divider, viewport mark-read must wait: settled-at-bottom acknowledgement would consume the
        // arriving backlog before the correction re-resolves it, leaving the correction nothing unread
        // to reveal AND erasing the divider for the next visit. Bounded: the repair's catch-up wait is
        // capped at ENTRY_HISTORY_READY_TIMEOUT_MS, and a republished correction releases the hold as
        // soon as the screen consumes its target (placed, given up, or overridden by the reader).
        private val _viewportReadHold = MutableStateFlow(false)

        /** True while [armPostCatchUpEntryRepair] may still move a settled bottom entry. */
        val viewportReadHold: StateFlow<Boolean> = _viewportReadHold.asStateFlow()

        // One-shot entry positioning as a single sealed state. Pending gates read-state advancement;
        // Settled releases it; Unresolved keeps the gate (messageUnavailable => explicit jump failure).
        // Restored bit-identically from the same three SavedState keys the latches used (settled wins).
        private val _entryState =
            MutableStateFlow(
                restoredEntryPositionState(
                    settled = savedStateHandle.get<Boolean>(ENTRY_POSITION_SETTLED_KEY) == true,
                    unresolved = savedStateHandle.get<Boolean>(ENTRY_POSITION_UNRESOLVED_KEY) == true,
                    messageUnavailable = savedStateHandle.get<Boolean>(ENTRY_MESSAGE_UNAVAILABLE_KEY) == true,
                ),
            )

        /** One-shot entry/deep-link positioning state; read gating derives from it. */
        val entryState: StateFlow<EntryPositionState> = _entryState.asStateFlow()

        // Started here, not in the first init block: viewModelScope dispatches on Main.immediate, so the
        // collector runs eagerly and would read `entryState` before this line initialized it.
        init {
            runHistoryGapFills()
        }

        // Re-resolve is allowed exactly once per normal-entry target; explicit jump requests carry
        // their own guard so a superseded request cannot spend the newer request's retry.
        private var initialReresolveUsed = false

        // Set the moment the reader takes the viewport over: a drag or fling on the timeline, the newest
        // FAB, a send, or an explicit jump. [armPostCatchUpEntryRepair] is its only reader — a position
        // the reader is driving themselves is no longer entry's to correct.
        @Volatile
        private var timelineInteracted = false

        /** The timeline reported a user-driven scroll (as opposed to a programmatic one). */
        fun onTimelineInteraction() {
            timelineInteracted = true
        }

        init {
            val hasDeepJump = routeHasDeepJump
            // `jump_consumed` only prevents duplicate work after a completed jump. If Android kills
            // the process while the first resolve/scroll is in flight, the restored handle has it set
            // but neither terminal entry-position state; re-publish the target/failure for the new UI.
            if (needsDeepJumpResolution(
                    hasDeepJump = hasDeepJump,
                    jumpConsumed = savedStateHandle.get<Boolean>(JUMP_CONSUMED_KEY) == true,
                    entryState = _entryState.value,
                )
            ) {
                savedStateHandle[JUMP_CONSUMED_KEY] = true
                resolveJump()
            }
            // Normal entry, on the first buffer emission: freeze the visit's divider boundary
            // ([freezeUnreadEntrySnapshot]) and publish the one-shot entry target, both
            // from ONE at-rest resolution shared with the Pager key.
            viewModelScope.launch {
                // A restored visit already positioned itself in a previous process; only a fresh one may
                // still be corrected below.
                val freshVisit = _entryState.value is EntryPositionState.Pending
                val initialEntryBuffer = bufferRepository.observeBuffer(bufferId).firstOrNull()
                // Must match the paging spec exactly, presence override included, or the entry index it
                // resolves would count rows the PagingSource never emits.
                val entrySpec =
                    MessageVisibilitySpec.from(
                        settingsRepository.settings.first(),
                        initialEntryBuffer?.presenceModeOverride,
                    )
                // The room this open may position in: a deep link owns its own destination, and a SERVER
                // buffer has no read-marker entry to resolve.
                val entryRoom =
                    initialEntryBuffer
                        ?.takeIf { !hasDeepJump && it.type != BufferType.SERVER }
                // Whether the network was still catching up AT ENTRY. Entry itself never waits on that
                // (see below), but a room entered mid-catch-up is owed exactly one correction once the
                // catch-up lands and the anchors it resolves may have moved older.
                val catchUpPending =
                    entryRoom != null &&
                        !entryHistoryReady(
                            connectionManager.connectionActivity.value,
                            entryRoom.networkId,
                        )
                // The SAME at-rest resolution the Pager key was built from, so entry lands on the row the
                // generation is already keyed at. Entry does NOT wait for history catch-up to publish it:
                // `historyCatchUpPending` is held across catch-up's whole retry loop (exponential backoff
                // up to 30s per attempt), and while entry stayed pending the screen kept its entire follow
                // machinery disarmed — no auto-follow at the live bottom, no newest FAB, no read-marker
                // advancement — captured live as a 42-second dead window on a room whose divider was
                // sitting in the store the whole time.
                var anchors = entryAnchors(entrySpec)
                val enteredFromAtRest = anchors.hasDurableContent
                if (entryRoom != null && !enteredFromAtRest) {
                    // The one case that still waits: no stored anchor, no saved viewport, not one
                    // retained row, so there is genuinely nothing to position in and the reader is
                    // looking at the empty-timeline spinner either way. Bounded for the same reason as
                    // before — a struggling catch-up must not own the screen for its whole retry loop —
                    // and the fallback is the already-shipped offline entry behavior, not a new mode.
                    withTimeoutOrNull(ENTRY_HISTORY_READY_TIMEOUT_MS) {
                        connectionManager.connectionActivity.first { activity ->
                            entryHistoryReady(activity, entryRoom.networkId)
                        }
                    }
                    anchors = resolveEntryAnchors(entrySpec, discardUnresolvedSaved = true)
                }
                freezeUnreadEntrySnapshot(anchors)
                if (!hasDeepJump && _entryState.value !is EntryPositionState.Settled) {
                    _initialTarget.value = anchors.target
                }
                // The wait path already resolved against caught-up data (or against an expired bound),
                // so only an at-rest entry can still be owed a correction.
                if (entryRoom != null && freshVisit && catchUpPending && enteredFromAtRest) {
                    armPostCatchUpEntryRepair(entryRoom.networkId, entrySpec, anchors)
                }
            }
            viewModelScope.launch {
                visibilityReader.observeEventRedirects().collect {
                    restoreRedirectedViewport(filterSpec.value)
                }
            }
        }

        /**
         * Freeze the visit's "New messages" divider boundary from [anchors], absence included.
         *
         * The boundary is anchored to the first message from SOMEONE ELSE past the marker (minus 1, so
         * the existing `> marker` divider/badge comparisons land on that message): your own sent messages
         * must never trip the divider or the scroll-down badge, since you have read what you just sent.
         * Null (no real marker, or nothing unread from others) hides both.
         *
         * It uses the SAME `firstUnread` the entry target was resolved from, so the divider and the row
         * entry lands on name one row by construction.
         *
         * Frozen once per VISIT. A restored snapshot already IS this visit's boundary; re-deriving it
         * would place the divider at what is unread NOW rather than on entry, which is exactly what
         * freezing exists to prevent. [armPostCatchUpEntryRepair] is the single exception and it is not
         * a re-derivation of a settled boundary: it refines a boundary this same visit froze from
         * not-yet-caught-up data, only while the reader has not touched the viewport, and at most once.
         */
        private suspend fun freezeUnreadEntrySnapshot(anchors: EntryAnchors) {
            if (unreadEntrySnapshotRestored) return
            val frozen =
                anchors.firstUnread?.let { first ->
                    val unreadRow =
                        anchors.room?.let { room ->
                            bufferRepository.observeChatList().first().firstOrNull { it.bufferId == room.id }
                        }
                    UnreadEntrySnapshot(
                        marker = TimelineAnchor(first.serverTime, first.eventId - 1L, first.timelineOrder),
                        loadedCount = (unreadRow?.unreadCount ?: 1).coerceAtLeast(1),
                        // Gap-derived only. The window-bounds disjunct that used to sit here is now always
                        // false (Recent no longer passes a lower boundary), and it was always the weaker of
                        // the two: `unreadCountIncomplete` is computed in SQL from the room's stored gaps
                        // against its read marker, so it states "some unread history is missing" directly
                        // instead of inferring it from a clamp.
                        lowerBound = unreadRow?.unreadCountIncomplete == true,
                    )
                }
            _unreadEntrySnapshot.value = frozen
            persistUnreadEntrySnapshot(frozen)
        }

        /**
         * The one bounded correction owed to a room that was entered while its network was still
         * catching up.
         *
         * Entry is published from at-rest data immediately, which is right — the anchors that decide it
         * are durable columns on the buffer, and a room with content can always be placed. What catch-up
         * can still change is which row those anchors name, and how deep it sits, so the correction
         * re-resolves once when catch-up clears (or when its own bound expires, in which case the
         * at-rest entry stands) and republishes only if the anchor actually moved. `jumpThreshold` in
         * the paging config turns a far correction into a Pager jump rather than a scroll through
         * history.
         *
         * It corrects a placement in progress or a bottom entry that produced no outcome, never an
         * outcome the reader has seen:
         *
         *  * Entry may still be [EntryPositionState.Pending] — the screen has not finished placing the
         *    at-rest target — or it settled a [nullDividerBottomEntry]: the live bottom with the divider
         *    frozen as ABSENT, which settles in milliseconds and shows nothing a correction could yank.
         *    A placed divider, by contrast, is an outcome the reader has already seen: moving it then is
         *    the yank the frozen boundary exists to prevent, and it would also let arriving history (a
         *    gap fill across the catch-up seam, say) redraw a divider that is by contract frozen for the
         *    visit — so the settled path additionally requires the frozen snapshot to be null and this
         *    visit's boundary to be its own (not restored from a previous process).
         *  * The reader must not have touched the viewport: a drag, fling, send, FAB or explicit jump
         *    retires the correction, after which read-marker correctness converges through ordinary
         *    marker advancement instead.
         *
         * While a null-divider bottom entry is correctable, viewport mark-read is held ([viewportReadHold]):
         * that entry settles at the effective bottom, and acknowledging the backlog as it arrives would
         * both strip the correction of anything unread to land on and advance the marker over rows the
         * reader never saw. The hold is bounded — the catch-up wait expires with the same
         * [ENTRY_HISTORY_READY_TIMEOUT_MS] bound that retires the correction, and a republished target
         * releases it the moment the screen consumes it.
         */
        private fun armPostCatchUpEntryRepair(
            networkId: Long,
            spec: MessageVisibilitySpec,
            entered: EntryAnchors,
        ) = viewModelScope.launch {
            val holdsViewportRead = nullDividerBottomEntry(entered.firstUnread, entered.target)
            if (holdsViewportRead) _viewportReadHold.value = true
            try {
                withTimeoutOrNull(ENTRY_HISTORY_READY_TIMEOUT_MS) {
                    connectionManager.connectionActivity.first { entryHistoryReady(it, networkId) }
                } ?: return@launch
                if (!entryCorrectable(entered)) return@launch
                val repaired = resolveEntryAnchors(spec, discardUnresolvedSaved = true)
                if (!entryAnchorMoved(entered.target, repaired.target)) return@launch
                // A settled bottom entry is owed only the divider it could not resolve at rest: catch-up
                // that landed nothing unread (self echoes, presence, read history) must not move an
                // outcome already on screen just because the marker's depth changed.
                if (_entryState.value !is EntryPositionState.Pending && repaired.firstUnread == null) {
                    return@launch
                }
                // Re-checked after the queries: the screen may have placed the entered target, or the
                // reader taken the viewport over, while they ran.
                if (!entryCorrectable(entered)) return@launch
                freezeUnreadEntrySnapshot(repaired)
                // The screen may already have settled the entered position, so force a distinct emission —
                // as [restoreRedirectedViewport] does — and give the correction its own one-shot index
                // repair budget.
                initialReresolveUsed = false
                _initialTarget.value = null
                _initialTarget.value = repaired.target
                // Releasing on publish would let a still-bottom viewport acknowledge the backlog in the
                // frames before the corrected position lands; wait for the screen to consume the target
                // (onInitialPositionHandled/Unresolved or jumpToNewest, all of which clear it).
                if (holdsViewportRead) _initialTarget.first { it == null }
            } finally {
                if (holdsViewportRead) _viewportReadHold.value = false
            }
        }

        /** The entry placement is still in flight and still ours; see [armPostCatchUpEntryRepair]. */
        private fun entryCorrectable(entered: EntryAnchors): Boolean =
            !timelineInteracted && (
                _entryState.value is EntryPositionState.Pending ||
                    (
                        !unreadEntrySnapshotRestored &&
                            _unreadEntrySnapshot.value == null &&
                            nullDividerBottomEntry(entered.firstUnread, entered.target)
                    )
            )

        /**
         * Position the viewport on the oldest unread message (the first unseen row) so it tops the
         * window and the remaining unread continues below it. Falls back to the read marker itself when
         * every unread row is filtered out (fools/self), so entry still sits at last-read.
         */
        private suspend fun readMarkerEntryTarget(
            anchor: TimelineAnchor,
            spec: MessageVisibilitySpec,
            requireExactIdentity: Boolean,
        ): ChatPositionTarget {
            val index = messageRepository.countNewerThan(bufferId, anchor.serverTime, anchor.eventId, spec)
            // A deep anchor (past the default newest load) is materialized by the Pager's initialKey:
            // the messages flow resolves the same anchor via entryPagingKey BEFORE the first
            // generation, so this target only needs the index. See entryPagingKey.
            // forceScrollOnEntry: retained list state sits at the newest row on entry, so the gate must
            // still scroll to the (typically non-zero) anchor index instead of treating it as a no-op.
            // placeAtTop: ChatScreen realizes the top placement (off-screen load + measured snap) so the
            // first unread tops the viewport rather than sitting at the bottom edge under read history.
            val row = if (requireExactIdentity) messageRepository.byId(anchor.eventId) else null
            return ChatPositionTarget(
                index = index,
                expectedEventId = row?.id,
                expectedMsgid = row?.msgid,
                serverTime = anchor.serverTime,
                forceScrollOnEntry = true,
                placeAtTop = true,
            )
        }

        /**
         * The viewport this room was last left at, re-resolved against the live timeline.
         *
         * [discardUnresolved] forgets a saved position whose anchor no longer resolves, which is right
         * for the one-shot entry decision (the row is gone and nothing will bring it back) and wrong for
         * the Pager key, which is computed before catch-up has finished writing: there, "cannot resolve"
         * usually means "not stored yet".
         */
        private suspend fun restoredScrollPosition(
            spec: MessageVisibilitySpec,
            discardUnresolved: Boolean = true,
        ): ChatPositionTarget? {
            val roomId = operationalBufferId.value
            val saved =
                scrollPositionStore.get(roomId)
                    ?: bufferId.takeIf { it != roomId }?.let(scrollPositionStore::get)
                    ?: return null
            val anchor =
                visibilityReader.resolveSavedAnchor(
                    bufferId = bufferId,
                    msgid = saved.msgid,
                    serverTime = saved.serverTime,
                    id = saved.rowId,
                    spec = spec,
                ) ?: run {
                    if (discardUnresolved) scrollPositionStore.remove(roomId)
                    return null
                }
            val index =
                visibilityReader.countTimelineNewer(
                    bufferId,
                    TimelineAnchor(anchor.serverTime, anchor.id, anchor.timelineOrder),
                    spec,
                )
            val canonicalSavedId = visibilityReader.resolveCanonicalEventId(saved.rowId)
            return ChatPositionTarget(
                index = index,
                offset = saved.offset.takeIf { anchor.id == canonicalSavedId } ?: 0,
                expectedEventId = anchor.id,
                expectedMsgid = anchor.msgid,
                serverTime = anchor.serverTime,
                fromSavedPosition = true,
            )
        }

        /** Re-anchor an already-open viewport when coalescence replaces and retimestamps its row. */
        private suspend fun restoreRedirectedViewport(spec: MessageVisibilitySpec) {
            val roomId = operationalBufferId.value
            val saved =
                scrollPositionStore.get(roomId)
                    ?: bufferId.takeIf { it != roomId }?.let(scrollPositionStore::get)
                    ?: return
            val canonicalSavedId = visibilityReader.resolveCanonicalEventId(saved.rowId)
            if (canonicalSavedId == saved.rowId) return
            val anchor =
                visibilityReader.resolveSavedAnchor(
                    bufferId = bufferId,
                    msgid = saved.msgid,
                    serverTime = saved.serverTime,
                    id = saved.rowId,
                    spec = spec,
                ) ?: return
            if (anchor.id != canonicalSavedId) return
            val index =
                visibilityReader.countTimelineNewer(
                    bufferId,
                    TimelineAnchor(anchor.serverTime, anchor.id, anchor.timelineOrder),
                    spec,
                )
            scrollPositionStore.put(
                roomId,
                saved.copy(
                    index = index,
                    msgid = anchor.msgid,
                    serverTime = anchor.serverTime,
                    rowId = anchor.id,
                ),
            )
            initialReresolveUsed = false
            _initialTarget.value =
                ChatPositionTarget(
                    index = index,
                    offset = saved.offset,
                    expectedEventId = anchor.id,
                    expectedMsgid = anchor.msgid,
                    serverTime = anchor.serverTime,
                    fromSavedPosition = true,
                )
        }

        private fun resolveJump() {
            val request = activeJumpRequest ?: return
            jumpResolveJob?.cancel()
            // The buffer name (chathistory target) may not be in `state` yet on first composition;
            // read it directly from the repo so the AROUND fallback has a target.
            jumpResolveJob =
                viewModelScope.launch {
                    val name = bufferRepository.observeBuffer(bufferId).firstOrNull()?.ircTarget
                    publishResolve(name, request)
                }
        }

        private suspend fun publishResolve(
            name: String?,
            request: JumpRequest,
        ) {
            if (activeJumpRequest?.token != request.token) return
            when (
                val r =
                    resolver.resolve(
                        bufferId,
                        request.msgid,
                        request.time,
                        name,
                        eventId = request.eventId,
                    )
            ) {
                is ChatJumpResolver.Result.Resolved -> {
                    if (activeJumpRequest?.token != request.token) return
                    // A deep jump is a GLOBAL index into the one unbounded timeline. The resolver
                    // already counted it that way, so nothing is recomputed here: the Pager generation
                    // the screen is holding is the generation that owns this index, and the screen
                    // reaches the row by requesting the placeholder at it. Rebuilding a narrow window
                    // around the target is what used to make its index 0 something other than the room's
                    // newest row, which is precisely what the viewport mark-read gate must never see.
                    val target = r.target
                    // Force a distinct emission so the screen's LaunchedEffect(jumpTarget) always
                    // re-runs, even when the re-resolved index equals the previous one.
                    _jumpTarget.value = null
                    _jumpTarget.value = target.copy(requestToken = request.token)
                }

                ChatJumpResolver.Result.NotFound -> {
                    if (activeJumpRequest?.token != request.token) return
                    _jumpTarget.value = null
                    failActiveJump(request)
                }
            }
        }

        /** Screen calls this after it has scrolled to (or given up on) the current target. */
        fun onJumpHandled(token: Long) {
            val request = activeJumpRequest?.takeIf { it.token == token } ?: return
            val settlesEntryPosition = request.settlesEntryPosition
            jumpResolveJob?.cancel()
            jumpResolveJob = null
            activeJumpRequest = null
            _jumpTarget.value = null
            if (settlesEntryPosition) transitionEntry(EntryPositionState.Settled)
        }

        /** The screen completed its one-shot normal-entry positioning. */
        fun onInitialPositionHandled() {
            _initialTarget.value = null
            transitionEntry(EntryPositionState.Settled)
        }

        /**
         * The newest FAB is an explicit request to go to the live bottom right now.
         *
         * The scroll itself belongs to the screen; what this does is abandon every one-shot positioning
         * operation that would otherwise fight it — an unresolved deep jump, a pending entry target —
         * and settle entry so read state is no longer gated on a position the user just overrode.
         */
        fun jumpToNewest() {
            timelineInteracted = true
            jumpResolveJob?.cancel()
            jumpResolveJob = null
            activeJumpRequest = null
            _jumpTarget.value = null
            _initialTarget.value = null
            transitionEntry(EntryPositionState.Settled)
        }

        /** Re-resolve an exact mention against the live timeline before scrolling to it. */
        fun focusRecentMention(target: ChatPositionTarget) {
            timelineInteracted = true
            jumpResolveJob?.cancel()
            _jumpTarget.value = null
            activeJumpRequest = null
            val eventId = target.expectedEventId ?: return
            val request =
                JumpRequest(
                    token = ++nextJumpToken,
                    msgid = target.expectedMsgid,
                    time = target.serverTime,
                    eventId = eventId,
                    settlesEntryPosition = false,
                )
            activeJumpRequest = request
            jumpResolveJob =
                viewModelScope.launch {
                    val row = messageRepository.byId(eventId) ?: return@launch failActiveJump(request)
                    val index =
                        messageRepository.countNewerThan(
                            bufferId,
                            row.serverTime,
                            row.id,
                            filterSpecs.first(),
                        )
                    if (activeJumpRequest?.token != request.token) return@launch
                    _jumpTarget.value =
                        target.copy(
                            index = index,
                            expectedEventId = row.id,
                            expectedMsgid = row.msgid,
                            serverTime = row.serverTime,
                            requestToken = request.token,
                        )
                }
        }

        fun saveScrollPosition(position: ChatScrollPosition) {
            scrollPositionStore.put(operationalBufferId.value, position)
        }

        /**
         * Called only from a provable bottom, so it states where entry should land rather than merely
         * forgetting where it was. See [ChatScrollPositionStore.markParkedAtBottom].
         */
        fun clearScrollPosition() {
            scrollPositionStore.markParkedAtBottom(operationalBufferId.value)
        }

        /**
         * The save-time snapshot was indeterminate (e.g. Paging swapped in an empty QUERY snapshot
         * during navigation, or no anchorable row was in reach): drop the saved viewport without
         * asserting a bottom park. See [ScrollPositionOutcome.Forget].
         */
        fun forgetScrollPosition() {
            scrollPositionStore.remove(operationalBufferId.value)
        }

        /**
         * The timeline put this row on screen. Local-only: it decides where a REOPEN lands and nothing
         * else. It is not read state, is never persisted, and never leaves the device — see
         * [ChatScrollPositionStore]. The outbound `MARKREAD` path is [markRead] alone, and it is
         * driven by [shouldMarkReadFromViewport] at the effective bottom, which this cannot reach.
         */
        fun recordFurthestDisplayed(anchor: TimelineAnchor) {
            scrollPositionStore.recordFurthestDisplayed(operationalBufferId.value, anchor)
        }

        /** A target could not be loaded safely; retain the read gate rather than marking it read. */
        fun onInitialPositionUnresolved() {
            _initialTarget.value = null
            transitionEntry(EntryPositionState.Unresolved(messageUnavailable = false))
        }

        fun onJumpUnresolved(token: Long) {
            val request = activeJumpRequest?.takeIf { it.token == token } ?: return
            _jumpTarget.value = null
            failActiveJump(request)
        }

        /**
         * Resolve and reveal a locally available replied-to message. Reply previews are only clickable
         * after their target has resolved from Room, so this normally remains a local index lookup; the
         * shared jump pipeline still supplies bounded paging, index-shift recovery, and highlighting.
         */
        fun jumpToRepliedMessage(msgid: String) {
            timelineInteracted = true
            val settlesEntryPosition = _entryState.value is EntryPositionState.Pending
            val request =
                JumpRequest(
                    token = ++nextJumpToken,
                    msgid = msgid,
                    time = 0,
                    eventId = null,
                    settlesEntryPosition = settlesEntryPosition,
                )
            jumpResolveJob?.cancel()
            if (settlesEntryPosition) _initialTarget.value = null
            activeJumpRequest = request
            _jumpTarget.value = null
            jumpResolveJob =
                viewModelScope.launch {
                    publishResolve(state.value.buffer?.ircTarget, request)
                }
        }

        fun retryReplyJump(request: ReplyJumpRequest) {
            jumpToRepliedMessage(request.msgid)
        }

        private fun failActiveJump(request: JumpRequest) {
            if (activeJumpRequest?.token != request.token) return
            jumpResolveJob = null
            activeJumpRequest = null
            // Entry may have settled while this jump resolved; the settled gate must not downgrade,
            // but the user's explicit tap still deserves the transient not-found feedback.
            val reportedDurably =
                request.settlesEntryPosition &&
                    transitionEntry(EntryPositionState.Unresolved(messageUnavailable = true))
            if (!reportedDurably) {
                request.msgid?.let { msgid ->
                    uiEventQueue.enqueue(ChatUiEvent.ReplyJumpUnavailable(ReplyJumpRequest(msgid)))
                }
            }
        }

        /**
         * Advance the one-shot entry state, never downgrading a [EntryPositionState.Settled] latch.
         * Each accepted transition writes through to the same three SavedState keys the old latches
         * used (settled / unresolved / message-unavailable), incrementally and never clearing a flag,
         * so process-death restoration stays bit-identical.
         *
         * @return whether the transition was accepted (false only when the settled latch rejects it).
         */
        private fun transitionEntry(next: EntryPositionState): Boolean {
            val current = _entryState.value
            if (current is EntryPositionState.Settled) return false
            // Merge unresolved reasons: a later ordinary failure must not clear a durable
            // message-unavailable report, keeping live state consistent with the persisted key.
            val merged =
                if (
                    current is EntryPositionState.Unresolved && next is EntryPositionState.Unresolved
                ) {
                    EntryPositionState.Unresolved(current.messageUnavailable || next.messageUnavailable)
                } else {
                    next
                }
            val (settled, unresolved, messageUnavailable) = entryPositionSavedFlags(merged)
            if (settled) savedStateHandle[ENTRY_POSITION_SETTLED_KEY] = true
            if (unresolved) savedStateHandle[ENTRY_POSITION_UNRESOLVED_KEY] = true
            if (messageUnavailable) savedStateHandle[ENTRY_MESSAGE_UNAVAILABLE_KEY] = true
            _entryState.value = merged
            // Entry state is a gate input for history loading (SeamLoadingGate.entrySettled), and until
            // now the journal could only see the SCREEN's separate `initialPositionSettled` flag, and
            // only when a Paging generation happened to be presented. A run where entry never left
            // Pending was therefore indistinguishable from one where it settled and the rule refused.
            // The screen's own entry tracing goes to AutoFollowTrace, which is logcat-only and cannot be
            // uploaded, so this is the cheap substitute rather than a duplicate.
            if (merged != current) {
                diagnostics.record("chat_timeline", "entry_state_changed") {
                    mapOf(
                        // The buffer's own id when the redirect has resolved, the ROUTE id until then.
                        // Read straight off the two things that are always available rather than through
                        // `operationalBufferId`, so this record cannot depend on the initialization
                        // order of a later-declared StateFlow: the transition this line exists to
                        // journal (Pending -> Settled) is the one worth least without a room to pin it
                        // to, and a field the journal had to drop would be exactly that loss.
                        "room_id" to (buffer.value?.id ?: bufferId),
                        "from" to current.journalName,
                        "to" to merged.journalName,
                    )
                }
            }
            return true
        }

        /**
         * Re-resolve the same target once when a live message shifted indices mid-jump. The single-shot
         * guard means the screen can always call [onJumpHandled] after it; a repeat request just clears
         * the target so the not-loaded path takes over.
         */
        fun reresolveJumpOnce(token: Long) {
            val request = activeJumpRequest?.takeIf { it.token == token } ?: return
            if (request.reresolveUsed) {
                _jumpTarget.value = null
                failActiveJump(request)
                return
            }
            request.reresolveUsed = true
            jumpResolveJob?.cancel()
            jumpResolveJob =
                viewModelScope.launch {
                    publishResolve(state.value.buffer?.ircTarget, request)
                }
        }

        /** Saved positions use the same exact one-shot index repair as explicit jumps. */
        fun reresolveInitialOnce(target: ChatPositionTarget) =
            viewModelScope.launch {
                if (initialReresolveUsed) {
                    onInitialPositionUnresolved()
                    return@launch
                }
                initialReresolveUsed = true
                when (
                    val result =
                        resolver.resolve(
                            bufferId = bufferId,
                            msgid = target.expectedMsgid,
                            timeMs = target.serverTime,
                            bufferName = null,
                            eventId = target.expectedEventId,
                        )
                ) {
                    is ChatJumpResolver.Result.Resolved -> {
                        _initialTarget.value = null
                        _initialTarget.value =
                            result.target.copy(
                                offset = target.offset,
                                highlightMsgid = null,
                                fromSavedPosition = target.fromSavedPosition,
                                forceScrollOnEntry = target.forceScrollOnEntry,
                                placeAtTop = target.placeAtTop,
                            )
                    }

                    ChatJumpResolver.Result.NotFound -> {
                        onInitialPositionUnresolved()
                    }
                }
            }

        /**
         * Isupport-normalized nick folding for autocomplete; lowercase fallback when no live client.
         */
        fun nickNormalizer(): (String) -> String {
            val rules = identityRules.value
            return rules::normalize
        }

        private companion object {
            // Survives config changes so a jump resolves exactly once per navigation.
            const val JUMP_CONSUMED_KEY = "jump_consumed"
            const val ENTRY_POSITION_SETTLED_KEY = "entry_position_settled"
            const val ENTRY_POSITION_UNRESOLVED_KEY = "entry_position_unresolved"
            const val ENTRY_MESSAGE_UNAVAILABLE_KEY = "entry_message_unavailable"

            // The divider boundary frozen on entry, flattened into Bundle-safe primitives. New keys
            // only: no existing SavedState or preference projection changes shape.
            const val UNREAD_SNAPSHOT_COMPUTED_KEY = "unread_entry_snapshot_computed"
            const val UNREAD_SNAPSHOT_TIME_KEY = "unread_entry_snapshot_time"
            const val UNREAD_SNAPSHOT_EVENT_KEY = "unread_entry_snapshot_event"
            const val UNREAD_SNAPSHOT_ORDER_KEY = "unread_entry_snapshot_order"
            const val UNREAD_SNAPSHOT_COUNT_KEY = "unread_entry_snapshot_count"
            const val UNREAD_SNAPSHOT_LOWER_BOUND_KEY = "unread_entry_snapshot_lower_bound"

            // Max wait for a pending own message's msgid to land before a queued reaction gives up.
            // Urgent history recovery may wait behind one unlabeled 30s wire request before its own
            // bounded newest-page request. The observer still completes immediately on a fast echo.
            const val REACT_QUEUE_TIMEOUT_MS = 72_000L
        }
    }

/** One-shot entry/deep-link positioning outcome; the single source of read-gate advancement. */
sealed interface EntryPositionState {
    /** Positioning has not resolved yet; read state stays gated. */
    data object Pending : EntryPositionState

    /** A resolved position has settled; read state may advance. Never downgrades once reached. */
    data object Settled : EntryPositionState

    /**
     * Positioning failed durably; the read gate is retained until the user navigates away.
     * [messageUnavailable] distinguishes an explicit message-jump failure (surfaces a snackbar)
     * from ordinary entry that simply could not be placed.
     */
    data class Unresolved(
        val messageUnavailable: Boolean,
    ) : EntryPositionState
}

/**
 * Fixed classification string for the journal. `message_unavailable` is folded into the name rather
 * than carried as a second field so one record fully describes the transition.
 */
internal val EntryPositionState.journalName: String
    get() =
        when (this) {
            EntryPositionState.Pending -> {
                "pending"
            }

            EntryPositionState.Settled -> {
                "settled"
            }

            is EntryPositionState.Unresolved -> {
                if (messageUnavailable) "unresolved_message_unavailable" else "unresolved"
            }
        }

/** Map the three restored SavedState booleans back into the sealed entry state (settled wins). */
internal fun restoredEntryPositionState(
    settled: Boolean,
    unresolved: Boolean,
    messageUnavailable: Boolean,
): EntryPositionState =
    when {
        settled -> EntryPositionState.Settled
        unresolved -> EntryPositionState.Unresolved(messageUnavailable)
        else -> EntryPositionState.Pending
    }

/**
 * The `(settled, unresolved, messageUnavailable)` SavedState flags a transition to [state] sets
 * true. Writes are incremental (a `false` here never clears an already-persisted flag), matching
 * the old per-latch write-through exactly.
 */
internal fun entryPositionSavedFlags(state: EntryPositionState): Triple<Boolean, Boolean, Boolean> =
    when (state) {
        EntryPositionState.Pending -> Triple(false, false, false)
        EntryPositionState.Settled -> Triple(true, false, false)
        is EntryPositionState.Unresolved -> Triple(false, true, state.messageUnavailable)
    }

/** Whether a deep link still needs its target/failure published after SavedState restoration. */
internal fun needsDeepJumpResolution(
    hasDeepJump: Boolean,
    jumpConsumed: Boolean,
    entryState: EntryPositionState,
): Boolean = hasDeepJump && (!jumpConsumed || entryState is EntryPositionState.Pending)

/**
 * Upper bound on the two remaining waits for [entryHistoryReady]: the empty-room entry that has
 * nothing to position in, and the single post-catch-up correction armed for a room entered while its
 * network was still catching up. Long enough for a healthy catch-up (typically one CHATHISTORY pass,
 * low seconds) and a first 2s-backoff retry; short enough that a struggling catch-up cannot keep the
 * screen's follow/FAB/read machinery disarmed for its whole retry loop. Expiry is not a failure: the
 * at-rest entry stands, which is the already-shipped offline entry behavior rather than a new mode.
 */
internal const val ENTRY_HISTORY_READY_TIMEOUT_MS = 8_000L

internal fun entryHistoryReady(
    activity: io.github.trevarj.motd.service.ConnectionActivitySnapshot,
    networkId: Long,
): Boolean =
    when (activity.states[networkId]) {
        is IrcClientState.Ready -> networkId !in activity.historyCatchUpPending

        IrcClientState.Connecting,
        IrcClientState.Registering,
        -> false

        IrcClientState.Disconnected,
        null,
        -> activity.initializationComplete && activity.progressing[networkId] != true

        is IrcClientState.Failed -> activity.progressing[networkId] != true
    }
