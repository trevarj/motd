package io.github.trevarj.motd

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.ui.chatlist.SeasonalChatListTitle
import io.github.trevarj.motd.ui.chatlist.SeasonalWordmark
import io.github.trevarj.motd.ui.chatlist.SeasonalWordmarkKind
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Ensures custom motion settles immediately when Android requests reduced motion. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class MotionDurationScaleTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule(ReducedMotionScale)

    @Test
    fun customMotionSettlesUnderSystemReducedMotion() {
        compose.setContent { MotionProbe() }

        compose.waitForIdle()

        compose.onNodeWithTag("motion_probe").assertTextEquals("settled")
    }

    @Test
    fun seasonalBatShowsOneStillPoseUnderReducedMotion() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Box(Modifier.testTag("seasonal_test_bar").padding(top = 28.dp)) {
                    SeasonalChatListTitle(SeasonalWordmark(SeasonalWordmarkKind.HALLOWEEN), showStatus = false)
                }
            }
        }
        compose.mainClock.autoAdvance = false
        val action = compose.onNodeWithTag("chatlist_seasonal_action")

        fun pixels() = compose.onNodeWithTag("seasonal_test_bar").captureToImage().asAndroidBitmap()

        val idle = pixels()
        action.performClick()
        compose.mainClock.advanceTimeBy(128)
        val still = pixels()
        assertFalse(idle.sameAs(still))
        compose.mainClock.advanceTimeBy(250)
        assertTrue(still.sameAs(pixels()))
        compose.mainClock.advanceTimeBy(950)
        assertTrue(idle.sameAs(pixels()))
    }

    @Composable
    private fun MotionProbe() {
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) {
            progress.animateTo(1f, MotdMotion.softSpring)
        }
        Text(
            text = if (progress.value == 1f) "settled" else "moving",
            modifier = Modifier.testTag("motion_probe"),
        )
    }

    private object ReducedMotionScale : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }
}
