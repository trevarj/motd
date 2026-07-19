package io.github.trevarj.motd.data.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BufferDaoTest {
    private lateinit var db: MotdDatabase
    private var networkId: Long = 0

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        networkId = db.networkDao().insert(network())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun chatList_unreadAndMentionMath_relativeToReadMarker() = runTest {
        val bufDao = db.bufferDao()
        val msgDao = db.messageDao()
        // readMarker at t=100: messages after count as unread.
        val bid = bufDao.insert(buffer(networkId, "#chan", readMarkerTime = 100))

        msgDao.insertAll(
            listOf(
                // before marker → read.
                message(bid, "old", serverTime = 50, dedupKey = "a"),
                // after marker, not self, chat kind → unread.
                message(bid, "new1", serverTime = 150, dedupKey = "b"),
                // after marker + mention → unread AND mention.
                message(bid, "hey me", serverTime = 160, dedupKey = "c", hasMention = true),
                // after marker but self → NOT unread.
                message(bid, "my own", sender = "me", serverTime = 170, dedupKey = "d", isSelf = true),
                // after marker but system kind → NOT unread.
                message(bid, "joined", serverTime = 180, dedupKey = "e", kind = MessageKind.JOIN),
            )
        )

        val row = bufDao.observeChatList().first().single()
        assertEquals(2, row.unreadCount)
        assertEquals(1, row.mentionCount)
        // JOIN is timeline-only; the newest eligible message supplies every preview field.
        assertEquals("my own", row.lastMessageText)
        assertEquals("me", row.lastMessageSender)
        assertEquals(170L, row.lastMessageTime)
    }

    @Test
    fun `same millisecond local anchor uses event id ordering`() = runTest {
        val room = db.bufferDao().insert(buffer(networkId, "#same-ms"))
        val ids = db.messageDao().insertAll(
            listOf(
                message(room, "first", serverTime = 100, dedupKey = "same-1"),
                message(room, "second", serverTime = 100, dedupKey = "same-2"),
            ),
        )

        db.bufferDao().advanceLocalReadAnchor(room, 100, ids.first())

        val row = db.bufferDao().observeChatList().first().single()
        assertEquals(1, row.unreadCount)
        assertEquals(ids.first(), db.bufferDao().observeById(room)?.localReadAnchorEventId)
    }

    @Test
    fun `deleting anchored event falls back to prior exact timeline row`() = runTest {
        val room = db.bufferDao().insert(buffer(networkId, "#fallback"))
        val ids = db.messageDao().insertAll(
            listOf(
                message(room, "first", serverTime = 100, dedupKey = "fallback-1"),
                message(room, "second", serverTime = 100, dedupKey = "fallback-2"),
            ),
        )
        db.bufferDao().advanceLocalReadAnchor(room, 100, ids.last())

        db.messageDao().deleteWithAnchorFallback(ids.last())

        val buffer = db.bufferDao().observeById(room)
        assertEquals(100L, buffer?.localReadAnchorTime)
        assertEquals(ids.first(), buffer?.localReadAnchorEventId)
    }

    @Test
    fun chatList_joinPartQuitNeverReplacePreviewOrActivity() = runTest {
        val bufDao = db.bufferDao()
        val msgDao = db.messageDao()
        val kinds = listOf(
            MessageKind.JOIN,
            MessageKind.PART,
            MessageKind.QUIT,
            MessageKind.NETSPLIT,
            MessageKind.NETJOIN,
        )

        kinds.forEachIndexed { index, kind ->
            val bufferId = bufDao.insert(buffer(networkId, "#ignored-$index"))
            msgDao.insertAll(
                listOf(
                    message(
                        bufferId,
                        "meaningful-$index",
                        sender = "sender-$index",
                        serverTime = 100L + index,
                        dedupKey = "meaningful-$index",
                    ),
                    message(
                        bufferId,
                        "ignored-$kind",
                        sender = "system-$index",
                        serverTime = 1_000L + index,
                        dedupKey = "ignored-$index",
                        kind = kind,
                    ),
                ),
            )
        }

        val rows = bufDao.observeChatList().first().associateBy(ChatListRow::displayName)
        kinds.indices.forEach { index ->
            val row = checkNotNull(rows["#ignored-$index"])
            assertEquals("meaningful-$index", row.lastMessageText)
            assertEquals("sender-$index", row.lastMessageSender)
            assertEquals(100L + index, row.lastMessageTime)
        }
    }

    @Test
    fun chatList_joinPartQuitOnlyBufferHasBlankPreviewAndNoActivity() = runTest {
        val bufDao = db.bufferDao()
        val msgDao = db.messageDao()
        val bufferId = bufDao.insert(buffer(networkId, "#only-ignored"))
        msgDao.insertAll(
            listOf(
                message(bufferId, "join", serverTime = 100, dedupKey = "join", kind = MessageKind.JOIN),
                message(bufferId, "part", serverTime = 200, dedupKey = "part", kind = MessageKind.PART),
                message(bufferId, "quit", serverTime = 300, dedupKey = "quit", kind = MessageKind.QUIT),
                message(bufferId, "split", serverTime = 400, dedupKey = "split", kind = MessageKind.NETSPLIT),
                message(bufferId, "join", serverTime = 500, dedupKey = "netjoin", kind = MessageKind.NETJOIN),
            ),
        )

        val row = bufDao.observeChatList().first().single()
        assertNull(row.lastMessageText)
        assertNull(row.lastMessageSender)
        assertNull(row.lastMessageTime)
    }

    @Test
    fun chatList_retainsOtherSystemKindsAndUsesOneSelectedRow() = runTest {
        val bufDao = db.bufferDao()
        val msgDao = db.messageDao()
        val kinds = listOf(
            MessageKind.KICK,
            MessageKind.NICK,
            MessageKind.MODE,
            MessageKind.TOPIC,
            MessageKind.ERROR,
        )

        kinds.forEachIndexed { index, kind ->
            val bufferId = bufDao.insert(buffer(networkId, "#retained-$index"))
            msgDao.insertAll(
                listOf(
                    message(
                        bufferId,
                        "older-$index",
                        sender = "older-sender-$index",
                        serverTime = 100,
                        dedupKey = "older-$index",
                    ),
                    message(
                        bufferId,
                        "selected-$kind",
                        sender = "selected-sender-$index",
                        serverTime = 500,
                        dedupKey = "selected-$index",
                        kind = kind,
                    ),
                ),
            )
        }

        val rows = bufDao.observeChatList().first().associateBy(ChatListRow::displayName)
        kinds.forEachIndexed { index, kind ->
            val row = checkNotNull(rows["#retained-$index"])
            assertEquals("selected-$kind", row.lastMessageText)
            assertEquals("selected-sender-$index", row.lastMessageSender)
            assertEquals(500L, row.lastMessageTime)
        }
    }

    @Test
    fun chatList_excludesServerBuffers_andSortsPinnedFirstThenActivity() = runTest {
        val bufDao = db.bufferDao()
        val msgDao = db.messageDao()

        val server = bufDao.insert(buffer(networkId, "server", type = BufferType.SERVER))
        val quiet = bufDao.insert(buffer(networkId, "#quiet"))
        val busy = bufDao.insert(buffer(networkId, "#busy"))
        val pinned = bufDao.insert(buffer(networkId, "#pinned", pinned = true))

        msgDao.insertAll(listOf(message(server, "motd", serverTime = 999, dedupKey = "s")))
        msgDao.insertAll(listOf(message(quiet, "hi", serverTime = 10, dedupKey = "q")))
        msgDao.insertAll(listOf(message(busy, "yo", serverTime = 500, dedupKey = "bz")))
        // pinned has no messages → still first via pinned sort.

        val rows = bufDao.observeChatList().first()
        // SERVER buffer excluded.
        assertNull(rows.firstOrNull { it.bufferId == server })
        assertEquals(3, rows.size)
        // pinned first, then by activity DESC (busy=500 > quiet=10).
        assertEquals(pinned, rows[0].bufferId)
        assertEquals(busy, rows[1].bufferId)
        assertEquals(quiet, rows[2].bufferId)
    }

    @Test
    fun advanceReadMarker_isMaxOnly() = runTest {
        val bufDao = db.bufferDao()
        val bid = bufDao.insert(buffer(networkId, "#chan"))

        bufDao.advanceReadMarker(bid, 200)
        assertEquals(200L, bufDao.observeById(bid)!!.readMarkerTime)

        // lower value ignored.
        bufDao.advanceReadMarker(bid, 100)
        assertEquals(200L, bufDao.observeById(bid)!!.readMarkerTime)

        // higher value advances.
        bufDao.advanceReadMarker(bid, 300)
        assertEquals(300L, bufDao.observeById(bid)!!.readMarkerTime)
    }

    @Test
    fun unmute_clears_local_backlog_without_advancing_remote_read_marker() = runTest {
        val bufDao = db.bufferDao()
        val msgDao = db.messageDao()
        val bid = bufDao.insert(buffer(networkId, "#muted"))
        bufDao.setMuted(bid, true)
        msgDao.insertAll(
            listOf(
                message(bid, "one", serverTime = 100, dedupKey = "mute-1"),
                message(bid, "two", serverTime = 200, dedupKey = "mute-2", hasMention = true),
                message(bid, "three", serverTime = 300, dedupKey = "mute-3"),
            ),
        )
        assertEquals(3, bufDao.observeChatList().first().single().unreadCount)

        bufDao.setMuted(bid, false)

        val unmuted = checkNotNull(bufDao.observeById(bid))
        assertEquals(null, unmuted.readMarkerTime)
        assertEquals(300L, unmuted.localUnreadFloorTime)
        assertEquals(0, bufDao.observeChatList().first().single().unreadCount)

        msgDao.insertAll(listOf(message(bid, "new", serverTime = 400, dedupKey = "mute-4")))
        assertEquals(1, bufDao.observeChatList().first().single().unreadCount)
    }
}
