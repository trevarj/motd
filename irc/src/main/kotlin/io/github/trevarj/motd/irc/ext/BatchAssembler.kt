package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage

/**
 * Assembles IRCv3 batches (plans/02).
 *
 * The client feeds every inbound live message through [route]. Messages carrying a `batch` tag
 * for an open batch are buffered (not emitted live); when the batch closes ([route] on
 * `BATCH -ref`) the caller receives a [Closed] describing the batch and its buffered messages.
 * Nested batches attach to their parent and flatten into it on close.
 *
 * This class is purely structural: it does NOT map messages to events (the client does that,
 * so it can apply the correct batchId context). It only tells the caller "these lines belong
 * together under this batch of this type".
 */
internal class BatchAssembler {
    private class Batch(
        val ref: String,
        val type: String,
        val params: List<String>,
        val parent: String?,
    ) {
        val messages = mutableListOf<IrcMessage>()
    }

    private val open = HashMap<String, Batch>()

    /** Result of feeding one message. */
    sealed interface Outcome {
        /** Message consumed into an (still-open) batch; nothing to emit live. */
        data object Buffered : Outcome
        /** A top-level batch fully closed. [messages] are its contents in arrival order. */
        data class Closed(
            val type: String,
            val params: List<String>,
            val ref: String,
            val messages: List<IrcMessage>,
        ) : Outcome
        /** Not part of any batch; caller should process it live. */
        data object PassThrough : Outcome
    }

    val hasOpenBatch: Boolean get() = open.isNotEmpty()

    fun route(msg: IrcMessage): Outcome {
        // Opening a batch: BATCH +ref TYPE [params...]
        if (msg.command == "BATCH" && msg.params.firstOrNull()?.startsWith("+") == true) {
            val ref = msg.params[0].substring(1)
            val type = msg.params.getOrNull(1).orEmpty()
            val rest = msg.params.drop(2)
            val parent = msg.tags["batch"]
            val batch = Batch(ref, type, rest, parent)
            open[ref] = batch
            // If this batch is nested, record the open marker in its parent's stream.
            if (parent != null) open[parent]?.messages?.add(msg)
            return Outcome.Buffered
        }

        // Closing a batch: BATCH -ref
        if (msg.command == "BATCH" && msg.params.firstOrNull()?.startsWith("-") == true) {
            val ref = msg.params[0].substring(1)
            val batch = open.remove(ref) ?: return Outcome.PassThrough
            val parent = batch.parent
            if (parent != null) {
                // Nested: flatten this batch's contents into the parent, keep the close marker too.
                val p = open[parent]
                if (p != null) {
                    p.messages.addAll(batch.messages)
                    p.messages.add(msg)
                    return Outcome.Buffered
                }
            }
            return Outcome.Closed(batch.type, batch.params, batch.ref, batch.messages.toList())
        }

        // A message tagged into an open batch.
        val batchTag = msg.tags["batch"]
        if (batchTag != null) {
            val batch = open[batchTag]
            if (batch != null) {
                batch.messages.add(msg)
                return Outcome.Buffered
            }
        }

        return Outcome.PassThrough
    }
}
