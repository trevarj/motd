package io.github.trevarj.motd.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.EndpointPreset
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.PasteProtocol
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface AttachmentFlow {
    data object Idle : AttachmentFlow
    data object Sources : AttachmentFlow
    data object EditText : AttachmentFlow
    data class Confirm(
        val source: AttachmentSource,
        val replaceDraft: Boolean,
        val config: PasteBackendConfig,
    ) : AttachmentFlow
}

internal data class UploadDestination(val label: String, val config: PasteBackendConfig)

internal fun uploadDestinations(source: AttachmentSource, config: PasteBackendConfig): List<UploadDestination> {
    val destinations = mutableListOf<UploadDestination>()
    if (source is AttachmentSource.Text) {
        destinations += UploadDestination("Termbin", config.copy(protocol = PasteProtocol.TERMBIN))
    }
    destinations += UploadDestination(
        "CrafterBin",
        config.copy(protocol = PasteProtocol.MULTIPART_0X0, endpoint = EndpointPreset.CRAFTERBIN.endpoint!!),
    )
    destinations += UploadDestination(
        "0x0.st",
        config.copy(protocol = PasteProtocol.MULTIPART_0X0, endpoint = EndpointPreset.ZERO_X_ZERO.endpoint!!),
    )
    if (EndpointPreset.entries.none { it.endpoint == config.endpoint }) {
        destinations += UploadDestination(
            "Custom",
            config.copy(protocol = PasteProtocol.MULTIPART_0X0),
        )
    }
    return destinations.distinctBy { it.config.protocol to it.config.endpoint }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheets(
    open: Boolean,
    currentDraft: String,
    startWithCurrentDraft: Boolean = false,
    onDismiss: () -> Unit,
    onInsertUrl: (String) -> Unit,
    onReplaceDraft: (String) -> Unit,
    viewModel: AttachmentViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val defaultConfig by viewModel.config.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var flow by remember { mutableStateOf<AttachmentFlow>(AttachmentFlow.Idle) }
    var pasteText by remember { mutableStateOf("") }
    var lastAttempt by remember { mutableStateOf<AttachmentFlow.Confirm?>(null) }
    var deleteTarget by remember { mutableStateOf<UploadRecord?>(null) }

    LaunchedEffect(open, startWithCurrentDraft) {
        if (!open) return@LaunchedEffect
        flow = if (startWithCurrentDraft && currentDraft.isNotBlank()) {
            AttachmentFlow.Confirm(AttachmentSource.Text(currentDraft), true, defaultConfig)
        } else {
            AttachmentFlow.Sources
        }
    }

    fun closeSourceSheet() {
        flow = AttachmentFlow.Idle
        onDismiss()
    }

    fun select(uri: Uri, photo: Boolean) {
        val meta = context.contentResolver.queryMeta(uri)
        val source = if (photo) {
            AttachmentSource.Photo(uri, meta.first, context.contentResolver.getType(uri), meta.second)
        } else {
            AttachmentSource.Document(uri, meta.first, context.contentResolver.getType(uri), meta.second)
        }
        flow = AttachmentFlow.Confirm(source, false, defaultConfig)
    }

    fun startUpload(request: AttachmentFlow.Confirm) {
        lastAttempt = request
        flow = AttachmentFlow.Idle
        viewModel.upload(request.source, request.config) { record ->
            if (request.replaceDraft) onReplaceDraft(record.url) else onInsertUrl(record.url)
            lastAttempt = null
            onDismiss()
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> select(uri, false) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
        it?.let { uri -> select(uri, true) }
    }

    when (val current = flow) {
        AttachmentFlow.Idle -> Unit
        AttachmentFlow.Sources -> SourceSheet(
            currentDraft = currentDraft,
            recent = recent,
            onDismiss = ::closeSourceSheet,
            onPhoto = {
                closeSourceSheet()
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onFile = {
                closeSourceSheet()
                filePicker.launch(arrayOf("*/*"))
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
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText(record.displayName, record.url))
            },
            onDeleteRecent = { deleteTarget = it },
        )
        AttachmentFlow.EditText -> TextPasteSheet(
            text = pasteText,
            onTextChange = { pasteText = it },
            onDismiss = ::closeSourceSheet,
            onContinue = {
                flow = AttachmentFlow.Confirm(AttachmentSource.Text(pasteText), false, defaultConfig)
            },
        )
        is AttachmentFlow.Confirm -> ConfirmationSheet(
            request = current,
            onConfigChange = { flow = current.copy(config = it) },
            onDismiss = ::closeSourceSheet,
            onUpload = { startUpload(current) },
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
            onDismissRequest = viewModel::clearError,
            icon = { Icon(Icons.Outlined.CloudUpload, null) },
            title = { Text(stringResource(R.string.upload_failed)) },
            text = { Text(message) },
            confirmButton = {
                lastAttempt?.let { request ->
                    TextButton(onClick = { viewModel.clearError(); startUpload(request) }) {
                        Icon(Icons.Outlined.Refresh, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.upload_retry))
                    }
                } ?: TextButton(onClick = viewModel::clearError) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = if (lastAttempt != null) {
                { TextButton(onClick = { viewModel.clearError(); lastAttempt?.let { flow = it } }) { Text(stringResource(R.string.action_back)) } }
            } else null,
        )
    }

    deleteTarget?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text(stringResource(R.string.upload_delete_title)) },
            text = { Text(stringResource(R.string.upload_delete_body, record.displayName)) },
            confirmButton = {
                Button(onClick = { viewModel.delete(record); deleteTarget = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(
    currentDraft: String,
    recent: List<UploadRecord>,
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    onCurrentDraft: () -> Unit,
    onNewText: () -> Unit,
    onInsertRecent: (UploadRecord) -> Unit,
    onCopyRecent: (UploadRecord) -> Unit,
    onDeleteRecent: (UploadRecord) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("attachment_source_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.upload_add_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.upload_add_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceCard(Icons.Outlined.Image, stringResource(R.string.upload_photo), stringResource(R.string.upload_photo_desc), true, onPhoto, Modifier.weight(1f))
                SourceCard(Icons.Outlined.AttachFile, stringResource(R.string.upload_file), stringResource(R.string.upload_file_desc), true, onFile, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SourceCard(Icons.Outlined.Description, stringResource(R.string.upload_draft), stringResource(R.string.upload_draft_desc), currentDraft.isNotBlank(), onCurrentDraft, Modifier.weight(1f))
                SourceCard(Icons.Outlined.Edit, stringResource(R.string.upload_text), stringResource(R.string.upload_text_desc), true, onNewText, Modifier.weight(1f))
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
            Text("${stringResource(if (record.backend == PasteProtocol.TERMBIN) R.string.upload_backend_termbin else R.string.upload_backend_0x0)} • ${record.sizeBytes?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown)}", maxLines = 1)
        },
        leadingContent = { Icon(if (record.mimeType?.startsWith("image/") == true) Icons.Outlined.Image else Icons.Outlined.Description, null) },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.upload_insert_link)) }, { menuOpen = false; onInsert(record) }, leadingIcon = { Icon(Icons.Outlined.InsertLink, null) })
                    DropdownMenuItem({ Text(stringResource(R.string.upload_copy_link)) }, { menuOpen = false; onCopy(record) }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) })
                    if (record.deletionToken != null) {
                        DropdownMenuItem({ Text(stringResource(R.string.action_delete)) }, { menuOpen = false; onDelete(record) }, leadingIcon = { Icon(Icons.Filled.Delete, null) })
                    }
                }
            }
        },
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onInsert(record) },
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun TextPasteSheet(text: String, onTextChange: (String) -> Unit, onDismiss: () -> Unit, onContinue: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
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
private fun ConfirmationSheet(
    request: AttachmentFlow.Confirm,
    onConfigChange: (PasteBackendConfig) -> Unit,
    onDismiss: () -> Unit,
    onUpload: () -> Unit,
) {
    val context = LocalContext.current
    val destinations = remember(request.source, request.config.endpoint) { uploadDestinations(request.source, request.config) }
    val thumbnail by produceState<android.graphics.Bitmap?>(null, request.source) {
        value = if (request.source is AttachmentSource.Photo) {
            withContext(Dispatchers.IO) {
                context.contentResolver.sampledThumbnail(request.source.uri)
            }
        } else {
            null
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.upload_confirm_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (request.source is AttachmentSource.Photo) {
                thumbnail?.let { bitmap ->
                        Image(bitmap.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)), contentScale = ContentScale.Crop)
                        Spacer(Modifier.height(12.dp))
                    }
            }
            AttachmentMetadata(request.source)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.upload_destination), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                destinations.forEach { destination ->
                    FilterChip(
                        selected = sameDestination(request.config, destination.config),
                        onClick = { onConfigChange(destination.config) },
                        label = { Text(destination.label) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            UploadPrivacyCard(request.config)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onUpload, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Icon(Icons.Outlined.CloudUpload, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.upload_action))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AttachmentMetadata(source: AttachmentSource) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (source is AttachmentSource.Photo) Icons.Outlined.Image else Icons.Outlined.Description, null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(source.displayName(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (source) {
                        is AttachmentSource.Text -> pluralStringResource(R.plurals.upload_character_count, source.text.length, source.text.length)
                        is AttachmentSource.Document -> stringResource(R.string.upload_metadata, source.mimeType ?: stringResource(R.string.upload_type_unknown), source.size?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown))
                        is AttachmentSource.Photo -> stringResource(R.string.upload_metadata, source.mimeType ?: stringResource(R.string.upload_type_photo), source.size?.let(::formatBytes) ?: stringResource(R.string.upload_size_unknown))
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
    val termbin = config.protocol == PasteProtocol.TERMBIN
    val safe = !termbin && config.secretUrl
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (safe) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(if (safe) Icons.Outlined.Lock else Icons.Outlined.Public, null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (termbin) stringResource(R.string.upload_termbin_unencrypted) else stringResource(R.string.upload_privacy_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(
                        if (safe) R.string.upload_privacy_secret else R.string.upload_privacy_public,
                        config.expiry ?: stringResource(R.string.upload_expiry_default),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun UploadProgressSheet(progress: UploadProgress, onCancel: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onCancel, dragHandle = null) {
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
            TextButton(onClick = onCancel) { Icon(Icons.Filled.Close, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.action_cancel)) }
        }
    }
}

private fun sameDestination(a: PasteBackendConfig, b: PasteBackendConfig): Boolean =
    a.protocol == b.protocol && (a.protocol == PasteProtocol.TERMBIN || a.endpoint == b.endpoint)

internal fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KiB".format(java.util.Locale.ROOT, bytes / 1024.0)
    else -> "%.1f MiB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
}

private fun AttachmentSource.displayName() = when (this) {
    is AttachmentSource.Text -> name
    is AttachmentSource.Document -> name
    is AttachmentSource.Photo -> name
}
private fun android.content.ContentResolver.queryMeta(uri: Uri): Pair<String, Long?> {
    var name = uri.lastPathSegment ?: "attachment"
    var size: Long? = null
    val cursor: Cursor? = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
    cursor?.use { if (it.moveToFirst()) { name = it.getString(0) ?: name; if (!it.isNull(1)) size = it.getLong(1) } }
    return name to size
}

private fun android.content.ContentResolver.sampledThumbnail(uri: Uri): android.graphics.Bitmap? = runCatching {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 640 || bounds.outHeight / sample > 360) sample *= 2
    val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample.coerceAtLeast(1) }
    openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
}.getOrNull()

fun isLongDraft(text: String): Boolean = text.length >= 1_200 || text.lineSequence().count() >= 4
