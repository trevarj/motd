package io.github.trevarj.motd.agentwire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentwireActionReceiptTest {
    @Test
    fun `late transport completion cannot hide acceptance or a terminal result`() {
        val accepted = mapOf("action" to "accepted")
        assertEquals(accepted, accepted.withOutcome("action", "sent"))
        val succeeded = accepted.withOutcome("action", "succeeded")
        assertEquals(succeeded, succeeded.withOutcome("action", "accepted").withOutcome("action", "unknown"))
    }

    @Test
    fun `receipt codec preserves only typed durable fields`() {
        val receipt = receipt("id", "accepted")

        assertEquals(listOf(receipt), decodeAgentwireReceipts(encodeAgentwireReceipts(listOf(receipt))))
        assertTrue(decodeAgentwireReceipts("not json").isEmpty())
    }

    @Test
    fun `retention evicts a terminal receipt before unresolved unknown`() {
        val now = 1_800_000_000_000L
        val unresolved = (1..999).map { receipt("unknown-$it", "unknown", now = now - it) }
        val terminal = receipt("terminal", "succeeded", now = now - 2_000)

        val stored = agentwireReceiptsWith(unresolved + terminal, receipt("new", "sent", now = now), now)

        assertEquals(1_000, stored.size)
        assertTrue(stored.none { it.id == "terminal" })
        assertTrue(stored.any { it.id == "unknown-1" })
    }

    @Test
    fun `full unresolved receipt set refuses a new mutation`() {
        val now = 1_800_000_000_000L
        val unresolved = (1..1_000).map { receipt("unknown-$it", "unknown", now = now - it) }

        assertThrows(IllegalStateException::class.java) {
            agentwireReceiptsWith(unresolved, receipt("new", "sent", now = now), now)
        }
    }

    @Test
    fun `same action id in separate scopes remains isolated and expired records disappear`() {
        val now = 1_800_000_000_000L
        val first = receipt("same", "accepted", now = now).copy(scope = "first")
        val second = receipt("same", "unknown", now = now).copy(scope = "second")

        assertEquals(setOf("first", "second"), agentwireReceiptsWith(listOf(first), second, now).map { it.scope }.toSet())
        assertTrue(pruneAgentwireReceipts(listOf(first.copy(sentAt = 0)), now).isEmpty())
    }

    private fun receipt(
        id: String,
        outcome: String,
        now: Long = 1_800_000_000_000L,
    ) = AgentwireActionReceipt(
        id = id,
        kind = "turn.prompt",
        channel = "#claude",
        sid = "session-1",
        sentAt = now,
        outcome = outcome,
        scope = "scope",
    )
}
