package io.github.trevarj.motd.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.ReadAloudSelection
import io.github.trevarj.motd.audio.ReadAloudState
import io.github.trevarj.motd.audio.ReadAloudVoices
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.settings.ChatSettingsContent
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
class PresenceModeSheetUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule val compose = createComposeRule()

    @Test
    fun globalCustomCheckboxUpdatesSelectionAndKeepsSheetOpen() {
        val settings = mutableStateOf(Settings(customPresenceKinds = setOf(MessageKind.JOIN)))
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
                    onPresenceMode = { settings.value = settings.value.copy(presenceMode = it) },
                    onCustomPresenceKind = { kind ->
                        val current = settings.value.customPresenceKinds
                        settings.value = settings.value.copy(customPresenceKinds = if (kind in current) current - kind else current + kind)
                    },
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
                    onSwipeToReplyEnabled = {},
                    onShowImages = {},
                    onShowLinkPreviews = {},
                    onAutoLoadOnUnmetered = {},
                    onAutoLoadOnMetered = {},
                    onShowSharedAvatars = {},
                    onVoiceEncryptionDefault = {},
                    onVoiceQuality = {},
                    onVoiceNoiseReduction = {},
                    onClearAudioCache = {},
                    readerConfig = ReadAloudSelection(),
                    readerState = ReadAloudState(),
                    readerVoices = ReadAloudVoices(),
                    onOpenReadAloudOptions = {},
                    onSaveReadAloudOptions = {},
                    onPreviewReadAloud = {},
                    onStopReadAloudPreview = {},
                )
            }
        }
        compose.onNodeWithTag("settings_presence_picker").performScrollTo().performClick()
        scrollTo("settings_presence_sheet_options", "settings_presence_mode_custom")
        compose.onNodeWithTag("settings_presence_mode_custom").performClick().assertIsSelected()
        scrollTo("settings_presence_sheet_options", "settings_presence_custom_join")
        compose
            .onNodeWithTag("settings_presence_custom_join")
            .assertIsOn()
            .performClick()
            .assertIsOff()
        compose.runOnIdle { assertEquals(emptySet<MessageKind>(), settings.value.customPresenceKinds) }
        compose.onNodeWithTag("settings_presence_sheet").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp", fontScale = 2f)
    fun inheritedCustomRemainsReadOnlyUntilLocalChoiceAndAllCheckboxesAreReachable() {
        val state = mutableStateOf(ConversationPresenceState(global = PresenceMode.CUSTOM, globalCustomKinds = emptySet()))
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                PresenceModeSheet(
                    state = state.value,
                    onSelect = { state.value = state.value.copy(override = it) },
                    onCustomKindSelected = { kind ->
                        val current = state.value.effectiveCustomKinds
                        state.value = state.value.copy(overrideCustomKinds = if (kind in current) current - kind else current + kind)
                    },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithTag("chat_presence_custom_join").assertDoesNotExist()
        scrollTo("chat_presence_sheet_options", "chat_presence_custom")
        compose.onNodeWithTag("chat_presence_custom").performClick().assertIsSelected()
        for (kind in io.github.trevarj.motd.data.visibility.PRESENCE_KINDS) {
            val tag = "chat_presence_custom_${kind.name.lowercase()}"
            scrollTo("chat_presence_sheet_options", tag)
            compose
                .onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertIsOff()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
                .performClick()
                .assertIsOn()
        }
        compose.runOnIdle { assertEquals(io.github.trevarj.motd.data.visibility.PRESENCE_KINDS, state.value.overrideCustomKinds) }
    }

    private fun scrollTo(
        container: String,
        tag: String,
    ) {
        compose.onNodeWithTag(container).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo()
    }
}
