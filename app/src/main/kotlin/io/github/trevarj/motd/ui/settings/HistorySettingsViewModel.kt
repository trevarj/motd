package io.github.trevarj.motd.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.data.prefs.HistoryRetention
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.sync.DatabaseProfile
import io.github.trevarj.motd.data.sync.HistoryPruner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface DatabaseCompactEvent {
    data class Compacted(
        val freedBytes: Long,
    ) : DatabaseCompactEvent

    data object Failed : DatabaseCompactEvent
}

@HiltViewModel
class HistorySettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val historyPruner: HistoryPruner,
    ) : ViewModel() {
        val settings = settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

        private val _databaseSizeBytes = MutableStateFlow(0L)
        val databaseSizeBytes = _databaseSizeBytes.asStateFlow()

        /** Null until [loadDatabaseProfile] has measured; the overview and planner read it. */
        private val _databaseProfile = MutableStateFlow<DatabaseProfile?>(null)
        val databaseProfile = _databaseProfile.asStateFlow()

        private val _databaseCompactEvents = MutableSharedFlow<DatabaseCompactEvent>()
        val databaseCompactEvents = _databaseCompactEvents.asSharedFlow()

        /** Covers the button and automatic compaction alike, so the row can't be pressed twice. */
        val compacting: StateFlow<Boolean> = historyPruner.compacting

        init {
            refreshDatabaseSize()
            loadDatabaseProfile()
        }

        fun setHistoryRetention(value: HistoryRetention) = launch { settingsRepository.setHistoryRetention(value) }

        fun setAutoCompactMb(mb: Int) = launch { settingsRepository.setAutoCompactMb(mb) }

        fun setHistoryRetentionCustomRows(rows: Int) =
            launch {
                settingsRepository.setHistoryRetentionCustomRows(rows)
                settingsRepository.setHistoryRetention(HistoryRetention.CUSTOM)
            }

        fun loadDatabaseProfile() =
            launch {
                _databaseProfile.value = null
                _databaseProfile.value = historyPruner.profile()
            }

        fun compactDatabase() =
            launch {
                val event =
                    try {
                        DatabaseCompactEvent.Compacted(historyPruner.compact())
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        DatabaseCompactEvent.Failed
                    }
                refreshDatabaseSize()
                loadDatabaseProfile()
                _databaseCompactEvents.emit(event)
            }

        private fun refreshDatabaseSize() = launch { _databaseSizeBytes.value = historyPruner.databaseSizeBytes() }

        private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }
    }
