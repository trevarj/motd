package io.github.trevarj.motd.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class WallpaperBackdropTest {
    @Test fun amoledWallpaperKeepsBlackBase() {
        assertEquals(
            List(4) { Color.Black },
            gradientColors(
                base = Color.Black,
                primary = Color.Red,
                secondary = Color.Green,
                tertiary = Color.Blue,
                foregrounds = listOf(Color.White),
            ),
        )
    }

    @Test fun wallpaperCoverageOnlyExpandsAcrossViewportChanges() {
        val initial = expandedWallpaperCoverage(IntSize.Zero, IntSize(1080, 1800))
        val keyboardOpen = expandedWallpaperCoverage(initial, IntSize(1080, 1100))
        val wider = expandedWallpaperCoverage(keyboardOpen, IntSize(1200, 1600))

        assertEquals(IntSize(1080, 1800), keyboardOpen)
        assertEquals(IntSize(1200, 1800), wider)
    }
}
