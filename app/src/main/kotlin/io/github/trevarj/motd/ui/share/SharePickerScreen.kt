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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.ui.chat.ComposerDraftStore
import io.github.trevarj.motd.ui.chat.ComposerPrefill
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

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

@HiltViewModel
class SharePickerViewModel
    @Inject
    constructor(
        private val bufferRepository: BufferRepository,
        private val store: PendingShareStore,
        private val draftStore: ComposerDraftStore,
    ) : ViewModel() {
        private val queryState = MutableStateFlow("")
        val query: StateFlow<String> = queryState.asStateFlow()
        private val share = store.peek()

        // Set once the payload leaves this screen (picked or explicitly dismissed) so teardown never
        // discards someone else's parked share.
        private var handled = false

        val targets: StateFlow<List<ChatListRow>> =
            bufferRepository
                .observeChatList()
                .combine(queryState, ::filterShareTargets)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun onQueryChange(value: String) {
            queryState.value = value
        }

        /** Picking only hands off a draft or file; it never sends a message. */
        suspend fun pick(bufferId: Long): Boolean {
            val current = share ?: return false
            if (!store.consumeIfUnchanged(current)) return false
            when (current) {
                is PendingShare.Text -> draftStore.push(bufferId, ComposerPrefill(current.text))
                is PendingShare.File -> store.assignFile(bufferId, current)
            }
            handled = true
            return true
        }

        fun cancel() = discardUnhandled()

        /** System back / pop tears the screen down without a cancel callback; clean up here too. */
        override fun onCleared() = discardUnhandled()

        private fun discardUnhandled() {
            if (handled) return
            handled = true
            share?.let(store::consumeIfUnchanged)
        }
    }

/** Chat picker for an inbound share. Picking never sends: it prefills or opens the upload sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePickerScreen(
    onPicked: (bufferId: Long) -> Unit,
    onCancel: () -> Unit,
    viewModel: SharePickerViewModel = hiltViewModel(),
) {
    val rows by viewModel.targets.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                colors =
                    androidx.compose.material3.TopAppBarDefaults
                        .topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                title = {
                    Text(stringResource(R.string.share_picker_title))
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
                        text = stringResource(R.string.share_picker_empty),
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
                                        onPicked(row.bufferId)
                                    } else {
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
