package io.github.trevarj.motd.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploadContext
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.HostedImageSnapshot
import io.github.trevarj.motd.attachment.MAX_X0AT_LIMIT_BYTES
import io.github.trevarj.motd.attachment.MetadataNotSupportedException
import io.github.trevarj.motd.attachment.OffHostUploadException
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.attachment.isHostedImage
import io.github.trevarj.motd.attachment.stripsHostedUploadMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

class AttachmentError(
    val message: String,
    val offHostNetworkId: Long? = null,
    val metadataNotSupported: Boolean = false,
)

class AttachmentAttempt(
    val source: AttachmentSource,
    val config: PasteBackendConfig,
    val context: AttachmentUploadContext,
    val replaceDraft: Boolean,
)

@HiltViewModel
class AttachmentViewModel
    @Inject
    constructor(
        private val prefs: AttachmentPrefs,
        private val uploader: AttachmentUploader,
    ) : ViewModel() {
        internal suspend fun currentConfig(): PasteBackendConfig = prefs.config.first()

        val config = prefs.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PasteBackendConfig())
        val recent = prefs.recentUploads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), emptyList())
        private val _progress = MutableStateFlow<UploadProgress?>(null)
        val progress: StateFlow<UploadProgress?> = _progress.asStateFlow()
        private val _error = MutableStateFlow<AttachmentError?>(null)
        val error: StateFlow<AttachmentError?> = _error.asStateFlow()
        private val _attempt = MutableStateFlow<AttachmentAttempt?>(null)
        val attempt: StateFlow<AttachmentAttempt?> = _attempt.asStateFlow()
        private var job: Job? = null
        private var uploadingReview: HostedImageSnapshot? = null
        private val reviews = mutableMapOf<String, HostedImageSnapshot>()

        internal suspend fun imageReviewLimit(): Long =
            prefs.config
                .first()
                .sizeLimitBytes
                .coerceIn(1, MAX_X0AT_LIMIT_BYTES)

        internal fun reviewedImage(session: String): HostedImageSnapshot? = reviews[session]

        internal fun replaceReviewedImage(
            session: String,
            snapshot: HostedImageSnapshot? = null,
        ) {
            val old = reviews[session]
            if (old === snapshot) return
            if (old != null && uploadingReview === old) {
                cancel()
                clearError()
            }
            old?.close()
            if (snapshot == null) reviews.remove(session) else reviews[session] = snapshot
        }

        override fun onCleared() {
            reviews.values.forEach { it.close() }
            reviews.clear()
        }

        fun upload(
            source: AttachmentSource,
            override: PasteBackendConfig = config.value,
            context: AttachmentUploadContext = AttachmentUploadContext(),
            replaceDraft: Boolean = false,
            onComplete: (UploadRecord) -> Unit,
        ) {
            job?.cancel()
            val review = (source as? AttachmentSource.LocalFile)?.reviewedImage
            uploadingReview = review
            _error.value = null
            _attempt.value = null
            _progress.value = UploadProgress.Transferring(0, null)
            job =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val snapshot = prefs.config.first()
                        val stripMetadata =
                            snapshot.stripsHostedUploadMetadata(source) || (source !is AttachmentSource.Text && override.stripsHostedUploadMetadata(source))
                        val destination =
                            if (source.isHostedImage()) {
                                override.copy(stripMetadata = stripMetadata, stripHostedImageMetadata = stripMetadata)
                            } else {
                                override.copy(stripMetadata = stripMetadata)
                            }
                        _attempt.value = AttachmentAttempt(source, destination, context.copy(allowOriginalMetadata = false), replaceDraft)
                        uploader.upload(source, destination, context).collect { update ->
                            _progress.value = update
                            if (update is UploadProgress.Complete) {
                                _attempt.value = null
                                uploadingReview = null
                                onComplete(update.record)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        _error.value =
                            AttachmentError(
                                failure.message ?: "Upload failed",
                                (failure as? OffHostUploadException)?.networkId,
                                failure is MetadataNotSupportedException,
                            )
                    } finally {
                        if (job === coroutineContext[Job]) {
                            job = null
                            _progress.value = null
                        }
                    }
                }
            job?.start()
        }

        internal fun setStripMetadata(
            source: AttachmentSource,
            enabled: Boolean,
        ) = viewModelScope.launch {
            prefs.updateConfig {
                if (source.isHostedImage()) it.copy(stripHostedImageMetadata = enabled) else it.copy(stripMetadata = enabled)
            }
        }

        fun fail(message: String) {
            _attempt.value = null
            _error.value = AttachmentError(message)
        }

        fun cancel() {
            job?.cancel()
            job = null
            uploadingReview = null
            _progress.value = null
            _attempt.value = null
        }

        fun clearError() {
            _error.value = null
        }

        fun clearAttempt(expected: AttachmentAttempt? = _attempt.value) {
            if (_attempt.value === expected) _attempt.value = null
        }

        fun clearHistory() =
            viewModelScope.launch {
                try {
                    prefs.clearUploadHistory()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _error.value = AttachmentError(failure.message ?: "Clear history failed")
                }
            }

        fun delete(record: UploadRecord) =
            viewModelScope.launch {
                runCatching {
                    uploader.delete(record)
                    prefs.removeUpload(record.url)
                }.onFailure { _error.value = AttachmentError(it.message ?: "Delete failed") }
            }
    }
