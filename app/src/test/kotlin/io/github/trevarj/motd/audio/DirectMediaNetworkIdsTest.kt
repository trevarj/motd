package io.github.trevarj.motd.audio

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DirectMediaNetworkIdsTest {
    @Test
    fun childUsesParentTransportAndOptInAllowsKnownProxiedNetworks() {
        val direct = network(1, NetworkRole.DIRECT)
        val proxiedRoot = network(2, NetworkRole.BOUNCER_ROOT, obfsMode = ObfsMode.TOR)
        val proxiedChild = network(3, NetworkRole.BOUNCER_CHILD, parentId = 2)
        val orphanChild = network(4, NetworkRole.BOUNCER_CHILD, parentId = 99)
        val networks = listOf(direct, proxiedRoot, proxiedChild, orphanChild)

        assertEquals(setOf(1L, 4L), directMediaAllowedNetworkIds(networks, false))
        assertEquals(setOf(1L, 2L, 3L, 4L), directMediaAllowedNetworkIds(networks, true))
    }

    @Test
    fun sameIdParentTransportChangeRevokesDirectMediaUntilOptIn() {
        val parent = network(1, NetworkRole.BOUNCER_ROOT)
        val child = network(2, NetworkRole.BOUNCER_CHILD, parentId = parent.id)
        assertEquals(setOf(parent.id, child.id), directMediaAllowedNetworkIds(listOf(parent, child), false))

        val networks = listOf(parent.copy(obfsMode = ObfsMode.EMBEDDED_REALITY), child)
        assertEquals(emptySet<Long>(), directMediaAllowedNetworkIds(networks, false))
        val optedIn = directMediaAllowedNetworkIds(networks, true)
        assertEquals(setOf(parent.id, child.id), optedIn)
        assertFalse(9_999L in optedIn)
    }

    private fun network(
        id: Long,
        role: NetworkRole,
        parentId: Long? = null,
        obfsMode: ObfsMode? = null,
    ) = NetworkEntity(
        id = id,
        name = "network-$id",
        role = role,
        parentId = parentId,
        host = "irc.example.test",
        port = 6697,
        nick = "tester",
        username = "tester",
        realname = "Tester",
        obfsMode = obfsMode,
    )
}
