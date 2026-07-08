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

/**
 * Covers the plans/15 #5 (buffer-scoped reactions, no IN-clause overflow) and #10 (failed-row
 * delete) DAO additions.
 */
@RunWith(RobolectricTestRunner::class)
class ReactionAndDeleteDaoTest {
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

    @Test
    fun observeForBuffer_returnsAllReactionsWithoutAnInList() = runTest {
        val dao = db.reactionDao()
        dao.upsert(ReactionEntity(bufferId = bufferId, targetMsgid = "m1", sender = "a", emoji = "👍", serverTime = 1))
        dao.upsert(ReactionEntity(bufferId = bufferId, targetMsgid = "m2", sender = "b", emoji = "🎉", serverTime = 2))
        dao.upsert(ReactionEntity(bufferId = bufferId + 999, targetMsgid = "x", sender = "c", emoji = "❤️", serverTime = 3))

        val rows = dao.observeForBuffer(bufferId).first()
        assertEquals(2, rows.size)
        assertEquals(setOf("m1", "m2"), rows.map { it.targetMsgid }.toSet())
    }

    @Test
    fun deleteById_removesTheFailedRow() = runTest {
        val dao = db.messageDao()
        val id = dao.insertAll(
            listOf(message(bufferId, "oops", serverTime = 1, dedupKey = "pending:l1", isSelf = true)),
        ).single()

        dao.deleteById(id)

        assertNull(dao.byMsgid(bufferId, "any"))
        val newest = dao.newestTime(bufferId)
        assertNull(newest)
    }
}
