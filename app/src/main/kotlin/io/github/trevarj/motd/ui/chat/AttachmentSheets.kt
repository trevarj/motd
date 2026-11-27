package io.github.trevarj.motd.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.icu.lang.UCharacter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertLink
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.AVAILABLE_ATTACHMENT_BACKENDS
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploadContext
import io.github.trevarj.motd.attachment.HostedImageSnapshot
import io.github.trevarj.motd.attachment.HostedImageTooLarge
import io.github.trevarj.motd.attachment.MAX_X0AT_LIMIT_BYTES
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.attachment.forBackend
import io.github.trevarj.motd.attachment.guessMimeType
import io.github.trevarj.motd.attachment.isHostedAttachmentUri
import io.github.trevarj.motd.attachment.isHostedImage
import io.github.trevarj.motd.attachment.normalizedConfig
import io.github.trevarj.motd.attachment.sizeOrNull
import io.github.trevarj.motd.attachment.stripsHostedUploadMetadata
import io.github.trevarj.motd.attachment.supports
import io.github.trevarj.motd.audio.formatAudioDuration
import io.github.trevarj.motd.ui.components.MetadataPill
import io.github.trevarj.motd.ui.imageviewer.LocalImageReviewDialog
import io.github.trevarj.motd.ui.settings.SwitchRow
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.readableForeground
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import io.github.trevarj.motd.ui.components.EffectDropdownMenu as DropdownMenu
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet as ModalBottomSheet
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

private sealed interface AttachmentFlow {
    data object Idle : AttachmentFlow

    data object Sources : AttachmentFlow

    data object Photos : AttachmentFlow

    data object EditText : AttachmentFlow

    data class Prepare(
        val source: AttachmentSource,
        val camera: Boolean = false,
    ) : AttachmentFlow

    data class Confirm(
        val source: AttachmentSource,
        val replaceDraft: Boolean,
        // Follow the default backend until the user explicitly chooses one.
        val backend: AttachmentBackend? = null,
        val originalSizeIsLowerBound: Boolean = false,
    ) : AttachmentFlow {
        fun needsVideoSizeCheck(limitBytes: Long): Boolean = originalSizeIsLowerBound && (source.sizeOrNull() ?: 0L) <= limitBytes
    }
}

private class VideoReviewConsent(
    val request: AttachmentFlow.Confirm,
    val config: PasteBackendConfig,
    val context: AttachmentUploadContext,
) {
    var revoked by mutableStateOf(false)
        private set

    fun revoke() {
        revoked = true
    }

    fun matches(
        config: PasteBackendConfig,
        context: AttachmentUploadContext,
    ): Boolean =
        !revoked && this.context == context &&
            this.config.sizeLimitBytes == config.sizeLimitBytes &&
            this.config.stripsHostedUploadMetadata(request.source) == config.stripsHostedUploadMetadata(request.source) &&
            sameUploadConsent(this.config, config)
}

internal data class UploadDestination(
    val label: String,
    val config: PasteBackendConfig,
)

internal data class GalleryPhoto(
    val uri: Uri,
    val name: String,
    val mimeType: String?,
    val size: Long?,
)

internal fun uploadDestinations(
    source: AttachmentSource,
    config: PasteBackendConfig,
    sojuFileHostAvailable: Boolean = false,
): List<UploadDestination> =
    AVAILABLE_ATTACHMENT_BACKENDS
        .filter { backend ->
            backend.supports(source) &&
                (backend != AttachmentBackend.SOJU_FILEHOST || sojuFileHostAvailable)
        }.map { backend ->
            UploadDestination(backend.label, config.forBackend(backend))
        }

internal fun preferredUploadConfig(
    source: AttachmentSource,
    configured: PasteBackendConfig,
    sojuFileHostAvailable: Boolean,
    preferSojuFileHost: Boolean,
): PasteBackendConfig =
    when {
        preferSojuFileHost && sojuFileHostAvailable && source !is AttachmentSource.Text -> {
            configured.forBackend(AttachmentBackend.SOJU_FILEHOST)
        }

        !configured.backend.supports(source) -> {
            configured.forBackend(AttachmentBackend.CRAFTERBIN)
        }

        else -> {
            configured
        }
    }

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheets(
    open: Boolean,
    currentDraft: String,
    networkId: Long?,
    sojuFileHostUrl: String?,
    preferSojuFileHost: Boolean = false,
    startWithCurrentDraft: Boolean = false,
    // Shared/pasted image hand-off: skip source selection and confirm this file directly.
    sharedFile: PendingShare.File? = null,
    directFileTransferAvailable: Boolean = false,
    imageOnly: Boolean = false,
    onDismiss: () -> Unit,
    onInsertUrl: (String) -> Unit,
    onReplaceDraft: (String) -> Unit,
    onOpenNetworkSettings: (Long) -> Unit,
    onDirectFile: (Uri) -> Unit = {},
    viewModel: AttachmentViewModel = hiltViewModel(),
    onUploaded: ((UploadRecord) -> Unit)? = null,
    prepareUploadCompletion: ((replacedDraft: String?) -> (UploadRecord) -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val cameraStartError = stringResource(R.string.upload_camera_failed)
    val localMediaRequiredError = stringResource(R.string.settings_local_media_required)
    val defaultConfig by viewModel.config.collectAsStateWithLifecycle()
    val sojuFileHostAvailable = sojuFileHostUrl != null
    val disclosedContext = AttachmentUploadContext(networkId, expectedSojuFileHostUrl = sojuFileHostUrl)
    val latestContext by rememberUpdatedState(disclosedContext)
    val latestPreferSojuFileHost by rememberUpdatedState(preferSojuFileHost)
    val consentScope = rememberCoroutineScope()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var flow by remember { mutableStateOf<AttachmentFlow>(AttachmentFlow.Idle) }
    var pasteText by remember { mutableStateOf("") }
    val attempt by viewModel.attempt.collectAsStateWithLifecycle()
    var compressionConsent by remember { mutableStateOf<VideoReviewConsent?>(null) }
    var compression by remember { mutableStateOf<UploadVideoReview?>(null) }
    var compressionJob by remember { mutableStateOf<Job?>(null) }
    var uploadingCompressed by remember { mutableStateOf(false) }
    val readyVideo = (compression as? UploadVideoReview.Ready)?.video
    val lastAttempt =
        attempt?.let {
            if (it.source === readyVideo?.source) {
                compressionConsent?.takeUnless { consent -> consent.revoked }?.request
            } else {
                AttachmentFlow.Confirm(it.source, it.replaceDraft, it.config.backend)
            }
        }
    val reviewSession = rememberSaveable { UUID.randomUUID().toString() }
    val entryStarted = remember { mutableStateOf(false) }
    var captureName by rememberSaveable { mutableStateOf<String?>(null) }
    var captureReady by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<UploadRecord?>(null) }
    var backendPickerRequest by remember { mutableStateOf<AttachmentFlow.Confirm?>(null) }
    var cameraPermissionGranted by remember { mutableStateOf(context.hasCameraPermission()) }
    var contentUnavailable by remember { mutableStateOf(false) }
    var photoAccessGranted by remember { mutableStateOf(context.hasPhotoAccess()) }
    val galleryPhotos by
        produceState(emptyList<GalleryPhoto>(), flow, photoAccessGranted) {
            value =
                if (flow == AttachmentFlow.Photos && photoAccessGranted) {
                    withContext(Dispatchers.IO) {
                        runCatching { context.contentResolver.recentGalleryPhotos() }.getOrDefault(emptyList())
                    }
                } else {
                    emptyList()
                }
        }

    fun clearCameraCapture() {
        cameraCaptureFile(context, captureName)?.let { file ->
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
            context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            file.delete()
        }
        captureName = null
        captureReady = false
    }

    fun clearCompression(cancelUpload: Boolean = false) {
        val ready = compression as? UploadVideoReview.Ready
        if (cancelUpload && uploadingCompressed) {
            viewModel.cancel()
            viewModel.clearError()
        }
        uploadingCompressed = false
        compressionConsent = null
        compression = null
        compressionJob?.cancel()
        compressionJob = null
        ready?.close()
    }

    DisposableEffect(viewModel, reviewSession) {
        onDispose {
            clearCompression(cancelUpload = true)
            if (activity?.isChangingConfigurations != true) {
                viewModel.replaceReviewedImage(reviewSession)
                clearCameraCapture()
            }
        }
    }

    LaunchedEffect(flow) {
        if (flow == AttachmentFlow.Photos) {
            cameraPermissionGranted = context.hasCameraPermission()
            photoAccessGranted = context.hasPhotoAccess()
        }
    }

    LaunchedEffect(open, startWithCurrentDraft, sharedFile) {
        val restoring = !entryStarted.value
        entryStarted.value = true
        if (!open || !restoring) {
            flow = AttachmentFlow.Idle
            backendPickerRequest = null
            contentUnavailable = false
            clearCompression(cancelUpload = true)
            viewModel.replaceReviewedImage(reviewSession)
            clearCameraCapture()
        }
        if (!open) return@LaunchedEffect
        if (restoring) {
            viewModel.reviewedImage(reviewSession)?.let { review ->
                if (viewModel.progress.value == null && viewModel.error.value == null) {
                    flow = AttachmentFlow.Confirm(review.source, false)
                }
                return@LaunchedEffect
            }
            cameraCaptureFile(context, captureName)?.let { captured ->
                if (captureReady) {
                    flow = AttachmentFlow.Prepare(AttachmentSource.LocalFile(captured, captured.name, "image/jpeg"), camera = true)
                }
                // A pending camera contract is delivered by the activity result registry.
                return@LaunchedEffect
            }
        }
        flow =
            when {
                sharedFile != null -> {
                    try {
                        require(sharedFile.uri.isHostedAttachmentUri()) { "Shared files require a local content or file URI" }
                        val (mime, meta) =
                            withContext(Dispatchers.IO) {
                                (sharedFile.mimeType ?: context.contentResolver.getType(sharedFile.uri)) to
                                    context.contentResolver.queryMeta(sharedFile.uri)
                            }
                        val source =
                            if (mime?.startsWith("image/") == true) {
                                AttachmentSource.Photo(sharedFile.uri, meta.first, mime, meta.second)
                            } else {
                                AttachmentSource.Document(sharedFile.uri, meta.first, mime, meta.second)
                            }
                        AttachmentFlow.Prepare(source)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        contentUnavailable = true
                        AttachmentFlow.Idle
                    }
                }

                startWithCurrentDraft && currentDraft.isNotBlank() -> {
                    AttachmentFlow.Confirm(AttachmentSource.Text(currentDraft), true)
                }

                else -> {
                    AttachmentFlow.Sources
                }
            }
    }

    val preparing = flow as? AttachmentFlow.Prepare
    val latestResources by rememberUpdatedState(LocalResources.current)
    LaunchedEffect(preparing) {
        preparing ?: return@LaunchedEffect
        try {
            val configured = viewModel.currentConfig()
            val limit = configured.sizeLimitBytes.coerceIn(1, MAX_X0AT_LIMIT_BYTES)
            val source = preparing.source
            val size =
                if (source is AttachmentSource.Document && source.sizeOrNull() == null && source.isLocalUploadVideo()) {
                    val selected = preferredUploadConfig(source, configured, latestContext.expectedSojuFileHostUrl != null, latestPreferSojuFileHost)
                    context.contentResolver.probeVideoSize(source.uri, normalizedConfig(selected).sizeLimitBytes)
                } else {
                    null
                }
            val original = if (size != null && source is AttachmentSource.Document) source.copy(size = size.first) else source
            val review = HostedImageSnapshot.capture(context, original, limit)
            if (flow !== preparing) {
                review?.close()
                return@LaunchedEffect
            }
            viewModel.replaceReviewedImage(reviewSession, review)
            flow = AttachmentFlow.Confirm(review?.source ?: original, false, originalSizeIsLowerBound = review == null && size?.second == true)
            if (preparing.camera) clearCameraCapture()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (oversized: HostedImageTooLarge) {
            if (flow !== preparing) return@LaunchedEffect
            val size = maxOf(oversized.minimumSize, preparing.source.sizeOrNull() ?: 0)
            if (preparing.camera) clearCameraCapture()
            flow = AttachmentFlow.Sources
            val sizeLabel = formatBytes(size)
            val limitLabel = formatBytes(oversized.limitBytes)
            val exact = sizeLabel == limitLabel
            viewModel.fail(
                latestResources.getString(
                    R.string.upload_over_size_limit,
                    if (exact) "$size B" else sizeLabel,
                    if (exact) "${oversized.limitBytes} B" else limitLabel,
                ),
            )
        } catch (_: Exception) {
            if (flow !== preparing) return@LaunchedEffect
            if (preparing.camera) clearCameraCapture()
            contentUnavailable = true
            flow = AttachmentFlow.Idle
        }
    }

    fun closeSourceSheet() {
        flow = AttachmentFlow.Idle
        clearCompression(cancelUpload = true)
        viewModel.replaceReviewedImage(reviewSession)
        clearCameraCapture()
        backendPickerRequest = null
        contentUnavailable = false
        onDismiss()
    }

    fun select(
        uri: Uri,
        photo: Boolean,
    ) {
        if (!uri.isHostedAttachmentUri()) {
            viewModel.clearAttempt()
            flow = AttachmentFlow.Sources
            viewModel.fail(localMediaRequiredError)
            return
        }
        val meta = context.contentResolver.queryMeta(uri)
        val source =
            if (photo) {
                AttachmentSource.Photo(uri, meta.first, context.contentResolver.getType(uri), meta.second)
            } else {
                AttachmentSource.Document(uri, meta.first, context.contentResolver.getType(uri), meta.second)
            }
        flow = AttachmentFlow.Prepare(source)
    }

    fun uploadConfig(
        request: AttachmentFlow.Confirm,
        configured: PasteBackendConfig = defaultConfig,
        fileHostAvailable: Boolean = sojuFileHostAvailable,
        preferFileHost: Boolean = preferSojuFileHost,
    ): PasteBackendConfig =
        request.backend?.let(configured::forBackend)
            ?: normalizedConfig(preferredUploadConfig(request.source, configured, fileHostAvailable, preferFileHost))

    fun currentUploadConfig(
        request: AttachmentFlow.Confirm,
        configured: PasteBackendConfig,
    ): PasteBackendConfig = uploadConfig(request, configured, latestContext.expectedSojuFileHostUrl != null, latestPreferSojuFileHost)

    val currentConfirmation = flow as? AttachmentFlow.Confirm
    val currentLimit = currentConfirmation?.let { uploadConfig(it).sizeLimitBytes }
    LaunchedEffect(currentConfirmation, currentLimit) {
        val request = currentConfirmation ?: return@LaunchedEffect
        val limit = currentLimit ?: return@LaunchedEffect
        val source = request.source as? AttachmentSource.Document ?: return@LaunchedEffect
        if (!request.needsVideoSizeCheck(limit)) return@LaunchedEffect
        val size = context.contentResolver.probeVideoSize(source.uri, limit)
        if (flow === request) {
            flow = request.copy(source = source.copy(size = size?.first), originalSizeIsLowerBound = size?.second == true)
        }
    }

    fun reconcileCompression(
        consent: VideoReviewConsent,
        settings: PasteBackendConfig,
    ) {
        if (compressionConsent !== consent) return
        val confirmation = flow as? AttachmentFlow.Confirm
        if ((confirmation == null || consent.request === confirmation) &&
            consent.matches(currentUploadConfig(consent.request, settings), latestContext)
        ) {
            return
        }
        if (uploadingCompressed && viewModel.progress.value != null) {
            // The accepted transfer can finish using its lease, but its review can never authorize retry.
            consent.revoke()
            (compression as? UploadVideoReview.Ready)?.close()
            return
        }
        if (uploadingCompressed) {
            viewModel.clearAttempt()
            flow = consent.request
        }
        clearCompression()
    }

    LaunchedEffect(compressionConsent) {
        val consent = compressionConsent ?: return@LaunchedEffect
        viewModel.uploadPreferences
            .catch { if (it !is IOException) throw it }
            .collect { reconcileCompression(consent, it) }
    }
    LaunchedEffect(currentConfirmation, defaultConfig, disclosedContext, preferSojuFileHost, progress) {
        compressionConsent?.let { reconcileCompression(it, defaultConfig) }
    }

    fun compressVideo(
        request: AttachmentFlow.Confirm,
        config: PasteBackendConfig,
        destination: AttachmentUploadContext,
    ) {
        if (flow !== request || compression == UploadVideoReview.Working || compression is UploadVideoReview.Ready ||
            !request.source.canCompressVideo(config.sizeLimitBytes)
        ) {
            return
        }
        val consent = VideoReviewConsent(request, config, destination)
        compressionConsent = consent
        compression = UploadVideoReview.Working
        compressionJob =
            consentScope.launch {
                fun isCurrent(configured: PasteBackendConfig): Boolean =
                    compressionConsent === consent && (flow === request || backendPickerRequest === request) &&
                        consent.matches(currentUploadConfig(request, configured), latestContext)

                var output: UploadVideoReview.Ready? = null
                try {
                    if (!isCurrent(viewModel.currentConfig())) return@launch
                    output = UploadVideoReview.Ready(viewModel.compressVideo(context.applicationContext, request.source, config.sizeLimitBytes))
                    if (!isCurrent(viewModel.currentConfig())) return@launch
                    compression = output
                    output = null
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (compressionConsent === consent) compression = UploadVideoReview.Failed
                } finally {
                    output?.close()
                    if (compressionConsent === consent && compression == UploadVideoReview.Working) {
                        compressionConsent = null
                        compression = null
                    }
                }
            }
    }

    suspend fun startUpload(
        request: AttachmentFlow.Confirm,
        disclosedConfig: PasteBackendConfig,
        uploadContext: AttachmentUploadContext,
        allowOriginalMetadata: Boolean = false,
        retry: AttachmentAttempt? = null,
        compressed: UploadVideoReview.Ready? = null,
    ) {
        // The presentation flow can lag a completed preferences write.
        val configured =
            try {
                viewModel.currentConfig()
            } catch (failure: IOException) {
                if (compressed == null) throw failure
                if (compression === compressed) {
                    if (retry != null && viewModel.attempt.value !== retry) return
                    flow = request
                    uploadingCompressed = false
                    viewModel.fail(latestResources.getString(R.string.upload_compression_settings_unavailable))
                }
                return
            }
        val requestStillCurrent = if (retry == null) flow === request else viewModel.attempt.value === retry
        if (!requestStillCurrent) return
        val binding = compressionConsent
        if (compressed != null && (compression !== compressed || binding?.request !== request)) return
        val uploadSource = compressed?.video?.source ?: request.source
        val review = (request.source as? AttachmentSource.LocalFile)?.reviewedImage
        if (review != null && viewModel.reviewedImage(reviewSession) !== review) return
        val currentContext = latestContext
        val config = currentUploadConfig(request, configured)
        val destinationChanged =
            !sameUploadConsent(disclosedConfig, config) ||
                (request.source !is AttachmentSource.Text && disclosedConfig.stripsHostedUploadMetadata(request.source) && !config.stripsHostedUploadMetadata(request.source)) ||
                (
                    config.backend == AttachmentBackend.SOJU_FILEHOST &&
                        (uploadContext != currentContext || currentContext.expectedSojuFileHostUrl == null)
                )
        if (compressed == null && request.needsVideoSizeCheck(config.sizeLimitBytes)) return
        if (destinationChanged || uploadSource.sizeOrNull()?.let { it > config.sizeLimitBytes } == true ||
            (compressed != null && binding?.matches(config, currentContext) != true)
        ) {
            if (compressed != null) clearCompression()
            viewModel.clearAttempt()
            flow = request
            return
        }
        uploadingCompressed = compressed != null
        flow = AttachmentFlow.Idle
        val completeUpload = prepareUploadCompletion?.invoke((request.source as? AttachmentSource.Text)?.text.takeIf { request.replaceDraft })
        viewModel.upload(
            uploadSource,
            disclosedConfig.copy(
                sizeLimitBytes = config.sizeLimitBytes,
                stripMetadata = config.stripMetadata,
                stripHostedImageMetadata = config.stripHostedImageMetadata,
            ),
            uploadContext.copy(allowOriginalMetadata = allowOriginalMetadata),
            replaceDraft = request.replaceDraft,
            canStart =
                if (compressed != null && binding != null) {
                    { settings ->
                        val stillSelected = compression === compressed && flow === AttachmentFlow.Idle
                        val valid = stillSelected && binding.matches(currentUploadConfig(request, settings), latestContext)
                        if (!valid && stillSelected) {
                            clearCompression()
                            flow = request
                        }
                        valid
                    }
                } else {
                    null
                },
            videoReview = compressed,
        ) { record ->
            if (completeUpload != null) {
                completeUpload(record)
            } else if (request.replaceDraft) {
                onReplaceDraft(record.url)
            } else {
                onInsertUrl(record.url)
            }
            onUploaded?.invoke(record)
            viewModel.replaceReviewedImage(reviewSession)
            if (compressed != null) clearCompression()
            onDismiss()
        }
    }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            if (it == null) flow = AttachmentFlow.Sources else select(it, false)
        }
    val directFilePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            if (it == null) {
                flow = AttachmentFlow.Sources
            } else {
                onDirectFile(it)
                closeSourceSheet()
            }
        }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            it?.let { uri -> select(uri, true) }
        }
    val takePicture =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val file = cameraCaptureFile(context, captureName)
            if (!ok || file == null || file.length() <= 0L) {
                clearCameraCapture()
                flow = AttachmentFlow.Photos
                return@rememberLauncherForActivityResult
            }
            captureReady = true
            val source = AttachmentSource.LocalFile(file, file.name, "image/jpeg")
            flow = AttachmentFlow.Prepare(source, camera = true)
        }

    fun launchCamera() {
        flow = AttachmentFlow.Idle
        try {
            clearCameraCapture()
            val directory = File(context.cacheDir, "camera").also { it.mkdirs() }
            val file = File.createTempFile("capture-", ".jpg", directory)
            captureName = file.name
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
            takePicture.launch(uri)
        } catch (_: Exception) {
            clearCameraCapture()
            flow = AttachmentFlow.Photos
            viewModel.fail(cameraStartError)
        }
    }

    val cameraPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            cameraPermissionGranted = granted
            if (granted) launchCamera()
        }
    val photoPermissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            photoAccessGranted = context.hasPhotoAccess()
        }

    when (val current = flow) {
        AttachmentFlow.Idle -> {}

        AttachmentFlow.Sources -> {
            SourceSheet(
                currentDraft = currentDraft,
                recent = recent,
                imageOnly = imageOnly,
                onDismiss = ::closeSourceSheet,
                onPhoto = { flow = AttachmentFlow.Photos },
                onFile = {
                    flow = AttachmentFlow.Idle
                    filePicker.launch(arrayOf(if (imageOnly) "image/*" else "*/*"))
                },
                directFileTransferAvailable = directFileTransferAvailable,
                onDirectFile = {
                    flow = AttachmentFlow.Idle
                    directFilePicker.launch(arrayOf("*/*"))
                },
                onCurrentDraft = {
                    flow = AttachmentFlow.Confirm(AttachmentSource.Text(currentDraft), true)
                },
                onNewText = {
                    pasteText = ""
                    flow = AttachmentFlow.EditText
                },
                onInsertRecent = { record ->
                    onInsertUrl(record.url)
                    closeSourceSheet()
                },
                onCopyRecent = { record ->
                    context
                        .getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(record.displayName, record.url))
                },
                onDeleteRecent = { deleteTarget = it },
                onFavoriteRecent = { record, favorite ->
                    viewModel.setUploadFavorite(record.url, favorite, context.getString(R.string.upload_library_favorite_failed))
                },
                onClearHistory = viewModel::clearHistory,
            )
        }

        AttachmentFlow.Photos -> {
            PhotoPickerSheet(
                photos = galleryPhotos,
                cameraPermissionGranted = cameraPermissionGranted,
                photoAccessGranted = photoAccessGranted,
                onCamera = {
                    if (cameraPermissionGranted) {
                        launchCamera()
                    } else {
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }
                },
                onRequestPhotoAccess = { photoPermissions.launch(photoReadPermissions()) },
                onBrowse = {
                    photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onPhoto = { photo ->
                    val source = AttachmentSource.Photo(photo.uri, photo.name, photo.mimeType, photo.size)
                    flow = AttachmentFlow.Prepare(source)
                },
                onDismiss = { flow = AttachmentFlow.Sources },
            )
        }

        AttachmentFlow.EditText -> {
            TextPasteSheet(
                text = pasteText,
                onTextChange = { pasteText = it },
                onDismiss = ::closeSourceSheet,
                onContinue = {
                    flow = AttachmentFlow.Confirm(AttachmentSource.Text(pasteText), false)
                },
            )
        }

        is AttachmentFlow.Prepare -> {
            AlertDialog(
                onDismissRequest = ::closeSourceSheet,
                title = { Text(stringResource(R.string.upload_confirm_title)) },
                text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
                confirmButton = {
                    TextButton(onClick = ::closeSourceSheet) { Text(stringResource(R.string.action_cancel)) }
                },
            )
        }

        is AttachmentFlow.Confirm -> {
            val disclosedConfig = uploadConfig(current)
            val shownCompression =
                compression.takeIf {
                    val consent = compressionConsent
                    consent != null && consent.request === current && consent.matches(disclosedConfig, disclosedContext)
                }
            ConfirmationSheet(
                source = current.source,
                config = disclosedConfig,
                sojuFileHostUrl = sojuFileHostUrl,
                onChangeDestination = {
                    if (flow === current) {
                        backendPickerRequest = current
                        flow = AttachmentFlow.Idle
                    }
                },
                compression = shownCompression,
                onCompress = { compressVideo(current, disclosedConfig, disclosedContext) },
                originalSizeIsLowerBound = current.originalSizeIsLowerBound,
                checkingVideoSize = current.needsVideoSizeCheck(disclosedConfig.sizeLimitBytes),
                onKeepOriginal = { if (flow === current && compression === shownCompression) clearCompression() },
                onDismiss = ::closeSourceSheet,
                onUpload = {
                    consentScope.launch {
                        startUpload(current, disclosedConfig, disclosedContext, compressed = shownCompression as? UploadVideoReview.Ready)
                    }
                },
                onStripMetadataChange = { viewModel.setStripMetadata(current.source, it) },
            )
        }
    }

    backendPickerRequest?.let { request ->
        BackendPickerSheet(
            source = request.source,
            config = uploadConfig(request),
            sojuFileHostAvailable = sojuFileHostAvailable,
            onSelect = { selected ->
                clearCompression()
                flow = request.copy(backend = selected.backend)
                backendPickerRequest = null
            },
            onDismiss = {
                flow = request
                backendPickerRequest = null
            },
        )
    }

    progress?.let { update ->
        UploadProgressSheet(
            progress = update,
            onCancel = {
                val request =
                    lastAttempt ?: compressionConsent?.request
                        ?: viewModel.reviewedImage(reviewSession)?.let { AttachmentFlow.Confirm(it.source, false) }
                viewModel.cancel()
                uploadingCompressed = false
                request?.let { flow = it }
            },
        )
    }

    error?.let { failure ->
        AlertDialog(
            onDismissRequest = {
                viewModel.clearError()
                lastAttempt?.let { flow = it }
                viewModel.clearAttempt(attempt)
            },
            icon = { Icon(Icons.Outlined.CloudUpload, null) },
            title = {
                Text(
                    if (failure.metadataNotSupported) {
                        stringResource(R.string.upload_metadata_warning_title)
                    } else {
                        stringResource(R.string.upload_failed)
                    },
                )
            },
            text = {
                Column {
                    if (failure.metadataNotSupported) {
                        val message =
                            if (attempt?.source === readyVideo?.source) R.string.upload_compression_metadata_warning else R.string.upload_metadata_warning_body
                        Text(stringResource(message))
                    } else {
                        Text(failure.message)
                    }
                    failure.offHostNetworkId?.let { id ->
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.upload_soju_off_host_override))
                        TextButton(
                            colors = readableTextButtonColors(),
                            onClick = {
                                viewModel.clearError()
                                closeSourceSheet()
                                onOpenNetworkSettings(id)
                            },
                        ) { Text(stringResource(R.string.upload_soju_open_network_settings)) }
                    }
                }
            },
            confirmButton = {
                lastAttempt?.let { request ->
                    TextButton(colors = readableTextButtonColors(), onClick = {
                        val retry = attempt ?: return@TextButton
                        if (viewModel.attempt.value !== retry || viewModel.error.value !== failure) return@TextButton
                        viewModel.clearError()
                        consentScope.launch {
                            startUpload(
                                request,
                                disclosedConfig = retry.config,
                                uploadContext = retry.context,
                                allowOriginalMetadata = failure.metadataNotSupported,
                                retry = retry,
                                compressed =
                                    (compression as? UploadVideoReview.Ready)?.takeIf { it.video.source === retry.source },
                            )
                        }
                    }) {
                        if (!failure.metadataNotSupported) {
                            Icon(Icons.Outlined.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            stringResource(
                                when {
                                    failure.metadataNotSupported && attempt?.source === readyVideo?.source -> R.string.upload_compression_send_unstripped
                                    failure.metadataNotSupported -> R.string.upload_send_original
                                    else -> R.string.upload_retry
                                },
                            ),
                        )
                    }
                } ?: TextButton(colors = readableTextButtonColors(), onClick = viewModel::clearError) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton =
                if (lastAttempt != null) {
                    {
                        TextButton(colors = readableTextButtonColors(), onClick = {
                            viewModel.clearError()
                            flow = lastAttempt
                            viewModel.clearAttempt(attempt)
                        }) {
                            Text(stringResource(if (failure.metadataNotSupported) R.string.action_cancel else R.string.action_back))
                        }
                    }
                } else {
                    null
                },
        )
    }

    if (contentUnavailable) {
        AlertDialog(
            onDismissRequest = ::closeSourceSheet,
            title = { Text(stringResource(R.string.upload_failed)) },
            text = { Text(stringResource(R.string.upload_content_unavailable)) },
            confirmButton = {
                TextButton(colors = readableTextButtonColors(), onClick = ::closeSourceSheet) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    deleteTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text(stringResource(R.string.upload_delete_title)) },
            text = { Text(stringResource(R.string.upload_delete_body, record.displayName)) },
            confirmButton = {
                Button(onClick = {
                    viewModel.delete(record)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = { TextButton(colors = readableTextButtonColors(), onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun AttachmentBatchSheets(
    batchId: String,
    bufferId: Long,
    files: List<PendingShare.File>,
    networkId: Long?,
    sojuFileHostUrl: String?,
    preferSojuFileHost: Boolean = false,
    canAddLinks: Boolean = true,
    onFinished: suspend (List<String>) -> Boolean,
    viewModel: AttachmentViewModel = hiltViewModel(),
) {
    val context = LocalContext.current.applicationContext
    val resolver = context.contentResolver
    val target = AttachmentBatchTarget(networkId, sojuFileHostUrl, preferSojuFileHost)
    val scheduled = viewModel.batchRequest.value
    if (scheduled?.id != batchId || scheduled.bufferId != bufferId || scheduled.files != files) return
    val review by viewModel.batchReview.collectAsStateWithLifecycle()
    val singleProgress by viewModel.progress.collectAsStateWithLifecycle()
    var picking by rememberSaveable(batchId) { mutableStateOf(false) }
    SideEffect { viewModel.updateBatchTarget(batchId, target) }
    LaunchedEffect(viewModel, batchId, resolver) { viewModel.reviewBatch(batchId, target, resolver) }
    LaunchedEffect(viewModel, batchId) { viewModel.watchBatchCompressionDestination(batchId) }
    DisposableEffect(viewModel, batchId) {
        onDispose { viewModel.leaveBatchCompression(batchId) }
    }
    val shown = review?.takeIf { it.request.id == batchId } ?: return
    if (picking && !shown.started && shown.settings != null) {
        BackendPickerSheet(
            source = shown.request.binarySource,
            config = shown.settings,
            selected = shown.destination?.config ?: shown.settings,
            sojuFileHostAvailable = shown.target.sojuFileHostUrl != null,
            onSelect = {
                viewModel.selectBatchBackend(batchId, it.backend)
                picking = false
            },
            onDismiss = { picking = false },
        )
    } else {
        AttachmentBatchReviewSheet(
            review = shown,
            singleBusy = singleProgress != null,
            canAddLinks = canAddLinks,
            onDestinationChange = { picking = true },
            onReviewChangedDestination = { viewModel.reviewChangedDestination(shown) },
            onUpload = { viewModel.uploadBatch(shown, context) },
            onRetry = { index, allowOriginal -> viewModel.retryBatch(shown, index, context, allowOriginal) },
            onCompress = { viewModel.compressBatchVideo(shown, it, context) },
            onKeepOriginal = { viewModel.keepBatchOriginal(shown, it) },
            onStop = { viewModel.stopBatch(batchId) },
            onFinish = { addLinks -> viewModel.finishBatch(batchId, addLinks, onFinished) },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentBatchReviewSheet(
    review: AttachmentBatchReview,
    singleBusy: Boolean,
    canAddLinks: Boolean,
    onDestinationChange: () -> Unit,
    onReviewChangedDestination: () -> Unit,
    onUpload: () -> Unit,
    onRetry: (Int, Boolean) -> Unit,
    onCompress: (Int) -> Unit,
    onKeepOriginal: (Int) -> Unit,
    onStop: () -> Unit,
    onFinish: (Boolean) -> Unit,
) {
    val destination = review.destination
    val hasUploads = review.hasUploads
    val preventDismiss by rememberUpdatedState(review.started || review.busy || hasUploads)
    val canUpload = destination != null && review.rows.any { it.status == AttachmentBatchStatus.Ready && it.isUploadable(destination) }
    val compressing = review.rows.any { it.compression == UploadVideoReview.Working }
    val uploadingCompressed =
        destination != null && review.rows.any { it.compressedSource != null && it.status == AttachmentBatchStatus.Ready && it.isUploadable(destination) }
    ModalBottomSheet(
        onDismissRequest = { if (!preventDismiss) onFinish(false) },
        sheetState =
            rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || !preventDismiss },
            ),
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("attachment-batch-review"),
    ) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.upload_batch_review), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.upload_batch_consent))
                if (destination != null) {
                    val config = destination.config
                    Text(stringResource(R.string.upload_destination), style = MaterialTheme.typography.titleSmall)
                    Text(config.backend.label, fontWeight = FontWeight.SemiBold)
                    if (destination.endpoint != null) {
                        Text(destination.endpoint)
                        Text(stringResource(R.string.upload_batch_size_limit, formatBytes(config.sizeLimitBytes)))
                        // x0.at ignores secret-link requests.
                        UploadPrivacyCard(if (config.backend == AttachmentBackend.X0_AT) config.copy(secretUrl = false) else config)
                        val stripImages = config.stripHostedImageMetadata == true
                        Text(stringResource(R.string.upload_batch_image_policy, batchMetadataPolicy(stripImages)))
                        Text(stringResource(R.string.upload_batch_other_policy, batchMetadataPolicy(config.stripMetadata)))
                        if (stripImages != config.stripMetadata) Text(stringResource(R.string.upload_batch_unknown_policy))
                    } else {
                        val message = if (config.backend == AttachmentBackend.SOJU_FILEHOST) R.string.upload_soju_unavailable else R.string.upload_custom_unavailable
                        Text(stringResource(message), color = MaterialTheme.colorScheme.error)
                    }
                    if (!review.started) {
                        TextButton(
                            onClick = onDestinationChange,
                            enabled = !review.busy,
                            modifier = Modifier.testTag("attachment-batch-change-destination"),
                        ) {
                            Text(stringResource(R.string.upload_destination_change))
                        }
                    }
                } else if (!review.busy) {
                    Text(stringResource(R.string.upload_batch_settings_unavailable), color = MaterialTheme.colorScheme.error)
                }
                review.rows.forEachIndexed { index, row ->
                    HorizontalDivider()
                    BatchReviewRow(
                        index = index,
                        row = row,
                        destination = destination,
                        started = review.started,
                        enabled = !review.busy && !singleBusy && !review.destinationChanged,
                        onAction = { allowOriginal -> onRetry(index, allowOriginal) },
                        onCompress = { onCompress(index) },
                        onKeepOriginal = { onKeepOriginal(index) },
                    )
                }
            }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                when {
                    review.finishing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(stringResource(R.string.upload_batch_saving_links))
                    }

                    review.started && review.busy && !review.destinationChanged && !compressing -> {
                        TextButton(
                            onClick = onStop,
                            enabled = !review.stopping,
                            modifier = Modifier.fillMaxWidth().testTag("attachment-batch-stop"),
                        ) {
                            Text(stringResource(if (review.stopping) R.string.upload_batch_stopping else R.string.upload_batch_stop))
                        }
                    }

                    review.destinationChanged && !review.busy -> {
                        Button(
                            onClick = onReviewChangedDestination,
                            modifier = Modifier.fillMaxWidth().testTag("attachment-batch-review-destination"),
                        ) {
                            Text(stringResource(R.string.upload_batch_review_destination))
                        }
                    }

                    !review.started -> {
                        Button(
                            onClick = onUpload,
                            enabled = !review.busy && !singleBusy && canUpload,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("attachment-batch-upload"),
                        ) {
                            Icon(Icons.Outlined.CloudUpload, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(
                                    when {
                                        uploadingCompressed -> R.string.upload_compression_batch_upload
                                        hasUploads -> R.string.upload_batch_upload_remaining
                                        else -> R.string.upload_batch_upload
                                    },
                                ),
                            )
                        }
                    }
                }
                if (!review.busy && review.finishError) {
                    Text(stringResource(R.string.upload_batch_draft_save_failed), color = MaterialTheme.colorScheme.error)
                }
                if (!review.busy && hasUploads) {
                    Button(
                        onClick = { onFinish(true) },
                        enabled = canAddLinks,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("attachment-batch-add-links"),
                    ) {
                        Icon(Icons.Outlined.InsertLink, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.upload_batch_add_links))
                    }
                }
                if (!review.finishing && (!review.started || !review.busy)) {
                    val closeLabel =
                        when {
                            hasUploads -> R.string.upload_batch_close_without_adding
                            review.started -> R.string.upload_batch_done
                            else -> R.string.action_cancel
                        }
                    TextButton(
                        onClick = { onFinish(false) },
                        modifier = Modifier.fillMaxWidth().testTag("attachment-batch-cancel"),
                    ) { Text(stringResource(closeLabel)) }
                }
            }
        }
    }
}

@Composable
private fun batchMetadataPolicy(strip: Boolean): String = stringResource(if (strip) R.string.upload_batch_policy_strip else R.string.upload_batch_policy_original)

@Composable
private fun BatchReviewRow(
    index: Int,
    row: AttachmentBatchRow,
    destination: AttachmentBatchDestination?,
    started: Boolean,
    enabled: Boolean,
    onAction: (Boolean) -> Unit,
    onCompress: () -> Unit,
    onKeepOriginal: () -> Unit,
) {
    val name = row.name.ifBlank { stringResource(R.string.upload_type_unknown) }
    val config = destination?.config
    val size = row.source?.size
    val uploadable = destination != null && row.isUploadable(destination)
    val oversized = destination != null && row.isOversized(destination)
    val needsCheck = row.needsCheck(destination)
    Column(Modifier.fillMaxWidth().testTag("attachment-batch-row-$index")) {
        Text(stringResource(R.string.upload_batch_item, index + 1, name), fontWeight = FontWeight.SemiBold)
        if (row.source != null) {
            val mime = row.source.mimeType ?: stringResource(R.string.upload_type_unknown)
            val sizeLabel = size?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown)
            Text(stringResource(R.string.upload_metadata, mime, sizeLabel))
        }
        if (oversized && size != null && config != null && row.compressedSource == null && row.status !is AttachmentBatchStatus.Uploaded) {
            val sizeLabel = formatBytes(size)
            val limitLabel = formatBytes(config.sizeLimitBytes)
            val exact = sizeLabel == limitLabel
            Text(
                stringResource(R.string.upload_over_size_limit, if (exact) "$size B" else sizeLabel, if (exact) "${config.sizeLimitBytes} B" else limitLabel),
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (destination != null && size != null && (row.compression != null || row.canCompress(destination))) {
            VideoCompressionControls(
                originalBytes = size,
                limitBytes = destination.config.sizeLimitBytes,
                review = row.compression,
                enabled = enabled,
                tags = {
                    if (it == "compress") "attachment-batch-compress-to-fit-$index" else "attachment-batch-compression-$it-$index"
                },
                onCompress = onCompress,
                onKeepOriginal = onKeepOriginal,
                originalSizeIsLowerBound = row.originalSizeIsLowerBound,
            )
        }
        when (val status = row.status) {
            AttachmentBatchStatus.Checking -> {
                Text(stringResource(R.string.upload_batch_checking))
            }

            AttachmentBatchStatus.Ready -> {
                if (!needsCheck) {
                    Text(stringResource(R.string.upload_batch_ready))
                    if (started && uploadable) {
                        TextButton(onClick = { onAction(false) }, enabled = enabled, modifier = Modifier.testTag("attachment-batch-upload-$index")) {
                            Text(stringResource(if (row.compressedSource != null) R.string.upload_compression_upload else R.string.upload_action))
                        }
                    }
                }
            }

            is AttachmentBatchStatus.Uploading -> {
                val progress = status.progress
                val total = progress.totalBytes
                if (total != null && total > 0) {
                    LinearProgressIndicator(progress = { (progress.bytesSent.toDouble() / total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.upload_progress_of, formatBytes(progress.bytesSent), formatBytes(total)))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.upload_uploading))
                }
            }

            is AttachmentBatchStatus.Uploaded -> {
                Text(stringResource(R.string.upload_batch_uploaded))
            }

            is AttachmentBatchStatus.Failed -> {
                val error =
                    if (status.needsOriginalConsent) {
                        stringResource(if (row.compressedSource != null) R.string.upload_compression_metadata_warning else R.string.upload_metadata_warning_body)
                    } else {
                        status.details ?: stringResource(status.reason)
                    }
                Text(error, color = MaterialTheme.colorScheme.error)
                if (started && uploadable) {
                    val label =
                        when {
                            status.needsOriginalConsent && row.compressedSource != null -> R.string.upload_compression_send_unstripped
                            status.needsOriginalConsent -> R.string.upload_send_original
                            status.reason == R.string.upload_batch_file_changed -> R.string.upload_action
                            else -> R.string.upload_retry
                        }
                    val action = if (status.needsOriginalConsent) "send-original" else "retry"
                    TextButton(
                        onClick = { onAction(status.needsOriginalConsent) },
                        enabled = enabled,
                        modifier = Modifier.testTag("attachment-batch-$action-$index"),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        if (needsCheck && row.compression != UploadVideoReview.Working &&
            (row.status is AttachmentBatchStatus.Ready || row.status is AttachmentBatchStatus.Failed)
        ) {
            TextButton(
                onClick = { onAction(false) },
                enabled = enabled,
                modifier = Modifier.testTag("attachment-batch-retry-$index"),
            ) { Text(stringResource(R.string.upload_batch_check_again)) }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun PhotoPickerSheet(
    photos: List<GalleryPhoto>,
    cameraPermissionGranted: Boolean,
    photoAccessGranted: Boolean,
    onCamera: () -> Unit,
    onRequestPhotoAccess: () -> Unit,
    onBrowse: () -> Unit,
    onPhoto: (GalleryPhoto) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("attachment_photo_sheet"),
    ) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 700.dp).padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.upload_photos_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "camera") {
                    CameraPreviewTile(cameraPermissionGranted, onCamera)
                }
                if (!photoAccessGranted) {
                    item(key = "permission") {
                        GalleryActionTile(
                            icon = Icons.Outlined.Image,
                            label = stringResource(R.string.upload_photos_allow),
                            tag = "attachment_photos_permission",
                            onClick = onRequestPhotoAccess,
                        )
                    }
                }
                item(key = "browse") {
                    GalleryActionTile(
                        icon = Icons.Filled.MoreVert,
                        label = stringResource(R.string.upload_photos_browse),
                        tag = "attachment_photos_browse",
                        onClick = onBrowse,
                    )
                }
                items(photos, key = { it.uri.toString() }) { photo ->
                    AsyncImage(
                        model = photo.uri,
                        contentDescription = photo.name,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onPhoto(photo) }
                                .testTag("attachment_photo_${photo.uri}"),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun GalleryActionTile(
    icon: ImageVector,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .clickable(onClick = onClick)
                .testTag(tag),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary, minimumContrast = 3.0))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun CameraPreviewTile(
    permissionGranted: Boolean,
    onClick: () -> Unit,
) {
    var previewFailed by remember(permissionGranted) { mutableStateOf(false) }
    val label = stringResource(R.string.upload_camera)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clickable(onClick = onClick)
                .testTag("attachment_camera_tile"),
        contentAlignment = Alignment.Center,
    ) {
        if (permissionGranted && !previewFailed) {
            LiveCameraPreview(onError = { previewFailed = true }, modifier = Modifier.matchParentSize())
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
                    .padding(6.dp),
            ) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = label, tint = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), minimumContrast = 3.0))
            }
        } else {
            Icon(
                Icons.Outlined.PhotoCamera,
                contentDescription = label,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary, minimumContrast = 3.0),
            )
        }
    }
}

@Composable
private fun LiveCameraPreview(
    onError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember(context) {
            PreviewView(context).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
        }

    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(previewView, lifecycleOwner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        val future = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        future.addListener(
            {
                runCatching {
                    val cameraProvider = future.get()
                    if (disposed) return@addListener
                    val useCase = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCase)
                    provider = cameraProvider
                    preview = useCase
                }.onFailure { onError() }
            },
            executor,
        )
        onDispose {
            disposed = true
            preview?.let { provider?.unbind(it) }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(
    currentDraft: String,
    recent: List<UploadRecord>,
    imageOnly: Boolean,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    directFileTransferAvailable: Boolean,
    onDirectFile: () -> Unit,
    onCurrentDraft: () -> Unit,
    onNewText: () -> Unit,
    onInsertRecent: (UploadRecord) -> Unit,
    onCopyRecent: (UploadRecord) -> Unit,
    onDeleteRecent: (UploadRecord) -> Unit,
    onFavoriteRecent: (UploadRecord, Boolean) -> Unit,
    onClearHistory: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val displayedUploads =
        remember(recent, query, favoritesOnly, imageOnly) {
            if (imageOnly) {
                recent.filter { it.mimeType?.startsWith("image/") == true }
            } else {
                val term = UCharacter.foldCase(query.trim(), true)
                if (term.isEmpty() && !favoritesOnly) {
                    recent
                } else {
                    recent.filter { record ->
                        if (favoritesOnly && !record.favorite) return@filter false
                        term.isEmpty() ||
                            UCharacter.foldCase(record.displayName, true).contains(term) ||
                            UCharacter.foldCase(record.url, true).contains(term)
                    }
                }
            }
        }
    val canClearRecents = remember(recent) { recent.any { !it.favorite } }
    var confirmClearHistory by remember { mutableStateOf(false) }
    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text(stringResource(R.string.upload_clear_recents_title)) },
            text = { Text(stringResource(R.string.upload_clear_recents_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearHistory()
                        confirmClearHistory = false
                    },
                    modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("attachment_clear_history_confirm"),
                ) {
                    Text(stringResource(R.string.upload_clear_recents))
                }
            },
            dismissButton = {
                TextButton(colors = readableTextButtonColors(), onClick = { confirmClearHistory = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !imageOnly),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("attachment_source_sheet"),
    ) {
        SheetSystemBars()
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag("attachment_source_list"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Column {
                    Text(stringResource(R.string.upload_add_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary))
                    Text(stringResource(R.string.upload_add_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SourceCard(Icons.Outlined.Image, stringResource(R.string.upload_photo), stringResource(R.string.upload_photo_desc), true, onPhoto, Modifier.weight(1f))
                        SourceCard(
                            Icons.Outlined.AttachFile,
                            stringResource(if (imageOnly) R.string.avatar_image_file else R.string.upload_file),
                            stringResource(if (imageOnly) R.string.avatar_image_file_desc else R.string.upload_file_desc),
                            true,
                            onFile,
                            Modifier.weight(1f),
                        )
                    }
                    if (directFileTransferAvailable && !imageOnly) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SourceCard(
                                Icons.Outlined.Lock,
                                stringResource(R.string.dcc_send_file),
                                stringResource(R.string.dcc_send_file_desc),
                                true,
                                onDirectFile,
                                Modifier.weight(1f),
                            )
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    if (!imageOnly) {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            SourceCard(Icons.Outlined.Description, stringResource(R.string.upload_draft), stringResource(R.string.upload_draft_desc), currentDraft.isNotBlank(), onCurrentDraft, Modifier.weight(1f))
                            SourceCard(Icons.Outlined.Edit, stringResource(R.string.upload_text), stringResource(R.string.upload_text_desc), true, onNewText, Modifier.weight(1f))
                        }
                    }
                }
            }
            if (!imageOnly || displayedUploads.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(if (imageOnly) Icons.Outlined.History else Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            stringResource(if (imageOnly) R.string.upload_recent else R.string.upload_library),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier =
                                Modifier
                                    .semantics { heading() }
                                    .then(if (imageOnly) Modifier else Modifier.testTag("attachment_upload_library")),
                        )
                    }
                }
            }
            if (!imageOnly) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth().testTag("attachment_upload_search"),
                        label = { Text(stringResource(R.string.upload_library_search)) },
                        singleLine = true,
                    )
                }
            }
            if (!imageOnly || displayedUploads.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!imageOnly) {
                            FilterChip(
                                selected = favoritesOnly,
                                onClick = { favoritesOnly = !favoritesOnly },
                                label = { Text(stringResource(R.string.upload_library_favorites)) },
                                modifier = Modifier.heightIn(min = 48.dp).testTag("attachment_upload_favorites"),
                            )
                        }
                        TextButton(
                            colors = readableTextButtonColors(),
                            enabled = canClearRecents,
                            onClick = { confirmClearHistory = true },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("attachment_clear_history"),
                        ) {
                            Text(stringResource(R.string.upload_clear_recents))
                        }
                    }
                }
                if (!imageOnly && displayedUploads.isEmpty()) {
                    item {
                        Text(
                            stringResource(if (recent.isEmpty()) R.string.upload_library_empty else R.string.upload_library_no_results),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp).testTag("attachment_upload_empty"),
                        )
                    }
                }
            }
            items(displayedUploads, key = { it.url }) { record ->
                RecentUploadRow(
                    record = record,
                    insertLabel = stringResource(if (imageOnly) R.string.avatar_url_save else R.string.upload_insert_link),
                    onInsert = onInsertRecent,
                    onCopy = onCopyRecent,
                    onDelete = onDeleteRecent,
                    onFavorite = if (imageOnly) null else onFavoriteRecent,
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SourceCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 108.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary, minimumContrast = 3.0) else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecentUploadRow(
    record: UploadRecord,
    insertLabel: String,
    onInsert: (UploadRecord) -> Unit,
    onCopy: (UploadRecord) -> Unit,
    onDelete: (UploadRecord) -> Unit,
    onFavorite: ((UploadRecord, Boolean) -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        colors =
            androidx.compose.material3.ListItemDefaults
                .colors(containerColor = Color.Transparent),
        headlineContent = { Text(record.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                MetadataPill(
                    stringResource(
                        R.string.upload_metadata,
                        record.mimeType ?: stringResource(R.string.upload_type_unknown),
                        record.sizeBytes?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown),
                    ),
                )
                Text(record.backend.label, style = MaterialTheme.typography.labelMedium)
                record.preview?.durationMillis?.let { duration ->
                    Text(
                        formatAudioDuration(duration),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.testTag("attachment_recent_duration"),
                    )
                }
                record.preview?.textSnippet?.let { snippet ->
                    androidx.compose.material3.Text(
                        text = snippet,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("attachment_recent_text"),
                    )
                }
            }
        },
        leadingContent = { UploadRecordPreview(record, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onFavorite != null) {
                    val favoriteLabel = stringResource(R.string.upload_library_favorite, record.displayName)
                    IconToggleButton(
                        checked = record.favorite,
                        onCheckedChange = { onFavorite(record, it) },
                        modifier =
                            Modifier
                                .size(48.dp)
                                .semantics { contentDescription = favoriteLabel }
                                .testTag("attachment_recent_favorite"),
                    ) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            Icon(if (record.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null)
                            if (record.favorite) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier =
                                        Modifier
                                            .align(Alignment.BottomEnd)
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp).testTag("attachment_recent_more")) {
                        Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more))
                    }
                    DropdownMenu(menuOpen, { menuOpen = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        DropdownMenuItem({ Text(insertLabel) }, {
                            menuOpen = false
                            onInsert(record)
                        }, leadingIcon = { Icon(Icons.Outlined.InsertLink, null) })
                        DropdownMenuItem({ Text(stringResource(R.string.upload_copy_link)) }, {
                            menuOpen = false
                            onCopy(record)
                        }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                        if (record.deletionToken != null) {
                            DropdownMenuItem({ Text(stringResource(R.string.action_delete)) }, {
                                menuOpen = false
                                onDelete(record)
                            }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                        }
                    }
                }
            }
        },
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = insertLabel) { onInsert(record) }.testTag("attachment_recent_row"),
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TextPasteSheet(
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.upload_text_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                placeholder = { Text(stringResource(R.string.upload_text_placeholder)) },
                supportingText = { Text(pluralStringResource(R.plurals.upload_character_count, text.length, text.length)) },
                shape = RoundedCornerShape(20.dp),
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onContinue, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.action_continue))
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ConfirmationSheet(
    source: AttachmentSource,
    config: PasteBackendConfig,
    sojuFileHostUrl: String?,
    onChangeDestination: () -> Unit,
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
    onStripMetadataChange: (Boolean) -> Unit = {},
    compression: UploadVideoReview? = null,
    onCompress: (() -> Unit)? = null,
    onKeepOriginal: (() -> Unit)? = null,
    originalSizeIsLowerBound: Boolean = false,
    checkingVideoSize: Boolean = false,
) {
    val sourceUri =
        when (source) {
            is AttachmentSource.Photo -> source.uri
            is AttachmentSource.Document -> source.uri
            else -> null
        }
    val unsupportedUri = sourceUri != null && !sourceUri.isHostedAttachmentUri()
    val preview = (source as? AttachmentSource.LocalFile)?.reviewedImage?.let { Uri.fromFile(it.file) }
    val isUnreviewedImage =
        source is AttachmentSource.Photo ||
            (source is AttachmentSource.Document && source.mimeType?.startsWith("image/", ignoreCase = true) == true) ||
            (source is AttachmentSource.LocalFile && source.mimeType.startsWith("image/", ignoreCase = true) && preview == null)
    var previewFailed by remember(source) { mutableStateOf(false) }
    var reviewing by remember(source) { mutableStateOf(false) }
    val sourceSize = remember(source) { source.sizeOrNull() }
    val limit = remember(config) { normalizedConfig(config).sizeLimitBytes }
    val oversizedBytes = sourceSize?.takeIf { it > limit }
    val sojuUnavailable = config.backend == AttachmentBackend.SOJU_FILEHOST && sojuFileHostUrl == null
    val customUnavailable =
        config.backend == AttachmentBackend.CUSTOM_0X0 &&
            io.github.trevarj.motd.attachment
                .validateEndpoint(config.endpoint) == null
    ModalBottomSheet(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("attachment_confirmation_sheet"),
    ) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.upload_confirm_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.height(12.dp))
                if (unsupportedUri) Text(stringResource(R.string.settings_local_media_required), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("attachment_thumbnail_error"))
                if (preview != null && !previewFailed) {
                    AsyncImage(
                        model = preview,
                        contentDescription = stringResource(R.string.upload_review_image),
                        onError = { previewFailed = true },
                        onSuccess = { previewFailed = false },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable(role = Role.Button) { reviewing = true }
                                .testTag("attachment_thumbnail"),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                if (previewFailed) Text(stringResource(R.string.settings_local_image_preview_failed), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("attachment_thumbnail_error"))
                AttachmentMetadata(source, originalSizeIsLowerBound)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.upload_destination), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ListItem(
                    headlineContent = { Text(config.backend.label, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Column {
                            Text(
                                when {
                                    sojuUnavailable -> stringResource(R.string.upload_soju_unavailable)
                                    customUnavailable -> stringResource(R.string.upload_custom_unavailable)
                                    else -> backendRetention(config)
                                },
                            )
                            val endpoint =
                                when (config.backend) {
                                    AttachmentBackend.SOJU_FILEHOST -> sojuFileHostUrl
                                    AttachmentBackend.CUSTOM_0X0 -> config.endpoint.takeUnless { customUnavailable }
                                    else -> null
                                }
                            endpoint?.let { Text(it) }
                        }
                    },
                    trailingContent = { Text(stringResource(R.string.upload_destination_change), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onChangeDestination).testTag("attachment_change_destination"),
                )
                Spacer(Modifier.height(12.dp))
                if (oversizedBytes != null && onCompress != null && onKeepOriginal != null &&
                    !sojuUnavailable && !customUnavailable && source.canCompressVideo(limit)
                ) {
                    VideoCompressionControls(
                        originalBytes = oversizedBytes,
                        limitBytes = limit,
                        review = compression,
                        enabled = true,
                        tags = {
                            when (it) {
                                "compress" -> "attachment_compress_to_fit"
                                "keep-original" -> "attachment_compression_keep_original"
                                else -> "attachment_compression_$it"
                            }
                        },
                        onCompress = onCompress,
                        onKeepOriginal = onKeepOriginal,
                        originalSizeIsLowerBound = originalSizeIsLowerBound,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                UploadPrivacyCard(config)
                if (source !is AttachmentSource.Text) {
                    val stripMetadata = config.stripsHostedUploadMetadata(source)
                    val image = source.isHostedImage()
                    SwitchRow(
                        title = stringResource(if (image) R.string.upload_strip_image_metadata else R.string.upload_strip_metadata),
                        subtitle =
                            stringResource(
                                when {
                                    image && !stripMetadata -> R.string.upload_strip_image_metadata_off_warning
                                    image -> R.string.upload_strip_image_metadata_description
                                    !stripMetadata -> R.string.upload_strip_metadata_off_warning
                                    else -> R.string.upload_strip_metadata_description
                                },
                            ),
                        checked = stripMetadata,
                        onCheckedChange = onStripMetadataChange,
                        switchTag = "attachment_strip_metadata",
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            val compressedReady = compression is UploadVideoReview.Ready
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (checkingVideoSize) Text(stringResource(R.string.upload_checking_video_size), modifier = Modifier.padding(bottom = 8.dp))
                if (oversizedBytes != null && !compressedReady) {
                    val sizeLabel = formatBytes(oversizedBytes)
                    val limitLabel = formatBytes(limit)
                    val sameRoundedLabels = sizeLabel == limitLabel
                    Text(
                        if (originalSizeIsLowerBound) {
                            stringResource(R.string.upload_over_size_limit_minimum, oversizedBytes, limitLabel)
                        } else {
                            stringResource(
                                R.string.upload_over_size_limit,
                                if (sameRoundedLabels) "$oversizedBytes B" else sizeLabel,
                                if (sameRoundedLabels) "$limit B" else limitLabel,
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 8.dp).testTag("attachment_size_limit_error"),
                    )
                }
                Button(
                    onClick = onUpload,
                    enabled =
                        !checkingVideoSize && !sojuUnavailable && !customUnavailable && !unsupportedUri && !isUnreviewedImage &&
                            (oversizedBytes == null || compressedReady),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("attachment_upload"),
                ) {
                    Icon(Icons.Outlined.CloudUpload, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (compressedReady) R.string.upload_compression_upload else R.string.upload_action))
                }
                TextButton(colors = readableTextButtonColors(), onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    if (reviewing && preview != null) {
        LocalImageReviewDialog(preview, onDismiss = { reviewing = false })
    }
}

@Composable
private fun VideoCompressionControls(
    originalBytes: Long,
    limitBytes: Long,
    review: UploadVideoReview?,
    enabled: Boolean,
    tags: (String) -> String,
    onCompress: () -> Unit,
    onKeepOriginal: () -> Unit,
    originalSizeIsLowerBound: Boolean = false,
) {
    when (review) {
        UploadVideoReview.Working -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.upload_compression_working), modifier = Modifier.testTag(tags("working")))
        }

        is UploadVideoReview.Ready -> {
            val label = if (originalSizeIsLowerBound) R.string.upload_compression_ready_minimum else R.string.upload_compression_ready
            Text(
                stringResource(label, originalBytes, checkNotNull(review.video.source.size), limitBytes),
                modifier = Modifier.testTag(tags("ready")),
            )
        }

        else -> {
            if (review == UploadVideoReview.Failed) {
                Text(
                    stringResource(R.string.upload_compression_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(tags("failed")),
                )
            }
            Text(stringResource(R.string.upload_compression_consent, formatBytes(limitBytes)))
        }
    }
    if (review == UploadVideoReview.Working || review is UploadVideoReview.Ready) {
        val working = review == UploadVideoReview.Working
        TextButton(
            onClick = onKeepOriginal,
            enabled = working || enabled,
            colors = readableTextButtonColors(),
            modifier = Modifier.testTag(tags(if (working) "cancel" else "keep-original")),
        ) {
            Icon(Icons.Filled.Close, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(if (working) R.string.upload_compression_cancel else R.string.upload_compression_keep_original))
        }
    } else {
        TextButton(
            onClick = onCompress,
            enabled = enabled,
            colors = readableTextButtonColors(),
            modifier = Modifier.testTag(tags("compress")),
        ) {
            Icon(Icons.Outlined.ArrowDownward, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.upload_compression_action))
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BackendPickerSheet(
    source: AttachmentSource,
    config: PasteBackendConfig,
    sojuFileHostAvailable: Boolean,
    onSelect: (PasteBackendConfig) -> Unit,
    onDismiss: () -> Unit,
    selected: PasteBackendConfig = config,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("attachment_destination_sheet"),
    ) {
        SheetSystemBars()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.upload_destination), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary))
            Spacer(Modifier.height(8.dp))
            uploadDestinations(source, config, sojuFileHostAvailable).forEach { destination ->
                io.github.trevarj.motd.ui.settings.RadioRow(
                    label = destination.label,
                    subtitle =
                        stringResource(
                            R.string.upload_metadata,
                            backendRetention(destination.config),
                            stringResource(R.string.upload_destination_size_limit, formatBytes(destination.config.sizeLimitBytes)),
                        ),
                    selected = sameDestination(selected, destination.config),
                    enabled = true,
                    onClick = { onSelect(destination.config) },
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)),
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AttachmentMetadata(
    source: AttachmentSource,
    originalSizeIsLowerBound: Boolean,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val image =
                source is AttachmentSource.Photo ||
                    source is AttachmentSource.Document && source.mimeType?.startsWith("image/") == true ||
                    source is AttachmentSource.LocalFile && source.mimeType.startsWith("image/")
            Icon(if (image) Icons.Outlined.Image else Icons.Outlined.Description, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary, minimumContrast = 3.0))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(source.displayName(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                io.github.trevarj.motd.ui.components.MetadataPill(
                    when (source) {
                        is AttachmentSource.Text -> {
                            pluralStringResource(R.plurals.upload_character_count, source.text.length, source.text.length)
                        }

                        is AttachmentSource.Document -> {
                            val size = source.size
                            val label =
                                when {
                                    size == null -> stringResource(R.string.upload_size_unknown)
                                    originalSizeIsLowerBound -> stringResource(R.string.upload_size_minimum, size)
                                    else -> formatBytes(size)
                                }
                            stringResource(R.string.upload_metadata, source.mimeType ?: stringResource(R.string.upload_type_unknown), label)
                        }

                        is AttachmentSource.Photo -> {
                            stringResource(R.string.upload_metadata, source.mimeType ?: stringResource(R.string.upload_type_photo), source.size?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown))
                        }

                        is AttachmentSource.LocalFile -> {
                            stringResource(R.string.upload_metadata, source.mimeType, source.size?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun UploadPrivacyCard(config: PasteBackendConfig) {
    val termbin = config.backend == AttachmentBackend.TERMBIN
    val safe =
        config.backend == AttachmentBackend.CNET ||
            config.backend == AttachmentBackend.SOJU_FILEHOST ||
            (config.protocol == io.github.trevarj.motd.attachment.PasteProtocol.MULTIPART_0X0 && config.secretUrl)
    val semanticColors = LocalMotdSemanticColors.current
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (safe) semanticColors.successContainer else MaterialTheme.colorScheme.errorContainer,
        contentColor = if (safe) semanticColors.onSuccessContainer else MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(if (safe) Icons.Outlined.Lock else Icons.Outlined.Public, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    when {
                        termbin -> stringResource(R.string.upload_termbin_unencrypted)
                        config.backend == AttachmentBackend.SOJU_FILEHOST -> stringResource(R.string.upload_soju_private_title)
                        config.backend == AttachmentBackend.CNET -> "Unguessable, deletable link"
                        safe -> stringResource(R.string.upload_privacy_title)
                        else -> "Public link"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (config.backend == AttachmentBackend.SOJU_FILEHOST) {
                        stringResource(R.string.upload_soju_private_desc)
                    } else if (config.backend == AttachmentBackend.CATBOX) {
                        "Anyone with the link can access it. Anonymous uploads cannot be deleted."
                    } else {
                        stringResource(
                            if (safe) R.string.upload_privacy_secret else R.string.upload_privacy_public,
                            backendRetention(config),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun UploadProgressSheet(
    progress: UploadProgress,
    onCancel: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onCancel, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface, dragHandle = null) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CloudUpload, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary, minimumContrast = 3.0))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.upload_uploading), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            if (progress is UploadProgress.Transferring && progress.totalBytes != null && progress.totalBytes > 0) {
                LinearProgressIndicator(progress = { (progress.bytesSent.toFloat() / progress.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.upload_progress_of, formatBytes(progress.bytesSent), formatBytes(progress.totalBytes)), Modifier.padding(top = 8.dp))
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (progress is UploadProgress.Transferring) Text(formatBytes(progress.bytesSent), Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(16.dp))
            TextButton(colors = readableTextButtonColors(), onClick = onCancel) {
                Icon(Icons.Filled.Close, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

private fun sameDestination(
    a: PasteBackendConfig,
    b: PasteBackendConfig,
): Boolean = a.backend == b.backend && (a.backend != AttachmentBackend.CUSTOM_0X0 || a.endpoint == b.endpoint)

private fun sameUploadConsent(
    disclosed: PasteBackendConfig,
    current: PasteBackendConfig,
): Boolean =
    sameDestination(disclosed, current) &&
        when (current.backend) {
            AttachmentBackend.CUSTOM_0X0 -> {
                disclosed.secretUrl == current.secretUrl && disclosed.expiry == current.expiry &&
                    disclosed.username == current.username && disclosed.password == current.password
            }

            AttachmentBackend.CRAFTERBIN -> {
                disclosed.secretUrl == current.secretUrl && disclosed.expiry == current.expiry
            }

            AttachmentBackend.X0_AT -> {
                disclosed.secretUrl == current.secretUrl
            }

            AttachmentBackend.LITTERBOX -> {
                disclosed.litterboxExpiry == current.litterboxExpiry
            }

            else -> {
                true
            }
        }

internal fun backendRetention(config: PasteBackendConfig): String =
    when (config.backend) {
        AttachmentBackend.CRAFTERBIN, AttachmentBackend.ZERO_X_ZERO, AttachmentBackend.CUSTOM_0X0 -> {
            config.expiry ?: "server default"
        }

        AttachmentBackend.X0_AT -> {
            "3–100 days by size"
        }

        AttachmentBackend.CNET -> {
            "rolling 180 days"
        }

        AttachmentBackend.UGUU -> {
            "3 hours"
        }

        AttachmentBackend.LITTERBOX -> {
            when (config.litterboxExpiry) {
                "1h" -> "1 hour"
                "12h" -> "12 hours"
                "24h" -> "24 hours"
                "72h" -> "72 hours"
                else -> config.litterboxExpiry
            }
        }

        AttachmentBackend.CATBOX -> {
            "up to 2 years without access"
        }

        AttachmentBackend.SOJU_FILEHOST -> {
            "server policy"
        }

        AttachmentBackend.TERMBIN -> {
            "server default"
        }
    }

internal fun formatBytes(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KiB".format(java.util.Locale.ROOT, bytes / 1024.0)
        else -> "%.1f MiB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
    }

private fun AttachmentSource.displayName() =
    when (this) {
        is AttachmentSource.Text -> name
        is AttachmentSource.Document -> name
        is AttachmentSource.Photo -> name
        is AttachmentSource.LocalFile -> name
    }

private fun android.content.ContentResolver.queryMeta(uri: Uri): Pair<String, Long?> {
    if (uri.scheme == ContentResolver.SCHEME_FILE) {
        val file = uri.path?.let(::File)?.takeIf { it.isFile }
        return (uri.lastPathSegment ?: "attachment") to file?.length()
    }
    var name = uri.lastPathSegment ?: "attachment"
    var size: Long? = null
    val cursor: Cursor? = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
            if (nameColumn >= 0) name = it.getString(nameColumn) ?: name
            if (sizeColumn >= 0 && !it.isNull(sizeColumn)) size = it.getLong(sizeColumn)
        }
    }
    return name to size
}

// Only read through the first byte beyond the selected cap, even if a provider streams an unbounded file.
private suspend fun ContentResolver.probeVideoSize(
    uri: Uri,
    limit: Long,
): Pair<Long, Boolean>? =
    withContext(Dispatchers.IO) {
        try {
            val stat = runCatching { openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull()
            if (stat != null && stat >= 0L) return@withContext stat to false
            openInputStream(uri)?.use { input ->
                val buffer = ByteArray(32 * 1024)
                var size = 0L
                while (size <= limit) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - size + 1).toInt())
                    if (count == -1) return@use size to false
                    if (count == 0) {
                        if (input.read() == -1) return@use size to false
                        size++
                    } else {
                        size += count
                    }
                }
                size to true
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

internal fun ContentResolver.inspectBatchFile(file: PendingShare.File): AttachmentBatchRow {
    val uri = file.uri
    var name = uri.lastPathSegment.orEmpty()
    val status =
        try {
            if (!uri.isHostedAttachmentUri()) {
                AttachmentBatchStatus.Failed(R.string.settings_local_media_required)
            } else {
                val meta = queryMeta(uri)
                name = meta.first
                val descriptorLength =
                    if (uri.scheme == ContentResolver.SCHEME_FILE && meta.second == null) {
                        null
                    } else {
                        openAssetFileDescriptor(uri, "r")?.use { it.length }
                    }
                if (descriptorLength == null) {
                    AttachmentBatchStatus.Failed(R.string.upload_batch_file_unavailable)
                } else {
                    val size = descriptorLength.takeIf { it >= 0 } ?: meta.second?.takeIf { it >= 0 }
                    val mime =
                        getType(uri).concreteMime() ?: file.mimeType.concreteMime()
                            ?: guessMimeType(name).concreteMime() ?: "application/octet-stream"
                    return AttachmentBatchRow(name, AttachmentSource.Document(uri, name, mime, size), AttachmentBatchStatus.Ready)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AttachmentBatchStatus.Failed(R.string.upload_batch_file_unavailable)
        }
    return AttachmentBatchRow(name, status = status)
}

private const val MIME_TOKEN = "[A-Za-z0-9][A-Za-z0-9!#\$&^_.+-]{0,126}"
private val BATCH_MIME_TYPE = Regex("$MIME_TOKEN/$MIME_TOKEN")

private fun String?.concreteMime(): String? {
    if (this == null || any { it != '\t' && it !in ' '..'~' }) return null
    return substringBefore(';').trim().takeIf(BATCH_MIME_TYPE::matches)
}

fun isLongDraft(text: String): Boolean = text.length >= 1_200 || text.lineSequence().count() >= 4

private fun Context.hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.hasPhotoAccess(): Boolean =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                ) == PackageManager.PERMISSION_GRANTED
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        }

        else -> {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

private fun photoReadPermissions(): Array<String> =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        }

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        }

        else -> {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

private fun ContentResolver.recentGalleryPhotos(): List<GalleryPhoto> {
    val projection =
        arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
        )
    val queryArgs =
        Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            // ponytail: 500 recent thumbnails keep sheet cheap; Browse reaches older media.
            putInt(ContentResolver.QUERY_ARG_LIMIT, 500)
        }
    return query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, queryArgs, null)
        ?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val size = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            buildList {
                while (cursor.moveToNext()) {
                    val mediaId = cursor.getLong(id)
                    add(
                        GalleryPhoto(
                            uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaId),
                            name = cursor.getString(name) ?: "photo-$mediaId",
                            mimeType = cursor.getString(mime),
                            size = cursor.getLong(size).takeUnless { cursor.isNull(size) },
                        ),
                    )
                }
            }
        }.orEmpty()
}

private fun cameraCaptureFile(
    context: Context,
    name: String?,
): File? {
    if (name == null || !name.matches(CAMERA_CAPTURE_NAME)) return null
    val directory = File(context.cacheDir.canonicalFile, "camera")
    val file = File(directory, name)
    return file.takeIf { it.canonicalFile == file }
}

private val CAMERA_CAPTURE_NAME = Regex("capture-[0-9]+\\.jpg")
