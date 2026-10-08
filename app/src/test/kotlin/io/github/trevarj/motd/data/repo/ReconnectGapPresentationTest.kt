package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.ExperimentalPagingApi
import androidx.paging.PagingSource
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
import io.github.trevarj.motd.data.history.seamAbove
import io.github.trevarj.motd.data.sync.ChatHistoryRemoteMediator
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Models `RequiredHeadlessE2eTest.sendEchoPersistsVisibleRowAndReconnects`: a channel with a deep
 * pre-seeded backlog, a disconnect/reconnect that leaves a bouncer catch-up **history gap**, and
 * then a LIVE message (the voice-note echo) written straight into Room by [EventProcessor] at the
 * newest end. The E2E times out for 30s waiting for that live row to be either composed or reachable
 * by its paging key, even though a repository probe proves the row exists in Room.
 *
 * The question this fixture answers empirically: **can the Recent history window alone starve a live
 * newest-end row while a recoverable reconnect gap exists?** Every scenario keeps the remote mediator
 * inert (`HistoryAvailability.Unsupported`, so it never fetches or rewrites a gap) except the last,
 * which deliberately re-introduces mediator/generation churn. That isolates window bounds from both
 * the mediator's `endOfPaginationReached` behavior and Pager generation churn.
 *
 * Recent is unbounded now, so "the echo survives" is no longer the whole answer and every survival
 * case also pins WHERE THE SEAM LANDS in the presented list ([seamPlacements]). Surviving with the
 * break shown in the wrong slot would be a different bug wearing the same green tick: the point of
 * removing the clamp was to say "history is missing here", and "here" is the assertion.
 *
 * Reversed timeline: the paging query is `ORDER BY serverTime DESC, timelineOrder DESC, id DESC`, so
 * index 0 is the newest row — exactly the row the E2E scrolls to on its 5s bottom reset.
 */
@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ReconnectGapPresentationTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId = 0L
    private var bufferId = 0L

    /** Realistic ms-resolution soju timestamps; the whole fixture is offsets from this instant. */
    private val base = 1_700_000_000_000L

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
            networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "soju",
                        role = NetworkRole.BOUNCER_CHILD,
                        host = "h",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            processor.onRegistered(networkId, "me", emptyMap())
            db.bufferDao().insert(
                BufferEntity(
                    networkId = networkId,
                    name = "##motdtest",
                    displayName = "##motdtest",
                    type = BufferType.CHANNEL,
                ),
            )
            bufferId = db.bufferDao().byName(networkId, "##motdtest")!!.id
        }

    @After fun tearDown() {
        db.close()
    }

    // ---------------------------------------------------------------- fixture builders

    private fun chatMsg(
        msgid: String,
        time: Long,
        self: Boolean = false,
    ) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, null, null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix(if (self) "me" else "alice"),
        target = "##motdtest",
        text = msgid,
        isSelf = self,
        replyToMsgid = null,
    )

    /** ~260 rows of pre-seeded channel backlog, as the prior E2E journey leaves the shared room. */
    private suspend fun seedBacklog() {
        (1..260).forEach { processor.process(networkId, chatMsg("seed$it", base + it * 1_000L)) }
    }

    /** The voice-note echo: a live self message written by EventProcessor at the newest end. */
    private suspend fun sendLiveEcho(time: Long): MessageEntity {
        processor.process(networkId, chatMsg(VOICE_MSGID, time, self = true))
        return checkNotNull(db.messageDao().byMsgid(bufferId, VOICE_MSGID)) {
            "the live echo must be durable in Room before presentation is asserted"
        }
    }

    /**
     * Record the reconnect catch-up exactly as production does: a CHATHISTORY LATEST page whose
     * oldest row is newer than the previously known newest boundary makes
     * `EventProcessor.reconcileHistoryGaps` insert the recoverable reconnect gap itself. [msgidWire]
     * false mirrors the hosted-CI wire (soju `MSGREFTYPES=timestamp` strips msgid references).
     */
    private suspend fun reconnectCatchUp(
        firstTime: Long,
        count: Int,
        msgidWire: Boolean,
    ) {
        val events = (0 until count).map { chatMsg("catchup$it", firstTime + it * 1_000L) }
        val refs = events.map { ChatHistoryReference(it.ctx.msgid.takeIf { _ -> msgidWire }, it.ctx.serverTime) }
        processor.persistHistoryPageResult(
            networkId,
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "##motdtest", limit = 50),
            ChatHistoryResponse.Messages(
                events = events,
                oldest = refs.first(),
                newest = refs.last(),
                endOfHistory = false,
                primaryMessageCount = events.size,
            ),
            expectedRoomId = bufferId,
        )
    }

    // ---------------------------------------------------------------- paging harness

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

    /** Inert history source: the mediator can neither fetch nor rewrite a gap. */
    private object NoHistory : ChatHistoryRemoteMediator.HistorySource {
        override suspend fun availability() = HistoryAvailability.Unsupported

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse = ChatHistoryResponse.Messages(emptyList(), null, null, endOfHistory = true)
    }

    private fun repository(history: ChatHistoryRemoteMediator.HistorySource = NoHistory) =
        MessageRepositoryImpl(
            db.bufferDao(),
            db.networkIdentityDao(),
            db,
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
                    visibility = visibility,
                    identityRules = identityRules,
                )
            },
            db.historyGapDao(),
        )

    /**
     * Row membership of the presented timeline, straight from the same SQL the PagingSource runs.
     * Separates "the source never offered the row" from "offered but never loaded".
     */
    private suspend fun windowContains(id: Long): Boolean {
        val result =
            db
                .messageDao()
                .pagingSource(
                    messagePagingQuery(bufferId, MessageVisibilitySpec(), IrcIdentityRules()),
                ).load(PagingSource.LoadParams.Refresh(null, 1_000, false))
        return (result as PagingSource.LoadResult.Page).data.any { it.id == id }
    }

    /** Open the Recent timeline, settle it, and report what the differ actually presents. */
    private suspend fun TestScope.presentation(
        repository: MessageRepositoryImpl,
        rounds: Int = 10,
        onRound: suspend (Int) -> Unit = {},
    ): Presentation {
        val differ = differ()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                repository
                    .messages(bufferId, MessageVisibilitySpec())
                    .collectLatest { differ.submitData(it) }
            }
        repeat(rounds) { round ->
            advanceUntilIdle()
            onRound(round)
            // The E2E's 5s bottom reset: performScrollToIndex(0) registers an access at the newest
            // index, which is what would materialize a placeholder there.
            if (differ.itemCount > 0) differ.getItem(0)
        }
        advanceUntilIdle()
        // Placeholders are kept as nulls on purpose: MessageList composes over exactly this list, and
        // a seam's placement depends on whether its row's OLDER neighbour is materialized.
        val rows = (0 until differ.itemCount).map { differ.peek(it) }
        val newest = if (differ.itemCount > 0) differ.peek(0) else null
        job.cancel()
        return Presentation(differ.itemCount, newest, rows)
    }

    private data class Presentation(
        val itemCount: Int,
        val newest: MessageEntity?,
        /** Newest-first, with unmaterialized placeholders left as null. */
        val rows: List<MessageEntity?>,
    ) {
        val loadedMsgids: List<String?> get() = rows.filterNotNull().map { it.msgid }
    }

    /**
     * The seam slots the presented list actually draws, as `(row msgid, gapId)` newest-first.
     *
     * Walks the snapshot exactly as `MessageList` does — index 0 newest, older neighbour at the next
     * index, a null neighbour left undecidable — so this is the rendered divider, not a restatement
     * of the seam list.
     */
    private suspend fun seamPlacements(
        repository: MessageRepositoryImpl,
        presented: Presentation,
    ): List<Pair<String?, Long>> {
        val seams = repository.observeTimelineSeams(bufferId, MessageVisibilitySpec()).first()
        return presented.rows.mapIndexedNotNull { index, row ->
            row ?: return@mapIndexedNotNull null
            seamAbove(row, presented.rows.getOrNull(index + 1), seams)?.let { row.msgid to it.gapId }
        }
    }

    private suspend fun report(
        label: String,
        echo: MessageEntity,
        p: Presentation,
    ) {
        val gaps = db.historyGapDao().forRoom(bufferId)
        println(
            "RECONNECT-GAP[$label] echo(id=${echo.id} t=${echo.serverTime} order=${echo.timelineOrder}) " +
                "itemCount=${p.itemCount} newest=${p.newest?.msgid} " +
                "inWindow=${windowContains(echo.id)} " +
                "gaps=" +
                gaps.joinToString { g ->
                    "(older=${g.olderServerTime}/${g.olderMsgid}/${g.olderEventId} " +
                        "newer=${g.newerServerTime}/${g.newerMsgid}/${g.newerEventId} rec=${g.recoverable})"
                },
        )
    }

    // ---------------------------------------------------------------- scenarios

    /**
     * Control, msgid wire: the reconnect gap is created by production code and its newer edge
     * resolves to a locally present catch-up row. The live echo is newer than that edge, so the
     * Recent window must contain it and the timeline must present it at index 0.
     */
    @Test
    fun liveEchoIsPresentedWhenTheReconnectGapNewerEdgeResolvesLocally() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                seedBacklog()
                reconnectCatchUp(firstTime = base + 400_000, count = 50, msgidWire = true)
                val echo = sendLiveEcho(base + 500_000)
                val repository = repository()
                val presented = presentation(repository)
                report("resolvable-newer-edge", echo, presented)

                // The gap is still open and still recoverable: the inert history source means no
                // PREPEND/AFTER merge can ever have closed it. Presenting the live row therefore does
                // NOT depend on gap closure (Lead 2).
                val gap = db.historyGapDao().forRoom(bufferId).single()
                assertTrue("reconnect gap still open and recoverable", gap.recoverable)
                assertTrue("live echo inside the Recent window", windowContains(echo.id))
                assertEquals("newest presented row", VOICE_MSGID, presented.newest?.msgid)
                // The window keeps the echo AND says why the rows below it are not contiguous: one seam,
                // in the slot above the first catch-up row, which is the gap's newer edge.
                assertEquals(
                    "seam slot",
                    listOf("catchup0" to gap.id),
                    seamPlacements(repository, presented),
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * Hosted-CI wire: soju advertises `MSGREFTYPES=timestamp`, so the catch-up page's boundary
     * references carry no msgid and the gap's newer edge must be resolved through the stored
     * eventId/serverTime rungs of `GapAnchorResolver.resolve`.
     */
    @Test
    fun liveEchoIsPresentedOnATimestampOnlyReconnectGap() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                seedBacklog()
                reconnectCatchUp(firstTime = base + 400_000, count = 50, msgidWire = false)
                val echo = sendLiveEcho(base + 500_000)
                val repository = repository()
                val presented = presentation(repository)
                report("timestamp-only", echo, presented)

                val gap = db.historyGapDao().forRoom(bufferId).single()
                assertTrue("reconnect gap still open and recoverable", gap.recoverable)
                // The timestamp-only wire strips the msgid, but EventProcessor.resolvePageBoundary
                // matches the boundary row by serverTime and stores its eventId, so the repository never
                // needs the Long.MAX_VALUE fallback. This is why the realistic reconnect gap is benign.
                assertNotNull("timestamp-only gap still carries a resolvable eventId", gap.newerEventId)
                assertTrue("live echo inside the Recent window", windowContains(echo.id))
                assertEquals("newest presented row", VOICE_MSGID, presented.newest?.msgid)
                // A stripped msgid changes which resolver rung finds the edge, not where the seam lands.
                assertEquals(
                    "seam slot",
                    listOf("catchup0" to gap.id),
                    seamPlacements(repository, presented),
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * Boundary characterization for the same unresolvable edge one millisecond OLDER than the live
     * echo: the window keeps the echo. Pins that the cliff in
     * [liveEchoAtTheUnresolvableGapEdgeTimestampIsStarvedByWindowBoundsAlone] is exactly the
     * equal-serverTime tie, not the fallback anchor as such.
     */
    @Test
    fun liveEchoOneMillisecondNewerThanAnUnresolvableGapEdgeSurvives() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                seedBacklog()
                val echoTime = base + 500_000
                db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = bufferId,
                        olderMsgid = null,
                        olderServerTime = base + 260_000,
                        newerMsgid = null,
                        newerServerTime = echoTime - 1,
                        recoverable = true,
                    ),
                )
                val echo = sendLiveEcho(echoTime)
                val repository = repository()
                val presented = presentation(repository)
                report("unresolvable-one-ms-older", echo, presented)

                assertTrue("live echo inside the Recent window", windowContains(echo.id))
                assertEquals("newest presented row", VOICE_MSGID, presented.newest?.msgid)
                // One millisecond older than the echo puts the seam in the echo's own slot: the break is
                // genuinely between seed260 and the echo, and it is drawn there rather than applied.
                assertEquals(
                    "seam slot",
                    listOf(
                        VOICE_MSGID to
                            db
                                .historyGapDao()
                                .forRoom(bufferId)
                                .single()
                                .id,
                    ),
                    seamPlacements(repository, presented),
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * **Regression pin for the window-bounds starvation.** The gap's newer edge has neither a
     * resolvable msgid nor an eventId, so `GapAnchorResolver.resolve` can only reach it as a
     * `GapEdgeAnchor.TimeOnly`, and it ties the live echo's millisecond.
     *
     * With the original `Long.MAX_VALUE` fallback this blanked the timeline completely — measured
     * `lower=TimelineAnchor(t, MAX, MAX)`, `itemCount=0`, `inWindow=false` — because
     * [io.github.trevarj.motd.data.db.TimelineAnchor] compares serverTime, then timelineOrder, then
     * eventId, so the fallback dominated every real row at that serverTime and the inclusive
     * lower-bound SQL degenerated to "strictly newer than t". Nothing composed and no paging key
     * resolved, exactly the E2E symptom. The permissive fallback keeps the newest-end row.
     */
    @Test
    fun liveEchoAtAnUnresolvableGapEdgeTimestampIsNotStarvedByWindowBounds() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                seedBacklog()
                val echoTime = base + 500_000
                // Catch-up island whose boundary the client could not resolve locally (msgid stripped by
                // the timestamp-only wire AND no retained event for the boundary row), sharing the echo's
                // millisecond.
                db.historyGapDao().insert(
                    HistoryGapEntity(
                        roomId = bufferId,
                        olderMsgid = null,
                        olderServerTime = base + 260_000,
                        newerMsgid = null,
                        newerServerTime = echoTime,
                        recoverable = true,
                    ),
                )
                val echo = sendLiveEcho(echoTime)
                val repository = repository()
                val presented = presentation(repository)
                report("unresolvable-equal-time", echo, presented)

                // Observed: the window is not merely missing the echo, it is EMPTY (itemCount 0) — the
                // MAX_VALUE lower boundary excludes every local row at or below its serverTime. The
                // timeline has nothing to compose and no paging key to scroll to, which is precisely the
                // E2E symptom.
                assertTrue("live echo inside the Recent window", windowContains(echo.id))
                assertTrue("Recent window is not blanked", presented.itemCount > 0)
                assertEquals("newest presented row", VOICE_MSGID, presented.newest?.msgid)
                // The cohort-floor projection is what keeps this honest at the tie: the seam sits UNDER
                // the whole equal-timestamp cohort, so it lands in the echo's slot instead of slicing
                // through the cohort at a position the client cannot justify — and instead of the
                // cohort-ceiling anchor that used to sit above every materialized row, match nothing,
                // and leave a silently truncated timeline with no indication anything was missing.
                assertEquals(
                    "seam slot",
                    listOf(
                        VOICE_MSGID to
                            db
                                .historyGapDao()
                                .forRoom(bufferId)
                                .single()
                                .id,
                    ),
                    seamPlacements(repository, presented),
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * The Lead-3 shape: the live echo arrives WHILE the mediator is backfilling and rewriting the
     * `history_gaps` row, so every persisted page recedes the Recent lower boundary and rebuilds the
     * whole Pager under the collector. Generation churn must not prevent the newest row from being
     * presented.
     */
    @Test
    fun liveEchoArrivingDuringBackfillChurnIsPresented() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                seedBacklog()
                reconnectCatchUp(firstTime = base + 400_000, count = 50, msgidWire = false)
                val history =
                    ScriptedBefore(
                        ArrayDeque(
                            (0 until 4).map { page ->
                                (0 until 50).map { row ->
                                    chatMsg(
                                        "fill${page}_$row",
                                        base + 300_000 - page * 50_000L + row * 1_000L,
                                    )
                                }
                            },
                        ),
                    )
                val repository = repository(history)
                lateinit var echo: MessageEntity
                val presented =
                    presentation(repository) { round ->
                        // Mid-backfill live arrival, exactly like the voice echo landing while the reconnect
                        // catch-up is still paging.
                        if (round == 2) echo = sendLiveEcho(base + 500_000)
                    }
                report("backfill-churn", echo, presented)

                assertNotNull("live echo durable in Room", db.messageDao().byMsgid(bufferId, VOICE_MSGID))
                assertTrue("live echo inside the Recent window", windowContains(echo.id))
                assertEquals("newest presented row", VOICE_MSGID, presented.newest?.msgid)
                // Backfill moves the gap under the collector, so the seam's exact slot is a function of
                // how far the churn got. What must hold regardless: a live row arriving at the newest end
                // is never fenced off behind a seam it is not actually adjacent to.
                assertTrue(
                    "the live echo must not be separated from the timeline by a seam",
                    seamPlacements(repository, presented).none { it.first == VOICE_MSGID },
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * The worst gap the fixture can build — an unidentifiable newer edge tying the newest row's
     * millisecond — is exactly the shape that emptied the whole window before `e91698a0`, back when
     * a gap edge could become a SQL lower boundary and an anchor dominating the newest row's
     * `(timelineOrder, id)` at the same serverTime excluded every row including that one.
     *
     * There is no longer a code path that turns that input into a clamp: the queries take no
     * boundary at all, so the compiler now enforces what this used to assert. The dangerous input
     * still exists, so pin what it does instead — every row stays reachable, and the gap is still
     * seen, publishing a seam. A store that had simply stopped noticing gaps would also present
     * every row, which is why both halves are asserted together.
     */
    @Test
    fun theWorstGapEdgeStillPresentsEveryRowAndPublishesASeam() =
        runTest {
            seedBacklog()
            val newest = checkNotNull(db.messageDao().byMsgid(bufferId, "seed260"))

            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = null,
                    olderServerTime = base + 100_000,
                    newerMsgid = null,
                    newerServerTime = newest.serverTime,
                    recoverable = true,
                ),
            )
            val repository = repository()

            // The worst boundary the resolver can be handed no longer reaches the presented rows at all:
            // nothing derives a window from a gap, so every seeded row stays reachable...
            assertTrue("every seeded row stays reachable", windowContains(newest.id))
            // ...and the gap is not being ignored either: it publishes a seam, which is how the break is
            // shown now. A gap store that had simply stopped seeing gaps would also present every row.
            assertEquals(1, repository.observeTimelineSeams(bufferId, MessageVisibilitySpec()).first().size)
        }

    /**
     * A fresh buffer's first CHATHISTORY LATEST seed on a timestamp-only wire returns a SATURATED,
     * msgid-less, non-terminal page. That page used to arm a degenerate zero-width gap insert in
     * `EventProcessor.reconcileHistoryGaps`, which named the same event on both edges and marked it
     * `recoverable = false`.
     *
     * That insert was removed: a zero-width interval asserts that messages are missing between a row
     * and itself, and `recoverable = false` is reserved for server-proven-empty remainders, which a
     * saturated page never proves. Both of its consumers acted on those falsehoods — the mediator
     * treated the unrecoverable focused gap as permanently terminal, and the history window
     * clamped the Recent window at the edge row.
     *
     * This test now pins the absence: the seed writes no gap at all, so nothing bounds the Recent
     * window and nothing terminates older backfill. The ambiguous boundary is recorded as a
     * diagnostic instead of being encoded as a false interval.
     */
    @Test
    fun saturatedTimestampOnlyLatestSeedWritesNoDegenerateGap() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val history =
                    SaturatedTimestampWire(
                        latest = (0 until 50).map { chatMsg("seed$it", base + 200_000 + it * 1_000L) },
                        before = ArrayDeque(emptyList()),
                    )
                val repository = repository(history)
                val presented = presentation(repository, rounds = 8)

                val gaps = db.historyGapDao().forRoom(bufferId)
                val boundaryRow = checkNotNull(db.messageDao().byMsgid(bufferId, "seed0"))
                println(
                    "NO-DEGENERATE-GAP requests=${history.calls} gaps=$gaps " +
                        "boundaryRow(id=${boundaryRow.id} t=${boundaryRow.serverTime}) " +
                        "itemCount=${presented.itemCount}",
                )

                assertTrue("saturated seed writes no gap", gaps.isEmpty())
                assertTrue("window presents the seeded rows", presented.itemCount > 0)
                // No gap, so no seam either: the ambiguous boundary is recorded as a diagnostic, not
                // encoded as a false interval that would now draw a divider claiming lost history.
                assertEquals(
                    emptyList<Pair<String?, Long>>(),
                    seamPlacements(repository, presented),
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * **Reachability probe — nothing here is hand-constructed.** Runs the whole hosted-CI shape
     * through production code on a simulated soju `MSGREFTYPES=timestamp` wire and reports what
     * `history_gaps` production actually ends up holding:
     *
     *  1. first open of an empty buffer drives the real [ChatHistoryRemoteMediator] LATEST seed with
     *     a SATURATED, msgid-less, non-terminal page — the exact precondition set of the
     *     saturated-boundary gap at `EventProcessor.kt:1159-1182` (`reference.msgid == null`,
     *     `primaryMessageCount >= limit`, `directionalGap == null`, `previousNewest == null`);
     *  2. real APPEND backfill through saturated msgid-less BEFORE pages;
     *  3. a reconnect catch-up LATEST page that jumps past the known newest boundary, so
     *     `reconcileHistoryGaps` writes the recoverable reconnect gap itself;
     *  4. the real send path ([EventProcessor.insertPending] + a labelled echo) with the echo's
     *     origin-server time BEHIND the newest authoritative row — soju trailing Libera, the skew
     *     commit 83a7fba4 addressed — so [CanonicalTimelineStore.applySelfSendSortFloor] clamps the
     *     echo's serverTime UP into an exact tie with the newest catch-up row.
     *
     * That combination is the only plausible near-deterministic wire-specific trigger for the ~90%
     * gate failure. The assertion is the invariant: the clamped self-send must still be presented.
     */
    @Test
    fun timestampOnlyWireJourneyKeepsTheClampedSelfSendPresented() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                // (1)+(2) first open of an EMPTY buffer: the mediator seeds with LATEST and then pages
                // older via BEFORE, every page saturated (50 == limit) and msgid-less.
                val history =
                    SaturatedTimestampWire(
                        latest = (0 until 50).map { chatMsg("seed$it", base + 200_000 + it * 1_000L) },
                        before =
                            ArrayDeque(
                                (1..3).map { page ->
                                    (0 until 50).map { row ->
                                        chatMsg("old${page}_$row", base + 200_000 - page * 50_000L + row * 1_000L)
                                    }
                                },
                            ),
                    )
                val repository = repository(history)
                presentation(repository, rounds = 8)
                val gapsAfterOpen = db.historyGapDao().forRoom(bufferId)
                println(
                    "REACHABILITY[after-open] requests=${history.calls} gaps=" +
                        gapsAfterOpen.joinToString { g ->
                            "(older=${g.olderServerTime}/${g.olderMsgid}/${g.olderEventId} " +
                                "newer=${g.newerServerTime}/${g.newerMsgid}/${g.newerEventId} rec=${g.recoverable})"
                        },
                )

                // (3) reconnect catch-up that jumps past the known newest boundary.
                reconnectCatchUp(firstTime = base + 400_000, count = 50, msgidWire = false)

                // (4) real self-send whose echo carries an origin-server time OLDER than the newest
                // authoritative row, so applySelfSendSortFloor clamps it into an exact tie.
                val pendingId =
                    processor.insertPending(
                        bufferId,
                        "motd-voice-1",
                        "me",
                        "voice note",
                        null,
                        io.github.trevarj.motd.data.db.MessageKind.PRIVMSG,
                    )
                processor.process(
                    networkId,
                    IrcEvent.ChatMessage(
                        ctx = MessageContext(VOICE_MSGID, base + 300_000, null, null, "motd-voice-1"),
                        kind = IrcEvent.ChatKind.PRIVMSG,
                        source = Prefix("me"),
                        target = "##motdtest",
                        text = "voice note",
                        isSelf = true,
                        replyToMsgid = null,
                    ),
                )
                val echo = checkNotNull(db.messageDao().byCanonicalId(pendingId))
                val newestOther = db.messageDao().byMsgid(bufferId, "catchup49")
                println(
                    "REACHABILITY[clamp] echoTimeOnWire=${base + 300_000} storedEchoTime=${echo.serverTime} " +
                        "newestCatchUp=${newestOther?.serverTime} clamped=${echo.serverTime != base + 300_000L}",
                )

                val presented = presentation(repository(history))
                report("ci-journey", echo, presented)

                assertTrue("live self-send inside the Recent window", windowContains(echo.id))
                assertTrue("Recent window is not blanked", presented.itemCount > 0)
                assertEquals("newest presented row", VOICE_MSGID, presented.newest?.msgid)
                // The clamped self-send ties the newest catch-up row's millisecond, which is the exact
                // cohort a seam projection can slice through. It must never end up above the send.
                assertTrue(
                    "the clamped self-send must not be fenced off by a seam",
                    seamPlacements(repository(history), presented).none { it.first == VOICE_MSGID },
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * Saturated, msgid-less LATEST + BEFORE pages: the hosted-CI wire regime. Every reference has a
     * null msgid (soju advertises `MSGREFTYPES=timestamp`) and every page fills the request limit,
     * which is what arms `EventProcessor`'s saturated-boundary gap.
     */
    private inner class SaturatedTimestampWire(
        private val latest: List<IrcEvent>,
        private val before: ArrayDeque<List<IrcEvent>>,
    ) : ChatHistoryRemoteMediator.HistorySource {
        val calls = mutableListOf<ChatHistoryRequest.Subcommand>()
        private var latestServed = false

        override suspend fun availability() = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP), 100)

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            calls += req.subcommand
            val events =
                when (req.subcommand) {
                    ChatHistoryRequest.Subcommand.LATEST -> {
                        if (latestServed) emptyList() else latest.also { latestServed = true }
                    }

                    ChatHistoryRequest.Subcommand.BEFORE -> {
                        before.removeFirstOrNull() ?: emptyList()
                    }

                    else -> {
                        emptyList()
                    }
                }
            val refs =
                events
                    .mapNotNull { (it as? IrcEvent.ChatMessage)?.ctx }
                    .map { ChatHistoryReference(null, it.serverTime) }
            return ChatHistoryResponse.Messages(
                events,
                oldest = refs.firstOrNull(),
                newest = refs.lastOrNull(),
                endOfHistory = false,
                primaryMessageCount = refs.size,
            )
        }
    }

    /** Timestamp-only BEFORE pages, mirroring the hosted-CI wire's stripped references. */
    private inner class ScriptedBefore(
        private val pages: ArrayDeque<List<IrcEvent>>,
    ) : ChatHistoryRemoteMediator.HistorySource {
        override suspend fun availability() = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP), 100)

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            val events =
                if (req.subcommand == ChatHistoryRequest.Subcommand.BEFORE) {
                    pages.removeFirstOrNull() ?: emptyList()
                } else {
                    emptyList()
                }
            val refs =
                events
                    .mapNotNull { (it as? IrcEvent.ChatMessage)?.ctx }
                    .map { ChatHistoryReference(null, it.serverTime) }
            return ChatHistoryResponse.Messages(
                events,
                oldest = refs.firstOrNull(),
                newest = refs.lastOrNull(),
                endOfHistory = false,
                primaryMessageCount = refs.size,
            )
        }
    }

    private companion object {
        const val VOICE_MSGID = "y2i68vtcgxjjvsk9666fjr8cqa"
    }
}
