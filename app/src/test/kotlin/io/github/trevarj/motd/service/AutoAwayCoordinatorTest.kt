package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.HistorySyncDepth
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TEN_MINUTES = 10 * 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
class AutoAwayCoordinatorTest {
    @Test
    fun backgrounded_long_enough_marks_ready_networks_away_and_foreground_brings_them_back() =
        runTest {
            val world = world(this, enabled = true)
            world.connections.states.value = mapOf(1L to ready(), 2L to ready())

            advanceTimeBy(TEN_MINUTES + 1)
            runCurrent()
            assertEquals(listOf(1L to "brb", 2L to "brb"), world.connections.writes.sortedBy { it.first })

            // Server confirms both; only then are they ours to clear.
            world.connections.away.value = mapOf(1L to "brb", 2L to "brb")
            runCurrent()
            world.connections.writes.clear()

            world.visibility.onScreenState.value = true
            runCurrent()
            assertEquals(listOf(1L to null, 2L to null), world.connections.writes.sortedBy { it.first })
        }

    @Test
    fun nothing_happens_before_the_configured_delay_elapses() =
        runTest {
            val world = world(this, enabled = true)
            world.connections.states.value = mapOf(1L to ready())

            advanceTimeBy(TEN_MINUTES - 1)
            runCurrent()
            assertTrue(world.connections.writes.isEmpty())
        }

    @Test
    fun disabled_auto_away_never_writes() =
        runTest {
            val world = world(this, enabled = false)
            world.connections.states.value = mapOf(1L to ready())

            advanceTimeBy(TEN_MINUTES * 10)
            runCurrent()
            assertTrue(world.connections.writes.isEmpty())

            world.visibility.onScreenState.value = true
            runCurrent()
            assertTrue(world.connections.writes.isEmpty())
        }

    @Test
    fun an_existing_away_is_never_overwritten_and_never_auto_backed() =
        runTest {
            val world = world(this, enabled = true)
            // Network 1 is already away (set by hand, or replayed by the bouncer on reconnect).
            world.connections.states.value = mapOf(1L to ready(), 2L to ready())
            world.connections.away.value = mapOf(1L to "afk since yesterday")

            advanceTimeBy(TEN_MINUTES + 1)
            runCurrent()
            assertEquals(listOf(2L to "brb"), world.connections.writes)

            world.connections.away.value = mapOf(1L to "afk since yesterday", 2L to "brb")
            runCurrent()
            world.connections.writes.clear()

            world.visibility.onScreenState.value = true
            runCurrent()
            assertEquals(listOf(2L to null), world.connections.writes)
        }

    @Test
    fun a_network_connecting_while_backgrounded_is_marked_away_too() =
        runTest {
            val world = world(this, enabled = true)
            world.connections.states.value = mapOf(1L to ready())

            advanceTimeBy(TEN_MINUTES + 1)
            runCurrent()
            world.connections.away.value = mapOf(1L to "brb")
            world.connections.writes.clear()

            world.connections.states.value = mapOf(1L to ready(), 2L to ready())
            runCurrent()
            assertEquals(listOf(2L to "brb"), world.connections.writes)
        }

    @Test
    fun a_confirmed_network_is_not_written_twice_while_it_stays_away() =
        runTest {
            val world = world(this, enabled = true)
            world.connections.states.value = mapOf(1L to ready())

            advanceTimeBy(TEN_MINUTES + 1)
            runCurrent()
            world.connections.away.value = mapOf(1L to "brb")
            // An unrelated connection-state churn must not resend AWAY.
            world.connections.states.value = mapOf(1L to ready(), 2L to IrcClientState.Connecting)
            runCurrent()
            assertEquals(listOf(1L to "brb"), world.connections.writes)
        }

    @Test
    fun a_manual_back_while_backgrounded_drops_the_marker() =
        runTest {
            val world = world(this, enabled = true)
            world.connections.states.value = mapOf(1L to ready())

            advanceTimeBy(TEN_MINUTES + 1)
            runCurrent()
            world.connections.away.value = mapOf(1L to "brb")
            runCurrent()
            // The user typed /back on another client: the server no longer reports us away.
            world.connections.away.value = emptyMap()
            runCurrent()
            world.connections.writes.clear()

            world.visibility.onScreenState.value = true
            runCurrent()
            assertTrue(world.connections.writes.isEmpty())
        }

    @Test
    fun a_disconnect_while_backgrounded_rearms_the_network_on_reconnect() =
        runTest {
            val world = world(this, enabled = true)
            world.connections.states.value = mapOf(1L to ready())

            advanceTimeBy(TEN_MINUTES + 1)
            runCurrent()
            world.connections.away.value = mapOf(1L to "brb")
            runCurrent()

            // Socket drops: the confirmed away state and the pending request both go with it.
            world.connections.states.value = mapOf(1L to IrcClientState.Disconnected)
            world.connections.away.value = emptyMap()
            runCurrent()
            world.connections.writes.clear()

            world.connections.states.value = mapOf(1L to ready())
            runCurrent()
            assertEquals(listOf(1L to "brb"), world.connections.writes)
        }

    @Test
    fun a_blank_configured_message_uses_the_localized_default() =
        runTest {
            val world = world(this, enabled = true, message = "")
            world.connections.states.value = mapOf(1L to ready())

            advanceTimeBy(TEN_MINUTES + 1)
            runCurrent()
            assertEquals(listOf(1L to "Away (auto)"), world.connections.writes)
        }

    @Test
    fun changing_the_delay_while_backgrounded_restarts_the_countdown() =
        runTest {
            val world = world(this, enabled = true)
            world.connections.states.value = mapOf(1L to ready())

            advanceTimeBy(TEN_MINUTES - 1000)
            runCurrent()
            world.settings.state.value =
                world.settings.state.value
                    .copy(autoAwayMinutes = 30)
            advanceTimeBy(2000)
            runCurrent()
            assertTrue(world.connections.writes.isEmpty())

            advanceTimeBy(30 * 60_000L)
            runCurrent()
            assertEquals(listOf(1L to "brb"), world.connections.writes)
        }

    // -- fixtures --

    private fun ready(nick: String = "me") = IrcClientState.Ready(nick, emptySet(), emptyMap())

    private class World(
        val connections: FakeConnections,
        val settings: FakeSettingsRepository,
        val visibility: FakeVisibility,
    )

    private fun world(
        scope: TestScope,
        enabled: Boolean,
        minutes: Int = 10,
        message: String = "brb",
    ): World {
        val connections = FakeConnections()
        val settings =
            FakeSettingsRepository(
                Settings(autoAwayEnabled = enabled, autoAwayMinutes = minutes, autoAwayMessage = message),
            )
        val visibility = FakeVisibility()
        AutoAwayCoordinator
            .forTest(
                connections = connections,
                settingsRepository = settings,
                visibility = visibility,
                scope = scope.backgroundScope,
                defaultMessage = { "Away (auto)" },
            ).start()
        scope.runCurrent()
        return World(connections, settings, visibility)
    }

    /** Starts backgrounded, which is what a headless process looks like. */
    private class FakeVisibility : AppVisibility {
        val onScreenState = MutableStateFlow(false)
        override val onScreen: StateFlow<Boolean> = onScreenState
    }

    private class FakeConnections : NoopConnectionManager() {
        val away = MutableStateFlow<Map<Long, String?>>(emptyMap())
        val writes = mutableListOf<Pair<Long, String?>>()
        override val selfAwayStates: StateFlow<Map<Long, String?>> = away

        override suspend fun setAway(
            networkId: Long,
            message: String?,
        ) {
            writes += networkId to message
        }

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ): Long = 0

        override suspend fun ensureServerBuffer(networkId: Long): Long = 0
    }

    private class FakeSettingsRepository(
        initial: Settings,
    ) : SettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: StateFlow<Settings> = state

        override suspend fun setThemeMode(m: ThemeMode) = Unit

        override suspend fun setDynamicColor(enabled: Boolean) = Unit

        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit

        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit

        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit

        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit

        override suspend fun setNickColorOverride(
            nick: String,
            hue: Int?,
        ) = Unit

        override suspend fun setFriend(
            nick: String,
            isFriend: Boolean,
        ) = Unit

        override suspend fun setFool(
            nick: String,
            isFool: Boolean,
        ) = Unit

        override suspend fun setFoolsMode(m: FoolsMode) = Unit

        override suspend fun setPresenceMode(m: PresenceMode) = Unit

        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit

        override suspend fun setChatWallpaper(w: ChatWallpaper) = Unit

        override suspend fun setShowComposerEmoji(show: Boolean) = Unit

        override suspend fun setShowComposerFormattingTools(show: Boolean) = Unit

        override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit

        override suspend fun setHistorySyncDepth(d: HistorySyncDepth) = Unit

        override suspend fun setHistorySyncMode(mode: HistorySyncMode) {
            state.value = state.value.copy(historySyncMode = mode)
        }

        override suspend fun setAutoAwayEnabled(enabled: Boolean) = Unit

        override suspend fun setAutoAwayMinutes(minutes: Int) = Unit

        override suspend fun setAutoAwayMessage(message: String) = Unit
    }
}
