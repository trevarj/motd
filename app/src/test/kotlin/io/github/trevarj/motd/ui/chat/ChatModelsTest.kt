package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.ReactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatModelsTest {

    private fun react(msgid: String, sender: String, emoji: String) =
        ReactionEntity(bufferId = 1L, targetMsgid = msgid, sender = sender, emoji = emoji, serverTime = 0L)

    @Test fun `mine matches own nick case-insensitively`() {
        val chips = aggregateReactions(listOf(react("m1", "Alice", "👍")), myNick = "alice")
        assertTrue(chips.getValue("m1").single().mine)
    }

    @Test fun `mine uses rfc1459 casefolding for bracket chars`() {
        // nick[] and nick{} are the same nick under rfc1459 ( [ == { , ] == } ).
        val chips = aggregateReactions(listOf(react("m1", "nick[]", "🎉")), myNick = "nick{}")
        assertTrue("rfc1459 folding should treat []{} as equivalent", chips.getValue("m1").single().mine)
    }

    @Test fun `mine is false for a different reactor`() {
        val chips = aggregateReactions(listOf(react("m1", "bob", "👍")), myNick = "alice")
        assertFalse(chips.getValue("m1").single().mine)
    }

    @Test fun `mine is false when disconnected (null nick)`() {
        val chips = aggregateReactions(listOf(react("m1", "alice", "👍")), myNick = null)
        assertFalse(chips.getValue("m1").single().mine)
    }

    @Test fun `custom normalizer is honored`() {
        // An isupport-style normalizer that folds trailing digits away would make nick1 == nick.
        val chips = aggregateReactions(
            listOf(react("m1", "Alice1", "👍")),
            myNick = "alice2",
            normalizer = { it.lowercase().trimEnd('1', '2') },
        )
        assertTrue(chips.getValue("m1").single().mine)
    }

    @Test fun `counts aggregate per emoji preserving first-appearance order`() {
        val chips = aggregateReactions(
            listOf(
                react("m1", "a", "👍"),
                react("m1", "b", "👍"),
                react("m1", "c", "🎉"),
            ),
            myNick = "z",
        ).getValue("m1")
        assertEquals(listOf("👍", "🎉"), chips.map { it.emoji })
        assertEquals(2, chips[0].count)
        assertEquals(1, chips[1].count)
    }

    // Auto-stick-to-bottom decision (autoscroll-to-newest bug). Pin the reverse list to the newest
    // row only when the user was already at the bottom AND a new row actually arrived.
    @Test fun `autoscroll when at bottom and count grew`() {
        assertTrue(shouldAutoscrollToNewest(atBottom = true, oldCount = 10, newCount = 11))
    }

    @Test fun `no autoscroll when scrolled up even if count grew`() {
        assertFalse(shouldAutoscrollToNewest(atBottom = false, oldCount = 10, newCount = 11))
    }

    @Test fun `no autoscroll when count did not grow`() {
        // Same count (e.g. an echo-confirm msgid swap) or a shrink must not yank the viewport.
        assertFalse(shouldAutoscrollToNewest(atBottom = true, oldCount = 10, newCount = 10))
        assertFalse(shouldAutoscrollToNewest(atBottom = true, oldCount = 10, newCount = 9))
    }
}
