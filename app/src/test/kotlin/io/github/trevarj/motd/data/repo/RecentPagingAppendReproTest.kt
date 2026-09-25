package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.cachedIn
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryCursorEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.sync.ChatHistoryRemoteMediator
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.HistoryGapFillCoordinator
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.data.sync.HistoryPruner
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Models the `RequiredHeadlessE2eTest.unreadHistoryEntersAtMarkerAndRemainsCanonical` open step: a
 * 49-row reconnect-catch-up island (rows 212..260), a recoverable history gap down to the read
 * marker, and deep older server history still fetchable inside that gap.
 *
 * ## What this file pins, and what changed
 *
 * It used to pin a CASCADE DRIVEN BY WINDOW BOUNDS: the Recent window was clamped at the gap, the
 * clamped window fitted inside `initialLoadSize`, so the local source returned `nextKey == null`,
 * Paging auto-fired a remote APPEND aimed at the gap, each persisted page receded the gap's newer
 * edge, that re-bounded the window, and the whole thing repeated until the window outgrew
 * `initialLoadSize` — a bounded three-page backfill, 49 -> 99 -> 149 -> 199.
 *
 * Recent is unbounded now, so none of those steps exist. The identical end state is reached by two
 * INDEPENDENT demand sources, and the point of this file is that they stay independent:
 *
 *  - the **autopilot** ([HistoryGapFillCoordinator], armed once per seam) fills the gap, bounded by
 *    the coordinator's page budget rather than by a window that happens to grow past a Paging
 *    constant. Three pages, the same three wire requests, the same 200 durable rows, the same gap
 *    receded to row62 — asserted as end state, request sequence and gap recession, never as bounds.
 *  - **Paging's own APPEND** pages BELOW the oldest retained row, which is the marker, and finds
 *    nothing. It must never walk the gap ladder, under any viewport regime.
 *
 * The wire here is boundary-honouring on purpose. The old fixture served BEFORE pages from a queue
 * regardless of what was asked for, which made a request sequence unfalsifiable; an unexpected
 * boundary now returns an empty page and the assertions fail loudly.
 */
@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RecentPagingAppendReproTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private lateinit var loader: HistoryPageLoader
    private var networkId = 0L
    private var bufferId = 0L

    @Before fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .setQueryExecutor { it.run() }
                    .setTransactionExecutor { it.run() }
                    .build()
            processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            loader = HistoryPageLoader(processor)
            networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "libera",
                        role = NetworkRole.DIRECT,
                        host = "h",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            processor.onRegistered(networkId, "me", emptyMap())
            db.bufferDao().insert(
                BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL),
            )
            bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        }

    @After fun tearDown() {
        db.close()
    }

    private fun chatMsg(
        msgid: String,
        time: Long,
    ) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "b", null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix("alice"),
        target = "#chan",
        text = msgid,
        isSelf = false,
        replyToMsgid = null,
    )

    private fun messages(events: List<IrcEvent>): ChatHistoryResponse.Messages {
        val refs =
            events
                .mapNotNull { (it as? IrcEvent.ChatMessage)?.ctx }
                .map { ChatHistoryReference(it.msgid, it.serverTime) }
        return ChatHistoryResponse.Messages(
            events,
            oldest = refs.firstOrNull(),
            newest = refs.lastOrNull(),
            endOfHistory = false,
            primaryMessageCount = refs.size,
        )
    }

    /**
     * Serves BEFORE pages by the EXACT boundary selector requested, so the ladder each driver walks
     * is observable rather than assumed. Anything else answers like a server with nothing older:
     * an empty page.
     */
    private inner class BoundaryScriptedHistory(
        private val timestampOnlyWire: Boolean,
        /** Runs on the wire, inside the loader's serialization, before the page is answered. */
        private val onRequest: suspend (ChatHistoryRequest) -> Unit = {},
    ) : ChatHistoryRemoteMediator.HistorySource,
        HistoryGapFillCoordinator.HistorySource {
        val requests = mutableListOf<ChatHistoryRequest>()

        val bounds: List<String?> get() = requests.map { it.bound1 }

        override suspend fun availability() =
            HistoryAvailability.Ready(
                if (timestampOnlyWire) {
                    setOf(HistoryReferenceType.TIMESTAMP)
                } else {
                    setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID)
                },
                100,
            )

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            requests += req
            onRequest(req)
            if (req.subcommand != ChatHistoryRequest.Subcommand.BEFORE) return messages(emptyList())
            return messages(BACKLOG_PAGES[req.bound1].orEmpty())
        }
    }

    /** The BEFORE selector for a fixture row under the wire's advertised reference types. */
    private fun selector(
        msgid: String,
        time: Long,
        timestampOnlyWire: Boolean,
    ): String = if (timestampOnlyWire) ChatHistorySelectors.timestamp(time) else ChatHistorySelectors.msgid(msgid)

    private fun differ() =
        AsyncPagingDataDiffer(
            diffCallback =
                object : DiffUtil.ItemCallback<MessageEntity>() {
                    override fun areItemsTheSame(
                        a: MessageEntity,
                        b: MessageEntity,
                    ) = a.id == b.id

                    override fun areContentsTheSame(
                        a: MessageEntity,
                        b: MessageEntity,
                    ) = a == b
                },
            updateCallback =
                object : ListUpdateCallback {
                    override fun onInserted(
                        position: Int,
                        count: Int,
                    ) {}

                    override fun onRemoved(
                        position: Int,
                        count: Int,
                    ) {}

                    override fun onMoved(
                        fromPosition: Int,
                        toPosition: Int,
                    ) {}

                    override fun onChanged(
                        position: Int,
                        count: Int,
                        payload: Any?,
                    ) {}
                },
            mainDispatcher = Dispatchers.Unconfined,
            workerDispatcher = Dispatchers.Unconfined,
        )

    /**
     * The state the E2E reaches just before opening the room: the read marker, the 49-row catch-up
     * island above it, and the recoverable gap the catch-up recorded between them. On a
     * timestamp-only wire (soju advertises `MSGREFTYPES=timestamp`) the recorded edges carry no
     * msgid, mirroring what the boundary references arrive stripped to.
     */
    private suspend fun seedCatchUpIsland(timestampOnlyWire: Boolean) {
        processor.process(networkId, chatMsg("marker", 10))
        (212..260).forEach { processor.process(networkId, chatMsg("row$it", it.toLong())) }
        db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = bufferId,
                olderMsgid = if (timestampOnlyWire) null else "marker",
                olderServerTime = 10,
                newerMsgid = if (timestampOnlyWire) null else "row212",
                newerServerTime = 212,
                recoverable = true,
            ),
        )
    }

    private fun repository(history: ChatHistoryRemoteMediator.HistorySource) =
        MessageRepositoryImpl(
            db.bufferDao(),
            db.networkIdentityDao(),
            db.messageDao(),
            db.reactionDao(),
            ChatHistoryMediatorFactory { roomId, visibility, identityRules ->
                ChatHistoryRemoteMediator(
                    roomId,
                    db.bufferDao(),
                    db.messageDao(),
                    processor,
                    history,
                    50,
                    db.historyCursorDao(),
                    db.historyGapDao(),
                    loader,
                    visibility = visibility,
                    identityRules = identityRules,
                )
            },
            db.historyGapDao(),
        )

    private fun coordinator() =
        HistoryGapFillCoordinator(
            NoClientConnectionManager,
            db.bufferDao(),
            db.messageDao(),
            db.historyCursorDao(),
            db.historyGapDao(),
            loader,
            io.github.trevarj.motd.diagnostics.DiagnosticLogger.Noop,
            settingsRepository = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>()),
            pruner = HistoryPruner.Noop,
        )

    /**
     * One hands-free fill: exactly what `HistoryGapFiller.fillGap` does when the timeline decides a
     * visible seam owes history, with the source supplied explicitly because there is no live client
     * behind the coordinator here. The selection is the ladder's own — this room holds one gap, so
     * naming it and ranking it come to the same thing.
     */
    private suspend fun runAutopilot(history: BoundaryScriptedHistory) =
        coordinator().fill(
            bufferId,
            HistoryGapFillCoordinator.GapSelection.Newest,
            history,
            pageSize = 50,
        )

    private suspend fun totalRows(): Int =
        db
            .messageDao()
            .pagingSource(bufferId)
            .load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, 500, false),
            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data.size }

    /** Open the Recent timeline and settle it, re-applying [hint]'s modeled viewport each round. */
    private suspend fun TestScope.openTimeline(
        repository: MessageRepositoryImpl,
        hint: (AsyncPagingDataDiffer<MessageEntity>) -> Unit,
    ): List<String?> {
        val differ = differ()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                repository
                    .messages(bufferId, MessageVisibilitySpec())
                    .collectLatest { differ.submitData(it) }
            }
        repeat(10) {
            advanceUntilIdle()
            if (differ.itemCount > 0) hint(differ)
        }
        advanceUntilIdle()
        val presented = (0 until differ.itemCount).map { differ.peek(it)?.msgid }
        job.cancel()
        return presented
    }

    // --- the autopilot's budgeted cascade ---------------------------------------------------------

    @Test
    fun autopilotFillReachesTheBudgetedEndStateWithTheGapLadder() =
        runTest {
            seedCatchUpIsland(timestampOnlyWire = false)
            val history = BoundaryScriptedHistory(timestampOnlyWire = false)

            val fill = runAutopilot(history)

            // Bounded by the coordinator's budget, not by a window growing past initialLoadSize.
            assertEquals("the fill stopped on its page budget", "page_budget", fill.endReason)
            assertEquals("pages one arming may fetch", 3, fill.pagesLoaded)
            // The ladder itself: each page recedes the gap's newer edge and the next request is issued
            // from the receded edge, never restarted and never jumped.
            assertEquals(
                listOf("msgid=row212", "msgid=row162", "msgid=row112"),
                history.bounds,
            )
            assertEquals("durable rows after one arming", 200, totalRows())
            val gap = db.historyGapDao().forRoom(bufferId).single()
            assertTrue("the seam stays fillable after the budget stop", gap.recoverable)
            assertEquals("receded gap newer edge", 62L, gap.newerServerTime)
            assertEquals("the gap's older edge never moves", 10L, gap.olderServerTime)
        }

    @Test
    fun timestampOnlyWireReachesTheSameEndStateAndKeepsTheGapRecoverable() =
        runTest {
            // The hosted-CI wire regime: soju 0.10.1 advertises MSGREFTYPES=timestamp, so every boundary
            // is a bare timestamp and every saturated page trips the loader's per-fetch cursor guard. A
            // saturated timestamp-only fill page must NOT poison the gap — `recoverable = false` is
            // reserved for server-proven-empty intervals — so the fill converges with the msgid wire.
            // Regression pin for the hosted-CI failure where the gap went unrecoverable and every fetch
            // ended with zero pages.
            seedCatchUpIsland(timestampOnlyWire = true)
            val history = BoundaryScriptedHistory(timestampOnlyWire = true)

            val fill = runAutopilot(history)

            assertEquals("page_budget", fill.endReason)
            assertEquals(
                listOf(
                    ChatHistorySelectors.timestamp(212),
                    ChatHistorySelectors.timestamp(162),
                    ChatHistorySelectors.timestamp(112),
                ),
                history.bounds,
            )
            assertEquals("durable rows after one arming", 200, totalRows())
            val gap = db.historyGapDao().forRoom(bufferId).single()
            assertEquals("gap recoverability after fill pages", true, gap.recoverable)
            assertEquals("receded gap newer edge", 62L, gap.newerServerTime)
        }

    @Test
    fun aSecondArmingResumesFromTheBoundaryTheFirstReached() =
        runTest {
            // The budget parks the fill; it does not restart it. Two armings walk one continuous ladder
            // and reach the depth the old bounds-driven cascade only reached under an adversarial
            // viewport — deliberately, and only because something asked twice.
            seedCatchUpIsland(timestampOnlyWire = false)
            val history = BoundaryScriptedHistory(timestampOnlyWire = false)

            runAutopilot(history)
            val second = runAutopilot(history)

            assertEquals(
                listOf("msgid=row212", "msgid=row162", "msgid=row112", "msgid=row62", "msgid=row12"),
                history.bounds,
            )
            // Row 12 is the oldest the fixture server holds, so the fifth request comes back empty and
            // proves the remainder gone rather than spending the rest of the budget.
            assertEquals("exhausted_focused_gap", second.endReason)
            assertEquals(250, totalRows())
        }

    // --- the two demand sources, opening together --------------------------------------------------

    @Test
    fun bothDemandSourcesOpeningTogetherReachTheSameDeterministicEndState() =
        runTest {
            // The race the E2E gate kept catching, with the two sources actually overlapping instead of
            // being exercised one at a time.
            //
            // The reconnect state that makes them collide is the stored cursor: a LATEST catch-up page
            // unions its own oldest row into it, and on a store with no protocol history before the
            // disconnect that row IS the gap's newer edge. So the mediator's bottom-of-timeline ladder
            // arrives at exactly the reference the fill is pinned to, and the loader — which used to
            // coalesce any concurrent (network, room, OLDER) fetch — handed whichever of them arrived
            // second a page for an interval it never requested. The follower's own boundary then never
            // moved, so it read its zero inserts as "this interval is exhausted" and stopped after one
            // page having achieved nothing, which is the 49-row terminal state the gate reported.
            //
            // Both halves of the split are load-bearing here and neither alone is sufficient: the
            // boundary clamp is what makes the two requests DIFFERENT, and the flight identity is what
            // stops the loader collapsing two different requests back onto one page.
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                seedCatchUpIsland(timestampOnlyWire = true)
                db.historyCursorDao().upsert(
                    HistoryCursorEntity(roomId = bufferId, oldestMsgid = null, oldestServerTime = 212),
                )
                val onWire = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val history =
                    BoundaryScriptedHistory(timestampOnlyWire = true) {
                        if (onWire.complete(Unit)) release.await()
                    }
                val mediator =
                    ChatHistoryRemoteMediator(
                        bufferId,
                        db.bufferDao(),
                        db.messageDao(),
                        processor,
                        history,
                        50,
                        db.historyCursorDao(),
                        db.historyGapDao(),
                        loader,
                    )

                // Paging's APPEND takes the wire first and is held there; the autopilot arms while it is
                // still in flight, which is the ~3 ms overlap the journal recorded.
                val paging =
                    async(UnconfinedTestDispatcher(testScheduler)) {
                        mediator.load(
                            LoadType.APPEND,
                            PagingState(
                                pages = emptyList(),
                                anchorPosition = null,
                                config = PagingConfig(pageSize = 50, prefetchDistance = 25, enablePlaceholders = false),
                                leadingPlaceholderCount = 0,
                            ),
                        )
                    }
                onWire.await()
                val fill = async(UnconfinedTestDispatcher(testScheduler)) { runAutopilot(history) }
                advanceUntilIdle()
                release.complete(Unit)
                paging.await()
                val filled = fill.await()

                // Four requests, four distinct intervals. The first is the ladder's, clamped strictly
                // below the gap (the marker), where this fixture server has nothing; the other three are
                // the fill's budgeted gap ladder, unaffected by it.
                assertEquals(
                    listOf(
                        ChatHistorySelectors.timestamp(10),
                        ChatHistorySelectors.timestamp(212),
                        ChatHistorySelectors.timestamp(162),
                        ChatHistorySelectors.timestamp(112),
                    ),
                    history.bounds,
                )
                assertEquals("the fill spent its budget rather than stalling", "page_budget", filled.endReason)
                assertEquals("every page landed", 150, filled.insertedCount)
                assertEquals("durable rows: 50 seeded + 150 filled", 200, totalRows())
                val gap = db.historyGapDao().forRoom(bufferId).single()
                assertTrue("an empty ladder page is not proof an interior interval is gone", gap.recoverable)
                assertEquals("receded gap newer edge", 62L, gap.newerServerTime)
                assertEquals("the gap's older edge never moves", 10L, gap.olderServerTime)
            } finally {
                Dispatchers.resetMain()
            }
        }

    // --- Paging's own APPEND stays below the timeline ----------------------------------------------

    /**
     * Open the timeline under one modeled viewport and assert what Paging asked the wire for.
     *
     * These three regimes used to produce three different backfill depths, because each one
     * interacted with a window that kept re-bounding underneath it. With the window unbounded they
     * are indistinguishable on the wire: Paging's APPEND asks for backlog below the OLDEST retained
     * row — the marker, on the far side of the seam — and the fixture server has nothing there.
     *
     * Paging3 still auto-fires that APPEND with no scroll whenever the initial source load returns
     * `nextKey == null`, which a 50-row store under `initialLoadSize` (150) always does. That
     * behavior is unchanged and unchangeable; what changed is where it aims.
     */
    private suspend fun TestScope.assertPagingPagesBelowTheTimeline(
        hint: (AsyncPagingDataDiffer<MessageEntity>) -> Unit,
    ) {
        seedCatchUpIsland(timestampOnlyWire = false)
        val history = BoundaryScriptedHistory(timestampOnlyWire = false)

        val presented = openTimeline(repository(history), hint)

        assertEquals(
            "Paging pages below the timeline, never the seam",
            listOf("msgid=marker"),
            history.bounds,
        )
        val gap = db.historyGapDao().forRoom(bufferId).single()
        assertTrue("the seam is untouched by Paging", gap.recoverable)
        assertEquals("the seam did not move", 212L, gap.newerServerTime)
        // The unbounded window is the whole point: the marker on the FAR side of the gap is
        // presented, which is what gives the seam a materialized older neighbour to sit on.
        assertEquals("newest presented row", "row260", presented.first())
        assertEquals("oldest presented row", "marker", presented.last())
        assertEquals("presented rows", 50, presented.size)
    }

    @Test
    fun entryAnchoredViewportPagesBelowTheTimeline() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                // The real app: the unread entry row keeps a fixed index from the newest end.
                assertPagingPagesBelowTheTimeline { differ -> differ.getItem(minOf(48, differ.itemCount - 1)) }
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun oldestPinnedViewportPagesBelowTheTimeline() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                // Adversarial: dragged to the oldest loaded row after every update. It used to drain the
                // whole scripted backlog through the gap; now the boundary it keeps hitting is the
                // bottom of the timeline, and the seam above it is nobody's business but the user's.
                assertPagingPagesBelowTheTimeline { differ -> differ.getItem(differ.itemCount - 1) }
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun hintFreeOpenPagesBelowTheTimeline() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                // No viewport interaction at all: proves the single request is doInitialLoad's
                // `nextKey == null` auto-APPEND, not an access hint.
                assertPagingPagesBelowTheTimeline { }
            } finally {
                Dispatchers.resetMain()
            }
        }

    // --- deep entry keying ------------------------------------------------------------------------

    @Test
    fun reopenWithDeepEntryAnchorMaterializesEntryRowInInitialRefresh() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                // Reopen state: an earlier visit filled the gap down to row62, and later live
                // traffic added rows through 700. The oldest unread entry (row62) sits at index
                // 638 — beyond the newest 600-row load. Scrolling into an unloaded placeholder
                // would drive a boundary APPEND and churn the generation before the row can compose.
                // entryAnchorPagingKey shifts back by initialLoadSize - pageSize, materializing the
                // entry AND newer rows below it in the FIRST refresh: Room treats a refresh key as
                // the load's start offset, so an unshifted anchor leaves the reversed viewport
                // below it as placeholders.
                processor.process(networkId, chatMsg("marker", 10))
                (62..700).forEach { processor.process(networkId, chatMsg("row$it", it.toLong())) }
                db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = bufferId,
                        olderMsgid = "marker",
                        olderServerTime = 10,
                        newerMsgid = "row62",
                        newerServerTime = 62,
                        recoverable = true,
                    ),
                )
                // Nothing older than the marker on this server, so Paging's own APPEND lands no rows and
                // cannot invalidate the keyed generation out from under the assertion. What a landing
                // backfill page does to the ladder is the cascade tests' business, not this one's.
                val history = BoundaryScriptedHistory(timestampOnlyWire = false)
                val repository = repository(history)
                val entryIndex = 638
                val anchorKey = entryAnchorPagingKey(entryIndex)
                assertEquals("anchor key shifts back by initialLoadSize - pageSize", 88, anchorKey)

                val keyed = openAndPeekIndex(repository, initialKey = anchorKey, index = entryIndex)
                val unkeyed = openAndPeekIndex(repository, initialKey = null, index = entryIndex)

                assertEquals("entry row materialized by the keyed initial refresh", "row62", keyed.first?.msgid)
                assertEquals(
                    "newer sibling below the entry materialized by the same refresh",
                    "row63",
                    keyed.second?.msgid,
                )
                // Without the key the same index is still an unloaded placeholder after the initial
                // refresh: pins the boundary-churn condition the initialKey removes.
                assertEquals("deep entry stays a placeholder without the key", null, unkeyed.first?.msgid)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun reopenKeySwapPresentsTheKeyedGeneration() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                // Mimics ChatViewModel's exact flow shape on reopen: the screen first collects the
                // UNKEYED Recent generation (fresh entryAnchorKey = null), then the entry computation
                // sets the anchor key, which flatMapLatest-swaps in the keyed Pager mid-collection —
                // all multicast through cachedIn like viewModel.messages. The keyed generation must
                // PRESENT (refresh completes and the entry row materializes); a swap that leaves the
                // differ's refresh stuck loading is the blank-reopen wedge.
                processor.process(networkId, chatMsg("marker", 10))
                (62..700).forEach { processor.process(networkId, chatMsg("row$it", it.toLong())) }
                db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = bufferId,
                        olderMsgid = "marker",
                        olderServerTime = 10,
                        newerMsgid = "row62",
                        newerServerTime = 62,
                        recoverable = true,
                    ),
                )
                val history = BoundaryScriptedHistory(timestampOnlyWire = false)
                val repository = repository(history)
                val keyFlow = MutableStateFlow<Int?>(null)
                // The cache scope mirrors viewModelScope (Main.immediate): an immediate dispatcher, not
                // the test's standard queue, so the multicaster runs as eagerly as production.
                val cacheScope =
                    kotlinx.coroutines.CoroutineScope(
                        UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.SupervisorJob(),
                    )
                val messages =
                    keyFlow
                        .flatMapLatest { key ->
                            repository.messages(bufferId, MessageVisibilitySpec(), key)
                        }.cachedIn(cacheScope)
                val differ = differ()
                val job =
                    launch(UnconfinedTestDispatcher(testScheduler)) {
                        messages.collectLatest { differ.submitData(it) }
                    }
                advanceUntilIdle()
                // The fresh reopen frame composes the newest rows of the unkeyed generation.
                if (differ.itemCount > 0) differ.getItem(0)
                advanceUntilIdle()
                val presentedBeforeSwap = differ.itemCount
                keyFlow.value = entryAnchorPagingKey(638)
                advanceUntilIdle()
                val target = (638).takeIf { it < differ.itemCount }?.let { differ.peek(it) }
                val sibling = (637).takeIf { it < differ.itemCount }?.let { differ.peek(it) }
                println(
                    "KEYSWAP before=$presentedBeforeSwap after=${differ.itemCount} " +
                        "target=${target?.msgid} sibling=${sibling?.msgid}",
                )
                assertEquals("entry row presented after the key swap", "row62", target?.msgid)
                assertEquals("newer sibling presented after the key swap", "row63", sibling?.msgid)
                job.cancel()
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * Open one generation, settle the initial refresh, and peek [index] and its newer sibling
     * ([index] - 1) without an access hint. Returns target to sibling.
     */
    private suspend fun TestScope.openAndPeekIndex(
        repository: MessageRepositoryImpl,
        initialKey: Int?,
        index: Int,
    ): Pair<MessageEntity?, MessageEntity?> {
        val differ = differ()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                repository
                    .messages(bufferId, MessageVisibilitySpec(), initialKey)
                    .collectLatest { differ.submitData(it) }
            }
        advanceUntilIdle()
        // peek never registers an access, so it cannot itself hint a boundary APPEND.
        val item = index.takeIf { it < differ.itemCount }?.let { differ.peek(it) }
        val newerSibling = (index - 1).takeIf { it in 0 until differ.itemCount }?.let { differ.peek(it) }
        // Diagnostic: report the materialized run around the target so the key-shift arithmetic
        // stays verifiable against Room's actual refresh-offset semantics.
        val loaded = (0 until differ.itemCount).count { differ.peek(it) != null }
        println(
            "KEYPROBE initialKey=$initialKey itemCount=${differ.itemCount} loaded=$loaded " +
                "target=${item?.msgid} newerSibling=${newerSibling?.msgid}",
        )
        job.cancel()
        return item to newerSibling
    }

    /** No live clients: every driver here is handed its scripted source explicitly. */
    private object NoClientConnectionManager : NoopConnectionManager()

    private companion object {
        /**
         * The deep interval the fixture server still holds inside the gap (rows 12..211), as the
         * 50-row pages a BEFORE ladder walks. Keyed by BOTH selector spellings of each boundary row
         * so the same backlog answers the msgid and the timestamp-only wire identically; a boundary
         * the fixture does not know about deliberately gets nothing.
         */
        private val BACKLOG_PAGES: Map<String?, List<IrcEvent>> =
            buildMap {
                fun page(
                    boundaryMsgid: String,
                    boundaryTime: Long,
                    rows: IntRange,
                ) {
                    val events =
                        rows.map { ordinal ->
                            IrcEvent.ChatMessage(
                                ctx = MessageContext("row$ordinal", ordinal.toLong(), null, "b", null),
                                kind = IrcEvent.ChatKind.PRIVMSG,
                                source = Prefix("alice"),
                                target = "#chan",
                                text = "row$ordinal",
                                isSelf = false,
                                replyToMsgid = null,
                            )
                        }
                    put(ChatHistorySelectors.msgid(boundaryMsgid), events)
                    put(ChatHistorySelectors.timestamp(boundaryTime), events)
                }
                page("row212", 212, 162..211)
                page("row162", 162, 112..161)
                page("row112", 112, 62..111)
                page("row62", 62, 12..61)
            }
    }
}
