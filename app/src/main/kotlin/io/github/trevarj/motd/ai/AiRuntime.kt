package io.github.trevarj.motd.ai

import io.github.trevarj.motd.ai.whisper.TranscriptionRequest
import io.github.trevarj.motd.ai.whisper.WhisperAudioException
import io.github.trevarj.motd.ai.whisper.WhisperCorruptModelException
import io.github.trevarj.motd.ai.whisper.WhisperException
import io.github.trevarj.motd.ai.whisper.WhisperInvalidRequestException
import io.github.trevarj.motd.ai.whisper.WhisperModelMagicException
import io.github.trevarj.motd.ai.whisper.WhisperModelOpenException
import io.github.trevarj.motd.ai.whisper.WhisperNativeException
import io.github.trevarj.motd.ai.whisper.WhisperNoModelLoadedException
import io.github.trevarj.motd.ai.whisper.WhisperOutOfMemoryException
import io.github.trevarj.motd.ai.whisper.WhisperRuntime
import io.github.trevarj.motd.ai.whisper.WhisperTranscriptionException
import io.github.trevarj.motd.ai.whisper.WhisperTruncatedModelException
import io.github.trevarj.motd.ai.whisper.WhisperUnsupportedModelException
import java.io.File
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

data class AiTranscriptionRequest(
    val settings: TranscriptionSettings,
)

enum class AiRuntimeFailure {
    MODEL_OPEN,
    INVALID_FORMAT,
    TRUNCATED_MODEL,
    CORRUPT_MODEL,
    UNSUPPORTED_ARCHITECTURE,
    INVALID_REQUEST,
    OUT_OF_MEMORY,
    INVALID_AUDIO,
    NO_MODEL_LOADED,
    INFERENCE,
    NATIVE,
}

class AiRuntimeException(
    val failure: AiRuntimeFailure,
    cause: Throwable? = null,
) : RuntimeException(failure.safeMessage, cause)

interface SpeechModelRuntime {
    suspend fun inspect(
        modelFile: File,
        capability: AiModelCapability,
    ): AiModelMetadata

    suspend fun load(
        modelFile: File,
        capability: AiModelCapability,
    )

    suspend fun transcribe(
        pcmWav: File,
        request: AiTranscriptionRequest,
        onProgress: (Int) -> Unit,
    ): String

    fun unload()
}

@Singleton
class WhisperSpeechModelRuntime
    @Inject
    constructor() : SpeechModelRuntime {
        override suspend fun inspect(
            modelFile: File,
            capability: AiModelCapability,
        ): AiModelMetadata = whisperCall { WhisperRuntime.inspect(modelFile).toMetadata() }

        override suspend fun load(
            modelFile: File,
            capability: AiModelCapability,
        ) {
            whisperCall { WhisperRuntime.load(modelFile) }
        }

        override suspend fun transcribe(
            pcmWav: File,
            request: AiTranscriptionRequest,
            onProgress: (Int) -> Unit,
        ): String =
            whisperCall {
                val settings = request.settings
                WhisperRuntime.transcribe(
                    pcmWav = pcmWav,
                    request =
                        TranscriptionRequest(
                            language = settings.language,
                            initialPrompt = settings.initialPrompt,
                            cpuThreads = settings.cpuThreads,
                        ),
                    onProgress = onProgress,
                )
            }

        override fun unload() {
            whisperCall { WhisperRuntime.unload() }
        }
    }

private fun io.github.trevarj.motd.ai.whisper.WhisperModelInfo.toMetadata(): AiModelMetadata =
    AiModelMetadata(
        architecture = modelType,
        quantization = quantization,
        maximumAudioSeconds = maxAudioSeconds,
        maximumCpuThreads = maxThreads,
        isMultilingual = isMultilingual,
    )

private inline fun <T> whisperCall(block: () -> T): T =
    try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: WhisperException) {
        throw AiRuntimeException(
            when (failure) {
                is WhisperModelOpenException -> AiRuntimeFailure.MODEL_OPEN
                is WhisperModelMagicException -> AiRuntimeFailure.INVALID_FORMAT
                is WhisperTruncatedModelException -> AiRuntimeFailure.TRUNCATED_MODEL
                is WhisperCorruptModelException -> AiRuntimeFailure.CORRUPT_MODEL
                is WhisperUnsupportedModelException -> AiRuntimeFailure.UNSUPPORTED_ARCHITECTURE
                is WhisperAudioException -> AiRuntimeFailure.INVALID_AUDIO
                is WhisperInvalidRequestException -> AiRuntimeFailure.INVALID_REQUEST
                is WhisperNoModelLoadedException -> AiRuntimeFailure.NO_MODEL_LOADED
                is WhisperTranscriptionException -> AiRuntimeFailure.INFERENCE
                is WhisperOutOfMemoryException -> AiRuntimeFailure.OUT_OF_MEMORY
                is WhisperNativeException -> AiRuntimeFailure.NATIVE
            },
            failure,
        )
    }

private val AiRuntimeFailure.safeMessage: String
    get() =
        when (this) {
            AiRuntimeFailure.MODEL_OPEN -> "The model file could not be opened"
            AiRuntimeFailure.INVALID_FORMAT -> "The file is not a supported model"
            AiRuntimeFailure.TRUNCATED_MODEL -> "The model file is incomplete"
            AiRuntimeFailure.CORRUPT_MODEL -> "The model file is corrupt"
            AiRuntimeFailure.UNSUPPORTED_ARCHITECTURE -> "The model architecture is unsupported"
            AiRuntimeFailure.INVALID_REQUEST -> "The AI request is invalid"
            AiRuntimeFailure.OUT_OF_MEMORY -> "There is not enough memory to run this model"
            AiRuntimeFailure.INVALID_AUDIO -> "The audio input is unsupported"
            AiRuntimeFailure.NO_MODEL_LOADED -> "No matching model is loaded"
            AiRuntimeFailure.INFERENCE -> "The AI operation failed"
            AiRuntimeFailure.NATIVE -> "The local AI runtime failed"
        }
