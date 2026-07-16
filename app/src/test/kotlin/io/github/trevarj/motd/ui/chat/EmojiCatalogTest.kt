package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmojiCatalogTest {
    @Test fun catalogIsPaginatedAndBroad() {
        val pages = systemEmojiPages(List(8) { "page-$it" })
        assertEquals(8, pages.size)
        assertTrue(pages.dropLast(1).all { it.emojis.isNotEmpty() })
        assertTrue(pages.sumOf { it.emojis.size } > 1_000)
        assertTrue(pages.last().emojis.size > 200)
        assertTrue(pages.all { it.emojis.size == it.emojis.distinct().size })
    }

    @Test
    fun `smile prefix finds named emoji`() {
        val results = searchSystemEmojis(systemEmojiSearchEntries(), "smile")

        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.name.contains("smil") })
    }

    @Test
    fun `system names are lower case underscore separated`() {
        val entries = systemEmojiSearchEntries()

        assertTrue(entries.any { it.name == "grinning_face" })
        assertTrue(entries.all { it.name == it.name.lowercase() })
        assertTrue(entries.all { it.name.none { character -> character == ' ' || character == '-' } })
    }

    @Test
    fun `spaces hyphens and underscores are equivalent in queries`() {
        val entries = listOf(EmojiSearchEntry("x", "smiling_face_with_heart-eyes"))

        assertEquals(entries, searchSystemEmojis(entries, "heart eyes"))
        assertEquals(entries, searchSystemEmojis(entries, "heart_eyes"))
    }

    @Test
    fun `fuzzy subsequence finds a partially typed name`() {
        val entries = listOf(
            EmojiSearchEntry("x", "grinning_face"),
            EmojiSearchEntry("y", "heart_eyes"),
        )

        assertEquals("x", searchSystemEmojis(entries, "grnng").single().emoji)
    }

    @Test
    fun `ranking prefers exact and word starts before loose substrings`() {
        val entries = listOf(
            EmojiSearchEntry("substring", "interface"),
            EmojiSearchEntry("word", "grinning_face"),
            EmojiSearchEntry("exact", "face"),
            EmojiSearchEntry("prefix", "face_with_tears_of_joy"),
        )

        assertEquals(
            listOf("exact", "prefix", "word", "substring"),
            searchSystemEmojis(entries, "face").map { it.emoji },
        )
    }
}
