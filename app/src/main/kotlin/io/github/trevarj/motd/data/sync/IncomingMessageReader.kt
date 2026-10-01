package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.data.db.MessageEntity

/** Admission only: never waits for synthesis or playback on the IRC collector. */
interface IncomingMessageReader {
    fun onIncoming(message: MessageEntity)

    object Noop : IncomingMessageReader {
        override fun onIncoming(message: MessageEntity) = Unit
    }
}
