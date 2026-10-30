package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.WallpaperSelection
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
class ScenicWallpaperUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun artworkFollowsLiveSystemModeOrResolvedAppModeAccordingToSelection() {
        var systemDark by mutableStateOf(false)
        var appTheme by mutableStateOf(ColorThemePreset.DARK)
        var selection by mutableStateOf(
            WallpaperSelection(
                preset = ChatWallpaperPreset.MIDNIGHT_TERMINAL,
                intensity = 100,
                scenicAnimationEnabled = false,
            ),
        )
        val day = artwork("light")
        val night = artwork("dark")
        try {
            compose.setContent {
                val configuration =
                    Configuration(LocalConfiguration.current).apply {
                        uiMode =
                            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (systemDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(1f)) {
                    MotdTheme(themePreset = appTheme, dynamicColor = false, syncSystemBars = false) {
                        Box(Modifier.size(256.dp, 448.dp).testTag("scenic")) {
                            ChatWallpaperBackground(selection, Modifier.matchParentSize())
                        }
                    }
                }
            }
            awaitArtwork(day)
            compose.runOnIdle { systemDark = true }
            awaitArtwork(night)
            compose.runOnIdle {
                systemDark = false
                selection = selection.copy(scenicFollowSystem = false)
            }
            awaitArtwork(night)
            compose.runOnIdle { appTheme = ColorThemePreset.LIGHT }
            awaitArtwork(day)
            compose.runOnIdle {
                systemDark = true
                selection = selection.copy(scenicFollowSystem = true)
            }
            awaitArtwork(night)
        } finally {
            day.recycle()
            night.recycle()
        }
    }

    private fun awaitArtwork(expected: Bitmap) {
        val expectedPixels = pixels(expected)
        compose.waitUntil(10_000) {
            val actual = compose.onNodeWithTag("scenic").captureToImage().asAndroidBitmap()
            actual.width == expected.width && actual.height == expected.height && pixels(actual).contentEquals(expectedPixels)
        }
    }

    /** Bitmap.sameAs also compares storage configuration; scenic image correctness is exact ARGB. */
    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun artwork(mode: String): Bitmap =
        ApplicationProvider
            .getApplicationContext<Context>()
            .assets
            .open("chat-wallpapers/scenic/retro-computer-room-v3-$mode.png")
            .use { requireNotNull(BitmapFactory.decodeStream(it)) }
}
