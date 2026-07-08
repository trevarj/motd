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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPushRegistrarTest {

    /** In-memory PushPrefs fake. */
    private class FakePushPrefs : PushPrefs {
        var endpoint: String? = null
        var keys: PushKeys? = null
        override suspend fun endpoint(): String? = endpoint
        override suspend fun setEndpoint(endpoint: String?) { this.endpoint = endpoint }
        override suspend fun keys(): PushKeys? = keys
        override suspend fun setKeys(keys: PushKeys) { this.keys = keys }
    }

    /** ConnectionManager fake with no live clients (register loop is a no-op). */
    private class FakeConnectionManager : ConnectionManager {
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
        // Second load returns the persisted material unchanged.
        assertTrue(first.publicUncompressed.contentEquals(second.publicUncompressed))
        assertTrue(first.privateKey.contentEquals(second.privateKey))
        assertTrue(first.auth.contentEquals(second.auth))
    }

    @Test
    fun onNewEndpoint_persists_endpoint_and_keys() = runTest {
        val prefs = FakePushPrefs()
        val registrar = WebPushRegistrar(prefs, FakeConnectionManager())

        val sent = registrar.onNewEndpoint("https://push.example/abc")

        assertEquals("no live clients => nothing registered", 0, sent)
        assertEquals("https://push.example/abc", prefs.endpoint)
        assertNotNull(prefs.keys)
    }

    @Test
    fun onUnregistered_clears_endpoint_but_keeps_keys() = runTest {
        val prefs = FakePushPrefs()
        val registrar = WebPushRegistrar(prefs, FakeConnectionManager())
        registrar.onNewEndpoint("https://push.example/abc")
        val keysBefore = prefs.keys

        registrar.onUnregistered()

        assertNull("endpoint cleared on unregister", prefs.endpoint)
        assertEquals("key material retained across unregister", keysBefore, prefs.keys)
    }
}
