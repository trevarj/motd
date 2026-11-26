package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.sync.COMMAND_RESPONSE_PAYLOAD_PREFIX
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemEventChunkTest {
    /**
     * Partition a presented run of system rows the way [MessageList] does: heads are decided per
     * row, then each head gathers older neighbours up to (not including) the next boundary.
     */
    private fun chunksOf(ids: List<Long>): List<List<Long>> {
        val heads = ids.indices.filter { i -> isSystemRunChunkHead(ids[i], newerIsSystem = i != 0) }
        return heads.map { head ->
            val chunk = mutableListOf(ids[head])
            var i = head + 1
            while (i < ids.size && !isSystemRunChunkBoundary(ids[i])) {
                chunk.add(ids[i])
                i++
            }
            chunk
        }
    }

    @Test fun `long event run partitions into non-overlapping chunks covering every row`() {
        val ids = (1L..500L).toList()
        val chunks = chunksOf(ids)

        val rendered = chunks.flatten()
        assertEquals(ids, rendered)
        assertEquals(rendered.size, rendered.toSet().size)
        assertTrue(chunks.size > 1)
    }

    @Test fun `chunk membership survives newer rows landing during catch-up`() {
        // The flicker case: a playback burst prepends rows, shifting every presented index. Chunk
        // membership below the newest row must not change, or every visible pill re-wraps.
        val settled = (100L..400L).toList()
        val before = chunksOf(settled)
        val after = chunksOf((1L..99L) + settled)

        val beforeOwner = before.flatMap { chunk -> chunk.map { it to chunk } }.toMap()
        val afterOwner = after.flatMap { chunk -> chunk.map { it to chunk } }.toMap()
        // Only the run's former newest chunk may absorb the arriving rows; every other settled row
        // keeps byte-identical chunk membership, so its pill neither re-summarizes nor re-keys.
        val interior = settled.filter { beforeOwner.getValue(it) !== before.first() }
        assertTrue(interior.size > settled.size / 2)
        interior.forEach { id -> assertEquals(beforeOwner.getValue(id), afterOwner.getValue(id)) }
    }

    @Test fun `boundary placement is independent of id periodicity`() {
        // Ids stride by 24 (one room in a busy multi-room store). An index- or modulo-of-id rule
        // would put every row in the same residue class and never cut; the mixed hash still does.
        val strided = (0 until 400).map { 7L + it * 24L }
        val chunks = chunksOf(strided)
        assertTrue(chunks.size > 5)
        assertEquals(strided, chunks.flatten())
    }

    @Test fun `tail append refreshes content without losing run expansion`() {
        // An expanded tail initially has two rows; append supplies older rows in that same chunk.
        val beforeAppend = SystemRunContentKey(newestId = 48, oldestId = 49, count = 2)
        val afterAppend = SystemRunContentKey(newestId = 48, oldestId = 51, count = 4)
        val expandedIds =
            updateExpandedSystemEvents(
                current = emptySet(),
                runIds = listOf(48L, 49L),
                expanded = true,
            )

        assertTrue(beforeAppend != afterAppend)
        assertTrue(systemRunExpanded(listOf(48L, 50L, 49L, 51L), expandedIds))
    }

    @Test fun `away and back events have clear grouped summaries`() {
        val run =
            listOf(
                systemMessage(1, MessageKind.AWAY),
                systemMessage(2, MessageKind.AWAY),
                systemMessage(3, MessageKind.BACK),
            )

        assertEquals("2 away · 1 back", summarizeSystemRun(run))
    }

    @Test fun `newest-first gathered chunks present chronologically across consecutive pills`() {
        val newerChunk =
            listOf(
                systemMessage(6, MessageKind.AWAY),
                systemMessage(5, MessageKind.PART),
                systemMessage(4, MessageKind.JOIN),
            )
        val olderChunk =
            listOf(
                systemMessage(3, MessageKind.AWAY),
                systemMessage(2, MessageKind.PART),
                systemMessage(1, MessageKind.JOIN),
            )

        val topToBottomLines =
            listOf(olderChunk, newerChunk).flatMap(::systemRunPresentationLines)

        assertEquals((1L..6L).map { "line $it" }, topToBottomLines)
    }

    @Test fun `bot JOIN uses the existing marker in single and expanded pills without changing stored text`() {
        val bot = systemMessage(1, MessageKind.JOIN).copy(sender = "Helper", text = "Helper joined", isBot = true)
        val human = systemMessage(2, MessageKind.JOIN).copy(sender = "Alice", text = "Alice joined")

        assertEquals("Helper 🤖 joined", systemEventDisplayText(bot))
        assertEquals(listOf("Helper 🤖 joined", "Alice joined"), systemRunPresentationLines(listOf(human, bot)))
        assertEquals("Helper joined", bot.text)
    }

    @Test fun `command responses group only within their own session`() {
        val join = systemMessage(1, MessageKind.JOIN)
        val first = systemMessage(2, MessageKind.SERVER_INFO, "${COMMAND_RESPONSE_PAYLOAD_PREFIX}first")
        val firstError = systemMessage(3, MessageKind.ERROR, "${COMMAND_RESPONSE_PAYLOAD_PREFIX}first")
        val second = systemMessage(4, MessageKind.SERVER_INFO, "${COMMAND_RESPONSE_PAYLOAD_PREFIX}second")

        assertTrue(sameSystemRun(first, firstError))
        assertTrue(!sameSystemRun(join, first))
        assertTrue(!sameSystemRun(first, second))
    }

    @Test fun `adjacent chunks keep distinct content identities`() {
        val beforeBoundary = SystemRunContentKey(newestId = 1, oldestId = 23, count = 23)
        val atBoundary = SystemRunContentKey(newestId = 24, oldestId = 47, count = 24)
        assertTrue(beforeBoundary != atBoundary)
    }

    private fun systemMessage(
        id: Long,
        kind: MessageKind,
        payload: String? = null,
    ): MessageEntity =
        MessageEntity(
            id = id,
            bufferId = 1,
            serverTime = id,
            sender = "/motd",
            kind = kind,
            text = "line $id",
            dedupKey = "dedup-$id",
            eventPayload = payload,
        )
}
