package io.github.trevarj.motd.ui.chat

/**
 * Pure nick-autocomplete logic (WP7 acceptance: ranking is a unit-tested pure function).
 *
 * The composer extracts the token under the cursor and asks [rankNickCompletions] for candidates,
 * ranked by most-recent-spoke (from loaded messages) then alphabetically, matched prefix-wise on a
 * caller-supplied [normalize] (Isupport-normalized in the app; lowercase fallback).
 */

/** The word being completed: the token boundaries around the cursor and whether it opens the line. */
data class NickToken(val start: Int, val end: Int, val text: String, val atLineStart: Boolean)

/**
 * Find the completion token in [text] at [cursor]. The token starts at the previous whitespace (or
 * an `@` sigil) and runs to the next whitespace. Returns null when the cursor is on whitespace or
 * the token is empty. [atLineStart] is true when only whitespace precedes the token, which selects
 * the `nick: ` insertion form.
 */
fun nickTokenAt(text: String, cursor: Int): NickToken? {
    if (cursor < 0 || cursor > text.length) return null
    // Walk left to a word boundary (space) — stop just after it.
    var start = cursor
    while (start > 0 && !text[start - 1].isWhitespace()) start--
    // Walk right to the next boundary.
    var end = cursor
    while (end < text.length && !text[end].isWhitespace()) end++
    if (start == end) return null

    var token = text.substring(start, end)
    // A leading @ sigil is stripped from the matchable text but stays inside [start, end] so that
    // applying a completion replaces the sigil too.
    if (token.startsWith("@")) token = token.substring(1)
    if (token.isEmpty()) return null

    val atLineStart = text.substring(0, start).isBlank()
    return NickToken(start = start, end = end, text = token, atLineStart = atLineStart)
}

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
        )
        .take(limit)
        .map { it.value }
}

/**
 * Replace the token [token] in [text] with [nick], choosing the insertion form: `nick: ` at line
 * start, otherwise `nick ` (plans/07). Returns the new text and the cursor offset after insertion.
 */
data class CompletionResult(val text: String, val cursor: Int)

fun applyCompletion(text: String, token: NickToken, nick: String): CompletionResult {
    val insert = if (token.atLineStart) "$nick: " else "$nick "
    val before = text.substring(0, token.start)
    val after = text.substring(token.end)
    val newText = before + insert + after
    return CompletionResult(text = newText, cursor = before.length + insert.length)
}
