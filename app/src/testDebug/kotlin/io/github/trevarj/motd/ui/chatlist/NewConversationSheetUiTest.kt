package io.github.trevarj.motd.ui.chatlist

import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.contrastRatio
import org.junit.Assert.assertEquals
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
class NewConversationSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectedActionPillsAreRoundedAndUseTheNetworkSelectionColors() {
        var fill = Color.Unspecified
        var ink = Color.Unspecified
        compose.setContent {
            MotdTheme(dynamicColor = false, themePreset = ColorThemePreset.ONE_DARK, useThemeTextColors = true, adjustPaletteReadability = true) {
                fill = MaterialTheme.colorScheme.secondaryContainer
                ink = MaterialTheme.colorScheme.onSecondaryContainer
                // Sample the selected fill, not the transient native ripple left by a real click.
                CompositionLocalProvider(LocalRippleConfiguration provides null) {
                    NewConversationSheetContent(
                        networks = listOf(network(1, "Libera"), network(2, "OFTC")),
                        preselectedNetworkId = 2,
                        onJoinChannel = { _, _, _ -> },
                        onMessageUser = { _, _ -> },
                    )
                }
            }
        }
        for (tab in listOf("new_conversation_join_tab", "new_conversation_message_tab", "new_conversation_join_tab")) {
            val row = compose.onNodeWithTag(tab).performClick().assertIsSelected()
            val pixels = row.captureToImage().toPixelMap()
            assertEquals(fill, pixels[pixels.width / 2, pixels.height / 4])
            assertTrue(pixels[2, 2] != fill)
        }
        compose.onNodeWithTag("new_conversation_network_selector").performClick()
        val option = compose.onNodeWithTag("new_conversation_network_option_2").assertIsSelected()
        val pixels = option.captureToImage().toPixelMap()
        assertEquals(fill, pixels[2, pixels.height / 2])
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText("OFTC") and hasAnyAncestor(hasTestTag("new_conversation_network_option_2")), useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(
            ink,
            layouts
                .single()
                .layoutInput.style.color,
        )
        assertTrue(contrastRatio(ink, fill) >= 4.49)
        compose.onNodeWithTag("new_conversation_network_option_1").performClick()
        compose.onNodeWithTag("new_conversation_network_selector").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Libera"),
        )
    }

    @Test
    fun joinAndMessageKeepTheSameSheetFieldAndFooterPositions() {
        setContent(networks = listOf(network()))

        val content = compose.onNodeWithTag("new_conversation_content")
        val input = compose.onNodeWithTag("new_conversation_input")
        val submit = compose.onNodeWithTag("new_conversation_submit")
        val join = compose.onNodeWithTag("new_conversation_join_tab").assertIsSelected()
        val message = compose.onNodeWithTag("new_conversation_message_tab")
        val browse = compose.onNodeWithTag("new_conversation_browse").assertIsEnabled()
        val shell = content.getUnclippedBoundsInRoot()
        val fieldTop = input.getUnclippedBoundsInRoot().top
        val footer = submit.getUnclippedBoundsInRoot()
        listOf(join, message, browse).forEach {
            val bounds = it.getUnclippedBoundsInRoot()
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
        val networkBounds = compose.onNodeWithTag("new_conversation_network_value").getUnclippedBoundsInRoot()
        val browseBounds = browse.getUnclippedBoundsInRoot()
        assertTrue(networkBounds.right < browseBounds.left)
        assertTrue(networkBounds.top < browseBounds.bottom && browseBounds.top < networkBounds.bottom)
        val joinBounds = join.getUnclippedBoundsInRoot()
        val messageBounds = message.getUnclippedBoundsInRoot()
        assertEquals(joinBounds.top, messageBounds.top)
        assertEquals(joinBounds.bottom, messageBounds.bottom)
        assertEquals(footer.left, joinBounds.left)
        assertEquals(footer.right, messageBounds.right)
        assertTrue(kotlin.math.abs((joinBounds.right - joinBounds.left - (messageBounds.right - messageBounds.left)).value) <= 1f)
        assertTrue(browse.getUnclippedBoundsInRoot().top < fieldTop)

        input.performClick().assertIsFocused()
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        assertEquals(shell, content.getUnclippedBoundsInRoot())
        assertEquals(footer, submit.getUnclippedBoundsInRoot())
        message.performClick().assertIsSelected()
        input.assertIsFocused()
        assertEquals(shell, content.getUnclippedBoundsInRoot())
        assertEquals(fieldTop, input.getUnclippedBoundsInRoot().top)
        assertEquals(footer, submit.getUnclippedBoundsInRoot())
        browse.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
    }

    @Test
    @Config(qualifiers = "w320dp-h360dp")
    fun narrowLargeTextKeepsBrowseAndTheFormReachable() {
        var browsedNetwork: Long? = null
        compose.setContent {
            MotdTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.6f)) {
                    NewConversationSheetContent(
                        networks = listOf(network()),
                        onBrowseChannels = { browsedNetwork = it },
                        onJoinChannel = { _, _, _ -> },
                        onMessageUser = { _, _ -> },
                        nickSuggestions = NickSuggestions(1, "al", listOf("Alice", "Albert", "Alfred")),
                    )
                }
            }
        }

        val submit = compose.onNodeWithTag("new_conversation_submit").assertIsDisplayed()
        val footer = submit.getUnclippedBoundsInRoot()
        val browse = compose.onNodeWithTag("new_conversation_browse").performScrollTo()
        val networkBounds = compose.onNodeWithTag("new_conversation_network_value").getUnclippedBoundsInRoot()
        assertTrue(networkBounds.bottom <= browse.getUnclippedBoundsInRoot().top)
        val joinBounds = compose.onNodeWithTag("new_conversation_join_tab").getUnclippedBoundsInRoot()
        val messageBounds = compose.onNodeWithTag("new_conversation_message_tab").getUnclippedBoundsInRoot()
        assertEquals(joinBounds.top, messageBounds.top)
        assertEquals(joinBounds.bottom, messageBounds.bottom)
        browse.assertIsDisplayed().performClick()
        compose.onNodeWithTag("new_conversation_message_tab").performScrollTo().performClick()
        compose.onNodeWithTag("new_conversation_input").performScrollTo().performTextInput("al")
        compose.onNodeWithTag("new_conversation_autocomplete_panel").performScrollTo().assertIsDisplayed()
        val suggestion = compose.onNodeWithTag("new_conversation_autocomplete_item_0").performScrollTo()
        suggestion.assertIsDisplayed().performClick()
        compose.onNodeWithTag("new_conversation_input").assertInputText("Alice")
        assertEquals(footer, submit.getUnclippedBoundsInRoot())
        compose.runOnIdle { assertEquals(1L, browsedNetwork) }
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
    fun browseFromMessageTabUsesTheSelectedNetwork() {
        var browsedNetworkId: Long? = null
        setContent(
            networks = listOf(network(1, "Libera"), network(2, "OFTC")),
            onBrowseChannels = { browsedNetworkId = it },
        )

        compose.onNodeWithTag("new_conversation_network_selector").performClick()
        compose.onNodeWithTag("new_conversation_network_option_2").performClick()
        compose.onNodeWithTag("new_conversation_message_tab").performClick().assertIsSelected()
        compose.onNodeWithTag("new_conversation_browse").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(2L, browsedNetworkId) }
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
    fun stateRestorationKeepsChannelDraftButNotKey() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MotdTheme {
                NewConversationSheetContent(
                    networks = listOf(network()),
                    onJoinChannel = { _, _, _ -> },
                    onMessageUser = { _, _ -> },
                )
            }
        }

        compose.onNodeWithTag("new_conversation_input").performTextInput("motd")
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        compose.onNodeWithTag("new_conversation_password").performTextInput("temporary-key")
        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithTag("new_conversation_input").assertInputText("motd")
        compose.onNodeWithTag("new_conversation_password").assertDoesNotExist()
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        compose.onNodeWithTag("new_conversation_password").assertInputText("")
    }

    @Test
    fun switchingNetworkClearsKeyBeforeJoining() {
        var joined: Triple<Long, String, String?>? = null
        setContent(
            networks = listOf(network(1, "Libera"), network(2, "OFTC")),
            onJoinChannel = { networkId, channel, key -> joined = Triple(networkId, channel, key) },
        )

        compose.onNodeWithTag("new_conversation_input").performTextInput("motd")
        compose.onNodeWithTag("new_conversation_password_toggle").performClick()
        compose.onNodeWithTag("new_conversation_password").performTextInput("libera-test-key")
        compose.onNodeWithTag("new_conversation_network_selector").performClick()
        compose.onNodeWithTag("new_conversation_network_option_2").performClick()

        compose.onNodeWithTag("new_conversation_password").assertDoesNotExist()
        compose.onNodeWithTag("new_conversation_submit").assertIsDisplayed().performClick()

        compose.runOnIdle { assertEquals(Triple(2L, "#motd", null), joined) }
    }

    @Test
    fun collapsingPasswordClearsItBeforeSubmit() {
        var joined: Triple<Long, String, String?>? = null
        setContent(
            networks = listOf(network()),
            onJoinChannel = { networkId, channel, key -> joined = Triple(networkId, channel, key) },
        )

        compose.onNodeWithTag("new_conversation_input").performTextInput("motd")
        val toggle = compose.onNodeWithTag("new_conversation_password_toggle")
        val input = compose.onNodeWithTag("new_conversation_password")
        val submit = compose.onNodeWithTag("new_conversation_submit")
        val footer = submit.getUnclippedBoundsInRoot()
        toggle.assertContentDescriptionEquals("Channel key (optional)")
        toggle.performTouchInput { click() }
        assertEquals(footer, submit.getUnclippedBoundsInRoot())
        input.assertIsDisplayed().performTextInput("discarded")
        toggle.performClick()
        input.assertDoesNotExist()
        toggle.assertContentDescriptionEquals("Channel key (optional)")
        assertEquals(footer, submit.getUnclippedBoundsInRoot())
        submit.assertIsDisplayed().performClick()

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
        onBrowseChannels: (Long) -> Unit = {},
    ) {
        compose.setContent {
            MotdTheme {
                NewConversationSheetContent(
                    networks = networks,
                    preselectedNetworkId = preselectedNetworkId,
                    onJoinChannel = onJoinChannel,
                    onMessageUser = onMessageUser,
                    onBrowseChannels = onBrowseChannels,
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
