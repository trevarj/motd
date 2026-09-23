package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SecuritySettingsScreenTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun typingSwitchesAreIndependentAndUnavailableProviderLinksToUploads() {
        var state by mutableStateOf(
            SecuritySettingsUiState(uploads = PasteBackendConfig().copy(backend = AttachmentBackend.CATBOX)),
        )
        var uploadsOpened = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SecuritySettingsContent(
                    state = state,
                    onOpenDirectConnections = {},
                    onOpenUploads = { uploadsOpened = true },
                    onSendTypingIndicators = { state = state.copy(settings = state.settings.copy(sendTypingIndicators = it)) },
                    onShowTypingIndicators = { state = state.copy(settings = state.settings.copy(showTypingIndicators = it)) },
                    onShowImages = {},
                    onShowLinkPreviews = {},
                    onAutoLoadOnUnmetered = {},
                    onAutoLoadOnMetered = {},
                    onShowSharedAvatars = {},
                    onVoiceEncryptionDefault = {},
                    onUpdateUploads = {},
                )
            }
        }

        compose.onNodeWithTag("settings_switch_send_typing_indicators_row").assertIsDisplayed()
        compose.onNodeWithTag("settings_switch_show_typing_indicators_row").assertIsDisplayed()
        compose.onNodeWithTag("settings_upload_privacy_unavailable").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(uploadsOpened) }
    }

    @Test
    fun eligibleProviderShowsPrivacyControls() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SecuritySettingsContent(
                    state = SecuritySettingsUiState(uploads = PasteBackendConfig().copy(backend = AttachmentBackend.CRAFTERBIN)),
                    onOpenDirectConnections = {},
                    onOpenUploads = {},
                    onSendTypingIndicators = {},
                    onShowTypingIndicators = {},
                    onShowImages = {},
                    onShowLinkPreviews = {},
                    onAutoLoadOnUnmetered = {},
                    onAutoLoadOnMetered = {},
                    onShowSharedAvatars = {},
                    onVoiceEncryptionDefault = {},
                    onUpdateUploads = {},
                )
            }
        }

        compose.onNodeWithTag("settings_upload_secret", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("settings_upload_expiry").assertIsDisplayed()
    }

    @Test
    fun remoteContentSwitchesRenderWithTheirConfiguredStates() {
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                SecuritySettingsContent(
                    state =
                        SecuritySettingsUiState(
                            contentPreviews =
                                ContentPreviewConfig(
                                    showImages = true,
                                    autoLoadOnUnmetered = false,
                                    autoLoadOnMetered = true,
                                    showLinkPreviews = true,
                                ),
                        ),
                    onOpenDirectConnections = {},
                    onOpenUploads = {},
                    onSendTypingIndicators = {},
                    onShowTypingIndicators = {},
                    onShowImages = {},
                    onShowLinkPreviews = {},
                    onAutoLoadOnUnmetered = {},
                    onAutoLoadOnMetered = {},
                    onShowSharedAvatars = {},
                    onVoiceEncryptionDefault = {},
                    onUpdateUploads = {},
                )
            }
        }

        compose.onNodeWithTag("settings_switch_show_images_row").assertIsOn()
        compose.onNodeWithTag("settings_switch_auto_media_unmetered_row").assertIsOff()
        compose.onNodeWithTag("settings_switch_auto_media_metered_row").assertIsOn()
        compose.onNodeWithTag("settings_switch_show_link_previews_row").assertIsOn()
        compose.onNodeWithTag("settings_switch_show_shared_avatars_row").assertIsOn()
        compose.onNodeWithTag("settings_switch_voice_encryption_row").assertIsOff()
    }
}
