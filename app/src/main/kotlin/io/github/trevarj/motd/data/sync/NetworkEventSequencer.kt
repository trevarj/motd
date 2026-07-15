package io.github.trevarj.motd.data.sync

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes persistence for one network while retaining concurrency across networks. */
internal class NetworkEventSequencer {
    private class Entry {
        val lock = Mutex()
        val retired = CompletableDeferred<Unit>()
        var users = 0
        var retiring = false
    }

    private sealed interface Acquisition {
        data class Use(val entry: Entry) : Acquisition
        data class Wait(val retired: CompletableDeferred<Unit>) : Acquisition
    }

    private val entries = ConcurrentHashMap<Long, Entry>()
    private val registry = Mutex()

    suspend fun <T> withNetwork(networkId: Long, block: suspend () -> T): T {
        while (true) {
            when (val acquisition = registry.withLock {
                val entry = entries.computeIfAbsent(networkId) { Entry() }
                if (entry.retiring) {
                    Acquisition.Wait(entry.retired)
                } else {
                    entry.users++
                    Acquisition.Use(entry)
                }
            }) {
                is Acquisition.Wait -> acquisition.retired.await()
                is Acquisition.Use -> {
                    try {
                        return acquisition.entry.lock.withLock { block() }
                    } finally {
                        release(networkId, acquisition.entry)
                    }
                }
            }
        }
    }

    suspend fun evict(networkId: Long) {
        val retired = registry.withLock {
            val entry = entries[networkId] ?: return
            entry.retiring = true
            retireIfUnused(networkId, entry)
            entry.retired
        }
        retired.await()
    }

    suspend fun clear() {
        entries.keys.toList().forEach { evict(it) }
    }

    internal fun size(): Int = entries.size

    private suspend fun release(networkId: Long, entry: Entry) {
        registry.withLock {
            entry.users--
            retireIfUnused(networkId, entry)
        }
    }

    private fun retireIfUnused(networkId: Long, entry: Entry) {
        if (entry.retiring && entry.users == 0 && entries.remove(networkId, entry)) {
            entry.retired.complete(Unit)
        }
    }
}
