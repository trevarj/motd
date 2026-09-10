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
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Demand-driven fills for an INTERIOR history gap: the same older-direction cascade
 * [ChatHistoryRemoteMediator.append] runs, with the demand source swapped from "Paging ran out of
 * local rows" to "this seam is on screen and owes history" — whether the timeline decided that on
 * its own or the user tapped the divider.
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
 * [HistoryPageLoader]. This class contributes exactly three things the mediator's Paging-driven
 * entry got for free: a demand source, a per-room single flight, and a page budget.
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
             * The caller names its gap outright. This is the only selection production uses: the
             * timeline decides which seam to work on from what the user can see, so the gap is already
             * chosen by the time it gets here.
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
             *  - [GapFillProgress.DROPPED] is a [gapId] of null, i.e. no gap was ever pinned — the
             *    room's single flight was already taken, the gap had closed, or the room cannot hold
             *    one. Nothing was even asked of the wire.
             *
             * Every other end (budget spent, gap closed, interval proven gone) moved history or settled
             * the question.
             */
            val progress: GapFillProgress
                get() =
                    when {
                        error != null || endReason == HISTORY_UNSUPPORTED -> GapFillProgress.FAILED
                        gapId == null -> GapFillProgress.DROPPED
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
        ): GapFill = fill(roomId, GapSelection.ById(gapId), historyFor(roomId))

        internal suspend fun fill(
            roomId: RoomId,
            selection: GapSelection,
            source: HistorySource,
            pageSize: Int = PAGE_SIZE,
            pageBudget: Int = PAGE_BUDGET,
        ): GapFill {
            // tryLock rather than withLock, and taken before the first suspension point: a second tap on
            // a room already filling must be DROPPED, not queued behind the first only to then page from
            // a boundary that fill already moved. The divider's spinner is the feedback for it.
            val lock = roomLocks.computeIfAbsent(roomId) { Mutex() }
            if (!lock.tryLock()) return ended(roomId, GapFill(null, 0, 0, "already_filling"))
            try {
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
                        cascade(room.networkId, roomId, room.ircTarget, gapId, source, pageSize, pageBudget),
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
                    // Terminality first, budget second: a seam that finished on its own is finished, and
                    // reporting the budget instead would invite a pointless retry. The budget itself is
                    // what keeps a 10k-message gap from being fetched unprompted — before the divider
                    // existed nothing ever did that, and an uncapped loop here would be a regression. The
                    // seam stays visible and the next tap resumes from the boundary this fill reached.
                    if (pages >= pageBudget) return GapFill(gapId, pages, inserted, "page_budget")
                }
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
             * This is no longer what stops a gap draining — the caller's demand is, since it only asks
             * again when the reader scrolls further toward the seam. What the budget still is, is the
             * QUANTUM of one such ask: how much a single approach to a seam fetches before handing
             * control back. Three pages rather than one because a gap fill's first page frequently lands
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

            /** The network cannot serve history at all; classified as a failure rather than an end. */
            internal const val HISTORY_UNSUPPORTED = "history_unsupported"
        }
    }
