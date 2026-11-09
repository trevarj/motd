package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.ChatFolderEntity
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.IgnoredAutoGroupPatternEntity
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.ChatFolderRepository
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.ui.components.FolderIcon
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.trevarj.motd.ui.components.EffectDropdownMenu as DropdownMenu
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

data class AutoGroupState(
    val chats: List<ChatListRow> = emptyList(),
    val folders: List<ChatFolderEntity> = emptyList(),
    val ignored: List<IgnoredAutoGroupPatternEntity> = emptyList(),
)

@HiltViewModel
class AutoGroupViewModel
    @Inject
    constructor(
        private val folderRepository: ChatFolderRepository,
        buffers: BufferRepository,
    ) : ViewModel() {
        val state: StateFlow<AutoGroupState> =
            combine(buffers.observeChatList(), folderRepository.observeFolders(), folderRepository.observeIgnored(), ::AutoGroupState)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoGroupState())

        fun approve(
            proposal: AutoGroupProposal,
            checked: Collection<Long>,
            destinationFolderId: Long?,
            name: String,
            icon: FolderIconRef,
            done: (Boolean) -> Unit,
        ) = viewModelScope.launch {
            val result =
                runCatching {
                    require(canApproveAutoGroup(destinationFolderId, checked.size)) { "Select enough chats for this destination." }
                    if (destinationFolderId == null) {
                        folderRepository.createAndAssign(name, icon, checked)
                    } else {
                        folderRepository.assign(checked, destinationFolderId)
                    }
                }
            done(result.isSuccess)
        }

        fun reject(proposal: AutoGroupProposal) = viewModelScope.launch { folderRepository.rejectAutoGroup(proposal.networkId, proposal.normalizedPrefix) }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoGroupScreen(
    networkId: Long?,
    onBack: () -> Unit,
    viewModel: AutoGroupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val proposals = autoGroupProposals(state.chats, state.ignored, networkId)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.testTag("screen_auto_group"),
        topBar = {
            TopAppBar(
                colors =
                    androidx.compose.material3.TopAppBarDefaults
                        .topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                title = { Text(stringResource(R.string.folders_auto_group)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
            )
        },
    ) { padding ->
        if (proposals.isEmpty()) {
            Text(stringResource(R.string.folders_auto_group_empty), modifier = Modifier.padding(padding).padding(24.dp))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(proposals, key = { "${it.networkId}:${it.normalizedPrefix}" }) { proposal ->
                    AutoGroupProposalCard(proposal, state.folders, viewModel::approve, viewModel::reject)
                }
            }
        }
    }
}

@Composable
internal fun AutoGroupProposalCard(
    proposal: AutoGroupProposal,
    folders: List<ChatFolderEntity>,
    onApprove: (AutoGroupProposal, Collection<Long>, Long?, String, FolderIconRef, (Boolean) -> Unit) -> Unit,
    onReject: (AutoGroupProposal) -> Unit,
) {
    var expanded by remember(proposal.normalizedPrefix) { mutableStateOf(true) }
    var checked by remember(proposal.normalizedPrefix) { mutableStateOf<Set<Long>>(proposal.chats.mapTo(mutableSetOf(), ChatListRow::bufferId)) }
    var name by remember(proposal.normalizedPrefix) { mutableStateOf(proposal.suggestedName) }
    var icon by remember(proposal.normalizedPrefix) { mutableStateOf(proposal.icon) }
    var destination by remember(proposal.normalizedPrefix) { mutableStateOf<Long?>(null) }
    var destinationMenu by remember { mutableStateOf(false) }
    var iconPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    Card(
        colors =
            androidx.compose.material3.CardDefaults
                .cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("auto_group_${proposal.networkId}_${proposal.normalizedPrefix}"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
                FolderIcon(icon, null, Modifier.size(24.dp))
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(name, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.folders_matched_prefix, proposal.matchedPrefix))
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
            }
            if (expanded) {
                proposal.chats.forEach { chat ->
                    Row(modifier = Modifier.fillMaxWidth().clickable { checked = if (chat.bufferId in checked) checked - chat.bufferId else checked + chat.bufferId }) {
                        Checkbox(chat.bufferId in checked, { value -> checked = if (value) checked + chat.bufferId else checked - chat.bufferId })
                        Text(chat.displayName, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            OutlinedTextField(name, {
                name = it
                error = false
            }, enabled = destination == null, label = { Text(stringResource(R.string.folders_name)) }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { destinationMenu = true }) {
                Text(destination?.let { id -> folders.firstOrNull { it.id == id }?.displayName } ?: stringResource(R.string.folders_new_destination))
            }
            DropdownMenu(destinationMenu, { destinationMenu = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                DropdownMenuItem(text = { Text(stringResource(R.string.folders_new_destination)) }, onClick = {
                    destination = null
                    destinationMenu = false
                })
                folders.forEach { folder ->
                    DropdownMenuItem(text = { Text(folder.displayName) }, onClick = {
                        destination = folder.id
                        destinationMenu = false
                    })
                }
            }
            if (destination == null) {
                TextButton(onClick = { iconPicker = true }) { Text(stringResource(R.string.folders_choose_icon)) }
            }
            Row {
                Button(
                    onClick = { onApprove(proposal, checked, destination, name, icon) { success -> error = !success } },
                    modifier = Modifier.testTag("auto_group_approve"),
                ) { Text(stringResource(R.string.folders_approve)) }
                OutlinedButton(onClick = { onReject(proposal) }, modifier = Modifier.padding(start = 8.dp).testTag("auto_group_reject")) {
                    Text(stringResource(R.string.folders_reject))
                }
            }
            if (error) Text(stringResource(R.string.folders_operation_failed), color = MaterialTheme.colorScheme.error)
        }
    }
    if (iconPicker) {
        FolderIconPicker(icon, {
            icon = it
            iconPicker = false
        }, { iconPicker = false })
    }
}
