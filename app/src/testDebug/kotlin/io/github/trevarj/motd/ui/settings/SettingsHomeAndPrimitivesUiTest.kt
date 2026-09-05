package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
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
class SettingsHomeAndPrimitivesUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun root_groups_search_and_exact_result_callback() {
        var state by mutableStateOf(SettingsHomeUiState(networks = listOf(network(4, "Libera"))))
        var opened: SettingsSearchDestination? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SettingsContent(
                    state = state,
                    onQueryChange = { state = state.copy(query = it) },
                    onBack = {},
                    onOpenAppearance = {},
                    onOpenChat = {},
                    onOpenDelivery = {},
                    onOpenNetworks = {},
                    onOpenUploads = {},
                    onOpenBackupRestore = {},
                    onOpenLabs = {},
                    onOpenAbout = {},
                    onOpenSearchResult = { opened = it },
                )
            }
        }

        compose.onNodeWithText("Connections").assert(isHeading())
        compose.onNodeWithText("Experience").assert(isHeading())
        compose.onNodeWithText("Services").assert(isHeading())
        compose.onNodeWithTag("settings_search_action").performClick()
        compose.onNodeWithTag("settings_search_prompt").assertIsDisplayed()
        compose.onNodeWithTag("settings_category_networks").assertDoesNotExist()
        compose.onNodeWithTag("settings_search_field").performTextReplacement("obfuscation")
        compose.onNodeWithText("Libera · Routing and obfuscation").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings_search_field").assertIsDisplayed()
        compose.onNodeWithText("Libera · Routing and obfuscation").assertIsDisplayed()

        compose.runOnIdle {
            val destination = opened as SettingsSearchDestination.Network
            assertEquals(4L, destination.networkId)
        }
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun wide_root_keeps_same_groups_and_callbacks() {
        var networksOpened = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SettingsContent(
                    state = SettingsHomeUiState(),
                    onQueryChange = {},
                    onBack = {},
                    onOpenAppearance = {},
                    onOpenChat = {},
                    onOpenDelivery = {},
                    onOpenNetworks = { networksOpened = true },
                    onOpenUploads = {},
                    onOpenBackupRestore = {},
                    onOpenLabs = {},
                    onOpenAbout = {},
                    onOpenSearchResult = {},
                )
            }
        }

        compose
            .onNodeWithTag("settings_category_networks")
            .assertIsDisplayed()
            .assert(hasClickAction())
            .performClick()
        compose.runOnIdle { assertTrue(networksOpened) }
    }

    @Test
    fun nick_hue_swatches_expose_localized_label_selection_and_click() {
        var picked: Int? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                NickHuePickerDialog("alice", 210, { picked = it }, {})
            }
        }

        compose
            .onNodeWithTag("nick_hue_210")
            .assertIsSelected()
            .assertContentDescriptionEquals("Hue 210°")
            .assert(hasClickAction())
        compose.onNodeWithTag("nick_hue_240").performClick()
        compose.runOnIdle { assertEquals(240, picked) }
    }

    @Test
    fun error_notice_is_persistent_polite_live_region() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                PersistentStatusNotice(
                    text = "Could not import backup",
                    error = true,
                    modifier = Modifier.testTag("settings_error_notice"),
                )
            }
        }

        compose
            .onNodeWithTag("settings_error_notice")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Could not import backup"))
    }

    @Test
    fun primitives_expose_heading_switch_role_and_target_highlight() {
        var checked by mutableStateOf(true)
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SectionHeader("Conversation")
                SwitchRow("Images", "Show images", checked, { checked = it }, switchTag = "images_switch")
                SettingsTarget(SettingsTarget.PRESENCE.name, SettingsTarget.PRESENCE.name) { modifier ->
                    SettingsActionRow("Presence changes", modifier = modifier, onClick = {})
                }
            }
        }

        compose.onNodeWithText("Conversation").assert(isHeading())
        compose.onNodeWithTag("images_switch_row").assertIsOn().assert(hasClickAction())
        compose.onNodeWithTag("settings_target_highlight_PRESENCE").assertIsDisplayed()
    }

    private fun network(
        id: Long,
        name: String,
    ) = NetworkEntity(
        id = id,
        name = name,
        role = NetworkRole.DIRECT,
        host = "irc.libera.chat",
        port = 6697,
        nick = "me",
        username = "me",
        realname = "Me",
    )
}
