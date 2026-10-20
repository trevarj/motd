package io.github.trevarj.motd.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
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
import io.github.trevarj.motd.data.prefs.CustomWallpaperStore
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.ui.chat.ChatWallpaperBackground
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.TimestampConfig
import io.github.trevarj.motd.ui.theme.contrastRatio
import io.github.trevarj.motd.ui.theme.ensureContrast
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
                replyFill = scheme.surfaceContainerHighest.copy(alpha = 0.6f).compositeOver(fill)
                val linkInk =
                    if (density.value == LayoutDensity.COMFORTABLE && (kind == MessageKind.ACTION || kind == MessageKind.NOTICE || self || mention)) {
                        bubble.content
                    } else {
                        scheme.primary
                    }
                linkColor = ensureContrast(linkInk, listOf(fill), MESSAGE_TEXT_CONTRAST)
                mentionColor = ensureContrast(nicks.nick("bob", Color.Unspecified), listOf(fill), MESSAGE_TEXT_CONTRAST)
                Surface(Modifier.width(380.dp)) {
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
                            // Wallpaper-safe tone adjustments are not a fixed palette RGB contract.
                            val attainable = maxOf(contrastRatio(Color.Black, fill), contrastRatio(Color.White, fill))
                            val minimum = if (attainable >= MESSAGE_TEXT_CONTRAST) MESSAGE_TEXT_CONTRAST else 4.5
                            for (word in listOf("Reading", "https://", "@bob")) {
                                assertTrue("$label $word reading contrast", contrastRatio(body.inkAt(word), fill) >= minimum - 0.05)
                            }
                            assertTrue("$label timestamp reading contrast", contrastRatio(timestamp, fill) >= minimum - 0.05)
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
    fun rowsPreserveInkWhileOnlyNormalTwoLineFillLetsWallpaperThrough() {
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
                        assertPaintedTextContrast("$label wallpaper=100", compose.onNodeWithText("Reading", substring = true, useUnmergedTree = true))
                        compose.runOnIdle { intensity.value = 0 }
                        val noImage = sample.captureToImage()
                        val noInk = noImage.toPixelMap()
                        if (layout == LayoutDensity.TWO_LINE) {
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
    private fun ContrastSample(
        isSelf: Boolean = false,
        kind: MessageKind = MessageKind.PRIVMSG,
        hasMention: Boolean = false,
        density: LayoutDensity = LayoutDensity.COMFORTABLE,
        wallpaperIntensity: Int? = null,
        wallpaperImage: String? = null,
        reply: ReplyPreviewData? = null,
        dimmed: Boolean = false,
        showSender: Boolean = false,
        showTimestamp: Boolean = false,
        audio: Boolean = false,
        background: Color? = null,
    ) {
        MotdTheme(
            dynamicColor = false,
            themePreset = ColorThemePreset.AYU_LIGHT,
            layoutDensity = density,
            avatarStyle = AvatarStyle.NONE,
            timestampConfig = TimestampConfig(show = showTimestamp),
        ) {
            val scheme = MaterialTheme.colorScheme
            MaterialTheme(colorScheme = if (background == null) scheme else scheme.copy(background = background)) {
                Box(
                    Modifier
                        .width(380.dp)
                        .height(360.dp)
                        .background(MaterialTheme.colorScheme.background)
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
