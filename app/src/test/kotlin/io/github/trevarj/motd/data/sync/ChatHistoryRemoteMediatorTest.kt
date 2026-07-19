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
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.client.IrcTimeoutException
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        networkId = db.networkDao().insert(
            NetworkEntity(name = "libera", role = NetworkRole.DIRECT, host = "h", port = 6697, nick = "me", username = "me", realname = "Me"),
        )
        processor.onRegistered(networkId, "me", emptyMap())
        db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL))
        bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
    }

    @After fun tearDown() { db.close() }

    private fun chatMsg(msgid: String, time: Long) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "b", null),
        kind = IrcEvent.ChatKind.PRIVMSG, source = Prefix("alice"), target = "#chan", text = msgid,
        isSelf = false, replyToMsgid = null,
    )

    private fun messages(
        events: List<IrcEvent>,
        endOfHistory: Boolean = false,
    ): ChatHistoryResponse.Messages {
        val references = events.mapNotNull { event ->
            val ctx = when (event) {
                is IrcEvent.ChatMessage -> event.ctx
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
        val referenceTypes: Set<HistoryReferenceType> = setOf(
            HistoryReferenceType.TIMESTAMP,
            HistoryReferenceType.MSGID,
        ),
    ) : ChatHistoryRemoteMediator.HistorySource {
        val calls = mutableListOf<ChatHistoryRequest.Subcommand>()
        val requests = mutableListOf<ChatHistoryRequest>()
        override suspend fun availability(): HistoryAvailability = if (offline) {
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
                ChatHistoryRequest.Subcommand.LATEST -> messages(latest, latestEndOfHistory)
                ChatHistoryRequest.Subcommand.BEFORE -> messages(
                    before.removeFirstOrNull() ?: emptyList(),
                    beforeEndOfHistory,
                )
                else -> messages(emptyList())
            }
        }
    }

    private fun mediator(history: FakeHistory, pageSize: Int = 50) =
        ChatHistoryRemoteMediator(
            bufferId,
            db.bufferDao(),
            db.messageDao(),
            processor,
            history,
            pageSize,
            db.historyCursorDao(),
        )

    private fun emptyState() = PagingState<Int, MessageEntity>(
        pages = emptyList(),
        anchorPosition = null,
        config = PagingConfig(pageSize = 50, prefetchDistance = 25, enablePlaceholders = false),
        leadingPlaceholderCount = 0,
    )

    private suspend fun load(m: ChatHistoryRemoteMediator, type: LoadType) = m.load(type, emptyState())

    private suspend fun rowCount(): Int = db.messageDao().pagingSource(bufferId).load(
        androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false),
    ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data.size }

    @Test
    fun appendOnEmptyBuffer_seedsLatest() = runTest {
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
    fun appendOnEmptyBuffer_noServerHistory_endsPagination() = runTest {
        // Empty local AND empty server: LATEST returns nothing → end of pagination, no rows.
        val history = FakeHistory(latest = emptyList())
        val result = load(mediator(history), LoadType.APPEND)

        assertTrue(result is RemoteMediator.MediatorResult.Success)
        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(0, rowCount())
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        assertTrue(db.historyCursorDao().byRoom(bufferId)!!.historyComplete)
    }

    @Test
    fun refreshOnEmptyBuffer_seedsLatest() = runTest {
        // Explicit REFRESH (e.g. swipe-to-refresh) on an empty buffer also LATEST-seeds.
        val history = FakeHistory(latest = listOf(chatMsg("a", 100)))
        load(mediator(history), LoadType.REFRESH)

        assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), history.calls)
        assertEquals(1, rowCount())
    }

    @Test
    fun appendWithLocalHistory_pagesBefore() = runTest {
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
    fun appendWithLocalHistory_emptyBefore_setsHistoryComplete() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(before = ArrayDeque())
        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun appendWithExplicitEnd_marksHistoryCompleteAfterIngestingPage() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(
            before = ArrayDeque(listOf(listOf(chatMsg("oldest", 100)))),
            beforeEndOfHistory = true,
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        assertEquals(2, rowCount())
    }

    @Test
    fun shortBeforePageDoesNotComplete_andReturnedMsgidBecomesNextCursor() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val history = FakeHistory(
            before = ArrayDeque(
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
    fun msgidRejectionFallsBackToAdvertisedTimestampAndPersistsFallbackRequest() = runTest {
        processor.process(networkId, chatMsg("OpaqueCase", 500))
        val history = FakeHistory(
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
        assertEquals("older", db.historyCursorDao().byRoom(bufferId)?.oldestMsgid)
    }

    @Test
    fun nonReferenceFailuresDoNotFallBackFromMsgidToTimestamp() = runTest {
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
    fun invalidMsgidDoesNotUseUnadvertisedTimestampFallback() = runTest {
        processor.process(networkId, chatMsg("OpaqueCase", 500))
        val history = FakeHistory(
            failure = IrcCommandException(
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
    fun timestampOnlyAdvertisementNeverSendsMsgid() = runTest {
        processor.process(networkId, chatMsg("OpaqueCase", 500))
        val history = FakeHistory(
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
    fun positivePrimaryCountWithoutAdvertisedBoundaryIsAnIncompleteError() = runTest {
        processor.process(networkId, chatMsg("seed", 500))
        val malformed = ChatHistoryResponse.Messages(
            events = listOf(chatMsg("unbounded", 100)),
            oldest = null,
            newest = null,
            endOfHistory = true,
            primaryMessageCount = 1,
        )
        val history = FakeHistory(responseFor = { malformed })

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertEquals(1, rowCount())
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun nonemptyContextOnlyLatestCompletesFromZeroPrimaryCount() = runTest {
        val contextOnly = ChatHistoryResponse.Messages(
            events = listOf(chatMsg("context", 100)),
            oldest = null,
            newest = null,
            endOfHistory = false,
            primaryMessageCount = 0,
        )
        val history = FakeHistory(responseFor = { contextOnly })

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertEquals(1, rowCount())
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }

    @Test
    fun latestExplicitEndPersistsCompletionWithNonEmptyPage() = runTest {
        val history = FakeHistory(
            latest = listOf(chatMsg("only", 100)),
            latestEndOfHistory = true,
        )

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(db.bufferDao().observeById(bufferId)!!.historyComplete)
        assertEquals("only", db.historyCursorDao().byRoom(bufferId)?.oldestMsgid)
    }

    @Test
    fun cancellationIsRethrownInsteadOfBecomingMediatorError() = runTest {
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
    fun noCap_paginatesLocalOnly() = runTest {
        val history = FakeHistory(hasChatHistory = false, latest = listOf(chatMsg("a", 100)))
        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(history.calls.isEmpty())
        assertEquals(0, rowCount())
    }

    @Test
    fun offlineHistoryIsRetryableAndDoesNotMarkCompletion() = runTest {
        val history = FakeHistory(offline = true)

        val result = load(mediator(history), LoadType.APPEND)

        assertTrue(result is RemoteMediator.MediatorResult.Error)
        assertTrue(history.calls.isEmpty())
        assertFalse(db.bufferDao().observeById(bufferId)!!.historyComplete)
    }
}
