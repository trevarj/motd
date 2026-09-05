package io.github.trevarj.motd.audio

import io.github.trevarj.motd.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class AudioWaveformAnalyzer internal constructor(
    private val ioDispatcher: CoroutineDispatcher,
    private val decodeFile: suspend (File) -> LocalPcmAudioLease,
) {
    @Inject
    constructor(
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        decoder: PcmAudioDecoder,
    ) : this(ioDispatcher, decoder::decode)

    suspend fun analyze(file: File): AudioWaveform? =
        try {
            withContext(ioDispatcher) {
                decodeFile(file).use { lease ->
                    val context = currentCoroutineContext()
                    waveformFromPcm16Wav(lease.file) { context.ensureActive() }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
}

internal fun waveformFromPcm16Wav(
    file: File,
    checkCancellation: () -> Unit = {},
): AudioWaveform {
    val dataBytes = file.length() - WAV_HEADER_BYTES
    if (dataBytes < 0 || dataBytes and 1L != 0L) throw IllegalArgumentException("Invalid PCM WAV")
    val totalSamples = dataBytes / 2
    if (totalSamples == 0L) return AudioWaveform.EMPTY

    val peaks = IntArray(AudioWaveform.DISPLAY_PEAKS)
    val buffer = ByteArray(WAVEFORM_READ_BUFFER_BYTES)
    BufferedInputStream(FileInputStream(file), buffer.size).use { input ->
        var remainingHeader = WAV_HEADER_BYTES.toLong()
        while (remainingHeader > 0) {
            checkCancellation()
            val skipped = input.skip(remainingHeader)
            if (skipped > 0) {
                remainingHeader -= skipped
            } else if (input.read() < 0) {
                throw IllegalArgumentException("Invalid PCM WAV")
            } else {
                remainingHeader--
            }
        }

        var sampleIndex = 0L
        var lowByte = -1
        while (sampleIndex < totalSamples) {
            checkCancellation()
            val count = input.read(buffer)
            if (count < 0) throw IllegalArgumentException("Truncated PCM WAV")
            for (index in 0 until count) {
                val byte = buffer[index].toInt() and 0xff
                if (lowByte < 0) {
                    lowByte = byte
                } else {
                    val sample = (lowByte or (byte shl 8)).toShort().toInt()
                    val amplitude = abs(sample).coerceAtMost(Short.MAX_VALUE.toInt())
                    val bin = ((sampleIndex * peaks.size) / totalSamples).toInt().coerceAtMost(peaks.lastIndex)
                    if (amplitude > peaks[bin]) peaks[bin] = amplitude
                    sampleIndex++
                    lowByte = -1
                    if (sampleIndex == totalSamples) break
                }
            }
        }
    }
    return AudioWaveform.fromAmplitudes(peaks.toList())
}

private const val WAV_HEADER_BYTES = 44
private const val WAVEFORM_READ_BUFFER_BYTES = 8 * 1024
