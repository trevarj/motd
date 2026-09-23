package io.github.trevarj.motd.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.data.prefs.ChatListSwipeAction
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.MentionsPlacement
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSettingsContent(
                    settings = Settings(showComposerEmoji = false, showComposerFormattingTools = true),
                    reply = ReplyConfig(),
                    contentPreviews = ContentPreviewConfig(),
                    voice = VoiceConfig(),
                    avatars = AvatarConfig(),
                    onBack = {},
                    onOpenFriends = {},
                    onOpenFools = {},
                    onOpenDirectConnections = {},
                    onPresenceMode = {},
                    onShowRedactedMessages = {},
                    onChatListSwipeAction = {},
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
                    onAutoLoadOnUnmetered = {},
                    onAutoLoadOnMetered = {},
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
        compose.onNodeWithTag("settings_switch_direct_media_proxied", useUnmergedTree = true).assertDoesNotExist()
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
        }
    }

    @Test
    fun chatSoundSettingsNavigationIsAvailableWithMasterOff() {
        var opened = false
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSettingsContent(
                    settings = Settings(chatSoundsEnabled = false),
                    reply = ReplyConfig(),
                    contentPreviews = ContentPreviewConfig(),
                    voice = VoiceConfig(),
                    avatars = AvatarConfig(),
                    onBack = {},
                    onOpenFriends = {},
                    onOpenFools = {},
                    onOpenDirectConnections = {},
                    onOpenChatSounds = { opened = true },
                    onPresenceMode = {},
                    onShowRedactedMessages = {},
                    onChatListSwipeAction = {},
                    onAutoAwayEnabled = {},
                    onAutoAwayMinutes = {},
                    onAutoAwayMessage = {},
                    onFoolsMode = {},
                    onShowComposerEmoji = {},
                    onShowComposerFormattingTools = {},
                    onChatSoundsEnabled = {},
                    onVisibleReplyPrefix = {},
                    onShowImages = {},
                    onShowLinkPreviews = {},
                    onAutoLoadOnUnmetered = {},
                    onAutoLoadOnMetered = {},
                    onShowSharedAvatars = {},
                    onVoiceEncryptionDefault = {},
                    onVoiceQuality = {},
                    onVoiceNoiseReduction = {},
                    onClearAudioCache = {},
                )
            }
        }
        compose.onNodeWithTag("settings_chat_sounds_configure").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun swipePickerRendersAndDispatchesEveryAction() {
        val settings = mutableStateOf(Settings())
        val selections = mutableListOf<ChatListSwipeAction>()
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSettingsContent(
                    settings = settings.value,
                    reply = ReplyConfig(),
                    contentPreviews = ContentPreviewConfig(),
                    voice = VoiceConfig(),
                    avatars = AvatarConfig(),
                    onBack = {},
                    onOpenFriends = {},
                    onOpenFools = {},
                    onOpenDirectConnections = {},
                    onPresenceMode = {},
                    onShowRedactedMessages = {},
                    onChatListSwipeAction = {
                        selections += it
                        settings.value = settings.value.copy(chatListSwipeAction = it)
                    },
                    onAutoAwayEnabled = {},
                    onAutoAwayMinutes = {},
                    onAutoAwayMessage = {},
                    onFoolsMode = {},
                    onShowComposerEmoji = {},
                    onShowComposerFormattingTools = {},
                    onChatSoundsEnabled = {},
                    onVisibleReplyPrefix = {},
                    onShowImages = {},
                    onShowLinkPreviews = {},
                    onAutoLoadOnUnmetered = {},
                    onAutoLoadOnMetered = {},
                    onShowSharedAvatars = {},
                    onVoiceEncryptionDefault = {},
                    onVoiceQuality = {},
                    onVoiceNoiseReduction = {},
                    onClearAudioCache = {},
                    target = SettingsTarget.CHAT_LIST_SWIPE,
                )
            }
        }

        var selected = ChatListSwipeAction.ARCHIVE
        ChatListSwipeAction.entries.forEach { action ->
            compose.onNodeWithTag("settings_chat_list_swipe_picker").performScrollTo().performClick()
            compose.onNodeWithTag("settings_chat_list_swipe_sheet_options").performScrollToIndex(selected.ordinal + 1)
            compose.onNodeWithTag("settings_chat_list_swipe_${selected.name.lowercase()}").assertIsSelected()
            compose.onNodeWithTag("settings_chat_list_swipe_sheet_options").performScrollToIndex(action.ordinal + 1)
            compose.onNodeWithTag("settings_chat_list_swipe_${action.name.lowercase()}").performClick()
            compose.onNodeWithTag("settings_chat_list_swipe_sheet").assertDoesNotExist()
            selected = action
        }
        compose.runOnIdle { assertEquals(ChatListSwipeAction.entries.toList(), selections) }
    }

    @Test
    fun mentionsStartsOffAndPlacementIsChosenOnlyWhenEnabled() {
        val settings = mutableStateOf(Settings())
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSettingsContent(
                    settings = settings.value,
                    reply = ReplyConfig(),
                    contentPreviews = ContentPreviewConfig(),
                    voice = VoiceConfig(),
                    avatars = AvatarConfig(),
                    onBack = {},
                    onOpenFriends = {},
                    onOpenFools = {},
                    onOpenDirectConnections = {},
                    onPresenceMode = {},
                    onShowRedactedMessages = {},
                    onChatListSwipeAction = {},
                    onMentionsEnabled = { settings.value = settings.value.copy(mentionsEnabled = it) },
                    onMentionsPlacement = { settings.value = settings.value.copy(mentionsPlacement = it) },
                    onAutoAwayEnabled = {},
                    onAutoAwayMinutes = {},
                    onAutoAwayMessage = {},
                    onFoolsMode = {},
                    onShowComposerEmoji = {},
                    onShowComposerFormattingTools = {},
                    onChatSoundsEnabled = {},
                    onVisibleReplyPrefix = {},
                    onShowImages = {},
                    onShowLinkPreviews = {},
                    onAutoLoadOnUnmetered = {},
                    onAutoLoadOnMetered = {},
                    onShowSharedAvatars = {},
                    onVoiceEncryptionDefault = {},
                    onVoiceQuality = {},
                    onVoiceNoiseReduction = {},
                    onClearAudioCache = {},
                )
            }
        }

        compose
            .onNodeWithTag("settings_mentions_switch_row")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.onNodeWithTag("settings_mentions_location_picker").performScrollTo().performClick()
        compose.onNodeWithTag("settings_mentions_location_folder_tab").performClick()
        compose.runOnIdle {
            assertEquals(true, settings.value.mentionsEnabled)
            assertEquals(MentionsPlacement.FOLDER_TAB, settings.value.mentionsPlacement)
        }

        compose
            .onNodeWithTag("settings_mentions_switch_row")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        compose.onNodeWithTag("settings_mentions_location_picker").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(MentionsPlacement.FOLDER_TAB, settings.value.mentionsPlacement) }
    }
}
