package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntSize
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperAssetTest {
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets

    @Test fun everyPreset_isA2048TransparentPngWithInk() {
        ChatWallpaperPreset.entries.filterNot { it == ChatWallpaperPreset.NONE }.forEach { preset ->
            val bitmap =
                assets.open("chat-wallpapers/${assetName(preset)}").use { input ->
                    BitmapFactory.decodeStream(input) ?: error("$preset failed to decode")
                }
            try {
                assertEquals("$preset width", 2048, bitmap.width)
                assertEquals("$preset height", 2048, bitmap.height)
                val alpha = bitmapAlpha(bitmap)
                assertTrue("$preset must contain visible ink", alpha.hasInk)
                assertTrue("$preset must retain transparent space", alpha.hasTransparentPixels)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun wallpaperInkOpacity_isDirectAtEveryIntensity() {
        assertEquals(0f, wallpaperInkAlpha(0))
        assertEquals(0.5f, wallpaperInkAlpha(50))
        assertEquals(0.8f, wallpaperInkAlpha(80))
        assertEquals(1f, wallpaperInkAlpha(100))
    }

    @Test fun amoledWallpaperKeepsBlackBaseAndFullInk() {
        val black = Color.Black

        assertEquals(
            List(4) { black },
            gradientColors(
                preset = ChatWallpaperPreset.RETRO_CHAT,
                base = black,
                primary = Color.Red,
                secondary = Color.Green,
                tertiary = Color.Blue,
                foregrounds = listOf(Color.White),
            ),
        )
        assertEquals(1f, wallpaperInkColor(Color.White, 100).alpha)
    }

    @Test fun wallpaperCoverageOnlyExpandsAcrossViewportChanges() {
        val initial = expandedWallpaperCoverage(IntSize.Zero, IntSize(1080, 1800))
        val keyboardOpen = expandedWallpaperCoverage(initial, IntSize(1080, 1100))
        val wider = expandedWallpaperCoverage(keyboardOpen, IntSize(1200, 1600))

        assertEquals(IntSize(1080, 1800), keyboardOpen)
        assertEquals(IntSize(1200, 1800), wider)
    }

    @Test fun tileKeyIsIndependentOfViewportThemeAndIntensity() {
        val retroChat = wallpaperTileKey(ChatWallpaperPreset.RETRO_CHAT, density = 2.5f)

        assertEquals(WallpaperTileKey(ChatWallpaperPreset.RETRO_CHAT, 1280), retroChat)
        assertEquals(retroChat, wallpaperTileKey(ChatWallpaperPreset.RETRO_CHAT, density = 2.5f))
        assertNotEquals(retroChat, wallpaperTileKey(ChatWallpaperPreset.MEMES, density = 2.5f))
        assertNotEquals(retroChat, wallpaperTileKey(ChatWallpaperPreset.RETRO_CHAT, density = 3f))
        assertEquals(
            WallpaperTileKey(ChatWallpaperPreset.RETRO_CHAT, 320),
            wallpaperTileKey(
                preset = ChatWallpaperPreset.RETRO_CHAT,
                density = 2.5f,
                tilePeriodDp = WALLPAPER_THUMBNAIL_TILE_PERIOD_DP,
            ),
        )
    }

    @Test fun everyPresetRendersASampledAlphaTile() {
        ChatWallpaperPreset.entries.filterNot { it == ChatWallpaperPreset.NONE }.forEach { preset ->
            val key = wallpaperTileKey(preset, density = 0.5f)
            val bitmap = renderWallpaperTile(assets, key)
            try {
                assertEquals(key.tileSizePx, bitmap.width)
                assertEquals(key.tileSizePx, bitmap.height)
                assertEquals(Bitmap.Config.ALPHA_8, bitmap.config)
                val alpha = bitmapAlpha(bitmap)
                assertTrue("$preset alpha mask must retain ink", alpha.hasInk)
                assertTrue("$preset alpha mask must retain transparent space", alpha.hasTransparentPixels)
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun sourceSamplingKeepsTheNearestUsefulPngResolution() {
        assertEquals(4, wallpaperDecodeSampleSize(2048, 2048, 512))
        assertEquals(2, wallpaperDecodeSampleSize(2048, 2048, 1024))
        assertEquals(1, wallpaperDecodeSampleSize(2048, 2048, 1536))
    }

    @Test fun everyPresetRepeatsWithoutRasterSeamsAtFractionalDeviceDensity() {
        ChatWallpaperPreset.entries.filterNot { it == ChatWallpaperPreset.NONE }.forEach { preset ->
            // 2.625 is a common Android density and produces a full-size 1,344 px tile.
            val tile = renderWallpaperTile(assets, wallpaperTileKey(preset, density = 2.625f))
            try {
                val rendered = Bitmap.createBitmap(tile.width + 1, tile.height + 1, Bitmap.Config.ARGB_8888)
                try {
                    Canvas(rendered).drawRect(
                        0f,
                        0f,
                        rendered.width.toFloat(),
                        rendered.height.toFloat(),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                        },
                    )
                    assertRepeatEdgesMatch(rendered, tile.width, tile.height, preset)
                } finally {
                    rendered.recycle()
                }
            } finally {
                tile.recycle()
            }
        }
    }

    private fun bitmapAlpha(bitmap: Bitmap): BitmapAlpha {
        val readable =
            if (bitmap.config == Bitmap.Config.ALPHA_8) {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                bitmap
            }
        val pixels = IntArray(bitmap.width * bitmap.height)
        try {
            readable.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return BitmapAlpha(
                hasInk = pixels.any { it ushr 24 != 0 },
                hasTransparentPixels = pixels.any { it ushr 24 == 0 },
            )
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    private data class BitmapAlpha(
        val hasInk: Boolean,
        val hasTransparentPixels: Boolean,
    )

    private fun assertRepeatEdgesMatch(
        bitmap: Bitmap,
        seamX: Int,
        seamY: Int,
        preset: ChatWallpaperPreset,
    ) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (coordinate in 0 until seamY) {
            assertEquals(
                "$preset horizontal repeat at y=$coordinate",
                pixels[coordinate * bitmap.width],
                pixels[coordinate * bitmap.width + seamX],
            )
        }
        for (coordinate in 0 until seamX) {
            assertEquals(
                "$preset vertical repeat at x=$coordinate",
                pixels[coordinate],
                pixels[seamY * bitmap.width + coordinate],
            )
        }
        assertEquals("$preset repeat corner", pixels[0], pixels[seamY * bitmap.width + seamX])
    }
}
