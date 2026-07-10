package io.github.trevarj.motd.push

import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.PushKeys
import io.github.trevarj.motd.data.prefs.PushPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushInstanceCoordinatorTest {

    /** Records every UnifiedPush call in order; distributor state is configurable. */
    private class FakeUnifiedPushApi(
        private var installed: List<String> = listOf("dist.a"),
        private var acked: String? = null,
    ) : UnifiedPushApi {
        val registered = mutableListOf<String>()
        val unregistered = mutableListOf<String>()
        val saved = mutableListOf<String>()
        override fun getDistributors(): List<String> = installed
        override fun getAckDistributor(): String? = acked
        override fun saveDistributor(distributor: String) {
            saved.add(distributor); acked = distributor
        }
        override fun registerApp(instance: String) { registered.add(instance) }
        override fun unregisterApp(instance: String) { unregistered.add(instance) }
    }

    private class FakePushPrefs(private val endpoints: MutableMap<Long, String> = mutableMapOf()) : PushPrefs {
        override suspend fun endpoints(): Map<Long, String> = endpoints.toMap()
        override suspend fun endpointFor(networkId: Long): String? = endpoints[networkId]
        override suspend fun setEndpointFor(networkId: Long, endpoint: String?) {
            if (endpoint == null) endpoints.remove(networkId) else endpoints[networkId] = endpoint
        }
        override suspend fun clearEndpoints() { endpoints.clear() }
        override suspend fun keys(): PushKeys? = null
        override suspend fun setKeys(keys: PushKeys) = Unit
    }

    private class FakeSettingsRepository(mode: DeliveryMode) : SettingsRepository {
        override val settings = MutableStateFlow(Settings(ThemeMode.SYSTEM, true, mode))
        override suspend fun setThemeMode(m: ThemeMode) = Unit
        override suspend fun setDynamicColor(enabled: Boolean) = Unit
        override suspend fun setDeliveryMode(m: DeliveryMode) {
            settings.value = settings.value.copy(deliveryMode = m)
        }
        // Round 4 members: unused by this test.
        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit
        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit
        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit
        override suspend fun setNickColorOverride(nick: String, hue: Int?) = Unit
        override suspend fun setFriend(nick: String, isFriend: Boolean) = Unit
        override suspend fun setFool(nick: String, isFool: Boolean) = Unit
        override suspend fun setFoolsMode(m: FoolsMode) = Unit
        override suspend fun setShowJoinPartQuit(show: Boolean) = Unit
        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit
        override suspend fun setChatWallpaper(w: io.github.trevarj.motd.data.prefs.ChatWallpaper) = Unit
    }

    private class FakeNetworkDao(nets: List<NetworkEntity>) : NetworkDao {
        val flow = MutableStateFlow(nets)
        override fun observeAll(): Flow<List<NetworkEntity>> = flow
        override suspend fun connectable(): List<NetworkEntity> = flow.value.filter { it.autoConnect }
        override suspend fun byId(id: Long): NetworkEntity? = flow.value.firstOrNull { it.id == id }
        override suspend fun insert(n: NetworkEntity): Long = 0
        override suspend fun update(n: NetworkEntity) = Unit
        override suspend fun delete(n: NetworkEntity) = Unit
        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = emptyList()
        override suspend fun allNow(): List<NetworkEntity> = flow.value
    }

    private fun net(id: Long, autoConnect: Boolean = true) = NetworkEntity(
        id = id, name = "n$id", role = NetworkRole.DIRECT,
        host = "h", port = 6697, nick = "me", username = "me", realname = "me",
        autoConnect = autoConnect,
    )

    private fun coordinator(
        up: UnifiedPushApi,
        prefs: PushPrefs = FakePushPrefs(),
        mode: DeliveryMode = DeliveryMode.PERSISTENT_SOCKET,
    ) = PushInstanceCoordinator(FakeSettingsRepository(mode), FakeNetworkDao(emptyList()), prefs, up)

    @Test
    fun push_mode_registers_every_connectable_and_saves_distributor() = runTest {
        val up = FakeUnifiedPushApi(installed = listOf("dist.a"), acked = null)
        coordinator(up).reconcile(DeliveryMode.UNIFIED_PUSH, setOf(1L, 2L))

        assertEquals(listOf("dist.a"), up.saved) // auto-selected first distributor
        assertEquals(setOf("1", "2"), up.registered.toSet())
        assertTrue(up.unregistered.isEmpty())
    }

    @Test
    fun already_acked_distributor_is_not_resaved() = runTest {
        val up = FakeUnifiedPushApi(acked = "dist.a")
        coordinator(up).reconcile(DeliveryMode.UNIFIED_PUSH, setOf(1L))
        assertTrue(up.saved.isEmpty())
        assertEquals(listOf("1"), up.registered)
    }

    @Test
    fun no_distributor_is_a_no_op() = runTest {
        val up = FakeUnifiedPushApi(installed = emptyList(), acked = null)
        coordinator(up).reconcile(DeliveryMode.UNIFIED_PUSH, setOf(1L, 2L))
        assertTrue(up.registered.isEmpty())
        assertTrue(up.unregistered.isEmpty())
        assertTrue(up.saved.isEmpty())
    }

    @Test
    fun socket_mode_unregisters_all_connectable_and_held_endpoints() = runTest {
        val up = FakeUnifiedPushApi()
        val prefs = FakePushPrefs(mutableMapOf(3L to "https://e/3"))
        coordinator(up, prefs).reconcile(DeliveryMode.PERSISTENT_SOCKET, setOf(1L, 2L))

        assertTrue(up.registered.isEmpty())
        // connectable {1,2} + held endpoint {3} all unregistered under socket mode.
        assertEquals(setOf("1", "2", "3"), up.unregistered.toSet())
    }

    @Test
    fun network_removed_under_push_unregisters_the_delta() = runTest {
        val up = FakeUnifiedPushApi(acked = "dist.a")
        // We hold an endpoint for a network no longer connectable.
        val prefs = FakePushPrefs(mutableMapOf(1L to "https://e/1", 9L to "https://e/9"))
        coordinator(up, prefs).reconcile(DeliveryMode.UNIFIED_PUSH, setOf(1L))

        assertEquals(listOf("1"), up.registered)
        // held {1,9} + connectable {1} - desired {1} = {9}
        assertEquals(setOf("9"), up.unregistered.toSet())
    }

    @Test
    fun start_collects_and_reconciles_from_live_streams() {
        // The coordinator collects on its own Dispatchers.Default scope, so drive real time.
        val up = FakeUnifiedPushApi(acked = "dist.a")
        val settings = FakeSettingsRepository(DeliveryMode.UNIFIED_PUSH)
        val dao = FakeNetworkDao(listOf(net(1L), net(2L, autoConnect = false)))
        val coordinator = PushInstanceCoordinator(settings, dao, FakePushPrefs(), up)

        coordinator.start()
        coordinator.start() // idempotent: second call must not double-collect

        // Poll for the collect to run (real background dispatcher).
        val deadline = System.currentTimeMillis() + 2_000
        while (up.registered.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }

        // Only the autoConnect network (1) is desired; idempotent start => single registration.
        assertEquals(listOf("1"), up.registered)
    }
}
