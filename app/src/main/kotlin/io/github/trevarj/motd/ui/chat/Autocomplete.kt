package io.github.trevarj.motd.ui.chat

/**
 * Pure nick-autocomplete logic (WP7 acceptance: ranking is a unit-tested pure function).
 *
 * The composer extracts the token under the cursor and asks [rankNickCompletions] for candidates,
 * ranked by most-recent-spoke (from loaded messages) then alphabetically, matched prefix-wise on a
 * caller-supplied [normalize] (Isupport-normalized in the app; lowercase fallback).
 */

/** The completion token's bounds and whether only whitespace precedes it in the whole message. */
data class NickToken(
    val start: Int,
    val end: Int,
    val text: String,
    val atLineStart: Boolean,
)

/**
 * Find the completion token in [text] at [cursor], bounded by whitespace or surrounding punctuation.
 * A leading `@` is included in the replacement. Returns null for an empty token.
 * [NickToken.atLineStart] means only whitespace precedes it in the entire message, not just its line.
 */
fun nickTokenAt(
    text: String,
    cursor: Int,
): NickToken? {
    if (cursor < 0 || cursor > text.length) return null
    var start = cursor
    while (start > 0 && isNickTokenCharacter(text[start - 1])) start--
    var end = cursor
    while (end < text.length && isNickTokenCharacter(text[end])) end++
    if (start == end) return null

    var token = text.substring(start, end)
    // A leading @ sigil is stripped from the matchable text but stays inside [start, end] so that
    // applying a completion replaces the sigil too.
    if (token.startsWith("@")) token = token.substring(1)
    if (token.isEmpty()) return null

    val atLineStart = nicknameSuffix(text, start) == ": "
    return NickToken(start = start, end = end, text = token, atLineStart = atLineStart)
}

private fun isNickTokenCharacter(char: Char): Boolean = !char.isWhitespace() && char !in ",.:;!?()"

/**
 * Prefix-rank [members] against [prefix]. [recentSpeakers] lists nicks most-recent-first (typically
 * derived from the loaded message window); those matches sort first in that order, remaining matches
 * sort alphabetically (case-insensitive). [normalize] governs case/Isupport-folding for the match.
 */
fun rankNickCompletions(
    prefix: String,
    members: List<String>,
    recentSpeakers: List<String>,
    normalize: (String) -> String = { it.lowercase() },
    limit: Int = 10,
): List<String> {
    if (prefix.isEmpty()) return emptyList()
    val normPrefix = normalize(prefix)

    // Prefix matches, de-duplicated by normalized form (keep first display spelling seen).
    val matches = LinkedHashMap<String, String>()
    for (m in members) {
        val key = normalize(m)
        if (key.startsWith(normPrefix) && key !in matches) matches[key] = m
    }
    if (matches.isEmpty()) return emptyList()

    // Rank index by recency; unseen speakers get a large index so they fall to the alpha tail.
    val recencyRank = HashMap<String, Int>()
    recentSpeakers.forEachIndexed { i, nick ->
        val key = normalize(nick)
        // First (most recent) occurrence wins.
        if (key !in recencyRank) recencyRank[key] = i
    }

    return matches.entries
        .sortedWith(
            compareBy(
                { recencyRank[it.key] ?: Int.MAX_VALUE },
                { it.value.lowercase() },
            ),
        ).take(limit)
        .map { it.value }
}

/** A completed message and the cursor offset immediately after its nickname insertion. */
data class CompletionResult(
    val text: String,
    val cursor: Int,
)

fun applyCompletion(
    text: String,
    token: NickToken,
    nick: String,
): CompletionResult {
    val suffix = nicknameSuffix(text, token.start, token.end)
    val insertionLength = nick.length + suffix.length
    val newText =
        buildString(text.length - (token.end - token.start) + insertionLength) {
            append(text, 0, token.start)
            append(nick)
            append(suffix)
            append(text, token.end, text.length)
        }
    return CompletionResult(text = newText, cursor = token.start + insertionLength)
}

/** Newlines and visual wrapping never restart a message's addressing prefix. */
internal fun nicknameSuffix(
    text: CharSequence,
    before: Int,
    after: Int = text.length,
): String {
    var atMessageStart = true
    for (index in 0 until before) {
        if (!text[index].isWhitespace()) {
            atMessageStart = false
            break
        }
    }
    if (atMessageStart) return ": "
    val next = text.getOrNull(after)
    return if (next != null && (next.isWhitespace() || next in "!,.;:?)")) "" else " "
}
