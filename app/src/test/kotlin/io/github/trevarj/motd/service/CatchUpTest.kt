package io.github.trevarj.motd.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResult
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatchUpTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private var networkId = 0L

    @Before fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        networkId = db.networkDao().insert(
            NetworkEntity(name = "libera", role = NetworkRole.DIRECT, host = "h", port = 6697, nick = "me", username = "me", realname = "Me"),
        )
        processor.onRegistered(networkId, "me", emptyMap())
    }

    @After fun tearDown() { db.close() }

    private fun chatMsg(msgid: String, target: String, time: Long) = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "b", null),
        kind = IrcEvent.ChatKind.PRIVMSG, source = Prefix("alice"), target = target, text = msgid,
        isSelf = false, replyToMsgid = null,
    )

    /** Scripts LATEST (empty-buffer seed) + AFTER pages per target and records TARGETS + MARKREAD. */
    private class FakeHistory(
        val hasChatHistory: Boolean = true,
        val targets: List<Pair<String, Long>> = emptyList(),
        val afterPages: MutableMap<String, ArrayDeque<List<IrcEvent>>> = mutableMapOf(),
        val latestPages: MutableMap<String, List<IrcEvent>> = mutableMapOf(),
    ) : CatchUp.HistorySource {
        val markReadFetched = mutableListOf<String>()
        val afterCalls = mutableListOf<String>()
        val latestCalls = mutableListOf<String>()
        override fun hasCap(cap: String) = hasChatHistory
        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResult = when (req.subcommand) {
            ChatHistoryRequest.Subcommand.TARGETS -> ChatHistoryResult(emptyList(), targets)
            ChatHistoryRequest.Subcommand.LATEST -> {
                latestCalls += req.target
                ChatHistoryResult(latestPages[req.target] ?: emptyList(), emptyList())
            }
            ChatHistoryRequest.Subcommand.AFTER -> {
                afterCalls += req.target
                val page = afterPages[req.target]?.removeFirstOrNull() ?: emptyList()
                ChatHistoryResult(page, emptyList())
            }
            else -> ChatHistoryResult(emptyList(), emptyList())
        }
        override suspend fun fetchReadMarker(target: String) { markReadFetched += target }
    }

    @Test
    fun afterLoop_paginatesUntilShortPage_andInsertsRows() = runTest {
        db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL))
        // Seed one existing local message so pageAfter has a starting boundary.
        processor.process(networkId, chatMsg("seed", "#chan", 100))

        val history = FakeHistory(
            targets = emptyList(),
            afterPages = mutableMapOf(
                "#chan" to ArrayDeque(listOf(
                    // full page (pageLimit=2 for the test) then a short page ends it.
                    listOf(chatMsg("a", "#chan", 200), chatMsg("b", "#chan", 300)),
                    listOf(chatMsg("c", "#chan", 400)),
                )),
            ),
        )
        val catchUp = CatchUp(db.bufferDao(), db.messageDao(), processor, history, normalize = { it.lowercase() }, pageLimit = 2)
        catchUp.run(networkId, listOf(0L to "#chan").let { listOf(db.bufferDao().byName(networkId, "#chan")!!.id to "#chan") })

        val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        val rows = db.messageDao().pagingSource(bufferId).load(
            androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false),
        ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }
        // seed + a + b + c = 4
        assertEquals(4, rows.size)
        // MARKREAD fetched for the open buffer.
        assertEquals(listOf("#chan"), history.markReadFetched)
    }

    @Test
    fun emptyBuffer_seedsWithLatest_thenPagesAfter() = runTest {
        // Fresh connect / cleared DB: the buffer exists but has no local messages, so `since` is
        // null and there is no AFTER lower bound. Catch-up must LATEST-seed the newest page instead
        // of AFTER-epoch (which walks forward from the oldest retained message).
        db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL))

        val history = FakeHistory(
            latestPages = mutableMapOf(
                // full page (pageLimit=2) → seed newest, then AFTER paginates newer.
                "#chan" to listOf(chatMsg("a", "#chan", 100), chatMsg("b", "#chan", 200)),
            ),
            afterPages = mutableMapOf(
                "#chan" to ArrayDeque(listOf(listOf(chatMsg("c", "#chan", 300)))),
            ),
        )
        val catchUp = CatchUp(db.bufferDao(), db.messageDao(), processor, history, normalize = { it.lowercase() }, pageLimit = 2)
        catchUp.run(networkId, listOf(db.bufferDao().byName(networkId, "#chan")!!.id to "#chan"))

        val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        val rows = db.messageDao().pagingSource(bufferId).load(
            androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false),
        ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }
        // LATEST seeded a + b, AFTER added c.
        assertEquals(3, rows.size)
        assertEquals(listOf("#chan"), history.latestCalls)
        assertEquals(listOf("#chan"), history.afterCalls)
        assertEquals(listOf("#chan"), history.markReadFetched)
    }

    @Test
    fun emptyBuffer_shortLatestPage_skipsAfter() = runTest {
        // A short LATEST page means the bouncer returned everything it has → no newer messages to
        // page AFTER, so we must not issue a redundant AFTER for the just-seeded newest boundary.
        db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL))

        val history = FakeHistory(
            latestPages = mutableMapOf("#chan" to listOf(chatMsg("a", "#chan", 100))),
        )
        val catchUp = CatchUp(db.bufferDao(), db.messageDao(), processor, history, normalize = { it.lowercase() }, pageLimit = 2)
        catchUp.run(networkId, listOf(db.bufferDao().byName(networkId, "#chan")!!.id to "#chan"))

        val bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        val rows = db.messageDao().pagingSource(bufferId).load(
            androidx.paging.PagingSource.LoadParams.Refresh(null, 100, false),
        ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data }
        assertEquals(1, rows.size)
        assertEquals(listOf("#chan"), history.latestCalls)
        assertTrue(history.afterCalls.isEmpty())
    }

    @Test
    fun targets_createMissingBuffers() = runTest {
        // Existing buffer with a message so `since` is non-null → TARGETS is issued.
        db.bufferDao().insert(BufferEntity(networkId = networkId, name = "#known", displayName = "#known", type = BufferType.CHANNEL))
        processor.process(networkId, chatMsg("seed", "#known", 100))

        val history = FakeHistory(
            targets = listOf("#new" to 500L),
            // #new has no local buffer/messages yet, so catch-up LATEST-seeds it (empty store path).
            latestPages = mutableMapOf("#new" to listOf(chatMsg("n1", "#new", 500))),
        )
        val catchUp = CatchUp(db.bufferDao(), db.messageDao(), processor, history, normalize = { it.lowercase() }, pageLimit = 100)
        catchUp.run(networkId, listOf(db.bufferDao().byName(networkId, "#known")!!.id to "#known"))

        // TARGETS-discovered #new got its history fetched (LATEST-seeded), which auto-created the buffer.
        val created = db.bufferDao().byName(networkId, "#new")
        assertTrue(created != null)
        assertTrue(history.latestCalls.contains("#new"))
    }

    @Test
    fun noChatHistoryCap_skipsEntirely() = runTest {
        val history = FakeHistory(hasChatHistory = false)
        val catchUp = CatchUp(db.bufferDao(), db.messageDao(), processor, history, normalize = { it.lowercase() })
        catchUp.run(networkId, emptyList())
        assertTrue(history.afterCalls.isEmpty())
        assertTrue(history.markReadFetched.isEmpty())
    }
}
