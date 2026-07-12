package io.github.trevarj.motd.ui.chat

import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.PasteProtocol
import io.github.trevarj.motd.attachment.UploadProgress

@OptIn(ExperimentalMaterial3Api::class)
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
    val config by viewModel.config.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<AttachmentSource?>(null) }
    var replaceDraft by remember { mutableStateOf(false) }
    var textEditor by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }

    LaunchedEffect(open, startWithCurrentDraft) {
        if (open && startWithCurrentDraft && currentDraft.isNotBlank()) {
            replaceDraft = true
            pending = AttachmentSource.Text(currentDraft)
            onDismiss()
        }
    }

    fun select(uri: Uri, photo: Boolean) {
        val meta = context.contentResolver.queryMeta(uri)
        pending = if (photo) AttachmentSource.Photo(uri, meta.first, context.contentResolver.getType(uri), meta.second)
        else AttachmentSource.Document(uri, meta.first, context.contentResolver.getType(uri), meta.second)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let { uri -> select(uri, false) } }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uri -> select(uri, true) } }

    if (open) ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("attachment_source_sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text("Add attachment", modifier = Modifier.padding(16.dp))
            ListItem({ Text("Photo from gallery") }, modifier = Modifier.clickable {
                onDismiss(); photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            ListItem({ Text("Choose file") }, modifier = Modifier.clickable { onDismiss(); filePicker.launch(arrayOf("*/*")) })
            ListItem({ Text("Upload current draft") }, supportingContent = { Text("Replaces the draft with its URL") },
                modifier = Modifier.clickable(enabled = currentDraft.isNotBlank()) {
                    replaceDraft = true; pending = AttachmentSource.Text(currentDraft); onDismiss()
                })
            ListItem({ Text("New text paste") }, modifier = Modifier.clickable { textEditor = true; onDismiss() })
            if (recent.isNotEmpty()) Text("Recent uploads", modifier = Modifier.padding(16.dp))
            recent.forEach { record -> ListItem(
                headlineContent = { Text(record.displayName) }, supportingContent = { Text(record.url) },
                trailingContent = { if (record.deletionToken != null) TextButton(onClick = { viewModel.delete(record) }) { Text("Delete") } },
                modifier = Modifier.clickable { onInsertUrl(record.url); onDismiss() },
            ) }
        }
    }
    if (textEditor) AlertDialog(onDismissRequest = { textEditor = false }, title = { Text("New text paste") },
        text = { OutlinedTextField(pasteText, { pasteText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp)) },
        confirmButton = { Button(enabled = pasteText.isNotBlank(), onClick = {
            pending = AttachmentSource.Text(pasteText); replaceDraft = false; textEditor = false
        }) { Text("Continue") } }, dismissButton = { TextButton(onClick = { textEditor = false }) { Text("Cancel") } })

    pending?.let { source -> AlertDialog(onDismissRequest = { pending = null }, title = { Text("Confirm upload") },
        text = { Column {
            if (source is AttachmentSource.Photo) {
                remember(source.uri) { context.contentResolver.sampledThumbnail(source.uri) }
                    ?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 220.dp)) }
            }
            Text(source.summary(), Modifier.padding(top = 8.dp)); Text("Destination: ${if (config.protocol == PasteProtocol.TERMBIN) "termbin.com:9999 (unencrypted)" else config.endpoint}")
            Text("Expires: ${config.expiry ?: "server default"}")
            Text(if (config.secretUrl && config.protocol == PasteProtocol.MULTIPART_0X0) "Secret URL requested; anyone with the URL can access it." else "Public upload; do not upload sensitive information.")
            if (config.protocol == PasteProtocol.TERMBIN && source !is AttachmentSource.Text) Text("Termbin is text-only. Select 0x0-compatible in Chat settings.")
        } }, confirmButton = { Button(enabled = config.protocol != PasteProtocol.TERMBIN || source is AttachmentSource.Text, onClick = {
            pending = null
            viewModel.upload(source) { record -> if (replaceDraft) onReplaceDraft(record.url) else onInsertUrl(record.url) }
        }) { Text("Upload") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } }) }

    progress?.let { update -> AlertDialog(onDismissRequest = {}, title = { Text("Uploading") }, text = {
        Column { CircularProgressIndicator(); if (update is UploadProgress.Transferring) Text("${update.bytesSent / 1024} KiB sent") }
    }, confirmButton = { TextButton(onClick = viewModel::cancel) { Text("Cancel") } }) }
    error?.let { message -> AlertDialog(onDismissRequest = viewModel::clearError, title = { Text("Upload failed") }, text = { Text(message) },
        confirmButton = { TextButton(onClick = viewModel::clearError) { Text("OK") } }) }
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
private fun AttachmentSource.summary() = when (this) {
    is AttachmentSource.Text -> "Text paste, ${text.length} characters"
    is AttachmentSource.Document -> "$name • ${mimeType ?: "unknown type"} • ${size?.let { "$it bytes" } ?: "unknown size"}"
    is AttachmentSource.Photo -> "$name • ${mimeType ?: "photo"} • ${size?.let { "$it bytes" } ?: "unknown size"}"
}

fun isLongDraft(text: String): Boolean = text.length >= 1_200 || text.lineSequence().count() >= 4
