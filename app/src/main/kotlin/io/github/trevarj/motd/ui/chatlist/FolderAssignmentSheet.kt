package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.ui.components.FolderIcon
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderAssignmentSheet(
    folders: List<ChatFolderEntity>,
    onAssign: (Long?, (Boolean) -> Unit) -> Unit,
    onCreate: (String, FolderIconRef, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf(FolderIconRef()) }
    var pickingIcon by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val visible = folders.filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("folder_assignment_sheet")) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.folders_add_to), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            OutlinedTextField(query, { query = it }, label = { Text(stringResource(R.string.action_search)) }, modifier = Modifier.fillMaxWidth())
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                item {
                    FolderDestinationRow(
                        icon = { Icon(Icons.Outlined.FolderOff, null) },
                        text = stringResource(R.string.folders_none),
                        tag = "folder_destination_none",
                    ) {
                        busy = true
                        onAssign(null) { success ->
                            busy = false
                            if (success) onDismiss() else error = true
                        }
                    }
                }
                items(visible, key = ChatFolderEntity::id) { folder ->
                    FolderDestinationRow(
                        icon = { FolderIcon(FolderIconRef(folder.iconKind, folder.iconKey), null, Modifier.size(24.dp)) },
                        text = folder.displayName,
                        tag = "folder_destination_${folder.id}",
                    ) {
                        busy = true
                        onAssign(folder.id) { success ->
                            busy = false
                            if (success) onDismiss() else error = true
                        }
                    }
                }
                item {
                    FolderDestinationRow(
                        icon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                        text = stringResource(R.string.folders_create),
                        tag = "folder_destination_create",
                    ) { creating = true }
                }
            }
            if (creating) {
                OutlinedTextField(name, {
                    name = it
                    error = false
                }, label = { Text(stringResource(R.string.folders_name)) }, isError = error, modifier = Modifier.fillMaxWidth())
                Button(onClick = { pickingIcon = true }, modifier = Modifier.padding(vertical = 8.dp)) {
                    FolderIcon(icon, null, Modifier.size(24.dp))
                    Text(stringResource(R.string.folders_choose_icon), modifier = Modifier.padding(start = 8.dp))
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        onCreate(name, icon) { success ->
                            busy = false
                            if (success) onDismiss() else error = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("folder_create_assign"),
                ) { Text(stringResource(R.string.folders_create)) }
            }
            if (error) Text(stringResource(R.string.folders_operation_failed), color = MaterialTheme.colorScheme.error)
        }
    }
    if (pickingIcon) {
        FolderIconPicker(icon, {
            icon = it
            pickingIcon = false
        }, { pickingIcon = false })
    }
}

@Composable
private fun FolderDestinationRow(
    icon: @Composable () -> Unit,
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .testTag(tag)
                .padding(vertical = 14.dp),
    ) {
        icon()
        Text(text, modifier = Modifier.padding(start = 16.dp))
    }
}
