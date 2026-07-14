package io.github.trevarj.motd.data.prefs

/**
 * Per-network watermark for a completed reconnect CHATHISTORY pass. This is deliberately kept
 * outside Room: it is local operational state, not IRC-derived content, and can be discarded
 * safely when a network is removed.
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
