package io.github.trevarj.motd.service

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Rejects state, event, and setup callbacks from superseded physical connection actors. */
internal class ConnectionGenerationGate {
    private val sequence = AtomicLong()
    private val current = ConcurrentHashMap<Long, Long>()

    fun begin(networkId: Long): Long = sequence.incrementAndGet().also { current[networkId] = it }

    fun isCurrent(networkId: Long, generation: Long): Boolean = current[networkId] == generation

    fun invalidate(networkId: Long) {
        current.remove(networkId)
    }

    fun invalidateAll() {
        current.clear()
    }
}
