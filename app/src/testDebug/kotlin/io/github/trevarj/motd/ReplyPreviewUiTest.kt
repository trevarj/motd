package io.github.trevarj.motd

import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.ui.components.DeletedMessageExample
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.ReplyMiniBubble
import io.github.trevarj.motd.ui.components.ReplyPrefixExample
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NickColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ReplyPreviewUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun settingsReplyAndDeletedExamplesChangeTheirActualBodiesWithoutMessageActions() {
        val prefix = mutableStateOf(false)
        val deleted = mutableStateOf(true)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                Column {
                    ReplyPrefixExample(prefix.value)
                    DeletedMessageExample(deleted.value)
                }
            }
        }
        compose.onNodeWithText("Alex: Thanks", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Message deleted by Sam", substring = true).assertExists()
        compose.runOnIdle {
            prefix.value = true
            deleted.value = false
        }
        compose.onNodeWithText("Alex: Thanks", substring = true).assertExists()
        compose.onNodeWithText("Message deleted by Sam", substring = true).assertDoesNotExist()
        for (tag in listOf("reply_prefix_example", "deleted_message_example")) {
            val action = hasClickAction() or SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick)
            assertEquals(0, compose.onAllNodes(action and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNodes().size)
        }
    }

    @Test
    fun resolvedReplyPreview_opensItsOriginalMessage() {
        var clicks = 0
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ReplyMiniBubble(
                    reply = ReplyPreviewData(sender = "alice", text = "original"),
                    nickColors =
                        NickColorScheme(
                            enabled = true,
                            palette = NickColorPalette.THEME,
                            overrides = emptyMap(),
                            isDark = false,
                        ),
                    onClick = { clicks++ },
                )
            }
        }

        compose
            .onNodeWithTag("chat_reply_preview")
            .assertHasClickAction()
            .performClick()

        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun replyRailFollowsRenderedContentAcrossMessageLayoutsAndFontScales() {
        data class Sample(
            val layout: LayoutDensity,
            val kind: MessageKind,
            val multiline: Boolean,
            val direction: LayoutDirection,
        )

        fun colorDistance(
            first: Int,
            second: Int,
        ): Int =
            kotlin.math.abs(Color.red(first) - Color.red(second)) +
                kotlin.math.abs(Color.green(first) - Color.green(second)) +
                kotlin.math.abs(Color.blue(first) - Color.blue(second))

        val sample = mutableStateOf(Sample(LayoutDensity.COMFORTABLE, MessageKind.PRIVMSG, false, LayoutDirection.Ltr))
        var clicks = 0
        compose.setContent {
            val current = sample.value
            CompositionLocalProvider(
                LocalDensity provides Density(1f, if (current.multiline) 2f else 1f),
                LocalLayoutDirection provides current.direction,
            ) {
                MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.AYU_LIGHT, layoutDensity = current.layout, chatShadowsEnabled = false) {
                    MessageBubble(
                        sender = "bob",
                        text = "Replying",
                        timeMs = 0,
                        formattedTime = "12:00",
                        isSelf = false,
                        kind = current.kind,
                        showSender = true,
                        dimmed = current.multiline,
                        reply =
                            ReplyPreviewData(
                                sender = "alice",
                                text = "original",
                                ircFormattedText = if (current.multiline) "\u0002First reply line\u0002\nSecond reply line" else "\u0002original\u0002",
                            ),
                        onReplyClick = { clicks++ },
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }

        var expectedClicks = 0
        for (layout in LayoutDensity.entries) {
            for (kind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
                for (multiline in listOf(false, true)) {
                    for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                        val current = Sample(layout, kind, multiline, direction)
                        compose.runOnIdle { sample.value = current }
                        val preview = compose.onNodeWithTag("chat_reply_preview", useUnmergedTree = true)
                        val body = compose.onNodeWithText(if (multiline) "First reply line\nSecond reply line" else "original", useUnmergedTree = true)
                        val results = mutableListOf<TextLayoutResult>()
                        body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                        assertEquals(current.toString(), if (multiline) 2 else 1, results.single().lineCount)

                        val image = preview.captureToImage().asAndroidBitmap()
                        // Stay inside the 6dp clipped corners and outside the text's 6dp inset.
                        val railX = if (direction == LayoutDirection.Ltr) 1 else image.width - 2
                        val backingX = if (direction == LayoutDirection.Ltr) 4 else image.width - 5
                        val railColor = image.getPixel(railX, 8)
                        assertNotEquals(current.toString(), image.getPixel(backingX, 8), railColor)
                        val referenceContrast = colorDistance(railColor, image.getPixel(backingX, 8))
                        for (y in 8 until image.height - 8) {
                            val rowContrast = colorDistance(image.getPixel(railX, y), image.getPixel(backingX, y))
                            assertTrue("$current rail at y=$y: contrast $rowContrast, reference $referenceContrast", rowContrast * 2 >= referenceContrast)
                        }
                        preview.assertHasClickAction().performClick()
                        expectedClicks++
                        compose.runOnIdle { assertEquals(expectedClicks, clicks) }
                    }
                }
            }
        }
    }
}
