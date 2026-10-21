package io.github.trevarj.motd.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.isLocalMediaUri
import io.github.trevarj.motd.avatar.validateAvatarUrl
import io.github.trevarj.motd.ui.theme.SheetSystemBars

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
fun AvatarEditorSheet(
    open: Boolean,
    currentModel: String?,
    isChannel: Boolean,
    identity: String,
    name: String,
    networkId: Long?,
    onDismiss: () -> Unit,
    onImport: (Uri) -> Unit,
    onUpload: () -> Unit,
    onUrl: (String) -> Unit,
    onReset: () -> Unit,
    onShare: () -> Unit = {},
    onClearShared: () -> Unit = {},
) {
    if (!open) return
    var url by remember(identity, networkId, currentModel) { mutableStateOf(currentModel?.takeIf { validateAvatarUrl(it) != null }.orEmpty()) }
    var invalid by remember(identity, networkId) { mutableStateOf(false) }
    var confirmClear by remember(identity, networkId) { mutableStateOf(false) }
    var localProposal by remember(identity, networkId, currentModel) { mutableStateOf<Uri?>(null) }
    var localImportError by remember(identity, networkId, currentModel) { mutableStateOf(false) }
    var importOwner by remember { mutableStateOf<Triple<String, Long?, String?>?>(null) }
    val imagePicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (importOwner == Triple(identity, networkId, currentModel)) {
                localImportError = uri != null && !uri.isLocalMediaUri()
                localProposal = uri?.takeIf { it.isLocalMediaUri() }
            }
            importOwner = null
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("avatar_editor_sheet"),
    ) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.avatar_editor_title),
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            CurrentAvatarInspection(name, isChannel, currentModel, Modifier.padding(horizontal = 16.dp))
            if (localImportError) Text(stringResource(R.string.settings_local_media_required), color = androidx.compose.material3.MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp).testTag("avatar_editor_import_error"))
            localProposal?.let { uri ->
                LocalAvatarProposal(uri, isChannel, Modifier.padding(horizontal = 16.dp))
                TextButton(onClick = { if (uri.isLocalMediaUri()) onImport(uri) }, enabled = uri.isLocalMediaUri(), modifier = Modifier.padding(horizontal = 8.dp).testTag("avatar_editor_use_image")) { Text(stringResource(R.string.settings_avatar_use_image)) }
                TextButton(onClick = { localProposal = null }, modifier = Modifier.padding(horizontal = 8.dp).testTag("avatar_editor_discard_image")) { Text(stringResource(R.string.action_cancel)) }
            }
            AvatarEditorAction(
                icon = Icons.Outlined.Image,
                title = stringResource(R.string.avatar_import),
                supporting = stringResource(R.string.avatar_import_desc),
                tag = "avatar_editor_import",
            ) {
                localImportError = false
                importOwner = Triple(identity, networkId, currentModel)
                imagePicker.launch(arrayOf("image/*"))
            }
            AvatarEditorAction(
                icon = Icons.Outlined.CloudUpload,
                title = stringResource(R.string.avatar_upload),
                supporting = stringResource(R.string.avatar_upload_desc),
                tag = "avatar_editor_upload",
                onClick = onUpload,
            )
            HorizontalDivider()
            OutlinedTextField(
                value = url,
                onValueChange = {
                    url = it
                    invalid = false
                    localImportError = false
                    localProposal = null
                    importOwner = null
                },
                label = { Text(stringResource(R.string.avatar_url)) },
                supportingText = {
                    Text(stringResource(if (invalid) R.string.avatar_url_invalid else R.string.avatar_url_desc))
                },
                isError = invalid,
                singleLine = true,
                trailingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("avatar_editor_url"),
            )
            TextButton(
                onClick = {
                    val validated = validateAvatarUrl(url)
                    if (validated == null) invalid = true else onUrl(validated)
                },
                modifier = Modifier.padding(horizontal = 8.dp).testTag("avatar_editor_url_save"),
            ) { Text(stringResource(R.string.avatar_url_save)) }
            ExplicitAvatarUrlPreview(url, networkId, identity, isChannel, Modifier.padding(horizontal = 16.dp))
            AvatarEditorAction(
                icon = Icons.Filled.Delete,
                title = stringResource(R.string.avatar_reset_local),
                tag = "avatar_editor_reset",
                onClick = onReset,
            )
            if (isChannel) {
                HorizontalDivider()
                AvatarEditorAction(
                    icon = Icons.Outlined.Public,
                    title = stringResource(R.string.avatar_share),
                    tag = "avatar_editor_share",
                    onClick = onShare,
                )
                AvatarEditorAction(
                    icon = Icons.Filled.Delete,
                    title = stringResource(R.string.avatar_clear_shared),
                    tag = "avatar_editor_clear_shared",
                ) { confirmClear = true }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.padding(horizontal = 8.dp).testTag("avatar_editor_cancel")) { Text(stringResource(R.string.action_cancel)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("avatar_editor_clear_dialog"),
            title = { Text(stringResource(R.string.avatar_clear_shared_title)) },
            text = { Text(stringResource(R.string.avatar_clear_shared_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClear = false
                        onClearShared()
                    },
                    modifier = Modifier.testTag("avatar_editor_clear_confirm"),
                ) { Text(stringResource(R.string.avatar_clear_shared)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun AvatarEditorAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    supporting: String? = null,
    tag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { value -> { Text(value) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag(tag)
                .clickable(role = Role.Button, onClick = onClick),
    )
}
