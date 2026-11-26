package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import io.github.trevarj.motd.irc.transport.IrcTransport
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicLong

/** Raised when a labeled command completes with a FAIL / ERR_* numeric. */
class IrcCommandException(
    val ircCommand: String,
    val code: String,
    val text: String,
) : Exception("$ircCommand failed ($code): $text")

/** Raised when an IRC request receives no completing response within the timeout. */
class IrcTimeoutException(
    val label: String,
) : Exception("IRC response timed out: $label")

/** Raised when a command awaiting an IRC response loses its connection. */
class IrcDisconnectedException(
    val ircCommand: String,
    val reason: String?,
) : Exception("$ircCommand disconnected${reason?.let { ": $it" }.orEmpty()}")

/** Raised when a completed IRC response violates the protocol shape required by its command. */
class IrcProtocolException(
    val ircCommand: String,
    detail: String,
) : Exception("$ircCommand returned an invalid response: $detail")

/** Conservative IRCv3 label subset: opaque ASCII, non-empty, and bounded before wire escaping. */
internal fun requireValidChatLabel(label: String) {
    require(label.length in 1..64 && label.all { it.isLetterOrDigit() || it in "-_." }) {
        "invalid chat label"
    }
}

/**
 * Correlates labeled requests with their responses (IRCv3 `labeled-response`).
 *
 * A single [IrcClient] connection owns one correlator. [next] hands out monotonic
 * `motd-<n>` labels. [register] parks a [CompletableDeferred] keyed by label; the read
 * loop feeds every inbound message through [route] which completes the deferred once the
 * response is fully assembled.
 *
 * Completion rules:
 *  - a labelled message that does NOT open a batch  → single-message response.
 *  - `BATCH +ref <type> ...` carrying the label     → buffer every `batch=ref` line
 *    (nested batches reassembled recursively) until `BATCH -ref`, complete with the list.
 *  - `ACK` carrying the label                        → complete with an empty list.
 *  - `FAIL` / `ERR_*` carrying the label             → complete exceptionally.
 */
internal data class CorrelatedResponse(
    val messages: List<IrcMessage>,
    val rootBatch: IrcMessage?,
)

internal class LabelCorrelator(
    private val maxBufferedMessages: Int = MAX_BUFFERED_MESSAGES,
) {
    init {
        require(maxBufferedMessages > 0)
    }

    private val counter = AtomicLong(0)

    private class Pending(
        val ircCommand: String,
        val deferred: CompletableDeferred<CorrelatedResponse>,
        val accepts: ((IrcMessage) -> Boolean)?,
        val consumer: ((IrcMessage) -> Unit)?,
    ) {
        // Non-null once this label opened a batch; collects everything under it.
        var batchRef: String? = null
        var rootBatch: IrcMessage? = null
        val buffered = mutableListOf<IrcMessage>()

        // Nested batch refs opened under the root batch (so we know when we're back at root).
        val openNested = mutableSetOf<String>()
        val nestedParents = mutableMapOf<String, String>()
    }

    // label -> pending request
    private val byLabel = HashMap<String, Pending>()

    // batchRef -> label, so batch-tagged lines route to the right pending request
    private val refToLabel = HashMap<String, String>()

    fun next(): String = "motd-${counter.incrementAndGet()}"

    @Synchronized
    fun register(
        label: String,
        ircCommand: String,
        deferred: CompletableDeferred<CorrelatedResponse>,
        accepts: ((IrcMessage) -> Boolean)? = null,
        consumer: ((IrcMessage) -> Unit)? = null,
    ) {
        check(label !in byLabel) { "duplicate label: $label" }
        byLabel[label] = Pending(ircCommand, deferred, accepts, consumer)
    }

    /** Remove exactly this registration and every batch-ref alias it acquired. */
    @Synchronized
    fun unregister(
        label: String,
        deferred: CompletableDeferred<CorrelatedResponse>,
    ) {
        val pending = byLabel[label]
        if (pending == null) {
            // Labels are monotonic and never reused, so any remaining alias is stale.
            refToLabel.entries.removeAll { it.value == label }
            return
        }
        if (pending.deferred !== deferred) return
        removePending(label, pending)
    }

    /**
     * Route one inbound message. Returns true if the message belonged to a labeled request
     * (and was consumed), false if the caller should process it normally.
     */
    @Synchronized
    fun route(msg: IrcMessage): Boolean {
        // 1) Route closes by the ref being closed before considering their optional batch tag.
        if (msg.command == "BATCH" && msg.params.firstOrNull()?.startsWith("-") == true) {
            val ref = msg.params[0].substring(1)
            val label = refToLabel[ref] ?: return false
            val pending =
                byLabel[label] ?: run {
                    refToLabel.remove(ref)
                    return false
                }
            if (ref in pending.openNested) {
                if (pending.nestedParents.values.any { it == ref }) {
                    failPending(label, pending, "nested batch closed before its child batch")
                    return true
                }
                pending.openNested.remove(ref)
                pending.nestedParents.remove(ref)
                // Nested close: still buffer the close marker, stay open.
                if (!buffer(label, pending, msg)) return true
                refToLabel.remove(ref)
                return true
            }
            if (pending.batchRef == ref) {
                if (pending.openNested.isNotEmpty()) {
                    failPending(label, pending, "root batch closed before all nested batches")
                    return true
                }
                val response = CorrelatedResponse(pending.buffered.toList(), pending.rootBatch)
                removePending(label, pending)
                pending.deferred.complete(response)
                return true
            }
            return false
        }

        // 2) Message tagged with a batch ref we own -> buffer under that request.
        val batchTag = msg.tags["batch"]
        if (batchTag != null) {
            val label = refToLabel[batchTag]
            if (label != null) {
                val pending =
                    byLabel[label] ?: run {
                        refToLabel.remove(batchTag)
                        return false
                    }
                // A BATCH command inside the root must be a valid nested opening; closes were
                // handled above by the ref they close.
                if (msg.command == "BATCH") {
                    if (msg.params.firstOrNull()?.startsWith("+") != true) {
                        failPending(label, pending, "batch content contained malformed framing")
                        return true
                    }
                    val ref = msg.params[0].substring(1)
                    if (ref.isEmpty() || ref == pending.batchRef || ref in pending.openNested || ref in refToLabel) {
                        failPending(label, pending, "nested batch reused an open or empty reference")
                        return true
                    }
                    refToLabel[ref] = label
                    pending.openNested.add(ref)
                    pending.nestedParents[ref] = batchTag
                } else if (pending.accepts?.invoke(msg) == false) {
                    return false
                }
                buffer(label, pending, msg)
                return true
            }
        }

        // 3) Direct label tag (opens a batch, or single-message / ACK / FAIL).
        val label = msg.tags["label"] ?: return false
        val pending = byLabel[label] ?: return false

        // Opening a batch under this label.
        if (msg.command == "BATCH" && msg.params.firstOrNull()?.startsWith("+") == true) {
            val ref = msg.params[0].substring(1)
            if (ref.isEmpty() || pending.batchRef != null || ref in refToLabel) {
                failPending(label, pending, "root batch reused an open or empty reference")
                return true
            }
            pending.batchRef = ref
            pending.rootBatch = msg
            refToLabel[ref] = label
            return true
        }

        // ACK -> empty response.
        if (msg.command == "ACK") {
            removePending(label, pending)
            pending.deferred.complete(CorrelatedResponse(emptyList(), rootBatch = null))
            return true
        }

        if (pending.accepts?.invoke(msg) == false) return false

        // FAIL / ERR_* -> exceptional completion.
        if (pending.accepts == null && (msg.command == "FAIL" || isErrorNumeric(msg.command))) {
            removePending(label, pending)
            val cmd = msg.params.getOrNull(0) ?: msg.command
            val code = if (msg.command == "FAIL") (msg.params.getOrNull(1) ?: "FAIL") else msg.command
            val text = msg.params.lastOrNull().orEmpty()
            pending.deferred.completeExceptionally(IrcCommandException(cmd, code, text))
            return true
        }

        // Single-message response.
        removePending(label, pending)
        pending.consumer?.invoke(msg)
        pending.deferred.complete(
            CorrelatedResponse(if (pending.consumer == null) listOf(msg) else emptyList(), rootBatch = null),
        )
        return true
    }

    /** Fail every outstanding request (connection lost / stopped). */
    @Synchronized
    fun failAll(cause: Throwable) {
        for (p in byLabel.values) {
            if (!p.deferred.isCompleted) p.deferred.completeExceptionally(cause)
        }
        byLabel.clear()
        refToLabel.clear()
    }

    @Synchronized
    fun failAllDisconnected(reason: String?) {
        for (pending in byLabel.values) {
            if (!pending.deferred.isCompleted) {
                pending.deferred.completeExceptionally(
                    IrcDisconnectedException(pending.ircCommand, reason),
                )
            }
        }
        byLabel.clear()
        refToLabel.clear()
    }

    private fun buffer(
        label: String,
        pending: Pending,
        message: IrcMessage,
    ): Boolean {
        pending.consumer?.let {
            it(message)
            return true
        }
        if (pending.buffered.size >= maxBufferedMessages) {
            failPending(label, pending, "response exceeded $maxBufferedMessages buffered messages")
            return false
        }
        pending.buffered += message
        return true
    }

    private fun removePending(
        label: String,
        pending: Pending,
    ) {
        if (byLabel[label] === pending) byLabel.remove(label)
        refToLabel.entries.removeAll { it.value == label }
        pending.openNested.clear()
        pending.nestedParents.clear()
    }

    private fun failPending(
        label: String,
        pending: Pending,
        detail: String,
    ) {
        removePending(label, pending)
        pending.deferred.completeExceptionally(IrcProtocolException(pending.ircCommand, detail))
    }

    private fun isErrorNumeric(command: String): Boolean = command.length == 3 && command.all { it.isDigit() } && command[0] == '4'

    private companion object {
        const val MAX_BUFFERED_MESSAGES = 4_096
    }
}

/** Owns each target's unlabeled WHOIS on one transport, through the last wire reply. */
internal class UnlabeledWhoisCorrelator(
    val transport: IrcTransport,
) {
    private class Collector(
        val deferred: CompletableDeferred<List<IrcMessage>>,
        val messages: MutableList<IrcMessage> = mutableListOf(),
    )

    private class Pending(
        val nicks: MutableList<String>,
        val server: String?,
        val rawLabel: String?,
        var collector: Collector?,
    ) {
        val consume = collector != null
    }

    // ponytail: scan outstanding targets using today's case mapping, even if 005 changes it.
    private val pending = mutableListOf<Pending>()
    private var closed = false

    @Synchronized
    fun register(
        msg: IrcMessage,
        isupport: Isupport,
        rawLabel: String?,
        deferred: CompletableDeferred<List<IrcMessage>>?,
    ): Boolean {
        if (closed) return false
        val nicks =
            msg.params
                .getOrNull(if (msg.params.size == 1) 0 else 1)
                ?.split(',')
                ?.toMutableList() ?: return true
        val targets = nicks.mapTo(hashSetOf(), isupport::normalize)
        val server = msg.params.firstOrNull().takeIf { msg.params.size > 1 }
        // With a server parameter, 402 names the server rather than the nick.
        if (pending.any { request ->
                (server != null && request.server.equals(server, ignoreCase = true)) ||
                    request.nicks.any { isupport.normalize(it) in targets }
            }
        ) {
            return false
        }
        pending += Pending(nicks, server, rawLabel, deferred?.let { Collector(it) })
        return true
    }

    @Synchronized
    fun abandon(deferred: CompletableDeferred<List<IrcMessage>>) {
        // Cancellation or timeout only ends local collection. Keep draining until 318 or disconnect.
        pending.firstOrNull { it.collector?.deferred === deferred }?.collector = null
    }

    /** Returns null for a sheet-owned reply; raw replies keep their normal event mapping. */
    @Synchronized
    fun route(
        msg: IrcMessage,
        isupport: Isupport,
    ): IrcMessage? {
        if (pending.isEmpty() || "label" in msg.tags || "batch" in msg.tags) return msg
        val command = msg.command
        val knownWhois = command in WHOIS_REPLIES || command == "401" || command == "402"
        // Private WHOIS numerics are not standardized. Only forward plausible nick-scoped
        // informational replies for manual owners, leaving known independent commands alone.
        val numeric = if (knownWhois) null else command.takeIf { it.length == 3 }?.toIntOrNull()
        if (!knownWhois &&
            (
                msg.params.size < 3 || numeric == null || numeric !in 300..399 && numeric !in 600..999 ||
                    command in OTHER_INFORMATIONAL_REPLIES || numeric in 730..734 || numeric in 770..779
            )
        ) {
            return msg
        }
        val target = msg.params.getOrNull(1) ?: return msg
        val folded = isupport.normalize(target)
        val endTargets = if (command == "318" && ',' in folded) folded.split(',') else null
        val request =
            pending.firstOrNull {
                if (!knownWhois && it.rawLabel == null) return@firstOrNull false
                if (command == "402") {
                    it.server?.let(isupport::normalize) == folded
                } else {
                    it.nicks.any { nick ->
                        val normalized = isupport.normalize(nick)
                        normalized == folded || endTargets?.contains(normalized) == true
                    }
                }
            } ?: return msg

        // ERR_NOSUCHNICK can precede RPL_ENDOFWHOIS; release the collector, not the wire slot.
        if (command == "402") request.nicks.clear()
        if (command == "318") {
            request.nicks.removeAll {
                val normalized = isupport.normalize(it)
                normalized == folded || endTargets?.contains(normalized) == true
            }
        }
        if (request.nicks.isEmpty()) pending.remove(request)
        request.collector?.let { collector ->
            if (command == "401" || command == "402") {
                request.collector = null
                collector.deferred.completeExceptionally(IrcCommandException("WHOIS", command, msg.params.lastOrNull().orEmpty()))
            } else {
                collector.messages += msg
                if (request.nicks.isEmpty()) collector.deferred.complete(collector.messages)
            }
        }
        if (request.consume) return null
        return request.rawLabel?.let { msg.copy(tags = msg.tags + ("label" to it)) } ?: msg
    }

    @Synchronized
    fun failAll(cause: Throwable) {
        closed = true
        for (request in pending) request.collector?.deferred?.completeExceptionally(cause)
        pending.clear()
    }

    fun failAllDisconnected(reason: String?) = failAll(IrcDisconnectedException("WHOIS", reason))

    private companion object {
        val WHOIS_REPLIES =
            setOf("275", "276", "301", "307", "310", "311", "312", "313", "317", "318", "319", "320", "330", "335", "338", "344", "350", "378", "379", "671")
        val OTHER_INFORMATIONAL_REPLIES =
            setOf(
                "302",
                "303",
                "305",
                "306",
                "314",
                "315",
                "321",
                "322",
                "323",
                "324",
                "329",
                "331",
                "332",
                "333",
                "341",
                "342",
                "346",
                "347",
                "348",
                "349",
                "351",
                "352",
                "353",
                "354",
                "364",
                "365",
                "366",
                "367",
                "368",
                "369",
                "371",
                "372",
                "374",
                "375",
                "376",
                "381",
                "382",
                "391",
                "392",
                "393",
                "394",
                "395",
            )
    }
}

/**
 * Correlates one CHATHISTORY response on servers such as soju that do not advertise
 * `labeled-response`. CHATHISTORY replies are still wrapped in a batch, so the caller registers
 * before writing and this collector consumes that exact response before the normal event path can
 * mistake it for unsolicited playback.
 *
 * The owning [IrcClient] serializes unlabelled requests, because their batches carry no other
 * request identity.
 */
internal class UnlabeledChatHistoryCorrelator {
    private class Pending(
        val request: ChatHistoryRequest,
        val deferred: CompletableDeferred<CorrelatedResponse>,
    ) {
        var rootRef: String? = null
        var rootBatch: IrcMessage? = null
        val refs = mutableSetOf<String>()
        val nestedParents = mutableMapOf<String, String>()
        val buffered = mutableListOf<IrcMessage>()
    }

    private var pending: Pending? = null

    @Synchronized
    fun register(
        request: ChatHistoryRequest,
        deferred: CompletableDeferred<CorrelatedResponse>,
    ) {
        check(pending == null) { "an unlabelled CHATHISTORY request is already pending" }
        pending = Pending(request, deferred)
    }

    @Synchronized
    fun clear(deferred: CompletableDeferred<CorrelatedResponse>) {
        if (pending?.deferred === deferred) pending = null
    }

    @Synchronized
    fun failAll(cause: Throwable) {
        pending?.deferred?.completeExceptionally(cause)
        pending = null
    }

    @Synchronized
    fun failAllDisconnected(reason: String?) {
        pending?.deferred?.completeExceptionally(IrcDisconnectedException("CHATHISTORY", reason))
        pending = null
    }

    /** Returns true only for the registered request's batch or failure response. */
    @Synchronized
    fun route(msg: IrcMessage): Boolean {
        val current = pending ?: return false
        if (isHistoryFailure(msg)) {
            finishFailure(current, msg)
            return true
        }

        if (msg.command == "BATCH") {
            val refToken = msg.params.firstOrNull().orEmpty()
            when {
                refToken.startsWith("+") -> {
                    val ref = refToken.substring(1)
                    if (current.rootRef == null) {
                        if (!isExpectedHistoryBatch(current.request, msg)) return false
                        if (ref.isEmpty()) {
                            failProtocol(current, "root batch used an empty reference")
                            return true
                        }
                        current.rootRef = ref
                        current.rootBatch = msg
                        current.refs += ref
                        return true
                    }
                    if (msg.tags["batch"]?.let(current.refs::contains) == true) {
                        if (ref.isEmpty() || ref in current.refs) {
                            failProtocol(current, "nested batch reused an open or empty reference")
                            return true
                        }
                        current.refs += ref
                        current.nestedParents[ref] = msg.tags.getValue("batch")
                        current.buffered += msg
                        return true
                    }
                }

                refToken.startsWith("-") -> {
                    val ref = refToken.substring(1)
                    if (ref !in current.refs) return false
                    if (current.nestedParents.values.any { it == ref }) {
                        failProtocol(current, "nested batch closed before its child batch")
                        return true
                    }
                    if (ref != current.rootRef) current.buffered += msg
                    current.refs -= ref
                    current.nestedParents.remove(ref)
                    if (ref == current.rootRef) {
                        if (current.refs.isNotEmpty()) {
                            failProtocol(current, "root batch closed before all nested batches")
                            return true
                        }
                        pending = null
                        current.deferred.complete(
                            CorrelatedResponse(current.buffered.toList(), current.rootBatch),
                        )
                    }
                    return true
                }

                else -> {
                    if (msg.tags["batch"]?.let(current.refs::contains) == true) {
                        failProtocol(current, "batch content contained malformed framing")
                        return true
                    }
                }
            }
        }

        if (msg.tags["batch"]?.let(current.refs::contains) == true) {
            current.buffered += msg
            return true
        }
        return false
    }

    private fun isExpectedHistoryBatch(
        request: ChatHistoryRequest,
        msg: IrcMessage,
    ): Boolean {
        val type =
            msg.params
                .getOrNull(1)
                ?.lowercase()
                .orEmpty()
        if (!type.contains("chathistory")) return false
        val targets = type.contains("chathistory-targets")
        return (request.subcommand == ChatHistoryRequest.Subcommand.TARGETS) == targets
    }

    private fun isHistoryFailure(msg: IrcMessage): Boolean =
        (
            msg.command == "FAIL" &&
                msg.params.firstOrNull()?.equals("CHATHISTORY", ignoreCase = true) == true
        ) ||
            (
                msg.command.length == 3 && msg.command.all { it.isDigit() } && msg.command[0] == '4' &&
                    msg.params.any { it.equals("CHATHISTORY", ignoreCase = true) }
            )

    private fun finishFailure(
        current: Pending,
        msg: IrcMessage,
    ) {
        pending = null
        val code = if (msg.command == "FAIL") msg.params.getOrNull(1) ?: "FAIL" else msg.command
        current.deferred.completeExceptionally(
            IrcCommandException(
                ircCommand = msg.params.firstOrNull() ?: "CHATHISTORY",
                code = code,
                text = msg.params.lastOrNull().orEmpty(),
            ),
        )
    }

    private fun failProtocol(
        current: Pending,
        detail: String,
    ) {
        pending = null
        current.refs.clear()
        current.nestedParents.clear()
        current.deferred.completeExceptionally(IrcProtocolException("CHATHISTORY", detail))
    }
}

/**
 * Correlates one SEARCH response on servers such as released soju (0.10.x) that advertise
 * `soju.im/search` without `labeled-response`. The reply is still a `soju.im/search` batch, so the
 * caller registers before writing and this collector claims the next batch of that exact type
 * before the normal event path can surface its result PRIVMSGs as live traffic.
 *
 * The owning [IrcClient] serializes unlabelled SEARCH requests, because an unlabelled batch
 * carries no other request identity.
 */
internal class UnlabeledSearchCorrelator {
    private class Pending(
        val deferred: CompletableDeferred<CorrelatedResponse>,
    ) {
        var rootRef: String? = null
        var rootBatch: IrcMessage? = null
        val refs = mutableSetOf<String>()
        val nestedParents = mutableMapOf<String, String>()
        val buffered = mutableListOf<IrcMessage>()
    }

    private var pending: Pending? = null

    @Synchronized
    fun register(deferred: CompletableDeferred<CorrelatedResponse>) {
        check(pending == null) { "an unlabelled SEARCH request is already pending" }
        pending = Pending(deferred)
    }

    @Synchronized
    fun clear(deferred: CompletableDeferred<CorrelatedResponse>) {
        if (pending?.deferred === deferred) pending = null
    }

    @Synchronized
    fun failAll(cause: Throwable) {
        pending?.deferred?.completeExceptionally(cause)
        pending = null
    }

    @Synchronized
    fun failAllDisconnected(reason: String?) {
        pending?.deferred?.completeExceptionally(IrcDisconnectedException("SEARCH", reason))
        pending = null
    }

    /** Returns true only for the registered request's batch or failure response. */
    @Synchronized
    fun route(msg: IrcMessage): Boolean {
        val current = pending ?: return false
        if (isSearchFailure(msg)) {
            finishFailure(current, msg)
            return true
        }

        if (msg.command == "BATCH") {
            val refToken = msg.params.firstOrNull().orEmpty()
            when {
                refToken.startsWith("+") -> {
                    val ref = refToken.substring(1)
                    if (current.rootRef == null) {
                        // Correlate by batch type: only a `soju.im/search` root belongs to us.
                        if (!isSearchBatch(msg)) return false
                        if (ref.isEmpty()) {
                            failProtocol(current, "root batch used an empty reference")
                            return true
                        }
                        current.rootRef = ref
                        current.rootBatch = msg
                        current.refs += ref
                        return true
                    }
                    if (msg.tags["batch"]?.let(current.refs::contains) == true) {
                        if (ref.isEmpty() || ref in current.refs) {
                            failProtocol(current, "nested batch reused an open or empty reference")
                            return true
                        }
                        current.refs += ref
                        current.nestedParents[ref] = msg.tags.getValue("batch")
                        current.buffered += msg
                        return true
                    }
                }

                refToken.startsWith("-") -> {
                    val ref = refToken.substring(1)
                    if (ref !in current.refs) return false
                    if (current.nestedParents.values.any { it == ref }) {
                        failProtocol(current, "nested batch closed before its child batch")
                        return true
                    }
                    if (ref != current.rootRef) current.buffered += msg
                    current.refs -= ref
                    current.nestedParents.remove(ref)
                    if (ref == current.rootRef) {
                        if (current.refs.isNotEmpty()) {
                            failProtocol(current, "root batch closed before all nested batches")
                            return true
                        }
                        pending = null
                        current.deferred.complete(
                            CorrelatedResponse(current.buffered.toList(), current.rootBatch),
                        )
                    }
                    return true
                }

                else -> {
                    if (msg.tags["batch"]?.let(current.refs::contains) == true) {
                        failProtocol(current, "batch content contained malformed framing")
                        return true
                    }
                }
            }
        }

        if (msg.tags["batch"]?.let(current.refs::contains) == true) {
            current.buffered += msg
            return true
        }
        return false
    }

    private fun isSearchBatch(msg: IrcMessage): Boolean =
        msg.params
            .getOrNull(1)
            .orEmpty()
            .equals("soju.im/search", ignoreCase = true)

    private fun isSearchFailure(msg: IrcMessage): Boolean =
        msg.command == "FAIL" &&
            msg.params.firstOrNull()?.equals("SEARCH", ignoreCase = true) == true

    private fun finishFailure(
        current: Pending,
        msg: IrcMessage,
    ) {
        pending = null
        current.deferred.completeExceptionally(
            IrcCommandException(
                ircCommand = msg.params.firstOrNull() ?: "SEARCH",
                code = msg.params.getOrNull(1) ?: "FAIL",
                text = msg.params.lastOrNull().orEmpty(),
            ),
        )
    }

    private fun failProtocol(
        current: Pending,
        detail: String,
    ) {
        pending = null
        current.refs.clear()
        current.nestedParents.clear()
        current.deferred.completeExceptionally(IrcProtocolException("SEARCH", detail))
    }
}
