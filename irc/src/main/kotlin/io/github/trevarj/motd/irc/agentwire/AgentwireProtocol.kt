package io.github.trevarj.motd.irc.agentwire

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.Inflater

const val AGENTWIRE_TAG = "+trevarj.github.io/agentwire"
const val AGENTWIRE_TOPIC_PREFIX = "agentwire:v1;"
const val AGENTWIRE_MAX_CONTENT_BYTES = 65_536
private const val TAG_SECTION_LIMIT = 4_094
private const val MAX_ENVELOPE_BYTES = 131_072
private const val MAX_FRAGMENTS = 64
private const val MAX_CONCURRENT_FRAGMENTS = 16
private const val MAX_DECLARED_FRAGMENT_BYTES = 2 * 1024 * 1024
private const val FRAGMENT_TIMEOUT_MS = 30_000L

val AGENTWIRE_REQUIRED_CAPS =
    setOf(
        "sasl",
        "message-tags",
        "account-tag",
        "server-time",
        "batch",
        "echo-message",
        "labeled-response",
        "standard-replies",
        "draft/multiline",
        "draft/chathistory",
        "draft/event-playback",
    )

val AGENTWIRE_ACTION_KINDS =
    setOf(
        "sync.request",
        "workspace.list.request",
        "session.list.request",
        "history.request",
        "action.status.request",
        "session.create",
        "session.close",
        "session.attach",
        "session.detach",
        "session.rename",
        "session.fork",
        "session.archive",
        "session.unarchive",
        "settings.update",
        "turn.prompt",
        "turn.steer",
        "turn.cancel",
        "queue.edit",
        "queue.move",
        "queue.delete",
        "queue.clear",
        "request.respond",
        "request.skip",
    )

val AGENTWIRE_EVENT_KINDS =
    setOf(
        "agent.hello",
        "channel.snapshot",
        "binding.changed",
        "session.snapshot",
        "session.status",
        "workspace.page",
        "session.page",
        "history.begin",
        "history.chunk",
        "history.end",
        "action.accepted",
        "action.succeeded",
        "action.failed",
        "action.uncertain",
        "action.status",
        "queue.snapshot",
        "queue.item.added",
        "queue.item.updated",
        "queue.item.moved",
        "queue.item.removed",
        "user.prompt",
        "turn.started",
        "turn.completed",
        "turn.failed",
        "assistant.delta",
        "assistant.completed",
        "plan.updated",
        "tool.started",
        "tool.updated",
        "tool.completed",
        "usage.updated",
        "subagent.updated",
        "request.opened",
        "request.resolved",
        "approval.review.started",
        "approval.review.completed",
    )

data class AgentwireTopic(
    val account: String,
    /** Account allowed to publish authoritative backend events. */
    val agentAccount: String,
    val backend: String,
    val options: Map<String, String>,
    val title: String?,
)

/** Why a topic carrying the Agentwire marker still cannot activate the channel. */
enum class AgentwireTopicDefect {
    /** One or more of `account=`, `agent=`, or `backend=` is absent or empty. */
    MISSING_FIELD,

    /** A `;`-separated parameter is not a `key=value` pair. */
    MALFORMED_PARAMETER,
    DUPLICATE_PARAMETER,

    /** A percent-escape was truncated or decoded to something that is not UTF-8. */
    INVALID_ENCODING,
}

/**
 * The three outcomes of reading a channel topic, kept apart because they need different answers.
 *
 * A channel with no marker is an ordinary channel and must stay silent. A channel whose marker is
 * broken was *meant* to run an agent, and collapsing it into the same "no" is what makes a
 * mis-set topic look like a client that does nothing at all.
 */
sealed interface AgentwireTopicParse {
    /** No Agentwire marker: an ordinary channel, not a failure. */
    data object NotMarked : AgentwireTopicParse

    /** [fields] names the offending parameters so the defect is actionable, never just "invalid". */
    data class Invalid(
        val defect: AgentwireTopicDefect,
        val fields: List<String> = emptyList(),
    ) : AgentwireTopicParse

    data class Valid(
        val topic: AgentwireTopic,
    ) : AgentwireTopicParse
}

fun parseAgentwireTopicResult(topic: String): AgentwireTopicParse {
    if (!topic.startsWith(AGENTWIRE_TOPIC_PREFIX)) return AgentwireTopicParse.NotMarked
    val machine = topic.substringBefore(" | ")
    val title = topic.substringAfter(" | ", missingDelimiterValue = "").ifEmpty { null }
    val fields = LinkedHashMap<String, String>()
    for (part in machine.removePrefix(AGENTWIRE_TOPIC_PREFIX).split(';')) {
        if (part.isEmpty()) continue
        val separator = part.indexOf('=')
        if (separator <= 0) {
            return AgentwireTopicParse.Invalid(AgentwireTopicDefect.MALFORMED_PARAMETER)
        }
        val key = part.substring(0, separator)
        val raw = part.substring(separator + 1)
        val value =
            percentDecode(raw)
                ?: return AgentwireTopicParse.Invalid(AgentwireTopicDefect.INVALID_ENCODING, listOf(key))
        if (key in fields) {
            return AgentwireTopicParse.Invalid(AgentwireTopicDefect.DUPLICATE_PARAMETER, listOf(key))
        }
        fields[key] = value
    }
    // Three different questions: `backend` names the engine (codex/opencode/claude), while
    // `account` and `agent` are IRC account names, never engine names. `account` is the
    // controller authorized to issue actions; `agent` is the bot account whose messages we
    // trust as authoritative backend state ("agent=claude" would be an account literally
    // named claude). Both fields stay required even when a deployment intentionally uses one
    // account for both roles.
    //
    // All three are case-folded exactly as the bridge folds them, so the two implementations
    // cannot disagree about what a topic means. Backend is the one that bites: a topic reading
    // "backend=Claude" would otherwise activate here while suspending the bridge, and the user
    // would watch an endless sync with no visible cause. Accounts are already compared
    // case-insensitively downstream, so folding them only normalizes what gets displayed.
    val account = fields["account"].orEmpty().lowercase()
    val agentAccount = fields["agent"].orEmpty().lowercase()
    val backend = fields["backend"].orEmpty().lowercase()
    val missing =
        listOf("account" to account, "agent" to agentAccount, "backend" to backend)
            .filter { it.second.isEmpty() }
            .map { it.first }
    if (missing.isNotEmpty()) {
        return AgentwireTopicParse.Invalid(AgentwireTopicDefect.MISSING_FIELD, missing)
    }
    return AgentwireTopicParse.Valid(AgentwireTopic(account, agentAccount, backend, fields, title))
}

fun parseAgentwireTopic(topic: String): AgentwireTopic? = (parseAgentwireTopicResult(topic) as? AgentwireTopicParse.Valid)?.topic

private fun percentDecode(value: String): String? =
    runCatching {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] == '%') {
                require(index + 2 < value.length) { "truncated percent escape" }
                val high = value[index + 1].digitToIntOrNull(16) ?: error("invalid percent escape")
                val low = value[index + 2].digitToIntOrNull(16) ?: error("invalid percent escape")
                bytes.write((high shl 4) or low)
                index += 3
            } else {
                val codePoint = value.codePointAt(index)
                bytes.write(String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8))
                index += Character.charCount(codePoint)
            }
        }
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    }.getOrNull()

fun agentwireMissingCaps(caps: Set<String>): Set<String> {
    val names = caps.mapTo(HashSet()) { it.substringBefore('=') }
    return AGENTWIRE_REQUIRED_CAPS - names
}

data class AgentwireEnvelope(
    val kind: String,
    val type: String,
    val id: String,
    val at: Long,
    val instance: String,
    val epoch: String? = null,
    val device: String? = null,
    val sid: String? = null,
    val tid: String? = null,
    val iid: String? = null,
    val rid: String? = null,
    val rev: Long? = null,
    val reply: String? = null,
    val history: Boolean? = null,
    val data: JsonObject? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("v", 1)
            put("k", kind)
            put("t", type)
            put("id", id)
            put("at", at)
            put("inst", instance)
            epoch?.let { put("epoch", it) }
            device?.let { put("device", it) }
            sid?.let { put("sid", it) }
            tid?.let { put("tid", it) }
            iid?.let { put("iid", it) }
            rid?.let { put("rid", it) }
            rev?.let { put("rev", it) }
            reply?.let { put("reply", it) }
            history?.let { put("hist", it) }
            data?.let { put("data", it) }
        }
}

data class AgentwireFragment(
    val id: String,
    val of: String,
    val type: String,
    val epoch: String?,
    val sid: String?,
    val part: Int,
    val parts: Int,
    val bytes: Int,
    val sha256: String,
    val encoding: String?,
    val b64: String,
)

sealed interface AgentwireValue {
    data class Envelope(
        val value: AgentwireEnvelope,
    ) : AgentwireValue

    data class Fragment(
        val value: AgentwireFragment,
    ) : AgentwireValue
}

private val wireJson =
    Json {
        isLenient = false
        ignoreUnknownKeys = false
        explicitNulls = true
    }
private val envelopeKeys =
    setOf(
        "v",
        "k",
        "t",
        "id",
        "at",
        "inst",
        "epoch",
        "device",
        "sid",
        "tid",
        "iid",
        "rid",
        "rev",
        "reply",
        "hist",
        "data",
    )
private val fragmentKeys =
    setOf(
        "v",
        "k",
        "id",
        "of",
        "t",
        "epoch",
        "sid",
        "part",
        "parts",
        "bytes",
        "sha256",
        "encoding",
        "b64",
    )
private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

fun decodeAgentwireValue(raw: String): Result<AgentwireValue> =
    runCatching {
        val root = wireJson.parseToJsonElement(raw) as? JsonObject ?: error("tag value must be an object")
        if (root.string("k") == "fragment") {
            AgentwireValue.Fragment(validateFragment(root))
        } else {
            AgentwireValue.Envelope(validateEnvelope(root))
        }
    }

fun decodeAgentwireEnvelope(root: JsonObject): Result<AgentwireEnvelope> = runCatching { validateEnvelope(root) }

fun encodeAgentwireEnvelope(envelope: AgentwireEnvelope): String {
    validateEnvelope(envelope.toJson())
    return wireJson.encodeToString(JsonElement.serializer(), sorted(envelope.toJson()))
}

private fun validateEnvelope(root: JsonObject): AgentwireEnvelope {
    require(root.keys.all { it in envelopeKeys }) { "unknown envelope field" }
    require(root.int("v") == 1) { "unsupported protocol version" }
    val kind = root.string("k") ?: error("missing k")
    val type = root.string("t") ?: error("missing t")
    require(type == "action" || type == "event") { "invalid t" }
    require(kind in if (type == "action") AGENTWIRE_ACTION_KINDS else AGENTWIRE_EVENT_KINDS) { "invalid k for t" }
    val id = root.string("id") ?: error("missing id")
    require(id.matches(uuidPattern) && runCatching { UUID.fromString(id) }.isSuccess) { "id is not a UUID" }
    val at = root.long("at") ?: error("missing at")
    require(at >= 0) { "invalid at" }
    val inst = root.nonEmptyString("inst") ?: error("missing inst")
    val device = root.optionalNonEmptyString("device")
    if (type == "action") require(device != null) { "actions require device" }
    val rev = root.optionalLong("rev")
    require(rev == null || rev >= 0) { "invalid rev" }
    val history = root.optionalBoolean("hist")
    val data = root["data"]?.let { it as? JsonObject ?: error("data must be an object") }
    validateKnownPayload(kind, type, root, data)
    return AgentwireEnvelope(
        kind,
        type,
        id,
        at,
        inst,
        root.optionalNonEmptyString("epoch"),
        device,
        root.optionalNonEmptyString("sid"),
        root.optionalNonEmptyString("tid"),
        root.optionalNonEmptyString("iid"),
        root.optionalNonEmptyString("rid"),
        rev,
        root.optionalNonEmptyString("reply"),
        history,
        data,
    )
}

private fun validateKnownPayload(
    kind: String,
    type: String,
    root: JsonObject,
    data: JsonObject?,
) {
    when {
        type == "action" && kind == "action.status.request" -> {
            val request = requireNotNull(data) { "action.status.request requires data" }
            require(request.keys.all { it == "actionId" || it == "channel" }) { "unknown action status request field" }
            requireValidUuid(request.string("actionId"), "actionId")
            request.optionalChannel("channel")
        }

        type == "event" && kind == "action.status" -> {
            require(root.optionalNonEmptyString("reply") != null) { "action.status requires reply" }
            val receipt = requireNotNull(data) { "action.status requires data" }
            requireValidUuid(receipt.string("actionId"), "actionId")
            val status = receipt.string("status") ?: error("action.status requires status")
            if (status == "unknown") {
                require(receipt.keys == setOf("actionId", "status")) { "unknown action status cannot include receipt metadata" }
            } else {
                require(status in setOf("accepted", "succeeded", "failed", "uncertain")) { "invalid action status" }
                require(receipt.keys.all { it in setOf("actionId", "status", "kind", "channel", "receivedAt", "message") }) {
                    "unknown action status receipt field"
                }
                val receiptKind = receipt.string("kind") ?: error("known action status requires kind")
                require(receiptKind in AGENTWIRE_ACTION_KINDS) { "invalid action status kind" }
                receipt.requireChannel("channel")
                val receivedAt = receipt.long("receivedAt") ?: error("known action status requires receivedAt")
                require(receivedAt >= 0) { "invalid action status receivedAt" }
                if ("message" in receipt) {
                    val message = receipt.string("message") ?: error("message must be a string")
                    require(message.length in 1..200) { "invalid action status message" }
                }
            }
        }
    }
}

private fun requireValidUuid(
    value: String?,
    name: String,
) {
    require(value != null && value.matches(uuidPattern) && runCatching { UUID.fromString(value) }.isSuccess) { "$name is not a UUID" }
}

private fun JsonObject.optionalChannel(key: String) {
    if (key in this) requireChannel(key)
}

private fun JsonObject.requireChannel(key: String) {
    val value = string(key) ?: error("$key must be a channel name")
    require(value.length in 2..200 && value.matches(Regex("^[#&][^ ,:]+$")) && value.none { it == '\u0000' || it == '\r' || it == '\n' }) {
        "invalid channel name"
    }
}

private fun validateFragment(root: JsonObject): AgentwireFragment {
    require(root.keys.all { it in fragmentKeys }) { "unknown fragment field" }
    require(root.int("v") == 1 && root.string("k") == "fragment") { "invalid fragment header" }
    val id = root.string("id") ?: error("missing id")
    require(id.matches(uuidPattern) && runCatching { UUID.fromString(id) }.isSuccess) { "id is not a UUID" }
    val of = root.nonEmptyString("of") ?: error("missing of")
    val type = root.string("t") ?: error("missing t")
    require(type == "action" || type == "event") { "invalid t" }
    require(of in if (type == "action") AGENTWIRE_ACTION_KINDS else AGENTWIRE_EVENT_KINDS) {
        "invalid of for t"
    }
    val part = root.int("part") ?: error("missing part")
    val parts = root.int("parts") ?: error("missing parts")
    val bytes = root.int("bytes") ?: error("missing bytes")
    require(parts in 1..MAX_FRAGMENTS && part in 0 until parts) { "invalid fragment position" }
    require(bytes in 1..MAX_ENVELOPE_BYTES) { "invalid fragment byte count" }
    val sha = root.string("sha256") ?: error("missing sha256")
    require(sha.matches(Regex("[0-9a-f]{64}"))) { "invalid sha256" }
    val encoding = root.optionalNonEmptyString("encoding")
    require(encoding == null || encoding == "zlib") { "unsupported fragment encoding" }
    val b64 = root.string("b64") ?: error("missing b64")
    require(b64.isNotEmpty() && b64.matches(Regex("[A-Za-z0-9_-]+"))) { "invalid b64" }
    return AgentwireFragment(
        id,
        of,
        type,
        root.optionalNonEmptyString("epoch"),
        root.optionalNonEmptyString("sid"),
        part,
        parts,
        bytes,
        sha,
        encoding,
        b64,
    )
}

fun fragmentAgentwireEnvelope(envelope: AgentwireEnvelope): List<String> {
    val encoded = encodeAgentwireEnvelope(envelope)
    if (agentwireEscapedTagBytes(encoded) <= TAG_SECTION_LIMIT) return listOf(encoded)
    val bytes = encoded.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_ENVELOPE_BYTES) { "envelope exceeds 128 KiB" }
    val digest = bytes.sha256()
    val compressed = bytes.deflate()
    var chunkSize = directValueBudget() - 360
    while (chunkSize > 0) {
        val rawParts = fragmentCount(bytes, chunkSize)
        val compressedParts = fragmentCount(compressed, chunkSize)
        val encoding = "zlib".takeIf { compressedParts < rawParts }
        val payload = if (encoding == null) bytes else compressed
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val chunks = b64.chunked(chunkSize)
        require(chunks.size <= MAX_FRAGMENTS) { "envelope requires more than 64 fragments" }
        val values =
            chunks.mapIndexed { index, chunk ->
                val fragment =
                    buildJsonObject {
                        put("v", 1)
                        put("k", "fragment")
                        put("id", envelope.id)
                        put("of", envelope.kind)
                        put("t", envelope.type)
                        envelope.epoch?.let { put("epoch", it) }
                        envelope.sid?.let { put("sid", it) }
                        put("part", index)
                        put("parts", chunks.size)
                        put("bytes", bytes.size)
                        put("sha256", digest)
                        encoding?.let { put("encoding", it) }
                        put("b64", chunk)
                    }
                wireJson.encodeToString(JsonElement.serializer(), sorted(fragment))
            }
        if (values.all { agentwireEscapedTagBytes(it) <= TAG_SECTION_LIMIT }) return values
        chunkSize -= 64
    }
    error("unable to fit fragment tag")
}

class AgentwireReassembler(
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Assembly(
        val firstAt: Long,
        val metadata: List<Any?>,
        val declaredBytes: Int,
        val parts: Array<String?>,
    )

    private val assemblies = LinkedHashMap<String, Assembly>()

    fun clear() = assemblies.clear()

    fun accept(fragment: AgentwireFragment): Result<AgentwireEnvelope?> =
        runCatching {
            expire()
            val metadata =
                listOf(
                    fragment.parts,
                    fragment.bytes,
                    fragment.sha256,
                    fragment.of,
                    fragment.type,
                    fragment.epoch,
                    fragment.sid,
                    fragment.encoding,
                )
            val current = assemblies[fragment.id]
            val assembly =
                current ?: run {
                    require(assemblies.size < MAX_CONCURRENT_FRAGMENTS) { "too many fragment assemblies" }
                    require(assemblies.values.sumOf { it.declaredBytes } + fragment.bytes <= MAX_DECLARED_FRAGMENT_BYTES) {
                        "fragment declarations exceed aggregate limit"
                    }
                    Assembly(now(), metadata, fragment.bytes, arrayOfNulls(fragment.parts)).also {
                        assemblies[fragment.id] = it
                    }
                }
            if (assembly.metadata != metadata) {
                assemblies.remove(fragment.id)
                error("conflicting fragment metadata")
            }
            val prior = assembly.parts[fragment.part]
            if (prior != null && prior != fragment.b64) {
                assemblies.remove(fragment.id)
                error("conflicting duplicate fragment")
            }
            assembly.parts[fragment.part] = fragment.b64
            if (assembly.parts.any { it == null }) return@runCatching null
            assemblies.remove(fragment.id)
            val packed = Base64.getUrlDecoder().decode(assembly.parts.joinToString(""))
            val decoded = if (fragment.encoding == "zlib") packed.inflate(fragment.bytes) else packed
            require(decoded.size == fragment.bytes) { "fragment byte count mismatch" }
            require(decoded.sha256() == fragment.sha256) { "fragment digest mismatch" }
            val text =
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(decoded))
                    .toString()
            val envelope =
                (decodeAgentwireValue(text).getOrThrow() as? AgentwireValue.Envelope)?.value
                    ?: error("fragment payload is not an envelope")
            require(envelope.id == fragment.id && envelope.kind == fragment.of && envelope.type == fragment.type) {
                "decoded envelope metadata mismatch"
            }
            envelope
        }

    /** Returns true when incomplete envelopes were discarded after their bounded assembly window. */
    fun expire(): Boolean {
        val cutoff = now() - FRAGMENT_TIMEOUT_MS
        return assemblies.entries.removeAll { it.value.firstAt < cutoff }
    }
}

private fun fragmentCount(
    payload: ByteArray,
    chunkSize: Int,
): Int {
    val encodedBytes =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encode(payload)
            .size
    return maxOf(1, (encodedBytes + chunkSize - 1) / chunkSize)
}

private fun ByteArray.deflate(): ByteArray {
    val deflater = Deflater(Deflater.BEST_SPEED)
    return try {
        deflater.setInput(this)
        deflater.finish()
        val output = ByteArrayOutputStream(size)
        val buffer = ByteArray(8_192)
        while (!deflater.finished()) {
            output.write(buffer, 0, deflater.deflate(buffer))
        }
        output.toByteArray()
    } finally {
        deflater.end()
    }
}

private fun ByteArray.inflate(expectedBytes: Int): ByteArray {
    val inflater = Inflater()
    return try {
        inflater.setInput(this)
        val output = ByteArrayOutputStream(expectedBytes)
        val buffer = ByteArray(8_192)
        var total = 0
        while (!inflater.finished()) {
            val count = inflater.inflate(buffer)
            if (count == 0) {
                require(inflater.finished()) { "invalid compressed fragment stream" }
                continue
            }
            total += count
            require(total <= expectedBytes) { "compressed fragment exceeds declared size" }
            output.write(buffer, 0, count)
        }
        require(inflater.remaining == 0 && total == expectedBytes) { "compressed fragment size mismatch" }
        output.toByteArray()
    } finally {
        inflater.end()
    }
}

private fun directValueBudget(): Int = TAG_SECTION_LIMIT - AGENTWIRE_TAG.toByteArray().size - 1

private fun agentwireEscapedTagBytes(value: String): Int = AGENTWIRE_TAG.toByteArray(Charsets.UTF_8).size + 1 + escapeTag(value).toByteArray(Charsets.UTF_8).size

private fun escapeTag(value: String): String =
    buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    ';' -> "\\:"
                    ' ' -> "\\s"
                    '\\' -> "\\\\"
                    '\r' -> "\\r"
                    '\n' -> "\\n"
                    else -> character
                },
            )
        }
    }

private fun ByteArray.sha256(): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { "%02x".format(it) }

private fun sorted(element: JsonElement): JsonElement =
    when (element) {
        is JsonObject -> {
            JsonObject(
                element.entries
                    .sortedBy(Map.Entry<String, JsonElement>::key)
                    .associate { it.key to sorted(it.value) },
            )
        }

        is JsonArray -> {
            JsonArray(element.map(::sorted))
        }

        else -> {
            element
        }
    }

private fun JsonObject.string(key: String): String? =
    (get(key) as? JsonPrimitive)
        ?.takeUnless { it is JsonNull || !it.isString }
        ?.contentOrNull

private fun JsonObject.nonEmptyString(key: String): String? = string(key)?.takeIf(String::isNotEmpty)

private fun JsonObject.optionalNonEmptyString(key: String): String? =
    if (key !in this) {
        null
    } else {
        nonEmptyString(key) ?: error("$key must be a non-empty string")
    }

private fun JsonObject.int(key: String): Int? = number(key)?.let { runCatching { it.intValueExact() }.getOrNull() }

private fun JsonObject.long(key: String): Long? = number(key)?.let { runCatching { it.longValueExact() }.getOrNull() }

private fun JsonObject.number(key: String): BigDecimal? =
    (get(key) as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.contentOrNull
        ?.let { runCatching { BigDecimal(it) }.getOrNull() }

private fun JsonObject.optionalLong(key: String): Long? =
    if (key !in this) {
        null
    } else {
        long(key) ?: error("$key must be an integer")
    }

private fun JsonObject.optionalBoolean(key: String): Boolean? =
    if (key !in this) {
        null
    } else {
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: error("$key must be boolean")
    }
