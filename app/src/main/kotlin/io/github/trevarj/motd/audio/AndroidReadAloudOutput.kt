package io.github.trevarj.motd.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class AndroidReadAloudOutput
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ReadAloudOutput {
        private val audio = context.getSystemService(AudioManager::class.java)
        private val handler = Handler(Looper.getMainLooper())
        private val attributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        private val focus =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener({ change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                        change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                    ) {
                        interrupt("Reading stopped because another app needs audio.")
                    }
                }, handler)
                .build()
        private var player: MediaPlayer? = null
        private var finish: ((Throwable?) -> Unit)? = null
        private var paused = false
        private var prepared = false
        private var current: (() -> Boolean)? = null
        override var onInterrupted: ((String) -> Unit)? = null
        override var onStarted: (() -> Unit)? = null

        private fun interrupt(reason: String) {
            stop()
            onInterrupted?.invoke(reason)
        }

        override suspend fun play(
            file: File,
            isCurrent: () -> Boolean,
        ) = withContext(Dispatchers.Main.immediate) {
            check(isCurrent()) { "Reading is no longer eligible." }
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Audio focus was not granted. Pause other audio and try again." }
            suspendCancellableCoroutine<Unit> { continuation ->
                val created = MediaPlayer()
                player = created
                prepared = false
                paused = false
                current = isCurrent
                finish = { error ->
                    if (player === created) release()
                    if (continuation.isActive) {
                        if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
                    }
                }
                continuation.invokeOnCancellation {
                    onMain { if (player === created) release() }
                }
                try {
                    created.setAudioAttributes(attributes)
                    created.setDataSource(file.absolutePath)
                    created.setOnPreparedListener {
                        if (player === created && isCurrent()) {
                            prepared = true
                            if (!paused) {
                                try {
                                    created.start()
                                    onStarted?.invoke()
                                } catch (_: Exception) {
                                    if (player === created) finish?.invoke(IllegalStateException("Could not start synthesized speech."))
                                }
                            }
                        } else if (player === created) {
                            finish?.invoke(CancellationException("Stale speech playback"))
                        }
                    }
                    created.setOnCompletionListener { if (player === created) finish?.invoke(null) }
                    created.setOnErrorListener { _, _, _ ->
                        if (player === created) finish?.invoke(IllegalStateException("Could not play synthesized speech."))
                        true
                    }
                    created.prepareAsync()
                } catch (error: Exception) {
                    finish?.invoke(error)
                }
            }
        }

        override fun pause() =
            onMain {
                paused = true
                try {
                    player?.takeIf { prepared && it.isPlaying }?.pause()
                } catch (_: Exception) {
                    interrupt("Could not pause synthesized speech.")
                }
                audio.abandonAudioFocusRequest(focus)
            }

        override fun resume() =
            onMain {
                if (player == null) return@onMain
                try {
                    if (current?.invoke() != true) {
                        stop()
                    } else if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        interrupt("Audio focus was not granted. Pause other audio and try again.")
                    } else {
                        paused = false
                        if (prepared) {
                            player?.start()
                            onStarted?.invoke()
                        }
                    }
                } catch (_: Exception) {
                    interrupt("Could not resume synthesized speech.")
                }
            }

        override fun stop() = onMain { finish?.invoke(CancellationException("Speech stopped")) ?: release() }

        private fun release() {
            finish = null
            player?.release()
            player = null
            current = null
            prepared = false
            audio.abandonAudioFocusRequest(focus)
        }

        private fun onMain(action: () -> Unit) {
            if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post(action)
        }
    }
