package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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

    @Test fun `fresh generation clears adds and snapshots while live changes use diffs`() {
        val previous = linkedMapOf("alice" to "Alice", "bob" to "Bob")
        val desired = linkedMapOf("bob" to "Bob", "carol" to "Carol")

        assertEquals(
            MonitorReconciliation(true, emptyList(), listOf("Bob", "Carol"), true),
            monitorReconciliation(previous, desired, fresh = true),
        )
        assertEquals(
            MonitorReconciliation(false, listOf("Alice"), listOf("Carol"), false),
            monitorReconciliation(previous, desired, fresh = false),
        )
    }

    @Test fun `presence tracks only desired identities and resets without false offline`() {
        val alice = PresenceKey(1, "alice")
        val bob = PresenceKey(1, "bob")
        val other = PresenceKey(2, "alice")
        var states = mapOf(alice to PresenceState.ONLINE, other to PresenceState.OFFLINE)

        states = presenceForDesired(states, 1, setOf("alice", "bob"))
        assertEquals(PresenceState.ONLINE, states[alice])
        assertEquals(PresenceState.UNKNOWN, states[bob])
        assertEquals(PresenceState.OFFLINE, states[other])

        states = presenceIfTracked(states, bob, PresenceState.OFFLINE)
        assertEquals(PresenceState.OFFLINE, states[bob])
        val untracked = PresenceKey(1, "carol")
        assertSame(states, presenceIfTracked(states, untracked, PresenceState.ONLINE))

        states = invalidatePresenceState(states, 1)
        assertEquals(PresenceState.UNKNOWN, states[alice])
        assertEquals(PresenceState.UNKNOWN, states[bob])
        assertEquals(PresenceState.OFFLINE, states[other])
    }

    @Test fun `nick change rekeys ephemeral state within one connection`() {
        val old = PresenceKey(1, "old")
        val renamed = PresenceKey(1, "new")
        val states = rekeyPresenceState(mapOf(old to PresenceState.ONLINE), old, renamed)

        assertEquals(null, states[old])
        assertEquals(PresenceState.ONLINE, states[renamed])
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
