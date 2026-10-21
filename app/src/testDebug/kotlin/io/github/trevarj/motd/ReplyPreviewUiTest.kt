package io.github.trevarj.motd

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.ui.components.DeletedMessageExample
import io.github.trevarj.motd.ui.components.ReplyMiniBubble
import io.github.trevarj.motd.ui.components.ReplyPrefixExample
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.NickColorScheme
import org.junit.Assert.assertEquals
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
}
