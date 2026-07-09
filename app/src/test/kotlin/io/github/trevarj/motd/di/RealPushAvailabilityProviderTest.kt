package io.github.trevarj.motd.di

import app.cash.turbine.test
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.push.WebPushRegistrar
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Availability truth table (distributor present/absent × bouncer webpush cap present/absent) and
 * reactivity: selecting UNIFIED_PUSH is gated on the bouncer cap only; a missing distributor is
 * surfaced as guidance, and the toggle enables live once a connection reaches Ready with the cap.
 */
class RealPushAvailabilityProviderTest {

    private val readyWithCap =
        IrcClientState.Ready(nick = "me", caps = setOf(WebPushRegistrar.WEBPUSH_CAP, "sasl"), isupport = emptyMap())
    private val readyNoCap =
        IrcClientState.Ready(nick = "me", caps = setOf("sasl"), isupport = emptyMap())

    private class FakeConnectionManager(
        override val connectionStates: MutableStateFlow<Map<Long, IrcClientState>>,
    ) : ConnectionManager {
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
        override val certPrompts: StateFlow<List<CertPrompt>> = MutableStateFlow(emptyList())
        override suspend fun trustCert(prompt: CertPrompt) = Unit
        override fun dismissCertPrompt(prompt: CertPrompt) = Unit
    }

    private fun provider(
        states: MutableStateFlow<Map<Long, IrcClientState>>,
        hasDistributor: Boolean,
    ) = RealPushAvailabilityProvider(FakeConnectionManager(states)) { hasDistributor }

    @Test
    fun cap_and_distributor_present_is_selectable_without_guidance() = runTest {
        val states = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to readyWithCap))
        provider(states, hasDistributor = true).availability().test {
            val a = awaitItem()
            assertTrue("selectable when cap + distributor", a.selectable)
            assertFalse("no guidance when distributor present", a.needsDistributor)
        }
    }

    @Test
    fun cap_present_but_no_distributor_is_selectable_with_guidance() = runTest {
        val states = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to readyWithCap))
        provider(states, hasDistributor = false).availability().test {
            val a = awaitItem()
            assertTrue("still selectable so registration self-heals", a.selectable)
            assertTrue("guidance shown to install a distributor", a.needsDistributor)
        }
    }

    @Test
    fun no_cap_is_not_selectable_regardless_of_distributor() = runTest {
        val states = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to readyNoCap))
        provider(states, hasDistributor = true).availability().test {
            val a = awaitItem()
            assertFalse("not selectable without bouncer webpush", a.selectable)
            assertFalse(a.needsDistributor)
        }
    }

    @Test
    fun disconnected_state_is_not_selectable() = runTest {
        val states = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to IrcClientState.Connecting))
        provider(states, hasDistributor = true).availability().test {
            assertFalse(awaitItem().selectable)
        }
    }

    @Test
    fun becomes_selectable_reactively_when_bouncer_reaches_ready() = runTest {
        // Settings opens before the soju root is Ready: starts unavailable, flips live on Ready.
        val states = MutableStateFlow<Map<Long, IrcClientState>>(mapOf(1L to IrcClientState.Registering))
        provider(states, hasDistributor = true).availability().test {
            assertFalse("stale/false before Ready", awaitItem().selectable)
            states.value = mapOf(1L to readyWithCap)
            assertTrue("enables once bouncer advertises webpush", awaitItem().selectable)
        }
    }
}
