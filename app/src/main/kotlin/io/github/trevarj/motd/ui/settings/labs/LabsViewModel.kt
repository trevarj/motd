package io.github.trevarj.motd.ui.settings.labs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.prefs.EbooksLabsPrefs
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.dickord.DickordLabsPrefs
import io.github.trevarj.motd.gesture.GesturePrefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Lab toggles; each lab keeps its own backup-excluded store, so this is just a shared surface. */
data class LabsUiState(
    val gesturesEnabled: Boolean = false,
    val globalFeedEnabled: Boolean = false,
    val dickordEnabled: Boolean = false,
    val ebooksEnabled: Boolean = false,
)

@HiltViewModel
class LabsViewModel
    @Inject
    constructor(
        private val gesturePrefs: GesturePrefs,
        private val globalFeedPrefs: GlobalFeedPrefs,
        private val dickordLabsPrefs: DickordLabsPrefs,
        private val ebooksLabsPrefs: EbooksLabsPrefs,
    ) : ViewModel() {
        val state: StateFlow<LabsUiState> =
            combine(
                gesturePrefs.enabled,
                globalFeedPrefs.enabled,
                dickordLabsPrefs.enabled,
                ebooksLabsPrefs.enabled,
            ) { gestures, globalFeed, dickord, ebooks ->
                LabsUiState(
                    gesturesEnabled = gestures,
                    globalFeedEnabled = globalFeed,
                    dickordEnabled = dickord,
                    ebooksEnabled = ebooks,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LabsUiState())

        fun setGesturesEnabled(enabled: Boolean) {
            viewModelScope.launch { gesturePrefs.setEnabled(enabled) }
        }

        fun setGlobalFeedEnabled(enabled: Boolean) {
            viewModelScope.launch { globalFeedPrefs.setEnabled(enabled) }
        }

        fun setDickordEnabled(enabled: Boolean) {
            viewModelScope.launch { dickordLabsPrefs.setEnabled(enabled) }
        }

        fun setEbooksEnabled(enabled: Boolean) {
            viewModelScope.launch { ebooksLabsPrefs.setEnabled(enabled) }
        }
    }
