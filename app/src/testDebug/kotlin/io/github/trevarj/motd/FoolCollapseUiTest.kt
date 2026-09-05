package io.github.trevarj.motd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.ui.chat.FoolCollapseChip
import io.github.trevarj.motd.ui.theme.MotdTheme
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
class FoolCollapseUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test fun collapse_affordance_fills_its_timeline_width_and_remains_tappable() {
        var collapsed = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Box(Modifier.width(240.dp).testTag("timeline_width")) {
                    FoolCollapseChip("fool", "chat_fool_collapse_msgid-1") { collapsed = true }
                }
            }
        }

        compose
            .onNodeWithTag("chat_fool_collapse_msgid-1")
            .assertWidthIsEqualTo(240.dp)
            .performTouchInput {
                click(Offset(with(compose.density) { 240.dp.toPx() - 2f }, with(compose.density) { 12.dp.toPx() }))
            }
        assertTrue(collapsed)
    }
}
