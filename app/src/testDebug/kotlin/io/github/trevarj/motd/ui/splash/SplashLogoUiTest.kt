package io.github.trevarj.motd.ui.splash

import android.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.SplashScreenAnimation
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
@Config(qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SplashLogoUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun movingLightIsClippedToTheRasterMark() {
        compose.setContent {
            Box(Modifier.size(240.dp, 400.dp).background(androidx.compose.ui.graphics.Color.Black)) {
                Column {
                    SplashMarkArtwork(
                        SplashScreenAnimation.EDGE_TRACE,
                        progress = 0f,
                        size = 180.dp,
                        modifier = Modifier.testTag("splash_logo_baseline"),
                    )
                    SplashMarkArtwork(
                        SplashScreenAnimation.EDGE_TRACE,
                        progress = 0.5f,
                        size = 180.dp,
                        modifier = Modifier.testTag("splash_logo_moving"),
                    )
                }
            }
        }

        val baseline = compose.onNodeWithTag("splash_logo_baseline").captureToImage().asAndroidBitmap()
        val image = compose.onNodeWithTag("splash_logo_moving").captureToImage().asAndroidBitmap()
        // Capture includes the black splash backing. Light must not reach either source hole.
        assertEquals(Color.BLACK, image.getPixel(image.width / 2, image.height / 2))
        assertEquals(Color.BLACK, image.getPixel(0, 0))
        assertFalse("The moving light must alter existing ceramic pixels", baseline.sameAs(image))
        val changedChannels =
            (0 until image.width).sumOf { x ->
                (0 until image.height).count { y ->
                    val before = baseline.getPixel(x, y)
                    val after = image.getPixel(x, y)
                    maxOf(
                        kotlin.math.abs(Color.red(after) - Color.red(before)),
                        kotlin.math.abs(Color.green(after) - Color.green(before)),
                        kotlin.math.abs(Color.blue(after) - Color.blue(before)),
                    ) >= 18
                }
            }
        assertTrue("The preview needs a clearly perceptible local ceramic light", changedChannels >= 40)
    }
}
