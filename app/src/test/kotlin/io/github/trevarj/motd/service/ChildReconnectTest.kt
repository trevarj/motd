package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the "reconnect bouncer children when the root reconnects" behavior. When a
 * BOUNCER_ROOT transitions into Ready, [ConnectionManagerImpl.onReady] iterates
 * [childrenToReconnect] and force-reconnects each returned child via `connect(id)` — the same
 * drop-and-rebuild path used for trust reconnects. A child's actor dies while the root is down
 * (the bound transport is gone) and a plain reconcile won't rebuild the parked Failed actor, so an
 * explicit connect() is required. Pure-function testing style matching [ConnectionIntentsTest] /
 * [TrustReconnectTest].
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
    fun `wanted children of the root are reconnected`() {
        val root = net(1, role = NetworkRole.BOUNCER_ROOT)
        val childA = net(2, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        val childB = net(3, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        assertEquals(
            setOf(2L, 3L),
            childrenToReconnect(1L, listOf(root, childA, childB), emptyMap()),
        )
    }

    @Test
    fun `children of a different root are excluded`() {
        val ownChild = net(2, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        val otherChild = net(3, role = NetworkRole.BOUNCER_CHILD, parentId = 9L)
        assertEquals(
            setOf(2L),
            childrenToReconnect(1L, listOf(ownChild, otherChild), emptyMap()),
        )
    }

    @Test
    fun `user-disconnected child (sticky intent false) is not resurrected`() {
        val childA = net(2, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        val childB = net(3, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        // User explicitly disconnected child 2: intent false overrides autoConnect=true.
        assertEquals(
            setOf(3L),
            childrenToReconnect(1L, listOf(childA, childB), mapOf(2L to false)),
        )
    }

    @Test
    fun `autoConnect=false child is skipped but a sticky force-connect is honored`() {
        val autoOff = net(2, autoConnect = false, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        val forced = net(3, autoConnect = false, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)
        // autoConnect=false and no intent -> skipped; force-connect intent true -> reconnected.
        assertEquals(
            setOf(3L),
            childrenToReconnect(1L, listOf(autoOff, forced), mapOf(3L to true)),
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
            childrenToReconnect(1L, listOf(root, direct, directWithParent), emptyMap()),
        )
    }
}
