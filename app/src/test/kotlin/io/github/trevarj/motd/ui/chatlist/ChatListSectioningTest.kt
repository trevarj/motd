package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun `pinned rows override friend and fool tiers while preserving friend membership`() {
        val rows = listOf(
            row(1, "alice", pinned = true),
            row(2, "bob", pinned = true),
            row(3, "carol"),
        )
        val s = sectionChatList(rows, friends = setOf("alice", "carol"), fools = setOf("bob"))
        assertEquals(listOf(1L, 2L), s.pinned.map { it.bufferId })
        assertEquals(listOf(3L), s.friends.map { it.bufferId })
        assertEquals(emptyList<Long>(), s.fools.map { it.bufferId })
        assertTrue(isFriendQuery(s.pinned.single { it.bufferId == 1L }, setOf("alice", "carol")))
    }

    @Test
    fun `classification is case-insensitive via normalizeNick`() {
        val s = sectionChatList(listOf(row(1, "Alice")), friends = setOf("alice"), fools = emptySet())
        assertEquals(listOf(1L), s.friends.map { it.bufferId })
    }

    @Test
    fun `tiers preserve activity order and have global priority`() {
        // Input is descending activity. Tiering may move a row ahead of a newer lower-priority
        // row, but must never reorder two rows that remain in the same tier.
        val rows = listOf(
            row(10, "pinned-regular", pinned = true),
            row(11, "alice", pinned = true),
            row(12, "bob", pinned = true),
            row(20, "regular-newer"),
            row(21, "carol"),
            row(22, "regular-older"),
            row(23, "dave"),
            row(24, "eve"),
        )
        val s = sectionChatList(
            rows,
            friends = setOf("alice", "carol", "dave"),
            fools = setOf("bob", "eve"),
        )

        assertEquals(listOf(10L, 11L, 12L), s.pinned.map { it.bufferId })
        assertEquals(listOf(21L, 23L), s.friends.map { it.bufferId })
        assertEquals(listOf(20L, 22L), s.regular.map { it.bufferId })
        assertEquals(listOf(24L), s.fools.map { it.bufferId })
        assertEquals(
            listOf(10L, 11L, 12L, 21L, 23L, 20L, 22L, 24L),
            (s.pinned + s.friends + s.regular + s.fools).map { it.bufferId },
        )
    }

    @Test
    fun `empty friends and fools leaves everything regular`() {
        val rows = listOf(row(1, "alice"), row(2, "#chan", type = BufferType.CHANNEL))
        val s = sectionChatList(rows, friends = emptySet(), fools = emptySet())
        assertEquals(listOf(1L, 2L), s.regular.map { it.bufferId })
        assertEquals(emptyList<Long>(), s.friends + s.fools)
    }

    @Test
    fun `recent header appears only after pinned or friend rows`() {
        assertFalse(
            sectionChatList(
                rows = listOf(row(1, "regular")),
                friends = emptySet(),
                fools = emptySet(),
            ).showRecentHeader,
        )
        assertTrue(
            sectionChatList(
                rows = listOf(row(1, "pinned", pinned = true), row(2, "regular")),
                friends = emptySet(),
                fools = emptySet(),
            ).showRecentHeader,
        )
        assertTrue(
            sectionChatList(
                rows = listOf(row(1, "alice"), row(2, "regular")),
                friends = setOf("alice"),
                fools = emptySet(),
            ).showRecentHeader,
        )
        assertFalse(
            sectionChatList(
                rows = listOf(row(1, "alice")),
                friends = setOf("alice"),
                fools = emptySet(),
            ).showRecentHeader,
        )
    }
}
