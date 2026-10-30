package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScenicWallpaperAssetTest {
    private val assets = ApplicationProvider.getApplicationContext<Context>().assets

    // Decoded RGBA of the approved studio masters, independent of shipped PNG encoding.
    private val approvedPixels =
        mapOf(
            "japanese-countryside-summer-v3-light.png" to "b91b74ffb62157cfcb9217feb70acff2fe3931661823588c00a9b59729d62900",
            "japanese-countryside-summer-v3-dark.png" to "b291040ae7a76becd59b83f7f4d6b05f88067bf78983b1d78f355bbd83a81e9b",
            "japanese-countryside-spring-v3-light.png" to "cfff87852eac77cec8787570173e71d30c6b7654c9c8a2a003e5e8fb24eadcbc",
            "japanese-countryside-spring-v3-dark.png" to "607caeae88335024bc9e986871b03eb81192994c78858df75ee8c00755b584c1",
            "japanese-countryside-autumn-v3-light.png" to "aaa0f70e865fad6b79f4279a7bf93cdbe2e0167829491e2a7b7b7d6d05c517c7",
            "japanese-countryside-autumn-v3-dark.png" to "aa32e6d916590e8191acc6747b2c594e9d6b060f62c0980f97a5868512ecabb2",
            "japanese-countryside-winter-v3-light.png" to "6a1fe9669de184dc61133eee951bf07e080dadd64f696694ca32bbc6594a93aa",
            "japanese-countryside-winter-v3-dark.png" to "6a1e01afa0f6c571c5f714c0b76bc1fc825d4bea65a147dd18441912dce7e25f",
            "rainy-cafe-light.png" to "0c20bcfd71c27a93f78e61677a31f56fb2d3bed20d666babc220b149cade9a30",
            "rainy-cafe-dark.png" to "9b2312bbee1717f93f89b1ed88e24adae354de45220a4203f44566bbe84957cd",
            "neon-arcade-v3-light.png" to "91c2016925225cf5d3bae18dfcab04fa97775389e8377b3ad8315ecc624740e5",
            "neon-arcade-v3-dark.png" to "37635fffaf22d15ede7151c19dddf26f03b3c1fe4092f6c163e53c31d15d2414",
            "retro-computer-room-v3-light.png" to "20f0d326d1d836bdb73f038afafb8f7ded74b53c07180bb0c03903d29349a3b2",
            "retro-computer-room-v3-dark.png" to "cc032316455cc93f1b3f35dca33e966373c930f8ba899dc632691483b1d75c1f",
            "cyberpunk-canal-light.png" to "e4977c78b100b6091f581530ec2d91ab334565baa90f5ce4a908be59adb3c9a0",
            "cyberpunk-canal-dark.png" to "eab2b903a6cf64d6926f024c5d397b111d372c273522201f953160d7f9780732",
        )

    private val stems =
        listOf(
            "japanese-countryside-spring-v3",
            "japanese-countryside-summer-v3",
            "japanese-countryside-autumn-v3",
            "japanese-countryside-winter-v3",
            "rainy-cafe",
            "neon-arcade-v3",
            "retro-computer-room-v3",
            "cyberpunk-canal",
        )

    @Test
    fun collectionContainsOnlyApprovedPairsAndEventDocuments() {
        val expected = stems.flatMap { stem -> listOf("$stem-light.png", "$stem-dark.png", "$stem-events.json") }
        assertEquals(expected.sorted(), assets.list("chat-wallpapers/scenic")!!.sorted())
        for (stem in stems) {
            for (mode in listOf("light", "dark")) {
                val bitmap =
                    assets.open("chat-wallpapers/scenic/$stem-$mode.png").use {
                        BitmapFactory.decodeStream(it) ?: error("Cannot decode $stem/$mode")
                    }
                try {
                    assertEquals("$stem/$mode width", 256, bitmap.width)
                    assertEquals("$stem/$mode height", 448, bitmap.height)
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    assertTrue("$stem/$mode must be an opaque resting scene", pixels.all { it ushr 24 == 255 })
                    assertEquals("$stem/$mode approved colors", approvedPixels.getValue("$stem-$mode.png"), rgbaHash(pixels))
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    @Test
    fun packagedEventsHaveRareCadenceAndBoundedOpaqueFramesEndingAtRest() {
        for (stem in stems) {
            val document = document(stem)
            assertEquals(1, document.getInt("schemaVersion"))
            assertEquals(256, document.getInt("width"))
            assertEquals(448, document.getInt("height"))
            assertEquals(20_000, document.getJSONArray("initialDelayMs").getInt(0))
            assertEquals(40_000, document.getJSONArray("initialDelayMs").getInt(1))
            assertEquals(45_000, document.getJSONArray("quietIntervalMs").getInt(0))
            assertEquals(90_000, document.getJSONArray("quietIntervalMs").getInt(1))
            val events = document.getJSONArray("events")
            assertTrue("$stem needs an authored event", events.length() > 0)
            for (eventIndex in 0 until events.length()) {
                val frames = events.getJSONObject(eventIndex).getJSONArray("frames")
                assertTrue("$stem event needs animation and restoration", frames.length() >= 2)
                var duration = 0L
                for (frameIndex in 0 until frames.length()) {
                    val frame = frames.getJSONObject(frameIndex)
                    val frameDuration = frame.getLong("durationMs")
                    assertTrue("$stem frame duration", frameDuration > 0)
                    duration += frameDuration
                    for (mode in listOf("light", "dark")) {
                        val pixels = frame.getJSONArray(mode)
                        val coordinates = mutableSetOf<Pair<Int, Int>>()
                        for (pixelIndex in 0 until pixels.length()) {
                            val pixel = pixels.getJSONArray(pixelIndex)
                            val x = pixel.getInt(0)
                            val y = pixel.getInt(1)
                            assertTrue("$stem $mode pixel lies within scene", x in 0..255 && y in 0..447)
                            assertTrue("$stem $mode duplicate override", coordinates.add(x to y))
                            assertTrue("$stem $mode opaque replacement", pixel.getString(2).matches(Regex("#[0-9a-fA-F]{6}")))
                        }
                        // An empty final frame reveals the unchanged canonical resting bitmap.
                        if (frameIndex == frames.length() - 1) assertEquals("$stem must restore $mode", 0, pixels.length())
                    }
                }
                assertTrue("$stem event must remain brief", duration <= 2_000)
            }
        }
    }

    @Test
    fun arcadeShipsTheFullSignOffEvent() {
        val events = document("neon-arcade-v3").getJSONArray("events")
        assertEquals(1, events.length())
        val event = events.getJSONObject(0)
        assertEquals("motd-sign-full-off", event.getString("id"))
        val frames = event.getJSONArray("frames")
        assertEquals(2, frames.length())
        assertEquals(180, frames.getJSONObject(0).getInt("durationMs"))
        for (mode in listOf("light", "dark")) {
            assertTrue(frames.getJSONObject(0).getJSONArray(mode).length() > 0)
            assertEquals(0, frames.getJSONObject(1).getJSONArray(mode).length())
        }
    }

    @Test
    fun androidDrawingReplacesTheFullSignBeforeApplyingIntensityAndRestoresExactly() {
        val offFrame =
            document("neon-arcade-v3")
                .getJSONArray("events")
                .getJSONObject(0)
                .getJSONArray("frames")
                .getJSONObject(0)
        for (mode in listOf("light", "dark")) {
            val base =
                assets.open("chat-wallpapers/scenic/neon-arcade-v3-$mode.png").use {
                    BitmapFactory.decodeStream(it) ?: error("Cannot decode arcade/$mode")
                }
            try {
                val original = pixels(base)
                val overrides = offFrame.getJSONArray(mode)
                val offPixels =
                    List(overrides.length()) { index ->
                        val pixel = overrides.getJSONArray(index)
                        ScenicPixels(pixel.getInt(0), pixel.getInt(1), Color.parseColor(pixel.getString(2)))
                    }
                for (intensity in listOf(0, 50, 100)) {
                    for (frame in listOf(offPixels, emptyList())) {
                        for ((width, height) in listOf(256 to 448, 360 to 650)) {
                            // Reference is a completed canonical raster drawn once, as in the studio.
                            val completed = requireNotNull(base.copy(Bitmap.Config.ARGB_8888, true))
                            val renderedFrame = composeScenicFrame(base, frame)
                            val expected = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            val actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            try {
                                frame.forEach { completed.setPixel(it.x, it.y, it.color) }
                                expected.eraseColor(Color.rgb(20, 32, 45))
                                actual.eraseColor(Color.rgb(20, 32, 45))
                                val scale = maxOf(width / 256f, height / 448f)
                                val left = (width - 256 * scale) / 2f
                                val top = (height - 448 * scale) / 2f
                                val destination = RectF(left, top, left + 256 * scale, top + 448 * scale)
                                val paint =
                                    Paint().apply {
                                        alpha = intensity * 255 / 100
                                        isFilterBitmap = false
                                    }
                                Canvas(expected).drawBitmap(completed, null, destination, paint)
                                drawScenicFrame(Canvas(actual), renderedFrame, destination, intensity)
                                val expectedPixels = pixels(expected)
                                val actualPixels = pixels(actual)
                                val label = "$mode at $intensity%, ${width}x$height"
                                assertArrayEquals("$label: all pixels, including outside the sign", expectedPixels, actualPixels)
                            } finally {
                                completed.recycle()
                                if (renderedFrame !== base) renderedFrame.recycle()
                                expected.recycle()
                                actual.recycle()
                            }
                        }
                    }
                }
                assertArrayEquals("Shared resting bitmap must stay immutable", original, pixels(base))
            } finally {
                base.recycle()
            }
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray =
        IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }

    private fun document(stem: String): JSONObject = assets.open("chat-wallpapers/scenic/$stem-events.json").bufferedReader().use { JSONObject(it.readText()) }

    private fun rgbaHash(pixels: IntArray): String {
        val rgba = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { index, argb ->
            rgba[index * 4] = (argb ushr 16).toByte()
            rgba[index * 4 + 1] = (argb ushr 8).toByte()
            rgba[index * 4 + 2] = argb.toByte()
            rgba[index * 4 + 3] = (argb ushr 24).toByte()
        }
        return MessageDigest.getInstance("SHA-256").digest(rgba).joinToString("") { "%02x".format(it) }
    }
}
