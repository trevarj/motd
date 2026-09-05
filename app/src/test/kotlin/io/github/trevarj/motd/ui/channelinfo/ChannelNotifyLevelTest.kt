package io.github.trevarj.motd.ui.channelinfo

import io.github.trevarj.motd.service.ChannelWatchState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Precedence for the channel-info notifications row: live watch > mute > mentions-only. */
class ChannelNotifyLevelTest {
    @Test
    fun `no watch reports mute or mentions only`() {
        assertEquals(ChannelNotifyLevel.MentionsOnly, deriveNotifyLevel(muted = false, watch = null, bufferId = 7, nowMillis = 0))
        assertEquals(ChannelNotifyLevel.Muted, deriveNotifyLevel(muted = true, watch = null, bufferId = 7, nowMillis = 0))
    }

    @Test
    fun `a watch on another buffer does not apply`() {
        val watch = ChannelWatchState(bufferId = 9, expiresAt = 600_000)
        assertEquals(ChannelNotifyLevel.Muted, deriveNotifyLevel(muted = true, watch = watch, bufferId = 7, nowMillis = 0))
    }

    @Test
    fun `an expired watch falls back to the buffer state`() {
        val watch = ChannelWatchState(bufferId = 7, expiresAt = 1_000)
        assertEquals(ChannelNotifyLevel.MentionsOnly, deriveNotifyLevel(muted = false, watch = watch, bufferId = 7, nowMillis = 1_000))
    }

    @Test
    fun `an active watch reports remaining minutes and mute override`() {
        val watch = ChannelWatchState(bufferId = 7, expiresAt = 900_000)
        assertEquals(
            ChannelNotifyLevel.All(minutesLeft = 15, overridesMute = false),
            deriveNotifyLevel(muted = false, watch = watch, bufferId = 7, nowMillis = 0),
        )
        assertEquals(
            ChannelNotifyLevel.All(minutesLeft = 15, overridesMute = true),
            deriveNotifyLevel(muted = true, watch = watch, bufferId = 7, nowMillis = 0),
        )
    }

    @Test
    fun `a forever watch reports no remaining minutes`() {
        val watch = ChannelWatchState(bufferId = 7, expiresAt = Long.MAX_VALUE)
        assertEquals(
            ChannelNotifyLevel.All(minutesLeft = null, overridesMute = false),
            deriveNotifyLevel(muted = false, watch = watch, bufferId = 7, nowMillis = 0),
        )
        assertEquals(
            ChannelNotifyLevel.All(minutesLeft = null, overridesMute = true),
            deriveNotifyLevel(muted = true, watch = watch, bufferId = 7, nowMillis = 5_000_000),
        )
    }

    @Test
    fun `remaining minutes round up and never reach zero`() {
        val watch = ChannelWatchState(bufferId = 7, expiresAt = 90_000)
        assertEquals(
            ChannelNotifyLevel.All(minutesLeft = 2, overridesMute = false),
            deriveNotifyLevel(muted = false, watch = watch, bufferId = 7, nowMillis = 0),
        )
        assertEquals(
            ChannelNotifyLevel.All(minutesLeft = 1, overridesMute = false),
            deriveNotifyLevel(muted = false, watch = watch, bufferId = 7, nowMillis = 89_999),
        )
    }
}
