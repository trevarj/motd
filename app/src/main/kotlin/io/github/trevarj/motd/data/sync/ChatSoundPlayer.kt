package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.irc.event.IrcEvent

/** Low-latency foreground chat sonification, kept separate from Android notifications. */
interface ChatSoundPlayer {
    suspend fun onIncoming(bufferId: Long, type: BufferType, message: IrcEvent.ChatMessage)

    /** Canonical identity-aware hook. Legacy/test implementations retain the event-only seam. */
    suspend fun onCanonicalIncoming(
        bufferId: Long,
        type: BufferType,
        message: IrcEvent.ChatMessage,
        canonical: MessageEntity,
    ) = onIncoming(bufferId, type, message)

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
