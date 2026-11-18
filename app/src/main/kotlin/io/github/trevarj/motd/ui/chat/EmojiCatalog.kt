package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.emoji.EmojiDataset
import java.util.Locale

data class EmojiSearchEntry(
    val emoji: String,
    val name: String,
)

/** Search all bundled Unicode sequences by their metadata names, independent of device support. */
fun emojiSearchEntries(dataset: EmojiDataset): List<EmojiSearchEntry> = dataset.entries.map { EmojiSearchEntry(it.sequence, canonicalEmojiName(it.name)) }

fun searchEmojis(
    entries: List<EmojiSearchEntry>,
    query: String,
    limit: Int = 8,
): List<EmojiSearchEntry> {
    val needle = canonicalEmojiSearchName(query)
    if (needle.isEmpty()) return emptyList()
    if (limit <= 0) return emptyList()

    val needleTokens = needle.split('_').map(::canonicalEmojiSearchToken)
    val normalizedNeedle = needleTokens.joinToString("_")
    val compactNeedle = normalizedNeedle.replace("_", "")

    return entries
        .asSequence()
        .mapIndexedNotNull { index, entry ->
            val name = canonicalEmojiName(entry.name)
            val tokens = name.split('_').map(::canonicalEmojiSearchToken)
            val normalizedName = tokens.joinToString("_")
            val match =
                emojiNameMatch(
                    normalizedName = normalizedName,
                    nameTokens = tokens,
                    needle = normalizedNeedle,
                    needleTokens = needleTokens,
                    compactNeedle = compactNeedle,
                ) ?: return@mapIndexedNotNull null
            EmojiSearchMatch(entry, match, index)
        }.sortedWith(
            compareBy<EmojiSearchMatch> { it.score.kind }
                .thenBy { it.score.wordIndex }
                .thenBy { it.score.characterIndex }
                .thenBy { it.score.gap }
                .thenBy { it.entry.name }
                .thenBy { it.originalIndex },
        ).map { it.entry }
        .take(limit)
        .toList()
}

private data class EmojiSearchMatch(
    val entry: EmojiSearchEntry,
    val score: EmojiMatchScore,
    val originalIndex: Int,
)

private data class EmojiMatchScore(
    val kind: Int,
    val wordIndex: Int,
    val characterIndex: Int,
    val gap: Int,
)

/**
 * Return a score for the best match of [needle] in a canonical emoji name.
 *
 * The tiers intentionally prefer complete names and word starts. A fuzzy subsequence match is a
 * useful fallback for short typos, but is kept below contiguous matches so that suggestions do
 * not jump around as the user types.
 */
private fun emojiNameMatch(
    normalizedName: String,
    nameTokens: List<String>,
    needle: String,
    needleTokens: List<String>,
    compactNeedle: String,
): EmojiMatchScore? {
    if (normalizedName == needle) return EmojiMatchScore(kind = 0, wordIndex = 0, characterIndex = 0, gap = 0)

    if (normalizedName.startsWith(needle)) {
        return EmojiMatchScore(kind = 1, wordIndex = 0, characterIndex = 0, gap = 0)
    }

    val tokenPrefix =
        nameTokens.indices.firstNotNullOfOrNull { start ->
            if (start + needleTokens.size > nameTokens.size) return@firstNotNullOfOrNull null
            if (needleTokens.indices.all { offset -> nameTokens[start + offset].startsWith(needleTokens[offset]) }) {
                EmojiMatchScore(
                    kind = 2,
                    wordIndex = start,
                    characterIndex = nameTokens.take(start).sumOf { it.length + 1 },
                    gap = 0,
                )
            } else {
                null
            }
        }
    if (tokenPrefix != null) return tokenPrefix

    if (needle.length >= 2) {
        val substringIndex = normalizedName.indexOf(needle)
        if (substringIndex >= 0) {
            val wordIndex = normalizedName.take(substringIndex).count { it == '_' }
            return EmojiMatchScore(kind = 3, wordIndex = wordIndex, characterIndex = substringIndex, gap = 0)
        }
    }

    if (compactNeedle.length < 2) return null
    val compactName = normalizedName.replace("_", "")
    val subsequence = compactSubsequenceMatch(compactName, compactNeedle) ?: return null
    val wordIndex = tokenIndexAtCompactOffset(nameTokens, subsequence.first)
    return EmojiMatchScore(
        kind = 4,
        wordIndex = wordIndex,
        characterIndex = subsequence.first,
        gap = subsequence.second,
    )
}

private fun compactSubsequenceMatch(
    name: String,
    needle: String,
): Pair<Int, Int>? {
    var needleIndex = 0
    var firstMatch = -1
    var lastMatch = -1
    name.forEachIndexed { index, character ->
        if (needleIndex < needle.length && character == needle[needleIndex]) {
            if (firstMatch < 0) firstMatch = index
            lastMatch = index
            needleIndex++
        }
    }
    if (needleIndex != needle.length) return null
    return firstMatch to (lastMatch - firstMatch + 1 - needle.length)
}

private fun tokenIndexAtCompactOffset(
    tokens: List<String>,
    offset: Int,
): Int {
    var remaining = offset
    tokens.forEachIndexed { index, token ->
        if (remaining < token.length) return index
        remaining -= token.length
    }
    return tokens.lastIndex.coerceAtLeast(0)
}

private fun canonicalEmojiSearchToken(value: String): String =
    when (val token = value.lowercase(Locale.ROOT)) {
        "smiling" -> "smile"
        else -> token
    }

/** Convert Unicode labels and user queries to the same lower-case snake_case representation. */
private fun canonicalEmojiName(value: String): String {
    val lower = value.lowercase(Locale.ROOT)
    val result = StringBuilder(lower.length)
    var separatorPending = false
    lower.forEach { character ->
        if (character.isLetterOrDigit()) {
            if (separatorPending && result.isNotEmpty()) result.append('_')
            result.append(character)
            separatorPending = false
        } else if (result.isNotEmpty()) {
            separatorPending = true
        }
    }
    return result.toString()
}

private fun canonicalEmojiSearchName(value: String): String = canonicalEmojiName(value.removePrefix(":"))
