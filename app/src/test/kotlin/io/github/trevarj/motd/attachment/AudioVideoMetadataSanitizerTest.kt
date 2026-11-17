package io.github.trevarj.motd.attachment

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class AudioVideoMetadataSanitizerTest {
    @Test fun opusCommentsAreRemovedWithoutChangingAudioOrTiming() =
        runBlocking {
            withFiles { input, output ->
                val audio = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
                val comments = tags("Private recorder", listOf("ARTIST=Private person", "GPS=51.2,0.1", "COVERART=secret"))
                input.writeBytes(page(0, 2, 0, listOf(opusHead())) + page(1, 0, 0, listOf(comments)) + page(2, 4, 960, listOf(audio)))
                assertEquals(SanitizedMedia("audio/ogg", ".ogg"), stripAudioVideoMetadata(input, output))
                val pages = parsePages(output.readBytes())
                assertEquals(3, pages.size)
                assertArrayEquals(opusHead(), pages[0].body)
                assertArrayEquals("OpusTags".toByteArray() + ByteArray(8), pages[1].body)
                assertArrayEquals(audio, pages[2].body)
                assertEquals(960L, pages[2].granule)
                assertEquals(4, pages[2].flags)
                assertFalse(output.readBytes().toString(Charsets.ISO_8859_1).contains("Private"))
            }
        }

    @Test fun spanningCommentsAreRemovedAndSharedAudioPageIsPreserved() =
        runBlocking {
            withFiles { input, output ->
                val audio = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
                val comments = tags("Private vendor", listOf("DESCRIPTION=" + "secret".repeat(12000)))
                val split = 255 * 255
                val first = rawPage(1, 0, -1, ByteArray(255) { 255.toByte() }, comments.copyOfRange(0, split))
                val tail = comments.copyOfRange(split, comments.size)
                val last = rawPage(2, 5, 960, laces(tail.size) + laces(audio.size), tail + audio)
                input.writeBytes(page(0, 2, 0, listOf(opusHead())) + first + last)
                stripAudioVideoMetadata(input, output)
                val pages = parsePages(output.readBytes())
                assertEquals(2, pages.size)
                assertEquals(1, pages[1].sequence)
                assertEquals(4, pages[1].flags)
                assertEquals(960L, pages[1].granule)
                assertArrayEquals("OpusTags".toByteArray() + ByteArray(8) + audio, pages[1].body)
            }
        }

    @Test fun malformedCommentsAndBrokenChecksumsFailRatherThanPassingOriginal() =
        runBlocking {
            withFiles { input, output ->
                val malformed = "OpusTags".toByteArray() + little(1000) + ByteArray(4)
                input.writeBytes(page(0, 2, 0, listOf(opusHead())) + page(1, 4, 960, listOf(malformed)))
                expectIo { stripAudioVideoMetadata(input, output) }
                val bytes = page(0, 2, 0, listOf(opusHead()))
                bytes[bytes.lastIndex] = 42
                input.writeBytes(bytes)
                expectIo { stripAudioVideoMetadata(input, output) }
            }
        }

    @Test fun truncatedSupportedMediaFailsAndUnknownContainersAreUnsupported() =
        runBlocking {
            withFiles { input, output ->
                input.writeBytes("OggS".toByteArray())
                expectIo { stripAudioVideoMetadata(input, output) }
                input.writeBytes("plain private document".toByteArray())
                assertNull(stripAudioVideoMetadata(input, output))
                input.writeBytes(page(0, 2, 0, listOf("Speex   unsupported".toByteArray())))
                assertNull(stripAudioVideoMetadata(input, output))
            }
        }

    @Test fun incompleteAudioDoesNotBecomeASanitizedFile() =
        runBlocking {
            withFiles { input, output ->
                input.writeBytes(page(0, 2, 0, listOf(opusHead())) + page(1, 4, 0, listOf(tags("private", emptyList()))))
                expectIo { stripAudioVideoMetadata(input, output) }
            }
        }

    @Test fun oversizedCommentPacketIsRejectedAtTheStreamingLimit() =
        runBlocking {
            withFiles { input, output ->
                input.outputStream().use { stream ->
                    stream.write(page(0, 2, 0, listOf(opusHead())))
                    repeat(17) { index ->
                        stream.write(
                            rawPage(
                                index + 1,
                                if (index == 0) 0 else 1,
                                -1,
                                ByteArray(255) { 255.toByte() },
                                ByteArray(255 * 255),
                            ),
                        )
                    }
                }
                try {
                    stripAudioVideoMetadata(input, output)
                    fail("Expected bounded-comment failure")
                } catch (error: IOException) {
                    assertEquals("Ogg comments exceed 1 MiB", error.message)
                }
            }
        }

    @Test fun truncatedOpusIdentificationIsMalformedNotUnsupported() =
        runBlocking {
            withFiles { input, output ->
                for (length in 8 until 19) {
                    input.writeBytes(page(0, 2, 0, listOf(opusHead().copyOf(length))))
                    expectIo { stripAudioVideoMetadata(input, output) }
                }
                val mapped = opusHead().also { it[18] = 1 }
                input.writeBytes(page(0, 2, 0, listOf(mapped)))
                expectIo { stripAudioVideoMetadata(input, output) }
                val validMapping =
                    opusHead().also {
                        it[9] = 2
                        it[18] = 1
                    } + byteArrayOf(1, 1, 0, 1)
                input.writeBytes(page(0, 2, 0, listOf(validMapping)))
                assertNull(stripAudioVideoMetadata(input, output))
                input.writeBytes(page(0, 2, 0, listOf(opusHead().also { it[8] = 2 })))
                assertNull(stripAudioVideoMetadata(input, output))
            }
        }

    @Test fun validLargeAndExtendedFileTypeBoxesAreUnsupportedNotMalformed() =
        runBlocking {
            withFiles { input, output ->
                val large =
                    ByteBuffer
                        .allocate(8192)
                        .putInt(8192)
                        .put("ftyp".toByteArray())
                        .put("isom".toByteArray())
                        .putInt(0)
                        .array()
                input.writeBytes(large)
                assertNull(stripAudioVideoMetadata(input, output))
                val extended =
                    ByteBuffer
                        .allocate(24)
                        .putInt(1)
                        .put("ftyp".toByteArray())
                        .putLong(24)
                        .put("isom".toByteArray())
                        .putInt(0)
                        .array()
                input.writeBytes(extended)
                assertNull(stripAudioVideoMetadata(input, output))
            }
        }

    @Test fun fileTypeBoxesWithTruncatedOrInvalidBoundsFail() =
        runBlocking {
            withFiles { input, output ->
                val cases =
                    listOf(
                        ByteBuffer
                            .allocate(8)
                            .putInt(16)
                            .put("ftyp".toByteArray())
                            .array(),
                        ByteBuffer
                            .allocate(12)
                            .putInt(12)
                            .put("ftyp".toByteArray())
                            .putInt(0)
                            .array(),
                        ByteBuffer
                            .allocate(16)
                            .putInt(8192)
                            .put("ftyp".toByteArray())
                            .putLong(0)
                            .array(),
                        ByteBuffer
                            .allocate(12)
                            .putInt(1)
                            .put("ftyp".toByteArray())
                            .putInt(0)
                            .array(),
                        ByteBuffer
                            .allocate(24)
                            .putInt(1)
                            .put("ftyp".toByteArray())
                            .putLong(32)
                            .putLong(0)
                            .array(),
                        ByteBuffer
                            .allocate(24)
                            .putInt(1)
                            .put("ftyp".toByteArray())
                            .putLong(-1)
                            .putLong(0)
                            .array(),
                    )
                for (bytes in cases) {
                    input.writeBytes(bytes)
                    expectIo { stripAudioVideoMetadata(input, output) }
                }
            }
        }

    @Test fun cancellationIsNotConvertedIntoUnsupportedOrIoFailure() =
        runBlocking {
            withFiles { input, output ->
                input.writeBytes(page(0, 2, 0, listOf(opusHead())))
                launch {
                    cancel()
                    try {
                        stripAudioVideoMetadata(input, output)
                        fail("Expected cancellation")
                    } catch (_: CancellationException) {
                        Unit
                    }
                }.join()
            }
        }

    private suspend fun withFiles(block: suspend (File, File) -> Unit) {
        val directory = Files.createTempDirectory("media-privacy-test").toFile()
        try {
            block(File(directory, "source"), File(directory, "output"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun expectIo(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected malformed-media failure")
        } catch (_: IOException) {
            Unit
        }
    }

    private fun opusHead(): ByteArray =
        "OpusHead".toByteArray() +
            byteArrayOf(1, 1, 0, 0) + little(48000) + byteArrayOf(0, 0, 0)

    private fun tags(
        vendor: String,
        comments: List<String>,
    ): ByteArray {
        val vendorBytes = vendor.toByteArray()
        return "OpusTags".toByteArray() + little(vendorBytes.size) + vendorBytes + little(comments.size) +
            comments.fold(ByteArray(0)) { result, text -> text.toByteArray().let { result + little(it.size) + it } }
    }

    private fun little(value: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()

    private fun laces(size: Int): ByteArray = ByteArray(size / 255) { 255.toByte() } + byteArrayOf((size % 255).toByte())

    private fun page(
        sequence: Int,
        flags: Int,
        granule: Long,
        packets: List<ByteArray>,
    ): ByteArray =
        rawPage(
            sequence,
            flags,
            granule,
            packets.fold(ByteArray(0)) { bytes, packet -> bytes + laces(packet.size) },
            packets.fold(ByteArray(0)) { bytes, packet -> bytes + packet },
        )

    private fun rawPage(
        sequence: Int,
        flags: Int,
        granule: Long,
        segments: ByteArray,
        body: ByteArray,
    ): ByteArray {
        val bytes =
            ByteBuffer
                .allocate(27 + segments.size + body.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("OggS".toByteArray())
                .put(0.toByte())
                .put(flags.toByte())
                .putLong(granule)
                .putInt(1234)
                .putInt(sequence)
                .putInt(0)
                .put(segments.size.toByte())
                .put(segments)
                .put(body)
                .array()
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc(bytes))
        return bytes
    }

    private data class Page(
        val sequence: Int,
        val flags: Int,
        val granule: Long,
        val body: ByteArray,
    )

    private fun parsePages(bytes: ByteArray): List<Page> {
        val result = mutableListOf<Page>()
        var offset = 0
        while (offset < bytes.size) {
            val segments = bytes[offset + 26].toInt() and 255
            val size = (0 until segments).sumOf { bytes[offset + 27 + it].toInt() and 255 }
            val end = offset + 27 + segments + size
            val page = bytes.copyOfRange(offset, end)
            val header = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
            val checksum = header.getInt(22)
            header.putInt(22, 0)
            assertEquals("Each output page must have a valid checksum", checksum, crc(page))
            result += Page(header.getInt(18), page[5].toInt(), header.getLong(6), page.copyOfRange(27 + segments, page.size))
            offset = end
        }
        return result
    }

    private fun crc(bytes: ByteArray): Int {
        var result = 0
        for (byte in bytes) {
            result = result xor ((byte.toInt() and 255) shl 24)
            repeat(8) { result = if (result < 0) (result shl 1) xor 0x04c11db7 else result shl 1 }
        }
        return result
    }
}
