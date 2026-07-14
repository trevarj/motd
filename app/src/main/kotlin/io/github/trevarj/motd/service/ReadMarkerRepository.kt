package io.github.trevarj.motd.service

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.MotdDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Durable Room snapshots used to bulk-clear and reconcile IRCv3 read markers. */
@Singleton
class ReadMarkerRepository @Inject constructor(
    private val db: MotdDatabase,
) : ReadMarkerSnapshotter {
    /**
     * Snapshot the newest incoming chat timestamp for each requested buffer in one transaction.
     * Incoming server rows are safe MARKREAD boundaries; pending/local self rows are not.
     */
    override suspend fun latestIncoming(bufferIds: Collection<Long>): List<BufferReadMarker> =
        db.withTransaction {
            bufferIds.distinct().mapNotNull { bufferId ->
                db.query(
                    SimpleSQLiteQuery(
                        """
                        SELECT b.id, b.name, MAX(m.serverTime)
                        FROM buffers b
                        JOIN messages m ON m.bufferId = b.id
                        WHERE b.id = ?
                          AND b.type != 'SERVER'
                          AND m.isSelf = 0
                          AND m.kind IN ('PRIVMSG', 'NOTICE', 'ACTION')
                        GROUP BY b.id, b.name
                        """.trimIndent(),
                        arrayOf<Any>(bufferId),
                    ),
                ).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.isNull(2)) return@mapNotNull null
                    BufferReadMarker(
                        bufferId = cursor.getLong(0),
                        target = cursor.getString(1),
                        timestamp = cursor.getLong(2),
                    )
                }
            }
        }

    /** Snapshot every non-SERVER target and its durable local marker for one network. */
    suspend fun storedForNetwork(networkId: Long): List<BufferReadMarker> =
        db.withTransaction {
            db.query(
                SimpleSQLiteQuery(
                    """
                    SELECT id, name, readMarkerTime
                    FROM buffers
                    WHERE networkId = ? AND type != 'SERVER'
                    ORDER BY id
                    """.trimIndent(),
                    arrayOf<Any>(networkId),
                ),
            ).use { cursor ->
                buildList(cursor.count) {
                    while (cursor.moveToNext()) {
                        add(
                            BufferReadMarker(
                                bufferId = cursor.getLong(0),
                                target = cursor.getString(1),
                                timestamp = if (cursor.isNull(2)) null else cursor.getLong(2),
                            ),
                        )
                    }
                }
            }
        }
}

interface ReadMarkerSnapshotter {
    suspend fun latestIncoming(bufferIds: Collection<Long>): List<BufferReadMarker>
}

data class BufferReadMarker(
    val bufferId: Long,
    val target: String,
    val timestamp: Long?,
)
