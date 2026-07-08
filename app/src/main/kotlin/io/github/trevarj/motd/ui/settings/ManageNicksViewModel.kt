package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.prefs.SettingsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which curated nick list a [ManageNicksScreen] instance is editing. */
enum class NickListKind { FRIENDS, FOOLS, COLORS }

data class ManageNicksUiState(
    val kind: NickListKind = NickListKind.FRIENDS,
    val nicks: List<String> = emptyList(), // sorted ascending
    val overrides: Map<String, Int> = emptyMap(), // COLORS: nick -> hue
)

/**
 * Backs all three manage-nick routes (friends / fools / nick-color overrides). The route picks the
 * [NickListKind] via [init]; the exposed [state] maps the settings flow accordingly and the
 * add/remove/setHue actions route to the matching [SettingsRepository] method.
 */
@HiltViewModel
class ManageNicksViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val kind = MutableStateFlow(NickListKind.FRIENDS)

    fun init(kind: NickListKind) {
        this.kind.value = kind
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<ManageNicksUiState> =
        kind.flatMapLatest { k ->
            settingsRepository.settings.map { settings ->
                when (k) {
                    NickListKind.FRIENDS -> ManageNicksUiState(k, settings.friends.sorted())
                    NickListKind.FOOLS -> ManageNicksUiState(k, settings.fools.sorted())
                    NickListKind.COLORS -> ManageNicksUiState(
                        kind = k,
                        nicks = settings.nickColorOverrides.keys.sorted(),
                        overrides = settings.nickColorOverrides,
                    )
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ManageNicksUiState(),
        )

    fun add(nick: String) = viewModelScope.launch {
        when (kind.value) {
            NickListKind.FRIENDS -> settingsRepository.setFriend(nick, true)
            NickListKind.FOOLS -> settingsRepository.setFool(nick, true)
            NickListKind.COLORS -> Unit // hue set via setHue once a swatch is picked
        }
    }

    fun remove(nick: String) = viewModelScope.launch {
        when (kind.value) {
            NickListKind.FRIENDS -> settingsRepository.setFriend(nick, false)
            NickListKind.FOOLS -> settingsRepository.setFool(nick, false)
            NickListKind.COLORS -> settingsRepository.setNickColorOverride(nick, null)
        }
    }

    fun setHue(nick: String, hue: Int) = viewModelScope.launch {
        settingsRepository.setNickColorOverride(nick, hue)
    }
}
