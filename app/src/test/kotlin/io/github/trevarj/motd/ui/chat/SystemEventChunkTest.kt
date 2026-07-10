package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemEventChunkTest {
    @Test fun `fifty event run partitions into non-overlapping bounded chunks`() {
        // The first event follows a normal message at index 1. Chunk boundaries are absolute, so
        // this exercises the short first chunk plus two full/bounded successors.
        val eventIndices = 1..50
        val heads = eventIndices.filter { index ->
            isSystemRunChunkHead(index, newerIsSystem = index != 1)
        }
        assertEquals(listOf(1, 24, 48), heads)

        val rendered = heads.flatMap { head ->
            (head until minOf(head + systemRunChunkLimit(head), 51)).toList()
        }
        assertEquals(eventIndices.toList(), rendered)
        assertEquals(rendered.size, rendered.toSet().size)
        assertTrue(heads.all { systemRunChunkLimit(it) <= MAX_COLLAPSED_SYSTEM_EVENTS })
    }

    @Test fun `tail append changes expanded-pill content identity`() {
        // An expanded tail initially has two rows; append supplies older rows in that same chunk.
        val beforeAppend = SystemRunContentKey(newestId = 48, oldestId = 49, count = 2)
        val afterAppend = SystemRunContentKey(newestId = 48, oldestId = 51, count = 4)
        assertTrue(beforeAppend != afterAppend)
    }

    @Test fun `arbitrary chunk boundary keeps distinct content identities`() {
        // Absolute chunk boundaries may create a short first chunk followed by a full one.
        val beforeBoundary = SystemRunContentKey(newestId = 1, oldestId = 23, count = 23)
        val atBoundary = SystemRunContentKey(newestId = 24, oldestId = 47, count = 24)
        assertTrue(beforeBoundary != atBoundary)
    }
}
