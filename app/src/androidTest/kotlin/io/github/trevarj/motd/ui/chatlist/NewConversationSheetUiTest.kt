package io.github.trevarj.motd.ui.chatlist

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import io.github.trevarj.motd.data.db.ConnectionTransport
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
                    networks =
                        listOf(
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
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        val joinBounds =
            compose
                .onNodeWithTag("new_conversation_content")
                .getUnclippedBoundsInRoot()

        compose.onNodeWithTag("new_conversation_message_tab").performClick()
        compose.waitForIdle()

        val messageBounds =
            compose
                .onNodeWithTag("new_conversation_content")
                .getUnclippedBoundsInRoot()
        assertEquals(joinBounds.bottom - joinBounds.top, messageBounds.bottom - messageBounds.top)
        assertEquals(
            0,
            compose.onAllNodesWithTag("new_conversation_browse").fetchSemanticsNodes().size,
        )
    }

    @Test
    fun submit_joinsTheEnteredChannelWithItsPassword() {
        var joined: Triple<Long, String, String?>? = null
        compose.setContent {
            MotdTheme {
                NewConversationSheetContent(
                    networks = listOf(network()),
                    onJoinChannel = { networkId, channel, key ->
                        joined = Triple(networkId, channel, key)
                    },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("new_conversation_input").performTextInput("motd")
        assertEquals(
            0,
            compose.onAllNodesWithTag("new_conversation_password").fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        compose.onNodeWithTag("new_conversation_password").performTextInput("discarded")
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        assertEquals(
            0,
            compose.onAllNodesWithTag("new_conversation_password").fetchSemanticsNodes().size,
        )
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        compose.onNodeWithTag("new_conversation_password").performTextInput("hunter2")
        compose.onNodeWithTag("new_conversation_submit").performClick()

        compose.runOnIdle { assertEquals(Triple(1L, "#motd", "hunter2"), joined) }
    }

    @Test
    fun sidecarNetworkUsesProviderOwnedTargetPicker() {
        var selected: Long? = null
        compose.setContent {
            MotdTheme {
                NewConversationSheetContent(
                    networks =
                        listOf(
                            network().copy(
                                name = "XMPP",
                                connectionTransport = ConnectionTransport.SIDECAR,
                                sidecarPackage = "provider.example",
                                sidecarService = "provider.example.Service",
                                sidecarAccountId = "account",
                            ),
                        ),
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                    onChooseProviderTarget = { selected = it },
                )
            }
        }

        compose.onNodeWithTag("new_conversation_provider_picker").performClick()
        compose.runOnIdle { assertEquals(1L, selected) }
    }

    private fun network() =
        NetworkEntity(
            id = 1,
            name = "Libera",
            role = NetworkRole.DIRECT,
            host = "irc.libera.chat",
            port = 6697,
            nick = "me",
            username = "me",
            realname = "Me",
        )
}
