package io.github.trevarj.motd.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.emoji.EmojiSetStore
import io.github.trevarj.motd.data.emoji.EmojiSets
import io.github.trevarj.motd.data.fonts.CustomFontStore
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.AvatarShape
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColor
import io.github.trevarj.motd.data.prefs.CompactNickBrackets
import io.github.trevarj.motd.data.prefs.ComposerStyle
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.LauncherIcon
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.NickMentionStyle
import io.github.trevarj.motd.data.prefs.PaletteSelection
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.TypingIndicatorPlacement
import io.github.trevarj.motd.data.prefs.UiCornerStyle
import io.github.trevarj.motd.data.prefs.UiIconSet
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.prefs.WallpaperWorkshopStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

data class AppearanceSettingsUiState(
    val settings: Settings = Settings(),
    val appearance: AppearanceConfig = AppearanceConfig(),
)

data class EmojiSettingsState(
    val installed: Map<String, File> = emptyMap(),
    val loading: Boolean = true,
    val downloadingId: String? = null,
    val error: Boolean = false,
)

enum class CustomFontImportEvent { IMPORTED, FAILED }

@HiltViewModel
class AppearanceSettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val appearancePrefs: AppearancePrefs,
        private val customFontStore: CustomFontStore,
        private val wallpaperWorkshopStore: WallpaperWorkshopStore,
        private val emojiSetStore: EmojiSetStore,
    ) : ViewModel() {
        private val _customFontImportEvents = MutableSharedFlow<CustomFontImportEvent>()
        val customFontImportEvents = _customFontImportEvents.asSharedFlow()
        val fontRevision: StateFlow<Long> = customFontStore.revision
        val customFontFile: File? get() = customFontStore.installedFile()
        private val _wallpaperImporting = MutableStateFlow(false)
        val wallpaperImporting: StateFlow<Boolean> = _wallpaperImporting
        private val _importedWallpaper = MutableSharedFlow<WallpaperSelection>()
        val importedWallpaper = _importedWallpaper.asSharedFlow()
        private val _wallpaperFailures = MutableSharedFlow<Unit>()
        val wallpaperFailures = _wallpaperFailures.asSharedFlow()
        private val _emojiState = MutableStateFlow(EmojiSettingsState())
        val emojiState: StateFlow<EmojiSettingsState> = _emojiState
        val emojiProgress = emojiSetStore.progress
        private var emojiIntent = 0L
        private var emojiJob: Job? = null
        private val emojiSelectionMutex = Mutex()

        init {
            viewModelScope.launch {
                emojiSetStore.revision.collectLatest {
                    val installed =
                        buildMap {
                            for (spec in EmojiSets.all) {
                                emojiSetStore.installedFile(spec.id)?.let { put(spec.id, it) }
                            }
                        }
                    _emojiState.update { it.copy(installed = installed, loading = false) }
                }
            }
        }

        fun cancelEmojiDownload() {
            emojiIntent++
            emojiJob?.cancel()
            emojiJob = null
            _emojiState.update { it.copy(downloadingId = null, error = false) }
        }

        fun selectEmojiSet(id: String) {
            require(id == EmojiSets.SYSTEM_ID || EmojiSets.find(id) != null)
            cancelEmojiDownload()
            val intent = emojiIntent
            emojiJob =
                viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val available = id == EmojiSets.SYSTEM_ID || emojiSetStore.installedFile(id) != null
                        if (!available) {
                            if (intent == emojiIntent) _emojiState.update { it.copy(error = true) }
                            return@launch
                        }
                        emojiSelectionMutex.withLock {
                            if (intent == emojiIntent) appearancePrefs.setEmojiSetId(id)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (intent == emojiIntent) _emojiState.update { it.copy(error = true) }
                    }
                }
        }

        fun downloadEmojiSet(id: String) {
            require(EmojiSets.find(id) != null)
            cancelEmojiDownload()
            val intent = emojiIntent
            _emojiState.update { it.copy(downloadingId = id) }
            emojiJob =
                viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        val file = emojiSetStore.download(id).getOrThrow()
                        emojiSelectionMutex.withLock {
                            if (intent == emojiIntent) {
                                appearancePrefs.setEmojiSetId(id)
                                _emojiState.update { it.copy(installed = it.installed + (id to file)) }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (intent == emojiIntent) _emojiState.update { it.copy(error = true) }
                    } finally {
                        if (intent == emojiIntent) _emojiState.update { it.copy(downloadingId = null) }
                    }
                }
        }

        fun removeEmojiSet(id: String) {
            require(EmojiSets.find(id) != null)
            cancelEmojiDownload()
            val intent = emojiIntent
            emojiJob =
                viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        emojiSetStore.remove(id)
                        if (intent == emojiIntent) {
                            _emojiState.update { it.copy(installed = it.installed - id) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        if (intent == emojiIntent) _emojiState.update { it.copy(error = true) }
                    }
                }
        }

        val state =
            combine(settingsRepository.settings, appearancePrefs.config, ::AppearanceSettingsUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearanceSettingsUiState())

        fun selectPalette(value: PaletteSelection) = launch { appearancePrefs.selectPalette(value) }

        fun setTrueBlack(value: Boolean) = launch { appearancePrefs.setTrueBlack(value) }

        fun setUseThemeTextColors(value: Boolean) = launch { appearancePrefs.setUseThemeTextColors(value) }

        fun setAdjustPaletteReadability(value: Boolean) = launch { appearancePrefs.setAdjustPaletteReadability(value) }

        fun setSurfaceEffectsEnabled(value: Boolean) = launch { appearancePrefs.setSurfaceEffectsEnabled(value) }

        fun setLinkGlobalOpacity(value: Boolean) = launch { appearancePrefs.setLinkGlobalOpacity(value) }

        fun setLinkGlobalBlur(value: Boolean) = launch { appearancePrefs.setLinkGlobalBlur(value) }

        fun setSurfaceOpacity(
            surface: io.github.trevarj.motd.data.prefs.EffectSurface,
            value: Int,
        ) = launch { appearancePrefs.setSurfaceOpacity(surface, value) }

        fun setSurfaceBlur(
            surface: io.github.trevarj.motd.data.prefs.EffectSurface,
            value: Int,
        ) = launch { appearancePrefs.setSurfaceBlur(surface, value) }

        fun setDynamicColor(value: Boolean) = launch { settingsRepository.setDynamicColor(value) }

        fun setLayoutDensity(value: LayoutDensity) = launch { settingsRepository.setLayoutDensity(value) }

        fun setCompactNickBrackets(value: CompactNickBrackets) = launch { settingsRepository.setCompactNickBrackets(value) }

        fun setShowChannelPrefixes(enabled: Boolean) = launch { settingsRepository.setShowChannelPrefixes(enabled) }

        fun setComposerStyle(value: ComposerStyle) = launch { appearancePrefs.setComposerStyle(value) }

        fun setInputCornerStyle(value: UiCornerStyle) = launch { appearancePrefs.setInputCornerStyle(value) }

        fun setAvatarShape(value: AvatarShape) = launch { appearancePrefs.setAvatarShape(value) }

        fun setChatCardCornerStyle(value: UiCornerStyle) = launch { appearancePrefs.setChatCardCornerStyle(value) }

        fun setCollapseTitleBar(value: Boolean) = launch { appearancePrefs.setCollapseTitleBar(value) }

        fun setMediaCornerStyle(value: UiCornerStyle) = launch { appearancePrefs.setMediaCornerStyle(value) }

        fun setUiIconSet(value: UiIconSet) = launch { appearancePrefs.setUiIconSet(value) }

        fun setFolderDisplayMode(value: FolderDisplayMode) = launch { settingsRepository.setFolderDisplayMode(value) }

        fun setShowFolderChatsInAll(value: Boolean) = launch { settingsRepository.setShowFolderChatsInAll(value) }

        fun setSeasonalWordmarksEnabled(value: Boolean) = launch { settingsRepository.setSeasonalWordmarksEnabled(value) }

        fun setAvatarStyle(value: AvatarStyle) = launch { settingsRepository.setAvatarStyle(value) }

        fun setNickColorsEnabled(value: Boolean) = launch { settingsRepository.setNickColorsEnabled(value) }

        fun setNickMentionStyle(value: NickMentionStyle) = launch { settingsRepository.setNickMentionStyle(value) }

        fun setTypingIndicatorPlacement(placement: TypingIndicatorPlacement) = launch { settingsRepository.setTypingIndicatorPlacement(placement) }

        fun setNickColorPalette(value: NickColorPalette) = launch { settingsRepository.setNickColorPalette(value) }

        fun setWallpaper(value: WallpaperSelection) =
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    wallpaperWorkshopStore.setWallpaper(value)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _wallpaperFailures.emit(Unit)
                }
            }

        fun importWallpaper(uri: Uri) =
            viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
                if (_wallpaperImporting.value) return@launch
                _wallpaperImporting.value = true
                try {
                    _importedWallpaper.emit(wallpaperWorkshopStore.importWallpaper(uri))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _wallpaperFailures.emit(Unit)
                } finally {
                    _wallpaperImporting.value = false
                }
            }

        fun setUiFontScale(value: Int) = launch { appearancePrefs.setUiFontScale(value) }

        fun setConversationFontScale(value: Int) = launch { appearancePrefs.setConversationFontScale(value) }

        fun setFontChoice(value: FontChoice) = launch { appearancePrefs.setFontChoice(value) }

        fun setShowTimestamps(value: Boolean) = launch { appearancePrefs.setShowTimestamps(value) }

        fun setTimeFormat(value: TimeFormat) = launch { appearancePrefs.setTimeFormat(value) }

        fun setCustomTimeFormatPattern(value: String) = launch { appearancePrefs.setCustomTimeFormatPattern(value) }

        fun setMessageSpacing(value: MessageSpacing) = launch { appearancePrefs.setMessageSpacing(value) }

        fun setBubbleCornerStyle(value: BubbleCornerStyle) = launch { appearancePrefs.setBubbleCornerStyle(value) }

        fun setChatShadowsEnabled(value: Boolean) = launch { appearancePrefs.setChatShadowsEnabled(value) }

        fun setNicknameBackgroundsEnabled(value: Boolean) = launch { appearancePrefs.setNicknameBackgroundsEnabled(value) }

        fun setMessageBackgroundOpacity(value: Int) = launch { appearancePrefs.setMessageBackgroundOpacity(value) }

        fun setMessageBackgroundBlur(value: Int) = launch { appearancePrefs.setMessageBackgroundBlur(value) }

        fun setSentBubbleColor(value: ComfortableBubbleColor) = launch { appearancePrefs.setSentBubbleColor(value) }

        fun setReceivedBubbleColor(value: ComfortableBubbleColor) = launch { appearancePrefs.setReceivedBubbleColor(value) }

        fun setLauncherIcon(value: LauncherIcon) = launch { appearancePrefs.setLauncherIcon(value) }

        fun importCustomFont(uri: Uri) =
            launch {
                val result = customFontStore.import(uri)
                result.onSuccess { name ->
                    appearancePrefs.setCustomFontName(name)
                    appearancePrefs.setFontChoice(FontChoice.CUSTOM)
                }
                _customFontImportEvents.emit(if (result.isSuccess) CustomFontImportEvent.IMPORTED else CustomFontImportEvent.FAILED)
            }

        private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
    }
