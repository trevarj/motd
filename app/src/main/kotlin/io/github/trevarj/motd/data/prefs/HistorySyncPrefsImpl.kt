package io.github.trevarj.motd.data.prefs

import io.github.trevarj.motd.data.db.MotdDatabase
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistorySyncPrefsImpl @Inject constructor(
    db: MotdDatabase,
) : HistorySyncPrefs {
    private val cursors = db.historyCursorDao()

    override suspend fun lastSuccessfulSync(networkId: Long): Long? =
        cursors.networkLastSuccessfulSync(networkId)

    override suspend fun setLastSuccessfulSync(networkId: Long, timestamp: Long) {
        cursors.setNetworkLastSuccessfulSync(networkId, timestamp)
    }

    override suspend fun clear(networkId: Long) {
        cursors.clearNetwork(networkId)
    }
}
