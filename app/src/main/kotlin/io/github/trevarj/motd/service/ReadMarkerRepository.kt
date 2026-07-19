package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MotdDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Durable Room snapshots used to bulk-clear and reconcile IRCv3 read markers. */
@Singleton
class ReadMarkerRepository @Inject constructor(
    private val db: MotdDatabase,
) : ReadMarkerSnapshotter {
    private val bufferDao: BufferDao get() = db.bufferDao()
    private val messageDao: MessageDao get() = db.messageDao()
    /**
     * Snapshot the newest incoming chat timestamp for each requested buffer in one transaction.
     * Incoming server rows are safe MARKREAD boundaries; pending/local self rows are not.
     */
    override suspend fun latestIncoming(bufferIds: Collection<Long>): List<BufferReadMarker> =
        bufferIds.distinct().takeIf { it.isNotEmpty() }
            ?.let { messageDao.latestIncomingMarkers(it) }
            .orEmpty()
            .map { BufferReadMarker(it.bufferId, it.target, it.timestamp, it.eventId) }

    /** Snapshot every non-SERVER target and its durable local marker for one network. */
    suspend fun storedForNetwork(networkId: Long): List<BufferReadMarker> =
        bufferDao.storedReadMarkers(networkId)
            .map { BufferReadMarker(it.bufferId, it.target, it.timestamp, it.eventId) }
}

interface ReadMarkerSnapshotter {
    suspend fun latestIncoming(bufferIds: Collection<Long>): List<BufferReadMarker>
}

data class BufferReadMarker(
    val bufferId: Long,
    val target: String,
    val timestamp: Long?,
    val eventId: Long? = null,
)
