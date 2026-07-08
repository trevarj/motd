package io.github.trevarj.motd.push

import io.github.trevarj.motd.data.prefs.PushKeys
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPushRegistrarTest {

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
        override suspend fun sendMessage(bufferId: Long, text: String, replyToMsgid: String?) = Unit
        override suspend fun sendTyping(bufferId: Long, state: String) = Unit
        override suspend fun sendReact(bufferId: Long, msgid: String, emoji: String) = Unit
        override suspend fun joinChannel(networkId: Long, channel: String) = Unit
        override suspend fun partChannel(bufferId: Long) = Unit
        override suspend fun ensureQueryBuffer(networkId: Long, nick: String): Long = 0L
        override suspend fun markRead(bufferId: Long, upToTime: Long) = Unit
        override suspend fun evaluatePushMode() = Unit
    }

    @Test
    fun loadOrCreateKeys_generates_and_persists_once() = runTest {
        val prefs = FakePushPrefs()
        val registrar = WebPushRegistrar(prefs, FakeConnectionManager())

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
        val registrar = WebPushRegistrar(prefs, FakeConnectionManager())

        val sent = registrar.onNewEndpoint(7L, "https://push.example/abc")

        assertFalse("no live client => nothing registered", sent)
        assertEquals("https://push.example/abc", prefs.endpointFor(7L))
        assertNull("endpoint keyed by network id only", prefs.endpointFor(8L))
        assertNotNull(prefs.keys)
    }

    @Test
    fun onUnregisteredNetwork_drops_only_that_network_and_keeps_keys() = runTest {
        val prefs = FakePushPrefs()
        val registrar = WebPushRegistrar(prefs, FakeConnectionManager())
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
        val registrar = WebPushRegistrar(prefs, FakeConnectionManager())
        assertFalse(registrar.reRegisterIfNeeded(99L))
    }
}
