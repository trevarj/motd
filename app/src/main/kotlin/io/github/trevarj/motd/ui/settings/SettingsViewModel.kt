package io.github.trevarj.motd.ui.settings

import android.net.Uri
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
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.fonts.CustomFontStore
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BouncerKindPrefs
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.NoopBouncerKindPrefs
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.push.PushDistributorController
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.sidecar.DisabledSidecarPrefs
import io.github.trevarj.motd.sidecar.SidecarPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SettingsUiState(
    val settings: Settings = Settings(),
    val networks: List<NetworkEntity> = emptyList(),
    val zncNetworkIds: Set<Long> = emptySet(),
    val pushAvailability: PushAvailability = PushAvailability(),
    val appearance: AppearanceConfig = AppearanceConfig(),
    val reply: ReplyConfig = ReplyConfig(),
    val contentPreviews: ContentPreviewConfig = ContentPreviewConfig(),
    val voice: VoiceConfig = VoiceConfig(),
    val avatars: AvatarConfig = AvatarConfig(),
    val sidecarsEnabled: Boolean = false,
)

private data class ChatUiPrefs(
    val appearance: AppearanceConfig,
    val reply: ReplyConfig,
    val contentPreviews: ContentPreviewConfig,
    val voice: VoiceConfig,
    val avatars: AvatarConfig,
)

private data class NetworkPrefs(
    val networks: List<NetworkEntity>,
    val zncNetworkIds: Set<Long>,
)

enum class AudioCacheClearEvent { CLEARED, FAILED }

enum class CustomFontImportEvent { IMPORTED, FAILED }

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
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val networkRepository: NetworkRepository,
        private val pushAvailability: PushAvailabilityProvider,
        private val appearancePrefs: AppearancePrefs,
        private val replyPrefs: ReplyPrefs,
        private val contentPreviewPrefs: ContentPreviewPrefs,
        private val voicePrefs: VoicePrefs,
        private val audioCacheStore: AudioCacheStore,
        private val avatarPrefs: AvatarPrefs,
        private val avatarController: AvatarController,
        private val pushDistributorController: PushDistributorController,
        private val customFontStore: CustomFontStore,
        private val bouncerKindPrefs: BouncerKindPrefs = NoopBouncerKindPrefs,
        private val sidecarPrefs: SidecarPrefs = DisabledSidecarPrefs,
    ) : ViewModel() {
        private val _audioCacheClearEvents = MutableSharedFlow<AudioCacheClearEvent>()
        val audioCacheClearEvents: SharedFlow<AudioCacheClearEvent> = _audioCacheClearEvents.asSharedFlow()

        private val _customFontImportEvents = MutableSharedFlow<CustomFontImportEvent>()
        val customFontImportEvents: SharedFlow<CustomFontImportEvent> = _customFontImportEvents.asSharedFlow()

        /** Current on-disk custom font, for the picker's live preview; null until one is imported. */
        val customFontFile: File?
            get() = customFontStore.installedFile()

        /**
         * Bumps on every successful import, including a same-name re-import that leaves
         * [io.github.trevarj.motd.data.prefs.AppearanceConfig.customFontName] unchanged — lets the
         * font-picker preview re-key off something other than the (possibly unchanged) display name.
         */
        val fontRevision: StateFlow<Long> = customFontStore.revision

        private val networkPrefs =
            combine(
                networkRepository.observeNetworks(),
                bouncerKindPrefs.zncNetworkIds,
                ::NetworkPrefs,
            )

        private val appearanceReplyAndPreviews =
            combine(
                appearancePrefs.config,
                replyPrefs.config,
                contentPreviewPrefs.config,
                voicePrefs.config,
                avatarPrefs.config,
                ::ChatUiPrefs,
            )

        val state: StateFlow<SettingsUiState> =
            combine(
                settingsRepository.settings,
                networkPrefs,
                // Reactive: recomputes as connections reach Ready / distributors appear, so the push
                // toggle enables live once the soju bouncer advertises webpush.
                pushAvailability.availability(),
                appearanceReplyAndPreviews,
                sidecarPrefs.enabled,
            ) { settings, networkPrefs, availability, appearanceReplyPreviews, sidecarsEnabled ->
                val (appearance, reply, contentPreviews, voice, avatars) = appearanceReplyPreviews
                SettingsUiState(
                    settings = settings,
                    networks = networkPrefs.networks,
                    zncNetworkIds = networkPrefs.zncNetworkIds,
                    pushAvailability = availability,
                    appearance = appearance,
                    reply = reply,
                    contentPreviews = contentPreviews,
                    voice = voice,
                    avatars = avatars,
                    sidecarsEnabled = sidecarsEnabled,
                )
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = SettingsUiState(),
            )

        fun setThemePreset(theme: ColorThemePreset) =
            viewModelScope.launch {
                appearancePrefs.setTheme(theme)
            }

        fun setTrueBlack(enabled: Boolean) =
            viewModelScope.launch {
                appearancePrefs.setTrueBlack(enabled)
            }

        fun setFollowSystem(enabled: Boolean) =
            viewModelScope.launch {
                appearancePrefs.setFollowSystem(enabled)
            }

        fun setDynamicColor(enabled: Boolean) =
            viewModelScope.launch {
                settingsRepository.setDynamicColor(enabled)
            }

        fun setDeliveryMode(mode: DeliveryMode) =
            viewModelScope.launch {
                settingsRepository.setDeliveryMode(mode)
            }

        fun selectPushDistributor(packageName: String) =
            viewModelScope.launch {
                runCatching { pushDistributorController.select(packageName) }
            }

        fun retryPushSetup() =
            viewModelScope.launch {
                runCatching { pushDistributorController.retry() }
            }

        /** Refresh the app-notification setting after Delivery Settings resumes from Android Settings. */
        fun refreshNotificationPermission() {
            pushAvailability.refreshNotificationPermission()
        }

        // Round 4: appearance/chat/people settings.
        fun setLayoutDensity(density: LayoutDensity) =
            viewModelScope.launch {
                settingsRepository.setLayoutDensity(density)
            }

        fun setNickColorsEnabled(enabled: Boolean) =
            viewModelScope.launch {
                settingsRepository.setNickColorsEnabled(enabled)
            }

        fun setNickColorPalette(palette: NickColorPalette) =
            viewModelScope.launch {
                settingsRepository.setNickColorPalette(palette)
            }

        fun setPresenceMode(mode: PresenceMode) =
            viewModelScope.launch {
                settingsRepository.setPresenceMode(mode)
            }

        fun setShowRedactedMessages(show: Boolean) =
            viewModelScope.launch {
                settingsRepository.setShowRedactedMessages(show)
            }

        fun setAutoAwayEnabled(enabled: Boolean) =
            viewModelScope.launch {
                settingsRepository.setAutoAwayEnabled(enabled)
            }

        fun setAutoAwayMinutes(minutes: Int) =
            viewModelScope.launch {
                settingsRepository.setAutoAwayMinutes(minutes)
            }

        fun setAutoAwayMessage(message: String) =
            viewModelScope.launch {
                settingsRepository.setAutoAwayMessage(message)
            }

        fun setWallpaper(selection: WallpaperSelection) =
            viewModelScope.launch {
                appearancePrefs.setWallpaper(selection)
            }

        fun setUiFontScale(percent: Int) =
            viewModelScope.launch {
                appearancePrefs.setUiFontScale(percent)
            }

        fun setConversationFontScale(percent: Int) =
            viewModelScope.launch {
                appearancePrefs.setConversationFontScale(percent)
            }

        fun setFontChoice(choice: FontChoice) =
            viewModelScope.launch {
                appearancePrefs.setFontChoice(choice)
            }

        fun setShowTimestamps(enabled: Boolean) =
            viewModelScope.launch {
                appearancePrefs.setShowTimestamps(enabled)
            }

        fun setTimeFormat(format: TimeFormat) =
            viewModelScope.launch {
                appearancePrefs.setTimeFormat(format)
            }

        fun setCustomTimeFormatPattern(pattern: String) =
            viewModelScope.launch {
                appearancePrefs.setCustomTimeFormatPattern(pattern)
            }

        fun setMessageSpacing(spacing: io.github.trevarj.motd.data.prefs.MessageSpacing) =
            viewModelScope.launch {
                appearancePrefs.setMessageSpacing(spacing)
            }

        fun setBubbleCornerStyle(style: io.github.trevarj.motd.data.prefs.BubbleCornerStyle) =
            viewModelScope.launch {
                appearancePrefs.setBubbleCornerStyle(style)
            }

        fun setLauncherIcon(icon: io.github.trevarj.motd.data.prefs.LauncherIcon) =
            viewModelScope.launch {
                appearancePrefs.setLauncherIcon(icon)
            }

        /** Import [uri] as the app-wide custom font; on success it also becomes the active choice. */
        fun importCustomFont(uri: Uri) =
            viewModelScope.launch {
                val result = customFontStore.import(uri)
                result.onSuccess { name ->
                    appearancePrefs.setCustomFontName(name)
                    appearancePrefs.setFontChoice(FontChoice.CUSTOM)
                }
                _customFontImportEvents.emit(
                    if (result.isSuccess) CustomFontImportEvent.IMPORTED else CustomFontImportEvent.FAILED,
                )
            }

        fun setFoolsMode(mode: FoolsMode) =
            viewModelScope.launch {
                settingsRepository.setFoolsMode(mode)
            }

        fun setAvatarStyle(style: AvatarStyle) =
            viewModelScope.launch {
                settingsRepository.setAvatarStyle(style)
            }

        fun setShowComposerEmoji(show: Boolean) =
            viewModelScope.launch {
                settingsRepository.setShowComposerEmoji(show)
            }

        fun setShowComposerFormattingTools(show: Boolean) =
            viewModelScope.launch {
                settingsRepository.setShowComposerFormattingTools(show)
            }

        fun setChatSoundsEnabled(enabled: Boolean) =
            viewModelScope.launch {
                settingsRepository.setChatSoundsEnabled(enabled)
            }

        fun setVisibleReplyPrefix(show: Boolean) =
            viewModelScope.launch {
                replyPrefs.setVisibleChannelPrefix(show)
            }

        fun setShowImages(show: Boolean) =
            viewModelScope.launch {
                contentPreviewPrefs.setShowImages(show)
            }

        fun setShowSharedAvatars(show: Boolean) =
            viewModelScope.launch {
                avatarController.setShowSharedAvatars(show)
            }

        fun setShowLinkPreviews(show: Boolean) =
            viewModelScope.launch {
                contentPreviewPrefs.setShowLinkPreviews(show)
            }

        fun setDirectMediaOnProxiedNetworks(enabled: Boolean) =
            viewModelScope.launch {
                contentPreviewPrefs.setDirectMediaOnProxiedNetworks(enabled)
            }

        fun setVoiceEncryptionDefault(enabled: Boolean) =
            viewModelScope.launch {
                voicePrefs.setEncryptionDefault(enabled)
            }

        fun setVoiceQuality(quality: VoiceRecordingQuality) =
            viewModelScope.launch {
                voicePrefs.setQuality(quality)
            }

        fun setVoiceNoiseReduction(enabled: Boolean) =
            viewModelScope.launch {
                voicePrefs.setNoiseReduction(enabled)
            }

        fun clearAudioCache() =
            viewModelScope.launch {
                _audioCacheClearEvents.emit(audioCacheClearEvent(audioCacheStore::clear))
            }
    }
