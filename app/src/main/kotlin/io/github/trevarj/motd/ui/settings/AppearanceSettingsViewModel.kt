package io.github.trevarj.motd.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.fonts.CustomFontStore
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.AppearancePrefs
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.BubbleCornerStyle
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.LauncherIcon
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MessageSpacing
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class AppearanceSettingsUiState(
    val settings: Settings = Settings(),
    val appearance: AppearanceConfig = AppearanceConfig(),
)

enum class CustomFontImportEvent { IMPORTED, FAILED }

@HiltViewModel
class AppearanceSettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val appearancePrefs: AppearancePrefs,
        private val customFontStore: CustomFontStore,
    ) : ViewModel() {
        private val _customFontImportEvents = MutableSharedFlow<CustomFontImportEvent>()
        val customFontImportEvents = _customFontImportEvents.asSharedFlow()
        val fontRevision: StateFlow<Long> = customFontStore.revision
        val customFontFile: File? get() = customFontStore.installedFile()

        val state =
            combine(settingsRepository.settings, appearancePrefs.config, ::AppearanceSettingsUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearanceSettingsUiState())

        fun setThemePreset(value: ColorThemePreset) = launch { appearancePrefs.setTheme(value) }

        fun setTrueBlack(value: Boolean) = launch { appearancePrefs.setTrueBlack(value) }

        fun setFollowSystem(value: Boolean) = launch { appearancePrefs.setFollowSystem(value) }

        fun setDynamicColor(value: Boolean) = launch { settingsRepository.setDynamicColor(value) }

        fun setLayoutDensity(value: LayoutDensity) = launch { settingsRepository.setLayoutDensity(value) }

        fun setFolderDisplayMode(value: FolderDisplayMode) = launch { settingsRepository.setFolderDisplayMode(value) }

        fun setShowFolderChatsInAll(value: Boolean) = launch { settingsRepository.setShowFolderChatsInAll(value) }

        fun setAvatarStyle(value: AvatarStyle) = launch { settingsRepository.setAvatarStyle(value) }

        fun setNickColorsEnabled(value: Boolean) = launch { settingsRepository.setNickColorsEnabled(value) }

        fun setNickColorPalette(value: NickColorPalette) = launch { settingsRepository.setNickColorPalette(value) }

        fun setWallpaper(value: WallpaperSelection) = launch { appearancePrefs.setWallpaper(value) }

        fun setUiFontScale(value: Int) = launch { appearancePrefs.setUiFontScale(value) }

        fun setConversationFontScale(value: Int) = launch { appearancePrefs.setConversationFontScale(value) }

        fun setFontChoice(value: FontChoice) = launch { appearancePrefs.setFontChoice(value) }

        fun setShowTimestamps(value: Boolean) = launch { appearancePrefs.setShowTimestamps(value) }

        fun setTimeFormat(value: TimeFormat) = launch { appearancePrefs.setTimeFormat(value) }

        fun setCustomTimeFormatPattern(value: String) = launch { appearancePrefs.setCustomTimeFormatPattern(value) }

        fun setMessageSpacing(value: MessageSpacing) = launch { appearancePrefs.setMessageSpacing(value) }

        fun setBubbleCornerStyle(value: BubbleCornerStyle) = launch { appearancePrefs.setBubbleCornerStyle(value) }

        fun setChatShadowsEnabled(value: Boolean) = launch { appearancePrefs.setChatShadowsEnabled(value) }

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
