package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkIgnoreDao
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Process-local snapshot invalidated by every production ignore mutation. */
@Singleton
class NetworkIgnoreCache
    @Inject
    constructor(
        private val dao: NetworkIgnoreDao,
    ) {
        private class Entry {
            @Volatile var enabled: List<NetworkIgnoreEntity>? = null
        }

        private val entries = ConcurrentHashMap<Long, Entry>()

        suspend fun enabledForNetwork(networkId: Long): List<NetworkIgnoreEntity> {
            val entry = entries.computeIfAbsent(networkId) { Entry() }
            return entry.enabled ?: dao.enabledForNetwork(networkId).also { entry.enabled = it }
        }

        fun invalidate(networkId: Long) {
            entries.remove(networkId)
        }
    }
