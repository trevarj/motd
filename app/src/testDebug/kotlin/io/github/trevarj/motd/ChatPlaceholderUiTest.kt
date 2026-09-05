package io.github.trevarj.motd

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.ui.chat.MessagePlaceholderRow
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChatPlaceholderUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pagingPlaceholderReservesNonzeroRowHeight() {
        compose.setContent {
            MotdTheme {
                Box(Modifier.testTag("placeholder_container")) {
                    MessagePlaceholderRow()
                }
            }
        }

        compose.onNodeWithTag("placeholder_container").assertHeightIsAtLeast(48.dp)
    }

    /**
     * The skeleton stands in for a row of the conversation's actual size, not a fixed 48dp bar.
     * A fixed height is what made every placeholder -> real swap reflow the list below it.
     */
    @Test
    fun pagingPlaceholderAdoptsTheEstimatedRowHeight() {
        compose.setContent {
            MotdTheme {
                Box(Modifier.testTag("placeholder_container")) {
                    MessagePlaceholderRow { 120.dp }
                }
            }
        }

        compose.onNodeWithTag("placeholder_container").assertHeightIsEqualTo(120.dp)
    }
}
