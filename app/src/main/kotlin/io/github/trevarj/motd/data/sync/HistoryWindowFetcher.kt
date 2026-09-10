package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.HistoryPruneDao
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a manual history fetch, shaped for one snackbar. */
sealed interface HistorySyncOutcome {
    data class Fetched(
        val messages: Int,
        val chats: Int,
    ) : HistorySyncOutcome

    data object Unsupported : HistorySyncOutcome

    data object Failed : HistorySyncOutcome

    /** Stopped by the user; whatever landed before that stays. */
    data object Cancelled : HistorySyncOutcome
}

/**
 * The manual "fetch the last N days" for one network, as a process-scoped job: it takes minutes on
 * a busy account and must outlive the settings screen that started it. Discovery runs first, so
 * chats active in the window that the device has never seen are found and seeded, then every open
 * room is paged older until it reaches the window's floor, under the chat list's sync bar.
 */
@Singleton
class HistoryWindowFetcher
    @Inject
    constructor(
        private val connectionManager: ConnectionManager,
        private val gapFill: HistoryGapFillCoordinator,
        private val resync: HistoryResyncController,
        private val dao: HistoryPruneDao,
        @param:ApplicationScope private val scope: CoroutineScope,
        private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    ) {
        private val _running = MutableStateFlow<Set<Long>>(emptySet())
        private val jobs = ConcurrentHashMap<Long, Job>()

        /** Networks with a fetch in flight. */
        val running: StateFlow<Set<Long>> = _running.asStateFlow()

        private val _results = MutableSharedFlow<Pair<Long, HistorySyncOutcome>>(extraBufferCapacity = 8)

        /** One entry per finished fetch, keyed by network. */
        val results: SharedFlow<Pair<Long, HistorySyncOutcome>> = _results.asSharedFlow()

        /** Start a fetch over the last [lookbackMs] (null: everything). A second start on a busy network is dropped. */
        fun fetch(
            networkId: Long,
            lookbackMs: Long?,
        ) {
            if (!_running.compareAndUpdate(networkId)) return
            jobs[networkId] =
                scope.launch {
                    val outcome =
                        try {
                            run(networkId, lookbackMs)
                        } catch (cancelled: CancellationException) {
                            // Decades of history are a legitimate reason to stop; what landed stays.
                            _results.tryEmit(networkId to HistorySyncOutcome.Cancelled)
                            throw cancelled
                        } catch (failure: Exception) {
                            diagnostics.record("chat_history", "window_fetch_failed") { mapOf("network_id" to networkId, "error" to failure.toString()) }
                            HistorySyncOutcome.Failed
                        } finally {
                            jobs.remove(networkId)
                            _running.update { it - networkId }
                        }
                    _results.emit(networkId to outcome)
                }
        }

        /** Stop a running fetch; rooms already walked keep their rows, the current page is abandoned. */
        fun cancel(networkId: Long) {
            jobs[networkId]?.cancel()
        }

        private suspend fun run(
            networkId: Long,
            lookbackMs: Long?,
        ): HistorySyncOutcome {
            val discovery = connectionManager.resyncHistory(networkId, lookbackMs)
            when (discovery) {
                HistoryResyncState.Unsupported -> return HistorySyncOutcome.Unsupported
                is HistoryResyncState.Incomplete -> Unit
                is HistoryResyncState.Failed -> return HistorySyncOutcome.Failed
                else -> Unit
            }
            val floor = lookbackMs?.let { System.currentTimeMillis() - it } ?: 0L
            var inserted = (discovery as? HistoryResyncState.Updated)?.inserted ?: (discovery as? HistoryResyncState.Incomplete)?.inserted ?: 0
            var chats = 0
            val rooms = dao.roomSpans(networkId).map { it.roomId }
            resync.manualPass(networkId, rooms) { roomId ->
                val fetch = gapFill.fetchRoomWindow(roomId, floor)
                if (fetch.pagesLoaded > 0) chats++
                inserted += fetch.insertedCount
            }
            return HistorySyncOutcome.Fetched(messages = inserted, chats = chats)
        }

        /** Atomically claim [networkId]; false when a fetch for it is already running. */
        private fun MutableStateFlow<Set<Long>>.compareAndUpdate(networkId: Long): Boolean {
            while (true) {
                val current = value
                if (networkId in current) return false
                if (compareAndSet(current, current + networkId)) return true
            }
        }
    }
