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
}
