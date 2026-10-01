package io.github.trevarj.motd.testing

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.trevarj.motd.ai.AiLabsRepository
import io.github.trevarj.motd.ai.AiLabsRuntimeBoundary
import io.github.trevarj.motd.ai.AiModelCapability
import io.github.trevarj.motd.ai.AiModelMetadata
import io.github.trevarj.motd.ai.AiModelSource
import io.github.trevarj.motd.ai.AiModelSourceMetadata
import io.github.trevarj.motd.ai.KokoroAsset
import io.github.trevarj.motd.ai.KokoroBundle
import io.github.trevarj.motd.audio.AudioActivityTracker
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.audio.ReadAloudController
import io.github.trevarj.motd.audio.ReadAloudOutput
import io.github.trevarj.motd.audio.ReadAloudPrefs
import io.github.trevarj.motd.audio.ReadAloudSelection
import io.github.trevarj.motd.audio.ReadAloudSynthesizer
import io.github.trevarj.motd.audio.ReadAloudVoices
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.service.AppVisibility
import io.github.trevarj.motd.service.ForegroundBufferTracker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Real controller and Room policy; only Android synthesis/media boundaries are controllable. */
internal class ReadAloudHarness(
    context: Context,
    db: MotdDatabase,
    scope: CoroutineScope,
    foreground: ForegroundBufferTracker = SelectedRoom(),
    settings: SettingsRepository? = null,
) {
    val selected = foreground
    val visible = MutableStateFlow(true)
    val social = MutableStateFlow(Settings())
    val activity = AudioActivityTracker()
    val audio = AttachmentPlayback()
    val synth = Synthesizer()
    val output = Output()
    val prefs = ReadAloudPrefs(context)
    private val root =
        java.nio.file.Files
            .createTempDirectory(context.cacheDir.toPath(), "reader-labs-")
            .toFile()
    private val bytes = "checked speech fixture".toByteArray()
    private val bundle = KokoroBundle("test/english", "fixture", listOf(KokoroAsset("model.int8.onnx", bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })))
    val labs =
        AiLabsRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "state.preferences_pb") }),
            File(root, "models"),
            object : AiModelSource {
                override fun metadata(uri: Uri) = AiModelSourceMetadata()

                override fun open(uri: Uri): InputStream = error("SAF not used by reader fixture")
            },
            object : AiLabsRuntimeBoundary {
                override suspend fun inspect(
                    modelId: String,
                    modelFile: File,
                    capability: AiModelCapability,
                ) = AiModelMetadata("kokoro", "int8", sampleRateHz = 24_000, voiceCount = 54)

                override suspend fun unloadForDeletion(modelId: String) = Unit

                override suspend fun cancelTextTools(unload: Boolean) = Unit

                override suspend fun cancelReadAloud(unload: Boolean) = Unit
            },
            scope,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            availableBytes = { Long.MAX_VALUE },
            allocateBytes = { _, _ -> },
            kokoroBundle = bundle,
            kokoroOpener = { _, _ -> bytes.inputStream() },
        )
    val controller =
        ReadAloudController(
            context,
            db,
            prefs,
            labs,
            foreground,
            object : AppVisibility {
                override val onScreen = visible
            },
            settings ?: object : SettingsRepository by DataStoreSettingsRepository(context) {
                override val settings = social
            },
            audio,
            activity,
            synth,
            output,
            scope,
        )

    class SelectedRoom : ForegroundBufferTracker {
        override val foregroundBufferId = MutableStateFlow<Long?>(null)

        override fun set(bufferId: Long?) {
            foregroundBufferId.value = bufferId
        }
    }

    class Synthesizer : ReadAloudSynthesizer {
        override val voices = MutableStateFlow(ReadAloudVoices())
        val utterances = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var failure: String? = null
        val selections = mutableListOf<ReadAloudSelection>()
        var synthesize: (suspend (ReadAloudSelection, File) -> Unit)? = null
        var onCancel: (() -> Unit)? = null

        override suspend fun loadVoices(config: ReadAloudSelection) {
            if (config.localEnabled && !config.localReady) error("Local model unavailable")
            failure?.let { error(it) }
        }

        override fun cancel() {
            onCancel?.invoke()
        }

        override suspend fun synthesize(
            text: String,
            config: ReadAloudSelection,
            output: File,
        ) {
            selections += config
            utterances += text
            gate?.await()
            failure?.let { error(it) }
            synthesize?.invoke(config, output) ?: output.writeText("synthetic audio fixture")
        }
    }

    class Output : ReadAloudOutput {
        override var onInterrupted: ((String) -> Unit)? = null
        override var onStarted: (() -> Unit)? = null
        val played = mutableListOf<File>()
        var completion = CompletableDeferred<Unit>()
        var paused = false
        var stops = 0

        override suspend fun play(
            file: File,
            isCurrent: () -> Boolean,
        ) {
            check(isCurrent())
            played += file
            completion = CompletableDeferred()
            onStarted?.invoke()
            completion.await()
        }

        override fun pause() {
            paused = true
        }

        override fun resume() {
            paused = false
            if (played.isNotEmpty() && completion.isActive) onStarted?.invoke()
        }

        override fun stop() {
            stops++
            completion.cancel()
            paused = false
        }

        fun complete() {
            completion.complete(Unit)
        }
    }

    class AttachmentPlayback : AudioPlaybackController {
        override val state = MutableStateFlow(AudioPlaybackState())
        override val waveforms = MutableStateFlow<Map<String, AudioWaveform>>(emptyMap())
        override val cacheStatuses = MutableStateFlow<Map<String, AudioCacheStatus>>(emptyMap())

        override fun play(
            request: AudioPlaybackRequest,
            speed: Float,
        ) = Unit

        override fun toggle(request: AudioPlaybackRequest) = Unit

        override fun inspectCache(attachment: AudioAttachment) = Unit

        override fun toggleActive() = Unit

        override fun pause() = Unit

        override fun dismiss(itemId: String) = Unit

        override fun cancelLoading() = Unit

        override fun retryActive() = Unit

        override fun seekTo(
            itemId: String,
            positionMs: Long,
        ) = Unit

        override fun setSpeed(
            itemId: String,
            speed: Float,
        ) = Unit
    }
}
