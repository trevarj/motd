package io.github.trevarj.motd.sidecar

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ConnectionTransport
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.repo.networkIdentityKey
import io.github.trevarj.motd.service.networkFingerprint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SidecarConnectionPolicyTest {
    @Test
    fun providerAccountParticipatesInConnectionAndRepositoryIdentity() {
        val first = sidecar("account-a")
        val second = sidecar("account-b")

        assertNotEquals(networkFingerprint(first), networkFingerprint(second))
        assertNotEquals(networkIdentityKey(first), networkIdentityKey(second))
    }

    @Test
    fun friendlyRoomNameNeverReplacesWireTarget() {
        val room =
            BufferEntity(
                networkId = 1,
                name = "u_a",
                displayName = "Alice Smith",
                wireTarget = "u_a",
                type = BufferType.QUERY,
            )

        assertEquals("u_a", room.ircTarget)
    }

    private fun sidecar(accountId: String) =
        NetworkEntity(
            name = accountId,
            role = NetworkRole.DIRECT,
            host = "sidecar",
            port = 0,
            tls = false,
            nick = "motd",
            username = "motd",
            realname = "motd",
            connectionTransport = ConnectionTransport.SIDECAR,
            sidecarPackage = "provider.example",
            sidecarService = "provider.example.Service",
            sidecarAccountId = accountId,
        )
}
