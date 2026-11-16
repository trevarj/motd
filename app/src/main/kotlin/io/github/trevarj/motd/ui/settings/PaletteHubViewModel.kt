package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.prefs.*
import io.github.trevarj.motd.data.repo.PaletteCatalog
import io.github.trevarj.motd.data.repo.PaletteCatalogState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class PaletteHubState(
    val appearance: AppearanceConfig = AppearanceConfig(),
    val settings: Settings = Settings(),
    val error: String? = null,
    val catalog: PaletteCatalogState = PaletteCatalogState(),
)

@HiltViewModel
class PaletteHubViewModel
    @Inject
    constructor(
        private val appearancePrefs: AppearancePrefs,
        settingsRepository: SettingsRepository,
        private val catalog: PaletteCatalog,
    ) : ViewModel() {
        private val error = MutableStateFlow<String?>(null)
        val state =
            combine(appearancePrefs.config, settingsRepository.settings, error, catalog.state) { appearance, settings, failure, community ->
                PaletteHubState(appearance, settings, failure, community)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PaletteHubState())

        fun select(selection: PaletteSelection) = perform { appearancePrefs.selectPalette(selection) }

        fun remove(id: String) = perform { appearancePrefs.removePalette(id) }

        fun refreshCatalog() = perform { catalog.refresh() }

        fun clearError() {
            error.value = null
        }

        fun reportError(failure: Throwable) {
            error.value = failure.message ?: "Palette action failed"
        }

        private fun perform(action: suspend () -> Unit) =
            viewModelScope.launch {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportError(failure)
                }
            }
    }

internal fun localPaletteFork(document: PaletteDocument): PaletteDocument = decodePalette(encodePalette(document.copy(id = UUID.randomUUID().toString(), revision = 1)))

internal fun duplicatePalette(document: PaletteDocument): PaletteDocument {
    val suffix = " (copy)"
    val base =
        document.name
            .codePoints()
            .limit((64 - suffix.length).toLong())
            .toArray()
    return localPaletteFork(document.copy(name = String(base, 0, base.size) + suffix))
}
