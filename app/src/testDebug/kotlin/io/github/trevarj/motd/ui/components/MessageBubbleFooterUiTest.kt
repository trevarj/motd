package io.github.trevarj.motd.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColor
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColorRole
import io.github.trevarj.motd.data.prefs.CustomWallpaperStore
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.UiCornerStyle
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.chat.ChatWallpaperBackground
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.TimestampConfig
import io.github.trevarj.motd.ui.theme.contrastRatio
import io.github.trevarj.motd.ui.theme.ensureContrast
import io.github.trevarj.motd.ui.theme.fixedThemeScheme
import io.github.trevarj.motd.ui.theme.semanticColors
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class MessageBubbleFooterUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    @Config(sdk = [35])
    fun nativeBlurCoversAllBackingsAndIntensitiesWithoutBlurringForegroundOrChangingOff() {
        data class Case(
            val density: LayoutDensity,
            val kind: MessageKind,
            val nick: Boolean,
            val opacity: Int = 0,
            val self: Boolean = false,
            val sender: Boolean = true,
            val intensities: List<Int> = listOf(75),
        )
        val current = mutableStateOf(Case(LayoutDensity.COMFORTABLE, MessageKind.PRIVMSG, false))
        val blur = mutableStateOf(0)
        val invertedWallpaper = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                val row = current.value
                BlurSample(
                    row.density,
                    row.kind,
                    row.nick,
                    row.opacity,
                    blur.value,
                    showSender = row.sender,
                    isSelf = row.self,
                    invertWallpaper = { invertedWallpaper.value },
                )
            }
        }
        val sample = compose.onNodeWithTag("blur_sample")
        val cases =
            LayoutDensity.entries.flatMap { density ->
                listOf(MessageKind.PRIVMSG, MessageKind.ACTION).flatMap { kind ->
                    listOf(false, true).map { nick -> Case(density, kind, nick) }
                } + Case(density, MessageKind.NOTICE, false)
            } +
                listOf(
                    Case(LayoutDensity.COMFORTABLE, MessageKind.PRIVMSG, false, sender = false),
                    Case(LayoutDensity.COMFORTABLE, MessageKind.PRIVMSG, false, self = true),
                    Case(LayoutDensity.COMFORTABLE, MessageKind.PRIVMSG, false, self = true, sender = false),
                    Case(LayoutDensity.COMFORTABLE, MessageKind.PRIVMSG, false, opacity = 37, intensities = listOf(25, 75, 100)),
                    Case(LayoutDensity.COMPACT, MessageKind.ACTION, true, opacity = 37),
                    Case(LayoutDensity.COMFORTABLE, MessageKind.PRIVMSG, false, opacity = 100),
                    Case(LayoutDensity.TWO_LINE, MessageKind.ACTION, false, opacity = 100),
                )
        for (row in cases) {
            val label = row.toString()
            compose.runOnIdle {
                current.value = row
                blur.value = 0
                invertedWallpaper.value = false
            }
            val original = sample.captureToImage().asAndroidBitmap()
            // A near-solid antialiased pixel can round to the ink byte on one background.
            // Both opposite checker colors must paint that byte before treating it as solid ink.
            compose.runOnIdle { invertedWallpaper.value = true }
            val opposite = sample.captureToImage().asAndroidBitmap()
            compose.runOnIdle { invertedWallpaper.value = false }
            val parent = sample.fetchSemanticsNode().boundsInRoot
            val backing = compose.onNodeWithTag("blur_backing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val left = (backing.left - parent.left).toInt()
            val right = (backing.right - parent.left).toInt()
            val top = (backing.top - parent.top).toInt()
            val xs = (left + 24) until (right - 24)
            assertTrue("$label has a real painted sample band", !xs.isEmpty())

            fun edgeStep(image: Bitmap): Int {
                val values = xs.map { image.getPixel(it, top + 1) and 255 }
                // Low blur softens edges before the wide tiles lose their flat-color extrema.
                return values.zipWithNext().maxOf { (before, after) -> kotlin.math.abs(after - before) }
            }
            val body = compose.onNodeWithText("Reading wallpaper with crisp text", substring = true, useUnmergedTree = true)
            val text = body.textLayout()
            val ink = text.inkAt("Reading")
            val textBounds = body.fetchSemanticsNode().boundsInRoot
            val first = text.getBoundingBox(text.layoutInput.text.indexOf("Reading"))
            val glyphs =
                ((textBounds.left - parent.left + first.left).toInt() until (textBounds.left - parent.left + first.right).toInt()).flatMap { x ->
                    ((textBounds.top - parent.top + first.top).toInt() until (textBounds.top - parent.top + first.bottom).toInt()).mapNotNull { y ->
                        (x to y).takeIf { original.getPixel(x, y) == ink.toArgb() && opposite.getPixel(x, y) == ink.toArgb() }
                    }
                }
            assertTrue("$label contains background-invariant solid glyph pixels", glyphs.isNotEmpty())
            val edgeSteps = mutableListOf(edgeStep(original))
            for (intensity in row.intensities) {
                compose.runOnIdle { blur.value = intensity }
                val blurred = sample.captureToImage().asAndroidBitmap()
                edgeSteps += edgeStep(blurred)
                val changedGlyphs = glyphs.filter { (x, y) -> blurred.getPixel(x, y) != ink.toArgb() }
                val changedColors = changedGlyphs.take(3).map { (x, y) -> "$x,$y ${ink.toArgb().toUInt().toString(16)} -> ${blurred.getPixel(x, y).toUInt().toString(16)}" }
                assertTrue("$label blur=$intensity leaves solid glyphs sharp: $changedColors", changedGlyphs.isEmpty())
                assertEquals("$label never blurs the wallpaper outside a message", original.getPixel(4, 4), blurred.getPixel(4, 4))
                if (row.density == LayoutDensity.COMFORTABLE) {
                    assertEquals("$label retains the grouped outline", original.getPixel(left, top), blurred.getPixel(left, top))
                }
            }
            if (row.opacity < 100) {
                assertTrue("$label smooths wallpaper edges at zero/intermediate tint: $edgeSteps", edgeSteps[1] < edgeSteps[0] - 4)
                assertTrue("$label higher intensity cannot sharpen edges: $edgeSteps", edgeSteps.zipWithNext().all { (before, after) -> after <= before })
            }
            compose.runOnIdle { blur.value = 0 }
            assertTrue("$label blur Off restores exact baseline pixels", original.sameAs(sample.captureToImage().asAndroidBitmap()))
        }
    }

    @Test
    @Config(sdk = [35])
    fun blurTracksDrawOnlySourceFramesScrollAndRunwayWithoutMovingWallpaperSamples() {
        val frame = mutableStateOf(0f)
        val translation = mutableStateOf(Offset.Zero)
        val viewportHeight = mutableStateOf(260)
        val scrolling = ScrollState(0)
        val blur = mutableStateOf(75)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                BlurSample(
                    LayoutDensity.COMPACT,
                    MessageKind.PRIVMSG,
                    false,
                    0,
                    blur.value,
                    frame = { frame.value },
                    translation = { translation.value },
                    scrolling = scrolling,
                    viewportHeight = viewportHeight.value,
                )
            }
        }
        val sample = compose.onNodeWithTag("blur_sample")

        fun band(): List<Int> {
            val image = sample.captureToImage().asAndroidBitmap()
            val parent = sample.fetchSemanticsNode().boundsInRoot
            val row = compose.onNodeWithTag("blur_backing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            // Fixed screen-space points remain inside the backing before and after each motion.
            val y = 80
            assertTrue("sample remains inside the moving backing", y >= row.top - parent.top && y < row.bottom - parent.top)
            return (80 until 280).map { image.getPixel(it, y) }
        }
        val original = band()
        compose.runOnIdle { frame.value = 16f }
        val animated = band()
        assertTrue("draw-only wallpaper changes reach already-recorded consumers", original != animated)
        compose.runOnIdle { frame.value = 0f }
        assertEquals("source frame restoration", original, band())
        compose.runOnIdle { translation.value = Offset(0f, -2f) }
        assertEquals("runway graphicsLayer translation must not drag wallpaper", original, band())
        compose.runOnIdle { translation.value = Offset.Zero }
        compose.runOnIdle { runBlocking { scrolling.scrollTo(2) } }
        compose.runOnIdle { viewportHeight.value = 220 }
        assertEquals("IME viewport remeasurement must not drag wallpaper", original, band())
        compose.runOnIdle { viewportHeight.value = 260 }
        assertEquals("scroll placement must not drag wallpaper", original, band())
        compose.runOnIdle {
            runBlocking { scrolling.scrollTo(0) }
            blur.value = 0
        }
        val off = sample.captureToImage().asAndroidBitmap()
        compose.runOnIdle { blur.value = 75 }
        assertTrue("returning from Off reattaches live native layers", !off.sameAs(sample.captureToImage().asAndroidBitmap()))
    }

    @Test
    @Config(sdk = [30])
    fun preAndroid12RetainsUnblurredTintForAllLayoutsAndZeroOpacity() {
        val layout = mutableStateOf(LayoutDensity.COMFORTABLE)
        val blur = mutableStateOf(0)
        val opacity = mutableStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                BlurSample(layout.value, MessageKind.ACTION, true, opacity.value, blur.value)
            }
        }
        for (density in LayoutDensity.entries) {
            for (percent in listOf(0, 37, 100)) {
                compose.runOnIdle {
                    layout.value = density
                    opacity.value = percent
                    blur.value = 0
                }
                val baseline = compose.onNodeWithTag("blur_sample").captureToImage().asAndroidBitmap()
                compose.runOnIdle { blur.value = 100 }
                assertTrue("$density opacity=$percent keeps the existing tint-only fallback", baseline.sameAs(compose.onNodeWithTag("blur_sample").captureToImage().asAndroidBitmap()))
            }
        }
    }

    @Test
    @Config(sdk = [35])
    fun nativeBlurPreservesOrdinaryMentionsAndBlursActionMentionsWithoutChangingRailsOrReplyAudio() {
        val layout = mutableStateOf(LayoutDensity.COMFORTABLE)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val blur = mutableStateOf(0)
        val mention = mutableStateOf(true)
        val opacity = mutableStateOf(0)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                BlurSample(layout.value, kind.value, true, opacity.value, blur.value, mention = mention.value, reply = ReplyPreviewData("quoted", "Quoted reply"), audio = true, viewportHeight = 360)
            }
        }
        for (density in LayoutDensity.entries) {
            val roles = listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)
            for ((messageKind, percent) in roles.flatMap { kind -> listOf(0, 37).map { kind to it } }) {
                compose.runOnIdle {
                    layout.value = density
                    kind.value = messageKind
                    mention.value = true
                    blur.value = 0
                    opacity.value = percent
                }
                val sample = compose.onNodeWithTag("blur_sample")
                val highlighted = sample.captureToImage().asAndroidBitmap()
                val reply = compose.onNodeWithText("Quoted reply", useUnmergedTree = true)
                val originalReply = reply.captureToImage().asAndroidBitmap()
                compose.runOnIdle { blur.value = 100 }
                val highlightedBlur = sample.captureToImage().asAndroidBitmap()
                val primary = requireNotNull(fixedThemeScheme(ColorThemePreset.AYU_LIGHT)).primary.toArgb()
                val mentionRailPixels = mutableListOf<Pair<Int, Int>>()
                for (x in 0 until 3) {
                    for (y in 0 until highlighted.height) {
                        if (highlighted.getPixel(x, y) == primary) mentionRailPixels += x to y
                    }
                }
                assertTrue("$density $messageKind mention has a primary leading rail", mentionRailPixels.isNotEmpty())
                assertTrue("$density $messageKind mention rail stays sharp at opacity=$percent", mentionRailPixels.all { (x, y) -> highlightedBlur.getPixel(x, y) == primary })
                if (messageKind == MessageKind.ACTION) {
                    assertTrue("$density ACTION mention opacity=$percent samples wallpaper blur", !highlighted.sameAs(highlightedBlur))
                } else {
                    assertTrue("$density $messageKind incoming opaque mention remains exact", highlighted.sameAs(highlightedBlur))
                }
                assertTrue("$density $messageKind reply stays sharp and opaque", originalReply.sameAs(reply.captureToImage().asAndroidBitmap()))
                compose.runOnIdle {
                    mention.value = false
                    blur.value = 0
                }
                val ordinary = sample.captureToImage().asAndroidBitmap()
                assertTrue("$density $messageKind nonmention has no primary attention rail", mentionRailPixels.none { (x, y) -> ordinary.getPixel(x, y) == primary })
                val ordinaryReply = reply.captureToImage().asAndroidBitmap()
                val audio = compose.onNodeWithTag("audio_player", useUnmergedTree = true).captureToImage().asAndroidBitmap()
                val railColor =
                    if (messageKind == MessageKind.ACTION && density != LayoutDensity.COMFORTABLE) {
                        requireNotNull(fixedThemeScheme(ColorThemePreset.AYU_LIGHT)).tertiary.toArgb()
                    } else {
                        null
                    }
                val railPixels = mutableListOf<Pair<Int, Int>>()
                if (railColor != null) {
                    for (x in 0 until ordinary.width) {
                        for (y in 0 until ordinary.height) {
                            if (ordinary.getPixel(x, y) == railColor) railPixels += x to y
                        }
                    }
                    assertTrue("$density ACTION has a real semantic rail", railPixels.isNotEmpty())
                }
                compose.runOnIdle { blur.value = 100 }
                val blurred = sample.captureToImage().asAndroidBitmap()
                assertTrue("$density $messageKind ordinary reply is independent of backing blur", ordinaryReply.sameAs(reply.captureToImage().asAndroidBitmap()))
                val blurredAudio = compose.onNodeWithTag("audio_player", useUnmergedTree = true).captureToImage().asAndroidBitmap()
                for (x in 16 until audio.width - 16) {
                    for (y in 16 until audio.height - 16) {
                        assertEquals("$density $messageKind audio content and controls stay sharp", audio.getPixel(x, y), blurredAudio.getPixel(x, y))
                    }
                }
                assertTrue("$density $messageKind accent stays sharp", railPixels.all { (x, y) -> blurred.getPixel(x, y) == railColor })
            }
        }
    }

    @Test
    @Config(sdk = [35])
    fun nativeBlurKeepsComfortableMorphWallpaperFixedInsideItsGrowingGroupedMask() {
        val progress = mutableStateOf(1f)
        val morphEnabled = mutableStateOf(false)
        val morphProgress = { progress.value }
        val blur = mutableStateOf(75)
        val sender = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                BlurSample(
                    LayoutDensity.COMFORTABLE,
                    MessageKind.PRIVMSG,
                    false,
                    0,
                    blur.value,
                    showSender = sender.value,
                    morphProgress = if (morphEnabled.value) morphProgress else null,
                )
            }
        }
        val sample = compose.onNodeWithTag("blur_sample")
        for (showSender in listOf(true, false)) {
            compose.runOnIdle {
                sender.value = showSender
                morphEnabled.value = false
                progress.value = 1f
                blur.value = 75
            }
            val full = sample.captureToImage().asAndroidBitmap()
            val parent = sample.fetchSemanticsNode().boundsInRoot
            val backing = compose.onNodeWithTag("blur_backing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val x = ((backing.left + backing.right) / 2 - parent.left).toInt()
            val y = (backing.top - parent.top + 3).toInt()
            compose.runOnIdle { morphEnabled.value = true }
            assertTrue("sender=$showSender completed morph matches the ordinary grouped surface", full.sameAs(sample.captureToImage().asAndroidBitmap()))
            compose.runOnIdle { progress.value = 0.75f }
            val growing = sample.captureToImage().asAndroidBitmap()
            assertEquals("morph scales the mask, not the sampled wallpaper", full.getPixel(x, y), growing.getPixel(x, y))
            compose.runOnIdle { progress.value = 0f }
            val noSurface = sample.captureToImage().asAndroidBitmap()
            compose.runOnIdle { blur.value = 0 }
            assertTrue("zero morph alpha removes blur as well as tint", noSurface.sameAs(sample.captureToImage().asAndroidBitmap()))
        }
    }

    @Test
    fun comfortableMorphOutlineAndCompletedRectBothHonorBackingOpacity() {
        val opacity = mutableStateOf(100)
        val progress = mutableStateOf(0.5f)
        compose.setContent {
            MotdTheme(dynamicColor = false, messageBackgroundOpacityPercent = opacity.value, chatShadowsEnabled = false) {
                Box(
                    Modifier
                        .width(380.dp)
                        .height(140.dp)
                        .background(Color.Magenta)
                        .testTag("morph_opacity_sample"),
                ) {
                    MessageBubble(
                        sender = "me",
                        text = "Reading",
                        timeMs = 0,
                        formattedTime = TIME,
                        isSelf = true,
                        kind = MessageKind.PRIVMSG,
                        showSender = true,
                        sendMorphProgress = { progress.value },
                    )
                }
            }
        }
        val sample = compose.onNodeWithTag("morph_opacity_sample")
        for (phase in listOf(0.5f, 1f)) {
            compose.runOnIdle {
                progress.value = phase
                opacity.value = 100
            }
            val baseline = sample.captureToImage().asAndroidBitmap()
            val colors = mutableMapOf<Int, Int>()
            for (y in 0 until baseline.height) {
                for (x in 0 until baseline.width) {
                    val color = baseline.getPixel(x, y)
                    if (color != Color.Magenta.toArgb()) colors[color] = (colors[color] ?: 0) + 1
                }
            }
            val backing = Color(colors.maxBy { it.value }.key)
            var fillPoint = 0 to 0
            findFill@ for (y in 0 until baseline.height) {
                for (x in 0 until baseline.width) {
                    if (baseline.getPixel(x, y) == backing.toArgb()) {
                        fillPoint = x to y
                        break@findFill
                    }
                }
            }
            for (percent in listOf(37, 0, 100)) {
                compose.runOnIdle { opacity.value = percent }
                val pixels = sample.captureToImage().asAndroidBitmap()
                val actual = pixels.getPixel(fillPoint.first, fillPoint.second)
                val expected = backing.copy(alpha = percent / 100f).compositeOver(Color.Magenta).toArgb()
                for (shift in listOf(0, 8, 16, 24)) {
                    assertTrue("morph=$phase opacity=$percent channel=$shift", kotlin.math.abs((actual ushr shift and 255) - (expected ushr shift and 255)) <= 2)
                }
                if (percent == 100) assertTrue("morph=$phase restores baseline", baseline.sameAs(pixels))
            }
        }
    }

    @Test
    @Config(sdk = [35])
    fun loadedImageAndVideoCornerPresetsCoverEveryDensityAndActionAndRestoreDefault() {
        RoutedInlineMediaFixture().use { fixture ->
            fixture.server.enqueue(solidMediaResponse())
            fixture.server.enqueue(solidMediaResponse())
            val density = mutableStateOf(LayoutDensity.COMFORTABLE)
            val kind = mutableStateOf(MessageKind.PRIVMSG)
            val corners = mutableStateOf(UiCornerStyle.DEFAULT)
            val video = mutableStateOf(false)
            val opacity = mutableStateOf(100)
            val blur = mutableStateOf(0)
            val imageUrl = "http://media.invalid/corner-mask.png"
            val videoUrl = "http://media.invalid/corner-mask.mp4"
            var pixelsPerDp = 1f
            compose.setContent {
                pixelsPerDp = LocalDensity.current.density
                MotdTheme(
                    dynamicColor = false,
                    layoutDensity = density.value,
                    avatarStyle = AvatarStyle.NONE,
                    mediaCornerStyle = corners.value,
                    chatShadowsEnabled = false,
                    messageBackgroundOpacityPercent = opacity.value,
                    messageBackgroundBlur = blur.value,
                ) {
                    CompositionLocalProvider(
                        LocalAutomaticRemoteMedia provides true,
                        LocalNetworkMediaHttp provides fixture.http,
                    ) {
                        Box(Modifier.width(380.dp).background(Color.Cyan)) {
                            val backdrop = rememberMessageWallpaperBackdrop(blur.value)
                            Canvas(Modifier.matchParentSize().recordMessageWallpaper(backdrop, Color.Cyan)) {
                                for (x in 0..12) {
                                    drawRect(if (x % 2 == 0) Color.Black else Color.White, Offset(x * 32f, 0f), Size(32f, size.height))
                                }
                            }
                            CompositionLocalProvider(LocalMessageWallpaperBackdrop provides backdrop) {
                                MessageBubble(
                                    sender = "alice",
                                    text = "Media",
                                    timeMs = 0,
                                    formattedTime = TIME,
                                    isSelf = false,
                                    kind = kind.value,
                                    showSender = true,
                                    networkId = fixture.networkId,
                                    mediaUrls = listOf(if (video.value) videoUrl else imageUrl),
                                )
                            }
                        }
                    }
                }
            }
            for (isVideo in listOf(false, true)) {
                for (layout in LayoutDensity.entries) {
                    for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
                        compose.runOnIdle {
                            video.value = isVideo
                            density.value = layout
                            kind.value = messageKind
                            corners.value = UiCornerStyle.DEFAULT
                            opacity.value = 100
                            blur.value = 0
                        }
                        compose.waitUntil(10_000) {
                            compose.onAllNodesWithTag("inline_media_loaded", useUnmergedTree = true).fetchSemanticsNodes().size == 1
                        }
                        val media = compose.onNodeWithTag(if (isVideo) "inline_video_preview" else "inline_media_loaded", useUnmergedTree = true)
                        val baseline = media.captureToImage().asAndroidBitmap()
                        val bounds = media.fetchSemanticsNode().boundsInRoot
                        for (preset in UiCornerStyle.entries + UiCornerStyle.DEFAULT) {
                            compose.runOnIdle { corners.value = preset }
                            val image = media.captureToImage().asAndroidBitmap()
                            val radius =
                                when (preset) {
                                    UiCornerStyle.DEFAULT -> {
                                        when {
                                            messageKind == MessageKind.ACTION -> 10
                                            layout == LayoutDensity.COMFORTABLE -> 12
                                            layout == LayoutDensity.COMPACT -> 8
                                            else -> 10
                                        }
                                    }

                                    UiCornerStyle.SQUARE -> {
                                        0
                                    }

                                    UiCornerStyle.SOFT -> {
                                        8
                                    }

                                    UiCornerStyle.ROUNDED -> {
                                        24
                                    }
                                }
                            val label = "$layout $messageKind video=$isVideo $preset"
                            assertMediaCornerPixels(image, radius * pixelsPerDp, Color.Magenta, pixelsPerDp, label)
                            assertEquals("$label unchanged bounds", bounds, media.fetchSemanticsNode().boundsInRoot)
                            if (preset == UiCornerStyle.DEFAULT) assertTrue("$label restored loaded pixels", baseline.sameAs(image))
                        }
                        for (percent in listOf(37, 0, 100)) {
                            for (intensity in listOf(0, 75)) {
                                compose.runOnIdle {
                                    opacity.value = percent
                                    blur.value = intensity
                                }
                                val image = media.captureToImage().asAndroidBitmap()
                                for (x in image.width / 4 until image.width * 3 / 4) {
                                    for (y in image.height / 4 until image.height * 3 / 4) {
                                        assertEquals("$layout $messageKind video=$isVideo opacity=$percent blur=$intensity never fades or blurs loaded media", baseline.getPixel(x, y), image.getPixel(x, y))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            assertTrue("media must really load through the controlled network", fixture.server.requestCount >= 2)
            assertTrue(fixture.selectedNetworks.isNotEmpty())
            assertTrue(fixture.selectedNetworks.all { it == fixture.networkId })
        }
    }

    @Test
    fun customComfortableFillKeepsBodyAndFailedFooterReadable() {
        val fill = Color(0xFF777777)
        compose.setContent {
            Sample(
                failed = true,
                sentBubbleColor = ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, fill.toArgb()),
            )
        }

        val body = compose.onNodeWithText(BODY, useUnmergedTree = true).textLayout().inkAt(BODY)
        val time =
            compose
                .onNodeWithText(TIME, useUnmergedTree = true)
                .textLayout()
                .layoutInput
                .style
                .color
        assertTrue(contrastRatio(body, fill) >= 4.5)
        assertTrue(contrastRatio(time, fill) >= 4.5)
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.chat_failed), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun storedOwnNickAppearsOnceBelowBodyBesideStatusAndTime() {
        compose.setContent { Sample() }

        compose.onAllNodesWithText(NICK, useUnmergedTree = true).assertCountEquals(1)
        val nick = compose.onNodeWithTag("self_sender_label", useUnmergedTree = true)
        nick.assertTextEquals(NICK).assertIsDisplayed()
        val nickBounds = nick.fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithText(BODY, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val time = compose.onNodeWithText(TIME, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val status = sent().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("message_metadata", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertTrue("own nick must not remain a header", nickBounds.top >= body.bottom)
        assertTrue("nick and timestamp must share a footer band", nickBounds.top < time.bottom && time.top < nickBounds.bottom)
        assertTrue("status must share the footer band", status.top < nickBounds.bottom && nickBounds.top < status.bottom)
        assertTrue("nick must precede status and timestamp", nickBounds.right <= status.left && status.right <= time.left)
        assertEquals("timestamp must end the right-aligned footer", footer.right, time.right, 1f)
    }

    @Test
    fun hidingTimestampsKeepsOwnNickButContinuationOmitsIt() {
        val showTime = mutableStateOf(true)
        val showSender = mutableStateOf(true)
        compose.setContent { Sample(showTime = showTime.value, showSender = showSender.value) }

        compose.runOnIdle { showTime.value = false }
        compose.onNodeWithText(TIME, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("self_sender_label", useUnmergedTree = true).assertTextEquals(NICK).assertIsDisplayed()
        sent().assertIsDisplayed()
        compose.runOnIdle { showSender.value = false }
        compose.onAllNodesWithText(NICK, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("self_sender_label", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(BODY, useUnmergedTree = true).assertIsDisplayed()
        sent().assertIsDisplayed()
        compose.runOnIdle { showTime.value = true }
        compose.onNodeWithText(TIME, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithText(NICK, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun longOwnNickEllipsizesWithoutClippingStatusOrTimeInNarrowPane() {
        compose.setContent { Sample(sender = LONG_NICK, width = 240.dp) }

        val nick = compose.onNodeWithTag("self_sender_label", useUnmergedTree = true).assertIsDisplayed()
        val time = compose.onNodeWithText(TIME, useUnmergedTree = true).assertIsDisplayed()
        val status = sent().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("message_metadata", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pane = compose.onNodeWithTag("sample", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val nickBounds = nick.fetchSemanticsNode().boundsInRoot
        val timeBounds = time.fetchSemanticsNode().boundsInRoot
        val nickLayout = nick.textLayout()
        val timeLayout = time.textLayout()

        compose.onAllNodesWithText(LONG_NICK, useUnmergedTree = true).assertCountEquals(1)
        assertEquals(1, nickLayout.lineCount)
        assertTrue("long nick must visibly ellipsize", nickLayout.isLineEllipsized(0))
        assertEquals(1, timeLayout.lineCount)
        assertFalse("timestamp must not ellipsize", timeLayout.isLineEllipsized(0))
        assertEquals("timestamp must retain every character", TIME.length, timeLayout.getLineEnd(0, visibleEnd = true))
        // Paragraph width retains the measure constraint; visible glyph bounds detect actual clipping.
        TIME.indices.forEach { offset ->
            val glyph = timeLayout.getBoundingBox(offset)
            assertTrue(
                "timestamp character $offset must be wholly visible: $glyph in $timeBounds",
                glyph.left >= 0 && glyph.top >= 0 && glyph.right <= timeBounds.width && glyph.bottom <= timeBounds.height,
            )
        }
        assertEquals("timestamp must not be clipped by an ancestor", timeLayout.size.width.toFloat(), timeBounds.width, 1f)
        assertTrue("metadata must stay within the narrow pane", footer.left >= pane.left && footer.right <= pane.right)
        assertTrue("nick must leave room for delivery status", nickBounds.right <= status.left)
        assertTrue("status must leave room for timestamp", status.right <= timeBounds.left)
        assertTrue("timestamp must remain inside footer", timeBounds.right <= footer.right)
        assertTrue("status must remain inside footer", status.top >= footer.top && status.bottom <= footer.bottom)
    }

    @Test
    fun incomingSenderRemainsAboveBody() {
        compose.setContent { Sample(isSelf = false) }

        compose.onAllNodesWithText(NICK, useUnmergedTree = true).assertCountEquals(1)
        val nick = compose.onNodeWithText(NICK, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithText(BODY, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("incoming sender must remain a header", nick.bottom <= body.top)
        compose.onNodeWithTag("self_sender_label", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun compactKeepsOwnNickInline() {
        compose.setContent { Sample(density = LayoutDensity.COMPACT) }

        compose.onNodeWithText("$NICK: $BODY", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("self_sender_label", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun twoLineKeepsOwnNickAboveBody() {
        compose.setContent { Sample(density = LayoutDensity.TWO_LINE) }

        val nick = compose.onNodeWithText(NICK, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithText(BODY, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("two-line sender must remain in its header", nick.bottom <= body.top)
        compose.onNodeWithTag("self_sender_label", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun twoLineLongSenderLeavesMetadataVisibleAtLargeFontScales() {
        val fontScale = mutableStateOf(1.5f)
        val self = mutableStateOf(false)
        val friend = mutableStateOf(false)
        val showTime = mutableStateOf(true)
        val showSender = mutableStateOf(true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                Sample(
                    sender = LONG_NICK,
                    width = 320.dp,
                    density = LayoutDensity.TWO_LINE,
                    avatarStyle = AvatarStyle.MONOGRAM,
                    isSelf = self.value,
                    senderIsFriend = friend.value,
                    showTime = showTime.value,
                    showSender = showSender.value,
                )
            }
        }

        for (scale in listOf(1.5f, 2f)) {
            for (own in listOf(false, true)) {
                for (isFriend in listOf(false, true)) {
                    compose.runOnIdle {
                        fontScale.value = scale
                        self.value = own
                        friend.value = isFriend
                        showTime.value = true
                        showSender.value = true
                    }
                    val nick = compose.onNodeWithText(LONG_NICK, useUnmergedTree = true).assertIsDisplayed()
                    val time = compose.onNodeWithText(TIME, useUnmergedTree = true).assertIsDisplayed()
                    val nickBounds = nick.fetchSemanticsNode().boundsInRoot
                    val timeBounds = time.fetchSemanticsNode().boundsInRoot
                    val pane = compose.onNodeWithTag("sample").fetchSemanticsNode().boundsInRoot
                    val body = compose.onNodeWithText(BODY, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val nickLayout = nick.textLayout()
                    val timeLayout = time.textLayout()
                    assertEquals(1, nickLayout.lineCount)
                    assertTrue("long sender must ellipsize at fontScale=$scale", nickLayout.isLineEllipsized(0))
                    assertTrue("sender must remain above the body", nickBounds.bottom <= body.top)
                    if (!isFriend) assertEquals("sender and body must align after the avatar gap", body.left, nickBounds.left, 1f)
                    assertEquals(1, timeLayout.lineCount)
                    assertFalse("timestamp must not ellipsize", timeLayout.isLineEllipsized(0))
                    assertEquals(TIME.length, timeLayout.getLineEnd(0, visibleEnd = true))
                    TIME.indices.forEach { offset ->
                        val glyph = timeLayout.getBoundingBox(offset)
                        assertTrue(
                            "timestamp character $offset must be wholly visible at fontScale=$scale: $glyph in $timeBounds",
                            glyph.left >= 0 && glyph.top >= 0 && glyph.right <= timeBounds.width && glyph.bottom <= timeBounds.height,
                        )
                    }
                    assertEquals(timeLayout.size.width.toFloat(), timeBounds.width, 1f)
                    assertTrue("timestamp must stay within the pane", timeBounds.right <= pane.right)
                    assertTrue("sender must leave room for metadata", nickBounds.right < timeBounds.left)
                    if (own) {
                        val status = sent().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                        assertTrue("status must retain positive bounds", status.width > 0 && status.height > 0)
                        assertTrue("sender must leave room for status", nickBounds.right <= status.left)
                        assertTrue("status must precede time", status.right <= timeBounds.left)
                        assertTrue("status must share the header band", status.top < nickBounds.bottom && nickBounds.top < status.bottom)
                        assertTrue("status must remain inside pane", status.left >= pane.left && status.right <= pane.right)
                    } else {
                        sent().assertDoesNotExist()
                    }

                    compose.runOnIdle { showTime.value = false }
                    compose.onNodeWithText(TIME, useUnmergedTree = true).assertDoesNotExist()
                    nick.assertIsDisplayed()
                    if (own) sent().assertIsDisplayed()
                    compose.runOnIdle { showSender.value = false }
                    nick.assertDoesNotExist()
                    sent().assertDoesNotExist()
                    compose.onNodeWithText(BODY, useUnmergedTree = true).assertIsDisplayed()
                }
            }
        }
    }

    @Test
    fun comfortableActionKeepsOwnNickInEmoteText() {
        compose.setContent { Sample(kind = MessageKind.ACTION) }

        compose.onNodeWithTag("chat_action_text", useUnmergedTree = true).assertTextEquals("$NICK $BODY").assertIsDisplayed()
        compose.onNodeWithContentDescription("* $NICK $BODY", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("self_sender_label", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun comfortableAvatarKeepsSenderActionForOrdinaryAndActionMessages() {
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        var senderOpens = 0
        compose.setContent { Sample(isSelf = false, kind = kind.value, onSenderClick = { senderOpens++ }) }

        compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, senderOpens) }

        compose.runOnIdle { kind.value = MessageKind.ACTION }
        compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(2, senderOpens) }
    }

    @Test
    fun comfortableShadowsCanBeDisabledForOrdinaryAndActionMessages() {
        val shadows = mutableStateOf(true)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        compose.setContent { Sample(isSelf = false, kind = kind.value, shadows = shadows.value) }

        for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
            compose.runOnIdle {
                kind.value = messageKind
                shadows.value = true
            }
            val withShadows = compose.onNodeWithTag("sample").captureToImage().toPixelMap()
            compose.runOnIdle { shadows.value = false }
            val withoutShadows = compose.onNodeWithTag("sample").captureToImage().toPixelMap()
            assertTrue(
                "$messageKind should render different pixels when chat shadows change",
                (0 until withShadows.width).any { x ->
                    (0 until withShadows.height).any { y -> withShadows[x, y] != withoutShadows[x, y] }
                },
            )
        }
    }

    @Test
    fun elevatedReactionChipHasRoomForItsShadowInsideAnimatedRow() {
        compose.setContent { Sample(reactions = listOf(ReactionChip("♥", 1, mine = false))) }

        val row = compose.onNodeWithTag("chat_reaction_row", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chip = compose.onNodeWithTag("chat_reaction_chip_♥", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("reaction shadow needs room at the bottom of its animated row", chip.bottom < row.bottom)
        assertTrue("reaction shadow needs room at the left edge of its animated row", row.left < chip.left)
        assertTrue("reaction shadow needs room at the right edge of its animated row", chip.right < row.right)
    }

    @Test
    fun pressedRectangleIndicationIsClippedToZeroElevationReactionCorners() {
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.LIGHT) {
                CompositionLocalProvider(LocalIndication provides PressedRectangleIndication) {
                    Surface(Modifier.width(120.dp)) {
                        ReactionRow(
                            reactions = listOf(ReactionChip("♥", 1, mine = false)),
                            onReact = {},
                            chipElevation = 0.dp,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
        }
        val chip = compose.onNodeWithTag("chat_reaction_chip_♥", useUnmergedTree = true).assertIsDisplayed()
        val idle = chip.captureToImage().toPixelMap()
        chip.performTouchInput { down(center) }
        try {
            // A real press paints a rectangle; only the production chip clip rounds its corners.
            compose.waitForIdle()
            val pressed = chip.captureToImage().toPixelMap()
            assertEquals(idle.width, pressed.width)
            assertEquals(idle.height, pressed.height)
            assertTrue(
                "the held press must visibly paint the rectangle inside the chip",
                (idle.width / 4 until idle.width * 3 / 4).any { x ->
                    (1 until idle.height / 4).any { y -> idle[x, y] != pressed[x, y] }
                },
            )
            // These small patches are wholly outside the capsule, not on its antialiased edge.
            for (dx in 0 until idle.height / 8) {
                for (dy in 0 until idle.height / 8) {
                    for (x in listOf(dx, idle.width - 1 - dx)) {
                        for (y in listOf(dy, idle.height - 1 - dy)) {
                            assertEquals("pressed indication must not square off corner $x,$y", idle[x, y], pressed[x, y])
                        }
                    }
                }
            }
        } finally {
            chip.performTouchInput { cancel() }
        }
    }

    private object PressedRectangleIndication : IndicationNodeFactory {
        override fun create(interactionSource: InteractionSource): DelegatableNode =
            object : Modifier.Node(), DrawModifierNode {
                private var pressCount = 0

                override fun onAttach() {
                    coroutineScope.launch {
                        interactionSource.interactions.collect { interaction ->
                            when (interaction) {
                                is PressInteraction.Press -> pressCount++
                                is PressInteraction.Release, is PressInteraction.Cancel -> pressCount--
                            }
                            invalidateDraw()
                        }
                    }
                }

                override fun ContentDrawScope.draw() {
                    drawContent()
                    if (pressCount > 0) drawRect(Color.Magenta)
                }
            }

        override fun hashCode(): Int = 0

        override fun equals(other: Any?): Boolean = other === this
    }

    @Test
    fun messageReadingInkAndTimestampsFollowSelectedTextColorsAcrossDensities() {
        val preset = mutableStateOf(ColorThemePreset.AYU_LIGHT)
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val role = mutableStateOf(Triple(MessageKind.PRIVMSG, false, false))
        val useThemeTextColors = mutableStateOf(false)
        var fill = Color.Unspecified
        var codeFill = Color.Unspecified
        var replyFill = Color.Unspecified
        var timestampColor = Color.Unspecified
        var linkColor = Color.Unspecified
        var mentionColor = Color.Unspecified
        var paletteReadingInk = Color.Unspecified
        compose.setContent {
            MotdTheme(
                dynamicColor = false,
                themePreset = preset.value,
                layoutDensity = density.value,
                avatarStyle = AvatarStyle.NONE,
                useThemeTextColors = useThemeTextColors.value,
                timestampConfig = TimestampConfig(show = true),
            ) {
                val scheme = MaterialTheme.colorScheme
                paletteReadingInk = scheme.onBackground
                val semantic = LocalMotdSemanticColors.current
                val nicks = LocalNickColors.current
                val (kind, self, mention) = role.value
                val highlighted = mention && !self
                val bubble = messageBubbleRoleColors(scheme, self, mention, kind, semantic)
                val flatTint =
                    when {
                        kind == MessageKind.ACTION && highlighted -> semantic.warningContainer.copy(alpha = MENTION_ROW_TINT_ALPHA)
                        kind == MessageKind.ACTION -> scheme.tertiaryContainer.copy(alpha = 0.22f)
                        highlighted && density.value == LayoutDensity.COMPACT -> scheme.secondaryContainer.copy(alpha = MENTION_ROW_TINT_ALPHA)
                        highlighted -> semantic.warningContainer.copy(alpha = MENTION_ROW_TINT_ALPHA)
                        else -> nicks.nick("alice", scheme.onSurfaceVariant).copy(alpha = 0.10f)
                    }
                fill = if (density.value == LayoutDensity.COMFORTABLE) bubble.container else flatTint.compositeOver(scheme.background)
                val metadataInk = if (density.value == LayoutDensity.COMFORTABLE) bubble.content else scheme.onSurfaceVariant
                timestampColor = ensureContrast(metadataInk, listOf(fill), MESSAGE_TEXT_CONTRAST)
                val codeBackground = scheme.surfaceVariant.copy(alpha = if (density.value == LayoutDensity.COMPACT && kind != MessageKind.ACTION) 0.72f else 1f)
                codeFill = codeBackground.compositeOver(fill)
                replyFill = scheme.primaryContainer
                val linkInk =
                    if (density.value == LayoutDensity.COMFORTABLE && (kind == MessageKind.ACTION || kind == MessageKind.NOTICE || self || mention)) {
                        bubble.content
                    } else {
                        scheme.primary
                    }
                linkColor = ensureContrast(linkInk, listOf(fill), MESSAGE_TEXT_CONTRAST)
                mentionColor = ensureContrast(nicks.nick("bob", Color.Unspecified), listOf(fill), MESSAGE_TEXT_CONTRAST)
                Surface(Modifier.width(380.dp), color = scheme.background) {
                    MessageBubble(
                        sender = "alice",
                        text = "Reading https://example.com @bob `code`",
                        timeMs = 0,
                        formattedTime = TIME,
                        kind = kind,
                        isSelf = self,
                        hasMention = mention,
                        // TWO_LINE continuations intentionally omit the timestamp-bearing header.
                        showSender = true,
                        knownNicks = setOf("bob"),
                        reply = ReplyPreviewData("quoted", "Quoted reply"),
                        modifier = Modifier.testTag("reading_ink_message"),
                    )
                }
            }
        }
        val roles =
            listOf(
                Triple(MessageKind.PRIVMSG, false, false),
                Triple(MessageKind.PRIVMSG, true, false),
                Triple(MessageKind.PRIVMSG, false, true),
                Triple(MessageKind.NOTICE, false, false),
                Triple(MessageKind.ACTION, false, false),
                Triple(MessageKind.ACTION, true, true),
            )
        for (theme in listOf(ColorThemePreset.AYU_LIGHT, ColorThemePreset.AYU_DARK)) {
            for (themed in listOf(false, true)) {
                for (layout in listOf(LayoutDensity.COMFORTABLE, LayoutDensity.COMPACT, LayoutDensity.TWO_LINE)) {
                    for (messageRole in roles) {
                        compose.runOnIdle {
                            preset.value = theme
                            useThemeTextColors.value = themed
                            density.value = layout
                            role.value = messageRole
                        }
                        compose.waitForIdle()
                        val seed =
                            when {
                                themed -> paletteReadingInk
                                theme == ColorThemePreset.AYU_LIGHT -> Color(0xFF171717)
                                else -> Color(0xFFF5F5F5)
                            }
                        val label = "$theme themed=$themed $layout $messageRole"
                        val body = compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true).textLayout()
                        val timestamp =
                            compose
                                .onNodeWithText(TIME, useUnmergedTree = true)
                                .textLayout()
                                .layoutInput.style.color
                        if (layout == LayoutDensity.TWO_LINE) {
                            // Resolve contrast against the actual baseline over the known theme canvas,
                            // not the unattenuated nick tint or arbitrary wallpaper.
                            val pixels = compose.onNodeWithTag("reading_ink_message", useUnmergedTree = true).captureToImage().toPixelMap()
                            val paintedFill = pixels[8, 2]
                            val attainable = maxOf(contrastRatio(Color.Black, paintedFill), contrastRatio(Color.White, paintedFill))
                            val minimum = if (attainable >= MESSAGE_TEXT_CONTRAST) MESSAGE_TEXT_CONTRAST else 4.5
                            for (word in listOf("Reading", "https://", "@bob")) {
                                assertTrue("$label $word reading contrast", contrastRatio(body.inkAt(word), paintedFill) >= minimum - 0.05)
                            }
                            assertTrue("$label timestamp reading contrast", contrastRatio(timestamp, paintedFill) >= minimum - 0.05)
                        } else {
                            assertEquals("$label prose", ensureContrast(seed, listOf(fill), MESSAGE_TEXT_CONTRAST), body.inkAt("Reading"))
                            assertEquals("$label link", linkColor, body.inkAt("https://"))
                            assertEquals("$label mention", mentionColor, body.inkAt("@bob"))
                            assertEquals("$label timestamp", timestampColor, timestamp)
                        }
                        assertEquals("$label code", ensureContrast(seed, listOf(codeFill), MESSAGE_TEXT_CONTRAST), body.inkAt("code"))
                        if (!themed) {
                            val prose = body.inkAt("Reading")
                            assertEquals("$label neutral prose red/green", prose.red, prose.green, 0.00001f)
                            assertEquals("$label neutral prose green/blue", prose.green, prose.blue, 0.00001f)
                            assertEquals("$label neutral timestamp red/green", timestamp.red, timestamp.green, 0.00001f)
                            assertEquals("$label neutral timestamp green/blue", timestamp.green, timestamp.blue, 0.00001f)
                        }
                        val quote = compose.onNodeWithText("Quoted reply", useUnmergedTree = true).textLayout()
                        assertEquals("$label reply", ensureContrast(seed, listOf(replyFill), MESSAGE_TEXT_CONTRAST), quote.inkAt("Quoted"))
                    }
                }
            }
        }
    }

    @Test
    fun ayuLightMessageTextUsesStrongerContrastOnItsActualFill() {
        val self = mutableStateOf(false)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val mention = mutableStateOf(false)
        compose.setContent {
            ContrastSample(isSelf = self.value, kind = kind.value, hasMention = mention.value, reply = ReplyPreviewData("quoted", "Quoted reply"))
        }
        for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)) {
            for (own in listOf(false, true)) {
                compose.runOnIdle {
                    self.value = own
                    kind.value = messageKind
                    mention.value = false
                }
                assertPaintedTextContrast("$messageKind self=$own", compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true))
                assertPaintedTextContrast("reply $messageKind self=$own", compose.onNodeWithText("Quoted reply", useUnmergedTree = true))
            }
        }
        compose.runOnIdle {
            self.value = false
            kind.value = MessageKind.PRIVMSG
            mention.value = true
        }
        assertPaintedTextContrast("mention", compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true))
    }

    @Test
    fun nicknameBackgroundToggleChangesOnlyOrdinaryDenseRowPixels() {
        data class Row(
            val self: Boolean = false,
            val sender: Boolean = true,
            val mention: Boolean = false,
            val kind: MessageKind = MessageKind.PRIVMSG,
            val friend: Boolean = false,
            val dimmed: Boolean = false,
        )
        val density = mutableStateOf(LayoutDensity.COMPACT)
        val row = mutableStateOf(Row())
        val enabled = mutableStateOf(true)
        val coloredNicks = mutableStateOf(true)
        val coloredNicknameInk = mutableMapOf<LayoutDensity, Color>()

        // Native rendering can round a composited channel one step differently from Color.toArgb().
        fun matchesPixel(
            actual: Int,
            expected: Int,
        ): Boolean =
            (0..3).all { channel ->
                val shift = channel * 8
                kotlin.math.abs((actual ushr shift and 255) - (expected ushr shift and 255)) <= 1
            }
        compose.setContent {
            ContrastSample(
                density = density.value,
                isSelf = row.value.self,
                showSender = row.value.sender,
                hasMention = row.value.mention,
                kind = row.value.kind,
                senderIsFriend = row.value.friend,
                dimmed = row.value.dimmed,
                nicknameBackgroundsEnabled = enabled.value,
                nickColorsEnabled = coloredNicks.value,
                avatarStyle = AvatarStyle.IRC_SPRITE,
            )
        }
        val scheme = requireNotNull(fixedThemeScheme(ColorThemePreset.AYU_LIGHT))
        for (layout in LayoutDensity.entries) {
            for (nickColors in listOf(true, false)) {
                for (
                sample in
                listOf(
                    Row(),
                    Row(self = true),
                    Row(sender = false),
                    Row(self = true, sender = false),
                    Row(mention = true),
                    Row(self = true, mention = true),
                    Row(kind = MessageKind.ACTION),
                    Row(mention = true, kind = MessageKind.ACTION),
                    Row(friend = true),
                    Row(dimmed = true),
                )
                ) {
                    val label = "$layout coloredNicks=$nickColors $sample"
                    compose.runOnIdle {
                        density.value = layout
                        coloredNicks.value = nickColors
                        row.value = sample
                        enabled.value = true
                    }
                    val canvas = compose.onNodeWithTag("contrast_sample")
                    val tinted = canvas.captureToImage().asAndroidBitmap()
                    val avatar = compose.onAllNodesWithTag("chat_sender_avatar", useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
                    val identity = avatar?.let { compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true).captureToImage().asAndroidBitmap() }
                    compose.runOnIdle { enabled.value = false }
                    val plain = canvas.captureToImage().asAndroidBitmap()
                    if (identity != null) {
                        assertTrue("$label must preserve avatar identity pixels", identity.sameAs(compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true).captureToImage().asAndroidBitmap()))
                    }
                    val ordinaryDense = layout != LayoutDensity.COMFORTABLE && !(sample.mention && !sample.self) && sample.kind == MessageKind.PRIVMSG
                    if (ordinaryDense) {
                        val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot
                        val bounds = compose.onNodeWithTag("contrast_message", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                        val y = (bounds.top - canvasBounds.top).toInt() + 2
                        val expected = messageContainerColor(scheme.background, scheme.background, sample.dimmed).toArgb()
                        assertTrue("$label OFF must paint the normal chat backing", matchesPixel(plain.getPixel(8, y), expected))
                        assertTrue("$label ON must have a sender wash", tinted.getPixel(8, y) != plain.getPixel(8, y))
                        if (sample == Row()) {
                            val ink = compose.onNodeWithText("alice", substring = true, useUnmergedTree = true).textLayout().inkAt("alice")
                            if (nickColors) {
                                coloredNicknameInk[layout] = ink
                            } else {
                                assertTrue("$label OFF must not disable nickname colors", coloredNicknameInk.getValue(layout) != ink)
                            }
                        }
                        if (sample.friend) {
                            val friendFill =
                                scheme.primary
                                    .copy(alpha = 0.12f)
                                    .compositeOver(scheme.background)
                                    .toArgb()
                            val nick = compose.onNodeWithText("alice", substring = true, useUnmergedTree = true)
                            val nickBounds = nick.fetchSemanticsNode().boundsInRoot
                            val left = (nickBounds.left - canvasBounds.left).toInt()
                            val right = (nickBounds.right - canvasBounds.left).toInt()
                            val top = (nickBounds.top - canvasBounds.top).toInt()
                            val bottom = (nickBounds.bottom - canvasBounds.top).toInt()
                            assertTrue(
                                "$label OFF must preserve the painted friend nickname highlight",
                                (left until right).any { x -> (top until bottom).any { py -> matchesPixel(plain.getPixel(x, py), friendFill) } },
                            )
                        }
                        assertPaintedTextContrast("$label OFF", compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true), if (sample.dimmed) 4.5 else 7.0)
                    } else {
                        assertTrue("$label mentions, ACTION, and Comfortable must be unchanged", tinted.sameAs(plain))
                    }
                    compose.runOnIdle { enabled.value = true }
                    assertTrue("$label must restore its original rendered pixels", tinted.sameAs(canvas.captureToImage().asAndroidBitmap()))
                }
            }
        }
    }

    @Test
    fun messageBackgroundOpacityPaintsZeroIntermediateAndFullAlphaWithoutFadingContent() {
        data class Row(
            val self: Boolean = false,
            val sender: Boolean = true,
            val kind: MessageKind = MessageKind.PRIVMSG,
            val friend: Boolean = false,
            val dimmed: Boolean = false,
            val mention: Boolean = false,
        )
        val density = mutableStateOf(LayoutDensity.COMPACT)
        val row = mutableStateOf(Row())
        val opacity = mutableStateOf(100)
        compose.setContent {
            ContrastSample(
                density = density.value,
                isSelf = row.value.self,
                showSender = row.value.sender,
                showTimestamp = true,
                kind = row.value.kind,
                senderIsFriend = row.value.friend,
                dimmed = row.value.dimmed,
                hasMention = row.value.mention,
                nicknameBackgroundsEnabled = false,
                messageBackgroundOpacityPercent = opacity.value,
                // Mid-tone fills exercise the old opaque contrast fallback; the contrasting
                // canvas makes even zero opacity observable without asynchronous wallpaper loads.
                background = Color.Gray,
                canvasColor = Color.Magenta,
                avatarStyle = AvatarStyle.MONOGRAM,
            )
        }
        for (layout in listOf(LayoutDensity.COMPACT, LayoutDensity.TWO_LINE)) {
            for (sample in listOf(Row(), Row(self = true), Row(sender = false), Row(self = true, sender = false), Row(kind = MessageKind.NOTICE), Row(friend = true), Row(dimmed = true), Row(self = true, mention = true))) {
                compose.runOnIdle {
                    density.value = layout
                    row.value = sample
                    opacity.value = 100
                }
                val canvas = compose.onNodeWithTag("contrast_sample")
                val original = canvas.captureToImage().asAndroidBitmap()
                val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot
                val bounds = compose.onNodeWithTag("contrast_message", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val y = (bounds.top - canvasBounds.top).toInt() + 2
                val avatar = compose.onAllNodesWithTag("chat_sender_avatar", useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()
                val identity = avatar?.let { compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true).captureToImage().asAndroidBitmap() }
                for (percent in listOf(0, 37, 100)) {
                    compose.runOnIdle { opacity.value = percent }
                    val pixels = canvas.captureToImage().asAndroidBitmap()
                    val expected =
                        Color.Gray
                            .copy(alpha = percent / 100f)
                            .compositeOver(Color.Magenta)
                            .toArgb()
                    val actual = pixels.getPixel(8, y)
                    for (channel in 0..3) {
                        val shift = channel * 8
                        assertTrue(
                            "$layout $sample opacity=$percent channel=$channel must paint the requested alpha, even when dimmed",
                            kotlin.math.abs((actual ushr shift and 255) - (expected ushr shift and 255)) <= 1,
                        )
                    }
                    val body = compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true)
                    val text = body.textLayout()
                    val ink = text.inkAt("Reading")
                    assertEquals("$layout $sample opacity=$percent must retain opaque text ink", 1f, ink.alpha, 0f)
                    val textBounds = body.fetchSemanticsNode().boundsInRoot
                    val offset = text.layoutInput.text.indexOf("Reading")
                    val first = text.getBoundingBox(offset)
                    val last = text.getBoundingBox(offset + "Reading".lastIndex)
                    val left = (textBounds.left - canvasBounds.left + first.left).toInt()
                    val right = (textBounds.left - canvasBounds.left + last.right).toInt()
                    val top = (textBounds.top - canvasBounds.top + first.top).toInt()
                    val bottom = (textBounds.top - canvasBounds.top + first.bottom).toInt()
                    assertTrue(
                        "$layout $sample opacity=$percent must paint full-opacity glyph pixels",
                        (left until right).any { x -> (top until bottom).any { py -> pixels.getPixel(x, py) == ink.toArgb() } },
                    )
                    if (identity != null) {
                        assertTrue("$layout opacity=$percent must not fade avatars", identity.sameAs(compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true).captureToImage().asAndroidBitmap()))
                    }
                }
                assertTrue("$layout $sample must restore the exact 100% rendering", original.sameAs(canvas.captureToImage().asAndroidBitmap()))
            }
        }
    }

    @Test
    fun opacityControlsEveryLayoutTintAndActionButPreservesOrdinaryMentionFills() {
        data class Row(
            val density: LayoutDensity,
            val enabled: Boolean,
            val mention: Boolean = false,
            val kind: MessageKind = MessageKind.PRIVMSG,
            val dimmed: Boolean = false,
        )
        val row = mutableStateOf(Row(LayoutDensity.COMFORTABLE, true))
        val opacity = mutableStateOf(100)
        compose.setContent {
            ContrastSample(
                density = row.value.density,
                nicknameBackgroundsEnabled = row.value.enabled,
                messageBackgroundOpacityPercent = opacity.value,
                hasMention = row.value.mention,
                kind = row.value.kind,
                dimmed = row.value.dimmed,
                showSender = true,
                showTimestamp = true,
                canvasColor = Color.Magenta,
            )
        }
        val rows =
            LayoutDensity.entries.flatMap { density ->
                listOf(false, true).flatMap { enabled ->
                    listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION).flatMap { kind ->
                        listOf(Row(density, enabled, kind = kind), Row(density, enabled, kind = kind, dimmed = true), Row(density, enabled, mention = true, kind = kind))
                    }
                }
            }
        for (sample in rows) {
            compose.runOnIdle {
                row.value = sample
                opacity.value = 100
            }
            val canvas = compose.onNodeWithTag("contrast_sample")
            val original = canvas.captureToImage().asAndroidBitmap()
            // A pixel from the dominant backing color avoids text, rounded edges, shadows and rails.
            val counts = mutableMapOf<Int, Int>()
            for (x in 0 until original.width) {
                for (y in 0 until original.height) {
                    val pixel = original.getPixel(x, y)
                    if (pixel != Color.Magenta.toArgb()) counts[pixel] = (counts[pixel] ?: 0) + 1
                }
            }
            val backing = counts.maxBy { it.value }.key
            var sampleX = 0
            var sampleY = 0
            findPixel@ for (y in 0 until original.height) {
                for (x in 0 until original.width) {
                    if (original.getPixel(x, y) == backing) {
                        sampleX = x
                        sampleY = y
                        break@findPixel
                    }
                }
            }
            for (percent in listOf(37, 0, 100)) {
                compose.runOnIdle { opacity.value = percent }
                val pixels = canvas.captureToImage().asAndroidBitmap()
                if (sample.mention && sample.kind != MessageKind.ACTION || percent == 100) {
                    assertTrue("$sample opacity=$percent preserves highlights or restores baseline", original.sameAs(pixels))
                } else {
                    val expected = Color(backing).copy(alpha = percent / 100f).compositeOver(Color.Magenta).toArgb()
                    val actual = pixels.getPixel(sampleX, sampleY)
                    for (shift in listOf(0, 8, 16, 24)) {
                        assertTrue("$sample opacity=$percent backing channel=$shift", kotlin.math.abs((actual ushr shift and 255) - (expected ushr shift and 255)) <= 2)
                    }
                    assertTrue("$sample opacity=$percent changes backing", !original.sameAs(pixels))
                }
                val body = compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true)
                val layout = body.textLayout()
                val ink = layout.inkAt("Reading")
                assertEquals("$sample text never fades", 1f, ink.alpha, 0f)
                val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot
                val bounds = body.fetchSemanticsNode().boundsInRoot
                val offset = layout.layoutInput.text.indexOf("Reading")
                val first = layout.getBoundingBox(offset)
                val last = layout.getBoundingBox(offset + "Reading".lastIndex)
                assertTrue(
                    "$sample opacity=$percent paints fully opaque glyphs",
                    ((bounds.left - canvasBounds.left + first.left).toInt() until (bounds.left - canvasBounds.left + last.right).toInt()).any { x ->
                        ((bounds.top - canvasBounds.top + first.top).toInt() until (bounds.top - canvasBounds.top + first.bottom).toInt()).any { y -> pixels.getPixel(x, y) == ink.toArgb() }
                    },
                )
            }
        }
    }

    @Test
    fun opacityLeavesReplyAndAudioChildSurfacesOpaqueAcrossEveryLayout() {
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val audio = mutableStateOf(false)
        val opacity = mutableStateOf(100)
        compose.setContent {
            ContrastSample(
                density = density.value,
                audio = audio.value,
                reply = ReplyPreviewData("quoted", "Quoted reply"),
                messageBackgroundOpacityPercent = opacity.value,
                canvasColor = Color.Magenta,
            )
        }
        for (layout in LayoutDensity.entries) {
            for (isAudio in listOf(false, true)) {
                compose.runOnIdle {
                    density.value = layout
                    audio.value = isAudio
                    opacity.value = 100
                }
                val child = if (isAudio) compose.onNodeWithTag("audio_player", useUnmergedTree = true) else compose.onNodeWithText("Quoted reply", useUnmergedTree = true)
                val original = child.captureToImage().asAndroidBitmap()
                for (percent in listOf(37, 0, 100)) {
                    compose.runOnIdle { opacity.value = percent }
                    assertTrue("$layout audio=$isAudio opacity=$percent child does not fade", original.sameAs(child.captureToImage().asAndroidBitmap()))
                }
            }
        }
    }

    @Test
    fun opacityKeepsKnownCanvasBodyAndMetadataReadableInEveryLayoutAndKind() {
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val theme = mutableStateOf(ColorThemePreset.AYU_LIGHT)
        val opacity = mutableStateOf(100)
        compose.setContent {
            ContrastSample(
                density = density.value,
                kind = kind.value,
                theme = theme.value,
                messageBackgroundOpacityPercent = opacity.value,
                showSender = true,
                showTimestamp = true,
            )
        }
        for (preset in listOf(ColorThemePreset.AYU_LIGHT, ColorThemePreset.AYU_DARK)) {
            for (layout in LayoutDensity.entries) {
                for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)) {
                    for (percent in listOf(0, 37, 100)) {
                        compose.runOnIdle {
                            theme.value = preset
                            density.value = layout
                            kind.value = messageKind
                            opacity.value = percent
                        }
                        val label = "$preset $layout $messageKind opacity=$percent"
                        assertPaintedTextContrast(label, compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true))
                        assertPaintedTextContrast(label, compose.onNodeWithText(TIME, useUnmergedTree = true))
                    }
                }
            }
        }
    }

    @Test
    fun messageBackgroundOpacityKeepsLightAndDarkBodyInkReadableAtZeroAndLowAlpha() {
        val theme = mutableStateOf(ColorThemePreset.AYU_LIGHT)
        val density = mutableStateOf(LayoutDensity.COMPACT)
        val opacity = mutableStateOf(0)
        compose.setContent {
            ContrastSample(
                theme = theme.value,
                density = density.value,
                nicknameBackgroundsEnabled = false,
                messageBackgroundOpacityPercent = opacity.value,
                showSender = true,
                showTimestamp = true,
            )
        }
        for (preset in listOf(ColorThemePreset.AYU_LIGHT, ColorThemePreset.AYU_DARK)) {
            for (layout in listOf(LayoutDensity.COMPACT, LayoutDensity.TWO_LINE)) {
                for (percent in listOf(0, 10, 37, 100)) {
                    compose.runOnIdle {
                        theme.value = preset
                        density.value = layout
                        opacity.value = percent
                    }
                    val label = "$preset $layout opacity=$percent"
                    val canvas = compose.onNodeWithTag("contrast_sample")
                    val image = canvas.captureToImage()
                    val pixels = image.toPixelMap()
                    val canvasBounds = canvas.fetchSemanticsNode().boundsInRoot
                    val row = compose.onNodeWithTag("contrast_message", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val fill = pixels[8, (row.top - canvasBounds.top).toInt() + 2]
                    val expectedFill = requireNotNull(fixedThemeScheme(preset)).background.toArgb()
                    val actualFill = fill.toArgb()
                    for (channel in 0..3) {
                        val shift = channel * 8
                        assertTrue(
                            "$label must retain its actual theme canvas",
                            kotlin.math.abs(((expectedFill ushr shift) and 0xff) - ((actualFill ushr shift) and 0xff)) <= 1,
                        )
                    }
                    val body = compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true)
                    val text = body.textLayout()
                    val ink = text.inkAt("Reading")
                    assertTrue(
                        "$label must use dark ink on the light canvas and light ink on the dark canvas",
                        if (preset == ColorThemePreset.AYU_LIGHT) ink.luminance() < 0.5f else ink.luminance() > 0.5f,
                    )
                    // Native 8-bit alpha compositing may shift the canvas by one channel step.
                    assertPaintedTextContrast(label, body, minimumTarget = MESSAGE_TEXT_CONTRAST - 0.1)
                    val textBounds = body.fetchSemanticsNode().boundsInRoot
                    val offset = text.layoutInput.text.indexOf("Reading")
                    val first = text.getBoundingBox(offset)
                    val last = text.getBoundingBox(offset + "Reading".lastIndex)
                    val left = (textBounds.left - canvasBounds.left + first.left).toInt()
                    val right = (textBounds.left - canvasBounds.left + last.right).toInt()
                    val top = (textBounds.top - canvasBounds.top + first.top).toInt()
                    val bottom = (textBounds.top - canvasBounds.top + first.bottom).toInt()
                    val paintedContrast = (left until right).maxOf { x -> (top until bottom).maxOf { y -> contrastRatio(pixels[x, y], fill) } }
                    assertTrue("$label actual body glyph contrast=$paintedContrast", paintedContrast >= MESSAGE_TEXT_CONTRAST - 0.2)
                }
            }
        }
    }

    @Test
    fun rowsPreserveInkWhileOnlyNormalTwoLineFillLetsWallpaperThrough() = assertRowsPreserveInkAndWallpaper(true)

    @Test
    fun nicknameBackgroundsOffPreservesInkAndWallpaperContrast() = assertRowsPreserveInkAndWallpaper(false)

    private fun assertRowsPreserveInkAndWallpaper(nicknameBackgroundsEnabled: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CustomWallpaperStore(context)
        val source = File.createTempFile("wallpaper-contrast", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.MAGENTA) }
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val image = runBlocking { store.import(Uri.fromFile(source)).getOrThrow() }
        val density = mutableStateOf(LayoutDensity.COMPACT)
        val role = mutableStateOf(Triple(MessageKind.PRIVMSG, false, false))
        val intensity = mutableStateOf(100)
        val localImage = mutableStateOf<String?>(null)
        compose.setContent {
            ContrastSample(
                density = density.value,
                kind = role.value.first,
                isSelf = role.value.second,
                hasMention = role.value.third,
                showSender = true,
                showTimestamp = true,
                wallpaperIntensity = intensity.value,
                wallpaperImage = localImage.value,
                nicknameBackgroundsEnabled = nicknameBackgroundsEnabled,
            )
        }
        try {
            for (name in listOf(null, image)) {
                compose.runOnIdle { localImage.value = name }
                for (layout in listOf(LayoutDensity.COMPACT, LayoutDensity.TWO_LINE)) {
                    for (
                    messageRole in
                    listOf(
                        Triple(MessageKind.PRIVMSG, false, false),
                        Triple(MessageKind.PRIVMSG, true, false),
                        Triple(MessageKind.PRIVMSG, false, true),
                        Triple(MessageKind.ACTION, false, false),
                    )
                    ) {
                        val (messageKind, self, mention) = messageRole
                        val label = "$layout $messageKind self=$self mention=$mention"
                        compose.runOnIdle {
                            density.value = layout
                            role.value = messageRole
                            intensity.value = 100
                        }
                        val sample = compose.onNodeWithTag("contrast_sample")
                        val sampleBounds = sample.fetchSemanticsNode().boundsInRoot
                        val row = compose.onNodeWithTag("contrast_message", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                        val top = (row.top - sampleBounds.top).toInt()
                        val bottom = (row.bottom - sampleBounds.top).toInt()
                        compose.waitUntil(timeoutMillis = 5_000) {
                            val pixels = sample.captureToImage().toPixelMap()
                            if (name == null) {
                                (0 until pixels.width).any { x ->
                                    (0 until top - 8).any { y -> pixels[x, y].luminance() < 0.5f }
                                }
                            } else {
                                pixels[pixels.width / 2, 8] == Color.Magenta
                            }
                        }
                        val fullImage = sample.captureToImage()
                        val fullInk = fullImage.toPixelMap()
                        // Arbitrary wallpaper has no 7:1 guarantee; this fixture must still paint
                        // readable, full-opacity glyphs rather than fading the message content.
                        assertPaintedTextContrast("$label wallpaper=100", compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true), minimumTarget = 4.5)
                        compose.runOnIdle { intensity.value = 0 }
                        val noImage = sample.captureToImage()
                        val noInk = noImage.toPixelMap()
                        assertPaintedTextContrast("$label known theme canvas", compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true))
                        if (layout == LayoutDensity.TWO_LINE && (nicknameBackgroundsEnabled || mention || messageKind == MessageKind.ACTION)) {
                            assertTrue(
                                "$label must let wallpaper contribute to the row fill",
                                (top + 2 until bottom - 2).any { y -> noInk[8, y] != fullInk[8, y] },
                            )
                            if (name != null) {
                                val fill = fullInk[8, top + 2]
                                assertTrue("$label must retain more tint than wallpaper", contrastRatio(fill, noInk[8, top + 2]) < contrastRatio(fill, Color.Magenta))
                            }
                            for (word in listOf("Reading", "alice", TIME)) {
                                val node = compose.onNodeWithText(word, substring = word != TIME, useUnmergedTree = true)
                                val textBounds = node.fetchSemanticsNode().boundsInRoot
                                val text = node.textLayout()
                                val ink = Color(text.inkAt(word).toArgb())
                                val offset = text.layoutInput.text.indexOf(word)
                                val first = text.getBoundingBox(offset)
                                val last = text.getBoundingBox(offset + word.length - 1)
                                val left = (textBounds.left - sampleBounds.left + first.left).toInt()
                                val right = (textBounds.left - sampleBounds.left + last.right).toInt()
                                val textTop = (textBounds.top - sampleBounds.top + first.top).toInt()
                                val textBottom = (textBounds.top - sampleBounds.top + first.bottom).toInt()
                                assertTrue(
                                    "$label $word must paint full-opacity ink independently of wallpaper",
                                    (left until right).any { x ->
                                        (textTop until textBottom).any { y ->
                                            fullInk[x, y] == ink && noInk[x, y] == ink
                                        }
                                    },
                                )
                            }
                        } else {
                            for (x in 8 until fullInk.width - 8) {
                                for (y in top + 2 until bottom - 2) {
                                    assertEquals("$label wallpaper leaked under row at $x,$y", noInk[x, y], fullInk[x, y])
                                }
                            }
                        }
                        assertTrue(
                            "$label must leave full-intensity wallpaper visible outside the row",
                            (0 until fullInk.width).any { x -> (0 until top - 8).any { y -> noInk[x, y] != fullInk[x, y] } },
                        )
                    }
                }
            }
        } finally {
            runBlocking { store.delete(image) }
            source.delete()
        }
    }

    @Test
    fun midToneTwoLineFillStaysOpaqueWhenWallpaperWouldDefeatReadingContrast() {
        val intensity = mutableStateOf(0)
        compose.setContent {
            ContrastSample(
                density = LayoutDensity.TWO_LINE,
                background = Color.Gray,
                showSender = true,
                showTimestamp = true,
                wallpaperIntensity = intensity.value,
            )
        }
        val sample = compose.onNodeWithTag("contrast_sample")
        val sampleBounds = sample.fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithTag("contrast_message", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val top = (row.top - sampleBounds.top).toInt()
        val bottom = (row.bottom - sampleBounds.top).toInt()
        val plain = sample.captureToImage().toPixelMap()
        compose.runOnIdle { intensity.value = 100 }
        compose.waitUntil(timeoutMillis = 5_000) {
            val pixels = sample.captureToImage().toPixelMap()
            (0 until pixels.width).any { x -> (0 until top - 8).any { y -> pixels[x, y] != plain[x, y] } }
        }
        val wallpaper = sample.captureToImage().toPixelMap()
        for (x in 8 until wallpaper.width - 8) {
            for (y in top + 2 until bottom - 2) {
                assertEquals("mid-tone fallback must keep wallpaper outside the row at $x,$y", plain[x, y], wallpaper[x, y])
            }
        }
        val fill = wallpaper[8, top + 2]
        assertTrue("fixture must exercise a mid-tone fill without an attainable 7:1 ink", maxOf(contrastRatio(Color.Black, fill), contrastRatio(Color.White, fill)) < MESSAGE_TEXT_CONTRAST)
        for (word in listOf("Reading", "alice", TIME)) {
            assertPaintedTextContrast("mid-tone fallback $word", compose.onNodeWithText(word, substring = word != TIME, useUnmergedTree = true), 4.5)
        }
    }

    @Test
    fun dimmedMessagesPaintReadableSubduedInkAcrossEveryDensityKindSenderAndMetadata() {
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val dimmed = mutableStateOf(false)
        val intensity = mutableStateOf(100)
        compose.setContent {
            ContrastSample(density = density.value, kind = kind.value, dimmed = dimmed.value, showSender = true, showTimestamp = true, wallpaperIntensity = intensity.value)
        }
        for (layout in LayoutDensity.entries) {
            for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)) {
                compose.runOnIdle {
                    density.value = layout
                    kind.value = messageKind
                    dimmed.value = false
                    intensity.value = 100
                }
                val normal = compose.onNodeWithTag("contrast_sample").captureToImage().asAndroidBitmap()
                compose.runOnIdle { dimmed.value = true }
                val sample = compose.onNodeWithTag("contrast_sample")
                val subduedImage = sample.captureToImage()
                val subdued = subduedImage.asAndroidBitmap()
                assertFalse("$layout $messageKind must change its painted palette", normal.sameAs(subdued))
                assertPaintedTextContrast("$layout $messageKind dimmed body", compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true), 4.5)
                val sender = compose.onNodeWithText("alice", substring = messageKind == MessageKind.ACTION || layout == LayoutDensity.COMPACT, useUnmergedTree = true)
                assertPaintedTextContrast("$layout $messageKind dimmed sender", sender, 4.5)
                assertPaintedTextContrast("$layout $messageKind dimmed timestamp", compose.onNodeWithText(TIME, useUnmergedTree = true), 4.5)
                if (layout == LayoutDensity.TWO_LINE) {
                    compose.runOnIdle { intensity.value = 0 }
                    val noWallpaper = sample.captureToImage().toPixelMap()
                    val wallpaper = subduedImage.toPixelMap()
                    val sampleBounds = sample.fetchSemanticsNode().boundsInRoot
                    val row = compose.onNodeWithTag("contrast_message", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    for (x in (row.left - sampleBounds.left + 8).toInt() until (row.right - sampleBounds.left - 8).toInt()) {
                        for (y in (row.top - sampleBounds.top + 2).toInt() until (row.bottom - sampleBounds.top - 2).toInt()) {
                            assertEquals("$layout $messageKind Dimmed must keep its opaque canvas at $x,$y", noWallpaper[x, y], wallpaper[x, y])
                        }
                    }
                }
            }
        }
    }

    @Test
    fun dimmedStandaloneAudioPaintsItsOwnOpaqueReadableSurfaceAndPreservesPlayAction() {
        val dimmed = mutableStateOf(false)
        compose.setContent { ContrastSample(dimmed = dimmed.value, audio = true, showTimestamp = true, wallpaperIntensity = 100) }
        val normal = compose.onNodeWithTag("audio_player", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        compose.runOnIdle { dimmed.value = true }
        val subdued = compose.onNodeWithTag("audio_player", useUnmergedTree = true).captureToImage().asAndroidBitmap()
        assertFalse(normal.sameAs(subdued))
        assertPaintedTextContrast("dimmed standalone audio title", compose.onNodeWithText("Reading audio", useUnmergedTree = true), 4.5)
        assertPaintedTextContrast("dimmed standalone audio timestamp", compose.onNodeWithText(TIME, useUnmergedTree = true), 4.5)
        compose.onNodeWithContentDescription("Download and play audio").assertIsDisplayed().performClick()
    }

    @Composable
    private fun BlurSample(
        density: LayoutDensity,
        kind: MessageKind,
        nicknameBackgrounds: Boolean,
        opacity: Int,
        blur: Int,
        showSender: Boolean = true,
        isSelf: Boolean = false,
        frame: () -> Float = { 0f },
        invertWallpaper: () -> Boolean = { false },
        translation: () -> Offset = { Offset.Zero },
        scrolling: ScrollState? = null,
        mention: Boolean = false,
        morphProgress: (() -> Float)? = null,
        reply: ReplyPreviewData? = null,
        audio: Boolean = false,
        viewportHeight: Int = 260,
    ) {
        MotdTheme(
            dynamicColor = false,
            themePreset = ColorThemePreset.AYU_LIGHT,
            layoutDensity = density,
            avatarStyle = AvatarStyle.MONOGRAM,
            nicknameBackgroundsEnabled = nicknameBackgrounds,
            messageBackgroundOpacityPercent = opacity,
            messageBackgroundBlur = blur,
            chatShadowsEnabled = false,
        ) {
            val backdrop = rememberMessageWallpaperBackdrop(blur)
            Box(
                Modifier
                    .width(380.dp)
                    .height(viewportHeight.dp)
                    .background(Color.Magenta)
                    .testTag("blur_sample"),
            ) {
                Canvas(Modifier.matchParentSize().recordMessageWallpaper(backdrop, Color.Magenta)) {
                    val phase = frame()
                    val inverted = invertWallpaper()
                    for (x in -1..12) {
                        for (y in -1..9) {
                            drawRect(
                                if (((x + y) % 2 == 0) != inverted) Color.White else Color.Black,
                                topLeft = Offset(x * 32f + phase, y * 32f + phase),
                                size = Size(32f, 32f),
                            )
                        }
                    }
                }
                CompositionLocalProvider(LocalMessageWallpaperBackdrop provides backdrop) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .let { if (scrolling == null) it else it.verticalScroll(scrolling) }
                            .graphicsLayer {
                                translationX = translation().x
                                translationY = translation().y
                            },
                    ) {
                        Spacer(Modifier.height(80.dp))
                        MessageBubble(
                            sender = "alice",
                            text = "Reading wallpaper with crisp text",
                            timeMs = 0,
                            formattedTime = TIME,
                            isSelf = isSelf,
                            kind = kind,
                            showSender = showSender,
                            hasMention = mention,
                            sendMorphProgress = morphProgress,
                            reply = reply,
                            modifier = Modifier.fillMaxWidth(),
                            containerModifier = Modifier.testTag("blur_backing"),
                            trailingContent =
                                if (audio) {
                                    {
                                        AudioAttachmentPlayers(
                                            attachments = listOf(AudioAttachment(url = "https://blur-preview.invalid/voice.ogg", title = "Voice message", durationMs = 42_000)),
                                            playbackState = AudioPlaybackState(),
                                            networkId = null,
                                            isSelf = false,
                                            onToggle = { _, _ -> },
                                            onSeek = { _, _ -> },
                                        )
                                    }
                                } else {
                                    null
                                },
                        )
                        Spacer(Modifier.height(400.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun ContrastSample(
        isSelf: Boolean = false,
        kind: MessageKind = MessageKind.PRIVMSG,
        hasMention: Boolean = false,
        density: LayoutDensity = LayoutDensity.COMFORTABLE,
        theme: ColorThemePreset = ColorThemePreset.AYU_LIGHT,
        wallpaperIntensity: Int? = null,
        wallpaperImage: String? = null,
        reply: ReplyPreviewData? = null,
        dimmed: Boolean = false,
        showSender: Boolean = false,
        showTimestamp: Boolean = false,
        audio: Boolean = false,
        background: Color? = null,
        nicknameBackgroundsEnabled: Boolean = true,
        messageBackgroundOpacityPercent: Int = 100,
        canvasColor: Color? = null,
        nickColorsEnabled: Boolean = true,
        senderIsFriend: Boolean = false,
        avatarStyle: AvatarStyle = AvatarStyle.NONE,
    ) {
        MotdTheme(
            dynamicColor = false,
            themePreset = theme,
            layoutDensity = density,
            avatarStyle = avatarStyle,
            timestampConfig = TimestampConfig(show = showTimestamp),
            nicknameBackgroundsEnabled = nicknameBackgroundsEnabled,
            messageBackgroundOpacityPercent = messageBackgroundOpacityPercent,
            nickColorsEnabled = nickColorsEnabled,
        ) {
            val scheme = MaterialTheme.colorScheme
            MaterialTheme(colorScheme = if (background == null) scheme else scheme.copy(background = background)) {
                Box(
                    Modifier
                        .width(380.dp)
                        .height(360.dp)
                        .background(canvasColor ?: MaterialTheme.colorScheme.background)
                        .testTag("contrast_sample"),
                ) {
                    wallpaperIntensity?.let { ChatWallpaperBackground(WallpaperSelection(ChatWallpaperPreset.MOTD, it, wallpaperImage)) }
                    Column(Modifier.padding(top = 96.dp)) {
                        if (audio) {
                            AudioAttachmentPlayers(
                                attachments = listOf(AudioAttachment(url = "https://files.example/audio.ogg", title = "Reading audio", durationMs = 60_000)),
                                playbackState = AudioPlaybackState(),
                                networkId = null,
                                isSelf = false,
                                dimmed = dimmed,
                                formattedTime = TIME,
                                onToggle = { _, _ -> },
                                onSeek = { _, _ -> },
                            )
                        } else {
                            MessageBubble(
                                sender = "alice",
                                text = "Reading https://example.com @bob \u000315grey\u000f `code  `",
                                timeMs = 0,
                                formattedTime = TIME,
                                isSelf = isSelf,
                                kind = kind,
                                showSender = showSender,
                                hasMention = hasMention,
                                dimmed = dimmed,
                                senderIsFriend = senderIsFriend,
                                knownNicks = setOf("bob"),
                                reply = reply,
                                modifier = Modifier.testTag("contrast_message"),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun assertPaintedTextContrast(
        label: String,
        node: SemanticsNodeInteraction,
        minimumTarget: Double = 7.0,
    ) {
        val layout = node.textLayout()
        val sample = compose.onNodeWithTag("contrast_sample")
        val sampleBounds = sample.fetchSemanticsNode().boundsInRoot
        val textBounds = node.fetchSemanticsNode().boundsInRoot
        val pixels = sample.captureToImage().toPixelMap()
        val container = pixels[(textBounds.left - sampleBounds.left - 3).toInt(), (textBounds.top - sampleBounds.top + 3).toInt()]
        for (word in listOf("Reading", "https://", "@bob", "grey", "code", "Quoted", "alice", TIME)) {
            val offset = layout.layoutInput.text.indexOf(word)
            if (offset < 0) continue
            var ink = layout.layoutInput.style.color
            var fill = container
            layout.layoutInput.text.spanStyles.filter { offset in it.start until it.end }.forEach { span ->
                if (span.item.color != Color.Unspecified) ink = span.item.color
                if (span.item.background != Color.Unspecified) {
                    // Sample the painted inline-code backdrop on its trailing space, away from glyphs.
                    val space = layout.getBoundingBox(offset + word.length)
                    fill =
                        pixels[
                            (textBounds.left - sampleBounds.left + space.center.x).toInt(),
                            (textBounds.top - sampleBounds.top + space.center.y).toInt(),
                        ]
                }
            }
            val attainable = maxOf(contrastRatio(Color.Black, fill), contrastRatio(Color.White, fill))
            val minimum = if (attainable >= minimumTarget) minimumTarget else 4.5
            assertTrue("$label $word ink=$ink fill=$fill contrast=${contrastRatio(ink, fill)}", contrastRatio(ink, fill) >= minimum - 0.05)
            if (minimumTarget == 4.5) {
                val glyph = layout.getBoundingBox(offset)
                val left = (textBounds.left - sampleBounds.left + glyph.left).toInt().coerceIn(0, pixels.width - 1)
                val right = (textBounds.left - sampleBounds.left + glyph.right).toInt().coerceIn(left + 1, pixels.width)
                val top = (textBounds.top - sampleBounds.top + glyph.top).toInt().coerceIn(0, pixels.height - 1)
                val bottom = (textBounds.top - sampleBounds.top + glyph.bottom).toInt().coerceIn(top + 1, pixels.height)
                val paintedContrast = (left until right).maxOf { x -> (top until bottom).maxOf { y -> contrastRatio(pixels[x, y], fill) } }
                assertTrue("$label $word actual glyph contrast=$paintedContrast", paintedContrast >= 4.5 - 0.2)
            }
        }
    }

    @Composable
    private fun Sample(
        sender: String = NICK,
        isSelf: Boolean = true,
        showSender: Boolean = true,
        showTime: Boolean = true,
        width: Dp = 380.dp,
        density: LayoutDensity = LayoutDensity.COMFORTABLE,
        kind: MessageKind = MessageKind.PRIVMSG,
        shadows: Boolean = true,
        reactions: List<ReactionChip> = emptyList(),
        onSenderClick: (() -> Unit)? = null,
        senderIsFriend: Boolean = false,
        avatarStyle: AvatarStyle = AvatarStyle.IRC_SPRITE,
        failed: Boolean = false,
        sentBubbleColor: ComfortableBubbleColor = ComfortableBubbleColor(),
        receivedBubbleColor: ComfortableBubbleColor = ComfortableBubbleColor(),
    ) {
        MotdTheme(
            dynamicColor = false,
            themePreset = ColorThemePreset.LIGHT,
            layoutDensity = density,
            avatarStyle = avatarStyle,
            chatShadowsEnabled = shadows,
            sentBubbleColor = sentBubbleColor,
            receivedBubbleColor = receivedBubbleColor,
            timestampConfig = TimestampConfig(show = showTime),
        ) {
            Surface(Modifier.width(width).testTag("sample")) {
                MessageBubble(
                    sender = sender,
                    text = BODY,
                    timeMs = 0,
                    formattedTime = TIME,
                    isSelf = isSelf,
                    kind = kind,
                    showSender = showSender,
                    reactions = reactions,
                    onSenderClick = onSenderClick,
                    senderIsFriend = senderIsFriend,
                    failed = failed,
                )
            }
        }
    }

    private fun TextLayoutResult.inkAt(word: String): Color {
        val offset = layoutInput.text.indexOf(word)
        assertTrue("Missing $word in ${layoutInput.text}", offset >= 0)
        return layoutInput.text.spanStyles
            .filter { offset in it.start until it.end && it.item.color != Color.Unspecified }
            .fold(layoutInput.style.color) { _, span -> span.item.color }
    }

    private fun sent() = compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.chat_sent), useUnmergedTree = true)

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }

    private companion object {
        const val NICK = "trev-before-rename"
        const val LONG_NICK = "trev-before-rename-with-a-very-long-stored-nickname"
        const val BODY = "That sounds good to me."
        const val TIME = "14:32"
    }
}

/** A private imported image exercises the real wallpaper loader/crop in both settings previews. */
internal fun withStripedMessageWallpaper(block: (WallpaperSelection) -> Unit) {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val store = CustomWallpaperStore(context)
    val source = File.createTempFile("message-blur-wallpaper", ".png", context.cacheDir)
    val bitmap = Bitmap.createBitmap(380, 640, Bitmap.Config.ARGB_8888)
    try {
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                bitmap.setPixel(x, y, if ((x / 32 + y / 32) % 2 == 0) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    } finally {
        bitmap.recycle()
    }
    val image = runBlocking { store.import(Uri.fromFile(source)).getOrThrow() }
    try {
        block(WallpaperSelection(ChatWallpaperPreset.NONE, 100, image))
    } finally {
        runBlocking { store.delete(image) }
        source.delete()
    }
}
