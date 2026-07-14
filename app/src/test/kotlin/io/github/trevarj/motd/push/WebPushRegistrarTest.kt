package io.github.trevarj.motd.push

import io.github.trevarj.motd.data.prefs.PushKeys
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPushRegistrarTest {

    private class FakePushHealthStore : PushHealthStore {
        private val values = MutableStateFlow<Map<Long, NetworkPushHealth>>(emptyMap())
        override val health: Flow<Map<Long, NetworkPushHealth>> = values
        override suspend fun snapshot() = values.value
        override suspend fun requestingEndpoint(networkId: Long) {
            values.value += networkId to NetworkPushHealth(
                registrationState = PushRegistrationState.WAITING_FOR_ENDPOINT,
            )
        }
        override suspend fun endpointReceived(networkId: Long, endpoint: String) {
            values.value += networkId to NetworkPushHealth(
                endpointFingerprint = fingerprintEndpoint(endpoint),
                registrationState = PushRegistrationState.WAITING_FOR_SERVER,
            )
        }
        override suspend fun waitingForServer(networkId: Long) {
            values.value += networkId to (values.value[networkId] ?: NetworkPushHealth()).copy(
                registrationState = PushRegistrationState.WAITING_FOR_SERVER,
            )
        }
        override suspend fun capability(networkId: Long, supported: Boolean) = Unit
        override suspend fun verifying(networkId: Long) = Unit
        override suspend fun registered(networkId: Long) = Unit
        override suspend fun probeDelivered(networkId: Long) = Unit
        override suspend fun messageDelivered(networkId: Long) = Unit
        override suspend fun failed(networkId: Long, code: String) = Unit
        override suspend fun warning(networkId: Long, code: String) = Unit
        override suspend fun clear(networkId: Long) { values.value -= networkId }
        override suspend fun retain(networkIds: Set<Long>) { values.value = values.value.filterKeys { it in networkIds } }
    }

    /** In-memory PushPrefs fake (per-network only). */
    private class FakePushPrefs : PushPrefs {
        var keys: PushKeys? = null
        private val perNetwork = mutableMapOf<Long, String>()
        override suspend fun endpoints(): Map<Long, String> = perNetwork.toMap()
        override suspend fun endpointFor(networkId: Long): String? = perNetwork[networkId]
        override suspend fun setEndpointFor(networkId: Long, endpoint: String?) {
            if (endpoint == null) perNetwork.remove(networkId) else perNetwork[networkId] = endpoint
        }
        override suspend fun clearEndpoints() { perNetwork.clear() }
        override suspend fun keys(): PushKeys? = keys
        override suspend fun setKeys(keys: PushKeys) { this.keys = keys }
    }

    /** ConnectionManager fake with no live clients (register loop is a no-op). */
    private open class FakeConnectionManager : ConnectionManager {
        override val connectionStates: StateFlow<Map<Long, IrcClientState>> = MutableStateFlow(emptyMap())
        override fun clientFor(networkId: Long): IrcClient? = null
        override suspend fun startAll() = Unit
        override suspend fun stopAll() = Unit
        override suspend fun connect(networkId: Long) = Unit
        override suspend fun disconnect(networkId: Long) = Unit
        override suspend fun reconnectStale() = Unit
        override suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String?) = Unit
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long, reason: String?) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0L
        override suspend fun ensureServerBuffer(networkId: Long): Long = 0L
        override suspend fun markRead(bufferId: Long, upToTime: Long) = Unit
        override suspend fun evaluatePushMode() = Unit
        override val certPrompts: StateFlow<List<io.github.trevarj.motd.service.CertPrompt>> =
            MutableStateFlow(emptyList())
        override suspend fun trustCert(prompt: io.github.trevarj.motd.service.CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: io.github.trevarj.motd.service.CertPrompt) = Unit
    }

    @Test
    fun loadOrCreateKeys_generates_and_persists_once() = runTest {
        val prefs = FakePushPrefs()
        val registrar = WebPushRegistrar(prefs, FakeConnectionManager(), FakePushHealthStore())

        val first = registrar.loadOrCreateKeys()
        assertNotNull("keys persisted after first load", prefs.keys)
        assertEquals(65, first.publicUncompressed.size)
        assertEquals(16, first.auth.size)

        val second = registrar.loadOrCreateKeys()
        assertTrue(first.publicUncompressed.contentEquals(second.publicUncompressed))
        assertTrue(first.privateKey.contentEquals(second.privateKey))
        assertTrue(first.auth.contentEquals(second.auth))
    }

    @Test
    fun onNewEndpoint_persists_endpoint_keyed_by_network_and_keys() = runTest {
        val prefs = FakePushPrefs()
        val health = FakePushHealthStore()
        val registrar = WebPushRegistrar(prefs, FakeConnectionManager(), health)

        val sent = registrar.onNewEndpoint(7L, "https://push.example/abc")

        assertFalse("no live client => nothing registered", sent)
        assertEquals("https://push.example/abc", prefs.endpointFor(7L))
        assertNull("endpoint keyed by network id only", prefs.endpointFor(8L))
        assertNotNull(prefs.keys)
        assertEquals(
            PushRegistrationState.WAITING_FOR_SERVER,
            health.snapshot()[7L]?.registrationState,
        )
    }

    @Test
    fun onUnregisteredNetwork_drops_only_that_network_and_keeps_keys() = runTest {
        val prefs = FakePushPrefs()
        val registrar = WebPushRegistrar(prefs, FakeConnectionManager(), FakePushHealthStore())
        registrar.onNewEndpoint(1L, "https://push.example/one")
        registrar.onNewEndpoint(2L, "https://push.example/two")
        val keysBefore = prefs.keys

        registrar.onUnregisteredNetwork(1L)

        assertNull("unregistered network endpoint dropped", prefs.endpointFor(1L))
        assertEquals("other network endpoint retained", "https://push.example/two", prefs.endpointFor(2L))
        assertEquals("key material retained across unregister", keysBefore, prefs.keys)
    }

    @Test
    fun reRegisterIfNeeded_returns_false_when_no_endpoint() = runTest {
        val prefs = FakePushPrefs()
        val registrar = WebPushRegistrar(prefs, FakeConnectionManager(), FakePushHealthStore())
        assertFalse(registrar.reRegisterIfNeeded(99L))
    }
}
