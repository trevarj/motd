package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferDao

/** Typed recovery reads kept behind a small connection-owned seam. */
internal class ConnectionRecoveryReader(private val bufferDao: BufferDao) {
    suspend fun joinedChannels(networkId: Long): List<String> =
        bufferDao.joinedChannelNames(networkId)
}
