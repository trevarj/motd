package io.github.trevarj.motd.ui.components

import android.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.identityRamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChannelLogoBadgeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun motd_channel_renders_the_bundled_logo_without_matching_other_channels() {
        composeRule.setContent {
            MotdTheme(dynamicColor = false) {
                Row {
                    Avatar(name = "#motd", isChannel = true)
                    Avatar(name = "#MOTD", isChannel = true)
                    Avatar(name = "#motd-help", isChannel = true)
                    Avatar(name = "#notmotd", isChannel = true)
                    Avatar(name = "#general", isChannel = true)
                }
            }
        }

        composeRule.onAllNodesWithTag("motd_channel_logo").assertCountEquals(2)
        composeRule.onAllNodesWithTag("motd_channel_logo")[0].assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun motd_badge_preserves_theme_background_highlight_and_ceramic_shading() {
        val expectedBackgrounds = mutableMapOf<ColorThemePreset, Int>()
        val expectedBorders = mutableMapOf<ColorThemePreset, Int>()
        val themes = listOf(ColorThemePreset.LIGHT, ColorThemePreset.DARK)
        composeRule.setContent {
            Row {
                for (theme in themes) {
                    MotdTheme(themePreset = theme, dynamicColor = false, syncSystemBars = false) {
                        val background = LocalNickColors.current.avatar("#motd")
                        expectedBackgrounds[theme] = background.toArgb()
                        expectedBorders[theme] =
                            identityRamp(background)
                                .highlight
                                .copy(alpha = 0.9f)
                                .compositeOver(background)
                                .toArgb()
                        Surface(modifier = Modifier.padding(12.dp)) {
                            Avatar(
                                name = "#motd",
                                isChannel = true,
                                modifier = Modifier.testTag("motd_badge_$theme"),
                            )
                        }
                    }
                }
            }
        }

        for (theme in themes) {
            // Capture the actual Avatar, not a reimplementation of its drawing.
            val avatar = composeRule.onNodeWithTag("motd_badge_$theme").captureToImage().asAndroidBitmap()
            assertEquals(expectedBackgrounds.getValue(theme), avatar.getPixel(2, avatar.height / 2))
            val border = avatar.getPixel(avatar.width / 2, 0)
            val expected = expectedBorders.getValue(theme)
            assertTrue("theme highlight red", kotlin.math.abs(Color.red(border) - Color.red(expected)) <= 3)
            assertTrue("theme highlight green", kotlin.math.abs(Color.green(border) - Color.green(expected)) <= 3)
            assertTrue("theme highlight blue", kotlin.math.abs(Color.blue(border) - Color.blue(expected)) <= 3)
        }
        for (index in themes.indices) {
            val mark = composeRule.onAllNodesWithTag("motd_channel_logo")[index].captureToImage().asAndroidBitmap()
            val ceramicTones =
                List(mark.width * mark.height) { pixel -> mark.getPixel(pixel % mark.width, pixel / mark.width) }
                    .filter { Color.red(it) == Color.green(it) && Color.red(it) == Color.blue(it) }
                    .map(Color::red)
                    .toSet()
            assertTrue("actual #motd mark retains ceramic nuance", ceramicTones.size > 8)
            assertTrue("actual #motd mark is not flat tint", ceramicTones.max() - ceramicTones.min() > 15)
        }
    }
}
