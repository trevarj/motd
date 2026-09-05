package io.github.trevarj.motd.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import io.github.trevarj.motd.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

enum class PcmAudioFailureKind {
    NO_AUDIO,
    UNSUPPORTED_CODEC,
    TOO_LONG,
    DECODE_FAILED,
}

class PcmAudioException(
    val kind: PcmAudioFailureKind,
    cause: Throwable? = null,
) : Exception(kind.safeMessage, cause)

private val PcmAudioFailureKind.safeMessage: String
    get() =
        when (this) {
            PcmAudioFailureKind.NO_AUDIO -> "The file does not contain audio"
            PcmAudioFailureKind.UNSUPPORTED_CODEC -> "This audio codec is not supported"
            PcmAudioFailureKind.TOO_LONG -> "Audio must be 15 minutes or shorter"
            PcmAudioFailureKind.DECODE_FAILED -> "The audio could not be decoded"
        }

class LocalPcmAudioLease internal constructor(
    val file: File,
    val plaintextSha256: String,
    val durationMillis: Long,
    private val managed: AudioFileLease? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (managed != null) {
            managed.close()
        } else if (file.exists() && !file.delete()) {
            throw IOException("The decoded audio file could not be deleted")
        }
    }
}

internal enum class PcmSampleEncoding(
    val bytesPerSample: Int,
) {
    UNSIGNED_8(1),
    SIGNED_16(2),
    SIGNED_24(3),
    SIGNED_32(4),
    FLOAT_32(4),
}

internal data class DecodedPcmFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encoding: PcmSampleEncoding,
    val byteOrder: ByteOrder = ByteOrder.nativeOrder(),
)

internal fun interface Pcm16Sink {
    fun write(sample: Int)
}

/** Stateful so interpolation phase and the edge sample survive codec buffer boundaries. */
internal class Pcm16Converter(
    private val sink: Pcm16Sink,
    private val checkCancellation: () -> Unit = {},
    private val maxDurationSeconds: Long = MAX_DECODED_AUDIO_SECONDS,
) {
    private var sourceRate: Int? = null
    private var sourceFrames = 0L
    private var nextOutputFrame = 0L
    private var previousSample = 0

    var outputFrames: Long = 0
        private set

    val durationMillis: Long
        get() = sourceRate?.let { sourceFrames * 1_000L / it } ?: 0L

    init {
        require(maxDurationSeconds > 0)
    }

    fun consume(
        source: ByteBuffer,
        format: DecodedPcmFormat,
    ) {
        if (format.sampleRate <= 0 || format.channelCount <= 0) decodeFailed()
        val rate = sourceRate
        if (rate == null) {
            sourceRate = format.sampleRate
        } else if (rate != format.sampleRate) {
            decodeFailed()
        }

        val frameBytes = format.channelCount.toLong() * format.encoding.bytesPerSample
        if (frameBytes > Int.MAX_VALUE || source.remaining() % frameBytes.toInt() != 0) decodeFailed()
        val frameCount = source.remaining() / frameBytes.toInt()
        if (sourceFrames + frameCount > maxDurationSeconds * format.sampleRate) {
            throw PcmAudioException(PcmAudioFailureKind.TOO_LONG)
        }

        val samples = source.slice().order(format.byteOrder)
        repeat(frameCount) { frame ->
            if (frame and CANCELLATION_CHECK_MASK == 0) checkCancellation()
            var sum = 0L
            repeat(format.channelCount) {
                sum += samples.readSample(format.encoding)
            }
            acceptSourceSample((sum / format.channelCount).toInt())
            sourceFrames++
        }
    }

    fun finish() {
        val rate = sourceRate ?: return
        val end = sourceFrames * TARGET_SAMPLE_RATE
        while (nextOutputFrame * rate < end) {
            write(previousSample)
        }
    }

    private fun acceptSourceSample(sample: Int) {
        val rate = checkNotNull(sourceRate)
        val sourceIndex = sourceFrames
        if (sourceIndex == 0L) {
            write(sample)
            previousSample = sample
            return
        }

        val leftPosition = (sourceIndex - 1) * TARGET_SAMPLE_RATE
        val rightPosition = sourceIndex * TARGET_SAMPLE_RATE
        while (nextOutputFrame * rate <= rightPosition) {
            val fraction = nextOutputFrame * rate - leftPosition
            val delta = (sample - previousSample).toLong() * fraction
            val rounded = if (delta >= 0) delta + TARGET_SAMPLE_RATE / 2 else delta - TARGET_SAMPLE_RATE / 2
            write((previousSample + rounded / TARGET_SAMPLE_RATE).toInt())
        }
        previousSample = sample
    }

    private fun write(sample: Int) {
        if (outputFrames and CANCELLATION_CHECK_MASK.toLong() == 0L) checkCancellation()
        sink.write(sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        outputFrames++
        nextOutputFrame++
    }
}

internal interface PcmDecodeSession : AutoCloseable {
    fun decode(
        checkCancellation: () -> Unit,
        consume: (DecodedPcmFormat, ByteBuffer) -> Unit,
    )
}

internal fun interface PcmDecodeSessionFactory {
    fun open(
        input: File,
        checkCancellation: () -> Unit,
    ): PcmDecodeSession
}

@Singleton
class PcmAudioDecoder internal constructor(
    private val createOutputLease: () -> AudioFileLease,
    private val ioDispatcher: CoroutineDispatcher,
    private val sessionFactory: PcmDecodeSessionFactory,
    private val maxDurationSeconds: Long = MAX_DECODED_AUDIO_SECONDS,
    private val openOutput: (File) -> RandomAccessFile = { RandomAccessFile(it, "rw") },
) {
    @Inject
    constructor(
        cacheStore: AudioCacheStore,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        createOutputLease = cacheStore::pcmLease,
        ioDispatcher = ioDispatcher,
        sessionFactory =
            PcmDecodeSessionFactory { input, checkCancellation ->
                AndroidPcmDecodeSession(input, checkCancellation)
            },
    )

    suspend fun decode(input: File): LocalPcmAudioLease {
        val produced = AtomicReference<LocalPcmAudioLease?>()
        return try {
            withContext(ioDispatcher) {
                val context = currentCoroutineContext()
                val checkCancellation = { context.ensureActive() }
                try {
                    decodeOnIo(input, checkCancellation).also(produced::set)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: PcmAudioException) {
                    throw failure
                } catch (failure: Exception) {
                    throw PcmAudioException(PcmAudioFailureKind.DECODE_FAILED, failure)
                }
            }.also { delivered -> produced.compareAndSet(delivered, null) }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable + ioDispatcher) {
                try {
                    produced.getAndSet(null)?.close()
                } catch (cleanupFailure: Throwable) {
                    cancelled.addSuppressed(cleanupFailure)
                }
            }
            throw cancelled
        }
    }

    private fun decodeOnIo(
        input: File,
        checkCancellation: () -> Unit,
    ): LocalPcmAudioLease {
        checkCancellation()
        val outputLease = createOutputLease()
        val output = outputLease.file
        var leased = false
        var failure: Throwable? = null
        try {
            var sha256: String? = null
            var durationMillis = 0L
            openOutput(output).use { target ->
                val writer = Pcm16WavWriter(target, checkCancellation)
                val converter = Pcm16Converter(writer, checkCancellation, maxDurationSeconds)
                sessionFactory.open(input, checkCancellation).use { session ->
                    session.decode(checkCancellation) { format, buffer ->
                        checkCancellation()
                        converter.consume(buffer, format)
                    }
                }
                checkCancellation()
                converter.finish()
                if (converter.outputFrames == 0L) decodeFailed()
                writer.flushSamples()
                checkCancellation()
                sha256 = input.sha256(checkCancellation)
                checkCancellation()
                writer.completeHeader()
                checkCancellation()
                durationMillis = converter.durationMillis
            }
            checkCancellation()
            val lease = LocalPcmAudioLease(output, checkNotNull(sha256), durationMillis, outputLease)
            leased = true
            return lease
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (!leased) {
                try {
                    outputLease.close()
                } catch (cleanupFailure: Throwable) {
                    failure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
                }
            }
        }
    }
}

private class Pcm16WavWriter(
    private val target: RandomAccessFile,
    private val checkCancellation: () -> Unit,
) : Pcm16Sink {
    private val buffer = ByteArray(WRITE_BUFFER_BYTES)
    private var buffered = 0
    private var dataBytes = 0L

    init {
        target.setLength(0)
        target.writeAscii("RIFF")
        target.writeLittleEndianInt(0)
        target.writeAscii("WAVE")
        target.writeAscii("fmt ")
        target.writeLittleEndianInt(16)
        target.writeLittleEndianShort(1)
        target.writeLittleEndianShort(1)
        target.writeLittleEndianInt(TARGET_SAMPLE_RATE)
        target.writeLittleEndianInt(TARGET_SAMPLE_RATE * PCM_BYTES_PER_SAMPLE)
        target.writeLittleEndianShort(PCM_BYTES_PER_SAMPLE)
        target.writeLittleEndianShort(PCM_BITS_PER_SAMPLE)
        target.writeAscii("data")
        target.writeLittleEndianInt(0)
    }

    override fun write(sample: Int) {
        if (buffered == buffer.size) flushSamples()
        buffer[buffered++] = sample.toByte()
        buffer[buffered++] = (sample ushr 8).toByte()
        dataBytes += PCM_BYTES_PER_SAMPLE
    }

    fun flushSamples() {
        if (buffered == 0) return
        checkCancellation()
        target.write(buffer, 0, buffered)
        buffered = 0
    }

    fun completeHeader() {
        flushSamples()
        if (dataBytes > WAV_MAX_DATA_BYTES) decodeFailed()
        target.seek(4)
        target.writeLittleEndianInt((WAV_HEADER_BYTES - 8 + dataBytes).toInt())
        target.seek(40)
        target.writeLittleEndianInt(dataBytes.toInt())
        checkCancellation()
        target.fd.sync()
    }
}

private class AndroidPcmDecodeSession(
    input: File,
    checkCancellation: () -> Unit,
) : PcmDecodeSession {
    private val extractor = MediaExtractor()
    private var decoder: MediaCodec? = null
    private var decoderStarted = false
    private var closed = false
    private var outputFormat: DecodedPcmFormat

    init {
        try {
            checkCancellation()
            extractor.setDataSource(input.absolutePath)
            val track =
                firstAudioTrack(extractor.trackCount) { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                } ?: throw PcmAudioException(PcmAudioFailureKind.NO_AUDIO)
            extractor.selectTrack(track)
            val inputFormat = extractor.getTrackFormat(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME) ?: throw PcmAudioException(PcmAudioFailureKind.NO_AUDIO)
            checkCancellation()
            val activeDecoder =
                try {
                    MediaCodec.createDecoderByType(mime)
                } catch (failure: Exception) {
                    throw PcmAudioException(PcmAudioFailureKind.UNSUPPORTED_CODEC, failure)
                }
            decoder = activeDecoder
            try {
                activeDecoder.configure(inputFormat, null, null, 0)
                activeDecoder.start()
                decoderStarted = true
            } catch (failure: Exception) {
                throw PcmAudioException(PcmAudioFailureKind.UNSUPPORTED_CODEC, failure)
            }
            outputFormat = inputFormat.toDecodedPcmFormat()
        } catch (failure: Throwable) {
            close()
            throw failure
        }
    }

    override fun decode(
        checkCancellation: () -> Unit,
        consume: (DecodedPcmFormat, ByteBuffer) -> Unit,
    ) {
        val activeDecoder = decoder ?: decodeFailed()
        val info = MediaCodec.BufferInfo()
        var inputEnded = false
        var outputEnded = false
        while (!outputEnded) {
            checkCancellation()
            if (!inputEnded) {
                val inputIndex = activeDecoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex >= 0) {
                    val buffer = activeDecoder.getInputBuffer(inputIndex) ?: decodeFailed()
                    buffer.clear()
                    checkCancellation()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        activeDecoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEnded = true
                    } else {
                        activeDecoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0), 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = activeDecoder.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outputFormat = activeDecoder.outputFormat.toDecodedPcmFormat()
                }

                MediaCodec.INFO_TRY_AGAIN_LATER -> {}

                else -> {
                    if (outputIndex >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val output = activeDecoder.getOutputBuffer(outputIndex) ?: decodeFailed()
                                val end = info.offset.toLong() + info.size
                                if (info.offset < 0 || info.size < 0 || end > output.capacity()) decodeFailed()
                                output.limit(end.toInt())
                                output.position(info.offset)
                                checkCancellation()
                                consume(outputFormat, output.slice().order(ByteOrder.nativeOrder()))
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            activeDecoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (decoderStarted) runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { extractor.release() }
    }
}

internal fun firstAudioTrack(
    trackCount: Int,
    mimeAt: (Int) -> String?,
): Int? {
    for (index in 0 until trackCount) {
        if (mimeAt(index)?.startsWith("audio/") == true) return index
    }
    return null
}

private fun MediaFormat.toDecodedPcmFormat(): DecodedPcmFormat {
    val sampleRate = integer(MediaFormat.KEY_SAMPLE_RATE)
    val channelCount = integer(MediaFormat.KEY_CHANNEL_COUNT)
    val encoding =
        when (integer(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)) {
            AudioFormat.ENCODING_PCM_8BIT -> PcmSampleEncoding.UNSIGNED_8

            AudioFormat.ENCODING_DEFAULT,
            AudioFormat.ENCODING_PCM_16BIT,
            -> PcmSampleEncoding.SIGNED_16

            AudioFormat.ENCODING_PCM_24BIT_PACKED -> PcmSampleEncoding.SIGNED_24

            AudioFormat.ENCODING_PCM_32BIT -> PcmSampleEncoding.SIGNED_32

            AudioFormat.ENCODING_PCM_FLOAT -> PcmSampleEncoding.FLOAT_32

            else -> throw PcmAudioException(PcmAudioFailureKind.UNSUPPORTED_CODEC)
        }
    return DecodedPcmFormat(sampleRate, channelCount, encoding)
}

private fun MediaFormat.integer(
    key: String,
    default: Int? = null,
): Int = if (containsKey(key)) getInteger(key) else default ?: decodeFailed()

private fun ByteBuffer.readSample(encoding: PcmSampleEncoding): Int =
    when (encoding) {
        PcmSampleEncoding.UNSIGNED_8 -> {
            ((get().toInt() and 0xff) - 128) shl 8
        }

        PcmSampleEncoding.SIGNED_16 -> {
            short.toInt()
        }

        PcmSampleEncoding.SIGNED_24 -> {
            val first = get().toInt() and 0xff
            val second = get().toInt() and 0xff
            val third = get().toInt() and 0xff
            val packed =
                if (order() == ByteOrder.LITTLE_ENDIAN) {
                    first or (second shl 8) or (third shl 16)
                } else {
                    third or (second shl 8) or (first shl 16)
                }
            ((packed shl 8) shr 8) shr 8
        }

        PcmSampleEncoding.SIGNED_32 -> {
            int shr 16
        }

        PcmSampleEncoding.FLOAT_32 -> {
            val sample = float
            if (!sample.isFinite()) decodeFailed()
            (sample.coerceIn(-1f, 1f) * 32_768f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        }
    }

private fun File.sha256(checkCancellation: () -> Unit): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(HASH_BUFFER_BYTES)
    FileInputStream(this).use { input ->
        while (true) {
            checkCancellation()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
    }
    val digits = "0123456789abcdef"
    return buildString(64) {
        digest.digest().forEach { byte ->
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0xf])
        }
    }
}

private fun RandomAccessFile.writeAscii(value: String) {
    write(value.toByteArray(StandardCharsets.US_ASCII))
}

private fun RandomAccessFile.writeLittleEndianShort(value: Int) {
    write(value)
    write(value ushr 8)
}

private fun RandomAccessFile.writeLittleEndianInt(value: Int) {
    write(value)
    write(value ushr 8)
    write(value ushr 16)
    write(value ushr 24)
}

private fun decodeFailed(): Nothing = throw PcmAudioException(PcmAudioFailureKind.DECODE_FAILED)

internal const val TARGET_SAMPLE_RATE = 16_000
internal const val MAX_DECODED_AUDIO_SECONDS = 15L * 60L
private const val PCM_BITS_PER_SAMPLE = 16
private const val PCM_BYTES_PER_SAMPLE = PCM_BITS_PER_SAMPLE / 8
private const val WAV_HEADER_BYTES = 44
private const val WAV_MAX_DATA_BYTES = 0xffff_ffffL
private const val WRITE_BUFFER_BYTES = 8 * 1024
private const val HASH_BUFFER_BYTES = 32 * 1024
private const val DEQUEUE_TIMEOUT_US = 10_000L
private const val CANCELLATION_CHECK_MASK = 0x3ff
