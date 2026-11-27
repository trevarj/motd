package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.MonitorQueryRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MonitorPresenceTest {
    @Test
    fun `only a matching opted-in direct fallback can reserve a supported slot`() {
        val row =
            NetworkEntity(
                name = "test",
                role = NetworkRole.DIRECT,
                host = "localhost",
                port = 6667,
                nick = "N[ck",
                username = "u",
                realname = "r",
                autoRegainPreferredNick = true,
            )
        val ready = IrcClientState.Ready("N[ck_", emptySet(), mapOf("MONITOR" to "1"), preferredNickToRegain = "n{ck")

        fun candidate(
            network: NetworkEntity = row,
            state: IrcClientState.Ready = ready,
            znc: Set<Long>? = emptySet(),
        ) = preferredMonitorNick(network, state, znc, IrcIdentityRules()::normalize)

        assertEquals("n{ck", candidate())
        assertNull(candidate(row.copy(autoRegainPreferredNick = false)))
        assertNull(candidate(row.copy(nick = "changed")))
        assertNull(candidate(row.copy(role = NetworkRole.BOUNCER_CHILD)))
        assertNull(candidate(row.copy(role = NetworkRole.BOUNCER_ROOT)))
        assertNull(candidate(znc = setOf(row.id)))
        assertNull(candidate(znc = null))
        assertNull(candidate(state = ready.copy(preferredNickToRegain = null)))
        assertNull(candidate(state = ready.copy(isupport = ready.isupport + ("BOUNCER_NETID" to ""))))
        assertNull(candidate(state = ready.copy(isupport = emptyMap())))
        assertNull(candidate(state = ready.copy(isupport = mapOf("MONITOR" to "bad"))))
    }

    @Test
    fun `preferred identity takes one bounded slot across friend and query casemap aliases`() {
        val selection =
            selectMonitorTargets(
                preferredNick = "N[ck",
                friends = setOf("alice", "n{ck"),
                queryRows = listOf(query("N{CK", pinned = true, activity = 123)),
                limit = 1,
                normalize = IrcIdentityRules()::normalize,
            )
        assertEquals(listOf("N[ck"), selection.selected)
        assertEquals(listOf("N[ck", "alice"), selection.allDesired)
    }

    @Test fun `friends win bounded slots before ordered query peers`() {
        val selection =
            selectMonitorTargets(
                preferredNick = null,
                friends = setOf("Zed", "alice"),
                queryRows =
                    listOf(
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

    private fun query(
        nick: String,
        pinned: Boolean,
        activity: Long,
    ) = MonitorQueryRow(
        networkId = 1,
        displayName = nick,
        pinned = pinned,
        lastMessageTime = activity,
    )
}
