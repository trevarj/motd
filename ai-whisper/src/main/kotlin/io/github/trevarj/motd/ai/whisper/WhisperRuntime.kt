package io.github.trevarj.motd.ai.whisper

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import java.util.function.IntConsumer

public data class WhisperModelInfo(
    val modelType: String,
    val isMultilingual: Boolean,
    val vocabularySize: Int,
    val audioContext: Int,
    val textContext: Int,
    val melBins: Int,
    val quantization: String,
    val sampleRate: Int,
    val maxAudioSeconds: Int,
    val maxThreads: Int,
)

public data class TranscriptionRequest(
    val language: String = "auto",
    val initialPrompt: String = "",
    val cpuThreads: Int = defaultWhisperThreads(),
)

public sealed class WhisperException(
    message: String,
) : RuntimeException(message)

public class WhisperModelOpenException(
    message: String,
) : WhisperException(message)

public class WhisperModelMagicException(
    message: String,
) : WhisperException(message)

public class WhisperTruncatedModelException(
    message: String,
) : WhisperException(message)

public class WhisperCorruptModelException(
    message: String,
) : WhisperException(message)

public class WhisperUnsupportedModelException(
    message: String,
) : WhisperException(message)

public class WhisperAudioException(
    message: String,
) : WhisperException(message)

public class WhisperInvalidRequestException(
    message: String,
) : WhisperException(message)

public class WhisperNoModelLoadedException(
    message: String,
) : WhisperException(message)

public class WhisperTranscriptionException(
    message: String,
) : WhisperException(message)

public class WhisperOutOfMemoryException(
    message: String,
) : WhisperException(message)

public class WhisperNativeException(
    message: String,
) : WhisperException(message)

public class WhisperCancellationException(
    message: String,
) : CancellationException(message)

public object WhisperRuntime {
    public const val SAMPLE_RATE: Int = 16_000
    public const val MAX_AUDIO_SECONDS: Int = 15 * 60

    private val requestIds = AtomicLong(0)

    init {
        System.loadLibrary("motd_whisper")
    }

    /** Probe-loads [model], returns its limits, and leaves no model resident. */
    public suspend fun inspect(model: File): WhisperModelInfo =
        nativeRequest { requestId ->
            nativeInspect(requestId, model.absolutePath.toByteArray(Charsets.UTF_8))
        }

    /** Replaces the resident model with [model] and returns its limits. */
    public suspend fun load(model: File): WhisperModelInfo =
        nativeRequest { requestId ->
            nativeLoad(requestId, model.absolutePath.toByteArray(Charsets.UTF_8))
        }

    /** Transcribes a strict mono, 16 kHz, signed PCM16 little-endian WAV. */
    public suspend fun transcribe(
        pcmWav: File,
        request: TranscriptionRequest,
        onProgress: (Int) -> Unit,
    ): String =
        nativeRequest { requestId ->
            nativeTranscribe(
                requestId = requestId,
                wavPath = pcmWav.absolutePath.toByteArray(Charsets.UTF_8),
                language = request.language.toByteArray(Charsets.UTF_8),
                initialPrompt = request.initialPrompt.toByteArray(Charsets.UTF_8),
                cpuThreads = request.cpuThreads,
                progress = IntConsumer(onProgress),
            ).toString(Charsets.UTF_8)
        }

    /** Frees the resident model after any in-flight native operation exits. */
    public fun unload() {
        val requestId = nextRequestId()
        nativeBegin(requestId)
        try {
            nativeUnload(requestId)
        } finally {
            nativeEnd(requestId)
        }
    }

    private suspend fun <T> nativeRequest(block: (Long) -> T): T =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val requestId = nextRequestId()
                try {
                    nativeBegin(requestId)
                } catch (failure: Throwable) {
                    continuation.resumeWith(Result.failure(failure))
                    return@suspendCancellableCoroutine
                }

                continuation.invokeOnCancellation {
                    try {
                        nativeCancel(requestId)
                    } catch (_: Throwable) {
                        // Cancellation must never be replaced by a cleanup failure.
                    }
                }

                var outcome: Result<T> =
                    if (continuation.isActive) {
                        runCatching { block(requestId) }
                    } else {
                        Result.failure(WhisperCancellationException("Whisper request cancelled"))
                    }
                try {
                    nativeEnd(requestId)
                } catch (cleanupFailure: Throwable) {
                    outcome.exceptionOrNull()?.addSuppressed(cleanupFailure)
                    if (outcome.isSuccess) outcome = Result.failure(cleanupFailure)
                }
                continuation.resumeWith(outcome)
            }
        }

    private fun nextRequestId(): Long =
        requestIds.updateAndGet { current ->
            if (current == Long.MAX_VALUE) {
                throw WhisperNativeException("Whisper request ID space exhausted")
            }
            current + 1
        }

    private external fun nativeBegin(requestId: Long)

    private external fun nativeEnd(requestId: Long)

    private external fun nativeCancel(requestId: Long)

    private external fun nativeInspect(
        requestId: Long,
        modelPath: ByteArray,
    ): WhisperModelInfo

    private external fun nativeLoad(
        requestId: Long,
        modelPath: ByteArray,
    ): WhisperModelInfo

    private external fun nativeTranscribe(
        requestId: Long,
        wavPath: ByteArray,
        language: ByteArray,
        initialPrompt: ByteArray,
        cpuThreads: Int,
        progress: IntConsumer,
    ): ByteArray

    private external fun nativeUnload(requestId: Long)
}

private fun defaultWhisperThreads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
