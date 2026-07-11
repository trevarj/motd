package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the "reconnect bouncer children when the root reconnects" behavior. When a
 * BOUNCER_ROOT transitions into Ready, [ConnectionManagerImpl.onReady] only rebuilds wanted
 * children which are absent, dead, or terminally disconnected/failed. A child's actor can die
 * while the root is down, while rebuilding a live Connecting/Registering/Ready actor races or
 * interrupts its bouncer connection.
 */
class ChildReconnectTest {

    private fun net(
        id: Long,
        autoConnect: Boolean = true,
        role: NetworkRole = NetworkRole.DIRECT,
        parentId: Long? = null,
    ) = NetworkEntity(
        id = id,
        name = "net$id",
        role = role,
        parentId = parentId,
        host = "irc.example.org",
        port = 6697,
        nick = "motd",
        username = "motd",
        realname = "MOTD",
        autoConnect = autoConnect,
    )

    @Test
    fun `healthy Ready child is not restarted`() {
        val root = net(1, role = NetworkRole.BOUNCER_ROOT)
        val childA = net(2, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        val childB = net(3, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        assertEquals(
            emptySet<Long>(),
            childrenNeedingReconnect(
                1L, listOf(root, childA, childB), emptyMap(),
                actorAlive = mapOf(2L to true, 3L to true),
                states = mapOf(2L to ready(), 3L to ready()),
            ),
        )
    }

    @Test
    fun `live child still connecting or registering is not restarted`() {
        val connecting = net(2, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        val registering = net(3, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        assertEquals(
            emptySet<Long>(),
            childrenNeedingReconnect(
                1L, listOf(connecting, registering), emptyMap(),
                actorAlive = mapOf(2L to true, 3L to true),
                states = mapOf(2L to IrcClientState.Connecting, 3L to IrcClientState.Registering),
            ),
        )
    }

    @Test
    fun `stale child is restarted but healthy sibling is preserved`() {
        val ownChild = net(2, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        val healthyChild = net(3, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        assertEquals(
            setOf(2L),
            childrenNeedingReconnect(
                1L, listOf(ownChild, healthyChild), emptyMap(),
                actorAlive = mapOf(2L to false, 3L to true),
                states = mapOf(2L to IrcClientState.Failed("root was down", fatal = false), 3L to ready()),
            ),
        )
    }

    @Test
    fun `user-disconnected child (sticky intent false) is not resurrected`() {
        val childA = net(2, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        val childB = net(3, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        // User explicitly disconnected child 2: intent false overrides autoConnect=true.
        assertEquals(
            setOf(3L),
            childrenNeedingReconnect(1L, listOf(childA, childB), mapOf(2L to false), emptyMap(), emptyMap()),
        )
    }

    @Test
    fun `autoConnect=false child is skipped but a sticky force-connect is honored`() {
        val autoOff = net(2, autoConnect = false, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        val forced = net(3, autoConnect = false, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        // autoConnect=false and no intent -> skipped; force-connect intent true -> reconnected.
        assertEquals(
            setOf(3L),
            childrenNeedingReconnect(1L, listOf(autoOff, forced), mapOf(3L to true), emptyMap(), emptyMap()),
        )
    }

    @Test
    fun `the root itself and DIRECT networks are never treated as children`() {
        val root = net(1, role = NetworkRole.BOUNCER_ROOT)
        val direct = net(4, role = NetworkRole.DIRECT)
        // A DIRECT network that happens to carry parentId is still not a BOUNCER_CHILD.
        val directWithParent = net(5, role = NetworkRole.DIRECT, parentId = 1L)
        assertEquals(
            emptySet<Long>(),
            childrenNeedingReconnect(1L, listOf(root, direct, directWithParent), emptyMap(), emptyMap(), emptyMap()),
        )
    }

    private fun ready() = IrcClientState.Ready("motd", emptySet(), emptyMap())
}
