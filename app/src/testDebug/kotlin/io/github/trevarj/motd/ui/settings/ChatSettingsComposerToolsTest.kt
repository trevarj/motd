package io.github.trevarj.motd.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.audio.ReadAloudConfig
import io.github.trevarj.motd.audio.ReadAloudPrefs
import io.github.trevarj.motd.audio.ReadAloudSelection
import io.github.trevarj.motd.audio.ReadAloudState
import io.github.trevarj.motd.audio.ReadAloudVoice
import io.github.trevarj.motd.audio.ReadAloudVoices
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.ChatListSwipeAction
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.MentionsPlacement
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.testing.ReadAloudHarness
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    fun composerAndReplySwitchesRenderAndDispatchIndependently() {
        var emoji: Boolean? = null
        var formatting: Boolean? = null
        val reply = mutableStateOf(ReplyConfig())
        compose.setContent {
            MotdTheme(dynamicColor = false) {
                ChatSettingsContent(
                    settings = Settings(showComposerEmoji = false, showComposerFormattingTools = true),
                    reply = reply.value,
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
                    onVisibleReplyPrefix = { reply.value = reply.value.copy(visibleChannelPrefix = it) },
                    onSwipeToReplyEnabled = { reply.value = reply.value.copy(swipeToReplyEnabled = it) },
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
        compose
            .onNodeWithText("Visible reply prefix")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose
            .onNodeWithText("Swipe to reply")
            .performScrollTo()
            .assertIsOn()
            .performClick()

        compose.onNodeWithText("Visible reply prefix").assertIsOn()
        compose.onNodeWithText("Swipe to reply").assertIsOff()

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
        val settings = mutableStateOf(Settings(mentionsEnabled = false))
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

    @Test
    fun installedVoiceSpeedAndPitchSaveAndPreviewFromOrdinarySettingsWithoutChatOptIn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reader = ReadAloudHarness(context, db, scope)
        val original = runBlocking { reader.prefs.systemConfig.first() }
        runBlocking { reader.prefs.replaceSystem(ReadAloudConfig()) }
        reader.synth.voices.value = ReadAloudVoices(listOf(ReadAloudVoice("installed-en-US", "Named offline voice", "en-US")))
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        val shown = mutableStateOf(true)
        compose.setContent {
            val config by reader.controller.config.collectAsState()
            val playback by reader.controller.state.collectAsState()
            val voices by reader.controller.voices.collectAsState()
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MotdTheme(dynamicColor = false) {
                    if (shown.value) {
                        ChatSettingsContent(
                            settings = Settings(),
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
                            readerConfig = config,
                            readerState = playback,
                            readerVoices = voices,
                            onOpenReadAloudOptions = reader.controller::openVoiceOptions,
                            onSaveReadAloudOptions = reader.controller::saveVoiceOptions,
                            onPreviewReadAloud = { reader.controller.preview(null, it) },
                            onStopReadAloudPreview = reader.controller::stopPreview,
                            target = SettingsTarget.READ_ALOUD,
                        )
                    }
                }
            }
        }
        try {
            compose.onNodeWithTag("settings_read_aloud_options").performScrollTo().performClick()
            compose.onNodeWithTag("read_aloud_voice").performClick()
            compose.onNodeWithTag("read_aloud_voice_installed-en-US").performClick()
            compose.onNodeWithTag("read_aloud_rate").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.2f) }
            compose.onNodeWithTag("read_aloud_pitch").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(.8f) }
            compose.onNodeWithTag("read_aloud_gap").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(600f) }
            compose.onNodeWithTag("read_aloud_save").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                reader.controller.config.value.options.voice == "installed-en-US"
            }
            val saved = runBlocking { ReadAloudPrefs(context).systemConfig.first() }
            assertEquals("installed-en-US", saved.voice)
            assertEquals(1.2f, saved.rate, .01f)
            assertEquals(.8f, saved.pitch, .01f)
            assertEquals(600, saved.gapMs)
            assertTrue(!reader.controller.state.value.enabled && reader.synth.utterances.isEmpty())
            compose.onNodeWithTag("settings_read_aloud_options").performScrollTo().performClick()
            compose.onNodeWithTag("read_aloud_preview").performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                reader.controller.state.value.previewing && reader.output.played.isNotEmpty()
            }
            assertEquals(
                saved,
                reader.synth.selections
                    .single()
                    .options,
            )
            assertTrue(!reader.controller.state.value.enabled && reader.controller.state.value.roomId == null)
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                !reader.controller.state.value.previewing && reader.output.played.none { it.exists() }
            }
            compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
            assertTrue(!reader.controller.state.value.previewing && !reader.controller.state.value.enabled)
            compose.onNodeWithTag("read_aloud_preview").performScrollTo().performClick()
            compose.waitUntil(5_000) { reader.controller.state.value.previewing }
            compose.runOnIdle { shown.value = false }
            compose.waitUntil(5_000) {
                compose.waitForIdle()
                !reader.controller.state.value.previewing && reader.output.played.none { it.exists() }
            }
        } finally {
            compose.runOnIdle {
                reader.controller.stop()
                scope.cancel()
            }
            runBlocking { reader.prefs.replaceSystem(original) }
            compose.waitForIdle()
            db.close()
        }
    }
}
