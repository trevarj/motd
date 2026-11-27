package io.github.trevarj.motd.ui.saved

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.saved.SavedMessage
import io.github.trevarj.motd.data.saved.SavedMessageSource
import io.github.trevarj.motd.data.saved.SavedMessagesRepository
import io.github.trevarj.motd.ui.components.EmojiSnackbarHost
import io.github.trevarj.motd.ui.components.EmptyState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

@HiltViewModel
internal class SavedMessagesViewModel
    @Inject
    constructor(
        private val repository: SavedMessagesRepository,
    ) : ViewModel() {
        val messages = repository.observe()

        fun observeSaved(eventId: Long): Flow<Boolean> = repository.observeSaved(eventId)

        fun setSaved(
            eventId: Long,
            saved: Boolean,
        ) {
            viewModelScope.launch { repository.setSaved(eventId, saved) }
        }

        fun remove(id: Long) {
            viewModelScope.launch { repository.remove(id) }
        }

        suspend fun resolveSource(id: Long): SavedMessageSource? = repository.resolveSource(id)
    }

@Composable
internal fun SavedMessagesScreen(
    onBack: () -> Unit,
    onOpenSource: (SavedMessageSource) -> Unit,
    viewModel: SavedMessagesViewModel = hiltViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle(initialValue = null)
    SavedMessagesContent(messages, onBack, onOpenSource, viewModel::resolveSource, viewModel::remove)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedMessagesContent(
    messages: List<SavedMessage>?,
    onBack: () -> Unit,
    onOpenSource: (SavedMessageSource) -> Unit,
    resolveSource: suspend (Long) -> SavedMessageSource?,
    onRemove: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val unavailable = stringResource(R.string.saved_messages_source_unavailable)
    val openSource by rememberUpdatedState(onOpenSource)
    var opening by remember { mutableStateOf<Job?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { opening?.cancel() }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("saved_messages"),
        containerColor = scheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surfaceContainerLowest),
                title = { Text(stringResource(R.string.saved_messages_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { EmojiSnackbarHost(snackbar) },
    ) { padding ->
        if (messages?.isEmpty() == true) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Outlined.StarBorder,
                    title = stringResource(R.string.saved_messages_empty_title),
                    message = stringResource(R.string.saved_messages_empty_message),
                    modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(min = maxHeight).testTag("saved_messages_empty"),
                )
            }
        } else {
            val locale = LocalConfiguration.current.locales[0]
            val zone = ZoneId.systemDefault()
            val dateFormat =
                remember(locale, zone) {
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(zone)
                }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(messages.orEmpty(), key = { it.id }) { message ->
                    SavedMessageRow(
                        message = message,
                        date = remember(message.serverTime, dateFormat) { dateFormat.format(Instant.ofEpochMilli(message.serverTime)) },
                        onOpen = {
                            opening?.cancel()
                            opening =
                                scope.launch {
                                    val source = resolveSource(message.id)
                                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
                                    if (source == null) snackbar.showSnackbar(unavailable) else openSource(source)
                                }
                        },
                        onRemove = { onRemove(message.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedMessageRow(
    message: SavedMessage,
    date: String,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().testTag("saved_message_${message.id}"),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(message.sender, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.saved_messages_conversation, message.networkName, message.conversationName),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove, modifier = Modifier.testTag("saved_message_remove_${message.id}")) {
                    Icon(Icons.Filled.Close, stringResource(R.string.saved_messages_remove))
                }
            }
            if (message.text == null) {
                Text(stringResource(R.string.saved_messages_redacted), color = scheme.onSurfaceVariant)
            } else {
                Text(message.text)
            }
            Text(date, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}
