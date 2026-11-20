package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.FolderIconKind
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.ChatFolderRepository
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.components.FolderIcon
import io.github.trevarj.motd.ui.components.FolderIconChoice
import io.github.trevarj.motd.ui.components.folderIconChoices
import io.github.trevarj.motd.ui.theme.readableOutlinedTextFieldColors
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.trevarj.motd.ui.components.EffectDropdownMenu as DropdownMenu
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

data class FolderManagementState(
    val folders: List<ChatFolderEntity> = emptyList(),
    val chats: List<ChatListRow> = emptyList(),
)

@HiltViewModel
class FolderManagementViewModel
    @Inject
    constructor(
        private val folders: ChatFolderRepository,
        buffers: BufferRepository,
    ) : ViewModel() {
        val state: StateFlow<FolderManagementState?> =
            combine(folders.observeFolders(), buffers.observeChatList(), ::FolderManagementState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun move(
            id: Long,
            delta: Int,
        ) {
            val order =
                state.value
                    ?.folders
                    ?.mapTo(mutableListOf(), ChatFolderEntity::id) ?: return
            val from = order.indexOf(id)
            if (from < 0) return
            val to = (from + delta).coerceIn(0, order.lastIndex)
            if (from == to) return
            order.add(to, order.removeAt(from))
            viewModelScope.launch { folders.reorder(order) }
        }

        fun save(
            id: Long,
            name: String,
            icon: FolderIconRef,
            members: Collection<Long>,
            result: (Boolean) -> Unit,
        ) = viewModelScope.launch {
            runCatching { folders.save(id, name, icon, members) }
                .onSuccess { result(true) }
                .onFailure { result(false) }
        }

        fun delete(
            id: Long,
            done: () -> Unit,
        ) = viewModelScope.launch {
            folders.delete(id)
            done()
        }

        fun resetIgnored() = viewModelScope.launch { folders.resetIgnored() }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageFoldersScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onAutoGroup: () -> Unit,
    viewModel: FolderManagementViewModel = hiltViewModel(),
) {
    val state = viewModel.state.collectAsStateWithLifecycle().value
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.testTag("screen_manage_folders"),
        topBar = {
            FolderManagementTopBar(
                onBack = onBack,
                onAutoGroup = onAutoGroup,
                onCreate = { onEdit(0) },
            )
        },
    ) { padding ->
        val folders = state?.folders.orEmpty()
        val chats = state?.chats.orEmpty()
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(folders, key = ChatFolderEntity::id) { folder ->
                var drag by remember(folder.id) { mutableFloatStateOf(0f) }
                val index = folders.indexOfFirst { it.id == folder.id }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .pointerInput(folder.id) {
                                detectDragGestures(
                                    onDragEnd = {
                                        when {
                                            drag > 24f -> viewModel.move(folder.id, 1)
                                            drag < -24f -> viewModel.move(folder.id, -1)
                                        }
                                        drag = 0f
                                    },
                                    onDragCancel = { drag = 0f },
                                ) { change, amount ->
                                    change.consume()
                                    drag += amount.y
                                }
                            }.clickable { onEdit(folder.id) }
                            .testTag("folder_manage_${folder.id}")
                            .padding(16.dp),
                ) {
                    FolderIcon(FolderIconRef(folder.iconKind, folder.iconKey), null, Modifier.size(24.dp))
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                        Text(folder.displayName)
                        val assigned = chats.count { it.folderId == folder.id }
                        Text(
                            pluralStringResource(R.plurals.folders_assigned_count, assigned, assigned),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    IconButton(onClick = { viewModel.move(folder.id, -1) }, enabled = index > 0, modifier = Modifier.testTag("folder_move_up_${folder.id}")) {
                        Icon(Icons.Filled.ArrowUpward, stringResource(R.string.folders_move_up))
                    }
                    IconButton(onClick = { viewModel.move(folder.id, 1) }, enabled = index in 0 until folders.lastIndex, modifier = Modifier.testTag("folder_move_down_${folder.id}")) {
                        Icon(Icons.Filled.ArrowDownward, stringResource(R.string.folders_move_down))
                    }
                }
            }
            item {
                TextButton(colors = readableTextButtonColors(), onClick = viewModel::resetIgnored, modifier = Modifier.padding(12.dp).testTag("folders_reset_ignored")) {
                    Text(stringResource(R.string.folders_reset_ignored))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderManagementTopBar(
    onBack: () -> Unit,
    onAutoGroup: () -> Unit,
    onCreate: () -> Unit,
) {
    TopAppBar(
        colors =
            androidx.compose.material3.TopAppBarDefaults
                .topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        title = { Text(stringResource(R.string.folders_manage)) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
            }
        },
        actions = {
            IconButton(onClick = onAutoGroup, modifier = Modifier.testTag("folders_auto_group")) {
                Icon(Icons.Outlined.AutoAwesome, stringResource(R.string.folders_auto_group))
            }
            IconButton(onClick = onCreate, modifier = Modifier.testTag("folders_create")) {
                Icon(Icons.Filled.Add, stringResource(R.string.folders_create))
            }
        },
    )
}

internal data class FolderDraft(
    val name: String,
    val icon: FolderIconRef,
    val members: Set<Long>,
    val query: String = "",
)

internal val FolderDraftSaver =
    listSaver<FolderDraft, Any>(
        save = { listOf(it.name, it.icon.kind.name, it.icon.key, it.members.toLongArray(), it.query) },
        restore = {
            FolderDraft(
                name = it[0] as String,
                icon = FolderIconRef(FolderIconKind.valueOf(it[1] as String), it[2] as String),
                members = (it[3] as LongArray).toSet(),
                query = it[4] as String,
            )
        },
    )

@Composable
fun FolderEditorScreen(
    folderId: Long,
    onBack: () -> Unit,
    viewModel: FolderManagementViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    FolderEditorContent(
        folderId = folderId,
        state = state,
        onBack = onBack,
        onSave = { name, icon, members, result -> viewModel.save(folderId, name, icon, members, result) },
        onDelete = { viewModel.delete(folderId, it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderEditorContent(
    folderId: Long,
    state: FolderManagementState?,
    onBack: () -> Unit,
    onSave: (String, FolderIconRef, Collection<Long>, (Boolean) -> Unit) -> Unit,
    onDelete: (() -> Unit) -> Unit,
) {
    val existing = state?.folders?.firstOrNull { it.id == folderId }
    var confirmDelete by remember(folderId, existing != null) { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.testTag("screen_folder_editor"),
        topBar = {
            TopAppBar(
                colors =
                    androidx.compose.material3.TopAppBarDefaults
                        .topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                title = { Text(stringResource(if (folderId == 0L) R.string.folders_create else R.string.folders_edit)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                actions = {
                    if (existing != null) IconButton(onClick = { confirmDelete = true }, modifier = Modifier.testTag("folder_delete")) { Icon(Icons.Filled.Delete, stringResource(R.string.folders_delete)) }
                },
            )
        },
    ) { padding ->
        if (folderId != 0L && existing == null) {
            if (state == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                EmptyState(
                    icon = Icons.Outlined.FolderOff,
                    title = stringResource(R.string.folders_missing),
                    message = null,
                    modifier = Modifier.padding(padding),
                )
            }
            return@Scaffold
        }
        val chats = state?.chats.orEmpty()
        var draft by rememberSaveable(folderId, stateSaver = FolderDraftSaver) {
            mutableStateOf(
                FolderDraft(
                    name = existing?.displayName.orEmpty(),
                    icon = if (existing == null) FolderIconRef() else FolderIconRef(existing.iconKind, existing.iconKey),
                    members =
                        if (existing == null) {
                            emptySet()
                        } else {
                            chats.mapNotNullTo(mutableSetOf()) { if (it.folderId == folderId) it.bufferId else null }
                        },
                ),
            )
        }
        var iconPicker by remember(folderId) { mutableStateOf(false) }
        var error by remember(folderId) { mutableStateOf(false) }
        val query = draft.query
        val visible =
            remember(chats, query) {
                if (query.isBlank()) chats else chats.filter { it.displayName.contains(query, ignoreCase = true) || it.networkName.contains(query, ignoreCase = true) }
            }
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            OutlinedTextField(draft.name, {
                draft = draft.copy(name = it)
                error = false
            }, colors = readableOutlinedTextFieldColors(), label = { Text(stringResource(R.string.folders_name)) }, isError = error, modifier = Modifier.fillMaxWidth().testTag("folder_name"))
            Button(onClick = { iconPicker = true }, modifier = Modifier.padding(vertical = 8.dp).testTag("folder_icon_picker")) {
                FolderIcon(draft.icon, null, Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.folders_choose_icon))
            }
            OutlinedTextField(draft.query, { draft = draft.copy(query = it) }, colors = readableOutlinedTextFieldColors(), label = { Text(stringResource(R.string.folders_search_chats)) }, modifier = Modifier.fillMaxWidth().testTag("folder_search"))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(visible, key = ChatListRow::bufferId) { chat ->
                    val checked = chat.bufferId in draft.members
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .background(if (checked) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                                .toggleable(value = checked, role = Role.Checkbox) { selected ->
                                    draft = draft.copy(members = if (selected) draft.members + chat.bufferId else draft.members - chat.bufferId)
                                }.padding(vertical = 8.dp),
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            modifier = Modifier.size(48.dp),
                            colors =
                                androidx.compose.material3.CheckboxDefaults
                                    .colors(checkedColor = MaterialTheme.colorScheme.onSecondaryContainer, checkmarkColor = MaterialTheme.colorScheme.secondaryContainer),
                        )
                        Column {
                            Text(chat.displayName, color = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
                            Text(chat.networkName, style = MaterialTheme.typography.bodySmall, color = if (checked) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Button(
                onClick = { onSave(draft.name, draft.icon, draft.members) { success -> if (success) onBack() else error = true } },
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag("folder_save"),
            ) { Text(stringResource(R.string.action_save)) }
        }
        if (iconPicker) {
            FolderIconPicker(draft.icon, {
                draft = draft.copy(icon = it)
                iconPicker = false
            }, { iconPicker = false })
        }
    }
    if (existing != null && confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.folders_delete)) },
            text = { Text(stringResource(R.string.folders_delete_confirm)) },
            confirmButton = { TextButton(colors = readableTextButtonColors(), onClick = { onDelete(onBack) }) { Text(stringResource(R.string.folders_delete)) } },
            dismissButton = { TextButton(colors = readableTextButtonColors(), onClick = { confirmDelete = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

@Composable
fun FolderIconPicker(
    selected: FolderIconRef,
    onSelect: (FolderIconRef) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folders_choose_icon)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 420.dp).selectableGroup()) {
                item {
                    OutlinedTextField(query, { query = it }, colors = readableOutlinedTextFieldColors(), label = { Text(stringResource(R.string.action_search)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                items(folderIconChoices(query), key = { "${it.ref.kind}:${it.ref.key}" }) { choice: FolderIconChoice ->
                    io.github.trevarj.motd.ui.settings.RadioRow(
                        label = choice.name,
                        selected = choice.ref == selected,
                        enabled = true,
                        onClick = { onSelect(choice.ref) },
                        supportingContent = { FolderIcon(choice.ref, null, Modifier.size(24.dp)) },
                    )
                }
            }
        },
        confirmButton = {},
    )
}
