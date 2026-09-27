package io.github.trevarj.motd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EbooksResultsTest {
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                ZipOutputStream(bytes).use { output ->
                    entries.forEach { (name, data) ->
                        output.putNextEntry(ZipEntry(name))
                        output.write(data)
                        output.closeEntry()
                    }
                }
            }.toByteArray()

    private fun resultLine(name: String = "27 - The Last Hero - Graphic Novel.pdf") = "!artemis_serv 16d6770d2ba9 | $name ::INFO:: 49.78MB"

    @Test
    fun readsOnlyDocumentedTextResultsAndPreservesExactRequest() {
        val lines = "Results for your search\r\n${resultLine()}\r\n!other_bot another | Four Winds.epub ::INFO:: 3.4MB\r\n"
        val archive = zip("nested/results.TXT" to lines.toByteArray(), "book.epub" to resultLine("Danger.epub").toByteArray())
        assertEquals(
            listOf(
                EbooksResult("!artemis_serv 16d6770d2ba9 | 27 - The Last Hero - Graphic Novel.pdf", "27 - The Last Hero - Graphic Novel.pdf", "49.78MB"),
                EbooksResult("!other_bot another | Four Winds.epub", "Four Winds.epub", "3.4MB"),
            ),
            parseEbooksResults(ByteArrayInputStream(archive)),
        )
    }

    @Test
    fun rejectsOversizedInflatedEntryEvenIfCompressedZIPIsTiny() {
        val archive = zip("results.txt" to ByteArray(2 * 1024 * 1024 + 1) { 'x'.code.toByte() })
        assertTrue(archive.size < 4096)
        assertEquals(
            "Results ZIP exceeds the uncompressed size limit",
            assertThrows(IllegalArgumentException::class.java) { parseEbooksResults(ByteArrayInputStream(archive)) }.message,
        )
    }

    @Test
    fun rejectsCombinedUncompressedSizeIncludingNonTextEntries() {
        val part = ByteArray(1_700_000)
        val archive = zip(*(1..5).map { "book$it.epub" to part }.toTypedArray())
        assertEquals(
            "Results ZIP exceeds the uncompressed size limit",
            assertThrows(IllegalArgumentException::class.java) { parseEbooksResults(ByteArrayInputStream(archive)) }.message,
        )
    }

    @Test
    fun discardsUnsafeAndMalformedCommandsRatherThanOfferingToSendThem() {
        val archive =
            zip(
                "results.txt" to
                    listOf(
                        "!bot token | clean.epub ::INFO:: 2MB",
                        "!bot token | injected\u0007.epub ::INFO:: 2MB",
                        "!bot token | sneaky\u202Eepub ::INFO:: 2MB",
                        "!bot token | bad.epub ::INFO:: unknown",
                        "!bot token | ${"x".repeat(401)} ::INFO:: 2MB",
                        "!bot token | unexpected.epub ::INFO:: 2MB\r!other token | unsafe.epub ::INFO:: 2MB",
                        "!bot token | missing-size.epub",
                    ).joinToString("\n").toByteArray(),
            )
        assertEquals(
            listOf(EbooksResult("!bot token | clean.epub", "clean.epub", "2MB")),
            parseEbooksResults(ByteArrayInputStream(archive)),
        )
    }

    @Test
    fun invalidAndUnsupportedArchivesExplainTheProblem() {
        val invalid = assertThrows(IllegalArgumentException::class.java) { parseEbooksResults(ByteArrayInputStream("not a ZIP".toByteArray())) }
        assertEquals("Invalid results ZIP file", invalid.message)
        val ebook =
            assertThrows(IllegalArgumentException::class.java) {
                parseEbooksResults(ByteArrayInputStream(zip("book.epub" to resultLine().toByteArray())))
            }
        assertEquals("No .txt results entries in ZIP", ebook.message)
        val unsupported =
            assertThrows(IllegalArgumentException::class.java) {
                parseEbooksResults(ByteArrayInputStream(zip("results.txt" to "no result lines".toByteArray())))
            }
        assertEquals("No valid book results in ZIP .txt entries", unsupported.message)
    }

    @Test
    fun capsNumberOfEntriesAndResults() {
        val tooManyEntries = zip(*(1..129).map { "file$it.bin" to byteArrayOf(0) }.toTypedArray())
        val entriesError =
            assertThrows(IllegalArgumentException::class.java) {
                parseEbooksResults(ByteArrayInputStream(tooManyEntries))
            }
        assertEquals("Results ZIP has too many entries (limit 128)", entriesError.message)
        val tooManyResults = zip("results.txt" to List(501) { resultLine("book$it.epub") }.joinToString("\n").toByteArray())
        val resultsError =
            assertThrows(IllegalArgumentException::class.java) {
                parseEbooksResults(ByteArrayInputStream(tooManyResults))
            }
        assertEquals("Results ZIP has too many book results (limit 500)", resultsError.message)
    }
}
