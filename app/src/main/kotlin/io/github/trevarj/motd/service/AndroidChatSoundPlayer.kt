package io.github.trevarj.motd.service

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.audio.AudioActivityTracker
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.identityRules
import io.github.trevarj.motd.data.prefs.ChatSoundConfig
import io.github.trevarj.motd.data.prefs.ChatSoundMelody
import io.github.trevarj.motd.data.prefs.ChatSoundPrefs
import io.github.trevarj.motd.data.prefs.ChatSoundTone
import io.github.trevarj.motd.data.prefs.ChatSoundVariation
import io.github.trevarj.motd.data.prefs.ChatSoundVoice
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.sync.ChatSoundPlayer
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.di.IoDispatcher
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

internal enum class ChatSoundCue { SEND, RECEIVE }

internal fun shouldPlayIncomingChatSound(
    enabled: Boolean,
    foregroundBufferId: Long?,
    bufferId: Long,
    type: BufferType,
    muted: Boolean,
    senderIsFool: Boolean,
): Boolean = enabled && foregroundBufferId == bufferId && type != BufferType.SERVER && !muted && !senderIsFool

internal fun shouldPlayOutgoingChatSound(
    enabled: Boolean,
    foregroundBufferId: Long?,
    bufferId: Long,
    muted: Boolean,
): Boolean = enabled && foregroundBufferId == bufferId && !muted

internal fun isFoolForChatSound(
    fools: Set<String>,
    identityRules: IrcIdentityRules,
    senderAccount: String?,
    normalizedActor: String,
): Boolean = MessageVisibilityPolicy(MessageVisibilitySpec(fools = fools), identityRules).matchesFoolIdentity(senderAccount, normalizedActor)

internal fun incomingChatSoundCue(
    enabled: Boolean,
    foregroundBufferId: Long?,
    bufferId: Long,
    type: BufferType,
    muted: Boolean,
    senderIsFool: Boolean,
): ChatSoundCue? = ChatSoundCue.RECEIVE.takeIf { shouldPlayIncomingChatSound(enabled, foregroundBufferId, bufferId, type, muted, senderIsFool) }

internal fun outgoingChatSoundCue(
    enabled: Boolean,
    foregroundBufferId: Long?,
    bufferId: Long,
    muted: Boolean,
): ChatSoundCue? = ChatSoundCue.SEND.takeIf { shouldPlayOutgoingChatSound(enabled, foregroundBufferId, bufferId, muted) }

/** A quiet gap, buffer change, or configuration change starts a fresh receive phrase. */
internal class ChatReceiveBurstGate {
    private var lastBufferId: Long? = null
    private var lastAtNanos = Long.MIN_VALUE
    private var accepted = 0

    fun accept(
        bufferId: Long,
        nowNanos: Long,
    ): Result =
        synchronized(this) {
            val reset = lastBufferId != bufferId || nowNanos - lastAtNanos >= RESET_AFTER_SILENCE_NANOS
            if (reset) accepted = 0
            lastBufferId = bufferId
            lastAtNanos = nowNanos
            val allowed = accepted < MAX_PER_BURST
            if (allowed) accepted++
            Result(allowed, reset)
        }

    fun reset() =
        synchronized(this) {
            lastBufferId = null
            lastAtNanos = Long.MIN_VALUE
            accepted = 0
        }

    data class Result(
        val accepted: Boolean,
        val reset: Boolean,
    )

    private companion object {
        const val MAX_PER_BURST = 5
        const val RESET_AFTER_SILENCE_NANOS = 2_000_000_000L
    }
}

internal data class ChatSoundSelection(
    val take: Int,
    val semitones: Int,
)

/** Natural picks are independent by direction; sends never affect an incoming phrase. */
internal class ChatSoundSequence(
    private val random: () -> Double = Math::random,
) {
    private val bags = mutableMapOf(ChatSoundCue.SEND to mutableListOf<Int>(), ChatSoundCue.RECEIVE to mutableListOf())
    private val last = mutableMapOf<ChatSoundCue, Int?>()
    private val melodyIndex = mutableMapOf<Pair<ChatSoundVoice, ChatSoundMelody>, Int>()

    fun next(
        cue: ChatSoundCue,
        config: ChatSoundConfig,
    ): ChatSoundSelection =
        synchronized(this) {
            when (config.variation) {
                ChatSoundVariation.FIXED -> {
                    ChatSoundSelection(2, 0)
                }

                ChatSoundVariation.MUSICAL -> {
                    if (cue == ChatSoundCue.SEND) {
                        ChatSoundSelection(2, 0)
                    } else {
                        val key = config.receive.voice to config.receiveMelody
                        val notes = motifs.getValue(key)
                        val index = melodyIndex.getOrDefault(key, 0) % notes.size
                        melodyIndex[key] = index + 1
                        ChatSoundSelection(2, notes[index])
                    }
                }

                ChatSoundVariation.NATURAL -> {
                    val bag = bags.getValue(cue)
                    if (bag.isEmpty()) {
                        bag += (0..4).shuffled(java.util.Random((random() * Long.MAX_VALUE).toLong()))
                        if (bag.firstOrNull() == last[cue]) java.util.Collections.swap(bag, 0, 1)
                    }
                    ChatSoundSelection(bag.removeAt(0).also { last[cue] = it }, 0)
                }
            }
        }

    fun reset() =
        synchronized(this) {
            bags.values.forEach { it.clear() }
            last.clear()
            melodyIndex.clear()
        }

    fun resetReceive() =
        synchronized(this) {
            // Silence restarts the melody, while natural takes retain their no-repeat history.
            melodyIndex.clear()
        }

    companion object {
        private val templates =
            mapOf(
                ChatSoundMelody.CLIMB to intArrayOf(0, 2, 4, 5, 0),
                ChatSoundMelody.RELAY to intArrayOf(0, 4, 2, 5, 0),
                ChatSoundMelody.BEACON to intArrayOf(0, 2, 7, 4, 0),
                ChatSoundMelody.VICTORY to intArrayOf(0, 4, 5, 7, 0),
            )
        private val homecoming =
            mapOf(
                ChatSoundVoice.SOFT_GLASS to intArrayOf(0, 4, 7, 2, 0),
                ChatSoundVoice.TERMINAL_TICK to intArrayOf(0, 7, 4, 2, 0),
                ChatSoundVoice.ARCADE_PLUCK to intArrayOf(0, 4, 2, 7, 0),
                ChatSoundVoice.SYNTH_16_BIT to intArrayOf(0, 4, 7, 4, 0),
            )
        internal val motifs: Map<Pair<ChatSoundVoice, ChatSoundMelody>, IntArray> =
            ChatSoundVoice.entries
                .flatMap { voice ->
                    ChatSoundMelody.entries.map { melody ->
                        (voice to melody) to (if (melody == ChatSoundMelody.HOMECOMING) homecoming.getValue(voice) else templates.getValue(melody))
                    }
                }.toMap()
    }
}

internal data class ChatSoundAssetKey(
    val voice: ChatSoundVoice,
    val cue: ChatSoundCue,
    val tone: ChatSoundTone,
    val take: Int,
) {
    val path: String get() = "chat-sounds/${voice.assetId}-${cue.name.lowercase()}-${tone.name.lowercase()}-$take.wav"
}

internal fun previewChatSoundAssetKeys(
    config: ChatSoundConfig,
    cue: ChatSoundCue,
    selections: List<ChatSoundSelection>,
): List<ChatSoundAssetKey> {
    val cueConfig = if (cue == ChatSoundCue.SEND) config.send else config.receive
    return selections
        .map { ChatSoundAssetKey(cueConfig.voice, cue, cueConfig.tone, it.take) }
        .distinct()
}

internal fun warmChatSoundAssetKeys(config: ChatSoundConfig): List<ChatSoundAssetKey> {
    if (config.masterVolume == 0) return emptyList()
    val takes = if (config.variation == ChatSoundVariation.NATURAL) 0..4 else 2..2
    return ChatSoundCue.entries.flatMap { cue ->
        val cueConfig = if (cue == ChatSoundCue.SEND) config.send else config.receive
        if (!cueConfig.enabled || cueConfig.volume == 0) {
            emptyList()
        } else {
            takes.map { take -> ChatSoundAssetKey(cueConfig.voice, cue, cueConfig.tone, take) }
        }
    }
}

/** One atomic decision owns the receive limit and phrase; send activity is independent. */
internal class ChatSoundConversation {
    private val gate = ChatReceiveBurstGate()
    private val sequence = ChatSoundSequence()
    private var lastConfig: ChatSoundConfig? = null

    fun next(
        cue: ChatSoundCue,
        bufferId: Long,
        nowNanos: Long,
        config: ChatSoundConfig,
    ): ChatSoundSelection? =
        synchronized(this) {
            if (lastConfig != config) {
                gate.reset()
                sequence.reset()
                lastConfig = config
            }
            val choice = if (cue == ChatSoundCue.SEND) config.send else config.receive
            if (!choice.enabled || choice.volume == 0 || config.masterVolume == 0) return@synchronized null
            if (cue == ChatSoundCue.RECEIVE) {
                val result = gate.accept(bufferId, nowNanos)
                if (result.reset) sequence.resetReceive()
                if (!result.accepted) return@synchronized null
            }
            sequence.next(cue, config)
        }
}

/** Preferences can suspend; eligibility must be checked after they arrive. */
internal suspend fun dispatchConfiguredChatSound(
    config: Flow<ChatSoundConfig>,
    stillEligible: () -> Boolean,
    play: (ChatSoundConfig) -> Unit,
) {
    val selected = config.first()
    if (stillEligible()) play(selected)
}

@Singleton
internal class SoundPoolChatSoundBackend
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val diagnostics: DiagnosticLogger,
        private val chatSoundPrefs: ChatSoundPrefs,
        @ApplicationScope private val applicationScope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        private val pool =
            runCatching {
                SoundPool
                    .Builder()
                    .setMaxStreams(5)
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    ).build()
            }.onFailure { trace("create_failed", it) }.getOrNull()
        private val samples = ConcurrentHashMap<ChatSoundAssetKey, Int>()
        private val ready = ConcurrentHashMap<Int, Boolean>()
        private val recentStreams = ArrayDeque<Int>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private val preview =
            ChatSoundPreview(
                nowMs = android.os.SystemClock::uptimeMillis,
                post = { task, delay -> mainHandler.postDelayed(task, delay) },
                remove = { mainHandler.removeCallbacks(it) },
                stopPlaying = ::stopStreams,
            )

        init {
            pool?.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready[id] = true }
            applicationScope.launch(ioDispatcher) {
                chatSoundPrefs.config.collect { config -> warmChatSoundAssets(config) }
            }
        }

        fun play(
            config: ChatSoundConfig,
            cue: ChatSoundCue,
            selection: ChatSoundSelection,
        ) {
            val cueConfig = if (cue == ChatSoundCue.SEND) config.send else config.receive
            if (!cueConfig.enabled || config.masterVolume == 0 || cueConfig.volume == 0) return
            val key = ChatSoundAssetKey(cueConfig.voice, cue, cueConfig.tone, selection.take)
            val id = loadIfNeeded(key) ?: return
            if (ready[id] != true) return // Drop loading-time events rather than playing stale feedback later.
            val volume = config.masterVolume / 100f * cueConfig.volume / 100f
            val rate = 2.0.pow((selection.semitones + cueConfig.pitch) / 12.0).toFloat()
            synchronized(recentStreams) {
                runCatching { pool?.play(id, volume, volume, 1, 0, rate) ?: 0 }.onFailure { trace("play_failed", it) }.getOrDefault(0).takeIf { it != 0 }?.let { stream ->
                    recentStreams += stream
                    while (recentStreams.size > 5) runCatching { pool?.stop(recentStreams.removeFirst()) }
                }
            }
        }

        fun preview(
            config: ChatSoundConfig,
            cue: ChatSoundCue,
        ) {
            val notes = if (cue == ChatSoundCue.RECEIVE) ChatSoundSequence.motifs.getValue(config.receive.voice to config.receiveMelody) else intArrayOf(0)
            val previewSequence = ChatSoundSequence()
            val selections =
                notes.map { note ->
                    if (config.variation == ChatSoundVariation.MUSICAL && cue == ChatSoundCue.RECEIVE) {
                        ChatSoundSelection(2, note)
                    } else {
                        previewSequence.next(cue, config)
                    }
                }
            val keys = previewChatSoundAssetKeys(config, cue, selections)
            keys.forEach(::preload)
            preview.start(
                noteCount = selections.size,
                ready = { keys.all { samples[it]?.let { id -> ready[id] == true } == true } },
                playNote = { play(config, cue, selections[it]) },
            )
        }

        fun stop() = preview.stop()

        private fun stopStreams() {
            synchronized(recentStreams) {
                recentStreams.forEach { runCatching { pool?.stop(it) } }
                recentStreams.clear()
            }
        }

        private fun preload(key: ChatSoundAssetKey) = loadIfNeeded(key)

        private fun warmChatSoundAssets(config: ChatSoundConfig) {
            warmChatSoundAssetKeys(config).forEach(::preload)
        }

        private fun loadIfNeeded(key: ChatSoundAssetKey): Int? = samples[key] ?: synchronized(samples) { samples[key] ?: load(key) }

        private fun load(key: ChatSoundAssetKey): Int? =
            runCatching {
                context.assets.openFd(key.path).use { pool?.load(it, 1) ?: 0 }
            }.onFailure { trace("load_failed", it) }.getOrNull()?.takeIf { it != 0 }?.also { samples[key] = it }

        private fun trace(
            event: String,
            error: Throwable,
        ) {
            diagnostics.record("chat_sound", event) { mapOf("error" to error::class.simpleName) }
        }
    }

@Singleton
class AndroidChatSoundPlayer
    @Inject
    internal constructor(
        private val db: MotdDatabase,
        private val foregroundBufferTracker: ForegroundBufferTracker,
        private val settingsRepository: SettingsRepository,
        private val chatSoundPrefs: ChatSoundPrefs,
        private val backend: SoundPoolChatSoundBackend,
        private val audioPlaybackController: AudioPlaybackController,
        private val audioActivityTracker: AudioActivityTracker,
    ) : ChatSoundPlayer {
        private val conversation = ChatSoundConversation()

        override suspend fun onIncoming(
            bufferId: Long,
            type: BufferType,
            message: IrcEvent.ChatMessage,
        ) = playIncoming(bufferId, type, message.ctx.account, message.source.nick, null)

        override suspend fun onCanonicalIncoming(
            bufferId: Long,
            type: BufferType,
            message: IrcEvent.ChatMessage,
            canonical: MessageEntity,
        ) = playIncoming(bufferId, type, canonical.senderAccount, null, canonical.normalizedActor)

        private suspend fun playIncoming(
            bufferId: Long,
            type: BufferType,
            senderAccount: String?,
            senderNick: String?,
            normalizedActor: String?,
        ) {
            val buffer = db.bufferDao().observeById(bufferId) ?: return
            if (audioActivityTracker.recording.value || audioPlaybackController.state.value.playing) return
            val settings = settingsRepository.settings.first()
            val rules = db.networkIdentityDao().byNetwork(buffer.networkId)?.identityRules ?: IrcIdentityRules()
            val eligible = incomingChatSoundCue(settings.chatSoundsEnabled, foregroundBufferTracker.foregroundBufferId.value, bufferId, type, buffer.muted, isFoolForChatSound(settings.fools, rules, senderAccount, normalizedActor ?: rules.normalize(senderNick.orEmpty())))
            if (eligible == null) return
            playConfigured(ChatSoundCue.RECEIVE, bufferId)
        }

        override suspend fun onOutgoingAccepted(bufferId: Long) {
            val buffer = db.bufferDao().observeById(bufferId) ?: return
            if (audioActivityTracker.recording.value || audioPlaybackController.state.value.playing) return
            val settings = settingsRepository.settings.first()
            if (outgoingChatSoundCue(settings.chatSoundsEnabled, foregroundBufferTracker.foregroundBufferId.value, bufferId, buffer.muted) == null) return
            playConfigured(ChatSoundCue.SEND, bufferId)
        }

        private suspend fun playConfigured(
            cue: ChatSoundCue,
            bufferId: Long,
        ) = dispatchConfiguredChatSound(
            config = chatSoundPrefs.config,
            stillEligible = {
                foregroundBufferTracker.foregroundBufferId.value == bufferId &&
                    !audioActivityTracker.recording.value && !audioPlaybackController.state.value.playing
            },
        ) { config ->
            synchronized(this) {
                conversation.next(cue, bufferId, android.os.SystemClock.elapsedRealtimeNanos(), config)?.let {
                    backend.play(config, cue, it)
                }
            }
        }

        fun previewSend(config: ChatSoundConfig) = backend.preview(config, ChatSoundCue.SEND)

        fun previewReceiveMelody(config: ChatSoundConfig) = backend.preview(config, ChatSoundCue.RECEIVE)

        fun stopPreview() = backend.stop()
    }
