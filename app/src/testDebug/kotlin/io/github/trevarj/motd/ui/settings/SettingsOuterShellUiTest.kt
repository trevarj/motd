package io.github.trevarj.motd.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkContent
import io.github.trevarj.motd.ui.settings.addnetwork.AddNetworkUiState
import io.github.trevarj.motd.ui.settings.bouncer.BouncerControlCallbacks
import io.github.trevarj.motd.ui.settings.bouncer.BouncerNetworksContent
import io.github.trevarj.motd.ui.settings.bouncer.BouncerNetworksUiState
import io.github.trevarj.motd.ui.settings.labs.GESTURE_EDITOR_SCREEN_TAG
import io.github.trevarj.motd.ui.settings.labs.GestureEditorCallbacks
import io.github.trevarj.motd.ui.settings.labs.GestureEditorUiState
import io.github.trevarj.motd.ui.settings.labs.GestureMenuEditorContent
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp")
class SettingsOuterShellUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun manage_nicks_uses_shared_settings_shell() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ManageNicksContent(ManageNicksUiState(kind = NickListKind.FRIENDS), {}, {}, {}, { _, _ -> })
            }
        }

        assertSharedShell()
    }

    @Test
    fun add_network_uses_shared_settings_shell() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                AddNetworkContent(
                    state = AddNetworkUiState(),
                    onBack = {},
                    onSetKind = {},
                    onSetBouncerKind = {},
                    onSelectPreset = {},
                    onDisplayNameChange = {},
                    onServerChange = {},
                    onAuthChange = {},
                    onSojuLoginChange = {},
                    onZncLoginChange = {},
                    onSubmit = {},
                    onRetry = {},
                    onSaveAnyway = {},
                    onEditForm = {},
                    onAbandon = {},
                    onConfirmPlaintext = {},
                    onDismissPlaintext = {},
                )
            }
        }

        assertSharedShell()
    }

    @Test
    fun bouncer_control_uses_shared_settings_shell_without_losing_tabs() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                BouncerNetworksContent(BouncerNetworksUiState(), {}, BouncerControlCallbacks())
            }
        }

        assertSharedShell()
        compose.onNodeWithTag("bouncer_tab_networks").assertIsDisplayed()
    }

    @Test
    fun gesture_editor_uses_shared_settings_shell_without_losing_actions() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                GestureMenuEditorContent(GestureEditorUiState(loaded = true), {}, GestureEditorCallbacks())
            }
        }

        assertSharedShell()
        compose.onNodeWithTag(GESTURE_EDITOR_SCREEN_TAG).assertIsDisplayed()
    }

    private fun assertSharedShell() {
        compose.onNodeWithTag("settings_back").assertIsDisplayed()
        compose.onNodeWithTag("settings_scroll").assertIsDisplayed()
    }
}
