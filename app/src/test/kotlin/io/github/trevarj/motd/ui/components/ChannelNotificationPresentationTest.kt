package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.service.ChannelWatchState
import io.github.trevarj.motd.service.NotificationConfig
import io.github.trevarj.motd.service.NotificationMode
import io.github.trevarj.motd.service.NotificationScope
import io.github.trevarj.motd.service.NotificationSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelNotificationPresentationTest {
    @Test
    fun `channel policy wins then clearing overrides reveals the exact parent`() {
        val config =
            NotificationConfig(
                global = NotificationMode.MENTIONS,
                servers = mapOf(1L to NotificationMode.ALL),
                channels = mapOf(7L to NotificationMode.OFF),
            )
        val channel = present(config)
        assertEquals(NotificationMode.OFF, channel.mode)
        assertEquals(NotificationMode.OFF, channel.channelOverride)
        assertEquals(NotificationMode.ALL, channel.parentMode)
        assertEquals(NotificationScope.CHANNEL, channel.source)

        val server = present(config.copy(channels = emptyMap()))
        assertEquals(NotificationMode.ALL, server.mode)
        assertNull(server.channelOverride)
        assertEquals(NotificationScope.SERVER, server.source)

        val otherServer =
            deriveChannelNotificationPresentation(
                NotificationSettingsState.Ready(config),
                networkId = 2,
                bufferId = 8,
                muted = false,
                nowMillis = 0,
            )
        assertEquals(NotificationMode.MENTIONS, otherServer.mode)
        assertEquals(NotificationMode.MENTIONS, otherServer.parentMode)
        assertEquals(NotificationScope.GLOBAL, otherServer.source)
    }

    @Test
    fun `watch and chat mute remain separate from an off channel policy`() {
        val config = NotificationConfig(channels = mapOf(7L to NotificationMode.OFF), watches = mapOf(7L to 900_000))
        val unmuted = present(config)
        val muted = present(config, muted = true)

        assertEquals(NotificationMode.OFF, unmuted.mode)
        assertEquals(NotificationScope.CHANNEL, unmuted.source)
        assertEquals(ChannelWatchState(7, 900_000), unmuted.watch)
        assertEquals(15, unmuted.minutesLeft)
        assertFalse(unmuted.muted)
        assertEquals(unmuted.copy(muted = true), muted)
    }

    @Test
    fun `another channel watch and expired watches do not affect the inherited policy`() {
        val config =
            NotificationConfig(
                servers = mapOf(1L to NotificationMode.ALL),
                watches = mapOf(7L to 1_000, 9L to Long.MAX_VALUE),
            )
        val expired = present(config, muted = true, nowMillis = 1_000)
        assertNull(expired.watch)
        assertNull(expired.minutesLeft)
        assertEquals(NotificationMode.ALL, expired.mode)
        assertEquals(NotificationScope.SERVER, expired.source)
        assertTrue(expired.muted)
    }

    @Test
    fun `forever has no countdown even at the maximum clock value`() {
        val forever = present(NotificationConfig(watches = mapOf(7L to Long.MAX_VALUE)), nowMillis = Long.MAX_VALUE)
        assertEquals(ChannelWatchState(7, Long.MAX_VALUE), forever.watch)
        assertNull(forever.minutesLeft)
        assertEquals(NotificationMode.MENTIONS, forever.mode)
    }

    @Test
    fun `finite minutes round up until the deadline without overflowing`() {
        val config = NotificationConfig(watches = mapOf(7L to 90_000))
        assertEquals(2, present(config).minutesLeft)
        assertEquals(1, present(config, nowMillis = 30_000).minutesLeft)
        assertEquals(1, present(config, nowMillis = 89_999).minutesLeft)
        assertNull(present(config, nowMillis = 90_000).watch)
        assertEquals(Int.MAX_VALUE, present(NotificationConfig(watches = mapOf(7L to Long.MAX_VALUE - 1))).minutesLeft)
    }

    @Test
    fun `loading and unavailable have no editable policy or watch`() {
        val loading = deriveChannelNotificationPresentation(NotificationSettingsState.Loading, 1, 7, muted = true, nowMillis = 0)
        val unavailable = deriveChannelNotificationPresentation(NotificationSettingsState.Unavailable, 1, 7, muted = true, nowMillis = 0)
        assertTrue(loading.loading)
        assertFalse(loading.available)
        assertFalse(unavailable.loading)
        assertFalse(unavailable.available)
        assertNull(unavailable.channelOverride)
        assertNull(unavailable.watch)
        assertEquals(loading.copy(loading = false), unavailable)

        val ready = present(NotificationConfig())
        assertFalse(ready.loading)
        assertTrue(ready.available)
    }

    private fun present(
        config: NotificationConfig,
        muted: Boolean = false,
        nowMillis: Long = 0,
    ) = deriveChannelNotificationPresentation(NotificationSettingsState.Ready(config), 1, 7, muted, nowMillis)
}
