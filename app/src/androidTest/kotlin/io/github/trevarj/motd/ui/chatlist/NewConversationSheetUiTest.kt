package io.github.trevarj.motd.ui.chatlist

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NewConversationSheetUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun switchingTabs_keepsTheSheetContentHeightStable() {
        compose.setContent {
            MotdTheme {
                NewConversationSheetContent(
                    networks = listOf(
                        NetworkEntity(
                            id = 1,
                            name = "Libera",
                            role = NetworkRole.DIRECT,
                            host = "irc.libera.chat",
                            port = 6697,
                            nick = "me",
                            username = "me",
                            realname = "Me",
                        ),
                    ),
                    onJoinChannel = { _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        val joinBounds = compose.onNodeWithTag("new_conversation_content")
            .getUnclippedBoundsInRoot()

        compose.onNodeWithTag("new_conversation_message_tab").performClick()
        compose.waitForIdle()

        val messageBounds = compose.onNodeWithTag("new_conversation_content")
            .getUnclippedBoundsInRoot()
        assertEquals(joinBounds.bottom - joinBounds.top, messageBounds.bottom - messageBounds.top)
        assertEquals(
            0,
            compose.onAllNodesWithTag("new_conversation_browse").fetchSemanticsNodes().size,
        )
    }
}
