package io.github.trevarj.motd.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.ai.AiCustomStyle
import io.github.trevarj.motd.ai.AiExecutionCoordinator
import io.github.trevarj.motd.ai.AiFeature
import io.github.trevarj.motd.ai.AiLabsRepository
import io.github.trevarj.motd.ai.AiLabsState
import io.github.trevarj.motd.ai.AiModelCapability
import io.github.trevarj.motd.ai.AiRuntimeException
import io.github.trevarj.motd.ai.AiRuntimeFailure
import io.github.trevarj.motd.ai.AiTranslationTarget
import io.github.trevarj.motd.ai.assignedModelId
import io.github.trevarj.motd.ai.defaultAiCpuThreads
import io.github.trevarj.motd.ai.isModelReadyFor
import io.github.trevarj.motd.ai.isValid
import io.github.trevarj.motd.ai.text.TextOperation
import io.github.trevarj.motd.ai.text.TextTermination
import io.github.trevarj.motd.ai.text.TextTransformRequest
import io.github.trevarj.motd.ai.text.TextTransformResult
import io.github.trevarj.motd.audio.isCanonicalVoiceFallback
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.irc.format.plainIrcText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AiComposerDraftSnapshot(
    val requestId: Long,
    val roomId: Long,
    val revision: Long,
    val text: String,
    val replyToEventId: Long?,
)

sealed interface AiTextSource {
    data class Composer(
        val draft: AiComposerDraftSnapshot,
    ) : AiTextSource

    data class StoredMessage(
        val roomId: Long,
        val eventId: Long,
        val msgid: String?,
        val text: String,
    ) : AiTextSource

    data class TransientMessage(
        val key: String,
        val text: String,
        val roomId: Long? = null,
        val msgid: String? = null,
    ) : AiTextSource
}

data class AiTextAction(
    val operation: TextOperation,
    val customStyleId: String? = null,
    val translationTarget: AiTranslationTarget? = null,
)

sealed interface AiTextUiState {
    data object Closed : AiTextUiState

    data class Choosing(
        val source: AiTextSource,
        val isSavingTarget: Boolean = false,
    ) : AiTextUiState

    data class Running(
        val requestId: Long,
        val source: AiTextSource,
    ) : AiTextUiState

    data class Result(
        val requestId: Long,
        val source: AiTextSource,
        val original: String,
        val result: TextTransformResult,
        val configurationVersion: Long,
    ) : AiTextUiState

    data class Failed(
        val source: AiTextSource?,
        val failure: AiRuntimeFailure,
    ) : AiTextUiState
}

internal fun aiTextEligibility(message: MessageEntity): Boolean = message.kind != MessageKind.REDACTED && plainIrcText(message.text).isNotBlank() && !isCanonicalVoiceFallback(message.text)

private fun AiTextSource.body(): String =
    when (this) {
        is AiTextSource.Composer -> plainIrcText(draft.text)
        is AiTextSource.StoredMessage -> text
        is AiTextSource.TransientMessage -> text
    }

fun AiTextUiState.source(): AiTextSource? =
    when (this) {
        AiTextUiState.Closed -> null
        is AiTextUiState.Choosing -> source
        is AiTextUiState.Running -> source
        is AiTextUiState.Result -> source
        is AiTextUiState.Failed -> source
    }

@HiltViewModel
class AiTextViewModel
    @Inject
    constructor(
        private val repository: AiLabsRepository,
        private val coordinator: AiExecutionCoordinator,
        private val messages: MessageRepository,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<AiTextUiState>(AiTextUiState.Closed)
        val state: StateFlow<AiTextUiState> = mutableState.asStateFlow()
        val labsState: StateFlow<AiLabsState> = repository.state
        private var token = 0L
        private var lease = 0L
        private var request: Job? = null
        private var observer: Job? = null
        private var source: AiTextSource? = null
        private var generatedVersion: Long? = null

        init {
            viewModelScope.launch {
                repository.textToolsVersion.collect { version ->
                    when (mutableState.value) {
                        is AiTextUiState.Running, is AiTextUiState.Result -> if (generatedVersion != version) close()
                        is AiTextUiState.Failed -> if (generatedVersion != null && generatedVersion != version) close()
                        else -> Unit
                    }
                }
            }
            viewModelScope.launch {
                labsState.collect { labs ->
                    val model = labs.models.firstOrNull { it.id == labs.assignedModelId(AiFeature.TEXT_TOOLS) }
                    if (source != null && (AiFeature.TEXT_TOOLS !in labs.enabledFeatures || model == null || !labs.isModelReadyFor(model, AiModelCapability.TEXT_TOOLS))) close()
                }
            }
        }

        private fun replace(next: AiTextSource?) {
            token++
            lease++
            source = next
            generatedVersion = null
            mutableState.value = next?.let { AiTextUiState.Choosing(it) } ?: AiTextUiState.Closed
            request?.cancel()
            observer?.cancel()
            observer = null
        }

        fun openComposer(snapshot: AiComposerDraftSnapshot) = replace(AiTextSource.Composer(snapshot))

        fun openStoredMessage(
            roomId: Long,
            eventId: Long,
        ) {
            replace(null)
            val selectedToken = lease
            observer =
                viewModelScope.launch {
                    val initial = messages.byId(eventId) ?: return@launch
                    var frozen: String? = null
                    messages.observeReplyTarget(roomId, eventId, initial.msgid).collect { row ->
                        if (lease != selectedToken) return@collect
                        if (row == null || row.kind == MessageKind.REDACTED) {
                            close()
                            return@collect
                        }
                        if (!aiTextEligibility(row)) {
                            if (frozen != null) {
                                close()
                            } else {
                                lease++
                                mutableState.value = AiTextUiState.Failed(null, AiRuntimeFailure.NO_TEXT)
                                observer?.cancel()
                            }
                            return@collect
                        }
                        if (frozen != null && row.text != frozen) {
                            close()
                            return@collect
                        }
                        if (frozen == null) {
                            frozen = row.text
                            source = AiTextSource.StoredMessage(roomId, row.id, row.msgid, row.text)
                            mutableState.value = AiTextUiState.Choosing(source!!)
                        }
                    }
                }
        }

        fun openTransientMessage(next: AiTextSource.TransientMessage) {
            replace(next)
            if (plainIrcText(next.text).isBlank() || isCanonicalVoiceFallback(next.text)) {
                mutableState.value = AiTextUiState.Failed(next, AiRuntimeFailure.NO_TEXT)
                return
            }
            val room = next.roomId ?: return
            val msgid = next.msgid ?: return
            val selectedToken = lease
            observer =
                viewModelScope.launch {
                    var seenLocalMatch = false
                    messages.observeReplyTarget(room, null, msgid).collect { row ->
                        if (lease != selectedToken) return@collect
                        if (row == null) {
                            if (seenLocalMatch) close()
                        } else {
                            seenLocalMatch = true
                            if (!aiTextEligibility(row) || row.text != next.text) close()
                        }
                    }
                }
        }

        fun updateTransientMessage(
            key: String,
            text: String?,
        ) {
            val selected = source as? AiTextSource.TransientMessage ?: return
            if (selected.key == key && selected.text != text) close()
        }

        fun selectTranslationTarget(target: AiTranslationTarget) {
            val choosing =
                when (val current = mutableState.value) {
                    is AiTextUiState.Choosing -> current
                    is AiTextUiState.Failed -> current.source?.let { AiTextUiState.Choosing(it) } ?: return
                    else -> return
                }
            if (choosing.isSavingTarget) return
            val selectedToken = lease
            generatedVersion = null
            mutableState.value = choosing.copy(isSavingTarget = true)
            val previous = request
            request =
                viewModelScope.launch {
                    previous?.cancelAndJoin()
                    val saved = repository.setTranslationTarget(target)
                    val committed = repository.textToolsConfiguration()
                    if (lease != selectedToken || source != choosing.source) return@launch
                    mutableState.value = if (saved.isSuccess && committed != null) choosing else AiTextUiState.Failed(choosing.source, AiRuntimeFailure.INVALID_REQUEST)
                }
        }

        fun generate(action: AiTextAction) {
            val current = mutableState.value
            val selected =
                when (current) {
                    is AiTextUiState.Choosing -> if (current.isSavingTarget) return else current.source
                    is AiTextUiState.Failed -> current.source ?: return
                    else -> return
                }
            if (selected !is AiTextSource.Composer && action.operation != TextOperation.TRANSLATE) {
                mutableState.value = AiTextUiState.Failed(selected, AiRuntimeFailure.INVALID_REQUEST)
                return
            }
            val selectedToken = ++token
            val previous = request
            generatedVersion = repository.textToolsVersion.value
            mutableState.value = AiTextUiState.Running(selectedToken, selected)
            request =
                viewModelScope.launch {
                    previous?.cancelAndJoin()
                    try {
                        val configuration = repository.textToolsConfiguration() ?: throw AiRuntimeException(AiRuntimeFailure.NO_MODEL_LOADED)
                        if (token != selectedToken || source != selected) return@launch
                        generatedVersion = configuration.version
                        val labs = configuration.state
                        val model = labs.models.firstOrNull { it.id == labs.assignedModelId(AiFeature.TEXT_TOOLS) }
                        if (AiFeature.TEXT_TOOLS !in labs.enabledFeatures || model == null || !labs.isModelReadyFor(model, AiModelCapability.TEXT_TOOLS)) throw AiRuntimeException(AiRuntimeFailure.NO_MODEL_LOADED)
                        val instruction = if (action.operation == TextOperation.CUSTOM) labs.customStyles.firstOrNull { it.id == action.customStyleId }?.instruction ?: throw AiRuntimeException(AiRuntimeFailure.INVALID_REQUEST) else ""
                        val target = if (action.operation == TextOperation.TRANSLATE) labs.translationTarget ?: action.translationTarget ?: throw AiRuntimeException(AiRuntimeFailure.INVALID_REQUEST) else null
                        if (target != null && !target.isValid()) throw AiRuntimeException(AiRuntimeFailure.INVALID_REQUEST)
                        val result = coordinator.transform(model.id, repository.modelFile(model.id), TextTransformRequest(action.operation, selected.body(), instruction, target?.name.orEmpty(), defaultAiCpuThreads())) { repository.isTextToolsVersionCurrent(configuration.version) }
                        repository.applyIfTextToolsVersion(configuration.version) {
                            if (token != selectedToken || source != selected) {
                                false
                            } else {
                                mutableState.value = AiTextUiState.Result(selectedToken, selected, selected.body(), result, configuration.version)
                                true
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        if (token == selectedToken) mutableState.value = AiTextUiState.Choosing(selected)
                    } catch (failure: Throwable) {
                        if (token == selectedToken) mutableState.value = AiTextUiState.Failed(selected, (failure as? AiRuntimeException)?.failure ?: AiRuntimeFailure.NATIVE)
                    }
                }
        }

        fun applyComposerResult(apply: (AiComposerDraftSnapshot, String) -> Boolean): Boolean {
            val result = mutableState.value as? AiTextUiState.Result ?: return false
            val composer = result.source as? AiTextSource.Composer ?: return false
            if (result.result.termination != TextTermination.EOG) return false
            val applied = repository.applyIfTextToolsVersion(result.configurationVersion) { apply(composer.draft, result.result.text) }
            if (applied) close()
            return applied
        }

        fun showUnavailable() {
            replace(null)
            mutableState.value = AiTextUiState.Failed(null, AiRuntimeFailure.NO_MODEL_LOADED)
        }

        fun upsertCustomStyle(style: AiCustomStyle) {
            val selectedLease = lease
            viewModelScope.launch {
                if (repository.upsertCustomStyle(style).isFailure && selectedLease == lease) mutableState.value = AiTextUiState.Failed(source, AiRuntimeFailure.INVALID_REQUEST)
            }
        }

        fun deleteCustomStyle(styleId: String) {
            val selectedLease = lease
            viewModelScope.launch {
                if (repository.deleteCustomStyle(styleId).isFailure && selectedLease == lease) mutableState.value = AiTextUiState.Failed(source, AiRuntimeFailure.INVALID_REQUEST)
            }
        }

        fun close() = replace(null)
    }
