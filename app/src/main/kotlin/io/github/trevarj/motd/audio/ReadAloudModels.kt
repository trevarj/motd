package io.github.trevarj.motd.audio

import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.ui.chat.trimUrl
import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class ReadAloudVoiceGender { FEMALE, MALE }

data class ReadAloudVoice(
    val id: String,
    val name: String,
    val locale: String,
    val gender: ReadAloudVoiceGender? = null,
)

/** Captured effective backend, independent saved profile, and local acquisition authorization. */
data class ReadAloudSelection(
    val localEnabled: Boolean = false,
    val modelId: String? = null,
    val localReady: Boolean = false,
    val options: ReadAloudConfig = ReadAloudConfig(),
    val localVersion: Long = 0,
    val profileVersion: Long = 0,
) {
    fun normalized(): ReadAloudSelection = copy(options = if (localEnabled) options.normalizedLocal() else options.normalized())
}

data class ReadAloudVoices(
    val voices: List<ReadAloudVoice> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

enum class ReadAloudStatus { WAITING, PREPARING, PLAYING, PAUSED, GAP, ERROR }

data class ReadAloudState(
    val roomId: Long? = null,
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val current: MessageEntity? = null,
    val preview: String = "",
    val position: Int = 0,
    val total: Int = 0,
    val pending: Int = 0,
    val skipped: Int = 0,
    val status: ReadAloudStatus = ReadAloudStatus.WAITING,
    val error: String? = null,
    val canPrevious: Boolean = false,
    val canSkip: Boolean = false,
    val canLatest: Boolean = false,
    val previewing: Boolean = false,
)

/**
 * Both installed and local-model synthesis produce a caller-owned ephemeral local file.
 * Return only after the file is complete; never start playback. On cancellation stop synthesis and
 * release its resources. In-process backends must drain writes. Android requests engine stop/shutdown
 * and removes any late callback output; it cannot sandbox an independently installed engine.
 * The controller rejects stale generations and deletes output on every terminal path.
 */
interface ReadAloudSynthesizer {
    val voices: StateFlow<ReadAloudVoices>

    suspend fun loadVoices(config: ReadAloudSelection)

    suspend fun synthesize(
        text: String,
        config: ReadAloudSelection,
        output: File,
    )

    /** Signal cancellation and release idle resources; cancelled synthesize must also join cleanup. */
    fun cancel()
}

/** Local file playback, not direct TTS speak; pause/resume keeps the same audio position. */
interface ReadAloudOutput {
    var onInterrupted: ((String) -> Unit)?
    var onStarted: (() -> Unit)?

    suspend fun play(
        file: File,
        isCurrent: () -> Boolean,
    )

    fun pause()

    fun resume()

    fun stop()
}

private val speechUrls = Regex("""(?i)(?:[a-z][a-z0-9+.-]*://|mailto:|www\.)[^\s<>]+""")
private val speechVoice = Regex("""(?i)\[voice(?:\s+encrypted)?\s+[^\]]*]\s+(?:https?://)[^\s<>]+""")
private val speechWhitespace = Regex("""[\s\p{Cc}\p{Cf}]+""")

/** Links become a spoken label: never pass userinfo, query tokens or encryption fragments to TTS. */
fun readAloudBody(text: String): String {
    val links =
        speechUrls.replace(speechVoice.replace(text, "voice message")) { match ->
            "link" + match.value.removePrefix(trimUrl(match.value))
        }
    return speechWhitespace.replace(links, " ").trim()
}

fun readAloudUtterance(message: MessageEntity): String {
    val sender = readAloudBody(message.sender)
    val body = readAloudBody(message.text)
    return if (message.kind == MessageKind.ACTION) "$sender $body" else "$sender says, $body"
}
