package io.github.trevarj.motd.data.sync

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import javax.inject.Inject
import javax.inject.Singleton

/** Atomic buffer creation shared by event persistence and user-driven buffer creation. */
@Singleton
class BufferStore @Inject constructor(
    private val db: MotdDatabase,
) {
    suspend fun getOrCreate(
        networkId: Long,
        normalizedName: String,
        displayName: String,
        type: BufferType,
    ): BufferEntity = db.withTransaction {
        val dao = db.bufferDao()
        dao.byName(networkId, normalizedName)?.let { return@withTransaction it }
        val candidate = BufferEntity(
            networkId = networkId,
            name = normalizedName,
            displayName = displayName,
            type = type,
        )
        val insertedId = dao.insertIgnore(candidate)
        if (insertedId > 0L) candidate.copy(id = insertedId) else {
            checkNotNull(dao.byName(networkId, normalizedName)) {
                "buffer insert conflict did not leave a row for $networkId/$normalizedName"
            }
        }
    }
}
