package io.github.trevarj.motd.ai

import io.github.trevarj.motd.audio.AndroidReadAloudSynthesizer
import io.github.trevarj.motd.audio.ReadAloudSelection
import io.github.trevarj.motd.audio.ReadAloudSynthesizer
import io.github.trevarj.motd.audio.ReadAloudVoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Explicit local intent is never replaced by installed/cloud synthesis, including on failure. */
@Singleton
class AiReadAloudSynthesizer internal constructor(
    private val installed: ReadAloudSynthesizer,
    private val repository: AiLabsRepository,
    private val coordinator: AiExecutionCoordinator,
) : ReadAloudSynthesizer {
    @Inject
    constructor(
        installed: AndroidReadAloudSynthesizer,
        repository: AiLabsRepository,
        coordinator: AiExecutionCoordinator,
    ) : this(installed as ReadAloudSynthesizer, repository, coordinator)

    private val mutableVoices = MutableStateFlow(ReadAloudVoices())
    override val voices = mutableVoices.asStateFlow()

    private fun authorize(selection: ReadAloudSelection) {
        if (!repository.isReadAloudVersionCurrent(selection.localVersion)) throw CancellationException("Reading configuration changed")
        if (selection.localEnabled) {
            check(selection.localReady && selection.modelId != null) { "Local reading is enabled, but its model is unavailable. Download Kokoro or turn off the local override in AI Labs." }
            check(selection.options.voice == null || kokoroEnglishVoices.any { it.id == selection.options.voice }) { "The selected local voice is unavailable." }
        }
    }

    override suspend fun loadVoices(config: ReadAloudSelection) {
        if (config.localEnabled) {
            mutableVoices.value = ReadAloudVoices(kokoroEnglishVoices)
            try {
                authorize(config)
            } catch (error: Exception) {
                mutableVoices.value = ReadAloudVoices(kokoroEnglishVoices, error = error.message)
                throw error
            }
        } else {
            authorize(config)
            mutableVoices.value = ReadAloudVoices(loading = true)
            try {
                installed.loadVoices(config)
            } finally {
                mutableVoices.value = installed.voices.value
            }
        }
    }

    override suspend fun synthesize(
        text: String,
        config: ReadAloudSelection,
        output: File,
    ) {
        authorize(config)
        if (!config.localEnabled) {
            installed.synthesize(text, config, output)
            return
        }
        val id = checkNotNull(config.modelId)
        try {
            coordinator.synthesize(id, repository.modelFile(id), text, config.options.normalizedLocal(), output) {
                repository.isReadAloudVersionCurrent(config.localVersion)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutableVoices.value = ReadAloudVoices(kokoroEnglishVoices, error = error.message ?: "Local reading failed.")
            throw error
        }
    }

    override fun cancel() {
        coordinator.requestReadAloudCancellation()
        installed.cancel()
    }
}
