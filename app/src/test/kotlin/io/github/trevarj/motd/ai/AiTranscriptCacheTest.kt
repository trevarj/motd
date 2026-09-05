package io.github.trevarj.motd.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class AiTranscriptCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `hit returns exact UTF-8 text and touches its LRU timestamp`() =
        runTest {
            var now = 1_000L
            val directory = temporaryFolder.newFolder("hit")
            val cache = cache(directory, nowMillis = { now })
            val key = key('a')
            val text = "  exact café transcript\n"

            cache.put(key, text)
            assertEquals(now, File(directory, key).lastModified())
            now = 9_000L

            assertEquals(text, cache.get(key))
            assertEquals(now, File(directory, key).lastModified())
        }

    @Test
    fun `key canonically fingerprints audio model runtime preprocessing and every setting`() =
        runTest {
            val base = TranscriptionSettings(language = "auto", initialPrompt = "", cpuThreads = 2)
            val audio = key('a')
            val model = key('b')
            val expectedFingerprint =
                canonicalSha256(
                    "pcm-mono-s16le-16000-linear-v1",
                    "whisper.cpp-642b5d3260e020c2fc6f34a9569d10ddd7672963-jni-v1",
                    "auto",
                    "",
                    "2",
                )
            assertEquals(expectedFingerprint, AiTranscriptCache.settingsFingerprint(base))
            assertEquals(
                canonicalSha256(audio, model, expectedFingerprint),
                AiTranscriptCache.key(audio, model, base),
            )

            val keys =
                setOf(
                    AiTranscriptCache.key(audio, model, base),
                    AiTranscriptCache.key(key('c'), model, base),
                    AiTranscriptCache.key(audio, key('d'), base),
                    AiTranscriptCache.key(audio, model, base.copy(language = "en")),
                    AiTranscriptCache.key(audio, model, base.copy(initialPrompt = "Names: Åsa")),
                    AiTranscriptCache.key(audio, model, base.copy(cpuThreads = 3)),
                )
            assertEquals(6, keys.size)

            val directory = temporaryFolder.newFolder("filenames")
            val cache = cache(directory)
            keys.forEach { cache.put(it, "transcript") }
            assertEquals(
                keys,
                directory
                    .listFiles()
                    .orEmpty()
                    .map { it.name }
                    .toSet(),
            )
            assertTrue(directory.listFiles().orEmpty().all { Regex("[0-9a-f]{64}").matches(it.name) })

            val invalid = runCatching { cache.put("../audio-url", "transcript") }.exceptionOrNull()
            assertTrue(invalid is IllegalArgumentException)
            assertFalse(File(directory.parentFile, "audio-url").exists())
        }

    @Test
    fun `UTF-8 byte limit is inclusive while blank and oversized puts preserve prior data`() =
        runTest {
            val directory = temporaryFolder.newFolder("entry-limit")
            val cache = cache(directory, maxEntryBytes = 5, maxDirectoryBytes = 20)
            val accepted = key('a')
            val oversized = key('b')
            val blank = key('c')

            cache.put(accepted, "ééx")
            assertEquals("ééx", cache.get(accepted))

            cache.put(accepted, "ééxx")
            cache.put(oversized, "ééxx")
            cache.put(blank, " \n\t")

            assertEquals("ééx", cache.get(accepted))
            assertNull(cache.get(oversized))
            assertNull(cache.get(blank))
            assertFalse(File(directory, oversized).exists())
            assertFalse(File(directory, blank).exists())
        }

    @Test
    fun `corrupt oversized blank and temporary entries are deleted as misses`() =
        runTest {
            val directory = temporaryFolder.newFolder("corrupt")
            val cache = cache(directory, maxEntryBytes = 5, maxDirectoryBytes = 20)
            val corrupt = File(directory, key('a')).apply { writeBytes(byteArrayOf(0xc3.toByte(), 0x28)) }
            val oversized = File(directory, key('b')).apply { writeBytes(ByteArray(6) { 1 }) }
            val blank = File(directory, key('c')).apply { writeText(" \t", StandardCharsets.UTF_8) }
            val temporary = File(directory, ".transcript-interrupted.tmp").apply { writeText("partial") }

            assertNull(cache.get(corrupt.name))
            assertNull(cache.get(oversized.name))
            assertNull(cache.get(blank.name))

            assertFalse(corrupt.exists())
            assertFalse(oversized.exists())
            assertFalse(blank.exists())
            assertFalse(temporary.exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `atomic replacement failure and cancellation preserve the prior valid entry`() =
        runTest {
            val directory = temporaryFolder.newFolder("atomic")
            val key = key('a')
            val cache = cache(directory)
            cache.put(key, "old")
            val failing =
                cache(
                    directory,
                    atomicReplace = { _, _ -> throw IOException("injected move failure") },
                )

            val moveFailure = runCatching { failing.put(key, "new") }.exceptionOrNull()
            assertTrue(moveFailure is IOException)
            assertEquals("old", cache.get(key))
            assertTrue(directory.listFiles().orEmpty().all { Regex("[0-9a-f]{64}").matches(it.name) })

            val cancelled = Job().apply { cancel() }
            val cancellation =
                runCatching {
                    withContext(cancelled) { cache.put(key, "cancelled") }
                }.exceptionOrNull()
            assertTrue(cancellation is CancellationException)
            assertEquals("old", cache.get(key))
        }

    @Test
    fun `LRU eviction is deterministic by timestamp then hash and always enforces the directory limit`() =
        runTest {
            assertEquals(20L * 1024 * 1024, MAX_TRANSCRIPT_CACHE_BYTES)
            var now = 1_000L
            val directory = temporaryFolder.newFolder("lru")
            val cache = cache(directory, maxEntryBytes = 4, maxDirectoryBytes = 8, nowMillis = { now })
            val a = key('a')
            val b = key('b')
            val c = key('c')
            val d = key('d')

            cache.put(a, "aaaa")
            cache.put(b, "bbbb")
            cache.put(c, "cccc")

            assertFalse(File(directory, a).exists())
            assertTrue(File(directory, b).exists())
            assertTrue(File(directory, c).exists())
            assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 8)

            now = 2_000L
            assertEquals("bbbb", cache.get(b))
            now = 3_000L
            cache.put(d, "dddd")

            assertTrue(File(directory, b).exists())
            assertFalse(File(directory, c).exists())
            assertTrue(File(directory, d).exists())
            assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 8)
        }

    @Test
    fun `remove is scoped to one hash and clear deletes the transcript directory`() =
        runTest {
            val directory = temporaryFolder.newFolder("remove-clear")
            val cache = cache(directory)
            val first = key('a')
            val second = key('b')
            cache.put(first, "first")
            cache.put(second, "second")

            cache.remove(first)
            assertNull(cache.get(first))
            assertEquals("second", cache.get(second))

            cache.clear()
            assertFalse(directory.exists())
            assertNull(cache.get(second))
        }

    private fun cache(
        directory: File,
        maxEntryBytes: Int = MAX_TRANSCRIPT_BYTES,
        maxDirectoryBytes: Long = MAX_TRANSCRIPT_CACHE_BYTES,
        nowMillis: () -> Long = { 1_000L },
        atomicReplace: (java.nio.file.Path, java.nio.file.Path) -> Unit = { source, destination ->
            java.nio.file.Files.move(
                source,
                destination,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        },
    ) = AiTranscriptCache(
        directory = directory,
        ioDispatcher = Dispatchers.Unconfined,
        maxEntryBytes = maxEntryBytes,
        maxDirectoryBytes = maxDirectoryBytes,
        nowMillis = nowMillis,
        atomicReplace = atomicReplace,
    )

    private fun key(character: Char): String = character.toString().repeat(64)

    private fun canonicalSha256(vararg fields: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fields.forEach { field ->
            val bytes = field.toByteArray(StandardCharsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        val alphabet = "0123456789abcdef"
        return buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }
}
