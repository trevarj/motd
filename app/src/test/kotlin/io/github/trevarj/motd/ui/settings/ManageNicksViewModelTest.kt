package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
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
        override val settings = MutableStateFlow(
            Settings(ThemeMode.SYSTEM, true, DeliveryMode.PERSISTENT_SOCKET),
        )

        override suspend fun setThemeMode(m: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit
        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit
        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit
        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit

        override suspend fun setNickColorOverride(nick: String, hue: Int?) {
            val key = normalizeNick(nick)
            settings.value = settings.value.copy(
                nickColorOverrides = settings.value.nickColorOverrides.toMutableMap().apply {
                    if (hue == null) remove(key) else put(key, hue.coerceIn(0, 359))
                },
            )
        }

        override suspend fun setFriend(nick: String, isFriend: Boolean) {
            val key = normalizeNick(nick)
            settings.value = settings.value.copy(
                friends = settings.value.friends.toMutableSet().apply {
                    if (isFriend) add(key) else remove(key)
                },
                // Disjoint: adding a friend removes it from fools.
                fools = if (isFriend) settings.value.fools - key else settings.value.fools,
            )
        }

        override suspend fun setFool(nick: String, isFool: Boolean) {
            val key = normalizeNick(nick)
            settings.value = settings.value.copy(
                fools = settings.value.fools.toMutableSet().apply {
                    if (isFool) add(key) else remove(key)
                },
                friends = if (isFool) settings.value.friends - key else settings.value.friends,
            )
        }

        override suspend fun setFoolsMode(m: FoolsMode) = Unit
        override suspend fun setShowJoinPartQuit(show: Boolean) = Unit
        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit
        override suspend fun setChatWallpaper(w: io.github.trevarj.motd.data.prefs.ChatWallpaper) = Unit
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
    fun friends_add_and_remove_route_to_repository() = runTest {
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
    fun fools_add_removes_from_friends_disjoint() = runTest {
        val repo = FakeSettingsRepository()
        repo.settings.value = repo.settings.value.copy(friends = setOf("bob"))
        val vm = vm(repo).apply { init(NickListKind.FOOLS) }

        vm.add("Bob")
        runCurrent()
        assertEquals(setOf("bob"), repo.settings.value.fools)
        assertEquals(emptySet<String>(), repo.settings.value.friends)
    }

    @Test
    fun colors_add_is_noop_until_hue_set() = runTest {
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
    fun colors_remove_clears_override() = runTest {
        val repo = FakeSettingsRepository()
        repo.settings.value = repo.settings.value.copy(nickColorOverrides = mapOf("dave" to 120))
        val vm = vm(repo).apply { init(NickListKind.COLORS) }

        vm.remove("dave")
        runCurrent()
        assertEquals(emptyMap<String, Int>(), repo.settings.value.nickColorOverrides)
    }

    @Test
    fun colors_state_maps_override_keys_sorted() = runTest {
        val repo = FakeSettingsRepository()
        repo.settings.value = repo.settings.value.copy(
            nickColorOverrides = mapOf("zoe" to 10, "amy" to 300),
        )
        val vm = vm(repo).apply { init(NickListKind.COLORS) }

        val state = vm.state.first { it.overrides.isNotEmpty() }
        assertEquals(listOf("amy", "zoe"), state.nicks)
        assertEquals(mapOf("zoe" to 10, "amy" to 300), state.overrides)
    }
}
