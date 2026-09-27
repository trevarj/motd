package io.github.trevarj.motd.dcc

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class DccStreamsTest {
    @Test fun `receive streams declared bytes and writes acknowledgements`() =
        runTest {
            val output = ByteArrayOutputStream()
            val ack = ByteArrayOutputStream()
            val progress = mutableListOf<Long>()

            val total =
                receiveDccBytes(
                    input = ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                    output = output,
                    ack = ack,
                    expectedBytes = 3,
                    maxBytes = 8,
                    progressStepBytes = 2,
                    persistProgress = { progress += it },
                )

            assertEquals(3, total)
            assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
            assertArrayEquals(
                byteArrayOf(
                    0,
                    0,
                    0,
                    3,
                ),
                ack.toByteArray(),
            )
            assertEquals(listOf(3L), progress)
        }

    @Test fun `declared size truncation fails after preserving partial progress`() =
        runTest {
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<Long>()

            val error =
                expectIntegrityFailure {
                    receiveDccBytes(
                        input = ByteArrayInputStream(byteArrayOf(1, 2)),
                        output = output,
                        ack = ByteArrayOutputStream(),
                        expectedBytes = 3,
                        maxBytes = 8,
                        progressStepBytes = 2,
                        persistProgress = { progress += it },
                    )
                }

            assertEquals("Transfer ended after 2 of 3 bytes", error.message)
            assertArrayEquals(byteArrayOf(1, 2), output.toByteArray())
            assertEquals(listOf(2L), progress)
        }

    @Test fun `unknown size transfer fails when it exceeds safety limit`() =
        runTest {
            val output = ByteArrayOutputStream()
            val progress = mutableListOf<Long>()

            val error =
                expectIntegrityFailure {
                    receiveDccBytes(
                        input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                        output = output,
                        ack = ByteArrayOutputStream(),
                        expectedBytes = null,
                        maxBytes = 3,
                        progressStepBytes = 2,
                        persistProgress = { progress += it },
                    )
                }

            assertEquals("Transfer exceeded the DCC size limit", error.message)
            assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
            assertEquals(listOf(3L), progress)
        }

    @Test fun `cached results cap also applies to unknown size receive stream`() =
        runTest {
            var remaining = EbooksResultCache.MAX_COMPRESSED_BYTES + 1
            var written = 0L
            val input =
                object : InputStream() {
                    override fun read(): Int = if (remaining-- > 0) 0 else -1

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (remaining <= 0) return -1
                        val count = minOf(length.toLong(), remaining).toInt()
                        remaining -= count
                        return count
                    }
                }
            val output =
                object : OutputStream() {
                    override fun write(value: Int) {
                        written++
                    }

                    override fun write(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ) {
                        written += length
                    }
                }
            val error =
                expectIntegrityFailure {
                    receiveDccBytes(
                        input = input,
                        output = output,
                        ack = ByteArrayOutputStream(),
                        expectedBytes = null,
                        maxBytes = EbooksResultCache.MAX_COMPRESSED_BYTES,
                        progressStepBytes = EbooksResultCache.MAX_COMPRESSED_BYTES,
                        persistProgress = {},
                    )
                }
            assertEquals("Transfer exceeded the DCC size limit", error.message)
            assertEquals(EbooksResultCache.MAX_COMPRESSED_BYTES, written)
        }

    private suspend fun expectIntegrityFailure(block: suspend () -> Unit): DccTransferIntegrityException =
        try {
            block()
            fail("Expected DccTransferIntegrityException")
            error("unreachable")
        } catch (error: DccTransferIntegrityException) {
            error
        }
}
