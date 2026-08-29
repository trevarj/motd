package io.github.trevarj.motd.ui.settings.labs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.agentwire.AgentwirePrefs
import io.github.trevarj.motd.data.prefs.GlobalFeedPrefs
import io.github.trevarj.motd.gesture.GesturePrefs
import io.github.trevarj.motd.sidecar.SidecarPrefs
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Lab toggles; each lab keeps its own backup-excluded store, so this is just a shared surface. */
data class LabsUiState(
    val gesturesEnabled: Boolean = false,
    val agentwireEnabled: Boolean = false,
    val globalFeedEnabled: Boolean = false,
    val sidecarsEnabled: Boolean = false,
)

@HiltViewModel
class LabsViewModel
    @Inject
    constructor(
        private val gesturePrefs: GesturePrefs,
        private val agentwirePrefs: AgentwirePrefs,
        private val globalFeedPrefs: GlobalFeedPrefs,
        private val sidecarPrefs: SidecarPrefs,
    ) : ViewModel() {
        val state: StateFlow<LabsUiState> =
            combine(
                gesturePrefs.enabled,
                agentwirePrefs.enabled,
                globalFeedPrefs.enabled,
                sidecarPrefs.enabled,
            ) { gestures, agentwire, globalFeed, sidecars ->
                LabsUiState(
                    gesturesEnabled = gestures,
                    agentwireEnabled = agentwire,
                    globalFeedEnabled = globalFeed,
                    sidecarsEnabled = sidecars,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LabsUiState())

        fun setGesturesEnabled(enabled: Boolean) {
            viewModelScope.launch { gesturePrefs.setEnabled(enabled) }
        }

        fun setAgentwireEnabled(enabled: Boolean) {
            viewModelScope.launch { agentwirePrefs.setEnabled(enabled) }
        }

        fun setGlobalFeedEnabled(enabled: Boolean) {
            viewModelScope.launch { globalFeedPrefs.setEnabled(enabled) }
        }

        fun setSidecarsEnabled(enabled: Boolean) {
            viewModelScope.launch { sidecarPrefs.setEnabled(enabled) }
        }
    }
