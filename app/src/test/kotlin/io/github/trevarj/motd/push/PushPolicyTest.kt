package io.github.trevarj.motd.push

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushPolicyTest {
    private fun network(id: Long, role: NetworkRole, autoConnect: Boolean = true) = NetworkEntity(
        id = id,
        name = "network-$id",
        role = role,
        host = "localhost",
        port = 6697,
        nick = "motd",
        username = "motd",
        realname = "motd",
        autoConnect = autoConnect,
    )

    @Test
    fun endpoint_change_invalidates_verified_health() {
        val first = "https://push.example/first"
        val health = NetworkPushHealth(
            endpointFingerprint = fingerprintEndpoint(first),
            capability = PushCapability.SUPPORTED,
            registrationState = PushRegistrationState.ACTIVE,
        )

        assertTrue(health.protects(first))
        assertFalse(health.protects("https://push.example/second"))
        assertFalse(health.protects(null))
    }

    @Test
    fun hybrid_policy_suspends_root_and_verified_child_only() {
        val root = network(1, NetworkRole.BOUNCER_ROOT)
        val verified = network(2, NetworkRole.BOUNCER_CHILD)
        val unsupported = network(3, NetworkRole.DIRECT)
        val endpoint = "https://push.example/verified"
        val health = mapOf(
            2L to NetworkPushHealth(
                endpointFingerprint = fingerprintEndpoint(endpoint),
                capability = PushCapability.SUPPORTED,
                registrationState = PushRegistrationState.ACTIVE,
            ),
            3L to NetworkPushHealth(
                capability = PushCapability.UNSUPPORTED,
                registrationState = PushRegistrationState.FALLBACK,
            ),
        )

        val suspended = pushSuspendedNetworkIds(
            networks = listOf(root, verified, unsupported),
            wantedIds = setOf(1L, 2L, 3L),
            endpoints = mapOf(2L to endpoint),
            health = health,
        )

        assertEquals(setOf(1L, 2L), suspended)
        assertEquals(
            setOf(3L),
            socketFallbackNetworkIds(listOf(root, verified, unsupported), mapOf(2L to endpoint), health),
        )
    }

    @Test
    fun root_is_never_a_unifiedpush_instance() {
        val networks = listOf(
            network(1, NetworkRole.BOUNCER_ROOT),
            network(2, NetworkRole.BOUNCER_CHILD),
            network(3, NetworkRole.DIRECT, autoConnect = false),
        )
        assertEquals(setOf(2L), pushEligibleNetworkIds(networks))
    }
}
