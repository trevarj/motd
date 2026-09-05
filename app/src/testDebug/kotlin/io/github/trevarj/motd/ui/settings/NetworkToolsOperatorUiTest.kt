package io.github.trevarj.motd.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.ui.theme.MotdTheme
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
class NetworkToolsOperatorUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun ircopGroupStartsCollapsedAndKillConfirmPreviewsTheExactCommand() {
        var sent: IrcMessage? = null
        compose.setContent {
            MotdTheme {
                NetworkToolsContent(
                    state = NetworkToolsUiState(networkId = 1, connected = true, selfNick = "me"),
                    onBack = {},
                    onSendCommand = { sent = it },
                )
            }
        }

        // Collapsed by default: no operator field is reachable until the group is opened.
        compose.onAllNodesWithTag("network_tools_kill_nick").assertCountEquals(0)

        compose.onNodeWithTag("network_tools_ircop_expand").performScrollTo().performClick()

        compose.onNodeWithTag("network_tools_kill_nick").performScrollTo().performTextInput("spammer")

        // The preset chips live in a horizontally scrolling Row inside the vertically scrolling
        // page. performScrollTo only scrolls the *closest* scroll parent, so scrolling to a chip
        // moves its Row sideways and never brings the Row itself into the viewport; the click then
        // lands outside the window and the reason stays empty. Scroll the page to a sibling whose
        // closest scroll parent is the page first, then scroll the chip within its own Row.
        compose.onNodeWithTag("network_tools_kill_reason").performScrollTo()
        compose.onNodeWithTag("network_tools_kill_chip_disruptive").performScrollTo().performClick()

        // Assert the precondition rather than inferring it from a dialog three steps later: the
        // send button is gated on this field being non-blank, so a silent miss here surfaced as a
        // missing confirm dialog and said nothing about the cause.
        compose.onNodeWithTag("network_tools_kill_reason").assertTextContains("Being disruptive")

        compose.onNodeWithTag("network_tools_kill_send").performScrollTo().performClick()

        // The confirmation shows the exact line the confirm button will hand to the transport.
        compose
            .onNodeWithTag("network_tools_confirm_preview")
            .assertTextEquals("KILL spammer :Being disruptive")
        compose.onNodeWithTag("network_tools_confirm_accept").performClick()

        compose.runOnIdle {
            assertEquals(listOf("spammer", "Being disruptive"), sent?.params)
            assertEquals("KILL", sent?.command)
        }
    }
}
