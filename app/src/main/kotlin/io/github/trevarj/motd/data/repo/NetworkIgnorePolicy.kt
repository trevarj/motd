package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.Prefix

internal const val DROP_IGNORED_MESSAGE = -1
internal const val SUPPRESS_UNREAD = 1
internal const val SUPPRESS_HIGHLIGHT = 2
internal const val SUPPRESS_NOTIFICATIONS = 4
internal const val ALL_IGNORE_SIGNALS = SUPPRESS_UNREAD or SUPPRESS_HIGHLIGHT or SUPPRESS_NOTIFICATIONS

data class IgnoreMask(
    val nick: String,
    val user: String,
    val host: String,
)

fun normalizeIgnorePattern(raw: String): Result<String> =
    runCatching {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "Ignore mask is required" }
        require(trimmed.none { it == '\r' || it == '\n' }) { "Ignore mask cannot contain line breaks" }
        when {
            '!' in trimmed -> trimmed
            '@' in trimmed -> "*!$trimmed"
            else -> "$trimmed!*@*"
        }
    }

fun parseIgnoreMask(pattern: String): IgnoreMask? {
    val bang = pattern.indexOf('!')
    val at = pattern.indexOf('@', startIndex = (bang + 1).coerceAtLeast(0))
    return when {
        bang > 0 && at > bang + 1 && at < pattern.lastIndex -> {
            IgnoreMask(
                nick = pattern.substring(0, bang),
                user = pattern.substring(bang + 1, at),
                host = pattern.substring(at + 1),
            )
        }

        bang < 0 && at > 0 && at < pattern.lastIndex -> {
            IgnoreMask(nick = "*", user = pattern.substring(0, at), host = pattern.substring(at + 1))
        }

        else -> {
            null
        }
    }
}

internal fun isExactIgnoreNick(
    nick: String,
    rules: IrcIdentityRules,
): Boolean {
    if (nick.isEmpty() || rules.isChannel(nick)) return false
    var index = 0
    while (index < nick.length) {
        val point = nick.codePointAt(index)
        val valid =
            if (point <= 0x7f) {
                val ch = point.toChar()
                ch.isIrcNickInitial() || (index != 0 && (ch in '0'..'9' || ch == '-'))
            } else {
                // Non-ASCII nick alphabets are server-specific; keep literal code points, not separators or controls.
                !Character.isSpaceChar(point) && !Character.isISOControl(point) &&
                    point !in 0xd800..0xdfff && Character.getType(point) != Character.FORMAT.toInt()
            }
        if (!valid) return false
        index += Character.charCount(point)
    }
    return true
}

private fun Char.isIrcNickInitial(): Boolean = this in 'A'..'Z' || this in 'a'..'z' || this in "[]\\`_^{|}"

internal fun hasExactNickIgnore(
    ignores: List<NetworkIgnoreEntity>,
    nick: String,
    rules: IrcIdentityRules,
): Boolean {
    val normalizedNick = rules.normalize(nick)
    return ignores.any { it.enabled && it.matchesExactIgnoreNick(normalizedNick, rules) }
}

internal fun NetworkIgnoreEntity.matchesExactIgnoreNick(
    normalizedNick: String,
    rules: IrcIdentityRules,
): Boolean {
    if (!dropMessages) return false
    val mask = parseIgnoreMask(pattern) ?: return false
    return mask.user == "*" && mask.host == "*" && mask.nick.none { it == '*' || it == '?' } &&
        rules.normalize(mask.nick) == normalizedNick
}

internal fun incomingIgnoreSignals(
    ignores: List<NetworkIgnoreEntity>,
    source: Prefix,
    identityRules: IrcIdentityRules,
): Int {
    var suppressed = 0
    var sourceNick: String? = null
    var sourceUser: String? = null
    var sourceHost: String? = null
    for (ignore in ignores) {
        if (!ignore.enabled) continue
        val mask = parseIgnoreMask(ignore.pattern) ?: continue
        val nick = sourceNick ?: identityRules.normalize(source.nick).also { sourceNick = it }
        if (!globMatches(identityRules.normalize(mask.nick), nick)) continue
        val user =
            sourceUser ?: source.user
                .orEmpty()
                .lowercase()
                .also { sourceUser = it }
        if (!globMatches(mask.user.lowercase(), user)) continue
        val host =
            sourceHost ?: source.host
                .orEmpty()
                .lowercase()
                .also { sourceHost = it }
        if (!globMatches(mask.host.lowercase(), host)) continue
        if (ignore.dropMessages) return DROP_IGNORED_MESSAGE
        suppressed = suppressed or (ignore.suppressedSignals and ALL_IGNORE_SIGNALS)
    }
    return suppressed
}

private fun globMatches(
    pattern: String,
    value: String,
): Boolean {
    var patternIndex = 0
    var valueIndex = 0
    var starIndex = -1
    var matchIndex = 0
    while (valueIndex < value.length) {
        when {
            patternIndex < pattern.length &&
                (pattern[patternIndex] == '?' || pattern[patternIndex] == value[valueIndex]) -> {
                patternIndex++
                valueIndex++
            }

            patternIndex < pattern.length && pattern[patternIndex] == '*' -> {
                starIndex = patternIndex
                matchIndex = valueIndex
                patternIndex++
            }

            starIndex >= 0 -> {
                patternIndex = starIndex + 1
                matchIndex++
                valueIndex = matchIndex
            }

            else -> {
                return false
            }
        }
    }
    while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex++
    return patternIndex == pattern.length
}
