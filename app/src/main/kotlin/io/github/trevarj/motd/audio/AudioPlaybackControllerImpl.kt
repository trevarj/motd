package io.github.trevarj.motd.audio

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioPlaybackControllerImpl
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val inputMaterializer: AudioInputMaterializer,
        private val cacheStore: AudioCacheStore,
        private val mediaCache: AudioMediaCache,
        private val waveformRepository: AudioWaveformRepository,
        private val waveformAnalyzer: AudioWaveformAnalyzer,
        private val db: MotdDatabase,
        @ApplicationScope private val applicationScope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : AudioPlaybackController {
        private val _state = MutableStateFlow(AudioPlaybackState())
        override val state: StateFlow<AudioPlaybackState> = _state.asStateFlow()
        override val waveforms: StateFlow<Map<String, AudioWaveform>> = waveformRepository.waveforms
        private val _cacheStatuses = MutableStateFlow<Map<String, AudioCacheStatus>>(emptyMap())
        override val cacheStatuses: StateFlow<Map<String, AudioCacheStatus>> = _cacheStatuses.asStateFlow()
        private var controller: MediaController? = null
        private val controllerReady = CompletableDeferred<MediaController>()
        private var activeLease: LocalAudioLease? = null
        private var playJob: Job? = null
        private var activeRequest: AudioPlaybackRequest? = null
        private var generation = 0L
        private val positionPoller =
            AudioPositionPoller(applicationScope, POSITION_POLL_MS) {
                withContext(Dispatchers.Main.immediate) {
                    if (_state.value.activeId != null) controller?.let(::updateState)
                }
                updateDownloadProgress()
            }

        init {
            val token = SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
            val future = MediaController.Builder(context, token).buildAsync()
            future.addListener(
                {
                    runCatching { future.get() }
                        .onSuccess { mediaController ->
                            controller =
                                mediaController.also { connected ->
                                    connected.addListener(
                                        object : Player.Listener {
                                            override fun onPlaybackStateChanged(playbackState: Int) = updateState(connected)

                                            override fun onIsPlayingChanged(isPlaying: Boolean) = updateState(connected)

                                            override fun onPlayerError(error: PlaybackException) {
                                                if (connected.currentMediaItem?.mediaId != _state.value.activeId) return
                                                connected.stop()
                                                connected.clearMediaItems()
                                                cleanupInput()
                                                _state.value =
                                                    _state.value.copy(
                                                        loading = false,
                                                        loadingFraction = null,
                                                        playing = false,
                                                        error = "Playback failed",
                                                    )
                                                syncPositionPolling()
                                            }
                                        },
                                    )
                                    updateState(connected)
                                }
                            controllerReady.complete(mediaController)
                        }.onFailure { error ->
                            controllerReady.completeExceptionally(error)
                            _state.value =
                                _state.value.copy(
                                    loading = false,
                                    error = "Audio service unavailable",
                                )
                            syncPositionPolling()
                        }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

        override fun play(
            request: AudioPlaybackRequest,
            speed: Float,
        ) {
            val session = ++generation
            playJob?.cancel()
            activeRequest = request
            val attachment = request.attachment
            inspectCache(attachment)
            _state.value =
                AudioPlaybackState(
                    activeId = attachment.playbackId,
                    title = attachment.title,
                    url = attachment.displayUrl,
                    loading = true,
                    speed = speed,
                    attachment = attachment,
                    origin = request.origin,
                    waveform = attachment.waveform ?: waveformRepository.waveforms.value[attachment.playbackId],
                )
            syncPositionPolling()
            playJob =
                applicationScope.launch {
                    var pendingLease: LocalAudioLease? = null
                    var handedOff = false
                    try {
                        if (attachment.waveform == null) {
                            waveformRepository.load(attachment.playbackId)?.let { waveform ->
                                if (session == generation) _state.value = _state.value.copy(waveform = waveform)
                            }
                        }
                        val networkName =
                            request.origin?.networkId?.let { networkId ->
                                withContext(ioDispatcher) { db.networkDao().byId(networkId)?.name }
                            }
                        if (session == generation) _state.value = _state.value.copy(networkName = networkName)
                        pendingLease =
                            inputMaterializer.materialize(request) { received, total ->
                                if (session == generation) {
                                    val fraction =
                                        total
                                            ?.takeIf { it > 0L }
                                            ?.let { (received.toFloat() / it).coerceIn(0f, 1f) }
                                    _state.value = _state.value.copy(loading = true, loadingFraction = fraction)
                                }
                            }
                        if (session != generation) throw CancellationException("Playback was replaced.")
                        if (attachment.encrypted) putCacheStatus(attachment.playbackId, AudioCacheStatus.CACHED)
                        withContext(Dispatchers.Main.immediate) {
                            if (session != generation) throw CancellationException("Playback was replaced.")
                            val mediaController = controller ?: controllerReady.await()
                            if (session != generation) throw CancellationException("Playback was replaced.")
                            val lease = checkNotNull(pendingLease)
                            val item =
                                MediaItem
                                    .Builder()
                                    .setMediaId(attachment.playbackId)
                                    .setUri(lease.file.toUri())
                                    .setMediaMetadata(mediaMetadata(request, networkName))
                                    .build()
                            activeLease =
                                handoffAudioLease(activeLease, lease) {
                                    mediaController.stop()
                                    mediaController.clearMediaItems()
                                }
                            pendingLease = null
                            handedOff = true
                            mediaController.setMediaItem(item)
                            mediaController.prepare()
                            mediaController.playbackParameters = PlaybackParameters(speed)
                            mediaController.play()
                            updateState(mediaController)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        if (session == generation) {
                            if (handedOff) {
                                withContext(Dispatchers.Main.immediate) {
                                    if (session != generation) return@withContext
                                    val failedLease = activeLease
                                    closeAudioLeaseAfterPlayerRelease(failedLease) {
                                        controller?.run {
                                            stop()
                                            clearMediaItems()
                                        }
                                    }
                                    if (activeLease === failedLease) activeLease = null
                                }
                            }
                            if (session != generation) return@launch
                            _state.value =
                                _state.value.copy(
                                    loading = false,
                                    loadingFraction = null,
                                    playing = false,
                                    error = (error as? AudioInputException)?.message ?: "Playback failed",
                                )
                            syncPositionPolling()
                        }
                    } finally {
                        pendingLease?.close()
                    }
                }
        }

        override fun toggle(request: AudioPlaybackRequest) {
            val current = state.value
            if (current.activeId != request.attachment.playbackId) {
                play(request)
                return
            }
            when {
                current.loading -> cancelLoading()
                current.error != null -> retryActive()
                else -> toggleActive()
            }
        }

        override fun inspectCache(attachment: AudioAttachment) {
            val playbackId = attachment.playbackId
            if (_cacheStatuses.value[playbackId] == AudioCacheStatus.CACHED) return
            applicationScope.launch(ioDispatcher) {
                val status =
                    if (attachment.encrypted) {
                        val cachedBytes =
                            cacheStore
                                .ciphertextFile(attachment.url.substringBefore('#'))
                                .takeIf { it.isFile }
                                ?.length()
                                ?: 0L
                        if (cachedBytes > 0L) {
                            AudioCacheStatus.CACHED
                        } else {
                            AudioCacheStatus.NOT_CACHED
                        }
                    } else if (attachment.url.startsWith("http", ignoreCase = true)) {
                        mediaCache.status(attachment.url)
                    } else {
                        AudioCacheStatus.CACHED
                    }
                putCacheStatus(playbackId, status)
            }
        }

        override fun pause() {
            applicationScope.launch(Dispatchers.Main.immediate) {
                controller?.pause()
                controller?.let(::updateState)
            }
        }

        override fun dismiss(itemId: String) {
            if (state.value.activeId != itemId) return
            val requestToAnalyze = activeRequest
            val session = ++generation
            positionPoller.stop()
            playJob?.cancel()
            playJob = null
            activeRequest = null
            applicationScope.launch(Dispatchers.Main.immediate) {
                if (session != generation) return@launch
                controller?.run {
                    stop()
                    clearMediaItems()
                }
                cleanupInput()
                _state.value = AudioPlaybackState()
            }
            requestToAnalyze?.let(::analyzeCachedAudio)
            requestToAnalyze?.attachment?.let(::inspectCache)
        }

        override fun cancelLoading() {
            val current = state.value
            if (!current.loading || current.activeId == null) return
            val session = ++generation
            positionPoller.stop()
            playJob?.cancel()
            playJob = null
            applicationScope.launch(Dispatchers.Main.immediate) {
                if (session != generation) return@launch
                controller?.run {
                    stop()
                    clearMediaItems()
                }
                cleanupInput()
                _state.value =
                    current.copy(
                        loading = false,
                        loadingFraction = null,
                        playing = false,
                        positionMs = 0,
                        bufferedMs = 0,
                        error = null,
                    )
            }
        }

        override fun retryActive() {
            val request = activeRequest ?: return
            play(request, state.value.speed)
        }

        override fun toggleActive() {
            val current = state.value
            if (current.loading) {
                cancelLoading()
                return
            }
            if (current.error != null || controller?.currentMediaItem == null) {
                retryActive()
                return
            }
            applicationScope.launch(Dispatchers.Main.immediate) {
                val mediaController = controller ?: return@launch
                if (mediaController.isPlaying) mediaController.pause() else mediaController.play()
                updateState(mediaController)
            }
        }

        override fun seekTo(
            itemId: String,
            positionMs: Long,
        ) {
            applicationScope.launch(Dispatchers.Main.immediate) {
                val mediaController = controller ?: return@launch
                if (state.value.activeId != itemId || state.value.loading) return@launch
                val duration = state.value.durationMs
                mediaController.seekTo(
                    positionMs.coerceAtLeast(0).let { value ->
                        if (duration == null) value else value.coerceAtMost(duration)
                    },
                )
                updateState(mediaController)
            }
        }

        override fun setSpeed(
            itemId: String,
            speed: Float,
        ) {
            applicationScope.launch(Dispatchers.Main.immediate) {
                val mediaController = controller ?: return@launch
                if (state.value.activeId != itemId) return@launch
                mediaController.playbackParameters = PlaybackParameters(speed)
                updateState(mediaController)
            }
        }

        private fun mediaMetadata(
            request: AudioPlaybackRequest,
            networkName: String?,
        ): MediaMetadata {
            val origin = request.origin
            val extras =
                Bundle().apply {
                    origin?.let {
                        putLong(EXTRA_BUFFER_ID, it.bufferId)
                        putLong(EXTRA_EVENT_ID, it.eventId)
                        putString(EXTRA_MSGID, it.msgid)
                        putLong(EXTRA_SERVER_TIME, it.serverTime)
                    }
                }
            return MediaMetadata
                .Builder()
                .setTitle(request.attachment.title)
                .setArtist(origin?.contextLabel(networkName))
                .setExtras(extras)
                .build()
        }

        private fun updateState(mediaController: MediaController) {
            val current = _state.value
            if (current.activeId == null) return
            val mediaId = mediaController.currentMediaItem?.mediaId ?: return
            // A newly requested encrypted item can spend time downloading while the controller still
            // references the previous item. Ignore those stale callbacks so they cannot clear the new
            // request's download state or progress.
            if (mediaId != current.activeId) return
            if (mediaController.playbackState == Player.STATE_ENDED) {
                dismiss(mediaId)
                return
            }
            val duration = mediaController.duration.takeIf { it >= 0 }
            val loading = mediaController.playbackState == Player.STATE_BUFFERING
            _state.value =
                current.copy(
                    activeId = mediaId,
                    loading = loading,
                    // Byte progress is sampled from the cache separately. Preserve it across the
                    // frequent MediaController position updates while buffering.
                    loadingFraction = current.loadingFraction.takeIf { loading },
                    playing = mediaController.isPlaying,
                    positionMs = mediaController.currentPosition.coerceAtLeast(0L),
                    durationMs = duration ?: current.attachment?.durationMs,
                    bufferedMs = mediaController.bufferedPosition.coerceAtLeast(0L),
                    speed = mediaController.playbackParameters.speed,
                    error = null,
                )
            syncPositionPolling()
        }

        private fun syncPositionPolling() {
            val current = _state.value
            if (current.activeId != null && (current.loading || current.playing)) {
                positionPoller.start()
            } else {
                positionPoller.stop()
            }
        }

        private suspend fun updateDownloadProgress() {
            val snapshot = _state.value
            val attachment = snapshot.attachment ?: return
            if (!snapshot.loading || attachment.encrypted ||
                !attachment.url.startsWith("http", ignoreCase = true)
            ) {
                return
            }

            val fraction = mediaCache.downloadFraction(attachment.url) ?: return
            _state.update { current ->
                if (current.activeId == snapshot.activeId && current.loading) {
                    current.copy(loadingFraction = fraction)
                } else {
                    current
                }
            }
        }

        private fun cleanupInput() {
            activeLease?.close()
            activeLease = null
        }

        private fun analyzeCachedAudio(request: AudioPlaybackRequest) {
            val attachment = request.attachment
            if (attachment.encrypted || attachment.waveform != null ||
                !attachment.url.startsWith("http", ignoreCase = true)
            ) {
                return
            }
            applicationScope.launch(ioDispatcher) {
                val local = cacheStore.inputLease()
                try {
                    if (!mediaCache.copyIfComplete(attachment.url.substringBefore('#'), local.file)) return@launch
                    val waveform = waveformAnalyzer.analyze(local.file) ?: return@launch
                    waveformRepository.put(attachment.playbackId, waveform)
                } finally {
                    local.close()
                }
            }
        }

        private fun putCacheStatus(
            playbackId: String,
            status: AudioCacheStatus,
        ) {
            _cacheStatuses.value =
                LinkedHashMap(_cacheStatuses.value).apply {
                    remove(playbackId)
                    put(playbackId, status)
                    while (size > MAX_CACHE_STATUS_ENTRIES) {
                        remove(keys.first())
                    }
                }
        }

        private companion object {
            const val POSITION_POLL_MS = 250L
            const val MAX_CACHE_STATUS_ENTRIES = 256
            const val EXTRA_BUFFER_ID = "motd.audio.buffer_id"
            const val EXTRA_EVENT_ID = "motd.audio.event_id"
            const val EXTRA_MSGID = "motd.audio.msgid"
            const val EXTRA_SERVER_TIME = "motd.audio.server_time"
        }
    }

internal fun closeAudioLeaseAfterPlayerRelease(
    current: LocalAudioLease?,
    releasePlayer: () -> Unit,
) {
    releasePlayer()
    current?.close()
}

internal fun handoffAudioLease(
    current: LocalAudioLease?,
    replacement: LocalAudioLease,
    releasePlayer: () -> Unit,
): LocalAudioLease {
    closeAudioLeaseAfterPlayerRelease(current, releasePlayer)
    return replacement
}

internal class AudioPositionPoller(
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val tick: suspend () -> Unit,
) {
    private var job: Job? = null
    val isRunning: Boolean get() = job?.isActive == true

    fun start() {
        if (isRunning) return
        job =
            scope.launch {
                while (true) {
                    tick()
                    delay(intervalMs)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

fun AudioPlaybackOrigin.contextLabel(
    networkName: String? = null,
    includeNetwork: Boolean = false,
): String {
    val actor = if (isSelf) "You" else sender
    val conversationLabel =
        when {
            directMessage && !isSelf -> "Direct message"
            else -> conversation
        }
    return buildString {
        append(actor)
        append(" · ")
        append(conversationLabel)
        if (includeNetwork && !networkName.isNullOrBlank()) {
            append(" · ")
            append(networkName)
        }
    }
}
