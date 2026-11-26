package io.github.trevarj.motd.ui.imageviewer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.components.LocalNetworkMediaHttp
import io.github.trevarj.motd.ui.components.RoutedInlineMediaFixture
import io.github.trevarj.motd.ui.theme.MotdTheme
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class VideoViewerScreenTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w320dp-h240dp", fontScale = 2f)
    fun playbackFailureAndRetryUseTheOwningNetwork() {
        RoutedInlineMediaFixture().use { fixture ->
            repeat(2) {
                fixture.server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("invalid video"))
            }
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalNetworkMediaHttp provides fixture.http) {
                        VideoViewerScreen(VIDEO_URL, fixture.networkId, onBack = {})
                    }
                }
            }

            awaitTag("video_viewer_failed")
            assertEquals(1, fixture.server.requestCount)
            val browser = compose.onNodeWithText(label(R.string.chat_link_open_browser))
            browser.assertIsNotDisplayed()
            compose.onNodeWithText(label(R.string.video_viewer_browser_warning)).performScrollTo().assertIsDisplayed()
            browser.performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(label(R.string.image_viewer_back)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.chat_retry)).performScrollTo().performClick()
            compose.waitUntil(10_000) { fixture.server.requestCount == 2 }
            awaitTag("video_viewer_failed")
            assertEquals(List(2) { fixture.networkId }, fixture.selectedNetworks.toList())
        }
    }

    @Test
    fun missingRoutedClientDoesNotCreateAPlayer() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                VideoViewerScreen(VIDEO_URL, networkId = 41L, onBack = {})
            }
        }

        compose.onNodeWithTag("video_viewer_failed").assertIsDisplayed()
        compose.onNodeWithTag("video_viewer_player").assertDoesNotExist()
    }

    @Test
    fun nativeControlsPauseAndLeavingReleasesPlaybackWithoutLosingIntent() {
        RoutedInlineMediaFixture().use { fixture ->
            repeat(2) { fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)) }
            val owner =
                object : LifecycleOwner {
                    override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
                }
            lateinit var root: View
            var backPressed = false
            compose.setContent {
                root = LocalView.current
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalNetworkMediaHttp provides fixture.http) {
                        VideoViewerScreen(VIDEO_URL, fixture.networkId, onBack = { backPressed = true })
                    }
                }
            }
            awaitTag("video_viewer_player")
            compose.onNodeWithContentDescription(label(R.string.video_viewer_loading)).assertIsDisplayed()

            lateinit var first: ExoPlayer
            compose.runOnIdle {
                val view = requireNotNull(root.findPlayerView())
                first = view.player as ExoPlayer
                val playPause = view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)
                assertTrue(view.isControllerFullyVisible)
                assertEquals(0, view.controllerShowTimeoutMs)
                assertFalse(view.controllerHideOnTouch)
                assertEquals(AspectRatioFrameLayout.RESIZE_MODE_FIT, view.resizeMode)
                assertTrue(view.findViewById<View>(androidx.media3.ui.R.id.exo_progress).isShown)
                assertTrue(first.playWhenReady)
                owner.lifecycle.currentState = Lifecycle.State.STARTED
                assertFalse(first.playWhenReady)
                assertFalse(first.isReleased)
                owner.lifecycle.currentState = Lifecycle.State.RESUMED
                assertTrue(first.playWhenReady)
                assertTrue(playPause.performClick())
                assertFalse(first.playWhenReady)
                first.seekTo(2_000L)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                assertTrue(first.isReleased)
            }

            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            awaitTag("video_viewer_player")
            lateinit var restored: ExoPlayer
            compose.runOnIdle {
                val view = requireNotNull(root.findPlayerView())
                restored = view.player as ExoPlayer
                assertFalse(restored.playWhenReady)
                assertEquals(2_000L, restored.currentPosition)
                assertTrue(view.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause).performClick())
                assertTrue(restored.playWhenReady)
            }
            compose.onNodeWithContentDescription(label(R.string.image_viewer_back)).performClick()
            compose.runOnIdle {
                assertTrue(backPressed)
                assertFalse(restored.playWhenReady)
                owner.lifecycle.currentState = Lifecycle.State.CREATED
                assertTrue(restored.isReleased)
            }
        }
    }

    @Test
    @Config(sdk = [28], qualifiers = "w411dp-h240dp")
    @Suppress("DEPRECATION")
    fun fullscreenHidesSystemBarsAndBackRestoresThemWithoutRestartingPlayback() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            lateinit var activity: ComponentActivity
            var backPressed = false
            compose.setContent {
                activity = LocalActivity.current as ComponentActivity
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalNetworkMediaHttp provides fixture.http) {
                        VideoViewerScreen(VIDEO_URL, fixture.networkId, onBack = { backPressed = true })
                    }
                }
            }
            awaitTag("video_viewer_player")
            val decor = activity.window.decorView
            val hiddenBars = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            lateinit var player: ExoPlayer
            val seekBarLocation = IntArray(2)
            compose.runOnIdle {
                val first = requireNotNull(decor.findPlayerView())
                player = first.player as ExoPlayer
                player.pause()
                player.seekTo(2_000L)
                assertEquals(0, decor.systemUiVisibility and hiddenBars)
                val timeline = first.findViewById<View>(androidx.media3.ui.R.id.exo_progress)
                assertTrue(timeline.isShown)
                timeline.getLocationInWindow(seekBarLocation)
            }
            val expand = compose.onNodeWithContentDescription(label(R.string.video_viewer_fullscreen))
            expand.assertIsDisplayed()
            assertTrue(expand.fetchSemanticsNode().boundsInWindow.bottom <= seekBarLocation[1])
            expand.performClick()
            compose.onNodeWithContentDescription(label(R.string.video_viewer_exit_fullscreen)).assertIsDisplayed()
            val viewerBack = compose.onNodeWithContentDescription(label(R.string.image_viewer_back))
            viewerBack.assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(hiddenBars, decor.systemUiVisibility and hiddenBars)
                assertSame(player, decor.findPlayerView()?.player)
                assertFalse(player.playWhenReady)
                assertEquals(2_000L, player.currentPosition)
                activity.onBackPressedDispatcher.onBackPressed()
            }
            viewerBack.assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(0, decor.systemUiVisibility and hiddenBars)
                assertFalse(backPressed)
                assertSame(player, decor.findPlayerView()?.player)
            }
            viewerBack.performClick()
            compose.runOnIdle { assertTrue(backPressed) }
        }
    }

    @Test
    fun playerMenuOpensTheOriginalUrlInABrowser() {
        val app = RuntimeEnvironment.getApplication()
        val browser = ComponentName("test.browser", "test.browser.OpenLink")
        val packageShadow = shadowOf(app.packageManager)
        packageShadow.addActivityIfNotPresent(browser)
        packageShadow.addIntentFilterForActivity(
            browser,
            IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addCategory(Intent.CATEGORY_BROWSABLE)
                addDataScheme("http")
            },
        )
        val url = "$VIDEO_URL?quality=720%2Fp#t=1"
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            compose.setContent {
                MotdTheme(dynamicColor = false) {
                    CompositionLocalProvider(LocalNetworkMediaHttp provides fixture.http) {
                        VideoViewerScreen(url, fixture.networkId, onBack = {})
                    }
                }
            }
            awaitTag("video_viewer_player")
            compose.onNodeWithContentDescription(label(R.string.action_more)).performClick()
            compose.onNodeWithText(label(R.string.video_viewer_browser_warning)).assertIsDisplayed()
            compose.onNodeWithText(label(R.string.chat_link_open_browser)).performClick()
            compose.runOnIdle {
                val intent = requireNotNull(shadowOf(app).nextStartedActivity)
                assertEquals(Intent.ACTION_VIEW, intent.action)
                assertEquals(browser, intent.component)
                assertEquals(url, intent.dataString)
            }
            compose.onNodeWithTag("video_viewer_player").assertIsDisplayed()
        }
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun label(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun View.findPlayerView(): PlayerView? =
        when (this) {
            is PlayerView -> this
            is ViewGroup -> children.firstNotNullOfOrNull { it.findPlayerView() }
            else -> null
        }

    private companion object {
        const val VIDEO_URL = "http://media.invalid/viewer.mp4"
    }
}
