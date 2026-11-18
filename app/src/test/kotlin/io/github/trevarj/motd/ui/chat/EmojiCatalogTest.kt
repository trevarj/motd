package io.github.trevarj.motd.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.emoji.EmojiSequenceCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmojiCatalogTest {
    private suspend fun catalogEntries(): List<EmojiSearchEntry> = emojiSearchEntries(EmojiSequenceCatalog(ApplicationProvider.getApplicationContext<Context>()).load())

    @Test
    fun `catalog includes every bundled metadata sequence`() =
        runTest {
            val dataset = EmojiSequenceCatalog(ApplicationProvider.getApplicationContext<Context>()).load()
            val entries = emojiSearchEntries(dataset)

            assertEquals(3790, entries.size)
            assertEquals(dataset.entries.map { it.sequence }, entries.map { it.emoji })
            assertEquals(entries.size, entries.map { it.emoji }.distinct().size)
        }

    @Test
    fun `metadata search preserves full ZWJ mixed tone and directional sequences`() =
        runTest {
            val entries = catalogEntries()
            val expected =
                mapOf(
                    "family man woman girl boy" to "👨‍👩‍👧‍👦",
                    "handshake light skin tone dark skin tone" to "🫱🏻‍🫲🏿",
                    "person walking facing right" to "🚶‍➡️",
                    "person walking facing right medium skin tone" to "🚶🏽‍➡️",
                    "face with bags under eyes" to "🫩",
                )

            expected.forEach { (query, sequence) ->
                assertEquals(query, sequence, searchEmojis(entries, query).first().emoji)
            }
        }

    @Test
    fun `smile prefix finds named emoji`() =
        runTest {
            val results = searchEmojis(catalogEntries(), "smile")

            assertTrue(results.isNotEmpty())
            assertTrue(results.all { it.name.contains("smil") })
        }

    @Test
    fun `metadata names are lower case underscore separated`() =
        runTest {
            val entries = catalogEntries()

            assertTrue(entries.any { it.name == "grinning_face" })
            assertTrue(entries.all { it.name == it.name.lowercase() })
            assertTrue(entries.all { it.name.none { character -> character == ' ' || character == '-' } })
        }

    @Test
    fun `spaces hyphens and underscores are equivalent in queries`() {
        val entries = listOf(EmojiSearchEntry("x", "smiling_face_with_heart-eyes"))

        assertEquals(entries, searchEmojis(entries, "heart eyes"))
        assertEquals(entries, searchEmojis(entries, "heart_eyes"))
        assertEquals(entries, searchEmojis(entries, "heart-eyes"))
    }

    @Test
    fun `fuzzy subsequence finds a partially typed name`() {
        val entries =
            listOf(
                EmojiSearchEntry("x", "grinning_face"),
                EmojiSearchEntry("y", "heart_eyes"),
            )

        assertEquals("x", searchEmojis(entries, "grnng").single().emoji)
    }

    @Test
    fun `ranking prefers exact and word starts before loose substrings`() {
        val entries =
            listOf(
                EmojiSearchEntry("substring", "interface"),
                EmojiSearchEntry("word", "grinning_face"),
                EmojiSearchEntry("exact", "face"),
                EmojiSearchEntry("prefix", "face_with_tears_of_joy"),
            )

        assertEquals(
            listOf("exact", "prefix", "word", "substring"),
            searchEmojis(entries, "face").map { it.emoji },
        )
    }
}
