package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent

enum class HistoryReferenceType {
    TIMESTAMP,
    MSGID,
}

sealed interface HistoryAvailability {
    data class Ready(
        val referenceTypes: Set<HistoryReferenceType>,
        /** Advertised per-request maximum; [Int.MAX_VALUE] means the server advertised no maximum. */
        val pageLimit: Int,
    ) : HistoryAvailability

    data object NegotiatingOrOffline : HistoryAvailability
    data object Unsupported : HistoryAvailability
}

data class ChatHistoryReference(
    val msgid: String?,
    /** Authoritative raw `time` tag, or null when the server supplied no valid timestamp. */
    val serverTime: Long?,
)

data class ChatHistoryTarget(
    val name: String,
    val latestMessageTime: Long,
)

/** A value of this type proves that the matching root CHATHISTORY batch closed successfully. */
sealed interface ChatHistoryResponse {
    val endOfHistory: Boolean

    data class Messages(
        /** Every event in the completed batch that the normal IRC event mapper can ingest. */
        val events: List<IrcEvent>,
        /** First usable non-context event reference in the server's response order. */
        val oldest: ChatHistoryReference?,
        /** Last usable non-context event reference in the server's response order. */
        val newest: ChatHistoryReference?,
        override val endOfHistory: Boolean,
        /** Number of mapped ingestible primary events, independent of reference availability. */
        val primaryMessageCount: Int = 0,
    ) : ChatHistoryResponse {
        init {
            require(primaryMessageCount >= 0) { "primaryMessageCount must not be negative" }
        }
    }

    data class Targets(
        val targets: List<ChatHistoryTarget>,
        override val endOfHistory: Boolean,
    ) : ChatHistoryResponse
}
