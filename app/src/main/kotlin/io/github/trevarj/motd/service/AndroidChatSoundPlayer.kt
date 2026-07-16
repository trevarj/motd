package io.github.trevarj.motd.service

import android.media.AudioManager
import android.media.ToneGenerator
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.normalizeNick
import io.github.trevarj.motd.data.sync.ChatSoundPlayer
import io.github.trevarj.motd.irc.event.IrcEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

internal fun shouldPlayIncomingChatSound(
    enabled: Boolean,
    foregroundBufferId: Long?,
    bufferId: Long,
    type: BufferType,
    muted: Boolean,
    senderIsFool: Boolean,
): Boolean = enabled &&
    foregroundBufferId == bufferId &&
    type != BufferType.SERVER &&
    !muted &&
    !senderIsFool

internal fun shouldPlayOutgoingChatSound(
    enabled: Boolean,
    foregroundBufferId: Long?,
    bufferId: Long,
    muted: Boolean,
): Boolean = enabled && foregroundBufferId == bufferId && !muted

/**
 * Process-lifetime sonification using Android's short system tones. The system stream keeps the
 * sounds subordinate to the user's device sound policy and avoids shipping a second media stack.
 */
@Singleton
class AndroidChatSoundPlayer @Inject constructor(
    private val db: MotdDatabase,
    private val foregroundBufferTracker: ForegroundBufferTracker,
    private val settingsRepository: SettingsRepository,
) : ChatSoundPlayer {
    private val tones by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ToneGenerator(AudioManager.STREAM_SYSTEM, TONE_VOLUME)
    }

    override suspend fun onIncoming(
        bufferId: Long,
        type: BufferType,
        message: IrcEvent.ChatMessage,
    ) {
        val buffer = db.bufferDao().observeById(bufferId) ?: return
        val settings = settingsRepository.settings.first()
        if (
            shouldPlayIncomingChatSound(
                enabled = settings.chatSoundsEnabled,
                foregroundBufferId = foregroundBufferTracker.foregroundBufferId.value,
                bufferId = bufferId,
                type = type,
                muted = buffer.muted,
                senderIsFool = normalizeNick(message.source.nick) in settings.fools,
            )
        ) {
            play(ToneGenerator.TONE_PROP_ACK)
        }
    }

    override suspend fun onOutgoingAccepted(bufferId: Long) {
        val buffer = db.bufferDao().observeById(bufferId) ?: return
        val settings = settingsRepository.settings.first()
        if (
            shouldPlayOutgoingChatSound(
                enabled = settings.chatSoundsEnabled,
                foregroundBufferId = foregroundBufferTracker.foregroundBufferId.value,
                bufferId = bufferId,
                muted = buffer.muted,
            )
        ) {
            play(ToneGenerator.TONE_PROP_BEEP2)
        }
    }

    private fun play(tone: Int) {
        synchronized(tones) {
            tones.startTone(tone, TONE_DURATION_MS)
        }
    }

    private companion object {
        const val TONE_VOLUME = 24
        const val TONE_DURATION_MS = 35
    }
}
