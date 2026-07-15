package io.github.trevarj.motd

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import io.github.trevarj.motd.ui.theme.MotdMotion
import org.junit.Rule
import org.junit.Test

/** Ensures custom motion settles immediately when Android requests reduced motion. */
@OptIn(ExperimentalTestApi::class)
class MotionDurationScaleTest {
    @get:Rule
    val compose: ComposeContentTestRule = createComposeRule(ReducedMotionScale)

    @Test
    fun customMotionSettlesUnderSystemReducedMotion() {
        compose.setContent { MotionProbe() }

        compose.waitForIdle()

        compose.onNodeWithTag("motion_probe").assertTextEquals("settled")
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
