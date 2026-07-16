package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.irc.event.IrcEvent

/** Low-latency foreground chat sonification, kept separate from Android notifications. */
interface ChatSoundPlayer {
    suspend fun onIncoming(bufferId: Long, type: BufferType, message: IrcEvent.ChatMessage)
    suspend fun onOutgoingAccepted(bufferId: Long)

    object Noop : ChatSoundPlayer {
        override suspend fun onIncoming(
            bufferId: Long,
            type: BufferType,
            message: IrcEvent.ChatMessage,
        ) = Unit

        override suspend fun onOutgoingAccepted(bufferId: Long) = Unit
    }
}
