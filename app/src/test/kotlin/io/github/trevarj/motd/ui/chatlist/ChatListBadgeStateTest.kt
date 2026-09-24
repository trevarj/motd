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

    @Test
    fun incomplete_positive_counts_are_exposed_as_lower_bounds() {
        val state =
            chatListBadgeState(
                row(muted = false, unread = 8, mentions = 3).copy(
                    unreadCountIncomplete = true,
                    mentionCountIncomplete = true,
                ),
            )

        assertEquals(
            ChatListBadgeState(
                mentions = 3,
                unread = 8,
                mentionsIncomplete = true,
                unreadIncomplete = true,
            ),
            state,
        )
    }

    @Test
    fun incomplete_zero_counts_use_a_history_cue_without_claiming_unread_activity() {
        val state =
            chatListBadgeState(
                row(muted = false, unread = 0, mentions = 0).copy(
                    unreadCountIncomplete = true,
                    mentionCountIncomplete = true,
                ),
            )

        assertEquals(
            ChatListBadgeState(
                mentionsIncomplete = true,
                unreadIncomplete = true,
                historyIncomplete = true,
            ),
            state,
        )
    }

    @Test
    fun advertised_activity_stands_in_for_a_count_the_device_does_not_have() {
        val state =
            chatListBadgeState(
                row(muted = false, unread = 0, mentions = 0).copy(advertisedUnread = true),
            )

        assertEquals(ChatListBadgeState(advertisedActivity = true), state)
    }

    @Test
    fun a_real_unread_count_replaces_the_advertised_cue() {
        // Once the rows are here the count is the better statement; showing both is noise.
        val state =
            chatListBadgeState(
                row(muted = false, unread = 2, mentions = 0).copy(advertisedUnread = true),
            )

        assertEquals(ChatListBadgeState(unread = 2), state)
    }

    @Test
    fun a_muted_row_is_not_told_about_activity_it_has_not_fetched() {
        val state =
            chatListBadgeState(
                row(muted = true, unread = 0, mentions = 0).copy(advertisedUnread = true),
            )

        assertEquals(ChatListBadgeState(), state)
    }

    private fun row(
        muted: Boolean,
        unread: Int,
        mentions: Int,
    ) = ChatListRow(
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
