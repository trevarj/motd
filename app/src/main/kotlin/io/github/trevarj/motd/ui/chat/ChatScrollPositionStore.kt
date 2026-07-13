package io.github.trevarj.motd.ui.chat

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatScrollPositionStore @Inject constructor() {
    private val positions = java.util.concurrent.ConcurrentHashMap<Long, ChatScrollPosition>()

    fun get(bufferId: Long): ChatScrollPosition? = positions[bufferId]

    fun put(bufferId: Long, position: ChatScrollPosition) {
        positions[bufferId] = position
    }

    fun remove(bufferId: Long) {
        positions.remove(bufferId)
    }
}
