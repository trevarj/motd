package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.joinRejectionChannel
import io.github.trevarj.motd.irc.event.messageContextOrNull
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.transport.IrcTransport
import kotlinx.coroutines.CompletableDeferred

/** Tracks only rejection ownership; self JOIN still takes its normal path to persistence. */
internal class JoinRejections(
    val transport: IrcTransport,
) {
    private class Pending(
        val channel: String,
        val wireLabel: String?,
        var requestId: String?,
        var drained: CompletableDeferred<Unit>? = null,
    )

    // A null entry quarantines an ambiguous target for this transport. Without a terminal reply
    // there is no safe expiry that would let us attribute a later untagged rejection to a retry.
    private val pending = mutableMapOf<String, Pending?>()
    private var disabled = false

    @Synchronized
    fun prior(channel: String): CompletableDeferred<Unit>? {
        val previous = pending[bucket(channel)] ?: return null
        return previous.drained ?: CompletableDeferred<Unit>().also { previous.drained = it }
    }

    @Synchronized
    fun release(
        channel: String,
        requestId: String,
    ) {
        // Keep only the drain marker after its UI owner disappears. Dropping the marker would let
        // a late rejection look like a rejection of the next attempt.
        pending[bucket(channel)]?.takeIf { it.requestId == requestId }?.requestId = null
    }

    fun expire(
        channel: String,
        prior: CompletableDeferred<Unit>,
    ) {
        synchronized(this) {
            val key = bucket(channel)
            if (pending[key]?.drained === prior) pending[key] = null
        }
        prior.complete(Unit)
    }

    /** Called in write order, including JOINs which don't have an app rejection observer. */
    fun sent(
        channel: String,
        wireLabel: String?,
        requestId: String?,
    ) {
        val wake =
            synchronized(this) {
                if (disabled) return
                val key = bucket(channel)
                when {
                    key in pending -> {
                        listOfNotNull(pending.put(key, null)?.drained)
                    }

                    pending.size >= MAX_TARGETS -> {
                        disable()
                    }

                    else -> {
                        pending[key] = Pending(channel, wireLabel, requestId)
                        return
                    }
                }
            }
        wake.forEach { it.complete(Unit) }
    }

    fun correlate(
        event: IrcEvent,
        isupport: Isupport,
        selfNick: String,
    ): IrcEvent {
        if (event !is IrcEvent.ServerError && event !is IrcEvent.StandardReply && (event !is IrcEvent.Joined || !event.isSelf)) return event
        val rules = isupport.identityRules
        val ctx = event.messageContextOrNull()
        if (ctx?.isHistoryContext == true || ctx?.batchId != null && ctx.label == null) return event
        val rejectionChannel = event.joinRejectionChannel(rules)
        val channel = rejectionChannel ?: (event as? IrcEvent.Joined)?.takeIf { it.isSelf }?.channel ?: return event
        // ERR_NOSUCHCHANNEL is shared with other commands (e.g. MODE and TOPIC).
        if (ctx?.label == null && event is IrcEvent.ServerError && event.code == "403") return event
        val mapping =
            if (isupport["CASEMAPPING"] == null && "CASEMAPPING" !in isupport.removedKeys) IrcCaseMapping.Ascii else rules.caseMapping
        if (event is IrcEvent.ServerError && event.params.firstOrNull()?.let(mapping::normalize) != mapping.normalize(selfNick)) return event
        val key = bucket(channel)
        val match =
            synchronized(this) {
                val candidate = pending[key] ?: return event
                if (candidate.wireLabel != ctx?.label || mapping.normalize(candidate.channel) != mapping.normalize(channel)) return event
                pending.remove(key)
                candidate
            }
        match.drained?.complete(Unit)
        val id = match.requestId ?: return event
        return when (event) {
            is IrcEvent.ServerError -> event.copy(unlabeledJoinRequestId = id)
            is IrcEvent.StandardReply -> event.copy(unlabeledJoinRequestId = id)
            else -> event
        }
    }

    fun close() {
        val wake = synchronized(this) { disable() }
        wake.forEach { it.complete(Unit) }
    }

    /** Caller holds this monitor. Preserve the safety guard even if the bounded table fills. */
    private fun disable(): List<CompletableDeferred<Unit>> {
        disabled = true
        val wake = pending.values.mapNotNull { it?.drained }
        pending.clear()
        return wake
    }

    // Overfold the exclusion key so a CASEMAPPING update cannot strand an earlier wire JOIN.
    private fun bucket(channel: String): String = IrcCaseMapping.Rfc1459.normalize(channel)

    private companion object {
        const val MAX_TARGETS = 256
    }
}
