package io.github.trevarj.motd.ui.settings

import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelStore
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.NotificationChannelRow
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.gesture.FakeBuffers
import io.github.trevarj.motd.gesture.FakeNetworks
import io.github.trevarj.motd.gesture.FakeSettings
import io.github.trevarj.motd.gesture.testNetwork
import io.github.trevarj.motd.service.ChannelWatchState
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.NotificationConfig
import io.github.trevarj.motd.service.NotificationMode
import io.github.trevarj.motd.service.NotificationScope
import io.github.trevarj.motd.service.NotificationSettings
import io.github.trevarj.motd.service.NotificationSettingsImpl
import io.github.trevarj.motd.service.NotificationSettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = ViewModelStore()
    private val networks = FakeNetworks(listOf(testNetwork(1, "One"), testNetwork(2, "Two")))
    private val channels =
        MutableStateFlow(
            listOf(
                NotificationChannelRow(11, 1, "#same", joined = true, archived = false, muted = false),
                NotificationChannelRow(21, 2, "#same", joined = false, archived = true, muted = true),
            ),
        )
    private val buffers =
        object : BufferRepository by FakeBuffers() {
            override fun observeNotificationChannels() = channels
        }
    private val preferences = FakeSettings()
    private var saved = NotificationConfig()
    private var failRead = false
    private var failWrite = false
    private var writeGate: CompletableDeferred<Unit>? = null

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        viewModels.clear()
        Dispatchers.resetMain()
    }

    private fun TestScope.owner() =
        NotificationSettingsImpl(
            scope = backgroundScope,
            clock = AppClock { testScheduler.currentTime },
            onExpired = {},
            load = {
                if (failRead) throw IOException("read failed")
                preferencesOf(stringPreferencesKey("config_v1") to Json.encodeToString(saved))
            },
            save = {
                writeGate?.await()
                if (failWrite) throw IOException("save failed")
                saved = it
            },
        )

    private fun TestScope.model(owner: NotificationSettings): NotificationSettingsViewModel {
        val model =
            NotificationSettingsViewModel(
                notificationSettings = owner,
                networkRepository = networks,
                bufferRepository = buffers,
                settingsRepository = preferences,
                clock = AppClock { testScheduler.currentTime },
            )
        viewModels.put("notifications", model)
        backgroundScope.launch { model.state.collect {} }
        runCurrent()
        return model
    }

    @Test
    fun groupsStoredChannelsInSourceOrderWithExactNetworkInheritanceAndCounts() =
        runTest {
            networks.rows.value =
                listOf(
                    testNetwork(30, "Z root").copy(role = NetworkRole.BOUNCER_ROOT),
                    testNetwork(10, "A child").copy(role = NetworkRole.BOUNCER_CHILD, parentId = 30),
                    testNetwork(20, "Empty"),
                )
            channels.value =
                listOf(
                    NotificationChannelRow(101, 10, "#a", joined = true, archived = false, muted = false),
                    NotificationChannelRow(102, 10, "#same", joined = false, archived = true, muted = true),
                    NotificationChannelRow(303, 30, "#before", joined = false, archived = false, muted = false),
                    NotificationChannelRow(302, 30, "#same", joined = true, archived = false, muted = true),
                )
            saved =
                NotificationConfig(
                    global = NotificationMode.MENTIONS,
                    servers = mapOf(30L to NotificationMode.ALL),
                    channels = mapOf(102L to NotificationMode.OFF, 302L to NotificationMode.MENTIONS, 999L to NotificationMode.OFF),
                    watches = mapOf(101L to 150_000L, 302L to Long.MAX_VALUE, 999L to Long.MAX_VALUE),
                )
            val model = model(owner())
            val state = model.state.value
            val (root, child, empty) = state.networks

            assertFalse(state.loading)
            assertEquals(listOf(30L, 10L, 20L), state.networks.map { it.networkId })
            assertEquals(listOf(303L, 302L), root.channels.map { it.bufferId })
            assertEquals(listOf(101L, 102L), child.channels.map { it.bufferId })
            assertEquals(NotificationMode.ALL, root.serverOverride)
            assertEquals(NotificationMode.ALL, root.effectiveMode)
            assertEquals(NotificationScope.SERVER, root.channels[0].notification.source)
            assertEquals(NotificationMode.ALL, root.channels[0].notification.mode)
            assertEquals(NotificationMode.MENTIONS, root.channels[1].notification.mode)
            assertEquals(NotificationScope.CHANNEL, root.channels[1].notification.source)
            assertEquals(ChannelWatchState(302, Long.MAX_VALUE), root.channels[1].notification.watch)
            assertNull(root.channels[1].notification.minutesLeft)
            assertEquals(1, root.channelOverrideCount)
            assertEquals(1, root.activeWatchCount)
            assertNull(child.serverOverride)
            assertEquals(NotificationMode.MENTIONS, child.effectiveMode)
            assertEquals(NotificationScope.GLOBAL, child.channels[0].notification.source)
            assertEquals(3, child.channels[0].notification.minutesLeft)
            assertEquals(NotificationMode.MENTIONS, child.channels[1].notification.parentMode)
            assertEquals(NotificationMode.OFF, child.channels[1].notification.mode)
            assertEquals(1, child.channelOverrideCount)
            assertEquals(1, child.activeWatchCount)
            assertTrue(empty.channels.isEmpty())
            assertEquals(0, empty.channelOverrideCount)
            assertEquals(0, empty.activeWatchCount)

            networks.rows.value = networks.rows.value.reversed()
            channels.value = channels.value.filterNot { it.bufferId == 302L }
            preferences.state.value = preferences.state.value.copy(deliveryMode = DeliveryMode.UNIFIED_PUSH)
            runCurrent()

            val updated = model.state.value
            assertEquals(listOf(20L, 10L, 30L), updated.networks.map { it.networkId })
            assertEquals(0, updated.networks.last().channelOverrideCount)
            assertEquals(0, updated.networks.last().activeWatchCount)
            assertEquals(DeliveryMode.UNIFIED_PUSH, updated.deliveryMode)
        }

    @Test
    fun scopeEditsWaitForPersistenceAndKeepSameNamedSiblingOverrides() =
        runTest {
            saved = NotificationConfig(global = NotificationMode.MENTIONS, channels = mapOf(21L to NotificationMode.OFF))
            val model = model(owner())
            val before = model.state.value
            writeGate = CompletableDeferred()

            model.setChannel(11, NotificationMode.ALL)
            runCurrent()
            assertEquals(before, model.state.value)

            writeGate?.complete(Unit)
            runCurrent()
            assertEquals(
                NotificationMode.ALL,
                model.state.value.networks[0]
                    .channels
                    .single()
                    .notification.mode,
            )
            assertEquals(before.networks[1], model.state.value.networks[1])

            model.setServer(1, NotificationMode.OFF)
            runCurrent()
            val overridden =
                model.state.value.networks[0]
                    .channels
                    .single()
            assertEquals(NotificationMode.OFF, overridden.notification.parentMode)
            assertEquals(NotificationMode.ALL, overridden.notification.mode)
            assertEquals(NotificationScope.CHANNEL, overridden.notification.source)
            assertEquals(before.networks[1], model.state.value.networks[1])

            model.setChannel(11, null)
            runCurrent()
            val inherited =
                model.state.value.networks[0]
                    .channels
                    .single()
            assertNull(inherited.notification.channelOverride)
            assertEquals(NotificationMode.OFF, inherited.notification.mode)
            assertEquals(NotificationScope.SERVER, inherited.notification.source)
            assertEquals(
                0,
                model.state.value.networks[0]
                    .channelOverrideCount,
            )

            model.setServer(1, null)
            model.setGlobal(NotificationMode.ALL)
            runCurrent()
            val final = model.state.value
            assertEquals(NotificationMode.ALL, final.global)
            assertNull(final.networks[0].serverOverride)
            assertEquals(
                NotificationMode.ALL,
                final.networks[0]
                    .channels
                    .single()
                    .notification.mode,
            )
            assertEquals(
                NotificationScope.GLOBAL,
                final.networks[0]
                    .channels
                    .single()
                    .notification.source,
            )
            assertEquals(
                NotificationMode.OFF,
                final.networks[1]
                    .channels
                    .single()
                    .notification.mode,
            )
            assertEquals(
                NotificationScope.CHANNEL,
                final.networks[1]
                    .channels
                    .single()
                    .notification.source,
            )
        }

    @Test
    fun startingAndStoppingWatchesKeepsTheSiblingAndPersistentPolicy() =
        runTest {
            saved = NotificationConfig(global = NotificationMode.OFF)
            val model = model(owner())

            model.startWatch(11, 90_000)
            model.startWatch(21, null)
            runCurrent()
            val watched = model.state.value
            assertEquals(listOf(1, 1), watched.networks.map { it.activeWatchCount })
            assertEquals(
                ChannelWatchState(11, 90_000),
                watched.networks[0]
                    .channels
                    .single()
                    .notification.watch,
            )
            assertEquals(
                ChannelWatchState(21, Long.MAX_VALUE),
                watched.networks[1]
                    .channels
                    .single()
                    .notification.watch,
            )
            assertEquals(
                NotificationMode.OFF,
                watched.networks[0]
                    .channels
                    .single()
                    .notification.mode,
            )

            model.stopWatch(11)
            runCurrent()
            assertNull(
                model.state.value.networks[0]
                    .channels
                    .single()
                    .notification.watch,
            )
            assertEquals(
                0,
                model.state.value.networks[0]
                    .activeWatchCount,
            )
            assertEquals(
                NotificationMode.OFF,
                model.state.value.networks[0]
                    .channels
                    .single()
                    .notification.mode,
            )
            assertEquals(watched.networks[1], model.state.value.networks[1])
        }

    @Test
    fun failedSavesRetainTheOverviewAndSiblingWatchesUntilErrorDismissal() =
        runTest {
            saved =
                NotificationConfig(
                    global = NotificationMode.MENTIONS,
                    channels = mapOf(11L to NotificationMode.OFF, 21L to NotificationMode.ALL),
                    watches = mapOf(11L to 600_000L, 21L to Long.MAX_VALUE),
                )
            val model = model(owner())
            val before = model.state.value
            failWrite = true

            model.setServer(1, NotificationMode.OFF)
            runCurrent()
            assertEquals(before.copy(saveError = true), model.state.value)

            model.dismissError()
            runCurrent()
            assertEquals(before, model.state.value)

            model.stopWatch(11)
            runCurrent()
            assertEquals(before.copy(saveError = true), model.state.value)
        }

    @Test
    fun thrownFailureShowsSaveErrorButCancellationDoesNot() =
        runTest {
            var failure: Exception = IOException("save failed")
            val settings =
                object : NotificationSettings by owner() {
                    override suspend fun setGlobal(mode: NotificationMode): Boolean = throw failure
                }
            val model = model(settings)
            val before = model.state.value

            model.setGlobal(NotificationMode.OFF)
            runCurrent()
            assertEquals(before.copy(saveError = true), model.state.value)

            model.dismissError()
            failure = CancellationException("cancelled")
            val job = model.setGlobal(NotificationMode.OFF)
            runCurrent()
            assertTrue(job.isCancelled)
            assertEquals(before, model.state.value)
        }

    @Test
    fun unavailableFailsClosedUntilRetryRestoresTheSavedOwnerState() =
        runTest {
            saved =
                NotificationConfig(
                    global = NotificationMode.OFF,
                    servers = mapOf(1L to NotificationMode.ALL),
                    channels = mapOf(11L to NotificationMode.MENTIONS),
                    watches = mapOf(21L to Long.MAX_VALUE),
                )
            failRead = true
            preferences.state.value = preferences.state.value.copy(deliveryMode = DeliveryMode.UNIFIED_PUSH)
            val model = model(owner())
            val unavailable = model.state.value
            assertFalse(unavailable.loading)
            assertTrue(unavailable.unavailable)
            assertEquals(NotificationMode.OFF, unavailable.global)
            assertTrue(unavailable.networks.isEmpty())
            assertEquals(DeliveryMode.UNIFIED_PUSH, unavailable.deliveryMode)

            model.setGlobal(NotificationMode.ALL)
            runCurrent()
            assertEquals(unavailable.copy(saveError = true), model.state.value)
            model.dismissError()
            model.retryLoad()
            runCurrent()
            assertEquals(unavailable.copy(saveError = true), model.state.value)

            failRead = false
            model.dismissError()
            model.retryLoad()
            runCurrent()
            val restored = model.state.value
            assertFalse(restored.loading)
            assertFalse(restored.unavailable)
            assertFalse(restored.saveError)
            assertEquals(NotificationMode.OFF, restored.global)
            assertEquals(NotificationMode.ALL, restored.networks[0].effectiveMode)
            assertEquals(
                NotificationMode.MENTIONS,
                restored.networks[0]
                    .channels
                    .single()
                    .notification.mode,
            )
            assertEquals(
                NotificationScope.CHANNEL,
                restored.networks[0]
                    .channels
                    .single()
                    .notification.source,
            )
            assertEquals(
                ChannelWatchState(21, Long.MAX_VALUE),
                restored.networks[1]
                    .channels
                    .single()
                    .notification.watch,
            )
            assertEquals(1, restored.networks[1].activeWatchCount)
        }

    @Test
    fun tickerRefreshesRemainingMinutesAndDropsExpiredWatchesWhenCleanupCannotSave() =
        runTest {
            saved =
                NotificationConfig(
                    global = NotificationMode.MENTIONS,
                    channels = mapOf(11L to NotificationMode.OFF),
                    watches = mapOf(11L to 90_000L, 21L to Long.MAX_VALUE),
                )
            val owner = owner()
            val model = model(owner)
            failWrite = true
            assertEquals(
                2,
                model.state.value.networks[0]
                    .channels
                    .single()
                    .notification.minutesLeft,
            )

            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(
                2,
                model.state.value.networks[0]
                    .channels
                    .single()
                    .notification.minutesLeft,
            )
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                1,
                model.state.value.networks[0]
                    .channels
                    .single()
                    .notification.minutesLeft,
            )

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(90_000L, (owner.state.value as NotificationSettingsState.Ready).config.watches[11L])
            val expired = model.state.value.networks[0]
            assertEquals(0, expired.activeWatchCount)
            assertNull(
                expired.channels
                    .single()
                    .notification.watch,
            )
            assertNull(
                expired.channels
                    .single()
                    .notification.minutesLeft,
            )
            assertEquals(
                NotificationMode.OFF,
                expired.channels
                    .single()
                    .notification.mode,
            )
            assertEquals(
                NotificationScope.CHANNEL,
                expired.channels
                    .single()
                    .notification.source,
            )
            assertEquals(
                1,
                model.state.value.networks[1]
                    .activeWatchCount,
            )
            assertEquals(
                ChannelWatchState(21, Long.MAX_VALUE),
                model.state.value.networks[1]
                    .channels
                    .single()
                    .notification.watch,
            )
        }
}
