package io.github.trevarj.motd.ui.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.ai.AiExecutionCoordinator
import io.github.trevarj.motd.ai.AiExecutionUnavailableException
import io.github.trevarj.motd.ai.AiFeature
import io.github.trevarj.motd.ai.AiLabsRepository
import io.github.trevarj.motd.ai.AiLabsState
import io.github.trevarj.motd.ai.AiModelCapability
import io.github.trevarj.motd.ai.AiRuntimeException
import io.github.trevarj.motd.ai.AiRuntimeFailure
import io.github.trevarj.motd.ai.AiTranscriptCache
import io.github.trevarj.motd.ai.AiTranscriptionRequest
import io.github.trevarj.motd.ai.MAX_TRANSCRIPT_BYTES
import io.github.trevarj.motd.ai.TranscriptionSettings
import io.github.trevarj.motd.ai.assignedModelId
import io.github.trevarj.motd.ai.isReadyFor
import io.github.trevarj.motd.ai.settingsFor
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.normalizedConfig
import io.github.trevarj.motd.audio.AudioActivityTracker
import io.github.trevarj.motd.audio.AudioInputException
import io.github.trevarj.motd.audio.AudioInputFailureKind
import io.github.trevarj.motd.audio.AudioInputMaterializer
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.CompletedVoiceRecording
import io.github.trevarj.motd.audio.LocalAudioLease
import io.github.trevarj.motd.audio.LocalPcmAudioLease
import io.github.trevarj.motd.audio.PcmAudioDecoder
import io.github.trevarj.motd.audio.PcmAudioException
import io.github.trevarj.motd.audio.PcmAudioFailureKind
import io.github.trevarj.motd.audio.VoiceConfig
import io.github.trevarj.motd.audio.VoiceMessageSender
import io.github.trevarj.motd.audio.VoicePrefs
import io.github.trevarj.motd.audio.VoiceRecorder
import io.github.trevarj.motd.audio.VoiceRecordingProfile
import io.github.trevarj.motd.audio.VoiceSendProgress
import io.github.trevarj.motd.audio.VoiceSendRequest
import io.github.trevarj.motd.ui.nav.ChatRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class VoiceRecordingUi(
    val elapsedMs: Long,
    val locked: Boolean,
    val waveform: AudioWaveform = AudioWaveform.EMPTY,
)

data class StagedVoiceMessage(
    val file: File,
    val durationMs: Long,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long,
    val encrypted: Boolean,
    val destination: PasteBackendConfig?,
    val waveform: AudioWaveform,
) {
    val source: AttachmentSource.LocalFile
        get() = AttachmentSource.LocalFile(file, file.name, mimeType, sizeBytes)
}

sealed interface VoiceTranscriptState {
    data class Preparing(
        val progress: Int,
    ) : VoiceTranscriptState

    data object Waiting : VoiceTranscriptState

    data class Transcribing(
        val progress: Int,
    ) : VoiceTranscriptState

    data class Ready(
        val text: String,
        val cached: Boolean,
    ) : VoiceTranscriptState

    data class Failed(
        val kind: VoiceTranscriptFailureKind,
    ) : VoiceTranscriptState
}

/** Content-free failure categories safe to retain in route-scoped UI state. */
enum class VoiceTranscriptFailureKind {
    FEATURE_UNAVAILABLE,
    UNSUPPORTED_SCHEME,
    FILE_UNAVAILABLE,
    EXPIRED,
    MISSING_ENCRYPTION_KEY,
    INVALID_ENCRYPTION_KEY,
    AUTHENTICATION_FAILED,
    ROUTE_UNAVAILABLE,
    TLS_FAILED,
    HTTP_AUTHENTICATION_FAILED,
    HTTP_FAILED,
    READ_FAILED,
    INPUT_TOO_LARGE,
    NO_AUDIO,
    UNSUPPORTED_CODEC,
    AUDIO_TOO_LONG,
    DECODE_FAILED,
    MODEL_OPEN,
    INVALID_MODEL_FORMAT,
    TRUNCATED_MODEL,
    CORRUPT_MODEL,
    UNSUPPORTED_MODEL_ARCHITECTURE,
    INVALID_REQUEST,
    OUT_OF_MEMORY,
    INVALID_AUDIO,
    NO_MODEL_LOADED,
    INFERENCE_FAILED,
    NATIVE_FAILURE,
    CACHE_FAILED,
}

data class VoiceMessageUiState(
    val config: VoiceConfig = VoiceConfig(),
    val recording: VoiceRecordingUi? = null,
    val staged: StagedVoiceMessage? = null,
    val progress: VoiceSendProgress? = null,
    val error: String? = null,
    val notice: String? = null,
    val transcripts: Map<String, VoiceTranscriptState> = emptyMap(),
    val transcriptionEnabled: Boolean = false,
    val transcriptionReady: Boolean = false,
)

internal class VoiceTranscriptionCalls(
    val labsState: StateFlow<AiLabsState>,
    val modelFile: (String) -> File,
    val materialize: suspend (AudioPlaybackRequest, (Long, Long?) -> Unit) -> LocalAudioLease,
    val decode: suspend (File) -> LocalPcmAudioLease,
    val cacheGet: suspend (String) -> String?,
    val cachePut: suspend (String, String) -> Unit,
    val cacheRemove: suspend (String) -> Unit,
    val transcribe:
        suspend (
            modelId: String,
            modelFile: File,
            pcmWav: File,
            request: AiTranscriptionRequest,
            onProgress: (Int) -> Unit,
        ) -> String,
) {
    @Inject
    constructor(
        aiLabsRepository: AiLabsRepository,
        inputMaterializer: AudioInputMaterializer,
        pcmAudioDecoder: PcmAudioDecoder,
        transcriptCache: AiTranscriptCache,
        executionCoordinator: AiExecutionCoordinator,
    ) : this(
        labsState = aiLabsRepository.state,
        modelFile = aiLabsRepository::modelFile,
        materialize = inputMaterializer::materialize,
        decode = pcmAudioDecoder::decode,
        cacheGet = transcriptCache::get,
        cachePut = transcriptCache::put,
        cacheRemove = transcriptCache::remove,
        transcribe = { modelId, modelFile, pcmWav, request, onProgress ->
            executionCoordinator.transcribe(
                modelId = modelId,
                modelFile = modelFile,
                capability = AiModelCapability.TRANSCRIPTION,
                pcmWav = pcmWav,
                request = request,
                onProgress = onProgress,
            )
        },
    )
}

private data class VoiceTranscriptionConfiguration(
    val enabled: Boolean = false,
    val modelId: String? = null,
    val settings: TranscriptionSettings? = null,
    val ready: Boolean = false,
)

private class VoiceTranscriptCacheException : Exception()

internal const val VOICE_PERMISSION_DENIED_ERROR =
    "Microphone permission is required to record voice messages. Enable it in system settings and try again."

@HiltViewModel
class VoiceMessageViewModel
    @Inject
    internal constructor(
        savedStateHandle: SavedStateHandle,
        private val recorder: VoiceRecorder,
        private val sender: VoiceMessageSender,
        private val prefs: VoicePrefs,
        private val activityTracker: AudioActivityTracker,
        private val playbackController: AudioPlaybackController,
        private val transcriptionCalls: VoiceTranscriptionCalls,
    ) : ViewModel() {
        private val bufferId = savedStateHandle.toRoute<ChatRoute>().bufferId

        private var transcriptionConfiguration =
            transcriptionCalls.labsState.value.voiceTranscriptionConfiguration()
        private val _state =
            MutableStateFlow(
                VoiceMessageUiState(
                    transcriptionEnabled = transcriptionConfiguration.enabled,
                    transcriptionReady = transcriptionConfiguration.ready,
                ),
            )
        val state: StateFlow<VoiceMessageUiState> = _state.asStateFlow()
        private val config = prefs.config.stateIn(viewModelScope, SharingStarted.Eagerly, VoiceConfig())
        private var recordingStartedAtMs: Long = 0L
        private var timerJob: Job? = null
        private var sendJob: Job? = null
        private val recordingAmplitudes = mutableListOf<Int>()
        private val transcriptionLock = Any()
        private var nextTranscriptionToken = 0L
        private val transcriptionTokens = mutableMapOf<String, Long>()
        private val transcriptionJobs = mutableMapOf<String, Job>()

        init {
            viewModelScope.launch {
                config.collectLatest { config ->
                    _state.update { current -> current.copy(config = config) }
                }
            }
            viewModelScope.launch {
                transcriptionCalls.labsState
                    .map { it.voiceTranscriptionConfiguration() }
                    .distinctUntilChanged()
                    .collect { current ->
                        val changed = current != transcriptionConfiguration
                        transcriptionConfiguration = current
                        _state.update {
                            it.copy(
                                transcriptionEnabled = current.enabled,
                                transcriptionReady = current.ready,
                            )
                        }
                        if (changed) cancelAllTranscriptions()
                    }
            }
        }

        fun startRecording(locked: Boolean) {
            if (_state.value.recording != null) return
            playbackController.pause()
            val active =
                try {
                    recorder.start(
                        VoiceRecordingProfile(
                            quality = config.value.quality,
                            noiseReduction = config.value.noiseReduction,
                        ),
                    )
                } catch (error: Exception) {
                    _state.update { it.copy(error = error.message ?: "Could not start recording.") }
                    return
                }
            recordingStartedAtMs = active.startedAtMs
            recordingAmplitudes.clear()
            activityTracker.setRecording(true)
            _state.update {
                it.copy(
                    recording = VoiceRecordingUi(elapsedMs = 0L, locked = locked),
                    error = null,
                )
            }
            if (active.processingFallback) {
                viewModelScope.launch {
                    if (prefs.takeNoiseFallbackNotice()) {
                        _state.update {
                            it.copy(notice = "Device noise reduction is unavailable. Recording uses the natural microphone.")
                        }
                    }
                }
            }
            timerJob?.cancel()
            timerJob =
                viewModelScope.launch {
                    while (true) {
                        delay(RECORDING_TICK_MS)
                        val elapsed = System.currentTimeMillis() - recordingStartedAtMs
                        recorder.currentAmplitude()?.let(recordingAmplitudes::add)
                        if (elapsed >= MAX_RECORDING_MS) {
                            stopRecording()
                            break
                        }
                        _state.update { current ->
                            current.copy(
                                recording =
                                    current.recording?.copy(
                                        elapsedMs = elapsed,
                                        waveform = AudioWaveform.fromAmplitudes(recordingAmplitudes),
                                    ),
                            )
                        }
                    }
                }
        }

        fun lockRecording() {
            _state.update { it.copy(recording = it.recording?.copy(locked = true)) }
        }

        fun stopRecording() {
            if (_state.value.recording == null) return
            timerJob?.cancel()
            timerJob = null
            activityTracker.setRecording(false)
            val completed = recorder.stop()
            recordingAmplitudes.clear()
            _state.update { current ->
                current.copy(
                    recording = null,
                    staged = completed?.toStaged(current.config),
                    error = if (completed == null) "Recording was too short." else null,
                )
            }
        }

        fun stopForBackground() {
            cancelAllTranscriptions()
            if (_state.value.recording != null) stopRecording()
        }

        fun cancelRecording() {
            timerJob?.cancel()
            timerJob = null
            recorder.cancel()
            recordingAmplitudes.clear()
            activityTracker.setRecording(false)
            _state.update { it.copy(recording = null, error = null) }
        }

        fun deleteStaged() {
            _state.value.staged?.let { staged ->
                dismissIfPreviewing(staged.file)
                staged.file.delete()
            }
            _state.update { it.copy(staged = null, progress = null, error = null) }
        }

        fun toggleEncryption() {
            _state.update { current ->
                current.copy(staged = current.staged?.copy(encrypted = !current.staged.encrypted))
            }
        }

        fun setDestination(config: PasteBackendConfig?) {
            val normalized = config?.let(::normalizedConfig)
            viewModelScope.launch { prefs.setRememberedDestination(normalized) }
            _state.update { current ->
                current.copy(staged = current.staged?.copy(destination = normalized))
            }
        }

        fun setEncryptionDefault(enabled: Boolean) =
            viewModelScope.launch {
                prefs.setEncryptionDefault(enabled)
            }

        fun clearError() {
            _state.update { it.copy(error = null) }
        }

        /** A denied Android permission must remain visible instead of looking like a broken control. */
        fun recordingPermissionDenied() {
            _state.update { it.copy(error = VOICE_PERMISSION_DENIED_ERROR) }
        }

        fun clearNotice() {
            _state.update { it.copy(notice = null) }
        }

        fun transcribe(
            request: AudioPlaybackRequest,
            force: Boolean = false,
        ) {
            val playbackId = request.attachment.playbackId
            val previous: Job?
            val token: Long
            synchronized(transcriptionLock) {
                previous = transcriptionJobs.remove(playbackId)
                token = ++nextTranscriptionToken
                transcriptionTokens[playbackId] = token
            }
            previous?.cancel()

            val configuration = transcriptionConfiguration
            val modelId = configuration.modelId
            val settings = configuration.settings
            if (
                !request.attachment.voice ||
                !configuration.ready ||
                modelId == null ||
                settings == null
            ) {
                synchronized(transcriptionLock) {
                    if (transcriptionTokens[playbackId] == token) {
                        transcriptionTokens.remove(playbackId)
                        _state.update {
                            it.copy(
                                transcripts =
                                    it.transcripts +
                                        (
                                            playbackId to
                                                VoiceTranscriptState.Failed(
                                                    if (request.attachment.voice) {
                                                        VoiceTranscriptFailureKind.FEATURE_UNAVAILABLE
                                                    } else {
                                                        VoiceTranscriptFailureKind.INVALID_AUDIO
                                                    },
                                                )
                                        ),
                            )
                        }
                    }
                }
                return
            }

            val job =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    var audioLease: LocalAudioLease? = null
                    var pcmLease: LocalPcmAudioLease? = null
                    try {
                        publishTranscript(playbackId, token, VoiceTranscriptState.Preparing(0))
                        audioLease =
                            transcriptionCalls.materialize(request) { copied, total ->
                                publishTranscript(
                                    playbackId,
                                    token,
                                    VoiceTranscriptState.Preparing(preparationProgress(copied, total)),
                                )
                            }
                        currentCoroutineContext().ensureActive()
                        publishTranscript(playbackId, token, VoiceTranscriptState.Preparing(100))
                        pcmLease = transcriptionCalls.decode(audioLease.file)
                        currentCoroutineContext().ensureActive()

                        val cacheKey =
                            AiTranscriptCache.key(
                                plaintextAudioSha256 = pcmLease.plaintextSha256,
                                transcriptionModelSha256 = modelId,
                                settings = settings,
                            )
                        if (!force) {
                            cacheCall { transcriptionCalls.cacheGet(cacheKey) }?.let { cached ->
                                if (cached.toByteArray(Charsets.UTF_8).size > MAX_TRANSCRIPT_BYTES) {
                                    publishTranscript(
                                        playbackId,
                                        token,
                                        VoiceTranscriptState.Failed(VoiceTranscriptFailureKind.INFERENCE_FAILED),
                                    )
                                    return@launch
                                }
                                publishTranscript(
                                    playbackId,
                                    token,
                                    VoiceTranscriptState.Ready(cached, cached = true),
                                )
                                return@launch
                            }
                        }

                        publishTranscript(playbackId, token, VoiceTranscriptState.Waiting)
                        val text =
                            transcriptionCalls.transcribe(
                                modelId,
                                transcriptionCalls.modelFile(modelId),
                                pcmLease.file,
                                AiTranscriptionRequest(settings),
                            ) { progress ->
                                publishTranscript(
                                    playbackId,
                                    token,
                                    VoiceTranscriptState.Transcribing(progress.coerceIn(0, 100)),
                                )
                            }
                        currentCoroutineContext().ensureActive()
                        if (text.toByteArray(Charsets.UTF_8).size > MAX_TRANSCRIPT_BYTES) {
                            publishTranscript(
                                playbackId,
                                token,
                                VoiceTranscriptState.Failed(VoiceTranscriptFailureKind.INFERENCE_FAILED),
                            )
                            return@launch
                        }
                        if (text.isBlank()) {
                            cacheCall { transcriptionCalls.cacheRemove(cacheKey) }
                        } else {
                            cacheCall { transcriptionCalls.cachePut(cacheKey, text) }
                        }
                        publishTranscript(
                            playbackId,
                            token,
                            VoiceTranscriptState.Ready(text, cached = false),
                        )
                    } catch (_: AiExecutionUnavailableException) {
                        publishTranscript(
                            playbackId,
                            token,
                            VoiceTranscriptState.Failed(VoiceTranscriptFailureKind.FEATURE_UNAVAILABLE),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        publishTranscript(
                            playbackId,
                            token,
                            VoiceTranscriptState.Failed(voiceTranscriptFailure(failure)),
                        )
                    } finally {
                        try {
                            pcmLease?.close()
                        } finally {
                            audioLease?.close()
                        }
                        synchronized(transcriptionLock) {
                            if (transcriptionTokens[playbackId] == token) {
                                transcriptionTokens.remove(playbackId)
                                transcriptionJobs.remove(playbackId)
                            }
                        }
                    }
                }
            synchronized(transcriptionLock) {
                if (transcriptionTokens[playbackId] == token) {
                    transcriptionJobs[playbackId] = job
                } else {
                    job.cancel()
                }
            }
            job.start()
        }

        fun cancelTranscription(playbackId: String) {
            val job =
                synchronized(transcriptionLock) {
                    transcriptionTokens.remove(playbackId)
                    transcriptionJobs.remove(playbackId).also {
                        _state.update { current ->
                            current.copy(transcripts = current.transcripts - playbackId)
                        }
                    }
                }
            job?.cancel()
        }

        private fun cancelAllTranscriptions() {
            val jobs =
                synchronized(transcriptionLock) {
                    transcriptionTokens.clear()
                    transcriptionJobs.values.toList().also {
                        transcriptionJobs.clear()
                        _state.update { current -> current.copy(transcripts = emptyMap()) }
                    }
                }
            jobs.forEach { it.cancel() }
        }

        private fun publishTranscript(
            playbackId: String,
            token: Long,
            transcript: VoiceTranscriptState,
        ) {
            synchronized(transcriptionLock) {
                if (transcriptionTokens[playbackId] != token) return
                _state.update { current ->
                    current.copy(transcripts = current.transcripts + (playbackId to transcript))
                }
            }
        }

        private suspend fun <T> cacheCall(block: suspend () -> T): T =
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                throw VoiceTranscriptCacheException()
            }

        fun send() {
            val staged = _state.value.staged ?: return
            sendJob?.cancel()
            sendJob =
                viewModelScope.launch {
                    try {
                        sender
                            .send(
                                VoiceSendRequest(
                                    bufferId = bufferId,
                                    file = staged.file,
                                    durationMs = staged.durationMs,
                                    mimeType = staged.mimeType,
                                    extension = staged.extension,
                                    sizeBytes = staged.sizeBytes,
                                    waveform = staged.waveform,
                                    encrypt = staged.encrypted,
                                    destination = staged.destination,
                                ),
                            ).collect { progress ->
                                _state.update { it.copy(progress = progress, error = null) }
                                if (progress is VoiceSendProgress.Complete) {
                                    dismissIfPreviewing(staged.file)
                                    staged.file.delete()
                                    _state.update { it.copy(staged = null, progress = null) }
                                }
                            }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        _state.update {
                            it.copy(progress = null, error = error.message ?: "Voice message failed.")
                        }
                    }
                }
        }

        override fun onCleared() {
            sendJob?.cancel()
            cancelAllTranscriptions()
            cancelRecording()
            _state.value.staged?.let { staged ->
                dismissIfPreviewing(staged.file)
                staged.file.delete()
            }
        }

        private fun dismissIfPreviewing(file: File) {
            playbackController.dismiss("voice:${file.toURI()}")
        }

        private fun CompletedVoiceRecording.toStaged(config: VoiceConfig): StagedVoiceMessage =
            StagedVoiceMessage(
                file = file,
                durationMs = durationMs,
                mimeType = mimeType,
                extension = extension,
                sizeBytes = sizeBytes,
                encrypted = config.encryptionDefault,
                destination = config.rememberedDestination,
                waveform = waveform,
            )

        private companion object {
            const val RECORDING_TICK_MS = 250L
            const val MAX_RECORDING_MS = 30L * 60L * 1000L
        }
    }

private fun AiLabsState.voiceTranscriptionConfiguration(): VoiceTranscriptionConfiguration {
    val modelId = assignedModelId(AiFeature.TRANSCRIPTION)
    val model = models.firstOrNull { it.id == modelId }
    val settings = modelId?.let { settingsFor(it, AiModelCapability.TRANSCRIPTION) }
    val enabled = AiFeature.TRANSCRIPTION in enabledFeatures
    return VoiceTranscriptionConfiguration(
        enabled = enabled,
        modelId = modelId,
        settings = settings,
        ready =
            enabled &&
                settings != null &&
                model?.isReadyFor(AiModelCapability.TRANSCRIPTION, settings) == true,
    )
}

internal fun preparationProgress(
    copied: Long,
    total: Long?,
): Int =
    if (total == null || total <= 0L) {
        0
    } else {
        ((copied.coerceIn(0L, total) * 100L) / total).toInt()
    }

internal fun voiceTranscriptFailure(failure: Throwable): VoiceTranscriptFailureKind =
    when (failure) {
        is AiExecutionUnavailableException -> {
            VoiceTranscriptFailureKind.FEATURE_UNAVAILABLE
        }

        is VoiceTranscriptCacheException -> {
            VoiceTranscriptFailureKind.CACHE_FAILED
        }

        is AudioInputException -> {
            when (failure.kind) {
                AudioInputFailureKind.UNSUPPORTED_SCHEME -> {
                    VoiceTranscriptFailureKind.UNSUPPORTED_SCHEME
                }

                AudioInputFailureKind.FILE_UNAVAILABLE -> {
                    VoiceTranscriptFailureKind.FILE_UNAVAILABLE
                }

                AudioInputFailureKind.EXPIRED -> {
                    VoiceTranscriptFailureKind.EXPIRED
                }

                AudioInputFailureKind.MISSING_ENCRYPTION_KEY -> {
                    VoiceTranscriptFailureKind.MISSING_ENCRYPTION_KEY
                }

                AudioInputFailureKind.INVALID_ENCRYPTION_KEY -> {
                    VoiceTranscriptFailureKind.INVALID_ENCRYPTION_KEY
                }

                AudioInputFailureKind.AUTHENTICATION_FAILED -> {
                    VoiceTranscriptFailureKind.AUTHENTICATION_FAILED
                }

                AudioInputFailureKind.ROUTE_UNAVAILABLE -> {
                    VoiceTranscriptFailureKind.ROUTE_UNAVAILABLE
                }

                AudioInputFailureKind.TLS_FAILED -> {
                    VoiceTranscriptFailureKind.TLS_FAILED
                }

                AudioInputFailureKind.HTTP_AUTHENTICATION_FAILED -> {
                    VoiceTranscriptFailureKind.HTTP_AUTHENTICATION_FAILED
                }

                AudioInputFailureKind.HTTP_FAILED -> {
                    VoiceTranscriptFailureKind.HTTP_FAILED
                }

                AudioInputFailureKind.READ_FAILED -> {
                    VoiceTranscriptFailureKind.READ_FAILED
                }

                AudioInputFailureKind.TOO_LARGE -> {
                    VoiceTranscriptFailureKind.INPUT_TOO_LARGE
                }
            }
        }

        is PcmAudioException -> {
            when (failure.kind) {
                PcmAudioFailureKind.NO_AUDIO -> VoiceTranscriptFailureKind.NO_AUDIO
                PcmAudioFailureKind.UNSUPPORTED_CODEC -> VoiceTranscriptFailureKind.UNSUPPORTED_CODEC
                PcmAudioFailureKind.TOO_LONG -> VoiceTranscriptFailureKind.AUDIO_TOO_LONG
                PcmAudioFailureKind.DECODE_FAILED -> VoiceTranscriptFailureKind.DECODE_FAILED
            }
        }

        is AiRuntimeException -> {
            when (failure.failure) {
                AiRuntimeFailure.MODEL_OPEN -> {
                    VoiceTranscriptFailureKind.MODEL_OPEN
                }

                AiRuntimeFailure.INVALID_FORMAT -> {
                    VoiceTranscriptFailureKind.INVALID_MODEL_FORMAT
                }

                AiRuntimeFailure.TRUNCATED_MODEL -> {
                    VoiceTranscriptFailureKind.TRUNCATED_MODEL
                }

                AiRuntimeFailure.CORRUPT_MODEL -> {
                    VoiceTranscriptFailureKind.CORRUPT_MODEL
                }

                AiRuntimeFailure.UNSUPPORTED_ARCHITECTURE -> {
                    VoiceTranscriptFailureKind.UNSUPPORTED_MODEL_ARCHITECTURE
                }

                AiRuntimeFailure.INVALID_REQUEST -> {
                    VoiceTranscriptFailureKind.INVALID_REQUEST
                }

                AiRuntimeFailure.OUT_OF_MEMORY -> {
                    VoiceTranscriptFailureKind.OUT_OF_MEMORY
                }

                AiRuntimeFailure.INVALID_AUDIO -> {
                    VoiceTranscriptFailureKind.INVALID_AUDIO
                }

                AiRuntimeFailure.NO_MODEL_LOADED -> {
                    VoiceTranscriptFailureKind.NO_MODEL_LOADED
                }

                AiRuntimeFailure.INFERENCE -> {
                    VoiceTranscriptFailureKind.INFERENCE_FAILED
                }

                AiRuntimeFailure.NATIVE -> {
                    VoiceTranscriptFailureKind.NATIVE_FAILURE
                }
            }
        }

        else -> {
            VoiceTranscriptFailureKind.INFERENCE_FAILED
        }
    }
