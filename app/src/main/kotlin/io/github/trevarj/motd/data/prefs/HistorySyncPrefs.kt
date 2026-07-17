package io.github.trevarj.motd.data.prefs

/**
 * Per-network watermark for a completed reconnect CHATHISTORY pass. The implementation persists
 * it beside the canonical room cursors so a v10 reset cannot inherit stale DataStore timestamps.
 */
interface HistorySyncPrefs {
    suspend fun lastSuccessfulSync(networkId: Long): Long?
    suspend fun setLastSuccessfulSync(networkId: Long, timestamp: Long)
    suspend fun clear(networkId: Long)
}

object NoopHistorySyncPrefs : HistorySyncPrefs {
    override suspend fun lastSuccessfulSync(networkId: Long): Long? = null
    override suspend fun setLastSuccessfulSync(networkId: Long, timestamp: Long) = Unit
    override suspend fun clear(networkId: Long) = Unit
}
