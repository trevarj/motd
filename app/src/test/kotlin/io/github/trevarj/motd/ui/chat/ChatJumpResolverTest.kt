package io.github.trevarj.motd.ui.chat

import androidx.paging.PagingData
import androidx.paging.LoadState
import androidx.lifecycle.SavedStateHandle
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.client.IrcTimeoutException
import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeMessageRepository(
    rows: List<MessageEntity> = emptyList(),
    private val canonicalRooms: Map<Long, Long> = emptyMap(),
) : MessageRepository {
    val store = rows.toMutableList()

    override fun messages(
        bufferId: Long,
        visibility: MessageVisibilitySpec,
    ): Flow<PagingData<MessageEntity>> = flowOf(PagingData.empty())
    override fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>> =
        flowOf(emptyList())

    override suspend fun byId(id: Long): MessageEntity? = store.firstOrNull { it.id == id }

    override suspend fun canonicalRoomId(bufferId: Long): Long = canonicalRooms[bufferId] ?: bufferId

    override suspend fun deleteMessage(id: Long) { store.removeAll { it.id == id } }

    override suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity? =
        store.firstOrNull { it.bufferId == bufferId && it.msgid == msgid }

    override fun observeByMsgid(bufferId: Long, msgid: String): Flow<MessageEntity?> =
        flowOf(store.firstOrNull { it.bufferId == bufferId && it.msgid == msgid })

    override suspend fun awaitMsgid(id: Long, timeoutMs: Long): String? =
        store.firstOrNull { it.id == id }?.msgid

    // Strict complement of (serverTime DESC, id DESC).
    override suspend fun countNewerThan(
        bufferId: Long,
        serverTime: Long,
        id: Long,
        visibility: MessageVisibilitySpec,
    ): Int =
        store.count {
            it.bufferId == bufferId &&
                (it.serverTime > serverTime || (it.serverTime == serverTime && it.id > id))
        }

}

private fun msg(id: Long, bufferId: Long, serverTime: Long, msgid: String?): MessageEntity =
    MessageEntity(
        id = id, bufferId = bufferId, msgid = msgid, serverTime = serverTime,
        sender = "alice", kind = MessageKind.PRIVMSG, text = "t", dedupKey = "d$id",
    )

class ChatJumpResolverTest {

    @Test fun `canonical event id resolves exact msgidless row`() = runTest {
        val repo = FakeMessageRepository(
            listOf(
                msg(1, 7, serverTime = 100, msgid = "older"),
                msg(2, 7, serverTime = 200, msgid = null),
                msg(3, 7, serverTime = 300, msgid = "newer"),
            ),
        )
        val resolver = ChatJumpResolver(repo, fetchAround = { _, _, _, _ -> false })

        val result = resolver.resolve(
            bufferId = 7,
            msgid = null,
            timeMs = 0,
            bufferName = null,
            eventId = 2,
        )

        val target = (result as ChatJumpResolver.Result.Resolved).target
        assertEquals(1, target.index)
        assertEquals(2L, target.expectedEventId)
    }

    @Test fun `canonical event id accepts a losing room redirect`() = runTest {
        val repo = FakeMessageRepository(
            rows = listOf(msg(2, 7, serverTime = 200, msgid = null)),
            canonicalRooms = mapOf(9L to 7L),
        )
        val resolver = ChatJumpResolver(repo, fetchAround = { _, _, _, _ -> false })

        val result = resolver.resolve(
            bufferId = 9,
            msgid = null,
            timeMs = 0,
            bufferName = null,
            eventId = 2,
        )

        val target = (result as ChatJumpResolver.Result.Resolved).target
        assertEquals(0, target.index)
        assertEquals(2L, target.expectedEventId)
    }

    @Test fun `only durable unresolved entry state presents the not-loaded snackbar`() {
        assertTrue(shouldPresentUnresolvedEntrySnackbar(entryPositionUnresolved = true))
        assertTrue(!shouldPresentUnresolvedEntrySnackbar(entryPositionUnresolved = false))
    }

    @Test fun `restored in-flight deep jump resolves again`() {
        val restored = SavedStateHandle(mapOf("jump_consumed" to true))

        assertTrue(
            needsDeepJumpResolution(
                hasDeepJump = true,
                jumpConsumed = restored.get<Boolean>("jump_consumed") == true,
                entryPositionSettled = restored.get<Boolean>("entry_position_settled") == true,
                entryPositionUnresolved = restored.get<Boolean>("entry_position_unresolved") == true,
            ),
        )
    }

    @Test fun `restored terminal deep jump is not resolved again`() {
        val restored = SavedStateHandle(
            mapOf("jump_consumed" to true, "entry_position_unresolved" to true),
        )

        assertTrue(
            !needsDeepJumpResolution(
                hasDeepJump = true,
                jumpConsumed = restored.get<Boolean>("jump_consumed") == true,
                entryPositionSettled = restored.get<Boolean>("entry_position_settled") == true,
                entryPositionUnresolved = restored.get<Boolean>("entry_position_unresolved") == true,
            ),
        )
    }

    @Test fun `empty refresh waits for a loading append then accepts its target rows`() {
        assertEquals(InitialPagingPage.Pending, initialPagingPage(itemCount = 0, append = LoadState.Loading))
        assertEquals(
            InitialPagingPage.Pending,
            initialPagingPage(itemCount = 0, append = LoadState.NotLoading(endOfPaginationReached = false)),
        )
        assertEquals(InitialPagingPage.RowsAvailable, initialPagingPage(itemCount = 1, append = LoadState.Loading))
    }

    @Test fun `terminal empty append leaves a deep or unread target unresolved`() {
        assertEquals(
            InitialPagingPage.TerminalEmpty,
            initialPagingPage(itemCount = 0, append = LoadState.NotLoading(endOfPaginationReached = true)),
        )
        assertEquals(
            InitialPagingPage.TerminalEmpty,
            initialPagingPage(itemCount = 0, append = LoadState.Error(IllegalStateException("no rows"))),
        )
    }

    @Test fun `deep jump cannot settle when its resolved index has a different msgid`() {
        // The screen must re-resolve this case and leave the mark-read gate closed until a later
        // target matches; a subsequent NotFound then takes the durable unresolved path.
        val target = ChatPositionTarget(index = 1, expectedEventId = 7, expectedMsgid = "wanted")
        assertTrue(!positionTargetMatches(target, msg(7, 1, 1, "shifted")))
        assertTrue(positionTargetMatches(target, msg(7, 1, 1, "wanted")))
        assertTrue(!positionTargetMatches(target, msg(8, 1, 1, "wanted")))
    }

    @Test fun `deep placeholder requests only target and waits for materialization at 50k`() = runTest {
        val requested = mutableListOf<Int>()
        val expected = msg(50_000, 7, 1, "Opaque/Exact")

        val loaded = requestAndAwaitTarget(
            index = 49_999,
            request = { requested += it; true },
            snapshots = flow {
                emit(TargetMaterialization<MessageEntity>(null, loading = false))
                emit(TargetMaterialization<MessageEntity>(null, loading = true))
                emit(TargetMaterialization(expected, loading = false))
            },
        )

        assertEquals(listOf(49_999), requested)
        assertEquals(expected, loaded)
    }

    @Test fun `target snapshot replacement terminates unresolved without scanning`() = runTest {
        val requested = mutableListOf<Int>()

        val loaded = requestAndAwaitTarget<MessageEntity>(
            index = 49_999,
            request = { requested += it; true },
            snapshots = flow {
                emit(TargetMaterialization(null, loading = false, generation = "old"))
                emit(TargetMaterialization(null, loading = false, generation = "replacement"))
            },
        )

        assertEquals(listOf(49_999), requested)
        assertEquals(null, loaded)
    }

    @Test fun `target materialization waits for its load and reports its error`() = runTest {
        val requested = mutableListOf<Int>()

        val loaded = requestAndAwaitTarget<MessageEntity>(
            index = 7,
            request = { requested += it; true },
            snapshots = flow {
                emit(TargetMaterialization(null, loading = false, generation = 1))
                emit(TargetMaterialization(null, loading = true, generation = 1))
                emit(TargetMaterialization(null, loading = false, failed = true, generation = 1))
            },
        )

        assertEquals(listOf(7), requested)
        assertEquals(null, loaded)
    }

    @Test fun `target load state ignores an unrelated preexisting paging error`() {
        val prependError = LoadState.Error(IllegalStateException("unrelated prepend"))
        val appendIdle = LoadState.NotLoading(endOfPaginationReached = false)

        assertEquals(
            appendIdle,
            relevantTargetLoadState(
                index = 90,
                loadedStart = 40,
                loadedEnd = 80,
                prepend = prependError,
                append = appendIdle,
            ),
        )
        assertEquals(
            null,
            relevantTargetLoadState(
                index = 60,
                loadedStart = 40,
                loadedEnd = 80,
                prepend = prependError,
                append = appendIdle,
            ),
        )
    }

    @Test fun `preexisting target-direction error does not hide later materialization`() = runTest {
        val expected = msg(8, 7, 1, "target")

        val loaded = requestAndAwaitTarget(
            index = 7,
            request = { true },
            snapshots = flow {
                emit(TargetMaterialization<MessageEntity>(null, loading = false, failed = true, generation = 1))
                emit(TargetMaterialization(expected, loading = false, failed = true, generation = 1))
            },
        )

        assertEquals(expected, loaded)
    }

    @Test fun `target materialization timeout reports unresolved`() = runTest {
        val loaded = requestAndAwaitTarget<MessageEntity>(
            index = 7,
            request = { true },
            snapshots = flow {
                emit(TargetMaterialization(null, loading = false, generation = 1))
                awaitCancellation()
            },
        )

        assertEquals(null, loaded)
    }

    @Test fun local_hit_returns_index_and_highlight() = runTest {
        val repo = FakeMessageRepository(
            listOf(
                msg(1, 7, serverTime = 100, msgid = "m-a"),
                msg(2, 7, serverTime = 200, msgid = "m-b"),
                msg(3, 7, serverTime = 300, msgid = "m-c"),
            )
        )
        val resolver = ChatJumpResolver(repo) { _, _, _, _ -> false }
        val r = resolver.resolve(bufferId = 7, msgid = "m-b", timeMs = 200, bufferName = "#chan")
        assertTrue(r is ChatJumpResolver.Result.Resolved)
        val target = (r as ChatJumpResolver.Result.Resolved).target
        assertEquals(1, target.index)
        assertEquals("m-b", target.highlightMsgid)
        assertEquals("m-b", target.expectedMsgid)
        assertEquals(2L, target.expectedEventId)
    }

    @Test fun around_fallback_inserts_then_resolves() = runTest {
        val repo = FakeMessageRepository(listOf(msg(1, 7, serverTime = 300, msgid = "m-c")))
        // fetchAround simulates CHATHISTORY inserting the missing target into the repo.
        val resolver = ChatJumpResolver(repo) { _, requestedMsgid, timeMs, _ ->
            assertEquals("m-b", requestedMsgid)
            repo.store.add(msg(2, 7, serverTime = timeMs, msgid = "m-b"))
            true
        }
        val r = resolver.resolve(bufferId = 7, msgid = "m-b", timeMs = 200, bufferName = "#chan")
        assertTrue(r is ChatJumpResolver.Result.Resolved)
        val target = (r as ChatJumpResolver.Result.Resolved).target
        // m-b at t=200 is older than m-c at t=300 → index 1.
        assertEquals(1, target.index)
        assertEquals("m-b", target.highlightMsgid)
    }

    @Test fun timeout_or_absent_is_not_found() = runTest {
        val repo = FakeMessageRepository(listOf(msg(1, 7, serverTime = 300, msgid = "m-c")))
        // fetchAround "fails" (returns false, e.g. timed out) and inserts nothing.
        val resolver = ChatJumpResolver(repo) { _, _, _, _ -> false }
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
        val resolver = ChatJumpResolver(repo) { _, _, _, _ -> false }
        val r = resolver.resolve(bufferId = 7, msgid = null, timeMs = 200, bufferName = "#chan")
        assertTrue(r is ChatJumpResolver.Result.Resolved)
        val target = (r as ChatJumpResolver.Result.Resolved).target
        assertEquals(1, target.index) // one row (t=300) is strictly newer than t=200
        assertEquals(null, target.highlightMsgid)
    }

    @Test fun `unread time target lands at its reverse-list boundary`() = runTest {
        val resolver = ChatJumpResolver(
            FakeMessageRepository(
                listOf(
                    msg(1, 7, serverTime = 100, msgid = "m-a"),
                    msg(2, 7, serverTime = 200, msgid = "m-b"),
                    msg(3, 7, serverTime = 300, msgid = "m-c"),
                ),
            ),
        ) { _, _, _, _ -> false }

        val result = resolver.resolve(bufferId = 7, msgid = null, timeMs = 200, bufferName = null)
        assertTrue(result is ChatJumpResolver.Result.Resolved)
        assertEquals(1, (result as ChatJumpResolver.Result.Resolved).target.index)
    }

    @Test fun `time target lands at the newest row of a tied timestamp`() = runTest {
        val resolver = ChatJumpResolver(
            FakeMessageRepository(
                listOf(
                    msg(1, 7, serverTime = 100, msgid = "m-a"),
                    msg(2, 7, serverTime = 200, msgid = "m-b"),
                    msg(3, 7, serverTime = 200, msgid = "m-c"),
                    msg(4, 7, serverTime = 300, msgid = "m-d"),
                ),
            ),
        ) { _, _, _, _ -> false }

        val result = resolver.resolve(bufferId = 7, msgid = null, timeMs = 200, bufferName = null)
        assertEquals(1, (result as ChatJumpResolver.Result.Resolved).target.index)
    }

    @Test fun `policy-backed count controls the presented timeline index`() = runTest {
        val repo = FakeMessageRepository(
            listOf(
                msg(1, 7, serverTime = 100, msgid = "target"),
                msg(2, 7, serverTime = 200, msgid = "ignored"),
            ),
        )
        val resolver = ChatJumpResolver(
            messages = repo,
            countNewer = { _, _, _ -> 0 },
            fetchAround = { _, _, _, _ -> false },
        )

        val result = resolver.resolve(7, "target", 100, "#chan")
        assertEquals(0, (result as ChatJumpResolver.Result.Resolved).target.index)
    }

    @Test fun `around fetch prefers and preserves opaque msgid then persists exact request`() = runTest {
        val requests = mutableListOf<ChatHistoryRequest>()
        var persisted: ChatHistoryRequest? = null
        val page = ChatHistoryResponse.Messages(emptyList(), null, null, false, 0)

        val completed = fetchAroundHistoryPage(
            target = "#chan",
            msgid = "MiXeD/opaque=Value",
            timeMs = 200,
            limit = 100,
            availability = HistoryAvailability.Ready(
                setOf(HistoryReferenceType.MSGID, HistoryReferenceType.TIMESTAMP),
                pageLimit = 50,
            ),
            requestPage = { request ->
                requests += request
                page
            },
            persistPage = { request, _ -> persisted = request },
        )

        assertTrue(completed)
        assertEquals(listOf("msgid=MiXeD/opaque=Value"), requests.map { it.bound1 })
        assertEquals(requests.single(), persisted)
        assertEquals(50, persisted?.limit)
    }

    @Test fun `around fetch falls back only from invalid msgid reference and persists fallback`() = runTest {
        val requests = mutableListOf<ChatHistoryRequest>()
        var persisted: ChatHistoryRequest? = null
        val page = ChatHistoryResponse.Messages(emptyList(), null, null, false, 0)

        fetchAroundHistoryPage(
            target = "#chan",
            msgid = "ExactCase",
            timeMs = 200,
            limit = 100,
            availability = HistoryAvailability.Ready(
                setOf(HistoryReferenceType.MSGID, HistoryReferenceType.TIMESTAMP),
                pageLimit = 100,
            ),
            requestPage = { request ->
                requests += request
                if (requests.size == 1) {
                    throw IrcCommandException("CHATHISTORY", "INVALID_MSGREFTYPE", "no msgid")
                }
                page
            },
            persistPage = { request, _ -> persisted = request },
        )

        assertEquals(
            listOf("msgid=ExactCase", "timestamp=1970-01-01T00:00:00.200Z"),
            requests.map { it.bound1 },
        )
        assertEquals(requests.last(), persisted)
    }

    @Test fun `around non-reference failures do not retry with timestamp`() = runTest {
        listOf(
            IrcTimeoutException("around"),
            IrcDisconnectedException("CHATHISTORY", "lost connection"),
            IOException("read failed"),
            IrcCommandException("CHATHISTORY", "MESSAGE_ERROR", "request rejected"),
        ).forEach { expected ->
            val requests = mutableListOf<ChatHistoryRequest>()
            var persisted = false
            val failure = runCatching {
                fetchAroundHistoryPage(
                    target = "#chan",
                    msgid = "ExactCase",
                    timeMs = 200,
                    limit = 100,
                    availability = HistoryAvailability.Ready(
                        setOf(HistoryReferenceType.MSGID, HistoryReferenceType.TIMESTAMP),
                        pageLimit = 100,
                    ),
                    requestPage = { request ->
                        requests += request
                        throw expected
                    },
                    persistPage = { _, _ -> persisted = true },
                )
            }.exceptionOrNull()

            assertTrue(failure === expected)
            assertEquals(listOf("msgid=ExactCase"), requests.map { it.bound1 })
            assertTrue(!persisted)
        }
    }
}
