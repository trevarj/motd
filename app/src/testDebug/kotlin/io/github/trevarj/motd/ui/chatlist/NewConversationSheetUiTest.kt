package io.github.trevarj.motd.ui.chatlist

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
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
class NewConversationSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hierarchyUsesHeadingTabsDescriptionsAndSingleNetworkValue() {
        setContent(networks = listOf(network()))

        compose.onNodeWithTag("new_conversation_header").assert(isHeading())
        compose.onNodeWithTag("new_conversation_join_tab").assertIsSelected()
        compose.onNodeWithTag("new_conversation_join_description").assertTextEquals(
            "Enter channel details or browse channels on the selected network.",
        )
        compose.onNodeWithTag("new_conversation_network_value").assertHasNoClickAction()
        compose.onNodeWithTag("new_conversation_network_selector").assertDoesNotExist()
        compose.onNodeWithTag("new_conversation_browse").assertIsEnabled()
    }

    @Test
    fun noEligibleNetworkShowsNoticeAndDisablesFormActions() {
        setContent(networks = listOf(network(role = NetworkRole.BOUNCER_ROOT)))

        compose.onNodeWithTag("new_conversation_no_networks").assertTextEquals(
            "No eligible networks are available. Add a network before starting a conversation.",
        )
        compose.onNodeWithTag("new_conversation_network_value").assertDoesNotExist()
        compose.onNodeWithTag("new_conversation_network_selector").assertDoesNotExist()
        compose.onNodeWithTag("new_conversation_input").assertIsNotEnabled()
        compose.onNodeWithTag("new_conversation_submit").assertIsNotEnabled()
        compose.onNodeWithTag("new_conversation_browse").assertIsNotEnabled()
    }

    @Test
    fun multipleNetworksUseSelectorAndMarkCurrentOptionSelected() {
        setContent(networks = listOf(network(1, "Libera"), network(2, "OFTC")), preselectedNetworkId = 2)

        compose.onNodeWithTag("new_conversation_network_selector").performClick()
        compose.onNodeWithTag("new_conversation_network_option_2").assertIsSelected()
        compose.onNodeWithTag("new_conversation_network_option_1").performClick()

        compose.onNodeWithTag("new_conversation_network_selector").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Libera"),
        )
    }

    @Test
    fun preselectionChangesAndRemovedNetworksFallBackSafely() {
        val networks = mutableStateOf(listOf(network(1, "Libera"), network(2, "OFTC")))
        val preselection = mutableStateOf<Long?>(1)
        compose.setContent {
            MotdTheme {
                NewConversationSheetContent(
                    networks = networks.value,
                    preselectedNetworkId = preselection.value,
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.runOnIdle { preselection.value = 2 }
        compose.onNodeWithTag("new_conversation_network_selector").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "OFTC"),
        )

        compose.runOnIdle { networks.value = listOf(network(1, "Libera")) }
        compose.onNodeWithTag("new_conversation_network_selector").assertDoesNotExist()
        compose.onNodeWithTag("new_conversation_network_value").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Network: Libera")),
        )
    }

    @Test
    fun switchingTabsPreservesIndependentDraftsAndExpandedPassword() {
        setContent(networks = listOf(network()))

        compose.onNodeWithTag("new_conversation_input").performTextInput("motd")
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        compose.onNodeWithTag("new_conversation_password").performTextInput("hunter2")
        compose.onNodeWithTag("new_conversation_message_tab").performClick()
        compose.onNodeWithTag("new_conversation_input").performTextInput("alice")
        compose.onNodeWithTag("new_conversation_join_tab").performClick()

        compose.onNodeWithTag("new_conversation_input").assertInputText("motd")
        compose.onNodeWithTag("new_conversation_password").assertInputText("hunter2")
        compose.onNodeWithTag("new_conversation_password_toggle").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"),
        )
        compose.onNodeWithTag("new_conversation_password_toggle").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
        )

        compose.onNodeWithTag("new_conversation_message_tab").performClick()
        compose.onNodeWithTag("new_conversation_input").assertInputText("alice")
    }

    @Test
    fun collapsingPasswordClearsItBeforeSubmit() {
        var joined: Triple<Long, String, String?>? = null
        setContent(
            networks = listOf(network()),
            onJoinChannel = { networkId, channel, key -> joined = Triple(networkId, channel, key) },
        )

        compose.onNodeWithTag("new_conversation_input").performTextInput("motd")
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        compose.onNodeWithTag("new_conversation_password").performTextInput("discarded")
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        compose.onNodeWithTag("new_conversation_password").assertDoesNotExist()
        compose.onNodeWithTag("new_conversation_submit").performScrollTo().performClick()

        compose.runOnIdle { assertEquals(Triple(1L, "#motd", null), joined) }
    }

    @Test
    fun keyboardDoneSubmitsAndNextFocusesExpandedPassword() {
        var joined: Triple<Long, String, String?>? = null
        setContent(
            networks = listOf(network()),
            onJoinChannel = { networkId, channel, key -> joined = Triple(networkId, channel, key) },
        )

        compose.onNodeWithTag("new_conversation_input").performTextInput("motd")
        compose.onNodeWithTag("new_conversation_input").performImeAction()
        compose.runOnIdle { assertEquals(Triple(1L, "#motd", null), joined) }

        joined = null
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        compose.onNodeWithTag("new_conversation_input").performImeAction()
        compose.onNodeWithTag("new_conversation_password").assertIsFocused().performTextInput("key")
        compose.onNodeWithTag("new_conversation_password").performImeAction()
        compose.runOnIdle { assertEquals(Triple(1L, "#motd", "key"), joined) }
    }

    @Test
    fun messageKeyboardDoneSubmits() {
        var message: Pair<Long, String>? = null
        setContent(
            networks = listOf(network()),
            onMessageUser = { networkId, nick -> message = networkId to nick },
        )

        compose.onNodeWithTag("new_conversation_message_tab").performClick()
        compose.onNodeWithTag("new_conversation_input").performTextInput("alice")
        compose.onNodeWithTag("new_conversation_input").performImeAction()

        compose.runOnIdle { assertEquals(1L to "alice", message) }
    }

    @Test
    fun autocompleteFiltersAndSelectionReplacesWholeNickname() {
        compose.setContent {
            MotdTheme {
                var suggestions by remember { mutableStateOf(NickSuggestions()) }
                NewConversationSheetContent(
                    networks = listOf(network()),
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                    nickSuggestions = suggestions,
                    onNickSuggestionQuery = { networkId, prefix ->
                        suggestions =
                            if (networkId == null) {
                                NickSuggestions()
                            } else {
                                NickSuggestions(networkId, prefix, listOf("Alice", "albert").filter { it.startsWith(prefix, true) })
                            }
                    },
                )
            }
        }

        compose.onNodeWithTag("new_conversation_message_tab").performClick()
        compose.onNodeWithTag("new_conversation_input").performTextInput("al")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("new_conversation_autocomplete_panel").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("new_conversation_autocomplete_item_0").performClick()

        compose.onNodeWithTag("new_conversation_input").assertInputText("Alice")
        compose.onNodeWithTag("new_conversation_autocomplete_panel").assertDoesNotExist()
    }

    @Test
    fun exactKnownNickKeepsLongerMatchesUntilSuggestionIsPicked() {
        compose.setContent {
            MotdTheme {
                var suggestions by remember { mutableStateOf(NickSuggestions()) }
                NewConversationSheetContent(
                    networks = listOf(network()),
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                    nickSuggestions = suggestions,
                    onNickSuggestionQuery = { networkId, prefix ->
                        suggestions =
                            if (networkId == null) {
                                NickSuggestions()
                            } else {
                                NickSuggestions(networkId, prefix, listOf("ann", "anna").filter { it.startsWith(prefix, true) })
                            }
                    },
                )
            }
        }

        compose.onNodeWithTag("new_conversation_message_tab").performClick()
        compose.onNodeWithTag("new_conversation_input").performTextInput("ann")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("new_conversation_autocomplete_panel").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("new_conversation_autocomplete_item_0").performClick()

        compose.onNodeWithTag("new_conversation_input").assertInputText("ann")
        compose.onNodeWithTag("new_conversation_autocomplete_panel").assertDoesNotExist()
    }

    @Test
    fun changingNetworkImmediatelyInvalidatesPriorNetworkSuggestions() {
        setContent(
            networks = listOf(network(1, "Libera"), network(2, "OFTC")),
            nickSuggestions = NickSuggestions(1, "al", listOf("Alice")),
        )

        compose.onNodeWithTag("new_conversation_message_tab").performClick()
        compose.onNodeWithTag("new_conversation_input").performTextInput("al")
        compose.onNodeWithTag("new_conversation_autocomplete_panel").assertExists()
        compose.onNodeWithTag("new_conversation_network_selector").performClick()
        compose.onNodeWithTag("new_conversation_network_option_2").performClick()

        compose.onNodeWithTag("new_conversation_autocomplete_panel").assertDoesNotExist()
    }

    private fun SemanticsNodeInteraction.assertInputText(expected: String) {
        assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(expected)))
    }

    private fun setContent(
        networks: List<NetworkEntity>,
        preselectedNetworkId: Long? = null,
        nickSuggestions: NickSuggestions = NickSuggestions(),
        onJoinChannel: (Long, String, String?) -> Unit = { _, _, _ -> },
        onMessageUser: (Long, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            MotdTheme {
                NewConversationSheetContent(
                    networks = networks,
                    preselectedNetworkId = preselectedNetworkId,
                    onJoinChannel = onJoinChannel,
                    onMessageUser = onMessageUser,
                    nickSuggestions = nickSuggestions,
                )
            }
        }
    }

    private fun network(
        id: Long = 1,
        name: String = "Libera",
        role: NetworkRole = NetworkRole.DIRECT,
    ) = NetworkEntity(
        id = id,
        name = name,
        role = role,
        host = "irc.example.test",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )
}
