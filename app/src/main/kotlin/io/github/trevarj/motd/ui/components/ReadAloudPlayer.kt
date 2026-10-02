package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LastPage
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.ReadAloudSelection
import io.github.trevarj.motd.audio.ReadAloudState
import io.github.trevarj.motd.audio.ReadAloudStatus
import io.github.trevarj.motd.audio.ReadAloudVoices
import io.github.trevarj.motd.audio.readAloudBody
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import kotlin.math.roundToInt

@Composable
fun ReadAloudPlayer(
    state: ReadAloudState,
    onPrevious: () -> Unit,
    onPauseResume: () -> Unit,
    onSkip: () -> Unit,
    onLatest: () -> Unit,
    onStop: () -> Unit,
    onOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.enabled && !state.previewing && state.error == null) return
    val status =
        state.error ?: stringResource(
            when {
                state.paused -> R.string.read_aloud_paused
                state.status == ReadAloudStatus.PREPARING -> R.string.read_aloud_preparing
                state.status == ReadAloudStatus.PLAYING -> R.string.read_aloud_playing
                state.status == ReadAloudStatus.GAP -> R.string.read_aloud_gap
                else -> R.string.read_aloud_waiting
            },
        )
    Surface(
        modifier = modifier.fillMaxWidth().testTag("read_aloud_player"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.RecordVoiceOver, null, Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(
                        state.current?.sender?.let(::readAloudBody) ?: stringResource(R.string.read_aloud_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.preview.isNotBlank()) Text(state.preview, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MaterialTheme.typography.labelSmall, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.total > 0) {
                        Text(
                            stringResource(R.string.read_aloud_position, state.position, state.total, state.pending) +
                                if (state.skipped > 0) " · " + stringResource(R.string.read_aloud_skipped, state.skipped) else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onStop, modifier = Modifier.size(48.dp).testTag("read_aloud_stop")) {
                    Icon(Icons.Filled.Stop, stringResource(R.string.read_aloud_stop))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = state.canPrevious, modifier = Modifier.size(48.dp).testTag("read_aloud_previous")) {
                    Icon(Icons.Filled.SkipPrevious, stringResource(R.string.read_aloud_previous))
                }
                Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
                    IconButton(onClick = onPauseResume, enabled = state.enabled, modifier = Modifier.size(48.dp).testTag("read_aloud_pause_resume")) {
                        Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, stringResource(if (state.paused) R.string.read_aloud_resume else R.string.read_aloud_pause))
                    }
                }
                IconButton(onClick = onSkip, enabled = state.canSkip, modifier = Modifier.size(48.dp).testTag("read_aloud_skip")) {
                    Icon(Icons.Filled.SkipNext, stringResource(R.string.read_aloud_skip))
                }
                IconButton(onClick = onLatest, enabled = state.canLatest, modifier = Modifier.size(48.dp).testTag("read_aloud_latest")) {
                    Icon(Icons.AutoMirrored.Outlined.LastPage, stringResource(R.string.read_aloud_latest))
                }
                IconButton(onClick = onOptions, modifier = Modifier.size(48.dp).testTag("read_aloud_options")) {
                    Icon(Icons.Outlined.Tune, stringResource(R.string.read_aloud_options))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudVoiceOptions(
    config: ReadAloudSelection,
    voices: ReadAloudVoices,
    onSave: (ReadAloudSelection) -> Unit,
    onPreview: (ReadAloudSelection) -> Unit,
    onDismiss: () -> Unit,
    onStopPreview: () -> Unit,
    onReloadVoices: () -> Unit,
    previewing: Boolean = false,
    error: String? = null,
) {
    var draft by remember(config) { mutableStateOf(config.options) }
    var voiceMenu by remember { mutableStateOf(false) }
    LaunchedEffect(config) { onReloadVoices() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetSystemBars()
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .testTag("read_aloud_voice_options"),
        ) {
            Text(stringResource(R.string.read_aloud_options), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.read_aloud_voice_disclosure), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
            Text(stringResource(R.string.read_aloud_voice), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { voiceMenu = true }, enabled = !voices.loading && voices.voices.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("read_aloud_voice")) {
                val selected = voices.voices.firstOrNull { it.id == draft.voice }
                Text(
                    selected?.let { voiceLabel(it) } ?: stringResource(
                        when {
                            draft.voice != null -> R.string.read_aloud_unavailable_voice
                            else -> R.string.read_aloud_default_voice
                        },
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }, modifier = Modifier.heightIn(max = 280.dp).testTag("read_aloud_voice_menu")) {
                    voices.voices.forEach { voice ->
                        DropdownMenuItem(modifier = Modifier.testTag("read_aloud_voice_${voice.id}"), text = { Text(voiceLabel(voice)) }, onClick = {
                            draft = draft.copy(voice = voice.id)
                            voiceMenu = false
                        })
                    }
                }
            }
            Text(stringResource(R.string.read_aloud_gender_disclosure), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
            Text(stringResource(R.string.read_aloud_rate, (draft.rate * 100).roundToInt()))
            Slider(value = draft.rate, onValueChange = { draft = draft.copy(rate = it) }, valueRange = .7f..1.3f, modifier = Modifier.testTag("read_aloud_rate"))
            Text(stringResource(R.string.read_aloud_pitch, (draft.pitch * 100).roundToInt()))
            Slider(value = draft.pitch, onValueChange = { draft = draft.copy(pitch = it) }, valueRange = .7f..1.3f, modifier = Modifier.testTag("read_aloud_pitch"))
            Text(stringResource(R.string.read_aloud_gap_option, draft.gapMs))
            Slider(value = draft.gapMs.toFloat(), onValueChange = { draft = draft.copy(gapMs = it.roundToInt()) }, valueRange = 0f..1_000f, modifier = Modifier.testTag("read_aloud_gap"))
            if (voices.loading) Text(stringResource(R.string.read_aloud_preparing))
            (error ?: voices.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (previewing) onStopPreview() else onPreview(config.copy(options = draft)) }, enabled = previewing || !voices.loading && voices.voices.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp).testTag("read_aloud_preview")) {
                    Text(stringResource(if (previewing) R.string.read_aloud_stop_preview else R.string.read_aloud_preview))
                }
                Button(onClick = {
                    onSave(config.copy(options = draft))
                    onDismiss()
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("read_aloud_save")) {
                    Text(stringResource(R.string.read_aloud_save))
                }
            }
        }
    }
}

@Composable
private fun voiceLabel(voice: io.github.trevarj.motd.audio.ReadAloudVoice): String =
    listOfNotNull(
        voice.name,
        voice.locale,
    ).joinToString(" · ")
