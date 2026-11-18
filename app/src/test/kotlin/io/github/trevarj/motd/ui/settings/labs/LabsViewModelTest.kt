package io.github.trevarj.motd.ui.settings.labs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.EbooksLabsPrefs
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.dickord.DickordLabsPrefs
import io.github.trevarj.motd.gesture.GestureMenuConfig
import io.github.trevarj.motd.gesture.GesturePrefs
import io.github.trevarj.motd.gesture.radial.OrbPlacement
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
    private val globalFeed = FakeGlobalFeedPrefs()
    private val dickord = FakeDickordLabsPrefs()
    private val ebooks = FakeEbooksLabsPrefs()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun vm() = LabsViewModel(gestures, globalFeed, dickord, ebooks)

    @Test fun everyLabStartsOff() =
        runTest {
            assertEquals(
                LabsUiState(
                    gesturesEnabled = false,
                    globalFeedEnabled = false,
                    dickordEnabled = false,
                    ebooksEnabled = false,
                ),
                vm().state.first(),
            )
        }

    /** The feed's entry points read this flag, so its default decides whether they render at all. */
    @Test fun globalFeedToggle_writesOnlyTheFeedStore() =
        runTest {
            val model = vm()
            assertEquals(false, globalFeed.enabled.first())

            model.setGlobalFeedEnabled(true)
            assertEquals(
                LabsUiState(gesturesEnabled = false, globalFeedEnabled = true),
                model.state.first { it.globalFeedEnabled },
            )
            assertEquals(false, gestures.enabled.first())
        }

    @Test fun dickordToggle_reflectsExistingStorageAndWritesOnlyTheDickordStore() =
        runTest {
            dickord.flag.value = true
            val model = vm()
            assertEquals(true, model.state.first { it.dickordEnabled }.dickordEnabled)

            model.setDickordEnabled(false)
            assertEquals(false, model.state.first { !it.dickordEnabled }.dickordEnabled)
            assertEquals(false, gestures.enabled.first())
            assertEquals(false, globalFeed.enabled.first())
        }

    @Test fun ebooksToggle_startsOffAndWritesOnlyItsOwnStore() =
        runTest {
            dickord.flag.value = true
            val model = vm()
            assertEquals(false, ebooks.enabled.first())
            assertEquals(false, model.state.first { it.dickordEnabled }.ebooksEnabled)

            model.setEbooksEnabled(true)
            assertEquals(true, model.state.first { it.ebooksEnabled && it.dickordEnabled }.ebooksEnabled)
            assertEquals(false, gestures.enabled.first())
            assertEquals(false, globalFeed.enabled.first())
            assertEquals(true, dickord.enabled.first())

            model.setEbooksEnabled(false)
            assertEquals(false, model.state.first { !it.ebooksEnabled }.ebooksEnabled)
            assertEquals(false, ebooks.enabled.first())
            assertEquals(true, dickord.enabled.first())
        }

    @Test fun gestureToggle_writesOnlyTheGestureStore() =
        runTest {
            val model = vm()
            model.setGesturesEnabled(true)
            assertEquals(
                LabsUiState(gesturesEnabled = true),
                model.state.first { it.gesturesEnabled },
            )
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

    private class FakeDickordLabsPrefs : DickordLabsPrefs(ApplicationProvider.getApplicationContext<Context>()) {
        val flag = MutableStateFlow(false)
        override val enabled: Flow<Boolean> = flag

        override suspend fun setEnabled(enabled: Boolean) {
            flag.value = enabled
        }
    }

    private class FakeEbooksLabsPrefs : EbooksLabsPrefs(ApplicationProvider.getApplicationContext<Context>()) {
        val flag = MutableStateFlow(false)
        override val enabled: Flow<Boolean> = flag

        override suspend fun setEnabled(enabled: Boolean) {
            flag.value = enabled
        }
    }
}
