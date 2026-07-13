package io.github.trevarj.motd.service

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.MotdDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** App-owned recovery reads kept outside the frozen Room DAO contracts. */
internal class ConnectionRecoveryReader(private val db: MotdDatabase) {
    suspend fun joinedChannels(networkId: Long): List<String> = withContext(Dispatchers.IO) {
        db.query(
            SimpleSQLiteQuery(
                "SELECT displayName FROM buffers " +
                    "WHERE networkId = ? AND type = 'CHANNEL' AND joined = 1 ORDER BY id",
                arrayOf<Any>(networkId),
            ),
        ).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
    }
}
