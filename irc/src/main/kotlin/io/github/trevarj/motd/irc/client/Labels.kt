package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicLong

/** Raised when a labeled command completes with a FAIL / ERR_* numeric. */
class IrcCommandException(
    val ircCommand: String,
    val code: String,
    val text: String,
) : Exception("$ircCommand failed ($code): $text")

/** Raised when a labeled command receives no completing response within the timeout. */
class IrcTimeoutException(val label: String) : Exception("labeled response timed out: $label")

/** Raised when a command awaiting an IRC response loses its connection. */
class IrcDisconnectedException(val ircCommand: String, val reason: String?) :
    Exception("$ircCommand disconnected${reason?.let { ": $it" }.orEmpty()}")

/**
 * Correlates labeled requests with their responses (IRCv3 `labeled-response`).
 *
 * A single [IrcClient] connection owns one correlator. [next] hands out monotonic
 * `motd-<n>` labels. [register] parks a [CompletableDeferred] keyed by label; the read
 * loop feeds every inbound message through [route] which completes the deferred once the
 * response is fully assembled.
 *
 * Completion rules (plans/02):
 *  - a labelled message that does NOT open a batch  → single-message response.
 *  - `BATCH +ref <type> ...` carrying the label     → buffer every `batch=ref` line
 *    (nested batches reassembled recursively) until `BATCH -ref`, complete with the list.
 *  - `ACK` carrying the label                        → complete with an empty list.
 *  - `FAIL` / `ERR_*` carrying the label             → complete exceptionally.
 */
internal class LabelCorrelator {
    private val counter = AtomicLong(0)

    private class Pending(val deferred: CompletableDeferred<List<IrcMessage>>) {
        // Non-null once this label opened a batch; collects everything under it.
        var batchRef: String? = null
        val buffered = mutableListOf<IrcMessage>()
        // Nested batch refs opened under the root batch (so we know when we're back at root).
        val openNested = mutableSetOf<String>()
    }

    // label -> pending request
    private val byLabel = HashMap<String, Pending>()
    // batchRef -> label, so batch-tagged lines route to the right pending request
    private val refToLabel = HashMap<String, String>()

    fun next(): String = "motd-${counter.incrementAndGet()}"

    fun register(label: String, deferred: CompletableDeferred<List<IrcMessage>>) {
        byLabel[label] = Pending(deferred)
    }

    /**
     * Route one inbound message. Returns true if the message belonged to a labeled request
     * (and was consumed), false if the caller should process it normally.
     */
    fun route(msg: IrcMessage): Boolean {
        // 1) Message tagged with a batch ref we own -> buffer under that request.
        val batchTag = msg.tags["batch"]
        if (batchTag != null) {
            val label = refToLabel[batchTag]
            if (label != null) {
                val pending = byLabel[label] ?: return false
                // A nested BATCH open inside our batch: track it, keep buffering.
                if (msg.command == "BATCH" && msg.params.firstOrNull()?.startsWith("+") == true) {
                    val ref = msg.params[0].substring(1)
                    refToLabel[ref] = label
                    pending.openNested.add(ref)
                }
                pending.buffered.add(msg)
                return true
            }
        }

        // 2) Closing a batch: `BATCH -ref`.
        if (msg.command == "BATCH" && msg.params.firstOrNull()?.startsWith("-") == true) {
            val ref = msg.params[0].substring(1)
            val label = refToLabel[ref] ?: return false
            val pending = byLabel[label] ?: return false
            if (pending.openNested.remove(ref)) {
                // Nested close: still buffer the close marker, stay open.
                pending.buffered.add(msg)
                refToLabel.remove(ref)
                return true
            }
            if (pending.batchRef == ref) {
                // Root batch closed -> complete.
                refToLabel.remove(ref)
                byLabel.remove(label)
                pending.deferred.complete(pending.buffered.toList())
                return true
            }
            return false
        }

        // 3) Direct label tag (opens a batch, or single-message / ACK / FAIL).
        val label = msg.tags["label"] ?: return false
        val pending = byLabel[label] ?: return false

        // Opening a batch under this label.
        if (msg.command == "BATCH" && msg.params.firstOrNull()?.startsWith("+") == true) {
            val ref = msg.params[0].substring(1)
            pending.batchRef = ref
            refToLabel[ref] = label
            return true
        }

        // ACK -> empty response.
        if (msg.command == "ACK") {
            byLabel.remove(label)
            pending.deferred.complete(emptyList())
            return true
        }

        // FAIL / ERR_* -> exceptional completion.
        if (msg.command == "FAIL" || isErrorNumeric(msg.command)) {
            byLabel.remove(label)
            val cmd = msg.params.getOrNull(0) ?: msg.command
            val code = if (msg.command == "FAIL") (msg.params.getOrNull(1) ?: "FAIL") else msg.command
            val text = msg.params.lastOrNull().orEmpty()
            pending.deferred.completeExceptionally(IrcCommandException(cmd, code, text))
            return true
        }

        // Single-message response.
        byLabel.remove(label)
        pending.deferred.complete(listOf(msg))
        return true
    }

    /** Fail every outstanding request (connection lost / stopped). */
    fun failAll(cause: Throwable) {
        for (p in byLabel.values) {
            if (!p.deferred.isCompleted) p.deferred.completeExceptionally(cause)
        }
        byLabel.clear()
        refToLabel.clear()
    }

    private fun isErrorNumeric(command: String): Boolean =
        command.length == 3 && command.all { it.isDigit() } && command[0] == '4'
}
