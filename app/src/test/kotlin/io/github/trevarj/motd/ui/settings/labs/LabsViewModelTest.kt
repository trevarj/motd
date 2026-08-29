package io.github.trevarj.motd.ui.settings.labs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.agentwire.AgentwirePrefs
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.gesture.GestureMenuConfig
import io.github.trevarj.motd.gesture.GesturePrefs
import io.github.trevarj.motd.gesture.radial.OrbPlacement
import io.github.trevarj.motd.sidecar.SidecarPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LabsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val gestures = FakeGesturePrefs()
    private val agentwire = FakeAgentwirePrefs()
    private val globalFeed = FakeGlobalFeedPrefs()
    private val sidecars = FakeSidecarPrefs()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = LabsViewModel(gestures, agentwire, globalFeed, sidecars)

    @Test fun everyLabStartsOff() =
        runTest {
            assertEquals(
                LabsUiState(gesturesEnabled = false, agentwireEnabled = false, globalFeedEnabled = false),
                vm().state.first(),
            )
        }

    @Test fun sidecarToggle_writesOnlyTheSidecarStore() =
        runTest {
            val model = vm()
            model.setSidecarsEnabled(true)
            assertEquals(
                LabsUiState(sidecarsEnabled = true),
                model.state.first { it.sidecarsEnabled },
            )
            assertEquals(false, gestures.enabled.first())
            assertEquals(false, agentwire.enabled.first())
            assertEquals(false, globalFeed.enabled.first())
        }

    /** The feed's entry points read this flag, so its default decides whether they render at all. */
    @Test fun globalFeedToggle_writesOnlyTheFeedStore() =
        runTest {
            val model = vm()
            assertEquals(false, globalFeed.enabled.first())

            model.setGlobalFeedEnabled(true)
            assertEquals(
                LabsUiState(gesturesEnabled = false, agentwireEnabled = false, globalFeedEnabled = true),
                model.state.first { it.globalFeedEnabled },
            )
            assertEquals(false, gestures.enabled.first())
            assertEquals(false, agentwire.enabled.first())
        }

    @Test fun gestureToggle_writesOnlyTheGestureStore() =
        runTest {
            val model = vm()
            model.setGesturesEnabled(true)
            assertEquals(
                LabsUiState(gesturesEnabled = true, agentwireEnabled = false),
                model.state.first { it.gesturesEnabled },
            )
            assertEquals(false, agentwire.enabled.first())
        }

    /** Agentwire keeps its own storage, so an already-enabled user stays enabled here. */
    @Test fun agentwireToggle_reflectsExistingStorageAndWritesBack() =
        runTest {
            agentwire.flag.value = true
            val model = vm()
            assertEquals(
                LabsUiState(gesturesEnabled = false, agentwireEnabled = true),
                model.state.first { it.agentwireEnabled },
            )

            model.setAgentwireEnabled(false)
            assertEquals(
                LabsUiState(gesturesEnabled = false, agentwireEnabled = false),
                model.state.first { !it.agentwireEnabled },
            )
            assertEquals(false, gestures.enabled.first())
        }

    private class FakeSidecarPrefs : SidecarPrefs {
        val flag = MutableStateFlow(false)
        override val enabled: Flow<Boolean> = flag

        override suspend fun setEnabled(enabled: Boolean) {
            flag.value = enabled
        }
    }

    private class FakeGlobalFeedPrefs : GlobalFeedPrefs {
        val flag = MutableStateFlow(false)
        override val enabled: Flow<Boolean> = flag

        override suspend fun setEnabled(enabled: Boolean) {
            flag.value = enabled
        }
    }

    private class FakeGesturePrefs : GesturePrefs {
        val flag = MutableStateFlow(false)
        val menuState = MutableStateFlow(GestureMenuConfig())
        override val enabled: Flow<Boolean> = flag

        override suspend fun setEnabled(enabled: Boolean) {
            flag.value = enabled
        }

        override val menu: Flow<GestureMenuConfig> = menuState

        override suspend fun setMenu(config: GestureMenuConfig) {
            menuState.value = config
        }

        override suspend fun replaceMenu(transform: (GestureMenuConfig) -> GestureMenuConfig) {
            menuState.value = transform(menuState.value)
        }

        val orbState = MutableStateFlow(OrbPlacement())
        override val orb: Flow<OrbPlacement> = orbState

        override suspend fun setOrb(placement: OrbPlacement) {
            orbState.value = placement
        }
    }

    private class FakeAgentwirePrefs : AgentwirePrefs(ApplicationProvider.getApplicationContext<Context>()) {
        val flag = MutableStateFlow(false)
        override val enabled: Flow<Boolean> = flag

        override suspend fun setEnabled(enabled: Boolean) {
            flag.value = enabled
        }

        override suspend fun deviceId(): String = "device-under-test"
    }
}
