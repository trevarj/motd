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
        }?.lowercase(Locale.ROOT)?.replace('_', ' ') ?: return@mapNotNull null
        EmojiSearchEntry(emoji, name)
    }
}

fun searchSystemEmojis(
    entries: List<EmojiSearchEntry>,
    query: String,
    limit: Int = 8,
): List<EmojiSearchEntry> {
    val needle = canonicalEmojiSearchToken(query)
    if (needle.isEmpty()) return emptyList()
    return entries.asSequence()
        .mapNotNull { entry ->
            val tokens = entry.name.split(' ', '-')
                .map(::canonicalEmojiSearchToken)
                .filter(String::isNotEmpty)
            val firstMatch = tokens.indexOfFirst { it.startsWith(needle) }
            if (firstMatch < 0) null else entry to firstMatch
        }
        .sortedWith(compareBy<Pair<EmojiSearchEntry, Int>> { it.second }.thenBy { it.first.name })
        .map { it.first }
        .take(limit)
        .toList()
}

private fun canonicalEmojiSearchToken(value: String): String = when (val token = value.lowercase(Locale.ROOT)) {
    "smiling" -> "smile"
    else -> token
}

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
