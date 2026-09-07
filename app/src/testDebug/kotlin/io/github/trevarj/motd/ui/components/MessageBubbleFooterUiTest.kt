package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.TimestampConfig
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
    fun comfortableActionKeepsOwnNickInEmoteText() {
        compose.setContent { Sample(kind = MessageKind.ACTION) }

        compose.onNodeWithTag("chat_action_text", useUnmergedTree = true).assertTextEquals("$NICK $BODY").assertIsDisplayed()
        compose.onNodeWithContentDescription("* $NICK $BODY", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("self_sender_label", useUnmergedTree = true).assertDoesNotExist()
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
    ) {
        MotdTheme(
            dynamicColor = false,
            themePreset = ColorThemePreset.LIGHT,
            layoutDensity = density,
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
