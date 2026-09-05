package io.github.trevarj.motd.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

class PcmAudioDecoderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `first audio track is selected and missing audio is rejected without a temp`() =
        runTest {
            assertEquals(
                1,
                firstAudioTrack(3) { index -> listOf("video/avc", "audio/opus", "audio/aac")[index] },
            )
            assertNull(firstAudioTrack(2) { "video/avc" })

            val input = temporaryFolder.newFile("no-audio.bin")
            val outputRoot = temporaryFolder.newFolder("no-audio-output")
            val decoder =
                decoder(outputRoot) { _, _, _ ->
                    throw PcmAudioException(PcmAudioFailureKind.NO_AUDIO)
                }

            assertEquals(PcmAudioFailureKind.NO_AUDIO, pcmFailure { decoder.decode(input) }.kind)
            assertTrue(outputRoot.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `unsupported codec is typed and leaves no temp`() =
        runTest {
            val input = temporaryFolder.newFile("unsupported.bin")
            val outputRoot = temporaryFolder.newFolder("unsupported-output")
            val decoder =
                decoder(outputRoot) { _, _, _ ->
                    throw PcmAudioException(PcmAudioFailureKind.UNSUPPORTED_CODEC)
                }

            assertEquals(PcmAudioFailureKind.UNSUPPORTED_CODEC, pcmFailure { decoder.decode(input) }.kind)
            assertTrue(outputRoot.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `empty decoded audio is rejected so every lease is strict-parser ready`() =
        runTest {
            val input = temporaryFolder.newFile("empty.bin")
            val outputRoot = temporaryFolder.newFolder("empty-output")
            val decoder =
                decoder(outputRoot) { _, _, consume ->
                    consume(
                        DecodedPcmFormat(16_000, 1, PcmSampleEncoding.SIGNED_16, ByteOrder.LITTLE_ENDIAN),
                        ByteBuffer.allocate(0),
                    )
                }

            assertEquals(PcmAudioFailureKind.DECODE_FAILED, pcmFailure { decoder.decode(input) }.kind)
            assertTrue(outputRoot.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `stereo is averaged before conversion to mono`() {
        val output = mutableListOf<Int>()
        val converter = Pcm16Converter(Pcm16Sink { output += it })

        converter.consume(
            pcm16(-3_000, 1_000, 1_000, 3_000),
            DecodedPcmFormat(16_000, 2, PcmSampleEncoding.SIGNED_16, ByteOrder.LITTLE_ENDIAN),
        )
        converter.finish()

        assertEquals(listOf(-1_000, 2_000), output)
    }

    @Test
    fun `linear resampling is stable across codec buffer boundaries at common rates`() {
        val source = shortArrayOf(0, 2_000, 4_000, 6_000, 8_000, 10_000)
        val expected =
            mapOf(
                8_000 to listOf(0, 1_000, 2_000, 3_000, 4_000, 5_000, 6_000, 7_000, 8_000, 9_000, 10_000, 10_000),
                16_000 to listOf(0, 2_000, 4_000, 6_000, 8_000, 10_000),
                48_000 to listOf(0, 6_000),
            )

        expected.forEach { (rate, samples) ->
            assertEquals(samples, convert(rate, listOf(source)))
            assertEquals(samples, convert(rate, source.map { shortArrayOf(it) }))
        }
    }

    @Test
    fun `decode writes canonical mono PCM16 WAV and hashes plaintext input`() =
        runTest {
            val input = temporaryFolder.newFile("plaintext.bin").apply { writeText("abc") }
            val outputRoot = temporaryFolder.newFolder("wav-output")
            val format = DecodedPcmFormat(8_000, 2, PcmSampleEncoding.SIGNED_16, ByteOrder.LITTLE_ENDIAN)
            val decoder =
                decoder(outputRoot) { _, checkCancellation, consume ->
                    checkCancellation()
                    consume(format, pcm16(-1_000, 1_000))
                    consume(format, pcm16(1_000, 3_000, 3_000, 5_000))
                }

            val lease = decoder.decode(input)
            val wav = lease.file.readBytes()
            assertEquals("RIFF", String(wav, 0, 4, StandardCharsets.US_ASCII))
            assertEquals(wav.size - 8, wav.littleEndianInt(4))
            assertEquals("WAVE", String(wav, 8, 4, StandardCharsets.US_ASCII))
            assertEquals("fmt ", String(wav, 12, 4, StandardCharsets.US_ASCII))
            assertEquals(16, wav.littleEndianInt(16))
            assertEquals(1, wav.littleEndianShort(20))
            assertEquals(1, wav.littleEndianShort(22))
            assertEquals(16_000, wav.littleEndianInt(24))
            assertEquals(32_000, wav.littleEndianInt(28))
            assertEquals(2, wav.littleEndianShort(32))
            assertEquals(16, wav.littleEndianShort(34))
            assertEquals("data", String(wav, 36, 4, StandardCharsets.US_ASCII))
            assertEquals(wav.size - 44, wav.littleEndianInt(40))
            assertEquals(listOf(0, 1_000, 2_000, 3_000, 4_000, 4_000), wav.pcm16Samples())
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", lease.plaintextSha256)

            val output = lease.file
            lease.close()
            assertFalse(output.exists())
        }

    @Test
    fun `decoded duration cap uses samples rather than container metadata`() =
        runTest {
            val input = temporaryFolder.newFile("duration.bin")
            val format = DecodedPcmFormat(2, 1, PcmSampleEncoding.SIGNED_16, ByteOrder.LITTLE_ENDIAN)
            val exactRoot = temporaryFolder.newFolder("duration-exact")
            val exact =
                decoder(exactRoot, maxDurationSeconds = 1) { _, _, consume ->
                    consume(format, pcm16(1, 2))
                }.decode(input)
            assertEquals(1_000L, exact.durationMillis)
            exact.close()

            val tooLongRoot = temporaryFolder.newFolder("duration-too-long")
            val tooLong =
                decoder(tooLongRoot, maxDurationSeconds = 1) { _, _, consume ->
                    consume(format, pcm16(1, 2, 3))
                }
            assertEquals(PcmAudioFailureKind.TOO_LONG, pcmFailure { tooLong.decode(input) }.kind)
            assertTrue(tooLongRoot.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `cancellation releases the media session and deletes partial output`() =
        runTest {
            val input = temporaryFolder.newFile("cancel.bin")
            val outputRoot = temporaryFolder.newFolder("cancel-output")
            val operation = Job()
            var released = false
            val session =
                object : PcmDecodeSession {
                    override fun decode(
                        checkCancellation: () -> Unit,
                        consume: (DecodedPcmFormat, ByteBuffer) -> Unit,
                    ) {
                        consume(
                            DecodedPcmFormat(16_000, 1, PcmSampleEncoding.SIGNED_16, ByteOrder.LITTLE_ENDIAN),
                            pcm16(1, 2, 3),
                        )
                        operation.cancel()
                        checkCancellation()
                    }

                    override fun close() {
                        released = true
                    }
                }
            val decoder =
                PcmAudioDecoder(
                    createOutputLease = { outputLease(outputRoot) },
                    ioDispatcher = Dispatchers.Unconfined,
                    sessionFactory = PcmDecodeSessionFactory { _, _ -> session },
                )

            var cancelled = false
            try {
                withContext(operation) {
                    decoder.decode(input)
                }
            } catch (_: CancellationException) {
                cancelled = true
            }

            assertTrue(cancelled)
            assertTrue(released)
            assertTrue(outputRoot.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `output close failure keeps the WAV unleased so cleanup removes it`() =
        runTest {
            val input = temporaryFolder.newFile("close-failure.bin")
            val outputRoot = temporaryFolder.newFolder("close-failure-output")
            val decoder =
                decoder(
                    outputRoot = outputRoot,
                    openOutput = { file ->
                        object : RandomAccessFile(file, "rw") {
                            override fun close() {
                                super.close()
                                throw IOException("forced close failure")
                            }
                        }
                    },
                ) { _, _, consume ->
                    consume(
                        DecodedPcmFormat(16_000, 1, PcmSampleEncoding.SIGNED_16, ByteOrder.LITTLE_ENDIAN),
                        pcm16(1, 2, 3),
                    )
                }

            assertEquals(PcmAudioFailureKind.DECODE_FAILED, pcmFailure { decoder.decode(input) }.kind)
            assertTrue(outputRoot.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `cancellation after decode but before dispatcher delivery closes the produced WAV lease`() =
        runTest {
            val input = temporaryFolder.newFile("delivery-cancel.bin")
            val outputRoot = temporaryFolder.newFolder("delivery-cancel-output")
            val decoder =
                decoder(outputRoot, ioDispatcher = Dispatchers.IO) { _, _, consume ->
                    consume(
                        DecodedPcmFormat(16_000, 1, PcmSampleEncoding.SIGNED_16, ByteOrder.LITTLE_ENDIAN),
                        pcm16(1, 2, 3),
                    )
                }
            val dispatcher = HoldReturnDispatcher()
            val job = launch(dispatcher) { decoder.decode(input) }

            assertTrue(withContext(Dispatchers.IO) { dispatcher.returnQueued.await(2, TimeUnit.SECONDS) })
            assertEquals(1, outputRoot.listFiles().orEmpty().size)
            job.cancel()
            dispatcher.release()
            job.join()

            assertTrue(outputRoot.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `waveform analysis reuses decoded WAV and closes its lease`() =
        runTest {
            val input = temporaryFolder.newFile("waveform.bin")
            val outputRoot = temporaryFolder.newFolder("waveform-output")
            val samples = ShortArray(AudioWaveform.DISPLAY_PEAKS) { index -> ((index + 1) * 600).toShort() }
            val decoder =
                decoder(outputRoot) { _, _, consume ->
                    val format = DecodedPcmFormat(16_000, 1, PcmSampleEncoding.SIGNED_16, ByteOrder.LITTLE_ENDIAN)
                    consume(format, pcm16(*samples.copyOfRange(0, 17)))
                    consume(format, pcm16(*samples.copyOfRange(17, 33)))
                    consume(format, pcm16(*samples.copyOfRange(33, samples.size)))
                }
            val analyzer = AudioWaveformAnalyzer(Dispatchers.Unconfined, decoder::decode)

            val waveform = analyzer.analyze(input)

            assertEquals(AudioWaveform.fromAmplitudes(samples.map { abs(it.toInt()) }), waveform)
            assertTrue(outputRoot.listFiles().orEmpty().isEmpty())
        }

    private fun decoder(
        outputRoot: File,
        maxDurationSeconds: Long = MAX_DECODED_AUDIO_SECONDS,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        openOutput: (File) -> RandomAccessFile = { RandomAccessFile(it, "rw") },
        mediaDecode: (
            input: File,
            checkCancellation: () -> Unit,
            consume: (DecodedPcmFormat, ByteBuffer) -> Unit,
        ) -> Unit,
    ): PcmAudioDecoder =
        PcmAudioDecoder(
            createOutputLease = { outputLease(outputRoot) },
            ioDispatcher = ioDispatcher,
            sessionFactory =
                PcmDecodeSessionFactory { input, _ ->
                    object : PcmDecodeSession {
                        override fun decode(
                            checkCancellation: () -> Unit,
                            consume: (DecodedPcmFormat, ByteBuffer) -> Unit,
                        ) {
                            mediaDecode(input, checkCancellation, consume)
                        }

                        override fun close() = Unit
                    }
                },
            maxDurationSeconds = maxDurationSeconds,
            openOutput = openOutput,
        )

    private class HoldReturnDispatcher : CoroutineDispatcher() {
        private val entered = AtomicBoolean()
        private val released = AtomicBoolean()
        private val queued = ConcurrentLinkedQueue<Runnable>()
        val returnQueued = CountDownLatch(1)

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (entered.compareAndSet(false, true) || released.get()) {
                block.run()
            } else {
                queued += block
                returnQueued.countDown()
            }
        }

        fun release() {
            released.set(true)
            while (true) queued.poll()?.run() ?: return
        }
    }

    private fun outputLease(root: File): AudioFileLease {
        check(root.isDirectory || root.mkdirs())
        val file = File.createTempFile("pcm-", ".wav", root)
        return AudioFileLease(file) { target -> check(!target.exists() || target.delete()) }
    }

    private suspend fun pcmFailure(block: suspend () -> Unit): PcmAudioException =
        try {
            block()
            throw AssertionError("Expected PcmAudioException")
        } catch (failure: PcmAudioException) {
            failure
        }

    private fun convert(
        sampleRate: Int,
        chunks: List<ShortArray>,
    ): List<Int> {
        val output = mutableListOf<Int>()
        val converter = Pcm16Converter(Pcm16Sink { output += it })
        val format = DecodedPcmFormat(sampleRate, 1, PcmSampleEncoding.SIGNED_16, ByteOrder.LITTLE_ENDIAN)
        chunks.forEach { converter.consume(pcm16(*it), format) }
        converter.finish()
        return output
    }

    private fun pcm16(vararg samples: Short): ByteBuffer =
        ByteBuffer
            .allocate(samples.size * 2)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                samples.forEach { putShort(it) }
                flip()
            }

    private fun pcm16(vararg samples: Int): ByteBuffer = pcm16(*samples.map(Int::toShort).toShortArray())

    private fun ByteArray.pcm16Samples(): List<Int> = (44 until size step 2).map { index -> littleEndianShort(index).toShort().toInt() }

    private fun ByteArray.littleEndianShort(offset: Int): Int = (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.littleEndianInt(offset: Int): Int = littleEndianShort(offset) or (littleEndianShort(offset + 2) shl 16)
}
