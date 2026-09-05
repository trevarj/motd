package io.github.trevarj.motd

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import io.github.trevarj.motd.ui.chatlist.CHAT_LIST_TITLE_CONNECTING_TAG
import io.github.trevarj.motd.ui.chatlist.ChatListDefaultTitle
import io.github.trevarj.motd.ui.chatlist.ChatListTitleConnectingSpinner
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The title-bar connectivity cue: a trailing spinner beside the chat-list title while sockets are
 * being (re-)established. It renders only for a presenter-resolved true, carries its content
 * description for TalkBack, and leaves the title alone when everything is settled.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class ChatListTitleConnectivityUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun connecting_rendersSpinnerWithContentDescription() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListTitleConnectingSpinner(visible = true)
            }
        }

        compose
            .onNode(hasTestTag(CHAT_LIST_TITLE_CONNECTING_TAG), useUnmergedTree = true)
            .assertIsDisplayed()
            .assertContentDescriptionEquals("Connecting")
    }

    @Test
    fun settled_rendersNothing() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatListTitleConnectingSpinner(visible = false)
            }
        }

        assertEquals(
            0,
            compose
                .onAllNodesWithTag(CHAT_LIST_TITLE_CONNECTING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )
    }

    @Test
    fun defaultTitle_showsSpinnerBesideAppNameWithoutReplacingIt() {
        compose.setContent {
            MotdTheme(dynamicColor = false) { ChatListDefaultTitle(titleConnecting = true) }
        }

        val title =
            compose
                .onNodeWithTag("chatlist_title", useUnmergedTree = true)
                .fetchSemanticsNode()
                .config[SemanticsProperties.Text]
                .single()
                .text
        assertTrue(title.startsWith("motd"))
        compose.onNode(hasTestTag(CHAT_LIST_TITLE_CONNECTING_TAG), useUnmergedTree = true).assertIsDisplayed()
    }
}
