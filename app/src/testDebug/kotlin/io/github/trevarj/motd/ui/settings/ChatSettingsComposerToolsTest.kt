package io.github.trevarj.motd.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatSettingsComposerToolsTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun composerToolSwitchesRenderAndDispatchIndependently() {
        var emoji: Boolean? = null
        var formatting: Boolean? = null
        var unmetered: Boolean? = null
        var metered: Boolean? = null
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSettingsContent(
                    settings = Settings(showComposerEmoji = false, showComposerFormattingTools = true),
                    reply = ReplyConfig(),
                    contentPreviews = ContentPreviewConfig(autoLoadOnUnmetered = false, autoLoadOnMetered = true),
                    voice = VoiceConfig(),
                    avatars = AvatarConfig(),
                    onBack = {},
                    onOpenFriends = {},
                    onOpenFools = {},
                    onOpenDirectConnections = {},
                    onPresenceMode = {},
                    onShowRedactedMessages = {},
                    onAutoAwayEnabled = {},
                    onAutoAwayMinutes = {},
                    onAutoAwayMessage = {},
                    onFoolsMode = {},
                    onShowComposerEmoji = { emoji = it },
                    onShowComposerFormattingTools = { formatting = it },
                    onChatSoundsEnabled = {},
                    onVisibleReplyPrefix = {},
                    onShowImages = {},
                    onShowLinkPreviews = {},
                    onAutoLoadOnUnmetered = { unmetered = it },
                    onAutoLoadOnMetered = { metered = it },
                    onDirectMediaOnProxiedNetworks = {},
                    onShowSharedAvatars = {},
                    onVoiceEncryptionDefault = {},
                    onVoiceQuality = {},
                    onVoiceNoiseReduction = {},
                    onClearAudioCache = {},
                    target = SettingsTarget.PRESENCE,
                )
            }
        }

        compose.onNodeWithTag("settings_target_highlight_PRESENCE", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("settings_presence_picker", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithText("Automatically load remote media on unmetered networks")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.onNodeWithTag("settings_switch_auto_media_unmetered", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithText("Automatically load remote media on metered networks")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        compose.onNodeWithTag("settings_switch_auto_media_metered", useUnmergedTree = true).assertIsDisplayed()
        compose
            .onNodeWithText("Emoji tool")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose
            .onNodeWithText("Formatting tools")
            .performScrollTo()
            .assertIsOn()
            .performClick()

        compose.runOnIdle {
            assertEquals(true, emoji)
            assertEquals(false, formatting)
            assertEquals(true, unmetered)
            assertEquals(false, metered)
        }
    }
}
