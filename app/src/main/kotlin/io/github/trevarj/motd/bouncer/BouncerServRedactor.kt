package io.github.trevarj.motd.bouncer

private const val REDACTED = "<redacted>"

fun redactBouncerServCommand(raw: String): String {
    val tokens = parsePosixWords(raw) ?: return raw.substringBefore(' ').ifBlank { "command" } + " $REDACTED"
    if (tokens.isEmpty()) return REDACTED
    val path = tokens.take(2).joinToString(" ").lowercase()
    val knownSafePath = path in setOf(
        "help", "network status", "network delete", "channel status", "channel create",
        "channel update", "channel delete", "user update", "sasl status", "sasl reset",
        "certfp generate", "certfp fingerprint", "user status", "user delete",
        "server status", "server debug",
    )
    if (path == "network quote" || path == "server notice" || path == "user run") {
        return tokens.take(2).joinToString(" ") + " $REDACTED"
    }
    if (!knownSafePath && path !in setOf("network create", "network update", "sasl set-plain", "user create")) {
        return tokens.first() + if (tokens.size > 1) " $REDACTED" else ""
    }

    val out = tokens.toMutableList()
    when (path) {
        "sasl set-plain" -> if (out.isNotEmpty()) out[out.lastIndex] = REDACTED
        "user delete" -> if (out.size > 3) out[3] = REDACTED
        "user create", "user update", "network create", "network update" -> {
            val secretFlags = if (path.startsWith("user ")) setOf("-password")
            else setOf("-pass", "-connect-command")
            for (index in 0 until out.lastIndex) {
                if (out[index].lowercase() in secretFlags) out[index + 1] = REDACTED
            }
        }
    }
    return out.joinToString(" ") { if (it == REDACTED) it else quoteBouncerArg(it) }
}

fun redactBouncerServReply(raw: String): String {
    val deletionConfirmation = Regex(
        "(?i)(To confirm user deletion, send \\\"user delete \\S+ )(\\S+)(\\\")",
    )
    if (deletionConfirmation.containsMatchIn(raw)) {
        return raw.replace(deletionConfirmation, "$1$REDACTED$3")
    }
    val lower = raw.lowercase()
    val looksSensitiveFailure = (lower.startsWith("error") || lower.startsWith("fail")) &&
        listOf("password", "-pass", "connect-command", "network quote", "server notice")
            .any(lower::contains)
    return if (looksSensitiveFailure) "BouncerServ command failed" else raw
}

/** Small POSIX-shell word parser: quotes and backslash only, matching BouncerServ's command grammar. */
internal fun parsePosixWords(input: String): List<String>? {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    var quote: Char? = null
    var escaped = false
    var started = false
    for (char in input) {
        if (escaped) {
            word.append(char); escaped = false; started = true; continue
        }
        if (char == '\\' && quote != '\'') {
            escaped = true; started = true; continue
        }
        if (quote != null) {
            if (char == quote) quote = null else word.append(char)
            started = true
            continue
        }
        if (char == '\'' || char == '"') {
            quote = char; started = true
        } else if (char.isWhitespace()) {
            if (started) { words += word.toString(); word.setLength(0); started = false }
        } else {
            word.append(char); started = true
        }
    }
    if (escaped || quote != null) return null
    if (started) words += word.toString()
    return words
}
