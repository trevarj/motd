package io.github.trevarj.motd.ai.text

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong

public enum class TextOperation(
    public val nativeCode: Int,
) {
    CORRECT(0),
    FORMAL(1),
    BUSINESS(2),
    SILLY(3),
    CUSTOM(4),
    TRANSLATE(5),
}

public enum class TextTermination { EOG, OUTPUT_LIMIT }

public data class TextModelInfo(
    val architecture: String,
    val quantization: String,
    val maximumContextTokens: Int,
    val templateId: String,
    val maximumCpuThreads: Int,
)

public data class TextTransformRequest(
    val operation: TextOperation,
    val text: String,
    val instruction: String = "",
    val targetLanguage: String = "",
    val cpuThreads: Int = defaultTextThreads(),
)

public data class TextTransformResult(
    val text: String,
    val termination: TextTermination,
)

public class TextException(
    public val code: Int,
) : RuntimeException("Local text operation failed ($code)") {
    public companion object {
        public const val MODEL_OPEN = 1
        public const val INVALID_FORMAT = 2
        public const val CORRUPT_MODEL = 3
        public const val UNSUPPORTED_ARCHITECTURE = 4
        public const val UNSUPPORTED_TEMPLATE = 5
        public const val INVALID_REQUEST = 6
        public const val INPUT_TOO_LONG = 7
        public const val OUT_OF_MEMORY = 8
        public const val NO_MODEL_LOADED = 9
        public const val INFERENCE = 10
        public const val INVALID_OUTPUT = 11
        public const val NATIVE = 12
    }
}

private data class NativeTextResult(
    val textUtf8: ByteArray,
    val stopCode: Int,
)

public fun defaultTextThreads(): Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

public object TextRuntime {
    private val requestIds = AtomicLong(0)

    init {
        System.loadLibrary("motd_text")
    }

    public suspend fun inspect(model: File): TextModelInfo = nativeRequest { nativeInspect(it, encode(model.absolutePath, Int.MAX_VALUE)) }

    public suspend fun load(
        model: File,
        cpuThreads: Int,
    ): TextModelInfo = nativeRequest { nativeLoad(it, encode(model.absolutePath, Int.MAX_VALUE), cpuThreads) }

    public suspend fun transform(request: TextTransformRequest): TextTransformResult {
        val text = encode(request.text, 65536)
        val instruction = encode(request.instruction, 4096)
        val target = encode(request.targetLanguage, 128)
        val result =
            nativeRequest {
                nativeTransform(it, request.operation.nativeCode, text, instruction, target, request.cpuThreads)
            }
        if (result.textUtf8.size > 65536) throw TextException(TextException.INVALID_OUTPUT)
        val decoded =
            try {
                Charsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(result.textUtf8))
                    .toString()
            } catch (_: Exception) {
                throw TextException(TextException.INVALID_OUTPUT)
            }
        val termination =
            when (result.stopCode) {
                0 -> TextTermination.EOG
                1 -> TextTermination.OUTPUT_LIMIT
                else -> throw TextException(TextException.NATIVE)
            }
        return TextTransformResult(decoded, termination)
    }

    public fun unload() {
        val id = nextRequestId()
        nativeBegin(id)
        try {
            nativeUnload(id)
        } finally {
            nativeEnd(id)
        }
    }

    private fun encode(
        value: String,
        maximum: Int,
    ): ByteArray {
        var index = 0
        while (index < value.length) {
            val character = value[index++]
            if (character == '\u0000') throw TextException(TextException.INVALID_REQUEST)
            if (character.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) {
                    throw TextException(TextException.INVALID_REQUEST)
                }
            } else if (character.isLowSurrogate()) {
                throw TextException(TextException.INVALID_REQUEST)
            }
        }
        return value.toByteArray(Charsets.UTF_8).also {
            if (it.size > maximum) throw TextException(TextException.INVALID_REQUEST)
        }
    }

    // The IO worker remains in the caller's structured Job until native RAII and nativeEnd finish.
    private suspend fun <T> nativeRequest(block: (Long) -> T): T =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val id = nextRequestId()
                try {
                    nativeBegin(id)
                } catch (failure: Throwable) {
                    continuation.resumeWith(Result.failure(failure))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation {
                    try {
                        nativeCancel(id)
                    } catch (_: Throwable) {
                        // Preserve cancellation.
                    }
                }
                var outcome =
                    if (continuation.isActive) {
                        runCatching { block(id) }
                    } else {
                        Result.failure(CancellationException("Local text operation cancelled"))
                    }
                try {
                    nativeEnd(id)
                } catch (failure: Throwable) {
                    if (outcome.isSuccess) {
                        outcome = Result.failure(failure)
                    } else {
                        outcome.exceptionOrNull()?.addSuppressed(failure)
                    }
                }
                continuation.resumeWith(outcome)
            }
        }

    private fun nextRequestId(): Long =
        requestIds.updateAndGet {
            if (it == Long.MAX_VALUE) throw TextException(TextException.NATIVE)
            it + 1
        }

    private external fun nativeBegin(requestId: Long)

    private external fun nativeEnd(requestId: Long)

    private external fun nativeCancel(requestId: Long)

    private external fun nativeInspect(
        requestId: Long,
        modelPath: ByteArray,
    ): TextModelInfo

    private external fun nativeLoad(
        requestId: Long,
        modelPath: ByteArray,
        cpuThreads: Int,
    ): TextModelInfo

    private external fun nativeTransform(
        requestId: Long,
        operation: Int,
        text: ByteArray,
        instruction: ByteArray,
        targetLanguage: ByteArray,
        cpuThreads: Int,
    ): NativeTextResult

    private external fun nativeUnload(requestId: Long)
}
