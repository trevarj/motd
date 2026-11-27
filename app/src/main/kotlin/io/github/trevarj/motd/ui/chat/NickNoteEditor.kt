package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.notes.MAX_NICK_NOTE_LENGTH
import io.github.trevarj.motd.data.notes.NickNoteKey
import io.github.trevarj.motd.data.notes.NickNoteKind
import io.github.trevarj.motd.data.notes.NickNotesRepository
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text

internal data class NickNoteTarget(
    val key: NickNoteKey,
    val displayIdentity: String,
)

@HiltViewModel
internal class NickNoteEditorViewModel
    @Inject
    constructor(
        private val repository: NickNotesRepository,
    ) : ViewModel() {
        suspend fun read(key: NickNoteKey): String? = repository.read(key)

        suspend fun save(
            key: NickNoteKey,
            text: String,
        ) = repository.save(key, text)
    }

@Composable
internal fun NickNoteEditor(
    target: NickNoteTarget,
    onDismiss: () -> Unit,
    viewModel: NickNoteEditorViewModel = hiltViewModel(),
) {
    key(target.key) {
        val noteKey = target.key
        val draftSaver =
            remember(noteKey) {
                val owner = listOf(noteKey.networkId, noteKey.kind.name, noteKey.value)
                listSaver<String?, Any>(
                    save = { if (it == null) emptyList() else owner + it },
                    restore = { if (it.take(3) == owner) it[3] as String else null },
                )
            }
        var draft by rememberSaveable(stateSaver = draftSaver) { mutableStateOf<String?>(null) }
        var original by remember { mutableStateOf<String?>(null) }
        var readFailed by remember { mutableStateOf(false) }
        var readAttempt by remember { mutableStateOf(0) }
        var writing by remember { mutableStateOf(false) }
        var writeFailed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val dismiss by rememberUpdatedState(onDismiss)

        LaunchedEffect(viewModel, readAttempt) {
            readFailed = false
            try {
                original = viewModel.read(noteKey).orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                readFailed = true
            }
        }

        fun write(text: String) {
            if (writing) return
            writing = true
            writeFailed = false
            scope.launch {
                try {
                    viewModel.save(noteKey, text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    writeFailed = true
                    return@launch
                } finally {
                    writing = false
                }
                ensureActive()
                dismiss()
            }
        }

        val text = draft ?: original.orEmpty()
        val source =
            when (noteKey.kind) {
                NickNoteKind.ACCOUNT -> R.string.nick_note_account_source
                NickNoteKind.NICKNAME -> R.string.nick_note_nickname_source
            }
        AlertDialog(
            onDismissRequest = { if (!writing) dismiss() },
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp)
                    .semantics { testTagsAsResourceId = true },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            title = { Text(stringResource(R.string.nick_private_note)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(source, target.displayIdentity), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.nick_note_local_scope))
                        when {
                            original != null -> {
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { if (it.length <= MAX_NICK_NOTE_LENGTH) draft = it },
                                    enabled = !writing,
                                    label = { Text(stringResource(R.string.nick_note_field)) },
                                    supportingText = {
                                        Text(stringResource(R.string.nick_note_character_count, text.length, MAX_NICK_NOTE_LENGTH))
                                    },
                                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                    minLines = 3,
                                    maxLines = 6,
                                    modifier = Modifier.fillMaxWidth().testTag("nick_note_field"),
                                )
                            }

                            readFailed -> {
                                Text(stringResource(R.string.nick_note_read_failed), color = MaterialTheme.colorScheme.error)
                            }

                            else -> {
                                Text(stringResource(R.string.nick_note_loading))
                            }
                        }
                    }
                    if (writing) Text(stringResource(R.string.nick_note_saving))
                    if (writeFailed) {
                        Text(
                            stringResource(R.string.nick_note_write_failed),
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                if (readFailed) {
                    TextButton(colors = readableTextButtonColors(), onClick = { readAttempt++ }) {
                        Text(stringResource(R.string.chat_retry))
                    }
                } else {
                    TextButton(
                        colors = readableTextButtonColors(),
                        enabled = original != null && !writing && text.isNotBlank(),
                        onClick = { write(text) },
                    ) { Text(stringResource(R.string.action_save)) }
                }
            },
            dismissButton = {
                if (!original.isNullOrBlank()) {
                    TextButton(colors = readableTextButtonColors(), enabled = !writing, onClick = { write("") }) {
                        Text(stringResource(R.string.action_delete))
                    }
                }
                TextButton(colors = readableTextButtonColors(), enabled = !writing, onClick = { if (!writing) dismiss() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
