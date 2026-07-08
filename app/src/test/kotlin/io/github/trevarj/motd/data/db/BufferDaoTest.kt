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
        // latest message projection reflects the newest by (serverTime, id).
        assertEquals("joined", row.lastMessageText)
        assertEquals(180L, row.lastMessageTime)
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
}
