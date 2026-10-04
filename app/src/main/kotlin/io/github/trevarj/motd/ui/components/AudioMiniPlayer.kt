package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.contextLabel
import io.github.trevarj.motd.audio.formatAudioDuration
import io.github.trevarj.motd.ui.theme.SheetSystemBars

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioMiniPlayer(
    state: AudioPlaybackState,
    onToggle: () -> Unit,
    onCancelLoading: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenOrigin: (AudioPlaybackOrigin) -> Unit,
    modifier: Modifier = Modifier,
    onSpeed: (Float) -> Unit = {},
    includeNetwork: Boolean = false,
) {
    val attachment = state.attachment ?: return
    if (state.activeId == null) return
    var showDetails by remember(state.activeId) { mutableStateOf(false) }
    val duration = state.durationMs ?: attachment.durationMs
    val played = duration?.takeIf { it > 0 }?.let { state.positionMs.toFloat() / it } ?: 0f
    val controlColor = MaterialTheme.colorScheme.onPrimaryContainer
    val originLabel = state.origin?.contextLabel(state.networkName, includeNetwork)
    val context =
        if (attachment.voice) {
            originLabel ?: attachment.title
        } else {
            listOfNotNull(attachment.title, originLabel).joinToString(" · ")
        }
    val status =
        if (state.error != null) {
            "Couldn’t play"
        } else {
            "${formatAudioDuration(state.positionMs)} / ${formatAudioDuration(duration)}"
        }

    Surface(
        modifier = modifier.fillMaxWidth().height(MINI_PLAYER_HEIGHT).testTag("audio_mini_player"),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(MINI_PLAYER_HEIGHT), contentAlignment = Alignment.Center) {
                RadialPlaybackWave(
                    playbackId = state.activeId,
                    waveform = state.waveform ?: attachment.waveform,
                    positionMs = state.positionMs,
                    durationMs = duration,
                    playing = state.playing,
                    modifier = Modifier.fillMaxSize().testTag("audio_mini_radial_wave"),
                )
                Surface(
                    modifier = Modifier.size(28.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {}
                IconButton(
                    onClick =
                        when {
                            state.loading -> onCancelLoading
                            state.error != null -> onRetry
                            else -> onToggle
                        },
                    modifier =
                        Modifier.fillMaxSize().testTag(
                            when {
                                state.loading -> "audio_mini_cancel_loading"
                                state.error != null -> "audio_mini_retry"
                                else -> "audio_mini_toggle"
                            },
                        ),
                ) {
                    when {
                        state.loading -> {
                            state.loadingFraction?.let { fraction ->
                                CircularProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.size(16.dp),
                                    color = controlColor,
                                    strokeWidth = 2.dp,
                                )
                            } ?: CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = controlColor,
                                strokeWidth = 2.dp,
                            )
                        }

                        state.error != null -> {
                            Icon(Icons.Filled.Refresh, "Retry audio", Modifier.size(18.dp), tint = controlColor)
                        }

                        state.playing -> {
                            Icon(Icons.Filled.Pause, "Pause audio", Modifier.size(18.dp), tint = controlColor)
                        }

                        else -> {
                            Icon(Icons.Filled.PlayArrow, "Play audio", Modifier.size(18.dp), tint = controlColor)
                        }
                    }
                }
            }
            Spacer(Modifier.width(2.dp))
            Text(
                text = context,
                modifier =
                    Modifier
                        .weight(1f)
                        .combinedClickable(
                            onClick = { state.origin?.let(onOpenOrigin) },
                            onLongClick = { showDetails = true },
                        ).testTag("audio_mini_context"),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            WaveformScrubber(
                value = played,
                onValueChange = { fraction -> duration?.let { onSeek((fraction * it).toLong()) } },
                onValueChangeFinished = {},
                enabled = state.canSeek,
                waveform = state.waveform,
                modifier = Modifier.width(64.dp).testTag("audio_mini_scrubber"),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = status,
                modifier = Modifier.widthIn(max = 88.dp),
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (state.error == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (attachment.voice) {
                Spacer(Modifier.width(4.dp))
                Surface(
                    onClick = { onSpeed(nextVoiceSpeed(state.speed)) },
                    modifier = Modifier.testTag("audio_mini_speed"),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) {
                    Text(
                        "${state.speed.cleanSpeed()}x",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp).testTag("audio_mini_close")) {
                Icon(Icons.Filled.Close, "Close audio player", Modifier.size(18.dp))
            }
        }
    }

    if (showDetails) {
        ModalBottomSheet(onDismissRequest = { showDetails = false }) {
            SheetSystemBars()
            AudioDetailsSheet(
                attachment = attachment,
                origin = state.origin,
            )
        }
    }
}

private val MINI_PLAYER_HEIGHT = 48.dp
