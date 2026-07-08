package io.github.trevarj.motd.data.db

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** DAO index math for search deep-jump (plans/11 §C, contract in plans/10). */
@RunWith(RobolectricTestRunner::class)
class MessageDaoJumpTest {
    private lateinit var db: MotdDatabase
    private var bufferId: Long = 0

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        val nid = db.networkDao().insert(network())
        bufferId = db.bufferDao().insert(buffer(nid, "#chan"))
    }

    @After
    fun tearDown() = db.close()

    @Test fun byMsgid_found_and_absent() = runTest {
        val dao = db.messageDao()
        dao.insertAll(listOf(message(bufferId, "hi", serverTime = 100, dedupKey = "a", msgid = "m-a")))
        assertNotNull(dao.byMsgid(bufferId, "m-a"))
        assertNull(dao.byMsgid(bufferId, "nope"))
    }

    @Test fun countNewerThan_newestRow_isZero() = runTest {
        val dao = db.messageDao()
        dao.insertAll(
            listOf(
                message(bufferId, "old", serverTime = 100, dedupKey = "a", msgid = "m-a"),
                message(bufferId, "mid", serverTime = 200, dedupKey = "b", msgid = "m-b"),
                message(bufferId, "new", serverTime = 300, dedupKey = "c", msgid = "m-c"),
            )
        )
        val newest = dao.byMsgid(bufferId, "m-c")!!
        assertEquals(0, dao.countNewerThan(bufferId, newest.serverTime, newest.id))

        val mid = dao.byMsgid(bufferId, "m-b")!!
        assertEquals(1, dao.countNewerThan(bufferId, mid.serverTime, mid.id))

        val oldest = dao.byMsgid(bufferId, "m-a")!!
        assertEquals(2, dao.countNewerThan(bufferId, oldest.serverTime, oldest.id))
    }

    @Test fun countNewerThan_tiedServerTime_brokenById() = runTest {
        val dao = db.messageDao()
        // Two rows share serverTime; ordering is (serverTime DESC, id DESC), so the higher id is
        // "newer" (index 0) and the lower id is index 1.
        dao.insertAll(listOf(message(bufferId, "first", serverTime = 500, dedupKey = "a", msgid = "m-a")))
        dao.insertAll(listOf(message(bufferId, "second", serverTime = 500, dedupKey = "b", msgid = "m-b")))
        val a = dao.byMsgid(bufferId, "m-a")!! // lower id
        val b = dao.byMsgid(bufferId, "m-b")!! // higher id

        assertEquals(0, dao.countNewerThan(bufferId, b.serverTime, b.id))
        assertEquals(1, dao.countNewerThan(bufferId, a.serverTime, a.id))
    }

    @Test fun countNewerThan_timeApproximation_boundaries() = runTest {
        val dao = db.messageDao()
        dao.insertAll(
            listOf(
                message(bufferId, "a", serverTime = 100, dedupKey = "a"),
                message(bufferId, "b", serverTime = 200, dedupKey = "b"),
                message(bufferId, "c", serverTime = 300, dedupKey = "c"),
            )
        )
        // Long.MAX_VALUE id → include every row at or newer than the given time.
        assertEquals(0, dao.countNewerThan(bufferId, 300, Long.MAX_VALUE)) // at newest → none newer
        assertEquals(1, dao.countNewerThan(bufferId, 200, Long.MAX_VALUE))
        assertEquals(2, dao.countNewerThan(bufferId, 100, Long.MAX_VALUE))
        assertEquals(3, dao.countNewerThan(bufferId, 50, Long.MAX_VALUE))  // before all rows
        assertEquals(0, dao.countNewerThan(bufferId, 400, Long.MAX_VALUE)) // after all rows
    }
}
