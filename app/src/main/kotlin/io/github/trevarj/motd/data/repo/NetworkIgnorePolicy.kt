package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.Prefix

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
    if (nick.isEmpty() || rules.isChannel(nick) || !nick[0].isIrcNickInitial()) return false
    for (index in 1 until nick.length) {
        val ch = nick[index]
        if (!ch.isIrcNickInitial() && ch !in '0'..'9' && ch != '-') return false
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
    val mask = parseIgnoreMask(pattern) ?: return false
    return mask.user == "*" && mask.host == "*" && mask.nick.none { it == '*' || it == '?' } &&
        rules.normalize(mask.nick) == normalizedNick
}

fun ignoredBy(
    ignores: List<NetworkIgnoreEntity>,
    source: Prefix,
    identityRules: IrcIdentityRules,
): Boolean =
    ignores.any { ignore ->
        ignore.enabled && parseIgnoreMask(ignore.pattern)?.matches(source, identityRules) == true
    }

private fun IgnoreMask.matches(
    source: Prefix,
    identityRules: IrcIdentityRules,
): Boolean {
    val normalizedMaskNick = identityRules.normalize(nick)
    val normalizedSourceNick = identityRules.normalize(source.nick)
    return globMatches(normalizedMaskNick, normalizedSourceNick) &&
        globMatches(user.lowercase(), (source.user ?: "").lowercase()) &&
        globMatches(host.lowercase(), (source.host ?: "").lowercase())
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
