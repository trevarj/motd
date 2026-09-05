package io.github.trevarj.motd.ui.share

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.agentwire.AgentwirePrefs
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.irc.agentwire.AgentwireTopicParse
import io.github.trevarj.motd.irc.agentwire.parseAgentwireTopicResult
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Share targets in the chat list's own order (pinned first, then recency). Archived and SERVER
 * buffers are never share destinations; the DAO already excludes SERVER, so that check is
 * defensive. A blank query keeps everything.
 */
internal fun filterShareTargets(
    rows: List<ChatListRow>,
    query: String,
): List<ChatListRow> {
    val needle = query.trim()
    return rows.filter { row ->
        !row.archived &&
            row.type != BufferType.SERVER &&
            (needle.isEmpty() || row.displayName.contains(needle, ignoreCase = true))
    }
}

internal fun isAgentwireShareTarget(buffer: BufferEntity?): Boolean =
    buffer != null &&
        buffer.type == BufferType.CHANNEL &&
        !buffer.archived &&
        buffer.topic?.let(::parseAgentwireTopicResult) is AgentwireTopicParse.Valid

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SharePickerViewModel
    @Inject
    constructor(
        private val bufferRepository: BufferRepository,
        private val store: PendingShareStore,
        private val draftStore: ComposerDraftStore,
        private val agentwirePrefs: AgentwirePrefs,
    ) : ViewModel() {
        private val queryState = MutableStateFlow("")
        val query: StateFlow<String> = queryState.asStateFlow()
        private val share = store.peek()
        val contextShare = share as? PendingShare.AgentContext
        private val _targetUnavailable = MutableStateFlow(false)
        val targetUnavailable: StateFlow<Boolean> = _targetUnavailable.asStateFlow()

        // Set once the payload leaves this screen (picked or explicitly dismissed) so teardown never
        // discards someone else's parked share.
        private var handled = false

        val targets: StateFlow<List<ChatListRow>> =
            bufferRepository
                .observeChatList()
                .flatMapLatest { rows ->
                    if (contextShare == null) {
                        flowOf(rows)
                    } else {
                        val channels = rows.filter { !it.archived && it.type == BufferType.CHANNEL }
                        if (channels.isEmpty()) {
                            flowOf(emptyList())
                        } else {
                            combine(
                                channels.map { row ->
                                    bufferRepository.observeBuffer(row.bufferId).map { buffer ->
                                        row.takeIf { isAgentwireShareTarget(buffer) }
                                    }
                                },
                            ) { it.filterNotNull() }
                        }
                    }
                }.combine(agentwirePrefs.enabled) { rows, enabled ->
                    if (contextShare != null && !enabled) emptyList() else rows
                }.combine(queryState, ::filterShareTargets)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun onQueryChange(value: String) {
            queryState.value = value
        }

        /** Picking only hands off a draft; sensitive context can never use the ordinary text path. */
        suspend fun pick(bufferId: Long): Boolean {
            val current = share ?: return false
            if (store.peek() !== current) return false
            if (current is PendingShare.AgentContext) {
                if (
                    !agentwirePrefs.enabled.first() ||
                    !isAgentwireShareTarget(bufferRepository.observeBuffer(bufferId).first()) ||
                    !store.assignAgentContext(bufferId, current)
                ) {
                    _targetUnavailable.value = true
                    return false
                }
                if (!store.consumeIfUnchanged(current)) {
                    store.clearAgentContext(bufferId, current)
                    return false
                }
            } else {
                if (!store.consumeIfUnchanged(current)) return false
                when (current) {
                    is PendingShare.Text -> draftStore.push(bufferId, current.text)
                    is PendingShare.File -> store.assignFile(bufferId, current)
                    is PendingShare.AgentContext -> error("Context uses the protected handoff")
                }
            }
            handled = true
            return true
        }

        fun cancel() = discardUnhandled()

        fun discardContext() {
            contextShare?.let(store::consumeIfUnchanged)
            handled = true
        }

        /** System back / pop tears the screen down without a cancel callback; clean up here too. */
        override fun onCleared() = discardUnhandled()

        private fun discardUnhandled() {
            if (handled) return
            handled = true
            if (contextShare == null) share?.let(store::consumeIfUnchanged)
        }
    }

/** Chat picker for an inbound share. Picking never sends: it prefills or opens the upload sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePickerScreen(
    onPicked: (bufferId: Long, isAgentContext: Boolean) -> Unit,
    onCancel: () -> Unit,
    viewModel: SharePickerViewModel = hiltViewModel(),
) {
    val rows by viewModel.targets.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val targetUnavailable by viewModel.targetUnavailable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val contextShare = viewModel.contextShare
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(if (contextShare == null) R.string.share_picker_title else R.string.agent_context_picker_title))
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.cancel()
                            onCancel()
                        },
                        modifier = Modifier.testTag("share_picker_close"),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (contextShare != null) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(contextShare.sourceLabel, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.agent_context_warning), modifier = Modifier.testTag("agent_context_warning"))
                    Text(contextShare.coverage, modifier = Modifier.testTag("agent_context_coverage"))
                    TextButton(
                        onClick = {
                            viewModel.discardContext()
                            onCancel()
                        },
                        modifier = Modifier.testTag("agent_context_discard"),
                    ) {
                        Text(stringResource(R.string.agentwire_context_discard))
                    }
                }
            }
            if (targetUnavailable) {
                Text(
                    stringResource(R.string.agent_context_target_unavailable),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp).testTag("agent_context_target_unavailable"),
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("share_picker_search"),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.share_picker_search)) },
            )
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(if (contextShare == null) R.string.share_picker_empty else R.string.agent_context_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag("share_picker_list")) {
                    items(rows, key = { it.bufferId }) { row ->
                        ChatListRowItem(
                            row = row,
                            showNetworkChip = true,
                            onClick = {
                                scope.launch {
                                    if (viewModel.pick(row.bufferId)) {
                                        onPicked(row.bufferId, contextShare != null)
                                    } else if (contextShare == null) {
                                        onCancel()
                                    }
                                }
                            },
                            onLongClick = {},
                        )
                    }
                }
            }
        }
    }
}
