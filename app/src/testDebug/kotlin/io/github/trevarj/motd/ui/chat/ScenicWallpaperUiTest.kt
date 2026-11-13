package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.components.LocalMessageWallpaperBackdrop
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.captureSurfaceBackdrop
import io.github.trevarj.motd.ui.components.recordMessageWallpaper
import io.github.trevarj.motd.ui.components.rememberMessageWallpaperBackdrop
import io.github.trevarj.motd.ui.components.rememberSurfaceBackdropSource
import io.github.trevarj.motd.ui.components.surfaceBackdrop
import io.github.trevarj.motd.ui.theme.LocalLottieMotionEnabled
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    @Config(sdk = [35])
    fun authoredScenicFramesUpdateExistingNativeBlurAndRemainIdleBetweenEvents() {
        val expected = artwork("dark")
        val sourceDraws = AtomicInteger()
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        val selection = WallpaperSelection(ChatWallpaperPreset.MIDNIGHT_TERMINAL, 100, scenicFollowSystem = false, scenicAnimationEnabled = true)
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f), LocalLifecycleOwner provides owner) {
                    MotdTheme(
                        themePreset = ColorThemePreset.DARK,
                        dynamicColor = false,
                        syncSystemBars = false,
                        layoutDensity = LayoutDensity.COMPACT,
                        avatarStyle = AvatarStyle.NONE,
                        messageBackgroundOpacityPercent = 0,
                        messageBackgroundBlur = 10,
                    ) {
                        CompositionLocalProvider(LocalLottieMotionEnabled provides true) {
                            val backdrop = rememberMessageWallpaperBackdrop(10)
                            Box(Modifier.size(256.dp, 448.dp).background(Color.Magenta).testTag("scenic_blur")) {
                                ChatWallpaperBackground(
                                    selection,
                                    Modifier
                                        .matchParentSize()
                                        .recordMessageWallpaper(backdrop, Color.Magenta)
                                        .drawWithContent {
                                            sourceDraws.incrementAndGet()
                                            drawContent()
                                        },
                                )
                                CompositionLocalProvider(LocalMessageWallpaperBackdrop provides backdrop) {
                                    Column(Modifier.padding(top = 250.dp)) {
                                        MessageBubble(
                                            sender = "alice",
                                            text = "Reading",
                                            timeMs = 0,
                                            isSelf = false,
                                            kind = MessageKind.PRIVMSG,
                                            showSender = true,
                                            modifier = Modifier.testTag("scenic_blur_message"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeByFrame()
                val image = compose.onNodeWithTag("scenic_blur").captureToImage().asAndroidBitmap()
                image.getPixel(20, 20) == expected.getPixel(20, 20) && image.getPixel(200, 400) == expected.getPixel(200, 400)
            }
            val row = compose.onNodeWithTag("scenic_blur_message")
            val still = row.captureToImage().asAndroidBitmap()
            val beforeQuiet = sourceDraws.get()
            compose.mainClock.advanceTimeBy(5_000)
            compose.waitForIdle()
            assertEquals("authored initial still interval must not run a source redraw loop", beforeQuiet, sourceDraws.get())
            // The real terminal event has an authored random 20–40s initial delay and a 720ms
            // visible frame. Drive its actual composition clock; do not change production timing.
            compose.mainClock.advanceTimeBy(13_000)
            var eventPixels: Bitmap? = null
            for (step in 0 until 125) {
                compose.mainClock.advanceTimeBy(200)
                compose.waitForIdle()
                val image = row.captureToImage().asAndroidBitmap()
                if (!still.sameAs(image)) {
                    eventPixels = image
                    break
                }
            }
            assertTrue("an actual authored scenic frame must reach an existing blurred message backing", eventPixels != null)
            val during = requireNotNull(eventPixels)
            var changed = 0
            for (x in 0 until still.width) {
                for (y in 0 until still.height) {
                    if (still.getPixel(x, y) != during.getPixel(x, y)) changed++
                }
            }
            assertTrue("native blur pixels, not merely source state, follow the event", changed > 0)
            compose.mainClock.advanceTimeBy(2_000)
            compose.waitForIdle()
            assertTrue("the authored event clears back to its exact static blurred frame", still.sameAs(row.captureToImage().asAndroidBitmap()))
            val settledDraws = sourceDraws.get()
            compose.mainClock.advanceTimeBy(5_000)
            compose.waitForIdle()
            assertEquals("the 45–90s quiet interval stays idle after the event", settledDraws, sourceDraws.get())
        } finally {
            compose.mainClock.autoAdvance = true
            expected.recycle()
        }
    }

    @Test
    @Config(sdk = [35])
    fun pageWallpaperEdgesFadeToCanvasAndReachSharedCaptures() {
        var theme by mutableStateOf(ColorThemePreset.LIGHT)
        var height by mutableStateOf(448)
        var faded by mutableStateOf(false)
        var replay by mutableStateOf("page")
        var canvas = Color.Unspecified
        val selection = WallpaperSelection(ChatWallpaperPreset.MIDNIGHT_TERMINAL, 100, scenicFollowSystem = false, scenicAnimationEnabled = false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MotdTheme(themePreset = theme, dynamicColor = false, syncSystemBars = false) {
                    val background = MaterialTheme.colorScheme.background
                    SideEffect { canvas = background }
                    val surfaceSource = rememberSurfaceBackdropSource()
                    val messageSource = requireNotNull(rememberMessageWallpaperBackdrop(1))
                    Box(Modifier.size(128.dp, height.dp).background(background).testTag("edge_fade")) {
                        ChatWallpaperBackground(
                            selection,
                            Modifier
                                .matchParentSize()
                                .captureSurfaceBackdrop(surfaceSource)
                                .recordMessageWallpaper(messageSource, MaterialTheme.colorScheme.surface)
                                .then(if (faded) Modifier.fadeChatEdges(background) else Modifier),
                            animate = false,
                        )
                        Box(
                            Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = if (replay == "surface") 1f else 0f }
                                .surfaceBackdrop(surfaceSource, background, opacityPercent = 1, blurPercent = 0),
                        )
                        Box(
                            Modifier.matchParentSize().drawBehind {
                                if (replay == "message") {
                                    messageSource.revision
                                    drawLayer(messageSource.raw)
                                }
                            },
                        )
                    }
                }
            }
        }

        fun capture(mode: String = "page"): Bitmap {
            compose.runOnIdle { replay = mode }
            return compose.onNodeWithTag("edge_fade").captureToImage().asAndroidBitmap()
        }

        fun assertNear(
            label: String,
            expected: Int,
            actual: Int,
            tolerance: Int = 3,
        ) {
            for (shift in listOf(16, 8, 0)) {
                val difference = ((expected shr shift) and 255) - ((actual shr shift) and 255)
                assertTrue("$label channel $shift: expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}", kotlin.math.abs(difference) <= tolerance)
            }
        }
        val proofs = File("build/reports/wallpaper-edge-fade").apply { mkdirs() }
        for ((preset, mode) in listOf(ColorThemePreset.LIGHT to "light", ColorThemePreset.DARK to "dark")) {
            val artwork = artwork(mode)
            try {
                compose.runOnIdle {
                    theme = preset
                    height = 448
                    faded = false
                    replay = "page"
                }
                compose.waitUntil(10_000) {
                    val image = capture()
                    try {
                        image.getPixel(64, 224) == artwork.getPixel(128, 224)
                    } finally {
                        image.recycle()
                    }
                }
                for (viewport in listOf(448, 256, 128)) {
                    compose.runOnIdle {
                        height = viewport
                        faded = false
                        replay = "page"
                    }
                    val original = capture()
                    compose.runOnIdle { faded = true }
                    val page = capture()
                    val message = capture("message")
                    val surface = capture("surface")
                    try {
                        for ((name, bitmap) in listOf("page" to page, "message" to message, "surface" to surface)) {
                            File(proofs, "$mode-$viewport-$name.png").outputStream().use {
                                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        }
                        assertEquals(128, page.width)
                        assertEquals(viewport, page.height)
                        assertTrue("the scenic source must contrast with the canvas", original.getPixel(64, 0) != canvas.toArgb())
                        val topExtent = minOf(192f, viewport / 2f)
                        val bottomExtent = minOf(96f, viewport / 2f)
                        for (x in listOf(16, 64, 112)) {
                            for (y in 0 until viewport) {
                                val opacity =
                                    maxOf(
                                        1f - (y + 0.5f) / topExtent,
                                        1f - (viewport - y - 0.5f) / bottomExtent,
                                        0f,
                                    )
                                val source = original.getPixel(x, y)
                                var expected = 0xff000000.toInt()
                                for (shift in listOf(16, 8, 0)) {
                                    val ink = (source shr shift) and 255
                                    val base = (canvas.toArgb() shr shift) and 255
                                    expected = expected or ((ink * (1f - opacity) + base * opacity).toInt() shl shift)
                                }
                                assertNear("$mode/$viewport fade at $x,$y", expected, page.getPixel(x, y))
                                if (y + 0.5f >= topExtent && y + 0.5f <= viewport - bottomExtent) {
                                    assertEquals("interior art is unchanged at $x,$y", source, page.getPixel(x, y))
                                }
                                assertNear("message raw replay at $x,$y", page.getPixel(x, y), message.getPixel(x, y), 1)
                                assertNear("surface replay at $x,$y", page.getPixel(x, y), surface.getPixel(x, y))
                            }
                            assertNear("top reaches theme canvas", canvas.toArgb(), page.getPixel(x, 0))
                            assertNear("bottom reaches theme canvas", canvas.toArgb(), page.getPixel(x, viewport - 1))
                        }
                    } finally {
                        original.recycle()
                        page.recycle()
                        message.recycle()
                        surface.recycle()
                    }
                }
            } finally {
                artwork.recycle()
            }
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
