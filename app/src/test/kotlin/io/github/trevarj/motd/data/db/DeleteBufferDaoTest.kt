package io.github.trevarj.motd.data.db

import kotlinx.coroutines.flow.first
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

/**
 * Verifies BufferDao.deleteBuffer drops the buffer and ALL of its content. messages cascade via
 * the buffers->messages FK ON DELETE CASCADE (and messages_fts via Room's FTS sync triggers);
 * members and reactions have no FK to buffers, so deleteBuffer clears them explicitly. A sibling
 * buffer on the same network is untouched.
 */
@RunWith(RobolectricTestRunner::class)
class DeleteBufferDaoTest {
    private lateinit var db: MotdDatabase
    private var networkId: Long = 0

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        networkId = db.networkDao().insert(network())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun deleteBuffer_removesMessagesMembersReactions_andLeavesSiblings() = runTest {
        val bufDao = db.bufferDao()
        val msgDao = db.messageDao()
        val memberDao = db.memberDao()
        val reactionDao = db.reactionDao()

        val victim = bufDao.insert(buffer(networkId, "#victim"))
        val sibling = bufDao.insert(buffer(networkId, "#keep"))

        msgDao.insertAll(listOf(message(victim, "gone", serverTime = 1, dedupKey = "v1", msgid = "m1")))
        msgDao.insertAll(listOf(message(sibling, "stays", serverTime = 1, dedupKey = "k1", msgid = "m2")))
        memberDao.upsert(MemberEntity(victim, "alice", "@"))
        memberDao.upsert(MemberEntity(sibling, "bob", ""))
        reactionDao.upsert(ReactionEntity(bufferId = victim, targetMsgid = "m1", actorKey = "nick:x", sender = "x", emoji = "👍", serverTime = 1))
        reactionDao.upsert(ReactionEntity(bufferId = sibling, targetMsgid = "m2", actorKey = "nick:y", sender = "y", emoji = "🎉", serverTime = 1))

        bufDao.deleteBuffer(victim)

        // Victim buffer + all of its content is gone.
        assertNull(bufDao.observeById(victim))
        assertNull(msgDao.byMsgid(victim, "m1"))
        assertNull(msgDao.newestTime(victim))
        assertTrue(memberDao.observe(victim).first().isEmpty())
        assertTrue(reactionDao.observeForBuffer(victim).first().isEmpty())

        // Sibling on the same network is untouched.
        assertEquals("#keep", bufDao.observeById(sibling)?.displayName)
        assertEquals("stays", msgDao.byMsgid(sibling, "m2")?.text)
        assertEquals(1, memberDao.observe(sibling).first().size)
        assertEquals(1, reactionDao.observeForBuffer(sibling).first().size)

        // FTS stays consistent: a search for the deleted text finds nothing, the kept one does.
        assertTrue(msgDao.search("gone*", null).first().isEmpty())
        assertEquals("stays", msgDao.search("stays*", null).first().single().message.text)
    }

    @Test
    fun deleteQuery_purgesContentButKeepsHiddenIdentityAndHistoryFloor() = runTest {
        val query = db.bufferDao().insert(buffer(networkId, "alice", BufferType.QUERY))
        db.roomAliasDao().insertIgnore(
            RoomAliasEntity(
                networkId = networkId,
                namespace = RoomAliasNamespace.PROVISIONAL_NICK,
                value = "alice",
                roomId = query,
            ),
        )
        db.messageDao().insertAll(
            listOf(message(query, "discarded", serverTime = 100, dedupKey = "old", msgid = "m-old")),
        )
        db.historyCursorDao().upsert(
            HistoryCursorEntity(
                roomId = query,
                newestMsgid = "m-old",
                newestServerTime = 100,
                oldestMsgid = "m-old",
                oldestServerTime = 100,
                historyComplete = true,
            ),
        )
        db.composerDraftDao().upsert(ComposerDraftEntity(query, "draft", null, 101))
        db.memberDao().upsert(MemberEntity(query, "alice"))
        db.reactionDao().upsert(
            ReactionEntity(
                bufferId = query,
                targetMsgid = "m-old",
                actorKey = "nick:bob",
                sender = "bob",
                emoji = "+1",
                serverTime = 101,
            ),
        )

        db.bufferDao().deleteBuffer(query)

        val shell = db.bufferDao().rawById(query)!!
        assertTrue(shell.dismissed)
        assertEquals("m-old", shell.historyDiscardedThroughMsgid)
        assertEquals(100L, shell.historyDiscardedThroughTime)
        assertNull(db.messageDao().byMsgid(query, "m-old"))
        assertNull(db.composerDraftDao().byRoom(query))
        assertTrue(db.memberDao().observe(query).first().isEmpty())
        assertTrue(db.reactionDao().observeForBuffer(query).first().isEmpty())
        assertEquals(query, db.roomAliasDao().byValue(
            networkId,
            RoomAliasNamespace.PROVISIONAL_NICK,
            "alice",
        )?.roomId)
        assertEquals("m-old", db.historyCursorDao().byRoom(query)?.newestMsgid)
        assertFalse(db.bufferDao().observeChatList().first().any { it.bufferId == query })
    }

    @Test
    fun deleteQueryNeverUsesDeviceClockAsHistoryFloor() = runTest {
        val query = db.bufferDao().insert(buffer(networkId, "alice", BufferType.QUERY))
        db.messageDao().insertAll(
            listOf(
                message(query, "authoritative", serverTime = 100, dedupKey = "m1", msgid = "m1"),
                message(
                    query,
                    "local-clock",
                    serverTime = 10_000,
                    dedupKey = "m2",
                    msgid = null,
                ).copy(serverTimeAuthoritative = false),
            ),
        )
        val room = db.bufferDao().rawById(query)!!
        db.bufferDao().update(room.copy(localReadAnchorTime = 10_000))

        db.bufferDao().deleteBuffer(query)

        val shell = db.bufferDao().rawById(query)!!
        assertEquals("m1", shell.historyDiscardedThroughMsgid)
        assertEquals(100L, shell.historyDiscardedThroughTime)
        assertEquals(100L, shell.localReadAnchorTime)
    }
}
