package io.github.trevarj.motd.ui.chat

import android.content.ContentResolver
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.AVAILABLE_ATTACHMENT_BACKENDS
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploadContext
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.HostedImageSnapshot
import io.github.trevarj.motd.attachment.HostedImageTooLarge
import io.github.trevarj.motd.attachment.MAX_X0AT_LIMIT_BYTES
import io.github.trevarj.motd.attachment.MetadataNotSupportedException
import io.github.trevarj.motd.attachment.OffHostUploadException
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.attachment.isHostedImage
import io.github.trevarj.motd.attachment.stripsHostedUploadMetadata
import io.github.trevarj.motd.ui.share.PendingShare
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
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
        private val _batchRequest = MutableStateFlow<AttachmentBatchRequest?>(null)
        internal val batchRequest = _batchRequest.asStateFlow()
        private val _batchReview = MutableStateFlow<AttachmentBatchReview?>(null)
        internal val batchReview = _batchReview.asStateFlow()
        private var batchJob: Job? = null
        private var batchTarget: AttachmentBatchTarget? = null
        private var batchLease: AutoCloseable? = null

        internal fun queueBatch(
            bufferId: Long,
            files: List<PendingShare.File>,
            lease: AutoCloseable? = null,
        ) {
            if (_batchRequest.value != null || files.isEmpty()) {
                lease?.close()
                return
            }
            batchLease = lease
            _batchRequest.value = AttachmentBatchRequest(UUID.randomUUID().toString(), bufferId, files.toList())
        }

        internal fun updateBatchTarget(
            id: String,
            target: AttachmentBatchTarget,
        ) {
            if (_batchRequest.value?.id == id) batchTarget = target
        }

        internal fun reviewBatch(
            id: String,
            target: AttachmentBatchTarget,
            resolver: ContentResolver,
        ) {
            val request = _batchRequest.value?.takeIf { it.id == id } ?: return
            updateBatchTarget(id, target)
            if (_batchReview.value != null) return
            val rows = request.files.map { AttachmentBatchRow(it.uri.lastPathSegment.orEmpty()) }
            val review = AttachmentBatchReview(request, target, rows)
            runBatch(review) {
                val settings = batchSettings()
                val liveTarget = batchTarget ?: target
                val destination = settings?.let { liveTarget.destination(request, it, selected = null) }
                if (settings != null) {
                    updateBatch(id) {
                        it.copy(target = liveTarget, settings = settings, destination = destination)
                    }
                }
                for (index in rows.indices) {
                    inspectBatchRow(request, index, resolver, destination?.config?.sizeLimitBytes)
                }
            }
        }

        internal fun reviewChangedDestination(shown: AttachmentBatchReview) {
            if (_batchReview.value !== shown || shown.busy || !shown.destinationChanged) return
            runBatch(shown) {
                val settings = batchSettings() ?: return@runBatch
                val target = batchTarget ?: return@runBatch
                updateBatch(shown.request.id) { review ->
                    review.copy(
                        target = target,
                        settings = settings,
                        destination = target.destination(review.request, settings, review.selected),
                        rows =
                            review.rows.map { row ->
                                if (row.source != null && row.status is AttachmentBatchStatus.Failed) {
                                    row.copy(status = AttachmentBatchStatus.Ready)
                                } else {
                                    row
                                }
                            },
                        started = false,
                        destinationChanged = false,
                    )
                }
            }
        }

        private suspend fun batchSettings(): PasteBackendConfig? =
            try {
                currentConfig()
            } catch (_: IOException) {
                null
            }

        internal fun selectBatchBackend(
            id: String,
            backend: AttachmentBackend,
        ) {
            updateBatch(id) { review ->
                val settings = review.settings
                if (review.busy || review.started || settings == null ||
                    backend !in AVAILABLE_ATTACHMENT_BACKENDS || !backend.acceptsBinary ||
                    (backend == AttachmentBackend.SOJU_FILEHOST && review.target.sojuFileHostUrl == null)
                ) {
                    review
                } else {
                    review.copy(selected = backend, destination = review.target.destination(review.request, settings, backend))
                }
            }
        }

        internal fun uploadBatch(
            shown: AttachmentBatchReview,
            context: Context,
        ) {
            val destination = shown.destination ?: return
            if (_batchReview.value !== shown || shown.busy || shown.started || shown.destinationChanged || job != null) return
            val ready = shown.rows.indices.filter { shown.rows[it].status == AttachmentBatchStatus.Ready && shown.rows[it].isUploadable(destination) }
            if (ready.isEmpty()) return
            runBatch(shown.copy(started = true)) {
                for (index in ready) {
                    val active = _batchReview.value ?: break
                    if (active.stopping || active.destinationChanged) break
                    transferBatchRow(shown, index, context, allowOriginal = false)
                }
            }
        }

        internal fun retryBatch(
            shown: AttachmentBatchReview,
            index: Int,
            context: Context,
            allowOriginal: Boolean = false,
        ) {
            if (_batchReview.value !== shown || shown.busy || shown.destinationChanged || job != null) return
            val row = shown.rows.getOrNull(index) ?: return
            val destination = shown.destination
            val needsRecheck = row.needsCheck(destination)
            val failed = row.status as? AttachmentBatchStatus.Failed
            if (row.status !is AttachmentBatchStatus.Ready && failed == null) return
            if (needsRecheck) {
                if (allowOriginal) return
                runBatch(shown) {
                    setBatchRow(shown.request.id, index, row.copy(status = AttachmentBatchStatus.Checking))
                    inspectBatchRow(shown.request, index, context.contentResolver, destination?.config?.sizeLimitBytes)
                }
            } else {
                if (destination == null || !shown.started || !row.isUploadable(destination)) return
                if (allowOriginal != (failed?.needsOriginalConsent == true)) return
                runBatch(shown) {
                    transferBatchRow(shown, index, context, allowOriginal)
                }
            }
        }

        internal fun stopBatch(id: String) {
            updateBatch(id) { if (it.started && it.busy && !it.finishing) it.copy(stopping = true) else it }
        }

        internal fun finishBatch(
            id: String,
            addLinks: Boolean,
            onFinished: suspend (List<String>) -> Boolean,
        ): Boolean {
            val review = _batchReview.value?.takeIf { it.request.id == id } ?: return false
            if (review.finishing || (review.busy && (addLinks || review.started))) return false
            val urls =
                if (addLinks) {
                    review.rows.mapNotNull { (it.status as? AttachmentBatchStatus.Uploaded)?.record?.url }
                } else {
                    emptyList()
                }
            batchJob?.cancel()
            runBatch(review.copy(finishing = true, finishError = false)) {
                val accepted =
                    try {
                        onFinished(urls)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                if (accepted) {
                    discardBatch(id)
                } else {
                    updateBatch(id) { it.copy(finishError = true) }
                }
            }
            return true
        }

        private fun discardBatch(id: String) {
            if (_batchRequest.value?.id != id) return
            batchTarget = null
            _batchReview.value = null
            _batchRequest.value = null
            releaseBatchLease()
        }

        private fun releaseBatchLease() {
            val lease = batchLease
            batchLease = null
            lease?.close()
        }

        private fun runBatch(
            review: AttachmentBatchReview,
            block: suspend () -> Unit,
        ) {
            val id = review.request.id
            _batchReview.value = review.copy(busy = true)
            batchJob =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        block()
                    } finally {
                        if (batchJob === coroutineContext[Job]) {
                            batchJob = null
                            updateBatch(id) { it.copy(busy = false, stopping = false, finishing = false) }
                        }
                    }
                }
            batchJob?.start()
        }

        private suspend fun inspectBatchRow(
            request: AttachmentBatchRequest,
            index: Int,
            resolver: ContentResolver,
            limitBytes: Long?,
        ) {
            val inspected =
                withContext(Dispatchers.IO) {
                    val row = resolver.inspectBatchFile(request.files[index])
                    val source = row.source ?: return@withContext row
                    if (limitBytes == null || (source.size ?: 0L) > limitBytes) return@withContext row
                    try {
                        val fingerprint = HostedImageSnapshot.reviewBatchBytes(resolver, source, limitBytes)
                        row.copy(source = source.copy(size = fingerprint.size), reviewedDigest = fingerprint.sha256)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (oversized: HostedImageTooLarge) {
                        row.copy(source = source.copy(size = oversized.minimumSize))
                    } catch (_: Exception) {
                        row.copy(source = null, status = AttachmentBatchStatus.Failed(R.string.upload_batch_file_unavailable))
                    }
                }
            setBatchRow(request.id, index, inspected)
        }

        private suspend fun transferBatchRow(
            shown: AttachmentBatchReview,
            index: Int,
            context: Context,
            allowOriginal: Boolean,
        ) {
            val id = shown.request.id
            val row = shown.rows[index]
            val source = checkNotNull(row.source)
            val expectedDigest = checkNotNull(row.reviewedDigest)
            val destination = checkNotNull(shown.destination)
            var uploaded = false
            var snapshot: HostedImageSnapshot? = null
            var reviewedRow = row
            setBatchRow(id, index, row.copy(status = AttachmentBatchStatus.Uploading(UploadProgress.Transferring(0, source.size))))
            try {
                if (!isCurrentBatchDestination(shown, destination, index, row)) return
                val inspected = withContext(Dispatchers.IO) { context.contentResolver.inspectBatchFile(shown.request.files[index]) }
                val current = inspected.source
                if (current == null || inspected.isOversized(destination)) {
                    setBatchRow(
                        id,
                        index,
                        if (current == null) inspected else inspected.copy(status = AttachmentBatchStatus.Failed(R.string.upload_batch_file_changed)),
                    )
                    return
                }
                val captured = HostedImageSnapshot.captureBatch(context, current, destination.config.sizeLimitBytes)
                snapshot = captured
                val actual = current.copy(mimeType = captured.source.mimeType, size = captured.source.size)
                val digest = checkNotNull(captured.contentDigest)
                reviewedRow = inspected.copy(source = actual, reviewedDigest = digest)
                val changed =
                    source.name != actual.name ||
                        !source.mimeType.equals(actual.mimeType, ignoreCase = true) ||
                        source.size != actual.size ||
                        !expectedDigest.contentEquals(digest)
                if (changed) {
                    setBatchRow(
                        id,
                        index,
                        reviewedRow.copy(
                            status = AttachmentBatchStatus.Failed(R.string.upload_batch_file_changed),
                        ),
                    )
                    return
                }
                if (!isCurrentBatchDestination(shown, destination, index, reviewedRow)) return
                val policy = destination.config
                // Unknown bytes cannot use an unverified image declaration to bypass document stripping.
                val stripMetadata =
                    if (captured.detectedImageMime == null && (actual.isHostedImage() || actual.mimeType == "application/octet-stream")) {
                        policy.stripMetadata || policy.stripHostedImageMetadata == true
                    } else {
                        policy.stripsHostedUploadMetadata(actual)
                    }
                uploader.upload(captured.source, policy.copy(stripMetadata = stripMetadata), destination.context.copy(allowOriginalMetadata = allowOriginal)).collect { progress ->
                    when (progress) {
                        is UploadProgress.Transferring -> {
                            if (!uploaded) setBatchRow(id, index, reviewedRow.copy(status = AttachmentBatchStatus.Uploading(progress)))
                        }

                        is UploadProgress.Complete -> {
                            if (!uploaded) {
                                uploaded = true
                                setBatchRow(id, index, reviewedRow.copy(status = AttachmentBatchStatus.Uploaded(progress.record), reviewedDigest = null))
                            }
                        }
                    }
                }
                if (!uploaded) setBatchRow(id, index, reviewedRow.copy(status = AttachmentBatchStatus.Failed()))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (oversized: HostedImageTooLarge) {
                val overLimit = checkNotNull(reviewedRow.source).copy(size = oversized.minimumSize)
                setBatchRow(
                    id,
                    index,
                    reviewedRow.copy(source = overLimit, status = AttachmentBatchStatus.Failed(R.string.upload_batch_file_changed), reviewedDigest = null),
                )
            } catch (failure: Exception) {
                if (!uploaded) {
                    val originalRejected = failure is MetadataNotSupportedException
                    val details = failure.message.takeUnless { originalRejected || failure is SecurityException }
                    val status = AttachmentBatchStatus.Failed(details = details, needsOriginalConsent = originalRejected && snapshot != null)
                    setBatchRow(id, index, reviewedRow.copy(status = status))
                }
            } finally {
                snapshot?.close()
            }
        }

        private suspend fun isCurrentBatchDestination(
            shown: AttachmentBatchReview,
            destination: AttachmentBatchDestination,
            index: Int,
            row: AttachmentBatchRow,
        ): Boolean {
            val latestSettings = currentConfig()
            if (batchTarget?.destination(shown.request, latestSettings, shown.selected) == destination) return true
            setBatchRow(shown.request.id, index, row.copy(status = AttachmentBatchStatus.Failed(R.string.upload_batch_destination_changed)))
            updateBatch(shown.request.id) { it.copy(destinationChanged = true) }
            return false
        }

        private inline fun updateBatch(
            id: String,
            transform: (AttachmentBatchReview) -> AttachmentBatchReview,
        ) {
            val batch = _batchReview.value?.takeIf { it.request.id == id } ?: return
            _batchReview.value = transform(batch)
        }

        private fun setBatchRow(
            id: String,
            index: Int,
            row: AttachmentBatchRow,
        ) {
            updateBatch(id) { review ->
                review.copy(rows = review.rows.toMutableList().also { it[index] = row })
            }
        }

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
            releaseBatchLease()
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

        internal fun setUploadFavorite(
            url: String,
            favorite: Boolean,
            errorMessage: String,
        ) = viewModelScope.launch {
            try {
                prefs.setUploadFavorite(url, favorite)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _error.value = AttachmentError(errorMessage)
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
