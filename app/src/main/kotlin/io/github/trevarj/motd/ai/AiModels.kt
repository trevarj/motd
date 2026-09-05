package io.github.trevarj.motd.ai

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
}

@Serializable
enum class AiModelCapability {
    TRANSCRIPTION,
}

@Serializable
enum class AiModelFormat {
    WHISPER_GGML,
}

val AiFeature.requiredCapability: AiModelCapability
    get() =
        when (this) {
            AiFeature.TRANSCRIPTION -> AiModelCapability.TRANSCRIPTION
        }

fun AiModelFormat.supports(capability: AiModelCapability): Boolean =
    when (this) {
        AiModelFormat.WHISPER_GGML -> capability == AiModelCapability.TRANSCRIPTION
    }

@Serializable
data class AiModelMetadata(
    val architecture: String,
    val quantization: String,
    val maximumAudioSeconds: Int? = null,
    val maximumCpuThreads: Int? = null,
    val isMultilingual: Boolean? = null,
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

fun AiModelRecord.isReadyFor(
    capability: AiModelCapability,
    settings: TranscriptionSettings?,
): Boolean {
    if (capability !in capabilities || !format.supports(capability)) return false
    return settings != null && settings.isRuntimeCompatible(metadata)
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
    @Transient val importState: AiImportState = AiImportState.Idle,
)

fun AiLabsState.assignedModelId(feature: AiFeature): String? = assignments.firstOrNull { it.feature == feature }?.modelId

fun AiLabsState.settingsFor(
    modelId: String,
    capability: AiModelCapability,
): TranscriptionSettings? =
    when (capability) {
        AiModelCapability.TRANSCRIPTION -> transcriptionSettings.firstOrNull { it.modelId == modelId }?.settings
    }

private fun clampCpuThreads(
    value: Int,
    availableProcessors: Int,
    runtimeMaximum: Int?,
): Int {
    val maximum = minOf(availableProcessors.coerceAtLeast(1), runtimeMaximum ?: Int.MAX_VALUE)
    return value.coerceIn(1, maximum)
}
