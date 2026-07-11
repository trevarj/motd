package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure wanted-set computation behind [ConnectionManagerImpl.reconcile]
 * (plans/16 §4). Verifies that sticky user intent overrides `autoConnect` in both directions and
 * that orphan BOUNCER_CHILD rows are excluded.
 */
class ConnectionIntentsTest {

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
    fun `autoConnect default with no intents`() {
        val all = listOf(net(1, autoConnect = true), net(2, autoConnect = false))
        assertEquals(setOf(1L), wantedNetworkIds(all, emptyMap()))
    }

    @Test
    fun `force-connect of autoConnect=false survives a reconcile input`() {
        val all = listOf(net(1, autoConnect = false))
        // User pressed Connect: intent true overrides autoConnect=false.
        assertEquals(setOf(1L), wantedNetworkIds(all, mapOf(1L to true)))
    }

    @Test
    fun `force-disconnect of autoConnect=true survives a reconcile input`() {
        val all = listOf(net(1, autoConnect = true))
        // User pressed Disconnect: intent false overrides autoConnect=true.
        assertEquals(emptySet<Long>(), wantedNetworkIds(all, mapOf(1L to false)))
    }

    @Test
    fun `wanted set collapses duplicate ids to one actor key`() {
        // reconcile keys actors by networkId and iterates the wanted SET, so even if two rows
        // carried the same id (defensive), only one actor would ever be ensured for it.
        val all = listOf(net(1, autoConnect = true), net(1, autoConnect = true))
        assertEquals(setOf(1L), wantedNetworkIds(all, emptyMap()))
    }

    @Test
    fun `orphan BOUNCER_CHILD is excluded even when wanted`() {
        val orphan = net(1, autoConnect = true, role = NetworkRole.BOUNCER_CHILD, parentId = null)
        val bound = net(2, autoConnect = true, role = NetworkRole.BOUNCER_CHILD, parentId = 9L)
        val states = mapOf(9L to IrcClientState.Ready("motd", emptySet(), emptyMap()))
        assertEquals(setOf(2L), wantedNetworkIds(listOf(orphan, bound), emptyMap(), states))
        // An explicit force-connect still cannot resurrect an orphan child.
        assertEquals(setOf(2L), wantedNetworkIds(listOf(orphan, bound), mapOf(1L to true), states))
    }

    @Test
    fun `bouncer child waits until parent root is ready`() {
        val root = net(1, autoConnect = true, role = NetworkRole.BOUNCER_ROOT)
        val child = net(2, autoConnect = true, role = NetworkRole.BOUNCER_CHILD, parentId = 1L)

        assertEquals(setOf(1L), wantedNetworkIds(listOf(root, child), emptyMap()))
        assertEquals(
            setOf(1L, 2L),
            wantedNetworkIds(
                listOf(root, child),
                emptyMap(),
                states = mapOf(1L to IrcClientState.Ready("motd", emptySet(), emptyMap())),
            ),
        )
    }
}
