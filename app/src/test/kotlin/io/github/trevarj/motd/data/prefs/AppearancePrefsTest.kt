package io.github.trevarj.motd.data.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppearancePrefsTest {
    private val prefs: AppearancePrefs = AppearancePrefsImpl(ApplicationProvider.getApplicationContext<Context>())

    @Test fun defaults_areSystemAndChatterAtForty() {
        assertEquals(
            AppearanceConfig(
                ColorThemePreset.SYSTEM,
                WallpaperSelection(ChatWallpaperPreset.CHATTER, 40),
                100,
                100,
            ),
            AppearanceConfig(),
        )
    }

    @Test fun themeAndWallpaper_roundTrip() = runTest {
        prefs.setTheme(ColorThemePreset.KANAGAWA_WAVE)
        prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.RELAY, 73))
        assertEquals(ColorThemePreset.KANAGAWA_WAVE, prefs.config.first().theme)
        assertEquals(WallpaperSelection(ChatWallpaperPreset.RELAY, 73), prefs.config.first().wallpaper)
    }

    @Test fun wallpaperIntensity_isClampedAtomically() = runTest {
        prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.SIGNALS, 500))
        assertEquals(WallpaperSelection(ChatWallpaperPreset.SIGNALS, 100), prefs.config.first().wallpaper)
        prefs.setWallpaper(WallpaperSelection(ChatWallpaperPreset.PIXELS, -9))
        assertEquals(WallpaperSelection(ChatWallpaperPreset.PIXELS, 0), prefs.config.first().wallpaper)
    }

    @Test fun fontScales_areIndependentRoundedAndClamped() = runTest {
        prefs.setUiFontScale(83)
        prefs.setConversationFontScale(999)
        assertEquals(85, prefs.config.first().uiFontScalePercent)
        assertEquals(140, prefs.config.first().conversationFontScalePercent)

        prefs.setUiFontScale(-20)
        prefs.setConversationFontScale(117)
        assertEquals(80, prefs.config.first().uiFontScalePercent)
        assertEquals(115, prefs.config.first().conversationFontScalePercent)

        prefs.setUiFontScale(DEFAULT_FONT_SCALE_PERCENT)
        prefs.setConversationFontScale(DEFAULT_FONT_SCALE_PERCENT)
        assertEquals(DEFAULT_FONT_SCALE_PERCENT, prefs.config.first().uiFontScalePercent)
        assertEquals(DEFAULT_FONT_SCALE_PERCENT, prefs.config.first().conversationFontScalePercent)
    }
}
