package io.github.trevarj.motd.irc.ext

/**
 * Tag-escaped `k=v;k2=v2` attribute strings used by soju's BOUNCER and WEBPUSH commands
 * (plans/03). Values use the IRCv3 tag-value escape table.
 */
internal fun renderAttrString(attrs: Map<String, String>): String =
    attrs.entries.joinToString(";") { (k, v) -> "$k=${escapeAttrValue(v)}" }

internal fun parseAttrString(raw: String): Map<String, String> {
    if (raw.isEmpty() || raw == "*") return emptyMap()
    val out = LinkedHashMap<String, String>()
    for (part in raw.split(';')) {
        if (part.isEmpty()) continue
        val eq = part.indexOf('=')
        if (eq < 0) {
            out[part] = ""
        } else {
            out[part.substring(0, eq)] = unescapeAttrValue(part.substring(eq + 1))
        }
    }
    return out
}

private fun escapeAttrValue(value: String): String {
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

private fun unescapeAttrValue(value: String): String {
    if (value.indexOf('\\') < 0) return value
    val sb = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch != '\\') { sb.append(ch); i++; continue }
        if (i == value.length - 1) break
        when (val next = value[i + 1]) {
            ':' -> sb.append(';')
            's' -> sb.append(' ')
            '\\' -> sb.append('\\')
            'r' -> sb.append('\r')
            'n' -> sb.append('\n')
            else -> sb.append(next)
        }
        i += 2
    }
    return sb.toString()
}
