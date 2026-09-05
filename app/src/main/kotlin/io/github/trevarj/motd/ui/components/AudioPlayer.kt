package io.github.trevarj.motd.ui.components

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.formatAudioDuration
import io.github.trevarj.motd.ui.chat.VoiceTranscriptFailureKind
import io.github.trevarj.motd.ui.chat.VoiceTranscriptState
import io.github.trevarj.motd.ui.chat.formatBytes
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdSizes
import io.github.trevarj.motd.ui.theme.SheetSystemBars

private const val MAX_COLLAPSED_AUDIO_PLAYERS = 3

/** Distinct glyph states for the play-button circle, so state changes crossfade instead of snap. */
private enum class AudioToggleGlyph { LOADING, ERROR, PLAYING, DOWNLOAD, PLAY }

@Composable
fun AudioAttachmentPlayers(
    attachments: List<AudioAttachment>,
    playbackState: AudioPlaybackState,
    networkId: Long?,
    isSelf: Boolean,
    onToggle: (AudioAttachment, Long?) -> Unit,
    onSeek: (AudioAttachment, Long) -> Unit,
    modifier: Modifier = Modifier,
    origin: AudioPlaybackOrigin? = null,
    derivedWaveforms: Map<String, AudioWaveform> = emptyMap(),
    cacheStatuses: Map<String, AudioCacheStatus> = emptyMap(),
    formattedTime: String? = null,
    pending: Boolean = false,
    failed: Boolean = false,
    onInspectCache: (AudioAttachment) -> Unit = {},
    onLongPress: (() -> Unit)? = null,
    reactions: List<ReactionChip> = emptyList(),
    onReact: (String) -> Unit = {},
    transcripts: Map<String, VoiceTranscriptState> = emptyMap(),
    transcriptionEnabled: Boolean = false,
    transcriptionReady: Boolean = false,
    onTranscribe: (AudioPlaybackRequest, Boolean) -> Unit = { _, _ -> },
    onCancelTranscription: (String) -> Unit = {},
) {
    var expanded by remember(attachments) { mutableStateOf(false) }
    val visible = if (expanded) attachments else attachments.take(MAX_COLLAPSED_AUDIO_PLAYERS)
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = MotdMotion.contentSize,
                    alignment = if (isSelf) Alignment.TopEnd else Alignment.TopStart,
                ),
        horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
    ) {
        if (attachments.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalAlignment = if (isSelf) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                visible.forEach { attachment ->
                    AudioAttachmentPlayer(
                        attachment = attachment,
                        playbackState = playbackState,
                        networkId = networkId,
                        origin = origin,
                        derivedWaveform = derivedWaveforms[attachment.playbackId],
                        cacheStatus = cacheStatuses[attachment.playbackId] ?: AudioCacheStatus.UNKNOWN,
                        transcript = transcripts[attachment.playbackId],
                        transcriptionEnabled = transcriptionEnabled,
                        transcriptionReady = transcriptionReady,
                        onTranscribe = onTranscribe,
                        onCancelTranscription = onCancelTranscription,
                        formattedTime = formattedTime,
                        pending = pending,
                        failed = failed,
                        onInspectCache = onInspectCache,
                        onToggle = onToggle,
                        onSeek = onSeek,
                        onLongPress = onLongPress,
                        modifier =
                            Modifier
                                .fillMaxWidth(0.82f)
                                .widthIn(max = 320.dp)
                                .testTag("audio_player"),
                    )
                }
                if (!expanded && attachments.size > MAX_COLLAPSED_AUDIO_PLAYERS) {
                    TextButton(
                        onClick = { expanded = true },
                        modifier = Modifier.testTag("audio_player_expand"),
                    ) {
                        Icon(Icons.Filled.ExpandMore, null)
                        Spacer(Modifier.width(4.dp))
                        Text("+${attachments.size - MAX_COLLAPSED_AUDIO_PLAYERS}")
                    }
                }
                ReactionRow(reactions = reactions, onReact = onReact, isSelf = isSelf)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioAttachmentPlayer(
    attachment: AudioAttachment,
    playbackState: AudioPlaybackState,
    networkId: Long?,
    origin: AudioPlaybackOrigin?,
    derivedWaveform: AudioWaveform?,
    cacheStatus: AudioCacheStatus,
    formattedTime: String?,
    pending: Boolean,
    failed: Boolean,
    onInspectCache: (AudioAttachment) -> Unit,
    onToggle: (AudioAttachment, Long?) -> Unit,
    onSeek: (AudioAttachment, Long) -> Unit,
    onLongPress: (() -> Unit)?,
    modifier: Modifier = Modifier,
    transcript: VoiceTranscriptState?,
    transcriptionEnabled: Boolean,
    transcriptionReady: Boolean,
    onTranscribe: (AudioPlaybackRequest, Boolean) -> Unit,
    onCancelTranscription: (String) -> Unit,
) {
    val context = LocalContext.current
    val active = playbackState.activeId == attachment.playbackId
    val playing = active && playbackState.playing
    val loading = active && playbackState.loading
    val error = playbackState.error.takeIf { active }
    val needsDownload = !active && cacheStatus != AudioCacheStatus.CACHED
    val duration = (if (active) playbackState.durationMs else attachment.durationMs) ?: attachment.durationMs
    val position = if (active) playbackState.positionMs else 0L
    // Do not replace the rendered peaks when playback starts or when analysis completes. The
    // seeded fallback is deterministic, and a newly available waveform is picked up next time
    // this message enters composition.
    val waveform =
        remember(attachment.playbackId, attachment.waveform) {
            attachment.waveform ?: derivedWaveform
        }
    var showDetails by remember { mutableStateOf(false) }
    var confirmHttp by remember { mutableStateOf(false) }
    var scrubValue by remember(attachment.playbackId) { mutableFloatStateOf(position.toFloat()) }
    var scrubbing by remember(attachment.playbackId) { mutableStateOf(false) }

    LaunchedEffect(active, position) {
        if (!scrubbing) scrubValue = position.toFloat()
    }

    LaunchedEffect(attachment.playbackId) {
        onInspectCache(attachment)
    }

    Surface(
        modifier =
            modifier.combinedClickable(
                onClick = {},
                onLongClick = onLongPress ?: { showDetails = true },
            ),
        shape = MotdShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(MotdSizes.touchTarget), contentAlignment = Alignment.Center) {
                if (attachment.voice) {
                    RadialPlaybackWave(
                        playbackId = attachment.playbackId,
                        waveform = playbackState.waveform ?: waveform,
                        positionMs = position,
                        durationMs = duration,
                        playing = playing,
                        modifier = Modifier.fillMaxSize().testTag("audio_player_radial_wave"),
                    )
                }
                IconButton(
                    onClick = {
                        if (attachment.cleartextHttp && !active) confirmHttp = true else onToggle(attachment, networkId)
                    },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                        Box(
                            Modifier.size(if (attachment.voice) 32.dp else 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            val glyph =
                                when {
                                    loading -> AudioToggleGlyph.LOADING
                                    error != null -> AudioToggleGlyph.ERROR
                                    playing -> AudioToggleGlyph.PLAYING
                                    needsDownload -> AudioToggleGlyph.DOWNLOAD
                                    else -> AudioToggleGlyph.PLAY
                                }
                            // Crossfade the glyph under the user's finger; the fixed container needs
                            // no size transform.
                            AnimatedContent(
                                targetState = glyph,
                                transitionSpec = {
                                    fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)
                                },
                                contentAlignment = Alignment.Center,
                                label = "audio_toggle",
                            ) { state ->
                                if (state == AudioToggleGlyph.LOADING) {
                                    playbackState.loadingFraction?.let { fraction ->
                                        CircularProgressIndicator(
                                            progress = { fraction },
                                            modifier = Modifier.size(20.dp),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            strokeWidth = 2.5.dp,
                                        )
                                    } ?: CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.5.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector =
                                            when (state) {
                                                AudioToggleGlyph.ERROR -> Icons.Filled.Refresh
                                                AudioToggleGlyph.PLAYING -> Icons.Filled.Pause
                                                AudioToggleGlyph.DOWNLOAD -> Icons.Outlined.Download
                                                else -> Icons.Filled.PlayArrow
                                            },
                                        contentDescription =
                                            when (state) {
                                                AudioToggleGlyph.ERROR -> "Retry audio"
                                                AudioToggleGlyph.PLAYING -> "Pause audio"
                                                AudioToggleGlyph.DOWNLOAD -> "Download audio"
                                                else -> "Play audio"
                                            },
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!attachment.voice) {
                    Text(
                        text = attachment.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                val scrubDuration = (duration ?: 1L).coerceAtLeast(1L)
                WaveformScrubber(
                    value = (scrubValue / scrubDuration).coerceIn(0f, 1f),
                    onValueChange = { fraction ->
                        scrubbing = true
                        scrubValue = fraction * scrubDuration
                    },
                    onValueChangeFinished = {
                        onSeek(attachment, scrubValue.toLong())
                        scrubbing = false
                    },
                    seed = attachment.playbackId,
                    enabled = active && !loading && error == null && duration != null && duration > 0,
                    waveform = waveform,
                    modifier = Modifier.testTag("audio_player_scrubber"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        error?.let { "Couldn’t play · $it" }
                            ?: "${formatAudioDuration(position)} / ${formatAudioDuration(duration)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (attachment.encrypted) {
                        Icon(
                            Icons.Outlined.Lock,
                            contentDescription = "Encrypted audio",
                            modifier = Modifier.padding(start = 4.dp).size(13.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    formattedTime?.let { time ->
                        Spacer(Modifier.width(6.dp))
                        MessageStatusIcon(isSelf = origin?.isSelf == true, pending = pending, failed = failed)
                        Text(
                            time,
                            style = MaterialTheme.typography.labelSmall,
                            color =
                                if (failed) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            maxLines = 1,
                        )
                    }
                    if (attachment.cleartextHttp) {
                        Icon(
                            Icons.Outlined.Warning,
                            null,
                            modifier = Modifier.padding(start = 4.dp).size(14.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    IconButton(
                        onClick = { showDetails = true },
                        modifier = Modifier.size(28.dp).testTag("audio_player_details"),
                    ) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "Audio details",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmHttp) {
        AlertDialog(
            onDismissRequest = { confirmHttp = false },
            icon = { Icon(Icons.Outlined.Warning, null) },
            title = { Text("Play cleartext audio?") },
            text = { Text("This link uses HTTP. Anyone on the network path may see or modify it.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmHttp = false
                    onToggle(attachment, networkId)
                }) {
                    Text("Play")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmHttp = false }) { Text("Cancel") }
            },
        )
    }

    if (showDetails) {
        ModalBottomSheet(
            onDismissRequest = { showDetails = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            SheetSystemBars()
            AudioDetailsSheet(
                attachment = attachment,
                origin = origin,
                onCopy = {
                    context
                        .getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText(attachment.title, attachment.displayUrl))
                },
                onOpen = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, attachment.displayUrl.toUri()))
                },
                onSave = { enqueueDownload(context, attachment) },
                transcriptionEnabled = transcriptionEnabled,
                transcriptionReady = transcriptionReady,
                transcript = transcript,
                onTranscribe = { force ->
                    onTranscribe(AudioPlaybackRequest(attachment, networkId, origin), force)
                },
                onCancelTranscription = { onCancelTranscription(attachment.playbackId) },
            )
        }
    }
}

@Composable
fun AudioDetailsSheet(
    attachment: AudioAttachment,
    origin: AudioPlaybackOrigin? = null,
    onCopy: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null,
    onSave: (() -> Unit)? = null,
    transcriptionEnabled: Boolean = false,
    transcriptionReady: Boolean = false,
    transcript: VoiceTranscriptState? = null,
    onTranscribe: ((Boolean) -> Unit)? = null,
    onCancelTranscription: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var pendingCleartextTranscription by
        remember(attachment.playbackId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(transcriptionEnabled, transcriptionReady) {
        if (!transcriptionEnabled || !transcriptionReady) {
            pendingCleartextTranscription = null
        }
    }
    val requestTranscription: (Boolean) -> Unit = { force ->
        if (attachment.cleartextHttp) {
            pendingCleartextTranscription = force
        } else {
            onTranscribe?.invoke(force)
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text("Audio", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        DetailRow("Link", attachment.displayUrl)
        origin?.let {
            DetailRow("From", if (it.isSelf) "You" else it.sender)
            DetailRow("Conversation", it.conversation)
        }
        DetailRow("Type", attachment.mimeType ?: "Unknown")
        DetailRow("Duration", formatAudioDuration(attachment.durationMs))
        DetailRow("Size", attachment.sizeBytes?.let(::formatBytes) ?: "Unknown")
        DetailRow("Expires", attachment.expiry ?: "Unknown")
        DetailRow("Encryption", if (attachment.encrypted) "Host-blind key in URL fragment" else "Off")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick =
                    onCopy ?: {
                        context
                            .getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText(attachment.title, attachment.displayUrl))
                        Unit
                    },
            ) {
                Icon(Icons.Outlined.ContentCopy, null)
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
            TextButton(
                onClick =
                    onOpen ?: {
                        context.startActivity(Intent(Intent.ACTION_VIEW, attachment.displayUrl.toUri()))
                    },
            ) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, null)
                Spacer(Modifier.width(6.dp))
                Text("Open")
            }
            TextButton(onClick = onSave ?: { enqueueDownload(context, attachment) }) {
                Icon(Icons.Outlined.Download, null)
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        }
        if (attachment.voice && transcriptionEnabled) {
            Spacer(Modifier.height(8.dp))
            VoiceTranscriptionDetails(
                ready = transcriptionReady,
                transcript = transcript,
                onTranscribe = requestTranscription,
                onCancel = onCancelTranscription ?: {},
            )
        }
        Spacer(Modifier.height(18.dp))
    }
    if (pendingCleartextTranscription != null) {
        AlertDialog(
            onDismissRequest = { pendingCleartextTranscription = null },
            modifier = Modifier.testTag("audio_transcription_http_confirm"),
            icon = { Icon(Icons.Outlined.Warning, null) },
            title = { Text(stringResource(R.string.voice_transcription_http_title)) },
            text = { Text(stringResource(R.string.voice_transcription_http_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val force = pendingCleartextTranscription ?: return@TextButton
                        pendingCleartextTranscription = null
                        onTranscribe?.invoke(force)
                    },
                ) {
                    Text(stringResource(R.string.voice_transcription_http_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCleartextTranscription = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun VoiceTranscriptionDetails(
    ready: Boolean,
    transcript: VoiceTranscriptState?,
    onTranscribe: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("audio_transcription_section"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.voice_transcription_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (!ready) {
            Text(
                stringResource(R.string.voice_transcription_setup),
                modifier = Modifier.testTag("audio_transcription_setup"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        when (transcript) {
            null -> {
                TextButton(
                    onClick = { onTranscribe(false) },
                    modifier = Modifier.testTag("audio_transcription_start"),
                ) {
                    Text(stringResource(R.string.voice_transcription_start))
                }
            }

            is VoiceTranscriptState.Preparing -> {
                VoiceTranscriptionProgress(
                    label =
                        stringResource(
                            R.string.voice_transcription_preparing,
                            transcript.progress,
                        ),
                    progress = transcript.progress,
                    onCancel = onCancel,
                )
            }

            VoiceTranscriptState.Waiting -> {
                Text(
                    stringResource(R.string.voice_transcription_waiting),
                    modifier = Modifier.testTag("audio_transcription_progress"),
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
                TranscriptionCancel(onCancel)
            }

            is VoiceTranscriptState.Transcribing -> {
                VoiceTranscriptionProgress(
                    label =
                        stringResource(
                            R.string.voice_transcription_running,
                            transcript.progress,
                        ),
                    progress = transcript.progress,
                    onCancel = onCancel,
                )
            }

            is VoiceTranscriptState.Ready -> {
                Text(
                    stringResource(R.string.voice_transcription_transcript),
                    style = MaterialTheme.typography.labelLarge,
                )
                val displayedTranscript =
                    if (transcript.text.isBlank()) {
                        stringResource(R.string.voice_transcription_no_speech)
                    } else {
                        transcript.text
                    }
                SelectionContainer {
                    Text(
                        text = displayedTranscript,
                        modifier = Modifier.fillMaxWidth().testTag("audio_transcription_text"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (transcript.cached) {
                    Text(
                        stringResource(R.string.voice_transcription_cached),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { onTranscribe(true) },
                    modifier = Modifier.testTag("audio_transcription_again"),
                ) {
                    Text(stringResource(R.string.voice_transcription_again))
                }
            }

            is VoiceTranscriptState.Failed -> {
                Text(
                    stringResource(transcript.kind.messageResource()),
                    modifier = Modifier.testTag("audio_transcription_error"),
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = { onTranscribe(false) },
                    modifier = Modifier.testTag("audio_transcription_retry"),
                ) {
                    Text(stringResource(R.string.chat_retry))
                }
            }
        }
    }
}

@Composable
private fun VoiceTranscriptionProgress(
    label: String,
    progress: Int,
    onCancel: () -> Unit,
) {
    Text(label, modifier = Modifier.testTag("audio_transcription_progress"))
    LinearProgressIndicator(
        progress = { progress.coerceIn(0, 100) / 100f },
        modifier = Modifier.fillMaxWidth(),
    )
    TranscriptionCancel(onCancel)
}

@Composable
private fun TranscriptionCancel(onCancel: () -> Unit) {
    TextButton(
        onClick = onCancel,
        modifier = Modifier.testTag("audio_transcription_cancel"),
    ) {
        Text(stringResource(R.string.action_cancel))
    }
}

@StringRes
private fun VoiceTranscriptFailureKind.messageResource(): Int =
    when (this) {
        VoiceTranscriptFailureKind.FEATURE_UNAVAILABLE -> {
            R.string.voice_transcription_error_unavailable
        }

        VoiceTranscriptFailureKind.UNSUPPORTED_SCHEME -> {
            R.string.voice_transcription_error_scheme
        }

        VoiceTranscriptFailureKind.FILE_UNAVAILABLE -> {
            R.string.voice_transcription_error_file
        }

        VoiceTranscriptFailureKind.EXPIRED -> {
            R.string.voice_transcription_error_expired
        }

        VoiceTranscriptFailureKind.MISSING_ENCRYPTION_KEY -> {
            R.string.voice_transcription_error_missing_key
        }

        VoiceTranscriptFailureKind.INVALID_ENCRYPTION_KEY -> {
            R.string.voice_transcription_error_invalid_key
        }

        VoiceTranscriptFailureKind.AUTHENTICATION_FAILED -> {
            R.string.voice_transcription_error_authentication
        }

        VoiceTranscriptFailureKind.ROUTE_UNAVAILABLE -> {
            R.string.voice_transcription_error_route
        }

        VoiceTranscriptFailureKind.TLS_FAILED -> {
            R.string.voice_transcription_error_tls
        }

        VoiceTranscriptFailureKind.HTTP_AUTHENTICATION_FAILED -> {
            R.string.voice_transcription_error_host_authentication
        }

        VoiceTranscriptFailureKind.HTTP_FAILED -> {
            R.string.voice_transcription_error_http
        }

        VoiceTranscriptFailureKind.READ_FAILED -> {
            R.string.voice_transcription_error_read
        }

        VoiceTranscriptFailureKind.INPUT_TOO_LARGE -> {
            R.string.voice_transcription_error_input_too_large
        }

        VoiceTranscriptFailureKind.NO_AUDIO -> {
            R.string.voice_transcription_error_no_audio
        }

        VoiceTranscriptFailureKind.UNSUPPORTED_CODEC -> {
            R.string.voice_transcription_error_codec
        }

        VoiceTranscriptFailureKind.AUDIO_TOO_LONG -> {
            R.string.voice_transcription_error_too_long
        }

        VoiceTranscriptFailureKind.DECODE_FAILED -> {
            R.string.voice_transcription_error_decode
        }

        VoiceTranscriptFailureKind.MODEL_OPEN,
        VoiceTranscriptFailureKind.NO_MODEL_LOADED,
        -> {
            R.string.voice_transcription_error_model_unavailable
        }

        VoiceTranscriptFailureKind.INVALID_MODEL_FORMAT -> {
            R.string.ai_error_invalid_format
        }

        VoiceTranscriptFailureKind.TRUNCATED_MODEL -> {
            R.string.ai_error_truncated_model
        }

        VoiceTranscriptFailureKind.CORRUPT_MODEL -> {
            R.string.ai_error_corrupt_model
        }

        VoiceTranscriptFailureKind.UNSUPPORTED_MODEL_ARCHITECTURE -> {
            R.string.ai_error_unsupported_architecture
        }

        VoiceTranscriptFailureKind.INVALID_REQUEST -> {
            R.string.voice_transcription_error_request
        }

        VoiceTranscriptFailureKind.OUT_OF_MEMORY -> {
            R.string.voice_transcription_error_out_of_memory
        }

        VoiceTranscriptFailureKind.INVALID_AUDIO -> {
            R.string.voice_transcription_error_invalid_audio
        }

        VoiceTranscriptFailureKind.INFERENCE_FAILED -> {
            R.string.voice_transcription_error_inference
        }

        VoiceTranscriptFailureKind.NATIVE_FAILURE -> {
            R.string.voice_transcription_error_runtime
        }

        VoiceTranscriptFailureKind.CACHE_FAILED -> {
            R.string.voice_transcription_error_cache
        }
    }

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    ListItem(
        headlineContent = { Text(label, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(value, maxLines = 3, overflow = TextOverflow.Ellipsis) },
    )
}

internal fun Float.cleanSpeed(): String = if (this == toInt().toFloat()) toInt().toString() else toString()

internal fun nextVoiceSpeed(speed: Float): Float =
    when {
        speed < 1.25f -> 1.5f
        speed < 1.75f -> 2f
        else -> 1f
    }

private fun enqueueDownload(
    context: Context,
    attachment: AudioAttachment,
) {
    val request =
        DownloadManager
            .Request(attachment.url.substringBefore('#').toUri())
            .setTitle(attachment.title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    context.getSystemService(DownloadManager::class.java)?.enqueue(request)
}
