package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListSectioningTest {

    private fun row(
        id: Long,
        name: String,
        type: BufferType = BufferType.QUERY,
        pinned: Boolean = false,
    ) = ChatListRow(
        bufferId = id, networkId = 1, networkName = "net",
        displayName = name, type = type, pinned = pinned, muted = false,
        lastMessageText = null, lastMessageSender = null, lastMessageTime = null,
        unreadCount = 0, mentionCount = 0,
    )

    @Test
    fun `classifies queries into friends and fools, regular otherwise`() {
        val rows = listOf(
            row(1, "alice"),
            row(2, "bob"),
            row(3, "carol"),
        )
        val s = sectionChatList(rows, friends = setOf("alice"), fools = setOf("bob"))
        assertEquals(listOf(1L), s.friends.map { it.bufferId })
        assertEquals(listOf(2L), s.fools.map { it.bufferId })
        assertEquals(listOf(3L), s.regular.map { it.bufferId })
    }

    @Test
    fun `channels never classify even if name matches a friend or fool`() {
        val rows = listOf(
            row(1, "#alice", type = BufferType.CHANNEL),
            row(2, "#bob", type = BufferType.CHANNEL),
        )
        val s = sectionChatList(rows, friends = setOf("#alice"), fools = setOf("#bob"))
        assertEquals(emptyList<Long>(), s.friends.map { it.bufferId })
        assertEquals(emptyList<Long>(), s.fools.map { it.bufferId })
        assertEquals(listOf(1L, 2L), s.regular.map { it.bufferId })
    }

    @Test
    fun `pinned rows classify normally (no separate section), leading their section`() {
        // Pinned no longer pulls rows aside; a pinned friend/fool stays in friends/fools, and the
        // query's pinned-first order is preserved (input order is preserved within a section).
        val rows = listOf(
            row(1, "alice", pinned = true),   // pinned friend, sorts first among friends
            row(2, "bob", pinned = true),     // pinned fool
            row(3, "carol"),                  // plain friend
        )
        val s = sectionChatList(rows, friends = setOf("alice", "carol"), fools = setOf("bob"))
        assertEquals(listOf(1L, 3L), s.friends.map { it.bufferId })
        assertEquals(listOf(2L), s.fools.map { it.bufferId })
    }

    @Test
    fun `classification is case-insensitive via normalizeNick`() {
        val s = sectionChatList(listOf(row(1, "Alice")), friends = setOf("alice"), fools = emptySet())
        assertEquals(listOf(1L), s.friends.map { it.bufferId })
    }

    @Test
    fun `input order is preserved within a section`() {
        val rows = listOf(row(3, "c"), row(1, "a"), row(2, "b"))
        val s = sectionChatList(rows, friends = setOf("a", "b", "c"), fools = emptySet())
        assertEquals(listOf(3L, 1L, 2L), s.friends.map { it.bufferId })
    }

    @Test
    fun `empty friends and fools leaves everything regular`() {
        val rows = listOf(row(1, "alice"), row(2, "#chan", type = BufferType.CHANNEL))
        val s = sectionChatList(rows, friends = emptySet(), fools = emptySet())
        assertEquals(listOf(1L, 2L), s.regular.map { it.bufferId })
        assertEquals(emptyList<Long>(), s.friends + s.fools)
    }
}
