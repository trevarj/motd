package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.PushProvider
import io.github.trevarj.motd.data.prefs.PushProviderPrefs
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.repo.NetworkRepository
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: Settings = Settings(),
    val networks: List<NetworkEntity> = emptyList(),
    val pushAvailability: PushAvailability = PushAvailability(),
    val pushProvider: PushProvider = PushProvider.UNIFIED_PUSH,
    val appearance: AppearanceConfig = AppearanceConfig(),
    val reply: ReplyConfig = ReplyConfig(),
    val contentPreviews: ContentPreviewConfig = ContentPreviewConfig(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val networkRepository: NetworkRepository,
    private val pushAvailability: PushAvailabilityProvider,
    private val pushProviderPrefs: PushProviderPrefs,
    private val appearancePrefs: AppearancePrefs,
    private val replyPrefs: ReplyPrefs,
    private val contentPreviewPrefs: ContentPreviewPrefs,
) : ViewModel() {

    private val appearanceReplyAndPreviews = combine(
        appearancePrefs.config,
        replyPrefs.config,
        contentPreviewPrefs.config,
        ::Triple,
    )

    val state: StateFlow<SettingsUiState> =
        combine(
            settingsRepository.settings,
            networkRepository.observeNetworks(),
            // Reactive: recomputes as connections reach Ready / distributors appear, so the push
            // toggle enables live once the soju bouncer advertises webpush.
            pushAvailability.availability(),
            pushProviderPrefs.provider,
            appearanceReplyAndPreviews,
        ) { settings, networks, availability, provider, appearanceReplyPreviews ->
            val (appearance, reply, contentPreviews) = appearanceReplyPreviews
            SettingsUiState(
                settings = settings,
                networks = networks,
                pushAvailability = availability,
                pushProvider = provider,
                appearance = appearance,
                reply = reply,
                contentPreviews = contentPreviews,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    fun setThemePreset(theme: ColorThemePreset) = viewModelScope.launch {
        appearancePrefs.setTheme(theme)
    }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setDynamicColor(enabled)
    }

    fun setDeliveryMode(mode: DeliveryMode) = viewModelScope.launch {
        settingsRepository.setDeliveryMode(mode)
    }

    fun setPushProvider(provider: PushProvider) = viewModelScope.launch {
        pushProviderPrefs.setProvider(provider)
        settingsRepository.setDeliveryMode(DeliveryMode.UNIFIED_PUSH)
    }

    // Round 4 (plans/13): appearance/chat/people settings.
    fun setLayoutDensity(density: LayoutDensity) = viewModelScope.launch {
        settingsRepository.setLayoutDensity(density)
    }

    fun setNickColorsEnabled(enabled: Boolean) = viewModelScope.launch {
        settingsRepository.setNickColorsEnabled(enabled)
    }

    fun setNickColorPalette(palette: NickColorPalette) = viewModelScope.launch {
        settingsRepository.setNickColorPalette(palette)
    }

    fun setShowJoinPartQuit(show: Boolean) = viewModelScope.launch {
        settingsRepository.setShowJoinPartQuit(show)
    }

    fun setWallpaper(selection: WallpaperSelection) = viewModelScope.launch {
        appearancePrefs.setWallpaper(selection)
    }

    fun setUiFontScale(percent: Int) = viewModelScope.launch {
        appearancePrefs.setUiFontScale(percent)
    }

    fun setConversationFontScale(percent: Int) = viewModelScope.launch {
        appearancePrefs.setConversationFontScale(percent)
    }

    fun setFoolsMode(mode: FoolsMode) = viewModelScope.launch {
        settingsRepository.setFoolsMode(mode)
    }

    fun setAvatarStyle(style: AvatarStyle) = viewModelScope.launch {
        settingsRepository.setAvatarStyle(style)
    }

    fun setShowComposerEmoji(show: Boolean) = viewModelScope.launch {
        settingsRepository.setShowComposerEmoji(show)
    }

    fun setVisibleReplyPrefix(show: Boolean) = viewModelScope.launch {
        replyPrefs.setVisibleChannelPrefix(show)
    }

    fun setShowImages(show: Boolean) = viewModelScope.launch {
        contentPreviewPrefs.setShowImages(show)
    }

    fun setShowLinkPreviews(show: Boolean) = viewModelScope.launch {
        contentPreviewPrefs.setShowLinkPreviews(show)
    }
}
