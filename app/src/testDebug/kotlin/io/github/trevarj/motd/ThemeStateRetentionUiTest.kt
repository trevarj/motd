package io.github.trevarj.motd

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.avatar.IrcSpriteV2Renderer
import io.github.trevarj.motd.avatar.IrcSpriteV2Theme
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.ui.components.IrcSpriteV2Avatar
import io.github.trevarj.motd.ui.components.isAppliedThemeDark
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NickColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ThemeStateRetentionUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchingFromDynamicToFixedPalette_preservesStatefulContent() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        lateinit var selectTheme: (ColorThemePreset) -> Unit
        compose.setContent {
            var theme by remember { mutableStateOf(ColorThemePreset.DARK) }
            selectTheme = { theme = it }
            MotdTheme(themePreset = theme, dynamicColor = true) {
                var marker by remember { mutableStateOf("initial") }
                Button(
                    onClick = { marker = "retained" },
                    modifier = Modifier.testTag("theme_state_marker"),
                ) {
                    Text(marker)
                }
            }
        }

        compose.onNodeWithTag("theme_state_marker").performClick().assertTextEquals("retained")
        compose.runOnUiThread { selectTheme(ColorThemePreset.AYU_DARK) }
        compose.waitForIdle()

        compose.onNodeWithTag("theme_state_marker").assertTextEquals("retained")
    }

    @Test
    fun spriteV2_recomposesWithTheResolvedLightAndDarkPalette() {
        lateinit var selectTheme: (ColorThemePreset) -> Unit
        var expected: Bitmap? = null
        compose.setContent {
            var theme by remember { mutableStateOf(ColorThemePreset.LIGHT) }
            selectTheme = { theme = it }
            // Keep the nickname hue fixed so this test isolates the sprite palette transition.
            val nickColors =
                remember {
                    NickColorScheme(
                        enabled = true,
                        palette = NickColorPalette.CLASSIC,
                        overrides = emptyMap(),
                        isDark = false,
                    )
                }
            MotdTheme(themePreset = theme, dynamicColor = false) {
                androidx.compose.runtime.CompositionLocalProvider(LocalNickColors provides nickColors) {
                    val context = LocalContext.current
                    val density = LocalDensity.current
                    val scheme = MaterialTheme.colorScheme
                    val dark = isAppliedThemeDark()
                    val accent = LocalNickColors.current.avatar("alice")
                    val size = 48.dp
                    val sizePx = with(density) { size.roundToPx() }
                    val base = if (dark) scheme.surfaceContainerHighest else scheme.surfaceContainerHigh
                    SideEffect {
                        expected =
                            IrcSpriteV2Renderer.render(
                                context = context,
                                name = "alice",
                                accent = accent.toArgb(),
                                sizePx = sizePx,
                                baseColor = base.toArgb(),
                                ringColor = accent.copy(alpha = 0.52f).toArgb(),
                                theme = if (dark) IrcSpriteV2Theme.DARK else IrcSpriteV2Theme.LIGHT,
                            )
                    }
                    IrcSpriteV2Avatar("alice", size, Modifier.testTag("sprite_v2_theme"))
                }
            }
        }

        fun assertAvatarMatchesExpected() {
            compose.waitForIdle()
            val actual = compose.onNodeWithTag("sprite_v2_theme").captureToImage().asAndroidBitmap()
            val rendered = requireNotNull(expected)
            assertEquals(rendered.width, actual.width)
            assertEquals(rendered.height, actual.height)
            // The circular clip only changes edge anti-aliasing; the center proves the renderer
            // received the active palette rather than a stale dark cache entry.
            assertEquals(rendered.getPixel(rendered.width / 2, rendered.height / 2), actual.getPixel(actual.width / 2, actual.height / 2))
        }

        assertAvatarMatchesExpected()
        compose.runOnUiThread { selectTheme(ColorThemePreset.DARK) }
        assertAvatarMatchesExpected()
        compose.runOnUiThread { selectTheme(ColorThemePreset.LIGHT) }
        assertAvatarMatchesExpected()
    }
}
