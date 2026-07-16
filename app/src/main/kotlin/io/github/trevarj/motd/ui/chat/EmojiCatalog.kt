package io.github.trevarj.motd.ui.chat

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import java.util.Locale

data class EmojiPage(val icon: String, val label: String, val emojis: List<String>)

data class EmojiSearchEntry(val emoji: String, val name: String)

/**
 * Build the catalog from Android's own Unicode emoji properties. This follows the Unicode version
 * supported by the device instead of freezing a short hand-maintained list in the app.
 */
fun systemEmojiPages(labels: List<String>): List<EmojiPage> {
    require(labels.size == 8)
    val buckets = List(7) { linkedSetOf<String>() }
    val ranges = listOf(0x00A9..0x00AE, 0x203C..0x3299, 0x1F000..0x1FAFF)
    for (range in ranges) {
        for (codePoint in range) {
            if (!UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI)) continue
            if (UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_MODIFIER) || codePoint in 0x1F1E6..0x1F1FF) continue
            val glyph = String(Character.toChars(codePoint)) +
                if (UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_PRESENTATION)) "" else "\uFE0F"
            val bucket = emojiBucket(codePoint)
            buckets[bucket] += glyph
            if (UCharacter.hasBinaryProperty(codePoint, UProperty.EMOJI_MODIFIER_BASE)) {
                for (tone in 0x1F3FB..0x1F3FF) buckets[bucket] += glyph.trimEnd('\uFE0F') + String(Character.toChars(tone))
            }
        }
    }

    // Keycaps and every ISO country flag are Emoji sequences rather than single Emoji code points.
    buckets[6] += listOf("#️⃣", "*️⃣") + ('0'..'9').map { "$it\uFE0F\u20E3" }
    val flags = Locale.getISOCountries().sorted().map { country ->
        country.map { letter -> String(Character.toChars(0x1F1E6 + (letter - 'A'))) }.joinToString("")
    }

    val icons = listOf("😀", "🐻", "🍜", "⚽", "🚀", "💡", "❤️")
    return buckets.mapIndexed { index, emojis ->
        val ordered = if (index == 0) emojis.sortedBy(::peopleEmojiOrder) else emojis.toList()
        EmojiPage(icons[index], labels[index], ordered)
    } +
        EmojiPage("🏳️", labels[7], flags)
}

/** Search labels derived from the device Unicode database, so completion tracks OS emoji support. */
fun systemEmojiSearchEntries(): List<EmojiSearchEntry> {
    val pages = systemEmojiPages(List(8) { "" })
    val countryNames = Locale.getISOCountries().associate { country ->
        val flag = country.map { letter ->
            String(Character.toChars(0x1F1E6 + (letter - 'A')))
        }.joinToString("")
        flag to Locale("", country).displayCountry
    }
    return pages.flatMap { it.emojis }.distinct().mapNotNull { emoji ->
        val name = when {
            countryNames[emoji] != null -> "${countryNames.getValue(emoji)} flag"
            emoji.endsWith("\u20E3") -> "${emoji.first()} keycap"
            else -> UCharacter.getName(emoji.codePointAt(0))
        }?.let(::canonicalEmojiName) ?: return@mapNotNull null
        EmojiSearchEntry(emoji, name)
    }
}

fun searchSystemEmojis(
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

    return entries.asSequence()
        .mapIndexedNotNull { index, entry ->
            val name = canonicalEmojiName(entry.name)
            val tokens = name.split('_').map(::canonicalEmojiSearchToken)
            val normalizedName = tokens.joinToString("_")
            val match = emojiNameMatch(
                normalizedName = normalizedName,
                nameTokens = tokens,
                needle = normalizedNeedle,
                needleTokens = needleTokens,
                compactNeedle = compactNeedle,
            ) ?: return@mapIndexedNotNull null
            EmojiSearchMatch(entry, match, index)
        }
        .sortedWith(
            compareBy<EmojiSearchMatch> { it.score.kind }
                .thenBy { it.score.wordIndex }
                .thenBy { it.score.characterIndex }
                .thenBy { it.score.gap }
                .thenBy { it.entry.name }
                .thenBy { it.originalIndex },
        )
        .map { it.entry }
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

    val tokenPrefix = nameTokens.indices.firstNotNullOfOrNull { start ->
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

private fun compactSubsequenceMatch(name: String, needle: String): Pair<Int, Int>? {
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

private fun tokenIndexAtCompactOffset(tokens: List<String>, offset: Int): Int {
    var remaining = offset
    tokens.forEachIndexed { index, token ->
        if (remaining < token.length) return index
        remaining -= token.length
    }
    return tokens.lastIndex.coerceAtLeast(0)
}

private fun canonicalEmojiSearchToken(value: String): String = when (val token = value.lowercase(Locale.ROOT)) {
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

private fun peopleEmojiOrder(emoji: String): Int {
    val codePoint = emoji.codePointAt(0)
    return when (codePoint) {
        in 0x1F600..0x1F64F -> 0
        in 0x1F910..0x1F92F -> 1
        else -> 2
    } * 0x110000 + codePoint
}

private fun emojiBucket(codePoint: Int): Int = when {
    codePoint in 0x1F600..0x1F64F || codePoint in 0x1F900..0x1F9FF ||
        codePoint in 0x1FA70..0x1FAFF || codePoint in 0x1F440..0x1F487 -> 0 // people
    codePoint in 0x1F32D..0x1F37F -> 2 // food
    codePoint in 0x1F3A0..0x1F3FF -> 3 // activity
    codePoint in 0x1F300..0x1F43F -> 1 // nature
    codePoint in 0x1F680..0x1F6FF -> 4 // travel
    codePoint in 0x1F4A0..0x1F5FF || codePoint in 0x1F700..0x1F8FF -> 5 // objects
    else -> 6 // symbols
}
