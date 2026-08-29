package io.github.trevarj.motd.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.attachment.forBackend
import io.github.trevarj.motd.attachment.supports
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private sealed interface AttachmentFlow {
    data object Idle : AttachmentFlow

    data object Sources : AttachmentFlow

    data object Photos : AttachmentFlow

    data object EditText : AttachmentFlow

    data class Confirm(
        val source: AttachmentSource,
        val replaceDraft: Boolean,
        val config: PasteBackendConfig,
    ) : AttachmentFlow
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheets(
    open: Boolean,
    currentDraft: String,
    networkId: Long?,
    sojuFileHostAvailable: Boolean,
    startWithCurrentDraft: Boolean = false,
    // Inbound share hand-off: skip source selection and confirm this file directly.
    sharedFile: PendingShare.File? = null,
    directFileTransferAvailable: Boolean = false,
    imageOnly: Boolean = false,
    onDismiss: () -> Unit,
    onInsertUrl: (String) -> Unit,
    onReplaceDraft: (String) -> Unit,
    onDirectFile: (Uri) -> Unit = {},
    providerUploadAvailable: Boolean = false,
    onProviderUpload: (AttachmentSource) -> Unit = {},
    viewModel: AttachmentViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val cameraStartError = stringResource(R.string.upload_camera_failed)
    val defaultConfig by viewModel.config.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var flow by remember { mutableStateOf<AttachmentFlow>(AttachmentFlow.Idle) }
    var pasteText by remember { mutableStateOf("") }
    var lastAttempt by remember { mutableStateOf<AttachmentFlow.Confirm?>(null) }
    var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<UploadRecord?>(null) }
    var backendPickerRequest by remember { mutableStateOf<AttachmentFlow.Confirm?>(null) }
    var cameraPermissionGranted by remember { mutableStateOf(context.hasCameraPermission()) }
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

    LaunchedEffect(flow) {
        if (flow == AttachmentFlow.Photos) {
            cameraPermissionGranted = context.hasCameraPermission()
            photoAccessGranted = context.hasPhotoAccess()
        }
    }

    LaunchedEffect(open, startWithCurrentDraft, sharedFile) {
        if (!open) return@LaunchedEffect
        capturePath?.let(::File)?.let { captured ->
            if (captured.length() > 0L) {
                flow =
                    AttachmentFlow.Confirm(
                        AttachmentSource.LocalFile(captured, captured.name, "image/jpeg", captured.length()),
                        false,
                        defaultConfig,
                    )
            } else {
                captured.delete()
                capturePath = null
                flow = AttachmentFlow.Photos
            }
            return@LaunchedEffect
        }
        flow =
            when {
                sharedFile != null -> {
                    // The sender's declared type wins; fall back to the provider's when it omitted one.
                    val mime = sharedFile.mimeType ?: context.contentResolver.getType(sharedFile.uri)
                    val meta = context.contentResolver.queryMeta(sharedFile.uri)
                    val source =
                        if (mime?.startsWith("image/") == true) {
                            AttachmentSource.Photo(sharedFile.uri, meta.first, mime, meta.second)
                        } else {
                            AttachmentSource.Document(sharedFile.uri, meta.first, mime, meta.second)
                        }
                    AttachmentFlow.Confirm(source, false, defaultConfig)
                }

                startWithCurrentDraft && currentDraft.isNotBlank() -> {
                    AttachmentFlow.Confirm(AttachmentSource.Text(currentDraft), true, defaultConfig)
                }

                else -> {
                    AttachmentFlow.Sources
                }
            }
    }

    fun closeSourceSheet() {
        flow = AttachmentFlow.Idle
        onDismiss()
    }

    fun select(
        uri: Uri,
        photo: Boolean,
    ) {
        val meta = context.contentResolver.queryMeta(uri)
        val source =
            if (photo) {
                AttachmentSource.Photo(uri, meta.first, context.contentResolver.getType(uri), meta.second)
            } else {
                AttachmentSource.Document(uri, meta.first, context.contentResolver.getType(uri), meta.second)
            }
        flow = AttachmentFlow.Confirm(source, false, defaultConfig)
    }

    fun startUpload(request: AttachmentFlow.Confirm) {
        lastAttempt = request
        flow = AttachmentFlow.Idle
        viewModel.upload(
            request.source,
            request.config,
            AttachmentUploadContext(networkId),
        ) { record ->
            if (request.replaceDraft) onReplaceDraft(record.url) else onInsertUrl(record.url)
            if (request.source.deleteCameraCapture()) capturePath = null
            lastAttempt = null
            onDismiss()
        }
    }

    val filePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let { uri -> select(uri, false) }
        }
    val directFilePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(onDirectFile)
        }
    val photoPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            it?.let { uri -> select(uri, true) }
        }
    val takePicture =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val file = capturePath?.let(::File)
            if (!ok || file == null || file.length() <= 0L) {
                file?.delete()
                capturePath = null
                flow = AttachmentFlow.Photos
                return@rememberLauncherForActivityResult
            }
            val source = AttachmentSource.LocalFile(file, file.name, "image/jpeg", file.length())
            flow = AttachmentFlow.Confirm(source, false, defaultConfig)
        }

    fun launchCamera() {
        flow = AttachmentFlow.Idle
        val file = cameraCaptureFile(context)
        capturePath = file.absolutePath
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.camera", file)
        try {
            takePicture.launch(uri)
        } catch (_: Exception) {
            capturePath = null
            file.delete()
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
                recent = if (imageOnly) recent.filter { it.mimeType?.startsWith("image/") == true } else recent,
                imageOnly = imageOnly,
                onDismiss = ::closeSourceSheet,
                onPhoto = { flow = AttachmentFlow.Photos },
                onFile = {
                    closeSourceSheet()
                    filePicker.launch(arrayOf(if (imageOnly) "image/*" else "*/*"))
                },
                directFileTransferAvailable = directFileTransferAvailable,
                onDirectFile = {
                    closeSourceSheet()
                    directFilePicker.launch(arrayOf("*/*"))
                },
                onCurrentDraft = {
                    closeSourceSheet()
                    flow = AttachmentFlow.Confirm(AttachmentSource.Text(currentDraft), true, defaultConfig)
                },
                onNewText = {
                    closeSourceSheet()
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
                    flow =
                        AttachmentFlow.Confirm(
                            AttachmentSource.Photo(photo.uri, photo.name, photo.mimeType, photo.size),
                            false,
                            defaultConfig,
                        )
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
                    flow = AttachmentFlow.Confirm(AttachmentSource.Text(pasteText), false, defaultConfig)
                },
            )
        }

        is AttachmentFlow.Confirm -> {
            ConfirmationSheet(
                source = current.source,
                config = current.config,
                sojuFileHostAvailable = sojuFileHostAvailable,
                providerUploadAvailable = providerUploadAvailable,
                onProviderUpload = {
                    onProviderUpload(current.source)
                    closeSourceSheet()
                },
                onChangeDestination = {
                    backendPickerRequest = current
                    flow = AttachmentFlow.Idle
                },
                onDismiss = {
                    if (current.source.deleteCameraCapture()) capturePath = null
                    closeSourceSheet()
                },
                onUpload = { startUpload(current) },
            )
        }
    }

    backendPickerRequest?.let { request ->
        BackendPickerSheet(
            source = request.source,
            config = request.config,
            sojuFileHostAvailable = sojuFileHostAvailable,
            onSelect = { selected ->
                flow = request.copy(config = selected)
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
                viewModel.cancel()
                lastAttempt?.let { flow = it }
            },
        )
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = {
                viewModel.clearError()
                lastAttempt?.let { flow = it }
            },
            icon = { Icon(Icons.Outlined.CloudUpload, null) },
            title = { Text(stringResource(R.string.upload_failed)) },
            text = { Text(message) },
            confirmButton = {
                lastAttempt?.let { request ->
                    TextButton(onClick = {
                        viewModel.clearError()
                        startUpload(request)
                    }) {
                        Icon(Icons.Outlined.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.upload_retry))
                    }
                } ?: TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton =
                if (lastAttempt != null) {
                    {
                        TextButton(onClick = {
                            viewModel.clearError()
                            lastAttempt?.let { flow = it }
                        }) { Text(stringResource(R.string.action_back)) }
                    }
                } else {
                    null
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
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
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
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("attachment_photo_sheet"),
    ) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 700.dp).padding(horizontal = 16.dp)) {
            Text(
                stringResource(R.string.upload_photos_title),
                style = MaterialTheme.typography.headlineSmall,
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
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable(onClick = onClick)
                .testTag(tag),
    ) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2)
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
                Icon(Icons.Outlined.PhotoCamera, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            Icon(
                Icons.Outlined.PhotoCamera,
                contentDescription = label,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("attachment_source_sheet")) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.upload_add_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.upload_recent), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                recent.forEach { record -> RecentUploadRow(record, onInsertRecent, onCopyRecent, onDeleteRecent) }
            }
            Spacer(Modifier.height(24.dp))
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
        modifier = modifier.height(108.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun RecentUploadRow(
    record: UploadRecord,
    onInsert: (UploadRecord) -> Unit,
    onCopy: (UploadRecord) -> Unit,
    onDelete: (UploadRecord) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(record.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text("${record.backend.label} • ${record.sizeBytes?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown)}", maxLines = 1)
        },
        leadingContent = { Icon(if (record.mimeType?.startsWith("image/") == true) Icons.Outlined.Image else Icons.Outlined.Description, null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.upload_insert_link)) }, {
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
        },
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onInsert(record) },
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.upload_text_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
    sojuFileHostAvailable: Boolean,
    providerUploadAvailable: Boolean = false,
    onProviderUpload: () -> Unit = {},
    onChangeDestination: () -> Unit,
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
) {
    val sojuUnavailable = config.backend == AttachmentBackend.SOJU_FILEHOST && !sojuFileHostAvailable
    val customUnavailable =
        config.backend == AttachmentBackend.CUSTOM_0X0 &&
            io.github.trevarj.motd.attachment
                .validateEndpoint(config.endpoint) == null
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.upload_confirm_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                val preview =
                    when (source) {
                        is AttachmentSource.Photo -> source.uri
                        is AttachmentSource.LocalFile -> source.file.takeIf { source.mimeType.startsWith("image/") }
                        else -> null
                    }
                if (preview != null) {
                    AsyncImage(
                        model = preview,
                        contentDescription = null,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(20.dp))
                                .testTag("attachment_thumbnail"),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                AttachmentMetadata(source)
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.upload_destination), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                ListItem(
                    headlineContent = { Text(config.backend.label, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(
                            when {
                                sojuUnavailable -> stringResource(R.string.upload_soju_unavailable)
                                customUnavailable -> stringResource(R.string.upload_custom_unavailable)
                                else -> backendRetention(config)
                            },
                        )
                    },
                    trailingContent = { Text(stringResource(R.string.upload_destination_change), color = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onChangeDestination),
                )
                Spacer(Modifier.height(12.dp))
                UploadPrivacyCard(config)
                Spacer(Modifier.height(8.dp))
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (providerUploadAvailable && source !is AttachmentSource.Text) {
                    Button(
                        onClick = onProviderUpload,
                        modifier = Modifier.fillMaxWidth().height(52.dp).testTag("attachment_provider_upload"),
                    ) {
                        Icon(Icons.Outlined.Lock, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sidecar_upload_action))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Button(
                    onClick = onUpload,
                    enabled = !sojuUnavailable && !customUnavailable,
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("attachment_upload"),
                ) {
                    Icon(Icons.Outlined.CloudUpload, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.upload_action))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.height(12.dp))
            }
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
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetSystemBars()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.upload_destination), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            uploadDestinations(source, config, sojuFileHostAvailable).forEach { destination ->
                ListItem(
                    headlineContent = { Text(destination.label, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text(backendRetention(destination.config)) },
                    trailingContent =
                        if (sameDestination(config, destination.config)) {
                            { Text("Selected", color = MaterialTheme.colorScheme.primary) }
                        } else {
                            null
                        },
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onSelect(destination.config) },
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun AttachmentMetadata(source: AttachmentSource) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val image = source is AttachmentSource.Photo || source is AttachmentSource.LocalFile && source.mimeType.startsWith("image/")
            Icon(if (image) Icons.Outlined.Image else Icons.Outlined.Description, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(source.displayName(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (source) {
                        is AttachmentSource.Text -> pluralStringResource(R.plurals.upload_character_count, source.text.length, source.text.length)
                        is AttachmentSource.Document -> stringResource(R.string.upload_metadata, source.mimeType ?: stringResource(R.string.upload_type_unknown), source.size?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown))
                        is AttachmentSource.Photo -> stringResource(R.string.upload_metadata, source.mimeType ?: stringResource(R.string.upload_type_photo), source.size?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown))
                        is AttachmentSource.LocalFile -> stringResource(R.string.upload_metadata, source.mimeType, source.size?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    ModalBottomSheet(onDismissRequest = onCancel, dragHandle = null) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CloudUpload, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
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
            TextButton(onClick = onCancel) {
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
    var name = uri.lastPathSegment ?: "attachment"
    var size: Long? = null
    val cursor: Cursor? = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            name = it.getString(0) ?: name
            if (!it.isNull(1)) size = it.getLong(1)
        }
    }
    return name to size
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

internal fun cameraCaptureFile(context: Context): File {
    val dir = File(context.cacheDir, "camera").also { it.mkdirs() }
    return File(dir, "capture-${System.currentTimeMillis()}.jpg")
}

private fun AttachmentSource.deleteCameraCapture(): Boolean =
    if (this is AttachmentSource.LocalFile && file.parentFile?.name == "camera") {
        file.delete()
        true
    } else {
        false
    }
