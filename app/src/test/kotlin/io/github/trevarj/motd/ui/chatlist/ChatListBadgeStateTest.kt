package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListBadgeStateTest {
    @Test
    fun muted_row_uses_one_subdued_total_activity_badge() {
        val state = chatListBadgeState(row(muted = true, unread = 8, mentions = 3))

        assertEquals(ChatListBadgeState(mutedActivity = 8), state)
    }

    @Test
    fun unmuted_row_keeps_distinct_mention_and_unread_badges() {
        val state = chatListBadgeState(row(muted = false, unread = 8, mentions = 3))

        assertEquals(ChatListBadgeState(mentions = 3, unread = 8), state)
    }

    private fun row(muted: Boolean, unread: Int, mentions: Int) = ChatListRow(
        bufferId = 1,
        networkId = 1,
        networkName = "Libera",
        displayName = "#motd",
        type = BufferType.CHANNEL,
        pinned = false,
        muted = muted,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = null,
        unreadCount = unread,
        mentionCount = mentions,
    )
}
