package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryPruneDao
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.channelRetentionRows
import io.github.trevarj.motd.data.prefs.queryRetentionRows
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Trims locally retained history to the configured [HistoryRetention]. */
interface HistoryPruner {
    /** Prune one network's rooms (null: every network) off the caller's thread. */
    fun schedule(networkId: Long?)

    /** Prune now, then rewrite the database file so freed pages return to the filesystem. Returns bytes reclaimed. */
    suspend fun compact(): Long

    /** Current on-disk size of the message database, WAL included. */
    suspend fun databaseSizeBytes(): Long

    /** Measure the database so a cap can be planned from a size target, or a size from a cap. */
    suspend fun profile(): DatabaseProfile

    /** True while the file is being rewritten, by the button or automatically after a prune. */
    val compacting: StateFlow<Boolean>

    object Noop : HistoryPruner {
        override fun schedule(networkId: Long?) = Unit

        override suspend fun compact(): Long = 0L

        override suspend fun databaseSizeBytes(): Long = 0L

        override suspend fun profile(): DatabaseProfile = DatabaseProfile(0L, 0L, emptyList())

        override val compacting: StateFlow<Boolean> = MutableStateFlow(false)
    }
}

@Singleton
class HistoryPrunerImpl
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val settingsRepository: SettingsRepository,
        @param:ApplicationScope private val scope: CoroutineScope,
        private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
    ) : HistoryPruner {
        // One prune at a time: the sync trigger and the settings trigger can coincide at startup.
        private val mutex = Mutex()

        private val _compacting = MutableStateFlow(false)
        override val compacting: StateFlow<Boolean> = _compacting.asStateFlow()

        /** A tightened retention takes effect immediately instead of at the next reconnect sync. */
        fun start() {
            settingsRepository.settings
                .map { it.channelRetentionRows to it.queryRetentionRows }
                .distinctUntilChanged()
                .drop(1)
                .onEach { schedule(null) }
                .launchIn(scope)
        }

        override fun schedule(networkId: Long?) {
            scope.launch(Dispatchers.IO) {
                runCatching { prune(networkId, settingsRepository.settings.first()) }
                    .onFailure { failure ->
                        diagnostics.record("history", "prune_failed") { mapOf("error" to failure.toString()) }
                    }
            }
        }

        override suspend fun compact(): Long =
            withContext(Dispatchers.IO) {
                prune(null, settingsRepository.settings.first())
                val before = databaseSizeBytes()
                mutex.withLock { vacuum() }
                (before - databaseSizeBytes()).also { freed ->
                    diagnostics.record("history", "database_compacted") { mapOf("freed_bytes" to freed) }
                }
            }

        /**
         * Rewrite the file so free pages return to the filesystem. Caller holds [mutex]; no transaction
         * may be open on the writer.
         *
         * In WAL mode a VACUUM writes the whole rewritten database into the WAL; the main file only
         * shrinks once a full checkpoint moves it back, and a checkpoint cannot complete while any
         * reader holds a snapshot. Room's observers re-query constantly but hold snapshots only for
         * the duration of a query, so a truncating checkpoint is retried until it finds that window;
         * without this the file stayed at full size until the app was closed.
         */
        private suspend fun vacuum() {
            _compacting.value = true
            try {
                val sql = db.openHelper.writableDatabase
                checkpointUntilClean(sql)
                // Takes effect with this VACUUM; from then on prune() can hand pages back incrementally.
                sql.execSQL("PRAGMA auto_vacuum = INCREMENTAL")
                sql.execSQL("VACUUM")
                checkpointUntilClean(sql)
            } finally {
                _compacting.value = false
            }
        }

        /** Truncating checkpoint, retried while readers are in the way; gives up after [CHECKPOINT_ATTEMPTS]. */
        private suspend fun checkpointUntilClean(sql: SupportSQLiteDatabase) {
            repeat(CHECKPOINT_ATTEMPTS) { attempt ->
                val busy = sql.query("PRAGMA wal_checkpoint(TRUNCATE)").use { if (it.moveToFirst()) it.getLong(0) else 0L }
                if (busy == 0L) return
                if (attempt == CHECKPOINT_ATTEMPTS - 1) {
                    diagnostics.record("history", "checkpoint_blocked") { mapOf("attempts" to CHECKPOINT_ATTEMPTS) }
                }
                delay(CHECKPOINT_RETRY_MS)
            }
        }

        /** Bytes the file holds in free pages: what a rewrite would give back. */
        private fun reclaimableBytes(): Long {
            val sql = db.openHelper.readableDatabase

            fun pragma(name: String): Long = sql.query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else 0L }
            return pragma("freelist_count") * pragma("page_size")
        }

        override suspend fun databaseSizeBytes(): Long =
            withContext(Dispatchers.IO) {
                val path = db.openHelper.writableDatabase.path ?: return@withContext 0L
                File(path).length() + File("$path-wal").length()
            }

        override suspend fun profile(): DatabaseProfile =
            withContext(Dispatchers.IO) {
                val sql = db.openHelper.readableDatabase

                fun pragma(name: String): Long = sql.query("PRAGMA $name").use { if (it.moveToFirst()) it.getLong(0) else 0L }
                val liveBytes = (pragma("page_count") - pragma("freelist_count")) * pragma("page_size")
                val dao = db.historyPruneDao()
                DatabaseProfile(liveBytes, dao.totalRows(), dao.prunableRooms())
            }

        private suspend fun prune(
            networkId: Long?,
            settings: Settings,
        ): Int =
            prune(
                networkId,
                settings.channelRetentionRows,
                settings.queryRetentionRows,
                autoCompactBytes = settings.autoCompactMb.takeIf { it > 0 }?.let { it * MEGABYTE },
            )

        /**
         * Returns the number of rows removed. Rooms whose network never synced server history are
         * skipped. When a prune leaves at least [autoCompactBytes] of free pages behind, the file is
         * rewritten on the spot, so a fast-paced account never needs the Compact button.
         */
        internal suspend fun prune(
            networkId: Long?,
            channelRows: Int?,
            queryRows: Int?,
            autoCompactBytes: Long? = null,
        ): Int =
            mutex.withLock {
                if (channelRows == null && queryRows == null) return@withLock 0
                val dao = db.historyPruneDao()
                var deleted = 0
                for (target in dao.targets(networkId)) {
                    val keep = (if (target.type == BufferType.QUERY) queryRows else channelRows) ?: continue
                    deleted += pruneRoom(dao, target.roomId, keep)
                }
                if (deleted > 0) {
                    val reclaimable = reclaimableBytes()
                    if (autoCompactBytes != null && reclaimable >= autoCompactBytes) {
                        vacuum()
                        diagnostics.record("history", "database_compacted") { mapOf("freed_bytes" to reclaimable, "automatic" to true) }
                    } else {
                        // No-op until a VACUUM has switched the file to incremental auto-vacuum.
                        db.openHelper.writableDatabase
                            .query("PRAGMA incremental_vacuum")
                            .use { it.moveToFirst() }
                    }
                    diagnostics.record("history", "pruned") { mapOf("network_id" to networkId, "rows" to deleted) }
                }
                deleted
            }

        private suspend fun pruneRoom(
            dao: HistoryPruneDao,
            roomId: RoomId,
            keepRows: Int,
        ): Int {
            val floor = dao.pruneFloor(roomId, keepOffset = keepRows - 1) ?: return 0
            var deleted = 0
            // Short transactions: every row also drops its FTS entry, aliases, and observations, and
            // the live ingest writer must never wait behind a whole room's worth of that.
            while (true) {
                val chunk = db.withTransaction { dao.deleteOldest(roomId, floor, CHUNK_ROWS) }
                if (chunk == 0) break
                deleted += chunk
            }
            if (deleted > 0) {
                db.withTransaction {
                    dao.reopenHistory(roomId)
                    dao.forgetOldestCursor(roomId)
                    dao.dropGapsBelow(roomId, floor)
                }
            }
            return deleted
        }

        private companion object {
            const val CHUNK_ROWS = 500
            const val MEGABYTE = 1_000_000L
            const val CHECKPOINT_ATTEMPTS = 100
            const val CHECKPOINT_RETRY_MS = 100L
        }
    }
