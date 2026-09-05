package io.github.trevarj.motd.ui.settings.labs

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ai.AiDerivedCacheCleaner
import io.github.trevarj.motd.ai.AiFeature
import io.github.trevarj.motd.ai.AiImportState
import io.github.trevarj.motd.ai.AiLabsException
import io.github.trevarj.motd.ai.AiLabsFailureKind
import io.github.trevarj.motd.ai.AiLabsRepository
import io.github.trevarj.motd.ai.AiLabsState
import io.github.trevarj.motd.ai.AiModelCapability
import io.github.trevarj.motd.ai.AiModelRecord
import io.github.trevarj.motd.ai.TranscriptionSettings
import io.github.trevarj.motd.ai.assignedModelId
import io.github.trevarj.motd.ai.isReadyFor
import io.github.trevarj.motd.ai.requiredCapability
import io.github.trevarj.motd.ai.settingsFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

internal data class AiImportProgress(
    val bytesCopied: Long,
    val totalBytes: Long?,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (bytesCopied.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }
}

internal data class AiFeatureUiState(
    val feature: AiFeature,
    val enabled: Boolean,
    val assignedModel: AiModelRecord?,
    val settings: TranscriptionSettings?,
    val compatibleModels: List<AiModelRecord>,
    val selectableModels: List<AiModelRecord>,
) {
    val capability: AiModelCapability get() = feature.requiredCapability
    val ready: Boolean get() = assignedModel?.isReadyFor(capability, settings) == true
}

internal data class AiModelUiState(
    val record: AiModelRecord,
    val assignments: Set<AiFeature>,
    val settings: Map<AiModelCapability, TranscriptionSettings>,
)

internal data class AiLabsStatus(
    @StringRes val message: Int,
    val error: Boolean,
)

internal data class AiLabsUiState(
    val features: Map<AiFeature, AiFeatureUiState> = emptyMap(),
    val models: List<AiModelUiState> = emptyList(),
    val importProgress: AiImportProgress? = null,
    val clearingCaches: Boolean = false,
    val status: AiLabsStatus? = null,
) {
    fun feature(feature: AiFeature): AiFeatureUiState = features[feature] ?: AiFeatureUiState(feature, false, null, null, emptyList(), emptyList())

    val importing: Boolean get() = importProgress != null
}

internal class AiLabsCalls(
    val persistedState: StateFlow<AiLabsState>,
    val setFeatureEnabled: suspend (AiFeature, Boolean) -> Result<Unit>,
    val importModel: suspend (Uri) -> Result<AiModelRecord>,
    val assignModel: suspend (AiFeature, String) -> Result<Unit>,
    val updateSettings: suspend (String, AiModelCapability, TranscriptionSettings) -> Result<Unit>,
    val deleteModel: suspend (String) -> Result<Unit>,
    val clearCaches: suspend () -> Result<Unit>,
) {
    @Inject
    constructor(
        repository: AiLabsRepository,
        cacheCleaner: AiDerivedCacheCleaner,
    ) : this(
        persistedState = repository.state,
        setFeatureEnabled = repository::setFeatureEnabled,
        importModel = { uri -> repository.importModel(uri, AiModelCapability.TRANSCRIPTION) },
        assignModel = repository::assignModel,
        updateSettings = repository::updateSettings,
        deleteModel = repository::deleteModel,
        clearCaches = cacheCleaner::clear,
    )
}

@HiltViewModel
class AiLabsViewModel
    @Inject
    internal constructor(
        private val calls: AiLabsCalls,
    ) : ViewModel() {
        private val status = MutableStateFlow<AiLabsStatus?>(null)
        private val clearingCaches = MutableStateFlow(false)

        internal val state: StateFlow<AiLabsUiState> =
            combine(calls.persistedState, status, clearingCaches, ::deriveAiLabsUiState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), deriveAiLabsUiState(AiLabsState(), null, false))

        fun setFeatureEnabled(
            feature: AiFeature,
            enabled: Boolean,
        ) = mutate {
            calls
                .setFeatureEnabled(feature, enabled)
                .andClearTranscripts(clear = !enabled)
        }

        fun importModel(uri: Uri) = mutate(R.string.ai_model_imported) { calls.importModel(uri).map {} }

        fun assignModel(
            feature: AiFeature,
            modelId: String,
        ) = mutate(R.string.ai_model_assigned) {
            val replaced =
                calls.persistedState.value
                    .assignedModelId(feature)
                    ?.let { it != modelId } == true
            calls
                .assignModel(feature, modelId)
                .andClearTranscripts(clear = replaced)
        }

        fun updateSettings(
            modelId: String,
            capability: AiModelCapability,
            settings: TranscriptionSettings,
        ) = mutate(R.string.ai_settings_saved) { calls.updateSettings(modelId, capability, settings) }

        fun deleteModel(modelId: String) =
            mutate(R.string.ai_model_deleted) {
                calls.deleteModel(modelId).andClearTranscripts(clear = true)
            }

        fun clearCaches() {
            if (clearingCaches.value) return
            status.value = null
            viewModelScope.launch {
                clearingCaches.value = true
                val result = calls.clearCaches()
                clearingCaches.value = false
                status.value =
                    result.fold(
                        onSuccess = { AiLabsStatus(R.string.ai_caches_cleared, error = false) },
                        onFailure = { AiLabsStatus(R.string.ai_error_cache_clear, error = true) },
                    )
            }
        }

        fun dismissStatus() {
            status.value = null
        }

        private suspend fun Result<Unit>.andClearTranscripts(clear: Boolean): Result<Unit> = if (isSuccess && clear) calls.clearCaches() else this

        private fun mutate(
            @StringRes successMessage: Int? = null,
            operation: suspend () -> Result<Unit>,
        ) {
            status.value = null
            viewModelScope.launch {
                val result = operation()
                status.value =
                    result.fold(
                        onSuccess = { successMessage?.let { AiLabsStatus(it, error = false) } },
                        onFailure = { AiLabsStatus(it.aiLabsMessage(), error = true) },
                    )
            }
        }
    }

internal fun deriveAiLabsUiState(
    persisted: AiLabsState,
    status: AiLabsStatus?,
    clearingCaches: Boolean,
): AiLabsUiState {
    val featureStates =
        AiFeature.entries.associateWith { feature ->
            val capability = feature.requiredCapability
            val compatible = persisted.models.filter { capability in it.capabilities }
            val assigned = persisted.assignedModelId(feature)?.let { id -> persisted.models.firstOrNull { it.id == id } }
            val assignedSettings = assigned?.let { persisted.settingsFor(it.id, capability) }
            AiFeatureUiState(
                feature = feature,
                enabled = feature in persisted.enabledFeatures && assigned?.isReadyFor(capability, assignedSettings) == true,
                assignedModel = assigned,
                settings = assignedSettings,
                compatibleModels = compatible,
                selectableModels = compatible.filter { it.isReadyFor(capability, persisted.settingsFor(it.id, capability)) },
            )
        }
    val modelStates =
        persisted.models.map { model ->
            AiModelUiState(
                record = model,
                assignments = persisted.assignments.filter { it.modelId == model.id }.mapTo(linkedSetOf()) { it.feature },
                settings =
                    model.capabilities
                        .mapNotNull { capability ->
                            persisted.settingsFor(model.id, capability)?.let { capability to it }
                        }.toMap(),
            )
        }
    val progress =
        (persisted.importState as? AiImportState.Importing)?.let {
            AiImportProgress(it.bytesCopied, it.totalBytes)
        }
    return AiLabsUiState(featureStates, modelStates, progress, clearingCaches, status)
}

@StringRes
internal fun Throwable.aiLabsMessage(): Int = ((this as? AiLabsException)?.kind ?: AiLabsFailureKind.INTERNAL).messageResource()

@StringRes
internal fun AiLabsFailureKind.messageResource(): Int =
    when (this) {
        AiLabsFailureKind.SOURCE_PERMISSION -> R.string.ai_error_source_permission
        AiLabsFailureKind.SOURCE_OPEN -> R.string.ai_error_source_open
        AiLabsFailureKind.SOURCE_READ -> R.string.ai_error_source_read
        AiLabsFailureKind.STORAGE_WRITE -> R.string.ai_error_storage_write
        AiLabsFailureKind.TOO_LARGE -> R.string.ai_error_too_large
        AiLabsFailureKind.INSUFFICIENT_SPACE -> R.string.ai_error_insufficient_space
        AiLabsFailureKind.TRUNCATED_MODEL -> R.string.ai_error_truncated_model
        AiLabsFailureKind.INVALID_FORMAT -> R.string.ai_error_invalid_format
        AiLabsFailureKind.CORRUPT_MODEL -> R.string.ai_error_corrupt_model
        AiLabsFailureKind.UNSUPPORTED_ROLE -> R.string.ai_error_unsupported_role
        AiLabsFailureKind.UNSUPPORTED_ARCHITECTURE -> R.string.ai_error_unsupported_architecture
        AiLabsFailureKind.NATIVE_OUT_OF_MEMORY -> R.string.ai_error_native_out_of_memory
        AiLabsFailureKind.RUNTIME_FAILURE -> R.string.ai_error_runtime
        AiLabsFailureKind.ATOMIC_INSTALL -> R.string.ai_error_atomic_install
        AiLabsFailureKind.DELETION -> R.string.ai_error_deletion
        AiLabsFailureKind.MODEL_NOT_FOUND -> R.string.ai_error_model_not_found
        AiLabsFailureKind.MODEL_NOT_READY -> R.string.ai_error_model_not_ready
        AiLabsFailureKind.INVALID_SETTINGS -> R.string.ai_error_invalid_settings
        AiLabsFailureKind.PERSISTENCE -> R.string.ai_error_persistence
        AiLabsFailureKind.INTERNAL -> R.string.ai_error_internal
    }
