package io.github.trevarj.motd.ui.chat

import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.repo.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMessageRepository(rows: List<MessageEntity> = emptyList()) : MessageRepository {
    val store = rows.toMutableList()

    override fun messages(bufferId: Long): Flow<PagingData<MessageEntity>> = flowOf(PagingData.empty())
    override fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>> =
        flowOf(emptyList())

    override fun reactionsForBuffer(bufferId: Long): Flow<List<ReactionEntity>> =
        flowOf(emptyList())

    override suspend fun deleteMessage(id: Long) { store.removeAll { it.id == id } }

    override suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity? =
        store.firstOrNull { it.bufferId == bufferId && it.msgid == msgid }

    // Strict complement of (serverTime DESC, id DESC).
    override suspend fun countNewerThan(bufferId: Long, serverTime: Long, id: Long): Int =
        store.count {
            it.bufferId == bufferId &&
                (it.serverTime > serverTime || (it.serverTime == serverTime && it.id > id))
        }

    override suspend fun firstUnreadOtherTime(bufferId: Long, after: Long): Long? =
        store.filter { it.bufferId == bufferId && !it.isSelf && it.serverTime > after }
            .minOfOrNull { it.serverTime }
}

private fun msg(id: Long, bufferId: Long, serverTime: Long, msgid: String?): MessageEntity =
    MessageEntity(
        id = id, bufferId = bufferId, msgid = msgid, serverTime = serverTime,
        sender = "alice", kind = MessageKind.PRIVMSG, text = "t", dedupKey = "d$id",
    )

class ChatJumpResolverTest {

    @Test fun local_hit_returns_index_and_highlight() = runTest {
        val repo = FakeMessageRepository(
            listOf(
                msg(1, 7, serverTime = 100, msgid = "m-a"),
                msg(2, 7, serverTime = 200, msgid = "m-b"),
                msg(3, 7, serverTime = 300, msgid = "m-c"),
            )
        )
        val resolver = ChatJumpResolver(repo) { _, _, _ -> false }
        val r = resolver.resolve(bufferId = 7, msgid = "m-b", timeMs = 200, bufferName = "#chan")
        assertTrue(r is ChatJumpResolver.Result.Target)
        r as ChatJumpResolver.Result.Target
        assertEquals(1, r.index)
        assertEquals("m-b", r.highlightMsgid)
    }

    @Test fun around_fallback_inserts_then_resolves() = runTest {
        val repo = FakeMessageRepository(listOf(msg(1, 7, serverTime = 300, msgid = "m-c")))
        // fetchAround simulates CHATHISTORY inserting the missing target into the repo.
        val resolver = ChatJumpResolver(repo) { _, timeMs, _ ->
            repo.store.add(msg(2, 7, serverTime = timeMs, msgid = "m-b"))
            true
        }
        val r = resolver.resolve(bufferId = 7, msgid = "m-b", timeMs = 200, bufferName = "#chan")
        assertTrue(r is ChatJumpResolver.Result.Target)
        r as ChatJumpResolver.Result.Target
        // m-b at t=200 is older than m-c at t=300 → index 1.
        assertEquals(1, r.index)
        assertEquals("m-b", r.highlightMsgid)
    }

    @Test fun timeout_or_absent_is_not_found() = runTest {
        val repo = FakeMessageRepository(listOf(msg(1, 7, serverTime = 300, msgid = "m-c")))
        // fetchAround "fails" (returns false, e.g. timed out) and inserts nothing.
        val resolver = ChatJumpResolver(repo) { _, _, _ -> false }
        val r = resolver.resolve(bufferId = 7, msgid = "m-missing", timeMs = 200, bufferName = "#chan")
        assertEquals(ChatJumpResolver.Result.NotFound, r)
    }

    @Test fun null_msgid_time_approximation_no_highlight() = runTest {
        val repo = FakeMessageRepository(
            listOf(
                msg(1, 7, serverTime = 100, msgid = "m-a"),
                msg(2, 7, serverTime = 200, msgid = "m-b"),
                msg(3, 7, serverTime = 300, msgid = "m-c"),
            )
        )
        val resolver = ChatJumpResolver(repo) { _, _, _ -> false }
        val r = resolver.resolve(bufferId = 7, msgid = null, timeMs = 200, bufferName = "#chan")
        assertTrue(r is ChatJumpResolver.Result.Target)
        r as ChatJumpResolver.Result.Target
        assertEquals(1, r.index) // one row (t=300) is strictly newer than t=200
        assertEquals(null, r.highlightMsgid)
    }
}
