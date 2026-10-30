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
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperAssetTest {
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets

    @Test fun everyPreset_isA2048TransparentPngWithTheApprovedAlpha() {
        ChatWallpaperPreset.entries.filter { it != ChatWallpaperPreset.NONE && !it.isScenic }.forEach { preset ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            assets.open("chat-wallpapers/${assetName(preset)}").use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            assertEquals("$preset codec", "image/png", bounds.outMimeType)
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
                assertEquals("$preset approved alpha", approvedAlphaSha256(preset), alpha.sha256)
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
        ChatWallpaperPreset.entries.filter { it != ChatWallpaperPreset.NONE && !it.isScenic }.forEach { preset ->
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

    @Test fun sourceSamplingKeepsTheNearestUsefulResolution() {
        assertEquals(4, wallpaperDecodeSampleSize(2048, 2048, 512))
        assertEquals(2, wallpaperDecodeSampleSize(2048, 2048, 1024))
        assertEquals(1, wallpaperDecodeSampleSize(2048, 2048, 1536))
    }

    @Test fun everyPresetRepeatsWithoutRasterSeamsAtFractionalDeviceDensity() {
        ChatWallpaperPreset.entries.filter { it != ChatWallpaperPreset.NONE && !it.isScenic }.forEach { preset ->
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
                sha256 =
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() })
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) },
            )
        } finally {
            if (readable !== bitmap) readable.recycle()
        }
    }

    private data class BitmapAlpha(
        val hasInk: Boolean,
        val hasTransparentPixels: Boolean,
        val sha256: String,
    )

    // SHA-256 of every row-major 8-bit alpha byte from the approved 2048px PNGs before re-encoding.
    private fun approvedAlphaSha256(preset: ChatWallpaperPreset): String =
        when (preset) {
            ChatWallpaperPreset.MOTD -> "5de511565c96edaf8213d7da50f0dc4cfea1e3c87180a7719541f9fdfa98a6ad"

            ChatWallpaperPreset.DEEP_SPACE -> "482222a5a77d4617215d30077af3b169463dd00f50b58a12c31f52cb3d1ac2e4"

            ChatWallpaperPreset.RETRO_GAMING -> "69135cf871afda80d221270454a48da4e56d2dd1ae005d29a4ef4db17f56b06f"

            ChatWallpaperPreset.RADIO_CLUB -> "7bb066ee8e00e707b8ccb64a2ef54d54a5488e9713bb52711d42459221c40bce"

            ChatWallpaperPreset.INTERNET_ODDITIES -> "f6dfd2eef38f356d26bdae5d6f9dc1b35d86c34f420f6e2591e6942866171e2d"

            ChatWallpaperPreset.RETRO_CHAT -> "2c1da3ee36e15c9f328a1ab0ad375f79af0e2cc7da35e7cb01ebcb1986505bd3"

            ChatWallpaperPreset.MEMES -> "621b181952fa4c75cdcdf18458ea9246cbad829cf8f3d074f461b657027709be"

            ChatWallpaperPreset.LINUX -> "d8ce022c61c9078279a65297d01d437fad92e800cff6c1f822386be89963b95e"

            ChatWallpaperPreset.SYSTEMCRAFTERS -> "fab88902fa7323b3df7b7180d30284f8c10335799cfad265ad6ea28bce66bc50"

            ChatWallpaperPreset.JAPANESE_COUNTRYSIDE,
            ChatWallpaperPreset.RAINY_CAFE,
            ChatWallpaperPreset.MOTD_ARCADE,
            ChatWallpaperPreset.MIDNIGHT_TERMINAL,
            ChatWallpaperPreset.CYBERPUNK_CANAL,
            -> error("scenic assets are full-color, not alpha tiles")

            ChatWallpaperPreset.NONE -> error("NONE has no approved wallpaper alpha")
        }

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
