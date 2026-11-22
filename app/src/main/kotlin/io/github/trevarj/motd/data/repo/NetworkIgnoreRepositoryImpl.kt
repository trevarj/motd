package io.github.trevarj.motd.data.repo

import androidx.room.withTransaction
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkBufferToolRow
import io.github.trevarj.motd.data.db.NetworkIgnoreDao
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class NetworkIgnoreRepositoryImpl
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val ignoreDao: NetworkIgnoreDao,
        private val bufferDao: BufferDao,
        private val cache: NetworkIgnoreCache,
    ) : NetworkIgnoreRepository {
        override fun observeIgnores(networkId: Long): Flow<List<NetworkIgnoreEntity>> = ignoreDao.observeForNetwork(networkId)

        override fun observeBuffers(networkId: Long): Flow<List<NetworkBufferToolRow>> = bufferDao.observeNetworkBufferTools(networkId)

        override suspend fun addIgnore(
            networkId: Long,
            pattern: String,
        ): Result<Unit> =
            normalizeIgnorePattern(pattern).mapCatching { normalized ->
                ignoreDao
                    .upsert(
                        NetworkIgnoreEntity(
                            networkId = networkId,
                            pattern = normalized,
                            enabled = true,
                            createdAt = System.currentTimeMillis(),
                        ),
                    ).let { cache.invalidate(networkId) }
            }

        override suspend fun setExactNickIgnored(
            networkId: Long,
            nick: String,
            rules: IrcIdentityRules,
            ignored: Boolean,
        ): Result<Unit> {
            if (!isExactIgnoreNick(nick, rules)) return Result.failure(IllegalArgumentException("Invalid IRC nickname"))
            val normalizedNick = rules.normalize(nick)
            var changed = false
            return try {
                db.withTransaction {
                    if (ignored) {
                        changed = enableExactNickIgnore(networkId, nick, normalizedNick, rules)
                    } else {
                        for (entry in ignoreDao.enabledForNetwork(networkId)) {
                            if (entry.matchesExactIgnoreNick(normalizedNick, rules)) {
                                ignoreDao.setEnabled(entry.id, false)
                                changed = true
                            }
                        }
                    }
                }
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                if (changed) cache.invalidate(networkId)
            }
        }

        private suspend fun enableExactNickIgnore(
            networkId: Long,
            nick: String,
            normalizedNick: String,
            rules: IrcIdentityRules,
        ): Boolean {
            var disabledId: Long? = null
            for (entry in ignoreDao.allForNetwork(networkId)) {
                if (!entry.matchesExactIgnoreNick(normalizedNick, rules)) continue
                if (entry.enabled) return false
                if (disabledId == null) disabledId = entry.id
            }
            if (disabledId == null) {
                ignoreDao.upsert(
                    NetworkIgnoreEntity(
                        networkId = networkId,
                        pattern = "$nick!*@*",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
            } else {
                ignoreDao.setEnabled(disabledId, true)
            }
            return true
        }

        override suspend fun setIgnoreEnabled(
            id: Long,
            enabled: Boolean,
        ) {
            val networkId = ignoreDao.networkIdFor(id)
            ignoreDao.setEnabled(id, enabled)
            networkId?.let(cache::invalidate)
        }

        override suspend fun deleteIgnore(id: Long) {
            val networkId = ignoreDao.networkIdFor(id)
            ignoreDao.delete(id)
            networkId?.let(cache::invalidate)
        }

        override suspend fun setMuted(
            bufferId: Long,
            muted: Boolean,
        ): MuteBacklogSuppression? = bufferDao.setMuted(bufferDao.canonicalId(bufferId) ?: bufferId, muted)

        override suspend fun restoreMuteBacklog(suppression: MuteBacklogSuppression) {
            bufferDao.restoreLocalUnreadFloor(suppression.bufferId, suppression.previousFloorTime)
        }
    }
