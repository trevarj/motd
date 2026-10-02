package io.github.trevarj.motd.audio

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Installed engines receive text. An offline-reported voice is not an engine privacy sandbox. */
@Singleton
class AndroidReadAloudSynthesizer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ReadAloudSynthesizer {
        private val lock = Mutex()
        private val _voices = MutableStateFlow(ReadAloudVoices())
        override val voices = _voices.asStateFlow()
        private var tts: TextToSpeech? = null

        override suspend fun loadVoices(config: ReadAloudSelection): Unit =
            withContext(Dispatchers.Main.immediate) {
                lock.withLock {
                    _voices.value = _voices.value.copy(loading = true, error = null)
                    try {
                        prepare(config.options)
                    } catch (cancelled: CancellationException) {
                        _voices.value = _voices.value.copy(loading = false)
                        throw cancelled
                    } catch (error: Exception) {
                        _voices.value = _voices.value.copy(loading = false, error = "Installed speech is unavailable. Choose an installed offline voice.")
                        throw error
                    }
                }
            }

        private suspend fun prepare(config: ReadAloudConfig): TextToSpeech {
            if (tts == null) {
                val ready = CompletableDeferred<Int>()
                // Android manages engine selection. Public API does not verify a particular bound vendor.
                val created = TextToSpeech(context) { ready.complete(it) }
                try {
                    check(withTimeoutOrNull(10_000) { ready.await() } == TextToSpeech.SUCCESS) { "Could not initialize installed speech." }
                    tts = created
                } catch (error: Exception) {
                    created.shutdown()
                    throw error
                }
            }
            val active = checkNotNull(tts)
            val offline =
                active.voices
                    .orEmpty()
                    .filter(::usableOfflineVoice)
                    .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
            _voices.value =
                ReadAloudVoices(
                    voices = offline.map { ReadAloudVoice(it.name, it.name, it.locale.toLanguageTag()) },
                )
            check(offline.isNotEmpty()) { "No installed offline voice is available." }
            val selected =
                if (config.voice != null) {
                    offline.firstOrNull { it.name == config.voice }
                        ?: error("The selected offline voice is unavailable. Choose another installed voice.")
                } else {
                    offline.firstOrNull { it.name == active.defaultVoice?.name && it.locale.language == Locale.getDefault().language }
                        ?: offline.firstOrNull { it.locale.language == Locale.getDefault().language }
                        ?: error("No installed offline voice supports the device language. Choose a voice and language explicitly.")
                }
            check(active.isLanguageAvailable(selected.locale) >= TextToSpeech.LANG_AVAILABLE) { "The selected voice language is unsupported." }
            check(active.setVoice(selected) == TextToSpeech.SUCCESS) { "Could not select the installed voice." }
            check(active.setSpeechRate(config.rate) == TextToSpeech.SUCCESS && active.setPitch(config.pitch) == TextToSpeech.SUCCESS) { "Could not configure the installed voice." }
            return active
        }

        override suspend fun synthesize(
            text: String,
            config: ReadAloudSelection,
            output: File,
        ) = withContext(Dispatchers.Main.immediate) {
            lock.withLock {
                val active = prepare(config.options.normalized())
                check(text.length <= TextToSpeech.getMaxSpeechInputLength()) { "This message is too long for the installed speech engine." }
                val completed = CompletableDeferred<Unit>()
                val id = UUID.randomUUID().toString()
                active.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == id) {
                                if (completed.isCancelled) output.delete() else completed.complete(Unit)
                            }
                        }

                        @Deprecated("Platform compatibility callback")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId == id) {
                                if (completed.isCancelled) output.delete() else completed.completeExceptionally(IllegalStateException("Installed speech synthesis failed."))
                            }
                        }

                        @Suppress("DEPRECATION")
                        override fun onError(
                            utteranceId: String?,
                            errorCode: Int,
                        ) = onError(utteranceId)

                        override fun onStop(
                            utteranceId: String?,
                            interrupted: Boolean,
                        ) {
                            if (utteranceId == id) {
                                if (completed.isCancelled) output.delete() else completed.completeExceptionally(IllegalStateException("Installed speech was interrupted."))
                            }
                        }
                    },
                )
                try {
                    check(active.synthesizeToFile(text, Bundle(), output, id) == TextToSpeech.SUCCESS) { "Installed speech could not synthesize this message." }
                    check(
                        withTimeoutOrNull(30_000) {
                            completed.await()
                            true
                        } == true,
                    ) { "Installed speech synthesis timed out." }
                    check(output.isFile && output.length() > 0) { "The installed engine returned no audio." }
                } catch (error: Exception) {
                    completed.cancel()
                    active.stop()
                    active.shutdown()
                    tts = null
                    output.delete()
                    throw error
                }
            }
        }

        override fun cancel() {
            val release = {
                tts?.stop()
                tts?.shutdown()
                tts = null
            }
            if (Looper.myLooper() == Looper.getMainLooper()) release() else Handler(Looper.getMainLooper()).post { release() }
        }
    }

internal fun usableOfflineVoice(voice: Voice): Boolean =
    !voice.isNetworkConnectionRequired &&
        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
