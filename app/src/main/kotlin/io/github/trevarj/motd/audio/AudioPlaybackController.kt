package io.github.trevarj.motd.audio

import kotlinx.coroutines.flow.StateFlow

data class AudioPlaybackOrigin(
    val bufferId: Long,
    val networkId: Long,
    val conversation: String,
    val sender: String,
    val isSelf: Boolean,
    val directMessage: Boolean,
    val eventId: Long,
    val msgid: String?,
    val serverTime: Long,
)

data class AudioPlaybackRequest(
    val attachment: AudioAttachment,
    val networkId: Long?,
    val origin: AudioPlaybackOrigin? = null,
)

data class AudioPlaybackState(
    val activeId: String? = null,
    val title: String? = null,
    val url: String? = null,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val canSeek: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long? = null,
    val bufferedMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
    val attachment: AudioAttachment? = null,
    val origin: AudioPlaybackOrigin? = null,
    val networkName: String? = null,
    val loadingFraction: Float? = null,
    val waveform: AudioWaveform? = attachment?.waveform,
)

enum class AudioCacheStatus {
    UNKNOWN,
    NOT_CACHED,
    PARTIAL,
    CACHED,
}

interface AudioPlaybackController {
    val state: StateFlow<AudioPlaybackState>
    val waveforms: StateFlow<Map<String, AudioWaveform>>
    val cacheStatuses: StateFlow<Map<String, AudioCacheStatus>>

    fun play(
        request: AudioPlaybackRequest,
        speed: Float = 1f,
    )

    fun toggle(request: AudioPlaybackRequest)

    fun inspectCache(attachment: AudioAttachment)

    fun toggleActive()

    fun pause()

    fun dismiss(itemId: String)

    fun cancelLoading()

    fun retryActive()

    fun seekTo(
        itemId: String,
        positionMs: Long,
    )

    fun setSpeed(
        itemId: String,
        speed: Float,
    )
}
