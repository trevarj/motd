package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.audio.VoiceRecordingQuality
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.data.prefs.AUTO_AWAY_MINUTE_CHOICES
import io.github.trevarj.motd.data.prefs.ChatListSwipeAction
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.MentionsPlacement
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.service.autoAwayText
import io.github.trevarj.motd.ui.chat.presenceModeDescription
import io.github.trevarj.motd.ui.chat.presenceModeLabel
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars

/** Chat category: presence-event visibility, friends/fools management, and fools' message handling. */
@Composable
fun ChatSettingsScreen(
    onBack: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onOpenFools: () -> Unit = {},
    onOpenDirectConnections: () -> Unit = {},
    onOpenChatSounds: () -> Unit = {},
    target: SettingsTarget? = null,
    viewModel: ChatSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val audioCacheCleared = stringResource(R.string.settings_audio_cache_cleared)
    val audioCacheClearFailed = stringResource(R.string.settings_audio_cache_clear_failed)
    LaunchedEffect(viewModel, audioCacheCleared, audioCacheClearFailed) {
        viewModel.audioCacheClearEvents.collect { event ->
            val message =
                when (event) {
                    AudioCacheClearEvent.CLEARED -> audioCacheCleared
                    AudioCacheClearEvent.FAILED -> audioCacheClearFailed
                }
            snackbarHostState.showSnackbar(message)
        }
    }
    ChatSettingsContent(
        settings = state.settings,
        reply = state.reply,
        contentPreviews = state.contentPreviews,
        voice = state.voice,
        avatars = state.avatars,
        onBack = onBack,
        onOpenFriends = onOpenFriends,
        onOpenFools = onOpenFools,
        onOpenDirectConnections = onOpenDirectConnections,
        onOpenChatSounds = onOpenChatSounds,
        onPresenceMode = viewModel::setPresenceMode,
        onShowRedactedMessages = viewModel::setShowRedactedMessages,
        onChatListSwipeAction = viewModel::setChatListSwipeAction,
        onMentionsEnabled = viewModel::setMentionsEnabled,
        onMentionsPlacement = viewModel::setMentionsPlacement,
        onAutoAwayEnabled = viewModel::setAutoAwayEnabled,
        onAutoAwayMinutes = viewModel::setAutoAwayMinutes,
        onAutoAwayMessage = viewModel::setAutoAwayMessage,
        onFoolsMode = viewModel::setFoolsMode,
        onShowComposerEmoji = viewModel::setShowComposerEmoji,
        onShowComposerFormattingTools = viewModel::setShowComposerFormattingTools,
        onChatSoundsEnabled = viewModel::setChatSoundsEnabled,
        onVisibleReplyPrefix = viewModel::setVisibleReplyPrefix,
        onShowImages = viewModel::setShowImages,
        onShowLinkPreviews = viewModel::setShowLinkPreviews,
        onAutoLoadOnUnmetered = viewModel::setAutoLoadOnUnmetered,
        onAutoLoadOnMetered = viewModel::setAutoLoadOnMetered,
        onShowSharedAvatars = viewModel::setShowSharedAvatars,
        onVoiceEncryptionDefault = viewModel::setVoiceEncryptionDefault,
        onVoiceQuality = viewModel::setVoiceQuality,
        onVoiceNoiseReduction = viewModel::setVoiceNoiseReduction,
        onClearAudioCache = viewModel::clearAudioCache,
        target = target,
        snackbarHostState = snackbarHostState,
    )
}

@Composable
fun ChatSettingsContent(
    settings: Settings,
    reply: ReplyConfig,
    contentPreviews: ContentPreviewConfig,
    voice: VoiceConfig,
    avatars: AvatarConfig,
    onBack: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenFools: () -> Unit,
    onOpenDirectConnections: () -> Unit,
    onOpenChatSounds: () -> Unit = {},
    onPresenceMode: (PresenceMode) -> Unit,
    onShowRedactedMessages: (Boolean) -> Unit,
    onChatListSwipeAction: (ChatListSwipeAction) -> Unit,
    onMentionsEnabled: (Boolean) -> Unit = {},
    onMentionsPlacement: (MentionsPlacement) -> Unit = {},
    onAutoAwayEnabled: (Boolean) -> Unit,
    onAutoAwayMinutes: (Int) -> Unit,
    onAutoAwayMessage: (String) -> Unit,
    onFoolsMode: (FoolsMode) -> Unit,
    onShowComposerEmoji: (Boolean) -> Unit,
    onShowComposerFormattingTools: (Boolean) -> Unit,
    onChatSoundsEnabled: (Boolean) -> Unit,
    onVisibleReplyPrefix: (Boolean) -> Unit,
    onShowImages: (Boolean) -> Unit,
    onShowLinkPreviews: (Boolean) -> Unit,
    onAutoLoadOnUnmetered: (Boolean) -> Unit,
    onAutoLoadOnMetered: (Boolean) -> Unit,
    onShowSharedAvatars: (Boolean) -> Unit,
    onVoiceEncryptionDefault: (Boolean) -> Unit,
    onVoiceQuality: (VoiceRecordingQuality) -> Unit,
    onVoiceNoiseReduction: (Boolean) -> Unit,
    onClearAudioCache: () -> Unit,
    target: SettingsTarget? = null,
    snackbarHostState: SnackbarHostState? = null,
) {
    var qualitySheetOpen by remember { mutableStateOf(false) }
    var presenceSheetOpen by remember { mutableStateOf(false) }
    var swipeSheetOpen by remember { mutableStateOf(false) }
    var mentionsPlacementSheetOpen by remember { mutableStateOf(false) }
    var awayDelaySheetOpen by remember { mutableStateOf(false) }
    var foolsSheetOpen by remember { mutableStateOf(false) }
    var awayMessageDialogOpen by remember { mutableStateOf(false) }
    val defaultAwayMessage = stringResource(R.string.auto_away_default_message)
    SettingsScaffold(
        title = stringResource(R.string.settings_chat),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) {
        SettingsGroup(title = stringResource(R.string.settings_conversation_section)) {
            SettingsTarget(
                if (target == SettingsTarget.CHAT) SettingsTarget.PRESENCE.name else target?.name,
                SettingsTarget.PRESENCE.name,
            ) { targetModifier ->
                Box(modifier = targetModifier) {
                    SettingsNavigationRow(
                        title = stringResource(R.string.settings_presence_title),
                        value = stringResource(presenceModeLabel(settings.presenceMode)),
                        modifier = Modifier.testTag("settings_presence_picker"),
                        onClick = { presenceSheetOpen = true },
                    )
                }
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_show_redacted_messages),
                subtitle = stringResource(R.string.settings_show_redacted_messages_desc),
                checked = settings.showRedactedMessages,
                onCheckedChange = onShowRedactedMessages,
                switchTag = "settings_switch_show_redacted_messages",
                requestedTarget = target?.name,
                targetName = SettingsTarget.DELETED_MESSAGES.name,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_chat_list_swipe),
                summary = stringResource(R.string.settings_chat_list_swipe_desc),
                value = chatListSwipeActionLabel(settings.chatListSwipeAction),
                modifier = Modifier.testTag("settings_chat_list_swipe_picker"),
                requestedTarget = target?.name,
                targetName = SettingsTarget.CHAT_LIST_SWIPE.name,
                onClick = { swipeSheetOpen = true },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.mentions_title),
                subtitle = stringResource(R.string.settings_mentions_desc),
                checked = settings.mentionsEnabled,
                onCheckedChange = onMentionsEnabled,
                switchTag = "settings_mentions_switch",
                requestedTarget = target?.name,
                targetName = SettingsTarget.MENTIONS.name,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_mentions_location),
                value = mentionsPlacementLabel(settings.mentionsPlacement),
                modifier = Modifier.testTag("settings_mentions_location_picker"),
                enabled = settings.mentionsEnabled,
                requestedTarget = target?.name,
                targetName = SettingsTarget.MENTIONS_LOCATION.name,
                onClick = { mentionsPlacementSheetOpen = true },
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_auto_away_section)) {
            SwitchRow(
                title = stringResource(R.string.settings_auto_away),
                subtitle = stringResource(R.string.settings_auto_away_desc),
                checked = settings.autoAwayEnabled,
                onCheckedChange = onAutoAwayEnabled,
                switchTag = "settings_auto_away_switch",
                requestedTarget = target?.name,
                targetName = SettingsTarget.AUTO_AWAY.name,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_auto_away_delay),
                value = pluralStringResource(R.plurals.settings_auto_away_minutes, settings.autoAwayMinutes, settings.autoAwayMinutes),
                summary =
                    if (settings.autoAwayEnabled) {
                        null
                    } else {
                        stringResource(R.string.settings_auto_away_disabled_explanation)
                    },
                modifier = Modifier.testTag("settings_auto_away_delay"),
                enabled = settings.autoAwayEnabled,
                requestedTarget = target?.name,
                targetName = SettingsTarget.AUTO_AWAY_DELAY.name,
                onClick = { awayDelaySheetOpen = true },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_auto_away_message_title),
                value = autoAwayText(settings.autoAwayMessage, defaultAwayMessage),
                enabled = settings.autoAwayEnabled,
                summary = if (settings.autoAwayEnabled) null else stringResource(R.string.settings_auto_away_disabled_explanation),
                modifier = Modifier.testTag("settings_auto_away_message"),
                requestedTarget = target?.name,
                targetName = SettingsTarget.AWAY_MESSAGE.name,
                onClick = { awayMessageDialogOpen = true },
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_composer_section)) {
            SwitchRow(
                title = stringResource(R.string.settings_chat_sounds),
                subtitle = stringResource(R.string.settings_chat_sounds_desc),
                checked = settings.chatSoundsEnabled,
                onCheckedChange = onChatSoundsEnabled,
                switchTag = "settings_switch_chat_sounds",
                requestedTarget = target?.name,
                targetName = SettingsTarget.CHAT_SOUNDS.name,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_chat_sounds_configure),
                summary = stringResource(R.string.settings_chat_sounds_configure_desc),
                modifier = Modifier.testTag("settings_chat_sounds_configure"),
                onClick = onOpenChatSounds,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_composer_emoji),
                subtitle = stringResource(R.string.settings_composer_emoji_desc),
                checked = settings.showComposerEmoji,
                onCheckedChange = onShowComposerEmoji,
                switchTag = "settings_switch_composer_emoji",
                requestedTarget = target?.name,
                targetName = SettingsTarget.COMPOSER_EMOJI.name,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_composer_formatting_tools),
                subtitle = stringResource(R.string.settings_composer_formatting_tools_desc),
                checked = settings.showComposerFormattingTools,
                onCheckedChange = onShowComposerFormattingTools,
                switchTag = "settings_switch_composer_formatting_tools",
                requestedTarget = target?.name,
                targetName = SettingsTarget.COMPOSER_FORMATTING.name,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_reply_prefix),
                subtitle = stringResource(R.string.settings_reply_prefix_desc),
                checked = reply.visibleChannelPrefix,
                onCheckedChange = onVisibleReplyPrefix,
                switchTag = "settings_switch_reply_prefix",
                requestedTarget = target?.name,
                targetName = SettingsTarget.REPLY_PREFIX.name,
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_voice_audio_section)) {
            SettingsNavigationRow(
                title = stringResource(R.string.settings_voice_quality),
                value = voiceQualityLabel(voice.quality),
                summary = voiceQualityDescription(voice.quality),
                modifier = Modifier.testTag("settings_voice_quality"),
                requestedTarget = target?.name,
                targetName = SettingsTarget.VOICE_QUALITY.name,
                onClick = { qualitySheetOpen = true },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_voice_noise_reduction),
                subtitle = stringResource(R.string.settings_voice_noise_reduction_desc),
                checked = voice.noiseReduction,
                onCheckedChange = onVoiceNoiseReduction,
                switchTag = "settings_switch_voice_noise_reduction",
                requestedTarget = target?.name,
                targetName = SettingsTarget.VOICE_NOISE_REDUCTION.name,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.AUDIO_CACHE.name) { targetModifier ->
                SettingsActionRow(
                    title = stringResource(R.string.settings_clear_audio_cache),
                    summary = stringResource(R.string.settings_clear_audio_cache_desc),
                    modifier = targetModifier.testTag("settings_clear_audio_cache"),
                    onClick = onClearAudioCache,
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_people)) {
            SettingsNavigationRow(
                icon = Icons.Outlined.PersonOutline,
                title = stringResource(R.string.settings_friends),
                value = pluralStringResource(R.plurals.settings_nick_count, settings.friends.size, settings.friends.size),
                modifier = Modifier.testTag("settings_friends"),
                requestedTarget = target?.name,
                targetName = SettingsTarget.FRIENDS.name,
                onClick = onOpenFriends,
            )
            SettingsNavigationRow(
                icon = Icons.Outlined.Block,
                title = stringResource(R.string.settings_fools),
                value = pluralStringResource(R.plurals.settings_nick_count, settings.fools.size, settings.fools.size),
                modifier = Modifier.testTag("settings_fools"),
                requestedTarget = target?.name,
                targetName = SettingsTarget.FOOLS.name,
                onClick = onOpenFools,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsNavigationRow(
                title = stringResource(R.string.settings_fools_mode),
                value = stringResource(if (settings.foolsMode == FoolsMode.COLLAPSE) R.string.settings_fools_collapse else R.string.settings_fools_hide),
                modifier = Modifier.testTag("settings_fools_mode_picker"),
                requestedTarget = target?.name,
                targetName = SettingsTarget.FOOLS_MODE.name,
                onClick = { foolsSheetOpen = true },
            )
        }
    }
    if (awayMessageDialogOpen) {
        AutoAwayMessageDialog(
            initial = settings.autoAwayMessage,
            placeholder = defaultAwayMessage,
            onSave = {
                onAutoAwayMessage(it)
                awayMessageDialogOpen = false
            },
            onDismiss = { awayMessageDialogOpen = false },
        )
    }
    if (presenceSheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_presence_title),
            selected = settings.presenceMode,
            options =
                PresenceMode.entries.map { mode ->
                    ChoiceOption(
                        mode,
                        stringResource(presenceModeLabel(mode)),
                        stringResource(presenceModeDescription(mode)),
                        "settings_presence_mode_${mode.name.lowercase()}",
                    )
                },
            onSelect = onPresenceMode,
            onDismiss = { presenceSheetOpen = false },
            tag = "settings_presence_sheet",
        )
    }
    if (swipeSheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_chat_list_swipe),
            selected = settings.chatListSwipeAction,
            options =
                ChatListSwipeAction.entries.map { action ->
                    ChoiceOption(
                        action,
                        chatListSwipeActionLabel(action),
                        tag = "settings_chat_list_swipe_${action.name.lowercase()}",
                    )
                },
            onSelect = onChatListSwipeAction,
            onDismiss = { swipeSheetOpen = false },
            tag = "settings_chat_list_swipe_sheet",
        )
    }
    if (mentionsPlacementSheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_mentions_location),
            selected = settings.mentionsPlacement,
            options =
                MentionsPlacement.entries.map { placement ->
                    ChoiceOption(
                        placement,
                        mentionsPlacementLabel(placement),
                        tag = "settings_mentions_location_${placement.name.lowercase()}",
                    )
                },
            onSelect = onMentionsPlacement,
            onDismiss = { mentionsPlacementSheetOpen = false },
            tag = "settings_mentions_location_sheet",
        )
    }
    if (awayDelaySheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_auto_away_delay),
            selected = settings.autoAwayMinutes,
            options =
                AUTO_AWAY_MINUTE_CHOICES.map { minutes ->
                    ChoiceOption(
                        minutes,
                        pluralStringResource(R.plurals.settings_auto_away_minutes, minutes, minutes),
                        tag = "settings_auto_away_minutes_$minutes",
                    )
                },
            onSelect = onAutoAwayMinutes,
            onDismiss = { awayDelaySheetOpen = false },
            tag = "settings_auto_away_delay_sheet",
        )
    }
    if (foolsSheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.settings_fools_mode),
            selected = settings.foolsMode,
            options =
                listOf(
                    ChoiceOption(FoolsMode.COLLAPSE, stringResource(R.string.settings_fools_collapse), stringResource(R.string.settings_fools_collapse_desc), "settings_fools_mode_collapse"),
                    ChoiceOption(FoolsMode.HIDE, stringResource(R.string.settings_fools_hide), stringResource(R.string.settings_fools_hide_desc), "settings_fools_mode_hide"),
                ),
            onSelect = onFoolsMode,
            onDismiss = { foolsSheetOpen = false },
            tag = "settings_fools_mode_sheet",
        )
    }
    if (qualitySheetOpen) {
        VoiceQualitySheet(
            selected = voice.quality,
            onSelect = {
                onVoiceQuality(it)
                qualitySheetOpen = false
            },
            onDismiss = { qualitySheetOpen = false },
        )
    }
}

@Composable
private fun chatListSwipeActionLabel(action: ChatListSwipeAction): String =
    stringResource(
        when (action) {
            ChatListSwipeAction.ARCHIVE -> R.string.chatlist_archive
            ChatListSwipeAction.MARK_READ -> R.string.chatlist_mark_read
            ChatListSwipeAction.MUTE -> R.string.settings_chat_list_swipe_mute
            ChatListSwipeAction.PIN -> R.string.settings_chat_list_swipe_pin
            ChatListSwipeAction.DELETE -> R.string.action_delete
            ChatListSwipeAction.NONE -> R.string.settings_chat_list_swipe_none
        },
    )

@Composable
private fun mentionsPlacementLabel(placement: MentionsPlacement): String =
    stringResource(
        when (placement) {
            MentionsPlacement.DRAWER -> R.string.settings_mentions_drawer
            MentionsPlacement.CHAT_LIST -> R.string.settings_mentions_chat_list
            MentionsPlacement.FOLDER_TAB -> R.string.settings_mentions_folder_tab
        },
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VoiceQualitySheet(
    selected: VoiceRecordingQuality,
    onSelect: (VoiceRecordingQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_voice_quality_sheet")) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            SubLabel(stringResource(R.string.settings_voice_quality))
            VoiceRecordingQuality.entries.forEach { quality ->
                RadioRow(
                    label = voiceQualityLabel(quality),
                    subtitle = voiceQualityDescription(quality),
                    selected = selected == quality,
                    enabled = true,
                    onClick = { onSelect(quality) },
                    modifier = Modifier.testTag("settings_voice_quality_${quality.name.lowercase()}"),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun voiceQualityLabel(quality: VoiceRecordingQuality): String =
    stringResource(
        when (quality) {
            VoiceRecordingQuality.DATA_SAVER -> R.string.settings_voice_quality_data_saver
            VoiceRecordingQuality.BALANCED -> R.string.settings_voice_quality_balanced
            VoiceRecordingQuality.HIGH -> R.string.settings_voice_quality_high
        },
    )

@Composable
private fun voiceQualityDescription(quality: VoiceRecordingQuality): String =
    stringResource(
        when (quality) {
            VoiceRecordingQuality.DATA_SAVER -> R.string.settings_voice_quality_data_saver_desc
            VoiceRecordingQuality.BALANCED -> R.string.settings_voice_quality_balanced_desc
            VoiceRecordingQuality.HIGH -> R.string.settings_voice_quality_high_desc
        },
    )

/** Blank keeps the localized default, which the field advertises as its placeholder. */
@Composable
private fun AutoAwayMessageDialog(
    initial: String,
    placeholder: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_auto_away_message_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                supportingText = { Text(stringResource(R.string.settings_auto_away_message_hint)) },
                modifier = Modifier.fillMaxWidth().testTag("settings_auto_away_message_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                modifier = Modifier.testTag("settings_auto_away_message_save"),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        modifier = Modifier.testTag("settings_auto_away_message_dialog"),
    )
}

@Preview
@Composable
private fun ChatSettingsPreview() {
    MotdTheme {
        ChatSettingsContent(
            settings = Settings(friends = setOf("alice"), fools = setOf("bob", "carol")),
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
            onShowImages = {},
            onShowLinkPreviews = {},
            onAutoLoadOnUnmetered = {},
            onAutoLoadOnMetered = {},
            onShowSharedAvatars = {},
            onVoiceEncryptionDefault = {},
            onClearAudioCache = {},
            onVoiceQuality = {},
            onVoiceNoiseReduction = {},
        )
    }
}
