package io.github.trevarj.motd.irc.proto

/** Parsed IRC prefix (`:nick!user@host`). */
data class Prefix(val nick: String, val user: String? = null, val host: String? = null)

/** One IRC protocol message. Tags are unescaped values; empty map when absent. */
data class IrcMessage(
    val tags: Map<String, String> = emptyMap(),
    val source: Prefix? = null,
    val command: String,
    val params: List<String> = emptyList(),
) {
    companion object {
        /** Max bytes for the non-tag portion including CRLF. */
        private const val MESSAGE_LIMIT = 512

        /** Max bytes for the tag section (excluding leading '@' and trailing space). */
        private const val TAG_LIMIT = 8191

        /** Parse a single line WITHOUT trailing CRLF. Throws IrcParseException on garbage. */
        fun parse(line: String): IrcMessage {
            var rest = line
            // Tolerate a stray trailing CRLF even though the transport should strip it.
            while (rest.endsWith("\r") || rest.endsWith("\n")) {
                rest = rest.dropLast(1)
            }

            var tags: Map<String, String> = emptyMap()
            if (rest.startsWith("@")) {
                val sp = rest.indexOf(' ')
                if (sp < 0) throw IrcParseException("tags without command", line)
                tags = parseTags(rest.substring(1, sp), line)
                rest = rest.substring(sp + 1).trimStart(' ')
            }

            var source: Prefix? = null
            if (rest.startsWith(":")) {
                val sp = rest.indexOf(' ')
                if (sp < 0) throw IrcParseException("source without command", line)
                source = parsePrefix(rest.substring(1, sp))
                rest = rest.substring(sp + 1).trimStart(' ')
            }

            if (rest.isEmpty()) throw IrcParseException("missing command", line)

            // Command: up to the next space (or end).
            val cmdEnd = rest.indexOf(' ')
            val rawCommand = if (cmdEnd < 0) rest else rest.substring(0, cmdEnd)
            if (rawCommand.isEmpty()) throw IrcParseException("empty command", line)
            // Numerics stay 3-digit; letter commands are uppercased.
            val command = if (rawCommand.all { it.isDigit() }) rawCommand else rawCommand.uppercase()

            val params = if (cmdEnd < 0) {
                emptyList()
            } else {
                parseParams(rest.substring(cmdEnd + 1))
            }

            return IrcMessage(tags = tags, source = source, command = command, params = params)
        }

        private fun parsePrefix(raw: String): Prefix {
            // nick[!user][@host] | servername (stored in nick)
            var s = raw
            var host: String? = null
            var user: String? = null
            val at = s.indexOf('@')
            if (at >= 0) {
                host = s.substring(at + 1)
                s = s.substring(0, at)
            }
            val bang = s.indexOf('!')
            if (bang >= 0) {
                user = s.substring(bang + 1)
                s = s.substring(0, bang)
            }
            return Prefix(nick = s, user = user, host = host)
        }

        private fun parseParams(raw: String): List<String> {
            val params = mutableListOf<String>()
            var s = raw
            while (s.isNotEmpty()) {
                if (s[0] == ':') {
                    // Trailing param: everything after ':' verbatim (may be empty, may hold spaces).
                    params.add(s.substring(1))
                    return params
                }
                val sp = s.indexOf(' ')
                if (sp < 0) {
                    params.add(s)
                    return params
                }
                if (sp > 0) params.add(s.substring(0, sp))
                s = s.substring(sp + 1)
                // Collapse runs of spaces between middle params.
                s = s.trimStart(' ')
            }
            return params
        }

        private fun parseTags(raw: String, line: String): Map<String, String> {
            if (raw.isEmpty()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (part in raw.split(';')) {
                if (part.isEmpty()) continue
                val eq = part.indexOf('=')
                if (eq < 0) {
                    out[part] = ""
                } else {
                    val key = part.substring(0, eq)
                    out[key] = unescapeTagValue(part.substring(eq + 1))
                }
            }
            return out
        }

        /** Unescape a tag value per the IRCv3 escape table. */
        private fun unescapeTagValue(value: String): String {
            if (value.indexOf('\\') < 0) return value
            val sb = StringBuilder(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                if (c != '\\') {
                    sb.append(c)
                    i++
                    continue
                }
                // Trailing lone backslash is dropped.
                if (i == value.length - 1) break
                when (val next = value[i + 1]) {
                    ':' -> sb.append(';')
                    's' -> sb.append(' ')
                    '\\' -> sb.append('\\')
                    'r' -> sb.append('\r')
                    'n' -> sb.append('\n')
                    else -> sb.append(next) // drop the backslash
                }
                i += 2
            }
            return sb.toString()
        }

        /** Escape a tag value per the IRCv3 escape table. */
        private fun escapeTagValue(value: String): String {
            val sb = StringBuilder(value.length)
            for (c in value) {
                when (c) {
                    ';' -> sb.append("\\:")
                    ' ' -> sb.append("\\s")
                    '\\' -> sb.append("\\\\")
                    '\r' -> sb.append("\\r")
                    '\n' -> sb.append("\\n")
                    else -> sb.append(c)
                }
            }
            return sb.toString()
        }

        internal fun escapeTagValueForTest(value: String) = escapeTagValue(value)

        internal fun unescapeTagValueForTest(value: String) = unescapeTagValue(value)
    }

    /** Serialize to wire format WITHOUT trailing CRLF. Escapes tag values. */
    fun serialize(): String {
        // Reject embedded CR/LF in the command/params: a bare newline would split the wire stream
        // and let the tail be parsed as a separate command (line injection). Callers must split
        // multiline bodies into separate messages first. Tag values are escaped, so they are safe.
        if (command.any { it == '\r' || it == '\n' }) {
            throw IllegalArgumentException("command contains CR/LF")
        }
        for (param in params) {
            if (param.any { it == '\r' || it == '\n' }) {
                throw IllegalArgumentException("param contains CR/LF (split multiline messages first)")
            }
        }

        val sb = StringBuilder()

        if (tags.isNotEmpty()) {
            sb.append('@')
            var first = true
            for ((key, value) in tags) {
                if (!first) sb.append(';')
                first = false
                sb.append(key)
                if (value.isNotEmpty()) {
                    sb.append('=')
                    sb.append(escapeTagValue(value))
                }
            }
            sb.append(' ')
        }

        val tagSectionBytes = if (sb.isEmpty()) 0 else {
            // bytes between '@' and the trailing space, exclusive of both delimiters
            sb.substring(1, sb.length - 1).toByteArray(Charsets.UTF_8).size
        }
        if (tagSectionBytes > TAG_LIMIT) {
            throw IllegalArgumentException("tag section exceeds $TAG_LIMIT bytes: $tagSectionBytes")
        }

        val messageStart = sb.length

        source?.let {
            sb.append(':')
            sb.append(it.nick)
            it.user?.let { u -> sb.append('!').append(u) }
            it.host?.let { h -> sb.append('@').append(h) }
            sb.append(' ')
        }

        sb.append(command)

        for ((index, param) in params.withIndex()) {
            sb.append(' ')
            val isLast = index == params.size - 1
            // A param needs trailing form if it is empty, contains a space, or starts with ':'.
            if (isLast && (param.isEmpty() || param.contains(' ') || param.startsWith(':'))) {
                sb.append(':').append(param)
            } else {
                sb.append(param)
            }
        }

        // Non-tag portion including CRLF must fit within MESSAGE_LIMIT.
        val messageBytes = sb.substring(messageStart).toByteArray(Charsets.UTF_8).size + 2 // + CRLF
        if (messageBytes > MESSAGE_LIMIT) {
            throw IllegalArgumentException("message exceeds $MESSAGE_LIMIT bytes (incl. CRLF): $messageBytes")
        }

        return sb.toString()
    }
}

class IrcParseException(message: String, val line: String) : Exception(message)

/** RPL_ISUPPORT (005) accumulator. */
class Isupport {
    private val tokens = LinkedHashMap<String, String>()

    /** params from a 005, minus nick + trailing text */
    fun update(tokens: List<String>) {
        for (token in tokens) {
            if (token.isEmpty()) continue
            if (token.startsWith("-")) {
                // Negation: `-KEY` removes a previously advertised token.
                this.tokens.remove(token.substring(1).uppercase())
                continue
            }
            val eq = token.indexOf('=')
            if (eq < 0) {
                this.tokens[token.uppercase()] = ""
            } else {
                this.tokens[token.substring(0, eq).uppercase()] = unescapeIsupportValue(token.substring(eq + 1))
            }
        }
    }

    /** e.g. get("CHATHISTORY") -> "1000" */
    operator fun get(key: String): String? = tokens[key.uppercase()]

    /** default "rfc1459" */
    val caseMapping: String
        get() = tokens["CASEMAPPING"]?.lowercase() ?: "rfc1459"

    /** default "#&" */
    val chanTypes: String
        get() = tokens["CHANTYPES"] ?: "#&"

    /** mode->prefix pairs, e.g. (o,'@'), (v,'+') */
    val prefixModes: List<Pair<Char, Char>>
        get() {
            // PREFIX=(ov)@+  -> [(o,@),(v,+)]
            val raw = tokens["PREFIX"] ?: return listOf('o' to '@', 'v' to '+')
            val close = raw.indexOf(')')
            if (!raw.startsWith("(") || close < 0) return emptyList()
            val modes = raw.substring(1, close)
            val prefixes = raw.substring(close + 1)
            val n = minOf(modes.length, prefixes.length)
            return (0 until n).map { modes[it] to prefixes[it] }
        }

    /** Case-normalize a nick/channel per CASEMAPPING for map keys and comparisons. */
    fun normalize(name: String): String {
        val sb = StringBuilder(name.length)
        val rfc = caseMapping == "rfc1459" || caseMapping == "rfc1459-strict"
        for (c in name) {
            sb.append(
                when {
                    c in 'A'..'Z' -> c + 32
                    rfc && c == '[' -> '{'
                    rfc && c == ']' -> '}'
                    rfc && c == '\\' -> '|'
                    rfc && c == '~' -> '^'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    /** ISUPPORT values use the same backslash escaping as tags for a few characters. */
    private fun unescapeIsupportValue(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 3 < value.length && value[i + 1] == 'x') {
                val hex = value.substring(i + 2, i + 4)
                val code = hex.toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 4
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
