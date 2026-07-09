package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

// Thin pass-through over NetworkDao. Delete resolves the row by id first (Dao.delete takes an
// entity); a missing row is a no-op.
class NetworkRepositoryImpl @Inject constructor(
    private val networkDao: NetworkDao,
) : NetworkRepository {
    override fun observeNetworks(): Flow<List<NetworkEntity>> = networkDao.observeAll()

    override suspend fun addNetwork(n: NetworkEntity): Long = networkDao.insert(n)

    override suspend fun updateNetwork(n: NetworkEntity) = networkDao.update(n)

    override suspend fun deleteNetwork(id: Long) {
        networkDao.byId(id)?.let { networkDao.delete(it) }
    }

    override suspend fun networkById(id: Long): NetworkEntity? = networkDao.byId(id)

    override suspend fun childrenOf(rootId: Long): List<NetworkEntity> = networkDao.childrenOf(rootId)
}
