package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickMentionStyle
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class NicknameGesturesUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun hitTestedSenderAndBodyTapsAndHoldsStayDistinctFromMessageActionsAcrossLayoutsAndActions() {
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val style = mutableStateOf(NickMentionStyle.BOLD)
        val colors = mutableStateOf(true)
        val taps = mutableListOf<String>()
        val holds = mutableListOf<String>()
        var actions = 0
        compose.setContent {
            MotdTheme(dynamicColor = false, layoutDensity = density.value, nickMentionStyle = style.value, nickColorsEnabled = colors.value, avatarStyle = AvatarStyle.NONE) {
                Surface(Modifier.width(380.dp)) {
                    MessageBubble(
                        sender = "alice",
                        text = "Reading @bob then ordinary",
                        timeMs = 0,
                        isSelf = false,
                        kind = kind.value,
                        showSender = true,
                        knownNicks = setOf("bob"),
                        onSenderClick = { taps += "alice" },
                        onSenderLongClick = { holds += "alice" },
                        onMentionClick = taps::add,
                        onMentionLongClick = holds::add,
                        onLongPress = { actions++ },
                    )
                }
            }
        }
        for (layout in LayoutDensity.entries) {
            for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.NOTICE, MessageKind.ACTION)) {
                for (mentionStyle in NickMentionStyle.entries) {
                    for (colored in listOf(true, false)) {
                        compose.runOnIdle {
                            density.value = layout
                            kind.value = messageKind
                            style.value = mentionStyle
                            colors.value = colored
                            taps.clear()
                            holds.clear()
                            actions = 0
                        }
                        val label = "$layout $messageKind $mentionStyle colors=$colored"
                        val sender = compose.onNodeWithText("alice", substring = true, useUnmergedTree = true)
                        sender.touchWord("alice", hold = false)
                        sender.touchWord("alice", hold = true)
                        val body =
                            if (messageKind == MessageKind.ACTION) {
                                compose.onNodeWithTag("chat_action_text", useUnmergedTree = true)
                            } else {
                                compose.onNodeWithText("Reading @bob then ordinary", substring = true, useUnmergedTree = true)
                            }
                        body.touchWord("@bob", hold = false)
                        body.touchWord("@bob", hold = true)
                        compose.runOnIdle {
                            assertEquals(label, listOf("alice", "bob"), taps)
                            assertEquals(label, listOf("alice", "bob"), holds)
                            assertEquals("$label nickname holds never open message actions", 0, actions)
                        }
                        body.touchWord("ordinary", hold = true)
                        compose.runOnIdle {
                            assertEquals("$label ordinary holds retain message actions", 1, actions)
                            assertEquals(label, listOf("alice", "bob"), taps)
                            assertEquals(label, listOf("alice", "bob"), holds)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun senderAvatarTapAndHoldUseNicknameCallbacksForOrdinaryAndActionMessages() {
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        var taps = 0
        var holds = 0
        var actions = 0
        compose.setContent {
            MotdTheme(dynamicColor = false, layoutDensity = LayoutDensity.COMFORTABLE, avatarStyle = AvatarStyle.MONOGRAM) {
                Surface(Modifier.width(380.dp)) {
                    MessageBubble(
                        sender = "alice",
                        text = "Reading",
                        timeMs = 0,
                        isSelf = false,
                        kind = kind.value,
                        showSender = true,
                        onSenderClick = { taps++ },
                        onSenderLongClick = { holds++ },
                        onLongPress = { actions++ },
                    )
                }
            }
        }
        for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
            compose.runOnIdle {
                kind.value = messageKind
                taps = 0
                holds = 0
                actions = 0
            }
            val avatar = compose.onNodeWithTag("chat_sender_avatar", useUnmergedTree = true)
            avatar.performTouchInput { click() }
            avatar.performTouchInput { longClick() }
            compose.runOnIdle {
                assertEquals(1, taps)
                assertEquals(1, holds)
                assertEquals(0, actions)
            }
        }
    }

    @Test
    // API26 exercises native selection without Robolectric's unsupported magnifier surface.
    @Config(sdk = [26], qualifiers = "w411dp-h891dp")
    fun nativeSelectionSuppressesNicknameGesturesAndConcealedBodyOnlyReveals() {
        val density = mutableStateOf(LayoutDensity.COMFORTABLE)
        val kind = mutableStateOf(MessageKind.PRIVMSG)
        val selecting = mutableStateOf(false)
        val concealed = mutableStateOf(false)
        var taps = 0
        var holds = 0
        var actions = 0
        var reveals = 0
        val dismiss: () -> Unit = {}
        compose.setContent {
            CompositionLocalProvider(
                LocalMessageSelection provides if (selecting.value) dismiss else null,
                LocalMessageActionsEnabled provides !selecting.value,
            ) {
                MotdTheme(dynamicColor = false, layoutDensity = density.value, nickMentionStyle = NickMentionStyle.CHIP, avatarStyle = AvatarStyle.NONE) {
                    Surface(Modifier.width(380.dp).testTag("message")) {
                        FoolMessageScope(concealed.value, {
                            reveals++
                            concealed.value = false
                        }, tag = "reveal") {
                            MessageBubble(
                                sender = "alice",
                                text = "Reading @bob then ordinary",
                                timeMs = 0,
                                isSelf = false,
                                kind = kind.value,
                                showSender = true,
                                knownNicks = setOf("bob"),
                                onSenderClick = { taps++ },
                                onSenderLongClick = { holds++ },
                                onMentionClick = { taps++ },
                                onMentionLongClick = { holds++ },
                                onLongPress = { actions++ },
                            )
                        }
                    }
                }
            }
        }
        for (layout in LayoutDensity.entries) {
            for (messageKind in listOf(MessageKind.PRIVMSG, MessageKind.ACTION)) {
                compose.runOnIdle {
                    density.value = layout
                    kind.value = messageKind
                    selecting.value = true
                    concealed.value = false
                    taps = 0
                    holds = 0
                    actions = 0
                    reveals = 0
                }
                val selected = compose.onNodeWithTag("message_selection_text", useUnmergedTree = true)
                val native = selected.layout()
                assertTrue(
                    native.layoutInput.text.text
                        .contains("Reading @bob then ordinary"),
                )
                assertTrue("selection keeps real glyphs, not chips", native.placeholderRects.isEmpty())
                assertFalse("selection has no interactive links", native.layoutInput.text.hasLinkAnnotations(0, native.layoutInput.text.length))
                selected.touchWord("@bob", hold = false)
                selected.touchWord("@bob", hold = true)
                compose.runOnIdle {
                    assertEquals(0, taps)
                    assertEquals(0, holds)
                    assertEquals(0, actions)
                    selecting.value = false
                }
                val body =
                    if (messageKind == MessageKind.ACTION) {
                        compose.onNodeWithTag("chat_action_text", useUnmergedTree = true)
                    } else {
                        compose.onNodeWithText("Reading @bob then ordinary", substring = true, useUnmergedTree = true)
                    }
                val position = body.wordPosition("@bob")
                val bodyOrigin = body.fetchSemanticsNode().boundsInRoot.topLeft
                val message = compose.onNodeWithTag("message")
                val target = bodyOrigin + position - message.fetchSemanticsNode().boundsInRoot.topLeft
                compose.runOnIdle { concealed.value = true }
                message.performTouchInput { longClick(target) }
                compose.runOnIdle {
                    assertEquals("$layout $messageKind concealed hold reveals only", 1, reveals)
                    assertEquals(0, taps)
                    assertEquals(0, holds)
                    assertEquals(0, actions)
                }
                compose.runOnIdle { concealed.value = true }
                message.performTouchInput { click(target) }
                compose.runOnIdle {
                    assertEquals("$layout $messageKind concealed tap reveals only", 2, reveals)
                    assertEquals(0, taps)
                    assertEquals(0, holds)
                    assertEquals(0, actions)
                }
                // Measure while visible: concealed inline text deliberately removes text semantics.
                val sender = compose.onNodeWithText("alice", substring = true, useUnmergedTree = true)
                val senderTarget =
                    sender.fetchSemanticsNode().boundsInRoot.topLeft + sender.wordPosition("alice") -
                        message.fetchSemanticsNode().boundsInRoot.topLeft
                compose.runOnIdle { concealed.value = true }
                message.performTouchInput { longClick(senderTarget) }
                compose.runOnIdle {
                    assertEquals("$layout $messageKind concealed sender hold still opens user menu", 1, holds)
                    assertEquals(0, taps)
                    assertEquals(0, actions)
                    assertEquals(2, reveals)
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun SemanticsNodeInteraction.wordPosition(word: String): Offset {
        val result = layout()
        val offset =
            result.layoutInput.text.text
                .indexOf(word)
        check(offset >= 0)
        return result.layoutInput.placeholders
            .zip(result.placeholderRects)
            .firstOrNull { (range, rect) -> rect != null && offset in range.start until range.end }
            ?.second
            ?.center
            ?: result.getBoundingBox(offset + 1).center
    }

    private fun SemanticsNodeInteraction.touchWord(
        word: String,
        hold: Boolean,
    ) {
        val position = wordPosition(word)
        performTouchInput { if (hold) longClick(position) else click(position) }
    }
}
