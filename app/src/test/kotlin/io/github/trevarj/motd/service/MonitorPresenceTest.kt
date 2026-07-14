package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import org.junit.Assert.assertEquals
import org.junit.Test

class MonitorPresenceTest {
    @Test fun `friends win bounded slots before ordered query peers`() {
        val selection = selectMonitorTargets(
            friends = setOf("Zed", "alice"),
            queryRows = listOf(
                query("bob", pinned = false, activity = 200),
                query("carol", pinned = true, activity = 100),
                query("ALICE", pinned = true, activity = 300),
            ),
            limit = 3,
            normalize = String::lowercase,
        )

        assertEquals(listOf("alice", "Zed", "carol"), selection.selected)
        assertEquals(listOf("alice", "Zed", "carol", "bob"), selection.allDesired)
    }

    private fun query(nick: String, pinned: Boolean, activity: Long) = ChatListRow(
        bufferId = activity,
        networkId = 1,
        networkName = "network",
        displayName = nick,
        type = BufferType.QUERY,
        pinned = pinned,
        muted = false,
        lastMessageText = null,
        lastMessageSender = null,
        lastMessageTime = activity,
        unreadCount = 0,
        mentionCount = 0,
    )
}
