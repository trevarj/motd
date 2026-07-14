package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object WhoxCommands {
    const val FIELDS = "tuhnafr"

    fun request(mask: String, token: Int): IrcMessage {
        require(token in 0..999) { "WHOX token must be 0..999" }
        return IrcMessage(command = "WHO", params = listOf(mask, "%$FIELDS,$token"))
    }
}

internal class WhoxTokenPool(private val size: Int = 1_000) {
    private val active = ConcurrentHashMap.newKeySet<Int>()
    private val next = AtomicInteger(0)

    init {
        require(size > 0)
    }

    fun acquire(): Int? {
        repeat(size) {
            val token = next.getAndUpdate { (it + 1) % size }
            if (active.add(token)) return token
        }
        return null
    }

    fun release(token: Int) {
        active.remove(token)
    }

    fun clear() {
        active.clear()
    }
}
