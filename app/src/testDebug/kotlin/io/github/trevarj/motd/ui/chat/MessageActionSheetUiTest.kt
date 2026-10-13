package io.github.trevarj.motd.ui.chat

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.components.ReactionChip
import io.github.trevarj.motd.ui.theme.MotdTheme
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
class MessageActionSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun tabsAreAbsentWithoutPersistedReactions() {
        render()

        compose.onNodeWithTag("message_action_tabs").assertDoesNotExist()
        compose.onNodeWithTag("message_more_reactions").assertIsDisplayed()
    }

    @Test
    fun reactionsTabRevealsReactorDisplayNames() {
        render(
            reactions =
                listOf(
                    ReactionChip(
                        emoji = "👍",
                        count = 2,
                        mine = false,
                        reactorDisplayNames = listOf("Alice", "bob"),
                    ),
                ),
        )

        compose.onNodeWithTag("message_action_tabs").assertIsDisplayed()
        compose.onNodeWithTag("message_reaction_reactors").assertDoesNotExist()
        compose.onNodeWithTag("message_action_tab_reactions").performClick()

        compose.onNodeWithTag("message_reaction_reactors").assertIsDisplayed()
        compose.onNodeWithText("Alice, bob", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("👍 reacted: Alice, bob").assertIsDisplayed()
    }

    @Test
    fun negotiatedRedactionActionInvokesConfirmationRequest() {
        var requested = false
        render(canRedact = true, onRedact = { requested = true })

        compose.onNodeWithTag("message_redact").performClick()
        compose.runOnIdle { assertTrue(requested) }
    }

    @Test
    fun threadContextActionInvokesPreparation() {
        var requested = false
        render(canPrepareThreadContext = true, onPrepareThreadContext = { requested = true })

        compose.onNodeWithTag("message_prepare_thread_context").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(requested) }
    }

    @Test
    fun threadContextActionStaysHiddenWithoutEligibleContext() {
        render()
        compose.onNodeWithTag("message_prepare_thread_context").assertDoesNotExist()
    }

    @Test
    fun threadContextActionStaysHiddenOnServer() {
        render(canPrepareThreadContext = true, isServerBuffer = true)
        compose.onNodeWithTag("message_prepare_thread_context").assertDoesNotExist()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun render(
        reactions: List<ReactionChip> = emptyList(),
        canRedact: Boolean = false,
        onRedact: () -> Unit = {},
        canPrepareThreadContext: Boolean = false,
        onPrepareThreadContext: () -> Unit = {},
        isServerBuffer: Boolean = false,
    ) {
        compose.setContent {
            MotdTheme {
                MessageActionSheet(
                    sheetState = rememberModalBottomSheetState(),
                    onDismiss = {},
                    onReply = {},
                    onReact = {},
                    reactions = reactions,
                    onCopy = {},
                    onQuote = {},
                    onShare = {},
                    canRedact = canRedact,
                    onRedact = onRedact,
                    canPrepareThreadContext = canPrepareThreadContext,
                    onPrepareThreadContext = onPrepareThreadContext,
                    isServerBuffer = isServerBuffer,
                )
            }
        }
    }
}
