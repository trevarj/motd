package io.github.trevarj.motd.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.CustomWallpaperStore
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.chat.ChatWallpaperBackground
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.TimestampConfig
import io.github.trevarj.motd.ui.theme.contrastRatio
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
    fun opaqueRowsProtectTextFromFullIntensityWallpaperWithoutHidingItOutsideRows() {
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
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val intensity = mutableStateOf(100)
        val localImage = mutableStateOf<String?>(null)
        compose.setContent {
            ContrastSample(density = density.value, kind = kind.value, wallpaperIntensity = intensity.value, wallpaperImage = localImage.value)
        }
        try {
            for (name in listOf(null, image)) {
                compose.runOnIdle { localImage.value = name }
                for (layout in listOf(LayoutDensity.COMPACT, LayoutDensity.TWO_LINE)) {
                    for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
                        compose.runOnIdle {
                            density.value = layout
                            kind.value = messageKind
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
                        val fullInk = sample.captureToImage().toPixelMap()
                        assertPaintedTextContrast("$layout $messageKind wallpaper=100", compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true))
                        compose.runOnIdle { intensity.value = 0 }
                        val noInk = sample.captureToImage().toPixelMap()
                        for (x in 8 until fullInk.width - 8) {
                            for (y in top + 2 until bottom - 2) {
                                assertEquals("$layout $messageKind wallpaper leaked under row at $x,$y", noInk[x, y], fullInk[x, y])
                            }
                        }
                        assertTrue(
                            "$layout $messageKind must leave full-intensity wallpaper visible outside the row",
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

    @Composable
    private fun ContrastSample(
        isSelf: Boolean = false,
        kind: MessageKind = MessageKind.PRIVMSG,
        hasMention: Boolean = false,
        density: LayoutDensity = LayoutDensity.COMFORTABLE,
        wallpaperIntensity: Int? = null,
        wallpaperImage: String? = null,
        reply: ReplyPreviewData? = null,
    ) {
        MotdTheme(
            dynamicColor = false,
            themePreset = ColorThemePreset.AYU_LIGHT,
            layoutDensity = density,
            avatarStyle = AvatarStyle.NONE,
            timestampConfig = TimestampConfig(show = false),
        ) {
            Box(
                Modifier
                    .width(380.dp)
                    .height(360.dp)
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("contrast_sample"),
            ) {
                wallpaperIntensity?.let { ChatWallpaperBackground(WallpaperSelection(ChatWallpaperPreset.MOTD, it, wallpaperImage)) }
                Column(Modifier.padding(top = 96.dp)) {
                    MessageBubble(
                        sender = "alice",
                        text = "Reading https://example.com @bob \u000315grey\u000f `code  `",
                        timeMs = 0,
                        formattedTime = TIME,
                        isSelf = isSelf,
                        kind = kind,
                        showSender = false,
                        hasMention = hasMention,
                        knownNicks = setOf("bob"),
                        reply = reply,
                        modifier = Modifier.testTag("contrast_message"),
                    )
                }
            }
        }
    }

    private fun assertPaintedTextContrast(
        label: String,
        node: SemanticsNodeInteraction,
    ) {
        val layout = node.textLayout()
        val sample = compose.onNodeWithTag("contrast_sample")
        val sampleBounds = sample.fetchSemanticsNode().boundsInRoot
        val textBounds = node.fetchSemanticsNode().boundsInRoot
        val pixels = sample.captureToImage().toPixelMap()
        val container = pixels[(textBounds.left - sampleBounds.left - 3).toInt(), (textBounds.top - sampleBounds.top + 3).toInt()]
        for (word in listOf("Reading", "https://", "@bob", "grey", "code", "Quoted")) {
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
            val minimum = if (attainable >= 7.0) 7.0 else 4.5
            assertTrue("$label $word ink=$ink fill=$fill contrast=${contrastRatio(ink, fill)}", contrastRatio(ink, fill) >= minimum - 0.05)
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
    ) {
        MotdTheme(
            dynamicColor = false,
            themePreset = ColorThemePreset.LIGHT,
            layoutDensity = density,
            avatarStyle = avatarStyle,
            chatShadowsEnabled = shadows,
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
                )
            }
        }
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
