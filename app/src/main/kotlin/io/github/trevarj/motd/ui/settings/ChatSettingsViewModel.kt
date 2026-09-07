package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.audio.AudioCacheStore
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.audio.VoiceRecordingQuality
import io.github.trevarj.motd.avatar.AvatarConfig
import io.github.trevarj.motd.avatar.AvatarController
import io.github.trevarj.motd.avatar.AvatarPrefs
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatSettingsUiState(
    val settings: Settings = Settings(),
    val reply: ReplyConfig = ReplyConfig(),
    val contentPreviews: ContentPreviewConfig = ContentPreviewConfig(),
    val voice: VoiceConfig = VoiceConfig(),
    val avatars: AvatarConfig = AvatarConfig(),
)

enum class AudioCacheClearEvent { CLEARED, FAILED }

internal suspend fun audioCacheClearEvent(clear: suspend () -> Unit): AudioCacheClearEvent =
    try {
        clear()
        AudioCacheClearEvent.CLEARED
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        AudioCacheClearEvent.FAILED
    }

@HiltViewModel
class ChatSettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val replyPrefs: ReplyPrefs,
        private val contentPreviewPrefs: ContentPreviewPrefs,
        private val voicePrefs: VoicePrefs,
        private val audioCacheStore: AudioCacheStore,
        private val avatarPrefs: AvatarPrefs,
        private val avatarController: AvatarController,
    ) : ViewModel() {
        private val _audioCacheClearEvents = MutableSharedFlow<AudioCacheClearEvent>()
        val audioCacheClearEvents = _audioCacheClearEvents.asSharedFlow()

        val state =
            combine(
                settingsRepository.settings,
                replyPrefs.config,
                contentPreviewPrefs.config,
                voicePrefs.config,
                avatarPrefs.config,
                ::ChatSettingsUiState,
            ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatSettingsUiState())

        fun setPresenceMode(value: PresenceMode) = launch { settingsRepository.setPresenceMode(value) }

        fun setShowRedactedMessages(value: Boolean) = launch { settingsRepository.setShowRedactedMessages(value) }

        fun setAutoAwayEnabled(value: Boolean) = launch { settingsRepository.setAutoAwayEnabled(value) }

        fun setAutoAwayMinutes(value: Int) = launch { settingsRepository.setAutoAwayMinutes(value) }

        fun setAutoAwayMessage(value: String) = launch { settingsRepository.setAutoAwayMessage(value) }

        fun setFoolsMode(value: FoolsMode) = launch { settingsRepository.setFoolsMode(value) }

        fun setShowComposerEmoji(value: Boolean) = launch { settingsRepository.setShowComposerEmoji(value) }

        fun setShowComposerFormattingTools(value: Boolean) = launch { settingsRepository.setShowComposerFormattingTools(value) }

        fun setChatSoundsEnabled(value: Boolean) = launch { settingsRepository.setChatSoundsEnabled(value) }

        fun setVisibleReplyPrefix(value: Boolean) = launch { replyPrefs.setVisibleChannelPrefix(value) }

        fun setShowImages(value: Boolean) = launch { contentPreviewPrefs.setShowImages(value) }

        fun setShowLinkPreviews(value: Boolean) = launch { contentPreviewPrefs.setShowLinkPreviews(value) }

        fun setAutoLoadOnUnmetered(value: Boolean) = launch { contentPreviewPrefs.setAutoLoadOnUnmetered(value) }

        fun setAutoLoadOnMetered(value: Boolean) = launch { contentPreviewPrefs.setAutoLoadOnMetered(value) }

        fun setShowSharedAvatars(value: Boolean) = launch { avatarController.setShowSharedAvatars(value) }

        fun setVoiceEncryptionDefault(value: Boolean) = launch { voicePrefs.setEncryptionDefault(value) }

        fun setVoiceQuality(value: VoiceRecordingQuality) = launch { voicePrefs.setQuality(value) }

        fun setVoiceNoiseReduction(value: Boolean) = launch { voicePrefs.setNoiseReduction(value) }

        fun clearAudioCache() = launch { _audioCacheClearEvents.emit(audioCacheClearEvent(audioCacheStore::clear)) }

        private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
    }
