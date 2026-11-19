package io.github.trevarj.motd.data.emoji

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EmojiSequenceCatalogTest {
    private val smile = "😀"
    private val heart = "❤️"
    private val family = "😀\u200D😀"

    private fun entry(
        sequence: String,
        base: String = sequence,
        support: Set<String> = setOf("noto"),
    ) = EmojiEntry(sequence, sequence, "Test", "test", base, support)

    private fun dataset() =
        EmojiDataset(
            listOf(entry(smile), entry(heart), entry(family, smile, emptySet())),
            listOf("Test"),
            listOf(EmojiAlias("❤", heart, setOf("openmoji"))),
        )

    @Test
    fun longestRecognitionHappensBeforeFontSupportFiltering() {
        val data = dataset()
        assertTrue(data.matches(family, "noto").isEmpty())
        assertEquals(listOf(EmojiMatch(1, 3, data.entry(smile)!!)), data.matches("x${smile}y", "noto"))
        assertTrue(data.matches(smile, EmojiSets.SYSTEM_ID).isEmpty())
        assertSame(emptyList<EmojiMatch>(), data.matches("ordinary text", "noto"))
    }

    @Test
    fun unsupportedContinuationsNeverStyleSupportedPrefixesOrJoinedSuffixes() {
        val data = dataset()
        val continuations =
            listOf(
                "\u200D🦄",
                "\u200D",
                "🏻",
                "\uFE0F",
                "\uFE0E",
                "\u20E3",
                String(Character.toChars(0xE0067)) + String(Character.toChars(0xE007F)),
            )
        continuations.forEach { suffix ->
            assertTrue("Unexpected match for $suffix", data.matches(smile + suffix, "noto").isEmpty())
        }
        assertTrue(data.matches("🦄\u200D$smile", "noto").isEmpty())
        assertEquals(1, data.matches("$smile🏻 $smile", "noto").size)
    }

    @Test
    fun regionalIndicatorsPairFromTheStartOfEachRun() {
        val au = entry("🇦🇺")
        val us = entry("🇺🇸")
        val data = EmojiDataset(listOf(au, us), listOf("Test"), emptyList())
        assertEquals(
            listOf(EmojiMatch(4, 8, us), EmojiMatch(11, 15, au), EmojiMatch(15, 19, us)),
            data.matches("🇦🇦🇺🇸🇦 🇦🇺🇺🇸", "noto"),
        )
        assertEquals(listOf(EmojiMatch(7, 11, us)), data.matches("😀\u200D🇦🇦🇺🇸", "noto"))
    }

    @Test
    fun aliasesHaveTheirOwnExactCoverageAndTextPresentationStaysNative() {
        val data = dataset()
        assertEquals(heart, data.entry("❤")!!.sequence)
        assertTrue(data.matches("❤", "noto").isEmpty())
        assertEquals(1, data.matches("❤", "openmoji").size)
        assertEquals(1, data.matches(heart, "noto").size)
        assertTrue(data.matches("❤\uFE0E", "openmoji").isEmpty())
        assertEquals(listOf(smile, family), data.variantsOf(family).map { it.sequence })
    }

    @Test
    fun generatedUnicode16CatalogLoadsOnceWithCompleteFamiliesAndGroups() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val catalog = EmojiSequenceCatalog(context)
            val data = catalog.load()
            assertSame(data, catalog.load())
            assertEquals(3790, data.entries.size)
            assertEquals(10, data.groups.size)
            assertEquals(9, data.entries.count { it.group == "Component" })
            assertEquals("grinning face", data.entry(smile)!!.name)
            assertEquals("red heart", data.entry("❤")!!.name)
            assertTrue(data.variantsOf("🤝").size > 20)
            assertTrue(data.variantsOf("🚶\u200D➡️").size >= 6)
            assertTrue(data.matches("😀\u200D🫩", "noto").isEmpty())
            assertEquals(1, data.matches("1️⃣", "noto").size)
            assertTrue(data.matches("1️⃣\uFE0E", "noto").isEmpty())
            for (sequence in listOf("👩🏽‍💻", "🫱🏽‍🫲🏿", "👩🏽‍❤️‍👨🏿", "🏳️‍🌈", "❤")) {
                assertEquals(1, data.matches(sequence, "twemoji").size)
            }
            assertTrue(data.matches("🫪", "twemoji").isEmpty())
            assertTrue(data.matches("❤\uFE0E", "twemoji").isEmpty())
        }

    @Test(expected = UnsupportedOperationException::class)
    fun publishedEntriesCannotBeMutated() {
        @Suppress("UNCHECKED_CAST")
        (dataset().entries as MutableList<EmojiEntry>).clear()
    }

    @Test(expected = UnsupportedOperationException::class)
    fun publishedCoverageCannotBeMutated() {
        @Suppress("UNCHECKED_CAST")
        (dataset().entry(smile)!!.supportedSetIds as MutableSet<String>).clear()
    }
}
