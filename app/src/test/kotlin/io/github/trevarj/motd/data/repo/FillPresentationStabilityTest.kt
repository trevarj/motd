package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.ExperimentalPagingApi
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
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
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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
 * What a history FILL is allowed to do to a timeline that is already on screen.
 *
 * The device symptom this exists for is a jarring flash after a catch-up completes rather than a
 * quiet arrival of older messages. Two of its ingredients live below the UI and are pinnable here,
 * with real Room and real Paging: every fetched page persists in its own transaction, so one
 * catch-up regenerates the `PagingSource` several times in a second, and each regeneration re-places
 * a loaded window that is far smaller than the window it replaces. Rows outside the new window
 * present as `null`, and a `null` row is a skeleton where a message used to be.
 *
 * These are DATA pins, deliberately. `AsyncPagingDataDiffer` has no viewport and no scroll anchor,
 * so nothing here can prove what the lazy list does — that is the viewport pin's own unit tests and
 * a Robolectric Compose test still to come. What this file can prove, and does, is that the
 * presented list itself stays sane across a fill: it never empties, the rows around the reader's
 * position stay materialized, and their order and identity are conserved. A regression in any of
 * those would make the flash strictly worse and is invisible to every other test in the repo.
 *
 * Modelled on [RecentPagingAppendReproTest], including its fixture geometry, so the two read
 * together: that file pins what the demand sources ASK FOR, this one pins what the timeline SHOWS.
 */
@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FillPresentationStabilityTest {
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

    // --- the pins ---------------------------------------------------------------------------------

    /**
     * A fill invalidates the source once per persisted page. If any of those presentations reaches
     * the screen as an empty list, the whole timeline blanks for a frame and repaints — the single
     * most visible thing a fill could do, and one that no row-count assertion elsewhere would catch
     * because the end state is identical either way.
     *
     * Sampled from inside the diffing callbacks, not just at rest, so a one-presentation blank
     * cannot hide between two settled observations.
     */
    @Test
    fun aFillNeverPresentsAnEmptyTimeline() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                seedCatchUpIsland()
                val probe = openTimeline(anchorIndex = 20)

                assertEquals("the seeded island is on screen", 50, probe.differ.itemCount)
                probe.counts.clear()

                runAutopilot(scriptedHistory())
                probe.settle(anchorIndex = 20)

                assertEquals("the fill landed", 200, probe.differ.itemCount)
                assertTrue("the fill presented nothing to observe", probe.counts.isNotEmpty())
                assertTrue(
                    "a fill presented an empty timeline; counts were ${probe.counts}",
                    probe.counts.none { it == 0 },
                )
                probe.close()
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * The rows the reader can see must survive the fill unchanged: same ids, same order, and still
     * MATERIALIZED after the refresh plus one viewport hint. A `null` here is the skeleton row, and
     * a reordered or renumbered id is the timeline visibly rewriting itself under the reader.
     *
     * The fill lands 150 rows OLDER than the anchor, which is the ordinary case — nothing above the
     * viewport changes, so nothing the reader is looking at may move.
     */
    @Test
    fun anOlderFillLeavesTheOnScreenRowsIdenticalAndMaterialized() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                seedCatchUpIsland()
                val probe = openTimeline(anchorIndex = 20)
                val before = probe.viewport(anchorIndex = 20)

                assertEquals("the modeled viewport before the fill", SEEDED_VIEWPORT_AT_20, before)

                runAutopilot(scriptedHistory())
                probe.settle(anchorIndex = 20)

                assertEquals("durable rows after the fill", 200, probe.differ.itemCount)
                assertEquals("the on-screen rows are the same rows, in the same order", before, probe.viewport(20))
                assertTrue("no on-screen row degraded to a placeholder", probe.viewport(20).none { it == null })
                // Non-vacuity, and the defect itself in one line: 200 rows are presented but only
                // `initialLoadSize` of them are loaded, so rows far from the anchor ARE placeholders.
                // That is what a skeleton on screen is made of, and it is why the materialization
                // assertions above are worth making at all.
                assertTrue(
                    "a bounded loaded window is in play; without one nothing above is being proved",
                    (0 until probe.differ.itemCount).any { probe.differ.peek(it) == null },
                )
                probe.close()
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * The catch-up case, where indices genuinely move: sixty rows NEWER than the whole viewport
     * arrive, so every row the reader can see slides sixty slots older. Identity and order must be
     * conserved across that shift, and the rows must be materialized again after one hint cycle at
     * the anchor's new index.
     *
     * This is the transition the viewport pin exists for. Here it pins the half that Paging owns:
     * the row that was at index 20 really is at index 80, and it is a loaded row there. If that were
     * not true, no scroll correction could put the reader back on it.
     */
    @Test
    fun rowsAreConservedAcrossACatchUpThatShiftsEveryIndex() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                seedCatchUpIsland()
                val probe = openTimeline(anchorIndex = 20)
                val before = probe.viewport(anchorIndex = 20)
                assertEquals("the modeled viewport before the catch-up", SEEDED_VIEWPORT_AT_20, before)

                (300..359).forEach { processor.process(networkId, chatMsg("row$it", it.toLong())) }
                probe.settle(anchorIndex = 80)

                assertEquals("60 newer rows joined the 50 seeded ones", 110, probe.differ.itemCount)
                assertEquals(
                    "every visible row moved by exactly the number of rows inserted above it",
                    before,
                    probe.viewport(anchorIndex = 80),
                )
                assertTrue("no on-screen row degraded to a placeholder", probe.viewport(80).none { it == null })
                probe.close()
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * Both at once, which is what a reconnect actually does: the catch-up lands newer rows and the
     * gap fill lands older ones, against a live timeline. The end state must still be one ordered
     * timeline with the reader's rows intact — no duplicates, no reordering, no placeholders left
     * over the viewport.
     */
    @Test
    fun anOverlappingCatchUpAndFillConserveTheOnScreenRows() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                seedCatchUpIsland()
                val probe = openTimeline(anchorIndex = 20)
                val before = probe.viewport(anchorIndex = 20)
                assertEquals("the modeled viewport before either source ran", SEEDED_VIEWPORT_AT_20, before)
                probe.counts.clear()

                (300..359).forEach { processor.process(networkId, chatMsg("row$it", it.toLong())) }
                runAutopilot(scriptedHistory())
                probe.settle(anchorIndex = 80)

                assertEquals("60 newer + 50 seeded + 150 filled", 260, probe.differ.itemCount)
                assertEquals("the reader's rows are untouched by either source", before, probe.viewport(80))
                assertTrue("the sources presented nothing to observe", probe.counts.isNotEmpty())
                assertTrue("no presentation blanked the timeline; counts were ${probe.counts}", probe.counts.none { it == 0 })
                probe.close()
            } finally {
                Dispatchers.resetMain()
            }
        }

    // --- fixture ----------------------------------------------------------------------------------

    /**
     * The state the required E2E reaches before opening the room: a read marker, a 49-row reconnect
     * catch-up island above it, and the recoverable gap the catch-up recorded between them.
     */
    private suspend fun seedCatchUpIsland() {
        processor.process(networkId, chatMsg("marker", 10))
        (212..260).forEach { processor.process(networkId, chatMsg("row$it", it.toLong())) }
        db.historyGapDao().insert(
            HistoryGapEntity(
                roomId = bufferId,
                olderMsgid = "marker",
                olderServerTime = 10,
                newerMsgid = "row212",
                newerServerTime = 212,
                recoverable = true,
            ),
        )
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
     * Serves the gap's backlog by exact boundary, so the fill's three pages are deterministic. The
     * Pager's own mediator gets [SilentHistory] instead, so a boundary APPEND cannot land rows and
     * add a second, uncontrolled source of invalidations to what these tests measure.
     */
    private inner class ScriptedHistory : HistoryGapFillCoordinator.HistorySource {
        override suspend fun availability() = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID), 100)

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            if (req.subcommand != ChatHistoryRequest.Subcommand.BEFORE) return messages(emptyList())
            return messages(BACKLOG_PAGES[req.bound1].orEmpty())
        }
    }

    private inner class SilentHistory : ChatHistoryRemoteMediator.HistorySource {
        override suspend fun availability() = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID), 100)

        override suspend fun chathistory(req: ChatHistoryRequest) = messages(emptyList())
    }

    private fun scriptedHistory() = ScriptedHistory()

    private fun repository() =
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
                    SilentHistory(),
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

    private suspend fun runAutopilot(history: ScriptedHistory) =
        HistoryGapFillCoordinator(
            NoClientConnectionManager,
            db.bufferDao(),
            db.messageDao(),
            db.historyCursorDao(),
            db.historyGapDao(),
            loader,
            DiagnosticLogger.Noop,
            settingsRepository = DataStoreSettingsRepository(ApplicationProvider.getApplicationContext<Context>()),
            pruner = HistoryPruner.Noop,
        ).fill(bufferId, HistoryGapFillCoordinator.GapSelection.Newest, history, pageSize = 50)

    // --- the modeled screen -----------------------------------------------------------------------

    /**
     * A live Paging collection plus the two things a screen contributes: a viewport that keeps
     * asking for the rows around one index, and an observation of every presented row count.
     */
    private inner class TimelineProbe(
        val differ: AsyncPagingDataDiffer<MessageEntity>,
        private val job: Job,
        val counts: MutableList<Int>,
        private val scope: TestScope,
    ) {
        /** Drain the scheduler and re-apply the viewport's load hint, as a real screen would. */
        fun settle(anchorIndex: Int) {
            repeat(SETTLE_ROUNDS) {
                scope.advanceUntilIdle()
                hint(anchorIndex)
            }
            scope.advanceUntilIdle()
        }

        fun hint(anchorIndex: Int) {
            if (anchorIndex in 0 until differ.itemCount) differ.getItem(anchorIndex)
        }

        /** The rows a viewport centred on [anchorIndex] would be showing; `null` is a skeleton. */
        fun viewport(anchorIndex: Int): List<String?> =
            (anchorIndex - VIEWPORT_RADIUS..anchorIndex + VIEWPORT_RADIUS)
                .map { index -> index.takeIf { it in 0 until differ.itemCount }?.let { differ.peek(it)?.msgid } }

        fun close() = job.cancel()
    }

    private fun TestScope.openTimeline(anchorIndex: Int): TimelineProbe {
        val counts = mutableListOf<Int>()
        lateinit var differ: AsyncPagingDataDiffer<MessageEntity>
        // Sampling from the diffing callbacks is what makes a one-presentation blank observable; a
        // list that empties and refills between two settled reads looks identical to one that never
        // emptied at all.
        val updates =
            object : ListUpdateCallback {
                override fun onInserted(
                    position: Int,
                    count: Int,
                ) {
                    counts += differ.itemCount
                }

                override fun onRemoved(
                    position: Int,
                    count: Int,
                ) {
                    counts += differ.itemCount
                }

                override fun onMoved(
                    fromPosition: Int,
                    toPosition: Int,
                ) {
                    counts += differ.itemCount
                }

                override fun onChanged(
                    position: Int,
                    count: Int,
                    payload: Any?,
                ) {
                    counts += differ.itemCount
                }
            }
        differ =
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
                updateCallback = updates,
                mainDispatcher = Dispatchers.Unconfined,
                workerDispatcher = Dispatchers.Unconfined,
            )
        val repository = repository()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.messages(bufferId, MessageVisibilitySpec()).collectLatest { differ.submitData(it) }
            }
        val probe = TimelineProbe(differ, job, counts, this)
        probe.settle(anchorIndex)
        return probe
    }

    /** No live clients: the fill is handed its source explicitly. */
    private object NoClientConnectionManager : NoopConnectionManager()

    private companion object {
        /** Rows either side of the anchor the modeled viewport shows. */
        const val VIEWPORT_RADIUS = 4

        /** Hint/drain rounds a presentation is given to materialize the viewport. */
        const val SETTLE_ROUNDS = 10

        /** The nine rows a viewport anchored at index 20 shows over the seeded island. */
        private val SEEDED_VIEWPORT_AT_20 =
            listOf(
                "row244",
                "row243",
                "row242",
                "row241",
                "row240",
                "row239",
                "row238",
                "row237",
                "row236",
            )

        /** The interval the fixture server holds inside the gap (rows 12..211), as 50-row pages. */
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
            }
    }
}
