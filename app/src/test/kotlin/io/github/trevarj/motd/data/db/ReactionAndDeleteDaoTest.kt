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
        dao.upsert(ReactionEntity(bufferId = bufferId, targetMsgid = "m1", actorKey = "nick:a", sender = "a", emoji = "👍", serverTime = 1))
        dao.upsert(ReactionEntity(bufferId = bufferId, targetMsgid = "m2", actorKey = "nick:b", sender = "b", emoji = "🎉", serverTime = 2))
        dao.upsert(ReactionEntity(bufferId = bufferId + 999, targetMsgid = "x", actorKey = "nick:c", sender = "c", emoji = "❤️", serverTime = 3))

        val rows = dao.observeForBuffer(bufferId).first()
        assertEquals(2, rows.size)
        assertEquals(setOf("m1", "m2"), rows.map { it.targetMsgid }.toSet())
    }

    @Test
    fun optimisticOwnReaction_reconcilesWithServerEcho_withoutDuplicating() = runTest {
        val dao = db.reactionDao()
        // Optimistic own react (upserted locally on tap, before the TAGMSG round-trips).
        dao.upsert(ReactionEntity(bufferId = bufferId, targetMsgid = "m1", actorKey = "nick:me", sender = "me", emoji = "👍", serverTime = 1))
        // Server echoes our own react back with a later server time; the actor+emoji key collapses it.
        dao.upsert(ReactionEntity(bufferId = bufferId, targetMsgid = "m1", actorKey = "nick:me", sender = "me", emoji = "👍", serverTime = 2))

        val rows = dao.observeForBuffer(bufferId).first()
        assertEquals(1, rows.size)
        assertEquals(2, rows.single().serverTime) // reconciled to the server-echoed row
    }

    @Test
    fun actorCanRetainMultipleEmojis_andDeleteIsReactionSpecific() = runTest {
        val dao = db.reactionDao()
        dao.upsert(ReactionEntity(bufferId = bufferId, targetMsgid = "m1", actorKey = "nick:me", sender = "me", emoji = "👍", serverTime = 1))
        dao.upsert(ReactionEntity(bufferId = bufferId, targetMsgid = "m1", actorKey = "nick:me", sender = "me", emoji = "🎉", serverTime = 2))

        assertEquals(
            "👍",
            dao.find(bufferId, "m1", listOf("account:me", "nick:me"), "👍")?.emoji,
        )
        dao.delete(bufferId, "m1", "nick:me", "👍")

        val rows = dao.observeForBuffer(bufferId).first()
        assertEquals(listOf("🎉"), rows.map { it.emoji })
    }

    @Test
    fun observeMsgid_isNullWhilePending_thenEmitsMsgidOnceEchoLands() = runTest {
        val dao = db.messageDao()
        val id = dao.insertAll(
            listOf(message(bufferId, "hi", serverTime = 1, dedupKey = "pending:l1", isSelf = true, pendingLabel = "l1")),
        ).single()

        assertNull(dao.observeMsgid(id).first())

        // Echo promotes the msgid in place (as EventProcessor does on a labeled/heuristic echo).
        val pending = dao.byPendingLabel(bufferId, "l1")!!
        dao.update(pending.copy(msgid = "server-m1", pendingLabel = null, dedupKey = "server-m1"))

        assertEquals("server-m1", dao.observeMsgid(id).first { it != null })
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
