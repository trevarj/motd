package io.github.trevarj.motd.ai

import io.github.trevarj.motd.audio.ReadAloudConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale

private val AI_MODEL_ID = Regex("[0-9a-f]{64}")

private const val MAX_TRANSCRIPTION_PROMPT_UTF8_BYTES = 65_536

// Mirrors g_lang at pinned whisper.cpp commit 642b5d3260e020c2fc6f34a9569d10ddd7672963.
private val WHISPER_LANGUAGE_CODES =
    """
    en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi
    he uk el ms cs ro da hu ta no th ur hr bg lt la mi ml cy sk
    te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw
    gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo
    ht ps tk nn mt sa lb my bo tl mg as tt haw ln ha ba jw su yue
    """.trimIndent().split(Regex("\\s+")).toSet()

@Serializable
enum class AiFeature {
    TRANSCRIPTION,
    TEXT_TOOLS,
    READ_ALOUD,
}

@Serializable
enum class AiModelCapability {
    TRANSCRIPTION,
    TEXT_TOOLS,
    SPEECH_SYNTHESIS,
}

@Serializable
enum class AiModelFormat {
    WHISPER_GGML,
    QWEN35_GGUF,
    KOKORO_ONNX,
}

val AiFeature.requiredCapability: AiModelCapability
    get() =
        when (this) {
            AiFeature.TRANSCRIPTION -> AiModelCapability.TRANSCRIPTION
            AiFeature.TEXT_TOOLS -> AiModelCapability.TEXT_TOOLS
            AiFeature.READ_ALOUD -> AiModelCapability.SPEECH_SYNTHESIS
        }

fun AiModelFormat.supports(capability: AiModelCapability): Boolean =
    when (this) {
        AiModelFormat.WHISPER_GGML -> capability == AiModelCapability.TRANSCRIPTION
        AiModelFormat.QWEN35_GGUF -> capability == AiModelCapability.TEXT_TOOLS
        AiModelFormat.KOKORO_ONNX -> capability == AiModelCapability.SPEECH_SYNTHESIS
    }

@Serializable
data class AiModelMetadata(
    val architecture: String,
    val quantization: String,
    val maximumAudioSeconds: Int? = null,
    val maximumCpuThreads: Int? = null,
    val isMultilingual: Boolean? = null,
    val maximumContextTokens: Int? = null,
    val textTemplateId: String? = null,
    val sampleRateHz: Int? = null,
    val voiceCount: Int? = null,
) {
    init {
        require(architecture.isNotBlank()) { "Model architecture must not be blank" }
        require(quantization.isNotBlank()) { "Model quantization must not be blank" }
        require(maximumAudioSeconds == null || maximumAudioSeconds > 0) {
            "Maximum audio duration must be positive"
        }
        require(maximumCpuThreads == null || maximumCpuThreads > 0) {
            "Maximum CPU threads must be positive"
        }
        require(maximumContextTokens == null || maximumContextTokens > 0) {
            "Maximum context must be positive"
        }
    }
}

@Serializable
data class AiModelRecord(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val format: AiModelFormat,
    val capabilities: Set<AiModelCapability>,
    val metadata: AiModelMetadata,
    val importedAtEpochMillis: Long,
) {
    init {
        require(isValidAiModelId(id)) { "Model ID must be a lowercase SHA-256" }
        require(displayName.isNotBlank()) { "Model display name must not be blank" }
        require(sizeBytes > 0) { "Model size must be positive" }
        require(importedAtEpochMillis >= 0) { "Import time must not be negative" }
        require(capabilities.isNotEmpty()) { "Model must have at least one capability" }
        require(capabilities.all(format::supports)) { "Model format and capabilities do not match" }
    }
}

fun isValidAiModelId(id: String): Boolean = AI_MODEL_ID.matches(id)

@Serializable
data class TranscriptionSettings(
    val language: String = "auto",
    val initialPrompt: String = "",
    val cpuThreads: Int = defaultAiCpuThreads(),
) {
    companion object {
        fun defaults(
            metadata: AiModelMetadata,
            availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
        ): TranscriptionSettings =
            TranscriptionSettings(cpuThreads = defaultAiCpuThreads(availableProcessors))
                .clampedTo(metadata, availableProcessors)
    }
}

fun defaultAiCpuThreads(availableProcessors: Int = Runtime.getRuntime().availableProcessors()): Int = minOf(4, maxOf(1, availableProcessors.coerceAtLeast(1) - 1))

fun TranscriptionSettings.clampedTo(
    metadata: AiModelMetadata,
    availableProcessors: Int = Runtime.getRuntime().availableProcessors(),
): TranscriptionSettings =
    copy(
        language = language.trim().lowercase(Locale.ROOT).ifEmpty { "auto" },
        cpuThreads = clampCpuThreads(cpuThreads, availableProcessors, metadata.maximumCpuThreads),
    )

internal fun TranscriptionSettings.normalizedLanguage(metadata: AiModelMetadata): String? {
    val normalized = language.trim().lowercase(Locale.ROOT)
    return normalized.takeIf {
        it.isNotEmpty() &&
            (it == "auto" || it in WHISPER_LANGUAGE_CODES) &&
            (metadata.isMultilingual != false || it == "auto" || it == "en")
    }
}

internal fun TranscriptionSettings.isRuntimeCompatible(metadata: AiModelMetadata): Boolean = normalizedLanguage(metadata) != null && initialPrompt.isValidWhisperPrompt()

private fun String.isValidWhisperPrompt(): Boolean {
    var bytes = 0
    var index = 0
    while (index < length) {
        val current = this[index]
        if (current == '\u0000') return false
        val width =
            when {
                current.code < 0x80 -> {
                    1
                }

                current.code < 0x800 -> {
                    2
                }

                current.isHighSurrogate() &&
                    index + 1 < length &&
                    this[index + 1].isLowSurrogate() -> {
                    index++
                    4
                }

                current.isSurrogate() -> {
                    1
                }

                else -> {
                    3
                }
            }
        if (bytes > MAX_TRANSCRIPTION_PROMPT_UTF8_BYTES - width) return false
        bytes += width
        index++
    }
    return true
}

fun AiLabsState.isModelReadyFor(
    model: AiModelRecord,
    capability: AiModelCapability,
): Boolean {
    if (capability !in model.capabilities || !model.format.supports(capability)) return false
    return when (capability) {
        AiModelCapability.TRANSCRIPTION -> {
            transcriptionSettingsFor(model.id)?.isRuntimeCompatible(model.metadata) == true
        }

        AiModelCapability.TEXT_TOOLS -> {
            model.metadata.architecture == "qwen35" &&
                (model.metadata.maximumContextTokens ?: 0) >= 4096 &&
                model.metadata.textTemplateId == "qwen35-nonthinking-v1"
        }

        AiModelCapability.SPEECH_SYNTHESIS -> {
            model.metadata.architecture == "kokoro" && model.metadata.quantization == "int8" &&
                model.metadata.sampleRateHz == 24_000 && model.metadata.voiceCount == 54
        }
    }
}

@Serializable
data class AiFeatureAssignment(
    val feature: AiFeature,
    val modelId: String,
) {
    init {
        require(isValidAiModelId(modelId)) { "Assigned model ID must be a lowercase SHA-256" }
    }
}

@Serializable
data class AiTranscriptionSettingsRecord(
    val modelId: String,
    val settings: TranscriptionSettings,
) {
    init {
        require(isValidAiModelId(modelId)) { "Settings model ID must be a lowercase SHA-256" }
    }
}

sealed interface AiImportState {
    data object Idle : AiImportState

    data class Importing(
        val bytesCopied: Long,
        val totalBytes: Long?,
    ) : AiImportState
}

@Serializable
data class AiLabsState(
    val enabledFeatures: Set<AiFeature> = emptySet(),
    val models: List<AiModelRecord> = emptyList(),
    val assignments: List<AiFeatureAssignment> = emptyList(),
    val transcriptionSettings: List<AiTranscriptionSettingsRecord> = emptyList(),
    val customStyles: List<AiCustomStyle> = emptyList(),
    val translationTarget: AiTranslationTarget? = null,
    val readAloudConfig: ReadAloudConfig = ReadAloudConfig(),
    @Transient val importState: AiImportState = AiImportState.Idle,
)

fun AiLabsState.assignedModelId(feature: AiFeature): String? = assignments.firstOrNull { it.feature == feature }?.modelId

fun AiLabsState.transcriptionSettingsFor(modelId: String): TranscriptionSettings? = transcriptionSettings.firstOrNull { it.modelId == modelId }?.settings

@Serializable
data class AiCustomStyle(
    val id: String,
    val name: String,
    val instruction: String,
)

@Serializable
data class AiTranslationTarget(
    val code: String,
    val name: String,
)

val aiTranslationTargets: List<AiTranslationTarget> =
    listOf(
        "en" to "English",
        "ar" to "Arabic",
        "bn" to "Bengali",
        "zh-Hans" to "Chinese (Simplified)",
        "zh-Hant" to "Chinese (Traditional)",
        "cs" to "Czech",
        "nl" to "Dutch",
        "fr" to "French",
        "de" to "German",
        "el" to "Greek",
        "he" to "Hebrew",
        "hi" to "Hindi",
        "id" to "Indonesian",
        "it" to "Italian",
        "ja" to "Japanese",
        "ko" to "Korean",
        "ms" to "Malay",
        "fa" to "Persian",
        "pl" to "Polish",
        "pt" to "Portuguese",
        "ro" to "Romanian",
        "ru" to "Russian",
        "es" to "Spanish",
        "sv" to "Swedish",
        "th" to "Thai",
        "tr" to "Turkish",
        "uk" to "Ukrainian",
        "vi" to "Vietnamese",
    ).map { (code, name) -> AiTranslationTarget(code, name) }

internal fun String.isValidAiText(maximumBytes: Int): Boolean {
    var index = 0
    while (index < length) {
        val char = this[index]
        if (char.isHighSurrogate()) {
            if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
            index += 2
            continue
        }
        if (char.isSurrogate() || ((char.code < 32 || char.code in 127..159) && char != '\n' && char != '\t')) return false
        index++
    }
    return toByteArray(Charsets.UTF_8).size <= maximumBytes
}

internal fun AiCustomStyle.isValid(): Boolean =
    runCatching {
        java.util.UUID
            .fromString(id)
            .toString() == id
    }.getOrDefault(false) &&
        name == name.trim() && name.codePointCount(0, name.length) in 1..40 &&
        name.isValidAiText(160) && instruction.isNotEmpty() && instruction.isValidAiText(4096)

internal fun AiTranslationTarget.isValid(): Boolean =
    if (code != "other") {
        this in aiTranslationTargets
    } else {
        name == name.trim() && name.codePointCount(0, name.length) in 2..48 &&
            name.isValidAiText(128) &&
            name.codePoints().allMatch {
                Character.isLetter(it) || it == 32 || it == 45 || it == 39 || it == 40 || it == 41
            }
    }

private fun clampCpuThreads(
    value: Int,
    availableProcessors: Int,
    runtimeMaximum: Int?,
): Int {
    val maximum = minOf(availableProcessors.coerceAtLeast(1), runtimeMaximum ?: Int.MAX_VALUE)
    return value.coerceIn(1, maximum)
}
