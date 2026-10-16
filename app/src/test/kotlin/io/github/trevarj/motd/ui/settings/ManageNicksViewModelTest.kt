package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.HistorySyncMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManageNicksViewModelTest {
    /** In-memory SettingsRepository enforcing friends/fools disjointness like the real one. */
    private class FakeSettingsRepository : SettingsRepository {
        override val settings =
            MutableStateFlow(
                Settings(ThemeMode.SYSTEM, true, DeliveryMode.PERSISTENT_SOCKET),
            )
        var foolWriteGate: CompletableDeferred<Unit>? = null
        val foolWrites = mutableListOf<FoolPreferences>()

        override suspend fun setThemeMode(m: ThemeMode) = Unit

        override suspend fun setDynamicColor(enabled: Boolean) = Unit

        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit

        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit

        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit

        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit

        override suspend fun setNickColorOverride(
            nick: String,
            hue: Int?,
        ) {
            val key = normalizeNick(nick)
            settings.value =
                settings.value.copy(
                    nickColorOverrides =
                        settings.value.nickColorOverrides.toMutableMap().apply {
                            if (hue == null) remove(key) else put(key, hue.coerceIn(0, 359))
                        },
                )
        }

        override suspend fun setFriend(
            nick: String,
            isFriend: Boolean,
        ) {
            val key = normalizeNick(nick)
            settings.value =
                settings.value.copy(
                    friends =
                        settings.value.friends.toMutableSet().apply {
                            if (isFriend) add(key) else remove(key)
                        },
                    // Disjoint: adding a friend removes it from fools.
                    fools = if (isFriend) settings.value.fools - key else settings.value.fools,
                    foolPreferences = if (isFriend) settings.value.foolPreferences - key else settings.value.foolPreferences,
                )
        }

        override suspend fun setFool(
            nick: String,
            isFool: Boolean,
        ) {
            val key = normalizeNick(nick)
            settings.value =
                settings.value.copy(
                    fools =
                        settings.value.fools.toMutableSet().apply {
                            if (isFool) add(key) else remove(key)
                        },
                    friends = if (isFool) settings.value.friends - key else settings.value.friends,
                    foolPreferences = if (isFool) settings.value.foolPreferences else settings.value.foolPreferences - key,
                )
        }

        override suspend fun setFoolPreferences(
            nick: String,
            preferences: io.github.trevarj.motd.data.prefs.FoolPreferences,
            identityRules: io.github.trevarj.motd.irc.proto.IrcIdentityRules?,
        ) {
            foolWrites += preferences
            foolWriteGate?.await()
            val current = settings.value
            val keys =
                current.fools.filter {
                    identityRules?.normalize(it) == identityRules?.normalize(nick.trim()) && (
                        identityRules != null || it ==
                            io.github.trevarj.motd.data.prefs
                                .normalizeNick(nick)
                    )
                }
            settings.value =
                current.copy(
                    foolPreferences =
                        current.foolPreferences.toMutableMap().apply {
                            keys.forEach {
                                if (preferences ==
                                    io.github.trevarj.motd.data.prefs
                                        .FoolPreferences()
                                ) {
                                    remove(it)
                                } else {
                                    put(it, preferences)
                                }
                            }
                        },
                )
        }

        override suspend fun setFoolsMode(m: FoolsMode) {
            settings.value = settings.value.copy(foolsMode = m)
        }

        override suspend fun setPresenceMode(m: PresenceMode) = Unit

        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit

        override suspend fun setChatWallpaper(w: io.github.trevarj.motd.data.prefs.ChatWallpaper) = Unit

        override suspend fun setShowComposerEmoji(show: Boolean) = Unit

        override suspend fun setShowComposerFormattingTools(show: Boolean) = Unit

        override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit

        override suspend fun setHistorySyncDepth(d: io.github.trevarj.motd.data.prefs.HistorySyncDepth) = Unit

        override suspend fun setHistorySyncMode(mode: HistorySyncMode) {
            settings.value = settings.value.copy(historySyncMode = mode)
        }

        override suspend fun setAutoAwayEnabled(enabled: Boolean) = Unit

        override suspend fun setAutoAwayMinutes(minutes: Int) = Unit

        override suspend fun setAutoAwayMessage(message: String) = Unit
    }

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm(repo: SettingsRepository) = ManageNicksViewModel(repo)

    @Test
    fun friends_add_and_remove_route_to_repository() =
        runTest {
            val repo = FakeSettingsRepository()
            val vm = vm(repo).apply { init(NickListKind.FRIENDS) }

            vm.add("Alice")
            runCurrent()
            assertEquals(setOf("alice"), repo.settings.value.friends)
            assertEquals(listOf("alice"), vm.state.first { it.nicks.isNotEmpty() }.nicks)

            vm.remove("alice")
            runCurrent()
            assertEquals(emptySet<String>(), repo.settings.value.friends)
        }

    @Test
    fun fools_add_removes_from_friends_disjoint() =
        runTest {
            val repo = FakeSettingsRepository()
            repo.settings.value = repo.settings.value.copy(friends = setOf("bob"))
            val vm = vm(repo).apply { init(NickListKind.FOOLS) }

            vm.add("Bob")
            runCurrent()
            assertEquals(setOf("bob"), repo.settings.value.fools)
            assertEquals(emptySet<String>(), repo.settings.value.friends)
        }

    @Test
    fun fools_state_and_immediate_edits_preserve_inheritance_and_independent_switches() =
        runTest {
            val repo = FakeSettingsRepository()
            val vm = vm(repo).apply { init(NickListKind.FOOLS) }
            vm.add("Bot")
            runCurrent()
            assertEquals(FoolsMode.DIMMED, vm.state.first { it.nicks.isNotEmpty() }.foolsMode)
            vm.updateFoolPreferences("bot") { it.copy(notifyMentions = true) }
            runCurrent()
            assertEquals(FoolPreferences(notifyMentions = true), vm.state.first { it.foolPreferences.isNotEmpty() }.foolPreferences["bot"])
            repo.setFoolsMode(FoolsMode.HIDE)
            assertEquals(FoolsMode.HIDE, vm.state.first { it.foolsMode == FoolsMode.HIDE }.foolsMode)
            assertEquals(
                null,
                repo.settings.value.foolPreferences["bot"]
                    ?.visibility,
            )
            vm.updateFoolPreferences("bot") { it.copy(visibility = FoolsMode.COLLAPSE, notifyMentions = false, notifyDirectMessages = true) }
            runCurrent()
            val saved = FoolPreferences(FoolsMode.COLLAPSE, notifyDirectMessages = true)
            assertEquals(saved, repo.settings.value.foolPreferences["bot"])
            repo.setFoolsMode(FoolsMode.DIMMED)
            assertEquals(saved, vm.state.first { it.foolsMode == FoolsMode.DIMMED }.foolPreferences["bot"])
            vm.remove("bot")
            vm.updateFoolPreferences("bot") { saved }
            runCurrent()
            assertEquals(emptyMap<String, FoolPreferences>(), repo.settings.value.foolPreferences)
        }

    @Test
    fun rapid_distinct_fool_controls_serialize_delayed_writes_without_losing_fields() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val other = FoolPreferences(FoolsMode.HIDE)
            val repo =
                FakeSettingsRepository().apply {
                    settings.value = settings.value.copy(fools = setOf("bot[", "bot{"), foolPreferences = mapOf("bot{" to other))
                    foolWriteGate = gate
                }
            val vm = vm(repo).apply { init(NickListKind.FOOLS) }

            vm.updateFoolPreferences("BOT[") { it.copy(notifyMentions = true) }
            vm.updateFoolPreferences("BOT[") { it.copy(notifyDirectMessages = true) }
            vm.updateFoolPreferences("BOT[") { it.copy(visibility = FoolsMode.COLLAPSE) }
            runCurrent()
            assertEquals(listOf(FoolPreferences(notifyMentions = true)), repo.foolWrites)
            assertEquals(mapOf("bot{" to other), repo.settings.value.foolPreferences)

            gate.complete(Unit)
            advanceUntilIdle()
            val saved = FoolPreferences(FoolsMode.COLLAPSE, notifyMentions = true, notifyDirectMessages = true)
            assertEquals(mapOf("bot[" to saved, "bot{" to other), repo.settings.value.foolPreferences)
            assertEquals(
                listOf(FoolPreferences(notifyMentions = true), FoolPreferences(notifyMentions = true, notifyDirectMessages = true), saved),
                repo.foolWrites,
            )
        }

    @Test
    fun colors_add_is_noop_until_hue_set() =
        runTest {
            val repo = FakeSettingsRepository()
            val vm = vm(repo).apply { init(NickListKind.COLORS) }

            vm.add("carol")
            runCurrent()
            assertEquals(emptyMap<String, Int>(), repo.settings.value.nickColorOverrides)

            vm.setHue("carol", 210)
            runCurrent()
            assertEquals(mapOf("carol" to 210), repo.settings.value.nickColorOverrides)
        }

    @Test
    fun colors_remove_clears_override() =
        runTest {
            val repo = FakeSettingsRepository()
            repo.settings.value = repo.settings.value.copy(nickColorOverrides = mapOf("dave" to 120))
            val vm = vm(repo).apply { init(NickListKind.COLORS) }

            vm.remove("dave")
            runCurrent()
            assertEquals(emptyMap<String, Int>(), repo.settings.value.nickColorOverrides)
        }

    @Test
    fun colors_state_maps_override_keys_sorted() =
        runTest {
            val repo = FakeSettingsRepository()
            repo.settings.value =
                repo.settings.value.copy(
                    nickColorOverrides = mapOf("zoe" to 10, "amy" to 300),
                )
            val vm = vm(repo).apply { init(NickListKind.COLORS) }

            val state = vm.state.first { it.overrides.isNotEmpty() }
            assertEquals(listOf("amy", "zoe"), state.nicks)
            assertEquals(mapOf("zoe" to 10, "amy" to 300), state.overrides)
        }
}
