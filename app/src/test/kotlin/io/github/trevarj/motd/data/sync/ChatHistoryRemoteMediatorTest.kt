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
import io.github.trevarj.motd.irc.client.ChatHistoryResult
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.test.runTest
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

    /** Scripts LATEST + BEFORE responses and records the subcommands issued. */
    private class FakeHistory(
        val hasChatHistory: Boolean = true,
        val latest: List<IrcEvent> = emptyList(),
        val before: ArrayDeque<List<IrcEvent>> = ArrayDeque(),
    ) : ChatHistoryRemoteMediator.HistorySource {
        val calls = mutableListOf<ChatHistoryRequest.Subcommand>()
        val requests = mutableListOf<ChatHistoryRequest>()
        override suspend fun hasCap(cap: String) = hasChatHistory
        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResult {
            calls += req.subcommand
            requests += req
            return when (req.subcommand) {
                ChatHistoryRequest.Subcommand.LATEST -> ChatHistoryResult(latest, emptyList())
                ChatHistoryRequest.Subcommand.BEFORE -> ChatHistoryResult(before.removeFirstOrNull() ?: emptyList(), emptyList())
                else -> ChatHistoryResult(emptyList(), emptyList())
            }
        }
    }

    private fun mediator(history: FakeHistory, pageSize: Int = 50) =
        ChatHistoryRemoteMediator(bufferId, db.bufferDao(), db.messageDao(), processor, history, pageSize)

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
        assertEquals("timestamp=1970-01-01T00:00:00.500Z", history.requests.single().bound1)
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
    fun noCap_paginatesLocalOnly() = runTest {
        val history = FakeHistory(hasChatHistory = false, latest = listOf(chatMsg("a", 100)))
        val result = load(mediator(history), LoadType.APPEND)

        assertTrue((result as RemoteMediator.MediatorResult.Success).endOfPaginationReached)
        assertTrue(history.calls.isEmpty())
        assertEquals(0, rowCount())
    }
}
