package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingConfig
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
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
import io.github.trevarj.motd.data.history.HistoryLadderStalled
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.client.IrcTimeoutException
import io.github.trevarj.motd.irc.event.HistoryEventMetadata
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.event.ServerTimeSource
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Load-logic coverage for [ChatHistoryRemoteMediator], the on-open backfill. The gap this guards:
 * with SKIP_INITIAL_REFRESH, an empty buffer never gets a REFRESH, so Paging drives APPEND past the
 * end boundary — which must LATEST-seed the newest page instead of bailing on a null oldest bound.
 */
@OptIn(ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatHistoryRemoteMediatorTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId = 0L
    private var bufferId = 0L

    @Before fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
            processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            networkId =
                db.networkDao().insert(
                    NetworkEntity(name = "libera", role = NetworkRole.DIRECT, host = "h", port = 6697, nick = "me", username = "me", realname = "Me"),
                )
            processor.onRegistered(networkId, "me", emptyMap())
            db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL))
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

    private fun joined(
        msgid: String,
        time: Long,
        nick: String = "lurker",
        isSelf: Boolean = false,
    ) = IrcEvent.Joined(
        ctx = MessageContext(msgid, time, null, "b", null),
        nick = nick,
        channel = "#chan",
        account = null,
        realname = null,
        isSelf = isSelf,
    )

    private fun hiddenPage(index: Int): List<IrcEvent> {
        val newest = 10_000 - ((index - 1) * 50) - 1
        return ((newest - 49)..newest).map { joined("join-$it", it.toLong()) }
    }

    private fun messages(
        events: List<IrcEvent>,
        endOfHistory: Boolean = false,
    ): ChatHistoryResponse.Messages {
        val references =
            events.mapNotNull { event ->
                val ctx =
                    when (event) {
                        is IrcEvent.ChatMessage -> event.ctx
                        is IrcEvent.Joined -> event.ctx
                        is IrcEvent.TagMessage -> event.ctx
                        else -> null
                    } ?: return@mapNotNull null
                ChatHistoryReference(ctx.msgid, ctx.serverTime)
            }
        return ChatHistoryResponse.Messages(
            events,
            oldest = references.firstOrNull(),
            newest = references.lastOrNull(),
            endOfHistory = endOfHistory,
            primaryMessageCount = references.size,
        )
    }

    /** Scripts LATEST + BEFORE responses and records the subcommands issued. */
    private inner class FakeHistory(
        val hasChatHistory: Boolean = true,
        val offline: Boolean = false,
        val latest: List<IrcEvent> = emptyList(),
        val before: ArrayDeque<List<IrcEvent>> = ArrayDeque(),
        val latestEndOfHistory: Boolean = false,
        val beforeEndOfHistory: Boolean = false,
        val failure: Throwable? = null,
        val failureFor: ((ChatHistoryRequest) -> Throwable?)? = null,
        val responseFor: ((ChatHistoryRequest) -> ChatHistoryResponse.Messages?)? = null,
        val referenceTypes: Set<HistoryReferenceType> =
            setOf(
                HistoryReferenceType.TIMESTAMP,
                HistoryReferenceType.MSGID,
            ),
    ) : ChatHistoryRemoteMediator.HistorySource {
        val calls = mutableListOf<ChatHistoryRequest.Subcommand>()
        val requests = mutableListOf<ChatHistoryRequest>()

        override suspend fun availability(): HistoryAvailability =
            if (offline) {
                HistoryAvailability.NegotiatingOrOffline
            } else if (hasChatHistory) {
                HistoryAvailability.Ready(
                    referenceTypes,
                    100,
                )
            } else {
                HistoryAvailability.Unsupported
            }

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            calls += req.subcommand
            requests += req
            (failureFor?.invoke(req) ?: failure)?.let { throw it }
            responseFor?.invoke(req)?.let { return it }
            return when (req.subcommand) {
                ChatHistoryRequest.Subcommand.LATEST -> {
                    messages(latest, latestEndOfHistory)
                }

                ChatHistoryRequest.Subcommand.BEFORE -> {
                    messages(
                        before.removeFirstOrNull() ?: emptyList(),
                        beforeEndOfHistory,
                    )
                }

                else -> {
                    messages(emptyList())
                }
            }
        }
    }

    private fun mediator(
        history: FakeHistory,
        pageSize: Int = 50,
    ) = ChatHistoryRemoteMediator(
        bufferId,
        db.bufferDao(),
        db.messageDao(),
        processor,
        history,
        pageSize,
        db.historyCursorDao(),
        db.historyGapDao(),
    )

    private fun emptyState() =
        PagingState<Int, MessageEntity>(
            pages = emptyList(),
            anchorPosition = null,
            config = PagingConfig(pageSize = 50, prefetchDistance = 25, enablePlaceholders = false),
            leadingPlaceholderCount = 0,
        )

    private suspend fun load(
        m: ChatHistoryRemoteMediator,
        type: LoadType,
    ) = m.load(type, emptyState())

    private suspend fun rowCount(): Int =
        db
            .messageDao()
            .pagingSource(bufferId)
            .load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, 1_000, false),
            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data.size }

    private suspend fun presentedRows(): List<MessageEntity> =
        db
            .messageDao()
            .pagingSource(messagePagingQuery(bufferId, MessageVisibilitySpec()))
            .load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, 1_000, false),
            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }

    @Test
    fun presenceOnlyLatestAdvancesCursorSoAppendFetchesOlderChat() =
        runTest {
            val joins = listOf(joined("oldest-join", 100), joined("newest-join", 200))
            processor.persistHistoryPage(
                networkId,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 50),
                messages(joins),
            )
            val cursor = db.historyCursorDao().byRoom(bufferId)!!
            assertEquals("oldest-join", cursor.oldestMsgid)
            assertEquals(100L, cursor.oldestServerTime)
            assertEquals(0, rowCount())

            val history = FakeHistory(before = ArrayDeque(listOf(listOf(chatMsg("older-chat", 50)))))
            val result = load(mediator(history), LoadType.APPEND)

            assertTrue(result is RemoteMediator.MediatorResult.Success)
            assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(listOf(ChatHistoryRequest.Subcommand.BEFORE), history.calls)
            assertEquals("msgid=oldest-join", history.requests.single().bound1)
            assertEquals(listOf("older-chat"), presentedRows().map { it.msgid })
            assertEquals("older-chat", db.historyCursorDao().byRoom(bufferId)!!.oldestMsgid)
        }

    @Test
    fun hiddenPresenceBudgetOffersRetryAndResumesFromTheAdvancedBoundary() =
        runTest {
            processor.process(networkId, joined("self-join", 10_000, nick = "me", isSelf = true))
            val pages = (1..8).map(::hiddenPage) + listOf(listOf(chatMsg("visible-after-retry", 9_599)))
            val history =
                FakeHistory(
                    before = ArrayDeque(pages),
                    referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
                )
            val mediator = mediator(history)

            val first = load(mediator, LoadType.APPEND)
            val eighthBoundary = history.requests.last().bound1
            val retried = load(mediator, LoadType.APPEND)

            assertTrue(first is RemoteMediator.MediatorResult.Error)
            assertTrue((first as RemoteMediator.MediatorResult.Error).throwable is HistoryLadderStalled)
            assertTrue(retried is RemoteMediator.MediatorResult.Success)
            assertEquals(9, history.calls.count { it == ChatHistoryRequest.Subcommand.BEFORE })
            assertTrue(history.requests.last().bound1 != eighthBoundary)
            assertTrue(presentedRows().any { it.msgid == "visible-after-retry" })
        }

    @Test
    fun appendOnEmptyBuffer_seedsLatest() =
        runTest {
            // Fresh/cleared store: Paging drives APPEND past the empty end boundary. The mediator must
            // LATEST-seed instead of returning end-of-pagination with nothing fetched (the reported bug).
            val history = FakeHistory(latest = listOf(chatMsg("a", 100), chatMsg("b", 200)))
            val result = load(mediator(history), LoadType.APPEND)

            assertTrue(result is RemoteMediator.MediatorResult.Success)
            assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), history.calls)
            assertEquals(2, rowCount())
        }

    @Test
    fun oversizedTerminalLatestKeepsTheDiscardedOlderIntervalPageable() =
        runTest {
            val history =
                FakeHistory(
                    latest = (1..1_000).map { chatMsg("m$it", it.toLong()) },
                    before = ArrayDeque(listOf(listOf(chatMsg("m950", 950)))),
                    latestEndOfHistory = true,
                )

            val first = load(mediator(history, pageSize = 50), LoadType.APPEND)
            val reopened = load(mediator(history, pageSize = 50), LoadType.APPEND)

            assertFalse((first as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertFalse((reopened as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(51, rowCount())
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
            assertFalse(db.historyCursorDao().byRoom(bufferId)!!.historyComplete)
            assertEquals(
                listOf(ChatHistoryRequest.Subcommand.LATEST, ChatHistoryRequest.Subcommand.BEFORE),
                history.calls,
            )
            assertEquals("msgid=m951", history.requests.last().bound1)
        }

    @Test
    fun malformedTimestampOnlyOverdeliveryIsRejectedInsteadOfInventingEpochBoundary() {
        val malformed =
            listOf(1L, 2L).map { time ->
                chatMsg("m$time", time).let { event ->
                    event.copy(
                        ctx =
                            event.ctx.copy(
                                msgid = null,
                                serverTime = 0,
                                serverTimeSource = ServerTimeSource.UNKNOWN,
                            ),
                    )
                }
            }
        val page = messages(malformed, endOfHistory = true)
        val request =
            ChatHistoryRequest(
                ChatHistoryRequest.Subcommand.LATEST,
                "#chan",
                limit = 1,
            )

        val failure = runCatching { page.boundedToRequest(request) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("usable retained boundary"))
    }

    @Test
    fun validTaggedTimestampOnlyOverdeliveryRetainsAdvertisedTime() {
        val events =
            listOf(100L, 200L).map { time ->
                chatMsg("m$time", time).let { event -> event.copy(ctx = event.ctx.copy(msgid = null)) }
            }
        val request =
            ChatHistoryRequest(
                ChatHistoryRequest.Subcommand.LATEST,
                "#chan",
                limit = 1,
            )

        val bounded = messages(events, endOfHistory = true).boundedToRequest(request)

        assertNull(bounded.oldest?.msgid)
        assertEquals(200L, bounded.oldest?.serverTime)
        assertFalse(bounded.endOfHistory)
    }

    @Test
    fun rawHistoryContextSurvivesPrimaryOverdeliveryBounding() {
        val rawContext =
            IrcEvent.Raw(
                IrcMessage(
                    tags =
                        mapOf(
                            "draft/chathistory-context" to "",
                            "time" to "1970-01-01T00:00:00.150Z",
                        ),
                    command = "TAGMSG",
                    params = listOf("#chan"),
                ),
            )
        val page =
            messages(
                listOf(chatMsg("m1", 100), rawContext, chatMsg("m2", 200)),
                endOfHistory = true,
            ).copy(primaryMessageCount = 2)

        val bounded =
            page.boundedToRequest(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 1),
            )

        assertEquals(1, bounded.primaryMessageCount)
        assertTrue(rawContext in bounded.events)
        assertEquals("m2", bounded.newest?.msgid)
    }

    @Test
    fun typedNetworkBatchSuppliesItsOpeningBoundaryDuringOverdeliveryBounding() {
        val networkBatch =
            IrcEvent.NetworkBatch(
                kind = IrcEvent.NetworkBatchKind.NETSPLIT,
                serverA = "a.example",
                serverB = "b.example",
                events = emptyList(),
                target = "#chan",
                historyMetadata =
                    HistoryEventMetadata(
                        isContext = false,
                        msgid = "split-event",
                        serverTime = 150,
                    ),
            )
        val page =
            messages(
                listOf(chatMsg("m1", 100), networkBatch, chatMsg("m2", 200)),
                endOfHistory = true,
            ).copy(primaryMessageCount = 3)

        val bounded =
            page.boundedToRequest(
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, "#chan", limit = 2),
            )

        assertEquals(2, bounded.primaryMessageCount)
        assertEquals(listOf(networkBatch, chatMsg("m2", 200)), bounded.events)
        assertEquals("split-event", bounded.oldest?.msgid)
        assertEquals(150L, bounded.oldest?.serverTime)
    }

    @Test
    fun appendOnEmptyBuffer_noServerHistory_endsThisLadderWithoutBrandingTheRoom() =
        runTest {
            // Empty local AND empty server: LATEST returns nothing, so this ladder has nowhere to go and
            // ends — but nothing durable is written. An unbounded LATEST names no boundary, so an empty
            // answer reports what the server could serve at that instant (a channel restored a moment
            // ago, a bouncer that has archived nothing for it yet), not where this room's history starts.
            // Completion is permanent and nothing clears it, so persisting it here left the room unable
            // to page for good once the backlog existed. The next entry simply asks again.
            val history = FakeHistory(latest = emptyList())
            val result = load(mediator(history), LoadType.APPEND)

            assertTrue(result is RemoteMediator.MediatorResult.Success)
            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(0, rowCount())
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
            assertFalse(db.historyCursorDao().byRoom(bufferId)!!.historyComplete)
        }

    @Test
    fun refreshOnEmptyBuffer_seedsLatest() =
        runTest {
            // Explicit REFRESH (e.g. swipe-to-refresh) on an empty buffer also LATEST-seeds.
            val history = FakeHistory(latest = listOf(chatMsg("a", 100)))
            load(mediator(history), LoadType.REFRESH)

            assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), history.calls)
            assertEquals(1, rowCount())
        }

    @Test
    fun appendWithLocalHistory_pagesBefore() =
        runTest {
            // Non-empty buffer: APPEND pages OLDER via BEFORE, never LATEST.
            processor.process(networkId, chatMsg("seed", 500))
            val history = FakeHistory(before = ArrayDeque(listOf(listOf(chatMsg("older", 100)))))
            val result = load(mediator(history), LoadType.APPEND)

            assertTrue(result is RemoteMediator.MediatorResult.Success)
            assertEquals(listOf(ChatHistoryRequest.Subcommand.BEFORE), history.calls)
            assertEquals("msgid=seed", history.requests.single().bound1)
            assertEquals(2, rowCount())
        }

    @Test
    fun recentAppendWithLocalHistory_pagesExactlyOneBeforePagePerLoad() =
        runTest {
            // Scroll-driven Recent paging: each APPEND fetches exactly one BEFORE page and the next
            // APPEND advances the cursor to the previous page's oldest boundary.
            processor.process(networkId, chatMsg("seed", 500))
            val history =
                FakeHistory(
                    before =
                        ArrayDeque(
                            listOf(
                                listOf(chatMsg("older-1", 100)),
                                listOf(chatMsg("older-2", 50)),
                            ),
                        ),
                )
            val mediator = mediator(history)

            val first = load(mediator, LoadType.APPEND)
            assertFalse((first as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(listOf(ChatHistoryRequest.Subcommand.BEFORE), history.calls)
            assertEquals("msgid=seed", history.requests.single().bound1)
            assertEquals(2, rowCount())

            val second = load(mediator, LoadType.APPEND)
            assertFalse((second as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(
                listOf(ChatHistoryRequest.Subcommand.BEFORE, ChatHistoryRequest.Subcommand.BEFORE),
                history.calls,
            )
            assertEquals("msgid=older-1", history.requests.last().bound1)
            assertEquals(3, rowCount())
        }

    @Test
    fun recentRefreshWithLocalRows_isNoop() =
        runTest {
            // Local rows already paint; REFRESH must not fetch and must leave APPEND to drive older.
            processor.process(networkId, chatMsg("seed", 500))
            val history = FakeHistory()

            val result = load(mediator(history), LoadType.REFRESH)

            assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertTrue(history.calls.isEmpty())
            assertEquals(1, rowCount())
        }

    @Test
    fun prependEndsPaginationEvenWithAnOpenGapAboveTheOldestRow() =
        runTest {
            // PREPEND is retired outright, and this is where that has to hold: the timeline is unbounded
            // and painted newest-first, so nothing above the presented rows is ever missing. Live events
            // supply newer messages, and an interior seam — including the recoverable reconnect gap
            // seeded here, which the old focused PREPEND would have fetched AFTER — belongs to
            // HistoryGapFillCoordinator. Not one wire request may leave this direction.
            processor.process(networkId, chatMsg("old", 100))
            processor.process(networkId, chatMsg("recent", 900))
            db.historyGapDao().insert(HistoryGapEntity(0, bufferId, "old", 100, "recent", 900))
            val history = FakeHistory()

            val result = load(mediator(history), LoadType.PREPEND)

            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertTrue(history.calls.isEmpty())
            // The gap is untouched: the direction ended without claiming or consuming it.
            assertTrue(
                db
                    .historyGapDao()
                    .forRoom(bufferId)
                    .single()
                    .recoverable,
            )
        }

    @Test
    fun recentAppendPagesTheGlobalCursorLadderAndIgnoresInteriorGaps() =
        runTest {
            // The inversion of the old `…BeforeTheRecentIslandInsteadOfTheGlobalOldestCursor` pin, which
            // now lives in HistoryGapFillCoordinatorTest as the coordinator's contract.
            //
            // Recent presents an UNBOUNDED timeline, so the local PagingSource only ever runs dry at the
            // true oldest retained row. The APPEND Paging asks for is therefore a request for backlog
            // below the bottom of the list, and aiming it at the interior gap answered a different
            // question: it re-fetched an interval that is already bracketed by rows the user can see,
            // and left the actual bottom of the timeline unable to page. So the boundary ladder is the
            // protocol cursor (then the oldest local row), never the gap's newer edge.
            processor.process(networkId, chatMsg("old", 100))
            processor.process(networkId, chatMsg("recent-boundary", 851))
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = "old",
                    olderServerTime = 100,
                    newerMsgid = "recent-boundary",
                    newerServerTime = 851,
                ),
            )
            // A stored cursor that is neither gap edge and neither local row, so the assertion below
            // distinguishes all three rungs of the ladder rather than only two.
            db.historyCursorDao().upsert(
                HistoryCursorEntity(
                    roomId = bufferId,
                    oldestMsgid = "cursor-oldest",
                    oldestServerTime = 60,
                ),
            )
            val history =
                FakeHistory(
                    before = ArrayDeque(listOf(listOf(chatMsg("older-page", 20), chatMsg("newer-page", 50)))),
                )

            val result = load(mediator(history), LoadType.APPEND)

            assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals("msgid=cursor-oldest", history.requests.single().bound1)
            // The interior gap is untouched: this page never went near it, so neither edge moved and it
            // is still recoverable and still a seam.
            val gap = db.historyGapDao().forRoom(bufferId).single()
            assertEquals(100L, gap.olderServerTime)
            assertEquals(851L, gap.newerServerTime)
            assertTrue(gap.recoverable)
            // ...and the page really did land below the whole timeline.
            assertEquals(4, rowCount())
        }

    @Test
    fun recentAppendIsClampedStrictlyBelowAnOpenGapEvenWhenTheCursorSitsOnItsNewerEdge() =
        runTest {
            // The reconnect shape, and the collision this clamp exists for. A LATEST catch-up page lands
            // a NEWER island, opens a gap between it and what the client already held, and unions its own
            // oldest row into the stored cursor — so the cursor ends up naming EXACTLY the gap's newer
            // edge. Unclamped, the mediator's bottom-of-timeline APPEND and the coordinator's fill then
            // issue the identical request for the identical interval, and one of the two is guaranteed to
            // insert nothing.
            processor.process(networkId, chatMsg("marker", 100))
            processor.process(networkId, chatMsg("catchup-oldest", 212))
            processor.process(networkId, chatMsg("catchup-newest", 260))
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = "marker",
                    olderServerTime = 100,
                    newerMsgid = "catchup-oldest",
                    newerServerTime = 212,
                ),
            )
            db.historyCursorDao().upsert(
                HistoryCursorEntity(
                    roomId = bufferId,
                    oldestMsgid = "catchup-oldest",
                    oldestServerTime = 212,
                ),
            )
            val history = FakeHistory(before = ArrayDeque(listOf(listOf(chatMsg("ancient", 20)))))

            load(mediator(history), LoadType.APPEND)

            // Strictly below the gap, not into it. BEFORE is strictly-older-than, so a request from the
            // gap's older edge cannot reach any row the gap covers, whichever demand source runs first.
            assertEquals("msgid=marker", history.requests.single().bound1)
            val gap = db.historyGapDao().forRoom(bufferId).single()
            assertEquals(100L, gap.olderServerTime)
            assertEquals(212L, gap.newerServerTime)
            assertTrue(gap.recoverable)
        }

    @Test
    fun recentAppendClampsToTheGapFloorWhenNoRetainedRowSitsBelowTheGap() =
        runTest {
            // The gap's older edge names a row this client no longer holds, so the oldest retained row is
            // INSIDE the gap's shadow and every other rung of the ladder points into the interval the gap
            // owns. The floor is then the only thing keeping the two demand sources apart.
            processor.process(networkId, chatMsg("catchup-oldest", 212))
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = "pruned",
                    olderServerTime = 100,
                    newerMsgid = "catchup-oldest",
                    newerServerTime = 212,
                ),
            )
            val history = FakeHistory(before = ArrayDeque(listOf(listOf(chatMsg("ancient", 20)))))

            load(mediator(history), LoadType.APPEND)

            assertEquals("msgid=pruned", history.requests.single().bound1)
        }

    @Test
    fun appendWithLocalHistory_emptyBefore_setsHistoryComplete() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val history = FakeHistory(before = ArrayDeque())
            val result = load(mediator(history), LoadType.APPEND)

            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun appendWithExplicitEnd_marksHistoryCompleteAfterIngestingPage() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val history =
                FakeHistory(
                    before = ArrayDeque(listOf(listOf(chatMsg("oldest", 100)))),
                    beforeEndOfHistory = true,
                )

            val result = load(mediator(history), LoadType.APPEND)

            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
            assertEquals(2, rowCount())
        }

    @Test
    fun shortBeforePageDoesNotComplete_andReturnedMsgidBecomesNextCursor() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val history =
                FakeHistory(
                    before =
                        ArrayDeque(
                            listOf(
                                listOf(chatMsg("older", 100)),
                                emptyList(),
                            ),
                        ),
                )

            val first = load(mediator(history), LoadType.APPEND)
            val second = load(mediator(history), LoadType.APPEND)

            assertFalse((first as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertTrue((second as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals("msgid=older", history.requests[1].bound1)
        }

    @Test
    fun msgidRejectionFallsBackToAdvertisedTimestampAndPersistsFallbackRequest() =
        runTest {
            processor.process(networkId, chatMsg("OpaqueCase", 500))
            val history =
                FakeHistory(
                    before = ArrayDeque(listOf(listOf(chatMsg("older", 100)))),
                    failureFor = { request ->
                        if (request.bound1 == "msgid=OpaqueCase") {
                            IrcCommandException("CHATHISTORY", "INVALID_MSGREFTYPE", "try timestamp")
                        } else {
                            null
                        }
                    },
                )

            val result = load(mediator(history), LoadType.APPEND)

            assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(
                listOf("msgid=OpaqueCase", "timestamp=1970-01-01T00:00:00.500Z"),
                history.requests.map { it.bound1 },
            )
            assertNull(db.historyCursorDao().byRoom(bufferId)?.oldestMsgid)
            assertEquals(100L, db.historyCursorDao().byRoom(bufferId)?.oldestServerTime)
        }

    @Test
    fun nonReferenceFailuresDoNotFallBackFromMsgidToTimestamp() =
        runTest {
            processor.process(networkId, chatMsg("OpaqueCase", 500))
            listOf(
                IrcTimeoutException("before"),
                IrcDisconnectedException("CHATHISTORY", "lost connection"),
                IOException("read failed"),
                IrcCommandException("CHATHISTORY", "MESSAGE_ERROR", "request rejected"),
            ).forEach { failure ->
                val history = FakeHistory(failure = failure)
                val result = load(mediator(history), LoadType.APPEND)

                assertTrue(result is RemoteMediator.MediatorResult.Error)
                assertEquals(listOf("msgid=OpaqueCase"), history.requests.map { it.bound1 })
            }
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun invalidMsgidDoesNotUseUnadvertisedTimestampFallback() =
        runTest {
            processor.process(networkId, chatMsg("OpaqueCase", 500))
            val history =
                FakeHistory(
                    failure =
                        IrcCommandException(
                            "CHATHISTORY",
                            "INVALID_MSGREFTYPE",
                            "timestamp was not advertised",
                        ),
                    referenceTypes = setOf(HistoryReferenceType.MSGID),
                )

            val result = load(mediator(history), LoadType.APPEND)

            assertTrue(result is RemoteMediator.MediatorResult.Error)
            assertEquals(listOf("msgid=OpaqueCase"), history.requests.map { it.bound1 })
        }

    @Test
    fun timestampOnlyAdvertisementNeverSendsMsgid() =
        runTest {
            processor.process(networkId, chatMsg("OpaqueCase", 500))
            val history =
                FakeHistory(
                    before = ArrayDeque(listOf(listOf(chatMsg("older", 100)))),
                    referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
                )

            val result = load(mediator(history), LoadType.APPEND)

            assertTrue(result is RemoteMediator.MediatorResult.Success)
            assertEquals(
                listOf("timestamp=1970-01-01T00:00:00.500Z"),
                history.requests.map { it.bound1 },
            )
        }

    @Test
    fun saturatedTimestampOnlyLatestSeedKeepsOlderBackfillAlive() =
        runTest {
            // A fresh buffer's first LATEST seed on a timestamp-only wire (soju advertises
            // MSGREFTYPES=timestamp) is saturated and msgid-less. That used to make EventProcessor write
            // a zero-width `recoverable = false` gap on the page's oldest row, and this test used to pin
            // the consequence: the seed reported terminal and a reopened mediator issued NO further
            // request, so a fresh buffer could never fetch a single older page. Both halves of that
            // expectation were wrong at the source — an interval whose two edges name the same row holds
            // no messages, and `recoverable = false` means "the server proved this empty", which a
            // saturated page never proves. With the write removed the seed leaves no gap behind and the
            // next APPEND pages older from the boundary the seed established.
            val history =
                FakeHistory(
                    latest = listOf(chatMsg("a", 100), chatMsg("b", 100)),
                    before = ArrayDeque(listOf(listOf(chatMsg("older1", 50), chatMsg("older2", 60)))),
                    referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
                )
            val mediator = mediator(history, pageSize = 2)

            val result = load(mediator, LoadType.APPEND)

            assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(2, rowCount())
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
            assertFalse(db.historyCursorDao().byRoom(bufferId)!!.historyComplete)
            assertTrue("a saturated seed records no durable gap", db.historyGapDao().forRoom(bufferId).isEmpty())

            val next = load(mediator, LoadType.APPEND)

            assertFalse((next as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(4, rowCount())
            assertEquals(
                listOf(null, "timestamp=1970-01-01T00:00:00.100Z"),
                history.requests.map { it.bound1 },
            )
        }

    @Test
    fun saturatedTimestampOnlyBeforeKeepsPagingFromTheRecededBoundary() =
        runTest {
            // Cursor-driven BEFORE, same wire regime, same removed write. Paging older past a saturated
            // msgid-less edge can still skip messages that share the edge's timestamp — the protocol
            // offers no selector for them — but that risk is per-fetch and is why
            // HistoryPageLoader.cannotSafelyPageBefore ends THIS fetch there and why history is never
            // marked complete. It is not a reason to stop the direction forever: the old behavior did not
            // recover those peers either (the gap it wrote was unrecoverable, so nothing ever fetched
            // it), it merely also discarded every older page. The mediator now continues while the
            // boundary keeps receding, and stops on the server's own terminal page.
            processor.process(networkId, chatMsg("seed", 500))
            val history =
                FakeHistory(
                    before =
                        ArrayDeque(
                            listOf(listOf(chatMsg("a", 100), chatMsg("b", 100))),
                        ),
                    referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
                )
            val mediator = mediator(history, pageSize = 2)

            val result = load(mediator, LoadType.APPEND)

            assertFalse((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(3, rowCount())
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
            assertTrue("a saturated page records no durable gap", db.historyGapDao().forRoom(bufferId).isEmpty())

            // The scripted wire is exhausted, so the next BEFORE is the server's empty (terminal) page:
            // the direction ends on proof, and only then is history complete.
            val reopened = load(mediator, LoadType.APPEND)
            assertTrue((reopened as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(
                listOf("timestamp=1970-01-01T00:00:00.500Z", "timestamp=1970-01-01T00:00:00.100Z"),
                history.requests.map { it.bound1 },
            )
            assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun freshTimestampOnlyBufferBackfillsPastTheSeedPage() =
        runTest {
            // The hosted-CI shape end to end, with no hand-written gap anywhere: an EMPTY buffer, a
            // saturated msgid-less LATEST seed, then a cursor-driven BEFORE ladder. Before the fix the
            // seed wrote an unrecoverable zero-width gap and this whole ladder measured exactly one
            // request; now every saturated page keeps the direction alive and only the server's empty
            // page ends it.
            val history =
                FakeHistory(
                    latest = listOf(chatMsg("a", 100), chatMsg("b", 110)),
                    before =
                        ArrayDeque(
                            listOf(
                                listOf(chatMsg("c", 80), chatMsg("d", 90)),
                                listOf(chatMsg("e", 60), chatMsg("f", 70)),
                                emptyList(),
                            ),
                        ),
                    referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
                )
            val mediator = mediator(history, pageSize = 2)

            val ends =
                (1..4).map {
                    (load(mediator, LoadType.APPEND) as RemoteMediator.MediatorResult.Success)
                        .endOfPaginationReached
                }

            assertEquals(listOf(false, false, false, true), ends)
            assertEquals(
                listOf(
                    null,
                    "timestamp=1970-01-01T00:00:00.100Z",
                    "timestamp=1970-01-01T00:00:00.080Z",
                    "timestamp=1970-01-01T00:00:00.060Z",
                ),
                history.requests.map { it.bound1 },
            )
            assertEquals(6, rowCount())
            assertTrue(db.historyGapDao().forRoom(bufferId).isEmpty())
            assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun unchangedBeforeBoundaryStallsRetryablyWithoutClaimingCompletion() =
        runTest {
            // The server answers with a row the store already holds: nothing lands, and the next request
            // would repeat verbatim, so this attempt stops. It is NOT the end of the direction — the room
            // still has a boundary it can ask from — and `endOfPaginationReached` is permanent for the
            // whole Pager, so reporting one here left the reader with a timeline that only started paging
            // again if they backed out of the room and came back. A retryable stall keeps the affordance.
            processor.process(networkId, chatMsg("seed", 500))
            val history =
                FakeHistory(
                    responseFor = { request ->
                        if (request.subcommand == ChatHistoryRequest.Subcommand.BEFORE) {
                            messages(listOf(chatMsg("seed", 500)))
                        } else {
                            null
                        }
                    },
                )

            val result = load(mediator(history), LoadType.APPEND)

            assertTrue(
                (result as RemoteMediator.MediatorResult.Error).throwable is HistoryLadderStalled,
            )
            assertEquals(1, rowCount())
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)

            // Asking again is what the footer's affordance does through `retry()`, and it reaches the wire.
            load(mediator(history), LoadType.APPEND)

            assertEquals(
                listOf(ChatHistoryRequest.Subcommand.BEFORE, ChatHistoryRequest.Subcommand.BEFORE),
                history.calls,
            )
        }

    @Test
    fun saturatedMsgidAppendLadderIsUnchanged() =
        runTest {
            // The msgid wire never trips the saturation guard, so this pins that the progress rule keeps
            // the existing one-page-per-load backfill ladder byte-for-byte.
            processor.process(networkId, chatMsg("seed", 500))
            val history =
                FakeHistory(
                    before =
                        ArrayDeque(
                            listOf(
                                listOf(chatMsg("a1", 400), chatMsg("a2", 450)),
                                listOf(chatMsg("b1", 300), chatMsg("b2", 350)),
                                emptyList(),
                            ),
                        ),
                )
            val mediator = mediator(history, pageSize = 2)

            val ends =
                (1..3).map {
                    (load(mediator, LoadType.APPEND) as RemoteMediator.MediatorResult.Success)
                        .endOfPaginationReached
                }

            assertEquals(listOf(false, false, true), ends)
            assertEquals(
                listOf("msgid=seed", "msgid=a1", "msgid=b1"),
                history.requests.map { it.bound1 },
            )
            assertEquals(5, rowCount())
            assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun anUnrecoverableGapDoesNotEndTheBottomOfTimelineAppend() =
        runTest {
            // THE SPLIT, and the defect the unbounded timeline had to fix, in one place.
            //
            // `recoverable = false` means the server proved THAT INTERVAL empty. It says nothing about
            // history older than the whole timeline. A gap-directed APPEND read it as "this direction is
            // finished", and Paging treats `endOfPaginationReached` as PERMANENT, so a single expired
            // seam anywhere in the room made the bottom of the list unpageable for the rest of the
            // session — with the far side of that seam now visible above it, which is how the user
            // reaches the bottom in the first place.
            //
            // The classification itself is not wrong and is not being weakened: it still ends the driver
            // that really is asking about that interval, which is HistoryGapFillCoordinator (see
            // HistoryGapFillCoordinatorTest.unrecoverableGapNeverTouchesTheWire).
            processor.process(networkId, chatMsg("marker", 10))
            processor.process(networkId, chatMsg("row212", 212))
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = bufferId,
                    olderMsgid = "marker",
                    olderServerTime = 10,
                    newerMsgid = "row212",
                    newerServerTime = 212,
                    recoverable = false,
                ),
            )

            // The mediator pages BELOW the oldest retained row, from the ladder that has nothing to do
            // with the seam, so an expired seam cannot terminate it.
            val bottom = FakeHistory(before = ArrayDeque(listOf(listOf(chatMsg("row5", 5)))))
            val recent = load(mediator(bottom), LoadType.APPEND)

            assertFalse((recent as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals("msgid=marker", bottom.requests.single().bound1)
            assertEquals(3, rowCount())
            // The seam is not consumed, repaired or re-classified by that page; it stays exactly what
            // the server said it was.
            val gap = db.historyGapDao().forRoom(bufferId).single()
            assertFalse(gap.recoverable)
            assertEquals(212L, gap.newerServerTime)
        }

    @Test
    fun positivePrimaryCountWithoutAdvertisedBoundaryIsAnIncompleteError() =
        runTest {
            processor.process(networkId, chatMsg("seed", 500))
            val malformed =
                ChatHistoryResponse.Messages(
                    events = listOf(chatMsg("unbounded", 100)),
                    oldest = null,
                    newest = null,
                    endOfHistory = false,
                    primaryMessageCount = 1,
                )
            val history = FakeHistory(responseFor = { malformed })

            val result = load(mediator(history), LoadType.APPEND)

            assertTrue(result is RemoteMediator.MediatorResult.Error)
            assertEquals(listOf(ChatHistoryRequest.Subcommand.BEFORE), history.calls)
            assertEquals(1, rowCount())
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun contextOnlyLatestPagesFromTheRowItLandedAndThenStopsWithoutBrandingTheRoom() =
        runTest {
            // A LATEST batch that delivered rows but no PRIMARY message advertises no boundary and was
            // never tagged terminal, so it proves nothing about where history starts. What it does leave
            // behind is a real retained row, and that row is a boundary the next load can page BEFORE —
            // so this seed keeps the ladder open rather than closing the room permanently.
            val contextOnly =
                ChatHistoryResponse.Messages(
                    events = listOf(chatMsg("context", 100)),
                    oldest = null,
                    newest = null,
                    endOfHistory = false,
                    primaryMessageCount = 0,
                )
            val history = FakeHistory(responseFor = { contextOnly })

            val seed = load(mediator(history), LoadType.APPEND)

            assertFalse((seed as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), history.calls)
            assertEquals(1, rowCount())
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)

            // The ladder now has a boundary, and the server answering that same context-only page to a
            // BEFORE is a directional "nothing older than this row" — which IS proof of the start.
            val next = load(mediator(history), LoadType.APPEND)

            assertTrue((next as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(
                listOf(ChatHistoryRequest.Subcommand.LATEST, ChatHistoryRequest.Subcommand.BEFORE),
                history.calls,
            )
            assertEquals(1, rowCount())
            assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun latestExplicitEndPersistsCompletionWithNonEmptyPage() =
        runTest {
            val history =
                FakeHistory(
                    latest = listOf(chatMsg("only", 100)),
                    latestEndOfHistory = true,
                )

            val result = load(mediator(history), LoadType.APPEND)

            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
            assertEquals("only", db.historyCursorDao().byRoom(bufferId)?.oldestMsgid)
        }

    @Test
    fun cancellationIsRethrownInsteadOfBecomingMediatorError() =
        runTest {
            val cancellation = CancellationException("cancel history")
            val history = FakeHistory(failure = cancellation)

            var observed: Throwable? = null
            try {
                load(mediator(history), LoadType.APPEND)
            } catch (error: Throwable) {
                observed = error
            }

            assertTrue(observed === cancellation)
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    @Test
    fun serverBufferNeverIssuesHistoryRequests() =
        runTest {
            // The mediator is attached unconditionally, but a SERVER console has no CHATHISTORY target:
            // every LoadType must end pagination locally without touching the wire.
            db.bufferDao().insert(
                BufferEntity(networkId = networkId, name = "server", displayName = "server", type = BufferType.SERVER),
            )
            val serverBufferId = db.bufferDao().byName(networkId, "server")!!.id
            val history = FakeHistory(latest = listOf(chatMsg("a", 100)))
            val mediator =
                ChatHistoryRemoteMediator(
                    serverBufferId,
                    db.bufferDao(),
                    db.messageDao(),
                    processor,
                    history,
                    50,
                    db.historyCursorDao(),
                    db.historyGapDao(),
                )

            listOf(LoadType.REFRESH, LoadType.PREPEND, LoadType.APPEND).forEach { type ->
                val result = load(mediator, type)
                assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            }

            assertTrue(history.calls.isEmpty())
        }

    /** soju answers CHATHISTORY for its console, so it is the one SERVER room that backfills. */
    @Test
    fun bouncerConsoleBacksFillLikeAnyOtherTarget() =
        runTest {
            val root = db.networkDao().byId(networkId)!!
            db.networkDao().update(root.copy(role = NetworkRole.BOUNCER_ROOT))
            db.bufferDao().insert(
                BufferEntity(
                    networkId = networkId,
                    name = "bouncerserv",
                    displayName = "BouncerServ",
                    type = BufferType.SERVER,
                ),
            )
            val consoleId = db.bufferDao().byName(networkId, "bouncerserv")!!.id
            val history = FakeHistory(latest = listOf(chatMsg("a", 100)))
            val mediator =
                ChatHistoryRemoteMediator(
                    consoleId,
                    db.bufferDao(),
                    db.messageDao(),
                    processor,
                    history,
                    50,
                    db.historyCursorDao(),
                    db.historyGapDao(),
                )

            load(mediator, LoadType.APPEND)

            assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), history.calls)
            assertEquals("bouncerserv", history.requests.single().target)
        }

    @Test
    fun noCap_paginatesLocalOnly() =
        runTest {
            val history = FakeHistory(hasChatHistory = false, latest = listOf(chatMsg("a", 100)))
            val result = load(mediator(history), LoadType.APPEND)

            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertTrue(history.calls.isEmpty())
            assertEquals(0, rowCount())
        }

    @Test
    fun offlineHistoryIsRetryableAndDoesNotMarkCompletion() =
        runTest {
            val history = FakeHistory(offline = true)

            val result = load(mediator(history), LoadType.APPEND)

            assertTrue(result is RemoteMediator.MediatorResult.Error)
            assertTrue(history.calls.isEmpty())
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
        }

    // =============================================================================================
    // CHARACTERIZATION PINS — these assert TODAY'S behavior, not desired behavior.
    //
    // They exist so a behavior-preserving refactor of the paging decision logic cannot drift
    // silently. `pinnedCurrentBehavior_saturatedTimestampOnlyRefreshIsTerminalForBothDirections`
    // in particular pins a LATENT DEFECT: do not "fix" it by editing the test.
    // =============================================================================================

    @Test
    fun pinnedCurrentBehavior_saturatedTimestampOnlyRefreshIsTerminalForBothDirections() =
        runTest {
            // PINNED CURRENT BEHAVIOR — LATENT DEFECT. Do not treat this as the desired contract.
            //
            // refresh() forwards the loader's endOfDirection straight through toMediatorResult(). On a
            // timestamp-only wire (soju advertises MSGREFTYPES=timestamp) a SATURATED LATEST page trips
            // HistoryPageLoader.cannotSafelyPageBefore, which means "not safe from THIS cursor", never
            // "no older history" — and the loader reports it as endOfDirection = true. On REFRESH,
            // endOfPaginationReached is terminal for BOTH directions permanently.
            //
            // The identical page fetched through append()'s empty-buffer LATEST seed goes through the
            // progress rule instead and correctly keeps paging — see
            // `saturatedTimestampOnlyLatestSeedKeepsOlderBackfillAlive`, same fixture, same page,
            // opposite outcome. This is the same shape as the APPEND bug already fixed; it stays masked
            // in production only because SKIP_INITIAL_REFRESH means remote REFRESH fires solely on an
            // explicit retry/refresh of an empty buffer.
            //
            // The assertions below also record that nothing PROVED history ended: no completion flag was
            // written, so the terminal verdict rests entirely on the per-fetch cursor guard.
            val history =
                FakeHistory(
                    latest = listOf(chatMsg("a", 100), chatMsg("b", 100)),
                    referenceTypes = setOf(HistoryReferenceType.TIMESTAMP),
                )

            val result = load(mediator(history, pageSize = 2), LoadType.REFRESH)

            assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
            assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), history.calls)
            assertEquals(2, rowCount())
            assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
            assertFalse(db.historyCursorDao().byRoom(bufferId)!!.historyComplete)
        }

    // The two equal-time gap-edge selection pins moved verbatim to HistoryGapFillCoordinatorTest:
    // this mediator never selects a gap in either direction any more, and gap selection is the
    // coordinator's.
}
