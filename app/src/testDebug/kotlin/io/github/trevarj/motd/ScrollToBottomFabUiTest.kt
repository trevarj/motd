package io.github.trevarj.motd

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.trevarj.motd.ui.chat.ScrollToBottomFab
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ScrollToBottomFabUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun physicalTapDispatchesTheFabClick() {
        var taps = 0
        var longPresses = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ScrollToBottomFab(
                    visible = true,
                    unread = 0,
                    mentionPending = false,
                    onClick = { taps++ },
                    onLongClick = { longPresses++ },
                )
            }
        }

        compose
            .onNodeWithTag("chat_scroll_to_bottom_fab")
            .performTouchInput { click() }

        compose.runOnIdle {
            assertEquals(1, taps)
            assertEquals(0, longPresses)
        }
    }

    @Test fun accessibilityActionsFollowTheCurrentJumpDestination() {
        val mentionPending = mutableStateOf(true)
        var taps = 0
        var newestJumps = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ScrollToBottomFab(
                    visible = true,
                    unread = 0,
                    mentionPending = mentionPending.value,
                    onClick = { taps++ },
                    onLongClick = { newestJumps++ },
                )
            }
        }

        val fab = compose.onNodeWithTag("chat_scroll_to_bottom_fab")
        fab.assertContentDescriptionEquals("Next unread mention").performClick()
        val newest = fab.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        assertEquals("Jump to newest", newest.label)
        compose.runOnIdle {
            assertEquals(1, taps)
            assertEquals(0, newestJumps)
            assertTrue(newest.action())
            assertEquals(1, taps)
            assertEquals(1, newestJumps)
            mentionPending.value = false
        }

        fab.assertContentDescriptionEquals("Jump to newest")
        assertFalse(fab.fetchSemanticsNode().config.contains(SemanticsActions.CustomActions))
    }
}
