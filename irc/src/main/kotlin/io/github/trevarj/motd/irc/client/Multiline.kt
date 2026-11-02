package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.format.splitIrcFormattedLinesUtf8
import io.github.trevarj.motd.irc.proto.IrcMessage

data class MultilineLimits(
    val maxBytes: Int,
    val maxLines: Int?,
)

sealed interface MultilineSendPlan {
    data class Single(
        val message: IrcMessage,
    ) : MultilineSendPlan

    data class Batch(
        val ref: String,
        val opening: IrcMessage,
        val components: List<IrcMessage>,
        val closing: IrcMessage,
    ) : MultilineSendPlan
}

internal fun multilineLimits(caps: Set<String>): MultilineLimits? {
    val value =
        caps.firstNotNullOfOrNull { cap ->
            cap
                .takeIf { it == MULTILINE_CAP || it.startsWith("$MULTILINE_CAP=") }
                ?.substringAfter('=', missingDelimiterValue = "")
        } ?: return null
    if (value.isEmpty()) return null
    val tokens = value.split(',').filter(String::isNotEmpty)
    val pairs =
        tokens
            .mapNotNull { token ->
                val key = token.substringBefore('=')
                val rawValue = token.substringAfter('=', missingDelimiterValue = "")
                key.takeIf(String::isNotEmpty)?.let { it to rawValue }
            }.toMap()
    val maxBytes = pairs["max-bytes"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    val maxLines = pairs["max-lines"]?.toIntOrNull()?.takeIf { it > 0 }
    return MultilineLimits(maxBytes, maxLines)
}

internal fun normalizeMultilineText(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

internal fun needsMultiline(text: String): Boolean = normalizeMultilineText(text).contains('\n')

internal fun planChatMessage(
    target: String,
    text: String,
    replyToMsgid: String?,
    label: String?,
    multilineLimits: MultilineLimits?,
    maxComponentBytes: Int = 400,
    forceLegacy: Boolean = false,
    protocolTags: Map<String, String> = emptyMap(),
): MultilineSendPlan? {
    val baseTags =
        buildMap {
            putAll(protocolTags)
            if (replyToMsgid != null) put("+reply", replyToMsgid)
            if (label != null) put("label", label)
        }
    val normalized = normalizeMultilineText(text)
    val needsBatch = normalized.contains('\n') || normalized.toByteArray(Charsets.UTF_8).size > maxComponentBytes
    if (forceLegacy || multilineLimits == null || !needsBatch) {
        return MultilineSendPlan.Single(
            IrcMessage(tags = baseTags, command = "PRIVMSG", params = listOf(target, text)),
        )
    }

    if (normalized.lines().all(String::isEmpty)) return null
    val components =
        splitIrcFormattedLinesUtf8(normalized, maxComponentBytes).flatMap { line ->
            line.mapIndexed { index, fragment -> LineFragment(text = fragment, concat = index > 0) }
        }
    if (components.isEmpty() || components.all { it.text.isEmpty() }) return null
    val combinedBytes =
        components.foldIndexed(0) { index, bytes, fragment ->
            bytes + fragment.text.toByteArray(Charsets.UTF_8).size +
                if (index > 0 && !fragment.concat) 1 else 0
        }
    if (combinedBytes > multilineLimits.maxBytes) return null
    multilineLimits.maxLines?.let { limit ->
        if (components.size > limit) return null
    }

    val ref = label ?: "motd-${System.currentTimeMillis()}"
    if (!ref.all { it.isLetterOrDigit() || it == '-' }) return null
    val opening =
        IrcMessage(
            tags = baseTags,
            command = "BATCH",
            params = listOf("+$ref", MULTILINE_CAP, target),
        )
    val wireComponents =
        components.map { fragment ->
            val tags =
                buildMap {
                    put("batch", ref)
                    if (fragment.concat) put(MULTILINE_CONCAT_TAG, "")
                }
            IrcMessage(tags = tags, command = "PRIVMSG", params = listOf(target, fragment.text))
        }
    return MultilineSendPlan.Batch(
        ref = ref,
        opening = opening,
        components = wireComponents,
        closing = IrcMessage(command = "BATCH", params = listOf("-$ref")),
    )
}

internal fun splitUtf8PreservingWhitespace(
    text: String,
    maxBytes: Int,
): List<String> {
    require(maxBytes > 0) { "maxBytes must be positive" }
    if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return listOf(text)

    val out = ArrayList<String>()
    var start = 0
    while (start < text.length) {
        var end = start
        var bytes = 0
        var lastSpaceEnd = -1
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val codePointLength = Character.charCount(codePoint)
            val codePointBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + codePointBytes > maxBytes) break
            bytes += codePointBytes
            end += codePointLength
            if (codePoint == ' '.code) lastSpaceEnd = end
        }
        require(end > start) { "maxBytes is smaller than one UTF-8 code point" }
        val split = if (end < text.length && lastSpaceEnd > start) lastSpaceEnd else end
        out += text.substring(start, split)
        start = split
    }
    return out
}

private data class LineFragment(
    val text: String,
    val concat: Boolean,
)

internal const val MULTILINE_CAP = "draft/multiline"
internal const val MULTILINE_CONCAT_TAG = "draft/multiline-concat"

internal val MULTILINE_REJECTION_CODES =
    setOf(
        "MULTILINE_MAX_BYTES",
        "MULTILINE_MAX_LINES",
        "MULTILINE_INVALID_TARGET",
        "MULTILINE_INVALID",
    )
