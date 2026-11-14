package io.github.trevarj.motd.ui.splash

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.window.SplashScreen
import android.window.SplashScreenView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.MainActivity
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashLogoUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun loadingCoverCanBeSkippedBeforeRootIsReady() {
        assertTapSkipsUnreadySplash(loadingInitially = true)
    }

    @Test
    fun animatedCoverCanBeSkippedBeforeRootIsReady() {
        assertTapSkipsUnreadySplash(loadingInitially = false)
    }

    private fun assertTapSkipsUnreadySplash(loadingInitially: Boolean) {
        val loading = mutableStateOf(loadingInitially)
        val request = mutableStateOf(if (loadingInitially) null else SplashOverlayRequest(180.dp))
        var finished = 0
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StartupSplashGate(
                request = request.value,
                loading = loading.value,
                rootReady = false,
                onShown = {},
                onFinished = {
                    finished++
                    loading.value = false
                    request.value = null
                },
            ) {
                Box(Modifier.fillMaxSize().testTag("startup_content").clickable { clicks++ })
            }
        }
        if (!loadingInitially) {
            compose.mainClock.advanceTimeBy(80)
            compose.waitForIdle()
        }
        val skipLabel = RuntimeEnvironment.getApplication().getString(R.string.skip_splash_screen)
        val cover = compose.onNodeWithTag("splash_cover")
        cover.assertIsDisplayed()
            .assertContentDescriptionEquals(skipLabel)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals(skipLabel, cover.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("startup_content").assertDoesNotExist()
        assertEquals(0, finished)
        cover.performTouchInput { click() }
        assertEquals("skip completes without waiting for readiness or advancing the clock", 1, finished)
        assertEquals("the dismissing tap is not delivered to the app", 0, clicks)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("splash_cover").assertDoesNotExist()
        compose.onNodeWithTag("startup_content").assertIsDisplayed().assertHasClickAction()
            .performTouchInput { click() }
        assertEquals(1, clicks)
        compose.mainClock.advanceTimeBy(STARTUP_SPLASH_TIMEOUT_MS + 1_000)
        compose.waitForIdle()
        assertEquals("skip cancels both animation and deadline completion", 1, finished)
    }

    @Test
    @Config(sdk = [31])
    fun missingPlatformExitTimesOutAndLateExitCannotReplaySplash() {
        // Attach without onCreate: the splash boundary needs no Hilt graph or service startup.
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        // ShadowActivity returns RoboSplashScreen, whose exit-listener methods are no-ops.
        // Install the SDK's real implementation in that framework-owned slot so both listener
        // registration and clearing, plus the saved late native callback, are exercised.
        val nativeSplash =
            Class
                .forName("android.window.SplashScreen\$SplashScreenImpl")
                .getDeclaredConstructor(Context::class.java)
                .newInstance(activity) as SplashScreen
        ReflectionHelpers.setField(shadowOf(activity), "splashScreen", nativeSplash)

        fun invoke(name: String) =
            MainActivity::class.java
                .getDeclaredMethod(name)
                .apply { isAccessible = true }
                .invoke(activity)

        fun value(name: String): Any? =
            MainActivity::class.java
                .getDeclaredMethod(name)
                .apply { isAccessible = true }
                .invoke(activity)
        MainActivity::class.java
            .getDeclaredMethod("configureStartupSplash", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(activity, true)
        assertTrue(activity.splashScreen === nativeSplash)
        val listenerField = nativeSplash.javaClass.getDeclaredField("mExitAnimationListener").apply { isAccessible = true }
        val lateExit = checkNotNull(listenerField.get(nativeSplash) as? SplashScreen.OnExitAnimationListener)
        var clicks = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StartupSplashGate(
                request = value("getStartupSplashOverlay") as SplashOverlayRequest?,
                loading = value("getStartupSplashLoading") as Boolean,
                rootReady = true,
                onShown = { invoke("removePlatformSplashForOverlay") },
                onFinished = { invoke("finishStartupSplash") },
            ) {
                Box(
                    Modifier
                        .size(80.dp)
                        .background(androidx.compose.ui.graphics.Color.Red)
                        .testTag("startup_content")
                        .clickable { clicks++ },
                )
            }
        }
        compose.onNodeWithTag("startup_content").assertDoesNotExist()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(STARTUP_SPLASH_TIMEOUT_MS + 32)
        compose.waitForIdle()
        compose.onNodeWithTag("startup_content").assertIsDisplayed()
        assertNull("deadline relinquishes the native exit listener", listenerField.get(nativeSplash))
        val lateView =
            SplashScreenView::class.java
                .getDeclaredConstructor(Context::class.java)
                .newInstance(activity)
        compose.runOnIdle {
            lateExit.onSplashScreenExit(lateView)
            invoke("finishStartupSplash")
            invoke("startStartupSplash")
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals(android.view.View.GONE, lateView.visibility)
        compose.onNodeWithTag("splash_logo_mark").assertDoesNotExist()
        compose.onNodeWithTag("startup_content").performTouchInput { click() }
        assertEquals(1, clicks)
    }

    @Test
    fun readySplashShowsActiveMotionAndFinishesWithoutStaticHolds() {
        val request = mutableStateOf<SplashOverlayRequest?>(SplashOverlayRequest(180.dp))
        var shown = 0
        var finished = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StartupSplashGate(
                request = request.value,
                loading = false,
                rootReady = true,
                onShown = { shown++ },
                onFinished = {
                    finished++
                    request.value = null
                },
            ) {
                Box(Modifier.size(180.dp).background(androidx.compose.ui.graphics.Color.Black).testTag("startup_content")) {
                    SplashMarkArtwork(120f / SPARK_CUT_DURATION_MS, 180.dp)
                }
            }
        }
        val firstFrame = compose.onNodeWithTag("splash_logo_mark", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        assertEquals(1, shown)
        assertEquals(0, finished)
        val moving = compose.onNodeWithTag("splash_logo_mark", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        assertFalse("startup starts at the active cut, not the opening hold", firstFrame.sameAs(moving))
        compose.onNodeWithTag("startup_content").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(STARTUP_SPLASH_READY_DURATION_MS.toLong() - 80 + 48)
        compose.waitForIdle()
        assertEquals(1, finished)
        compose.onNodeWithTag("startup_content").assertIsDisplayed()
        val activeStart = compose.onNodeWithTag("startup_content").captureToImage().asAndroidBitmap()
        assertTrue("startup omits the authored opening hold", firstFrame.sameAs(activeStart))
        compose.mainClock.advanceTimeBy(STARTUP_SPLASH_TIMEOUT_MS)
        compose.waitForIdle()
        assertEquals(1, finished)
    }

    @Test
    fun lateHandoffDoesNotRestartTheCoverDeadline() {
        val loading = mutableStateOf(true)
        val ready = mutableStateOf(false)
        val request = mutableStateOf<SplashOverlayRequest?>(null)
        var finished = 0
        var shown = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StartupSplashGate(
                request = request.value,
                loading = loading.value,
                onShown = { shown++ },
                rootReady = ready.value,
                onFinished = {
                    finished++
                    loading.value = false
                    request.value = null
                },
            ) {
                Box(Modifier.size(80.dp).testTag("startup_content"))
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("startup_content").assertDoesNotExist()
        assertEquals(0, finished)
        val coverStartedAt = compose.mainClock.currentTime
        // Start from a composed, covered root on the rule's queued effect dispatcher;
        // advancing before readiness makes the handoff's timing depend on initial dispatch.
        compose.mainClock.advanceTimeBy(STARTUP_SPLASH_TIMEOUT_MS - 500)
        compose.waitForIdle()
        assertEquals("the original cover still owns the deadline", 0, finished)
        compose.onNodeWithTag("startup_content").assertDoesNotExist()
        compose.runOnIdle {
            loading.value = false
            request.value = SplashOverlayRequest(180.dp)
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            shown == 1
        }
        assertEquals("the original deadline has not expired", 0, finished)
        compose.onNodeWithTag("startup_content").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(coverStartedAt + STARTUP_SPLASH_TIMEOUT_MS - 80 - compose.mainClock.currentTime)
        compose.waitForIdle()
        compose.runOnIdle { ready.value = true }
        compose.mainClock.advanceTimeBy(coverStartedAt + STARTUP_SPLASH_TIMEOUT_MS + 32 - compose.mainClock.currentTime)
        compose.waitForIdle()
        assertEquals(1, finished)
        compose.onNodeWithTag("startup_content").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        assertEquals(1, finished)
    }

    @Test
    fun readinessDuringMotionAcceleratesWithoutReplayingOrRepeatingNativeHandoff() {
        assertReadinessRetargets(afterMs = 300, remainingBoundMs = STARTUP_SPLASH_READY_DURATION_MS.toLong())
    }

    @Test
    fun readinessNearTheEndDoesNotExtendPlaybackAndCompletesToTheOriginalRaster() {
        assertReadinessRetargets(afterMs = 640, remainingBoundMs = 740 - 640)
    }

    private fun assertReadinessRetargets(afterMs: Long, remainingBoundMs: Long) {
        val request = mutableStateOf<SplashOverlayRequest?>(SplashOverlayRequest(180.dp))
        val ready = mutableStateOf(false)
        var shown = 0
        var finished = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            StartupSplashGate(
                request = request.value,
                loading = false,
                rootReady = ready.value,
                onShown = { shown++ },
                onFinished = { finished++ },
            ) {
                Box(Modifier.size(180.dp).background(androidx.compose.ui.graphics.Color.Black)) {
                    SplashMarkArtwork(1f, 180.dp, Modifier.testTag("original_mark"))
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            shown == 1
        }
        compose.mainClock.advanceTimeBy(afterMs)
        compose.waitForIdle()
        val before = compose.onNodeWithTag("splash_logo_mark", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        assertEquals(0, finished)
        compose.runOnIdle { ready.value = true }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        val after = compose.onNodeWithTag("splash_logo_mark", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        fun openedPixels(bitmap: Bitmap): Int =
            (bitmap.width / 3 until bitmap.width * 2 / 3).sumOf { x ->
                (bitmap.height / 3 until bitmap.height * 2 / 3).count { y -> bitmap.getPixel(x, y) == Color.BLACK }
            }
        assertTrue("readiness must not replay the intact face", openedPixels(after) >= openedPixels(before))
        compose.mainClock.advanceTimeBy(remainingBoundMs - 64 + 48)
        compose.waitForIdle()
        assertEquals("readiness never extends normal remaining motion", 1, finished)
        assertEquals("readiness must not repeat the native handoff", 1, shown)
        val completed = compose.onNodeWithTag("splash_logo_mark", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        compose.runOnIdle { request.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val original = compose.onNodeWithTag("original_mark").captureToImage().asAndroidBitmap()
        assertTrue("playback reaches the original painter before completion", completed.sameAs(original))
    }

    @Test
    fun sparkCutOpensTheActualApertureAndSettlesToTheOriginal() {
        val progress = mutableFloatStateOf(0f)
        compose.setContent {
            Box(Modifier.size(180.dp).background(androidx.compose.ui.graphics.Color.Black)) {
                SplashMarkArtwork(progress.floatValue, 180.dp, Modifier.testTag("splash_logo"))
            }
        }

        fun capture(at: Float): Bitmap {
            compose.runOnIdle { progress.floatValue = at }
            return compose.onNodeWithTag("splash_logo").captureToImage().asAndroidBitmap()
        }
        val intact = capture(0f)
        val cutting = capture(0.5f)
        val settled = capture(0.95f)
        val original = capture(1f)
        val proofs = File("build/reports/spark-cut").apply { mkdirs() }
        listOf("intact" to intact, "cutting" to cutting, "settled" to settled, "original" to original).forEach { (name, bitmap) ->
            File(proofs, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertFalse("The cut must visibly change the intact ceramic", intact.sameAs(cutting))
        assertTrue("The final still is the unchanged original raster", settled.sameAs(original))
        assertEquals(Color.BLACK, original.getPixel(original.width / 2, original.height / 2))
        assertTrue(Color.red(intact.getPixel(intact.width / 2, intact.height / 2)) > 180)
        // A substantial open region already exists behind the moving contact before completion.
        val opened =
            (0 until cutting.width).sumOf { x ->
                (0 until cutting.height).count { y ->
                    Color.red(intact.getPixel(x, y)) > 180 && cutting.getPixel(x, y) == Color.BLACK
                }
            }
        assertTrue("The hole must open progressively, not appear only at rest", opened > 40)
        // The former incoming ray entered the upper-right exterior. It must remain empty.
        for (image in listOf(intact, cutting, settled)) {
            assertEquals(Color.BLACK, image.getPixel(image.width * 9 / 10, image.height / 10))
            val before = Color.red(original.getPixel(image.width / 4, image.height / 2))
            val after = Color.red(image.getPixel(image.width / 4, image.height / 2))
            // Density-selected originals and the full-resolution intact face can round by one tone.
            assertTrue("Untouched outer ceramic retains its shading", kotlin.math.abs(before - after) <= 2)
        }
    }
}
