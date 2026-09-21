package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryCursorDao
import io.github.trevarj.motd.data.db.HistoryGapDao
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.history.GapAnchorResolver
import io.github.trevarj.motd.data.history.NO_APPEND_PROGRESS
import io.github.trevarj.motd.data.history.PageProgress
import io.github.trevarj.motd.data.history.Pageability
import io.github.trevarj.motd.data.history.newestPageableGap
import io.github.trevarj.motd.data.history.olderPageability
import io.github.trevarj.motd.data.history.openGapFloor
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interior gap fills share the older-direction cascade used by [ChatHistoryRemoteMediator.append].
 * Demand comes from a visible seam, an explicit tap, or the current Ready session's Aggressive
 * policy; the mediator still owns paging below the oldest retained island.
 *
 * Why this cannot live in the mediator: a Paging3 `RemoteMediator` is only asked to APPEND when the
 * local `PagingSource` runs dry. Once the timeline is presented UNBOUNDED — every retained row
 * visible, with a tappable divider row marking each seam instead of a SQL window bound hiding
 * everything past it — the source never runs dry at an interior seam, so the APPEND callback is
 * physically never invoked for one. The mediator keeps the bottom-of-timeline append (its
 * LATEST seed and the global cursor ladder); this owns every seam above it.
 *
 * Everything else is deliberately NOT restated here. The boundary ladder, the unrecoverable/
 * server-proven-empty classifications, the anti-livelock no-progress rule, and the timestamp-only
 * `advancedFrom` asymmetry all come from [olderPageability]; gap selection comes from
 * [newestPageableGap] over [GapAnchorResolver]; the wire request, msgid→timestamp fallback,
 * per-network serialization, and the persist through the sole IRC→Room writer all come from
 * [HistoryPageLoader]. This class adds demand scheduling, a per-room single flight, and a bounded
 * page quantum.
 *
 * Two deliberate differences from the mediator's cascade, both consequences of being gap-scoped:
 *  - the gap is pinned for the whole fill, so a page that closes it ends the fill rather than
 *    silently spending the remaining budget on the next gap down;
 *  - there is no LATEST seed. A gap always supplies a boundary, and an empty store has no seam.
 */
@Singleton
class HistoryGapFillCoordinator
    @Inject
    constructor(
        private val connectionManager: ConnectionManager,
        private val bufferDao: BufferDao,
        private val messageDao: MessageDao,
        private val historyCursorDao: HistoryCursorDao,
        private val historyGapDao: HistoryGapDao,
        private val loader: HistoryPageLoader,
        private val diagnostics: DiagnosticLogger,
        private val settingsRepository: SettingsRepository,
        private val pruner: HistoryPruner,
    ) {
        /**
         * Minimal seam over the live history transport, resolved per call so a network that reaches
         * Ready after the room opened is picked up on the next tap. Same shape the mediator factory and
         * the resync coordinator use, so one scripted source can drive all three.
         */
        interface HistorySource : HistoryPageLoader.HistorySource {
            override suspend fun availability(): HistoryAvailability

            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse
        }

        /** Which gap a fill works on. Chosen once per fill and then pinned by id. */
        internal sealed interface GapSelection {
            /**
             * The caller names the seam selected by the viewport or the proactive room scheduler.
             */
            data class ById(
                val gapId: Long,
            ) : GapSelection

            /**
             * The gap older paging would work on under Recent focus — the newest seam in the room, from
             * [newestPageableGap]. This is the ladder's own ranking rather than a demand source, and it
             * is where [fill]'s selection is pinned against a room holding more than one seam.
             */
            data object Newest : GapSelection
        }

        /**
         * What one fill achieved. [endReason] is a fixed classification, shared verbatim with the
         * `end_reason` diagnostic field; the values sourced from [Pageability.End] are that module's
         * wire contract, so do not reword them.
         */
        data class GapFill(
            val gapId: Long?,
            val pagesLoaded: Int,
            val insertedCount: Int,
            val endReason: String,
            val error: Throwable? = null,
        ) {
            /**
             * What this fill achieved, for a caller that has to decide whether to say something.
             *
             * [GapFillProgress.FAILED] is checked FIRST and is the only end that raises an affordance,
             * so it has to be exactly the ends that broke: an [error] from the wire or the persist, and
             * the network that cannot serve history at all. A fill that inserted rows and then failed is
             * still a failure — the reader was mid-load and the load stopped.
             *
             * Two ends say "not yet", and both are statements about the ATTEMPT rather than about the
             * seam, so neither may advertise an error:
             *  - [GapFillProgress.STALLED] is the anti-livelock stop with zero durable inserts. The seam
             *    is open, still recoverable, and its boundary is where it was, so the interval is still
             *    owed;
             *  - [GapFillProgress.DROPPED] means no gap was pinned, or saved policy stopped automatic
             *    work before any inserts. Contention, a missing seam, and policy deferral leave the
             *    interval retryable without reporting a failure.
             *
             * Every other end (budget spent, gap closed, interval proven gone) moved history or settled
             * the question.
             */
            val progress: GapFillProgress
                get() =
                    when {
                        error != null || endReason == HISTORY_UNSUPPORTED -> GapFillProgress.FAILED
                        gapId == null -> GapFillProgress.DROPPED
                        insertedCount == 0 && endReason == "policy_stopped" -> GapFillProgress.DROPPED
                        insertedCount == 0 && endReason == NO_APPEND_PROGRESS -> GapFillProgress.STALLED
                        else -> GapFillProgress.MOVED
                    }
        }

        // Resolves stored gap edges against the local store so selection ranks gaps by real timeline
        // positions — the same projection the timeline's seams are placed with.
        private val gapAnchors = GapAnchorResolver(messageDao)

        // Guards overlapping fills of the SAME room. The wire itself is already serialized per network
        // inside HistoryPageLoader; what that cannot prevent is a second fill computing its boundary
        // from a store the first fill is halfway through moving.
        private val roomLocks = ConcurrentHashMap<RoomId, Mutex>()

        private val filling = MutableStateFlow<Set<Long>>(emptySet())

        /** Gap ids with a fill in flight, for the spinner on their divider rows. */
        val fillsInFlight: StateFlow<Set<Long>> = filling.asStateFlow()

        private data class ProactiveRoom(
            val gapIds: Set<Long>,
            val activation: Any,
        )

        private class ProactiveHistoryStopped : CancellationException()

        /** One Ready session's paced, round-robin drain of retained interior gaps. */
        suspend fun fillProactively(
            networkId: Long,
            source: HistoryPageLoader.HistorySource,
            isCurrent: () -> Boolean,
        ): Unit =
            coroutineScope {
                val latest = MutableStateFlow<Map<RoomId, ProactiveRoom>>(emptyMap())
                val observation =
                    launch {
                        combine(
                            historyGapDao.observeSyncGaps(networkId),
                            settingsRepository.settings.map { it.historySyncMode }.distinctUntilChanged(),
                        ) { gaps, mode ->
                            gaps.filter { (it.historySyncModeOverride ?: mode) == HistorySyncMode.AGGRESSIVE }
                        }.distinctUntilChanged().collect { gaps ->
                            val previous = latest.value
                            latest.value =
                                gaps.groupBy { it.roomId }.mapValues { (roomId, rows) ->
                                    ProactiveRoom(
                                        rows.mapTo(mutableSetOf()) { it.gapId },
                                        previous[roomId]?.activation ?: Any(),
                                    )
                                }
                        }
                    }
                val blocked = mutableMapOf<RoomId, MutableSet<Long>>()
                var previous = emptyMap<RoomId, ProactiveRoom>()
                var lastRoomId = Long.MIN_VALUE
                var inserted = false

                fun pruneInserted() {
                    if (inserted) {
                        pruner.schedule(networkId)
                        inserted = false
                    }
                }
                try {
                    while (isCurrent()) {
                        currentCoroutineContext().ensureActive()
                        val snapshot = latest.value
                        // The observer preserves activation identity even when a room leaves and
                        // re-enters Aggressive while another room's quantum is still on the wire.
                        blocked.keys.removeAll { snapshot[it]?.activation !== previous[it]?.activation }
                        blocked.forEach { (roomId, ids) -> ids.retainAll(snapshot[roomId]?.gapIds.orEmpty()) }
                        previous = snapshot
                        val candidates =
                            snapshot.asSequence().filter { (roomId, room) ->
                                room.gapIds.any { it !in blocked[roomId].orEmpty() }
                            }
                        val next = candidates.firstOrNull { it.key > lastRoomId } ?: candidates.firstOrNull()
                        if (next == null) {
                            pruneInserted()
                            latest.first { it != snapshot }
                            continue
                        }
                        val roomId = next.key
                        val stopped = blocked.getOrPut(roomId) { mutableSetOf() }
                        val gaps =
                            historyGapDao.forRoom(roomId).filter {
                                it.recoverable && it.id in next.value.gapIds && it.id !in stopped
                            }
                        val gapId = newestPageableGap(gapAnchors.resolve(roomId, gaps))?.gap?.id
                        lastRoomId = roomId
                        if (gapId == null) {
                            stopped += next.value.gapIds
                            continue
                        }
                        try {
                            val result =
                                fill(
                                    roomId,
                                    GapSelection.ById(gapId),
                                    proactiveSource(networkId, roomId, source, isCurrent),
                                    waitForRoom = true,
                                    onInserted = { inserted = true },
                                )
                            if (result.endReason != "page_budget") stopped += gapId
                        } catch (_: ProactiveHistoryStopped) {
                            // Only this activation is stopped; manual fills never consult this set.
                            stopped += gapId
                            pruneInserted()
                        }
                        delay(PROACTIVE_PACE_MS)
                    }
                } finally {
                    observation.cancel()
                    pruneInserted()
                }
            }

        private fun proactiveSource(
            networkId: Long,
            roomId: RoomId,
            source: HistoryPageLoader.HistorySource,
            isCurrent: () -> Boolean,
        ): HistorySource =
            object : HistorySource {
                private suspend fun checkAllowed() {
                    val mode = settingsRepository.settings.first().historySyncMode
                    val room = bufferDao.rawById(roomId)
                    currentCoroutineContext().ensureActive()
                    if (!isCurrent() ||
                        room == null ||
                        room.networkId != networkId ||
                        (room.type != BufferType.CHANNEL && room.type != BufferType.QUERY) ||
                        room.redirectToRoomId != null ||
                        room.pendingCloseAt != null ||
                        room.dismissed ||
                        (room.historySyncModeOverride ?: mode) != HistorySyncMode.AGGRESSIVE
                    ) {
                        throw ProactiveHistoryStopped()
                    }
                }

                override suspend fun availability(): HistoryAvailability {
                    checkAllowed()
                    return source.availability()
                }

                override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                    checkAllowed()
                    if (source.availability() !is HistoryAvailability.Ready || !isCurrent()) throw ProactiveHistoryStopped()
                    return source.chathistory(req)
                }
            }

        private class AutomaticHistoryStopped : CancellationException()

        private fun automaticSource(
            roomId: RoomId,
            source: HistorySource,
        ): HistorySource =
            object : HistorySource by source {
                private suspend fun checkAllowed() {
                    val mode = settingsRepository.settings.first().historySyncMode
                    val room = bufferDao.observeById(roomId)
                    currentCoroutineContext().ensureActive()
                    if (room == null || (room.type != BufferType.SERVER && (room.historySyncModeOverride ?: mode) == HistorySyncMode.LAZY)) {
                        throw AutomaticHistoryStopped()
                    }
                }

                override suspend fun availability(): HistoryAvailability {
                    checkAllowed()
                    return source.availability()
                }

                override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                    checkAllowed()
                    return source.chathistory(req)
                }
            }

        /**
         * Eagerly page [roomId] older until its oldest local row is at or below [floorMs] — the
         * "fetch the last N days" a discovery pass deliberately never does (that pass seeds one newest
         * page per chat and leaves the rest to scrolling). A room already reaching the floor costs
         * nothing; a room mid gap-fill is skipped rather than raced.
         */
        suspend fun fetchRoomWindow(
            roomId: RoomId,
            floorMs: Long,
            pageSize: Int = WINDOW_PAGE_SIZE,
        ): GapFill =
            fetchRoomWindow(roomId, floorMs, historyFor(roomId), pageSize).also { fetch ->
                diagnostics.record("chat_history", "window_fetch_ended") {
                    mapOf(
                        "room_id" to roomId,
                        "floor" to floorMs,
                        "pages_loaded" to fetch.pagesLoaded,
                        "inserted" to fetch.insertedCount,
                        "end_reason" to fetch.endReason,
                        "error_class" to fetch.error?.let { it::class.simpleName },
                    )
                }
            }

        internal suspend fun fetchRoomWindow(
            roomId: RoomId,
            floorMs: Long,
            source: HistorySource,
            pageSize: Int = PAGE_SIZE,
            pageBudget: Int = WINDOW_PAGE_BUDGET,
        ): GapFill {
            val lock = roomLocks.computeIfAbsent(roomId) { Mutex() }
            if (!lock.tryLock()) return GapFill(null, 0, 0, "already_filling")
            // ponytail: a divider tap during this walk drops with no per-seam spinner — [filling]
            // is keyed by gap id and this walk pins no gap, and the global "Backfilling…" sync bar
            // is the feedback. Room-keyed seam spinners if this ever confuses users.
            try {
                val room = bufferDao.observeById(roomId) ?: return GapFill(null, 0, 0, "missing_room")
                if (room.type == BufferType.SERVER) return GapFill(null, 0, 0, "server_room")
                var pages = 0
                var inserted = 0
                try {
                    while (true) {
                        // The floor is measured on what the store holds, so a room that already reaches it
                        // — or reached it with this page — is done without another request.
                        val oldest = oldestLocalRow(roomId)
                        val oldestTime = oldest?.serverTime
                        if (oldestTime != null && oldestTime <= floorMs) return GapFill(null, pages, inserted, "window_reached")
                        val gaps = historyGapDao.forRoom(roomId)
                        val start =
                            olderPageability(null, historyComplete(roomId), cursorOldest(roomId), oldest, progress = null, gapFloor = openGapFloor(gaps))
                        val next =
                            when (start) {
                                is Pageability.End -> return GapFill(null, pages, inserted, start.reason)
                                Pageability.SeedLatest -> return GapFill(null, pages, inserted, "no_local_boundary")
                                is Pageability.Page -> start
                            }
                        val result =
                            loader.loadPage(room.networkId, roomId, room.ircTarget, HistoryPageLoader.Direction.OLDER, source, pageSize, boundary = next.boundary)
                        pages++
                        val page =
                            when (result) {
                                is HistoryPageLoader.PageResult.Loaded -> result
                                HistoryPageLoader.PageResult.Unsupported -> return GapFill(null, pages, inserted, HISTORY_UNSUPPORTED)
                                is HistoryPageLoader.PageResult.Unavailable -> return GapFill(null, pages, inserted, "history_unavailable", result.cause)
                                is HistoryPageLoader.PageResult.Failed -> return GapFill(null, pages, inserted, "page_failed", result.cause)
                            }
                        inserted += page.insertedCount
                        val verdict =
                            olderPageability(
                                null,
                                historyComplete(roomId),
                                cursorOldest(roomId),
                                oldestLocalRow(roomId),
                                PageProgress(previous = next.boundary, insertedCount = page.insertedCount),
                                gapFloor = openGapFloor(historyGapDao.forRoom(roomId)),
                            )
                        if (verdict is Pageability.End) return GapFill(null, pages, inserted, verdict.reason)
                        if (pages >= pageBudget) return GapFill(null, pages, inserted, "page_budget")
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    return GapFill(null, pages, inserted, "error", error)
                }
            } finally {
                lock.unlock()
            }
        }

        /** Fill the named gap. Each call grants a fresh page budget. */
        suspend fun fillGap(
            roomId: RoomId,
            gapId: Long,
            automatic: Boolean = false,
        ): GapFill = fill(roomId, GapSelection.ById(gapId), historyFor(roomId), automatic = automatic)

        /** Explicit recovery keeps granting three-page quanta until no recoverable seam remains. */
        suspend fun drainGaps(
            roomId: RoomId,
            client: IrcClient,
            isCurrent: () -> Boolean,
        ): GapFillProgress = drainGaps(roomId, client.historySource(), isCurrent)

        internal suspend fun drainGaps(
            roomId: RoomId,
            source: HistoryPageLoader.HistorySource,
            isCurrent: () -> Boolean,
            pageSize: Int = PAGE_SIZE,
        ): GapFillProgress {
            val currentSource =
                object : HistorySource {
                    override suspend fun availability(): HistoryAvailability = if (isCurrent()) source.availability() else HistoryAvailability.NegotiatingOrOffline

                    override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
                        if (!isCurrent()) throw IrcDisconnectedException("CHATHISTORY", null)
                        return source.chathistory(req)
                    }
                }
            var progress = GapFillProgress.DROPPED
            var canonicalRoomId = roomId
            while (isCurrent()) {
                currentCoroutineContext().ensureActive()
                canonicalRoomId = bufferDao.canonicalId(canonicalRoomId) ?: return progress
                val gaps = historyGapDao.forRoom(canonicalRoomId).filter { it.recoverable }
                val gapId = newestPageableGap(gapAnchors.resolve(canonicalRoomId, gaps))?.gap?.id ?: return progress
                // Share the room lock and loader, but not automatic admission or its policy stops.
                val result =
                    fill(
                        canonicalRoomId,
                        GapSelection.ById(gapId),
                        currentSource,
                        pageSize = pageSize,
                        waitForRoom = true,
                        preserveUnread = true,
                    )
                if (result.progress == GapFillProgress.FAILED || result.progress == GapFillProgress.STALLED) return result.progress
                if (result.progress == GapFillProgress.MOVED) progress = GapFillProgress.MOVED
                when (result.endReason) {
                    "page_budget", "gap_closed", "gap_filled", "exhausted_focused_gap", "unrecoverable_focused_gap", "no_gap" -> Unit

                    // Includes no-progress after earlier inserts: it must not earn another quantum.
                    else -> return result.progress
                }
            }
            return progress
        }

        internal suspend fun fill(
            roomId: RoomId,
            selection: GapSelection,
            source: HistorySource,
            pageSize: Int = PAGE_SIZE,
            pageBudget: Int = PAGE_BUDGET,
            waitForRoom: Boolean = false,
            automatic: Boolean = false,
            preserveUnread: Boolean = false,
            onInserted: () -> Unit = {},
        ): GapFill {
            // Divider taps drop behind an active fill; room drains wait cancellably for their turn.
            val lock = roomLocks.computeIfAbsent(roomId) { Mutex() }
            if (waitForRoom) {
                lock.lock()
            } else if (!lock.tryLock()) {
                return ended(roomId, GapFill(null, 0, 0, "already_filling"))
            }
            try {
                // A waiting source rechecks admission after the mutex wait and inside every wire
                // permit (including fallbacks); only proactive/automatic sources consult policy.
                if (waitForRoom) source.availability()
                val room =
                    bufferDao.observeById(roomId)
                        ?: return ended(roomId, GapFill(null, 0, 0, "missing_room"))
                // A console has no CHATHISTORY target, so it can hold no fillable seam.
                if (room.type == BufferType.SERVER) {
                    return ended(roomId, GapFill(null, 0, 0, "server_room"))
                }
                val gapId =
                    selectGapId(roomId, selection)
                        ?: return ended(roomId, GapFill(null, 0, 0, "no_gap"))
                diagnostics.record("chat_history", "gap_fill_started") {
                    mapOf(
                        "room_id" to roomId,
                        "gap_id" to gapId,
                        "selection" to selection::class.simpleName,
                        "page_budget" to pageBudget,
                    )
                }
                filling.update { it + gapId }
                try {
                    return ended(
                        roomId,
                        cascade(
                            room.networkId,
                            roomId,
                            room.ircTarget,
                            gapId,
                            if (automatic) automaticSource(room.id, source) else source,
                            pageSize,
                            pageBudget,
                            preserveUnread,
                            onInserted,
                        ),
                    )
                } finally {
                    filling.update { it - gapId }
                }
            } finally {
                lock.unlock()
            }
        }

        /**
         * The mediator's older cascade, one iteration per [ChatHistoryRemoteMediator.append] call.
         *
         * Each iteration asks [olderPageability] twice with the same inputs the mediator uses: once
         * before the fetch to pick the boundary, and once after — with [PageProgress] — to decide
         * terminality. Keeping both calls means every classification, including the ones this file never
         * mentions by name, is inherited rather than re-derived.
         */
        private suspend fun cascade(
            networkId: Long,
            roomId: RoomId,
            target: String,
            gapId: Long,
            source: HistorySource,
            pageSize: Int,
            pageBudget: Int,
            preserveUnread: Boolean,
            onInserted: () -> Unit,
        ): GapFill {
            var pages = 0
            var inserted = 0
            try {
                while (true) {
                    val gap =
                        gap(roomId, gapId)
                            ?: return GapFill(gapId, pages, inserted, "gap_closed")
                    val start =
                        olderPageability(
                            gap,
                            historyComplete(roomId),
                            cursorOldest(roomId),
                            oldestLocalRow(roomId),
                            progress = null,
                        )
                    val next =
                        when (start) {
                            is Pageability.End -> return GapFill(gapId, pages, inserted, start.reason)

                            // Unreachable while a gap is pinned: both gap edges carry a server timestamp, so
                            // the ladder always has a boundary. Classified rather than asserted — a fill that
                            // cannot name a boundary must leave the seam visible, not crash the room.
                            Pageability.SeedLatest -> return GapFill(gapId, pages, inserted, "no_gap_boundary")

                            is Pageability.Page -> start
                        }
                    recordBoundary(roomId, gap, next.boundary, pages)
                    val result =
                        loader.loadPage(
                            networkId,
                            roomId,
                            target,
                            HistoryPageLoader.Direction.OLDER,
                            source,
                            pageSize,
                            gapId = next.focusedGapId,
                            boundary = next.boundary,
                            preserveUnread = preserveUnread,
                        )
                    pages++
                    val page =
                        when (result) {
                            is HistoryPageLoader.PageResult.Loaded -> {
                                result
                            }

                            HistoryPageLoader.PageResult.Unsupported -> {
                                return GapFill(gapId, pages, inserted, HISTORY_UNSUPPORTED)
                            }

                            is HistoryPageLoader.PageResult.Unavailable -> {
                                return GapFill(gapId, pages, inserted, "history_unavailable", result.cause)
                            }

                            is HistoryPageLoader.PageResult.Failed -> {
                                return GapFill(gapId, pages, inserted, "page_failed", result.cause)
                            }
                        }
                    inserted += page.insertedCount
                    if (page.insertedCount > 0) onInserted()
                    // Re-read AFTER the persist, deliberately: this page may have shrunk the gap, closed
                    // it, or proven its remainder empty, and each of those is an input to terminality.
                    val remaining =
                        gap(roomId, gapId)
                            ?: return GapFill(gapId, pages, inserted, "gap_filled")
                    val verdict =
                        olderPageability(
                            remaining,
                            historyComplete(roomId),
                            cursorOldest(roomId),
                            oldestLocalRow(roomId),
                            PageProgress(previous = next.boundary, insertedCount = page.insertedCount),
                        )
                    if (verdict is Pageability.End) return GapFill(gapId, pages, inserted, verdict.reason)
                    // Terminality first, budget second: only a spent quantum invites another
                    // proactive turn. Manual callers still grant a fresh quantum on each demand.
                    if (pages >= pageBudget) return GapFill(gapId, pages, inserted, "page_budget")
                }
            } catch (_: AutomaticHistoryStopped) {
                return GapFill(gapId, pages, inserted, "policy_stopped")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Transport and persist failures are per-fill, not per-gap: the seam stays visible and
                // recoverable so a retry can reach it.
                return GapFill(gapId, pages, inserted, "error", error)
            }
        }

        /** Pin the gap for the whole fill: the caller names its own, or the ladder takes the newest. */
        private suspend fun selectGapId(
            roomId: RoomId,
            selection: GapSelection,
        ): Long? {
            val gaps = historyGapDao.forRoom(roomId)
            return when (selection) {
                is GapSelection.ById -> gaps.firstOrNull { it.id == selection.gapId }?.id
                GapSelection.Newest -> newestPageableGap(gapAnchors.resolve(roomId, gaps))?.gap?.id
            }
        }

        private suspend fun gap(
            roomId: RoomId,
            gapId: Long,
        ): HistoryGapEntity? = historyGapDao.forRoom(roomId).firstOrNull { it.id == gapId }

        private suspend fun historyComplete(roomId: RoomId): Boolean = bufferDao.observeById(roomId)?.historyComplete == true

        private suspend fun cursorOldest(roomId: RoomId): ChatHistoryReference? = historyCursorDao.byRoom(roomId)?.let { ChatHistoryReference(it.oldestMsgid, it.oldestServerTime) }

        private suspend fun oldestLocalRow(roomId: RoomId): ChatHistoryReference? = messageDao.oldestBoundary(roomId)?.let { ChatHistoryReference(it.msgid, it.serverTime) }

        /** The per-page decision point: which boundary this request carries, and off which gap state. */
        private fun recordBoundary(
            roomId: RoomId,
            gap: HistoryGapEntity,
            boundary: ChatHistoryReference,
            pageIndex: Int,
        ) {
            diagnostics.record("chat_history", "gap_fill_boundary") {
                mapOf(
                    "room_id" to roomId,
                    "gap_id" to gap.id,
                    "gap_recoverable" to gap.recoverable,
                    "page_index" to pageIndex,
                    "boundary_has_msgid" to (boundary.msgid != null),
                    "boundary_server_time" to boundary.serverTime,
                )
            }
        }

        /**
         * Journal the fill's outcome. The field is named `end_reason` because [DiagnosticLogger] redacts
         * any field literally named `reason` (IRC quit/kick reasons are user content; this fixed
         * classification is not).
         */
        private fun ended(
            roomId: RoomId,
            fill: GapFill,
        ): GapFill {
            diagnostics.record("chat_history", "gap_fill_ended") {
                mapOf(
                    "room_id" to roomId,
                    "gap_id" to fill.gapId,
                    "pages_loaded" to fill.pagesLoaded,
                    "inserted_count" to fill.insertedCount,
                    "end_reason" to fill.endReason,
                    "error_class" to fill.error?.let { it::class.simpleName },
                )
            }
            return fill
        }

        // Resolve the live client lazily per call, exactly as ChatHistoryMediatorFactoryImpl does: the
        // room can be open before its network reaches Ready, and clientFor(...) is only stable once
        // connected. A missing/negotiating client stays retryable rather than masquerading as
        // unsupported or as a completed empty history response.
        private fun historyFor(roomId: RoomId): HistorySource =
            object : HistorySource {
                private suspend fun client() = bufferDao.observeById(roomId)?.networkId?.let { connectionManager.clientFor(it) }

                override suspend fun availability(): HistoryAvailability = client()?.historyAvailability ?: HistoryAvailability.NegotiatingOrOffline

                override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse = client()?.chathistory(req) ?: throw IrcDisconnectedException("CHATHISTORY", null)
            }

        internal companion object {
            /**
             * Pages one fill may fetch for one gap (~150 rows at the default page size), matching the
             * scale a scroll-driven cascade reached before the divider existed.
             *
             * One quantum hands control back to the viewport or the proactive round-robin scheduler.
             * Three pages rather than one because a gap fill's first page frequently lands
             * on rows the client already holds (the boundary cohort, and the whole page on a
             * timestamp-only wire), so a one-page quantum could leave a seam that did not visibly move.
             * The cascade still stops early the moment the gap closes, is proven empty, or stops making
             * progress, so the budget is a ceiling and rarely the reason a fill ends.
             */
            internal const val PAGE_BUDGET = 3

            /**
             * Rows per request for a manual window fetch. Scrolling keeps the small [PAGE_SIZE] so a
             * page lands before the reader reaches it; a backfill is round-trip bound, so it asks for
             * as much as servers usually allow. The loader clamps to the advertised CHATHISTORY limit.
             */
            internal const val WINDOW_PAGE_SIZE = 500

            /** Pages one manual window fetch may spend per room: "everything" stays bounded. */
            internal const val WINDOW_PAGE_BUDGET = 400

            private const val PAGE_SIZE = 50
            private const val PROACTIVE_PACE_MS = 500L

            /** The network cannot serve history at all; classified as a failure rather than an end. */
            internal const val HISTORY_UNSUPPORTED = "history_unsupported"
        }
    }
