package io.github.trevarj.motd.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w411dp-h891dp")
class ComposerImeRestoreTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    private val imeHeightPx = mutableStateOf(320)
    private var inputSessions = 0
    private var focusClears = 0
    private var keyboardShows = 0
    private var keyboardHides = 0
    private var originalInputSessions = 0
    private var originalKeyboardShows = 0

    @Test
    fun slowOpeningKeepsFocusAndTheExistingInputConnection() {
        openPickerOverFocusedKeyboard()
        beginRestore()

        // A slow keyboard can take substantially longer than six frames to report any inset.
        advanceFrames(12)
        assertRestoreRequests(retries = 0)
        assertEquals(320, panelHeight())
        compose.onNodeWithTag("chat_composer_field").assertIsFocused()

        listOf(40, 80, 120, 160, 200, 240, 280).forEach { height ->
            compose.runOnIdle { imeHeightPx.value = height }
            compose.mainClock.advanceTimeByFrame()
            assertEquals(320, height + panelHeight())
            assertRestoreRequests(retries = 0)
        }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(0, panelHeight())
        assertRestoreRequests(retries = 0)
        compose.onNodeWithTag("chat_composer_field").assertIsFocused()
    }

    @Test
    fun zeroInsetRetriesFocusOnlyAfterTheTimeoutAndCanStillRestore() {
        openPickerOverFocusedKeyboard()
        beginRestore()

        compose.mainClock.advanceTimeBy(1_400)
        assertRestoreRequests(retries = 0)
        compose.onNodeWithTag("chat_composer_field").assertIsFocused()
        assertEquals(320, panelHeight())

        compose.mainClock.advanceTimeBy(200)
        assertRestoreRequests(retries = 1)
        compose.onNodeWithTag("chat_composer_field").assertIsFocused()
        compose.runOnIdle { imeHeightPx.value = 200 }
        // Observe rendered frames, not only virtual time: Android measure/layout is not clock-driven.
        var previousPanelHeight = 320
        val residualHeights = mutableSetOf<Int>()
        for (frame in 1..60) {
            compose.mainClock.advanceTimeByFrame()
            val height = panelHeight()
            assertTrue("The restoring panel must not grow", height in 0..previousPanelHeight)
            previousPanelHeight = height
            if (height in 1..119) residualHeights += height
            assertRestoreRequests(retries = 1)
            compose.onNodeWithTag("chat_composer_field").assertIsFocused()
            if (height == 0) break
        }
        assertTrue("The residual strip must animate after the keyboard arrives", residualHeights.size >= 2)
        assertEquals(0, panelHeight())
        assertRestoreRequests(retries = 1)
    }

    @Test
    fun refusedKeyboardReopensTheCapturedPickerAfterOneRetry() {
        openPickerOverFocusedKeyboard()
        beginRestore()

        compose.mainClock.advanceTimeBy(1_400)
        assertRestoreRequests(retries = 0)
        compose.mainClock.advanceTimeBy(200)
        assertRestoreRequests(retries = 1)
        compose.mainClock.advanceTimeBy(1_600)

        assertEquals(320, panelHeight())
        compose.onNodeWithContentDescription("Close emoji picker").assertExists()
        compose.onNodeWithTag("chat_composer_emoji_picker").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(2_000)
        assertRestoreRequests(retries = 1)
        assertEquals(320, panelHeight())
    }

    @Test
    fun reopeningDuringRestoreCancelsThePendingFocusRetry() {
        openPickerOverFocusedKeyboard()
        beginRestore()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.mainClock.advanceTimeByFrame()

        compose.mainClock.advanceTimeBy(3_500)
        assertEquals(320, panelHeight())
        assertRestoreRequests(retries = 0)
        compose.onNodeWithContentDescription("Close emoji picker").assertExists()
        compose.onNodeWithTag("chat_composer_field").assertIsFocused()

        // A fresh dismissal still restores normally; the cancelled attempt cannot remove its panel.
        beginRestore()
        compose.runOnIdle { imeHeightPx.value = 280 }
        compose.mainClock.advanceTimeBy(400)
        assertEquals(0, panelHeight())
        compose.runOnIdle {
            assertEquals(0, focusClears)
            assertEquals(originalInputSessions, inputSessions)
            assertEquals(originalKeyboardShows + 2, keyboardShows)
        }
    }

    @Test
    fun shorterKeyboardContinuouslyReleasesTheResidualViewport() {
        openPickerOverFocusedKeyboard()
        beginRestore()
        compose.runOnIdle {
            imeHeightPx.value = 120
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitUntil(timeoutMillis = 5_000) { panelHeight() != 320 }
        assertEquals(320, 120 + panelHeight())

        val viewportHeights = mutableListOf(120 + panelHeight())
        for (frame in 1..60) {
            compose.mainClock.advanceTimeByFrame()
            val height = panelHeight()
            viewportHeights += 120 + height
            if (height == 0) break
        }
        assertTrue("The viewport must shrink continuously", viewportHeights.zipWithNext().all { (before, after) -> after <= before })
        assertTrue("The residual strip must animate, not disappear in one jump", viewportHeights.filter { it in 121..319 }.distinct().size >= 2)
        assertEquals(120, viewportHeights.last())
        assertRestoreRequests(retries = 0)
    }

    private fun openPickerOverFocusedKeyboard() {
        val keyboard =
            object : SoftwareKeyboardController {
                override fun show() {
                    keyboardShows++
                }

                override fun hide() {
                    keyboardHides++
                }
            }
        val inputInterceptor =
            object : PlatformTextInputInterceptor {
                override suspend fun interceptStartInputMethod(
                    request: PlatformTextInputMethodRequest,
                    nextHandler: PlatformTextInputSession,
                ): Nothing {
                    inputSessions++
                    return nextHandler.startInputMethod(request)
                }
            }
        compose.setContent {
            val delegate = LocalFocusManager.current
            val focusManager =
                remember(delegate) {
                    object : FocusManager by delegate {
                        override fun clearFocus(force: Boolean) {
                            focusClears++
                            delegate.clearFocus(force)
                        }
                    }
                }
            InterceptPlatformTextInput(interceptor = inputInterceptor) {
                CompositionLocalProvider(
                    LocalFocusManager provides focusManager,
                    LocalSoftwareKeyboardController provides keyboard,
                ) {
                    MotdTheme(dynamicColor = false) {
                        Composer(
                            value = TextFieldValue("draft"),
                            onValueChange = {},
                            onSend = {},
                            enabled = true,
                            imeHeightPx = imeHeightPx.value,
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("chat_composer_field").performClick().assertIsFocused()
        compose.onNodeWithTag("chat_composer_tools").performClick()
        compose.onNodeWithTag("chat_composer_emoji").performClick()
        compose.runOnIdle { imeHeightPx.value = 0 }
        compose.waitForIdle()
        assertEquals(320, panelHeight())
        compose.runOnIdle {
            assertEquals(1, inputSessions)
            assertEquals(0, focusClears)
            assertTrue(keyboardHides > 0)
            originalInputSessions = inputSessions
            originalKeyboardShows = keyboardShows
        }
        compose.mainClock.autoAdvance = false
    }

    private fun beginRestore() {
        compose.onNodeWithTag("chat_composer_tools").performClick()
        advanceFrames(3)
    }

    private fun advanceFrames(count: Int) {
        repeat(count) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun panelHeight(): Int =
        compose
            .onNodeWithTag("chat_composer_emoji_panel")
            .fetchSemanticsNode()
            .size.height

    private fun assertRestoreRequests(retries: Int) {
        compose.runOnIdle {
            assertEquals("Only a timed-out zero inset may clear focus", retries, focusClears)
            assertEquals(originalInputSessions + retries, inputSessions)
            assertEquals(originalKeyboardShows + 1 + retries, keyboardShows)
        }
    }
}
