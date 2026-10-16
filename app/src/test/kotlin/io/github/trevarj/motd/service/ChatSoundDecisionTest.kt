package io.github.trevarj.motd.service

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.audio.AudioActivityTracker
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioCacheStatus
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.audio.AudioPlaybackRequest
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.audio.AudioWaveform
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.ChatSoundConfig
import io.github.trevarj.motd.data.prefs.ChatSoundMelody
import io.github.trevarj.motd.data.prefs.ChatSoundPrefs
import io.github.trevarj.motd.data.prefs.ChatSoundVariation
import io.github.trevarj.motd.data.prefs.ChatSoundVoice
import io.github.trevarj.motd.data.prefs.DataStoreSettingsRepository
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.di.ForegroundBufferTrackerImpl
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class ChatSoundDecisionTest {
    @Test
    fun `navigation or recording during preference loading suppresses playback`() =
        runTest {
            for (changeForeground in listOf(true, false)) {
                val readStarted = CompletableDeferred<Unit>()
                val loaded = CompletableDeferred<ChatSoundConfig>()
                var foreground: Long? = 7
                var audioActive = false
                var played = false
                val job =
                    launch {
                        dispatchConfiguredChatSound(
                            config =
                                flow {
                                    readStarted.complete(Unit)
                                    emit(loaded.await())
                                },
                            stillEligible = { foreground == 7L && !audioActive },
                            play = { played = true },
                        )
                    }
                readStarted.await()
                if (changeForeground) foreground = null else audioActive = true
                loaded.complete(ChatSoundConfig())
                job.join()
                assertFalse(played)
            }
        }

    @Test
    fun `conversation resets partial phrases and preserves receive burst across sends`() {
        val conversation = ChatSoundConversation()
        val config = ChatSoundConfig()

        fun receive(
            at: Long,
            buffer: Long = 7,
        ) = conversation.next(ChatSoundCue.RECEIVE, buffer, at, config)?.semitones
        assertEquals(0, receive(0))
        assertEquals(4, receive(100))
        assertEquals(0, conversation.next(ChatSoundCue.SEND, 8, 200, config)?.semitones)
        assertEquals(7, receive(300))
        assertEquals(0, receive(2_000_000_300))
        assertEquals(4, receive(2_000_000_400))
        assertEquals(0, receive(2_000_000_500, buffer = 8))
        listOf(4, 7, 2, 0).forEachIndexed { index, note -> assertEquals(note, receive(2_000_000_600L + index, buffer = 8)) }
        assertNull(receive(2_000_000_700, buffer = 8))
        assertNull(receive(4_000_000_699, buffer = 8))
        assertEquals(0, receive(6_000_000_699, buffer = 8))
        assertEquals(0, conversation.next(ChatSoundCue.RECEIVE, 8, 6_000_000_700, config.copy(receiveMelody = ChatSoundMelody.VICTORY))?.semitones)
    }

    @Test
    fun `five approved melodies preserve their configured phrases`() {
        val sequence = ChatSoundSequence()
        ChatSoundMelody.entries.forEach { melody ->
            val config = ChatSoundConfig(receive = ChatSoundConfig().receive.copy(voice = ChatSoundVoice.SYNTH_16_BIT), receiveMelody = melody)
            val actual = (0 until 5).map { sequence.next(ChatSoundCue.RECEIVE, config).semitones }
            assertEquals(ChatSoundSequence.motifs.getValue(ChatSoundVoice.SYNTH_16_BIT to melody).toList(), actual)
            sequence.reset()
        }
    }

    @Test
    fun `natural variants use all takes without adjacent repeats`() {
        val sequence = ChatSoundSequence { 0.37 }
        val config = ChatSoundConfig(variation = ChatSoundVariation.NATURAL)
        val takes = (0 until 10).map { sequence.next(ChatSoundCue.RECEIVE, config).take }
        assertEquals((0..4).toSet(), takes.take(5).toSet())
        assertTrue(takes.zipWithNext().none { (left, right) -> left == right })
    }

    @Test
    fun `receive gate caps a burst while suppressed arrivals extend its reset window`() {
        val gate = ChatReceiveBurstGate()
        assertTrue((0 until 5).all { gate.accept(7, it.toLong()).accepted })
        assertFalse(gate.accept(7, 6).accepted)
        assertFalse(gate.accept(7, 1_999_999_999).accepted)
        assertTrue(gate.accept(7, 4_000_000_000).accepted)
    }

    @Test
    fun `send and receive select distinct cues`() {
        assertEquals(
            ChatSoundCue.SEND,
            outgoingChatSoundCue(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                muted = false,
            ),
        )
        assertEquals(
            ChatSoundCue.RECEIVE,
            incomingChatSoundCue(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.CHANNEL,
                muted = false,
                senderAllowsNotification = true,
            ),
        )
    }

    @Test
    fun `incoming sound is limited to the open foreground chat`() {
        assertTrue(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.CHANNEL,
                muted = false,
                senderAllowsNotification = true,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 8,
                bufferId = 7,
                type = BufferType.CHANNEL,
                muted = false,
                senderAllowsNotification = true,
            ),
        )
    }

    @Test
    fun `incoming sound respects master mute category permission and server buffers`() {
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = false,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderAllowsNotification = true,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = true,
                senderAllowsNotification = true,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderAllowsNotification = false,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.SERVER,
                muted = false,
                senderAllowsNotification = true,
            ),
        )
    }

    @Test
    fun `outgoing sound requires enabled open unmuted chat`() {
        assertTrue(
            shouldPlayOutgoingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                muted = false,
            ),
        )
        assertFalse(
            shouldPlayOutgoingChatSound(
                enabled = true,
                foregroundBufferId = null,
                bufferId = 7,
                muted = false,
            ),
        )
        assertFalse(
            shouldPlayOutgoingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                muted = true,
            ),
        )
        assertNull(
            outgoingChatSoundCue(
                enabled = true,
                foregroundBufferId = null,
                bufferId = 7,
                muted = false,
            ),
        )
    }

    @Test
    fun `sound fool suppression follows the networks casemapping`() {
        val configured = setOf("listener~")
        val rfc = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459)
        val strict = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459Strict)

        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderAllowsNotification = !rfc.matchesConfiguredNick("listener^", configured),
            ),
        )
        assertTrue(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderAllowsNotification = !strict.matchesConfiguredNick("listener^", configured),
            ),
        )
    }

    @Test
    fun `sound fool suppression follows canonical account across nick changes`() {
        val rules = IrcIdentityRules(caseMapping = IrcCaseMapping.Ascii)

        assertTrue(
            isFoolForChatSound(
                fools = setOf("stable-account"),
                identityRules = rules,
                senderAccount = "stable-account",
                normalizedActor = rules.normalize("new-nick"),
            ),
        )
        assertFalse(
            isFoolForChatSound(
                fools = setOf("old-nick"),
                identityRules = rules,
                senderAccount = null,
                normalizedActor = rules.normalize("new-nick"),
            ),
        )
    }

    @Test
    fun `receive sounds use independent categories including hidden fools and mentioned DMs`() {
        for (visibility in FoolsMode.entries) {
            for (mentions in listOf(false, true)) {
                for (directMessages in listOf(false, true)) {
                    val settings =
                        Settings(
                            fools = setOf("fool"),
                            foolPreferences =
                                mapOf("fool" to FoolPreferences(visibility, mentions, directMessages)),
                        )
                    val policy = MessageVisibilityPolicy(MessageVisibilitySpec.from(settings))
                    for (type in listOf(BufferType.CHANNEL, BufferType.QUERY)) {
                        for (hasMention in listOf(false, true)) {
                            val allowed = policy.allowsNotification(null, "fool", type == BufferType.QUERY, hasMention)
                            val expected = if (type == BufferType.QUERY) directMessages else hasMention && mentions
                            assertEquals(
                                expected,
                                shouldPlayIncomingChatSound(true, 7, 7, type, false, allowed),
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `canonical reply and account facts govern receive sound without enabling TTS membership`() {
        val rules = IrcIdentityRules(caseMapping = IrcCaseMapping.Ascii)
        val settings =
            Settings(
                fools = setOf("stable-account"),
                foolPreferences =
                    mapOf("stable-account" to FoolPreferences(visibility = FoolsMode.HIDE, notifyMentions = true)),
            )
        val policy = MessageVisibilityPolicy(MessageVisibilitySpec.from(settings), rules)
        val canonical =
            MessageEntity(
                bufferId = 7,
                serverTime = 1_000,
                sender = "renamed",
                normalizedActor = rules.normalize("renamed"),
                senderAccount = "stable-account",
                kind = MessageKind.PRIVMSG,
                text = "reply without a literal nickname",
                hasMention = true,
                replyToMsgid = "self-parent",
                dedupKey = "reply",
            )

        fun cue(row: MessageEntity) =
            incomingChatSoundCue(
                true,
                7,
                row.bufferId,
                BufferType.CHANNEL,
                false,
                policy.allowsNotification(row.senderAccount, row.normalizedActor, false, row.hasMention),
            )
        assertEquals(ChatSoundCue.RECEIVE, cue(canonical))
        assertNull(cue(canonical.copy(hasMention = false)))
        assertFalse(policy.mentionAttention(canonical, directMessage = false))
        assertTrue(isFoolForChatSound(settings.fools, rules, canonical.senderAccount, canonical.normalizedActor))

        val colliding =
            MessageVisibilityPolicy(
                MessageVisibilitySpec.from(settings.copy(fools = settings.fools + "renamed")),
                rules,
            )
        assertFalse(colliding.allowsNotification(canonical.senderAccount, canonical.normalizedActor, false, true))
    }

    @Test
    fun `canonical adapter forwards reply mention to the actual foreground sound gate`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
            val repo = DataStoreSettingsRepository(context)
            val prefs = ChatSoundPrefs(context)
            val originalSettings = repo.settings.first()
            val originalConfig = prefs.config.first()
            val fool = "canonical-sound-fool"
            try {
                val networkId =
                    db.networkDao().insert(
                        NetworkEntity(
                            name = "sound",
                            role = NetworkRole.DIRECT,
                            host = "irc.example.org",
                            port = 6697,
                            nick = "me",
                            username = "me",
                            realname = "Me",
                        ),
                    )
                val bufferId =
                    db.bufferDao().insert(
                        BufferEntity(networkId = networkId, name = "#sound", displayName = "#sound", type = BufferType.CHANNEL),
                    )
                repo.setFool(fool, true)
                repo.setFoolPreferences(fool, FoolPreferences(notifyMentions = true))
                repo.setChatSoundsEnabled(true)
                prefs.replace(ChatSoundConfig())
                val tracker = ForegroundBufferTrackerImpl().also { it.set(bufferId) }
                val backend = SoundPoolChatSoundBackend(context, DiagnosticLogger.Noop, prefs, backgroundScope, StandardTestDispatcher(testScheduler))
                val playback =
                    object : AudioPlaybackController {
                        override val state = MutableStateFlow(AudioPlaybackState())
                        override val waveforms = MutableStateFlow<Map<String, AudioWaveform>>(emptyMap())
                        override val cacheStatuses = MutableStateFlow<Map<String, AudioCacheStatus>>(emptyMap())

                        override fun play(
                            request: AudioPlaybackRequest,
                            speed: Float,
                        ) = error("Unexpected media playback")

                        override fun toggle(request: AudioPlaybackRequest) = error("Unexpected media playback")

                        override fun inspectCache(attachment: AudioAttachment) = error("Unexpected media playback")

                        override fun toggleActive() = error("Unexpected media playback")

                        override fun pause() = error("Unexpected media playback")

                        override fun dismiss(itemId: String) = error("Unexpected media playback")

                        override fun cancelLoading() = error("Unexpected media playback")

                        override fun retryActive() = error("Unexpected media playback")

                        override fun seekTo(
                            itemId: String,
                            positionMs: Long,
                        ) = error("Unexpected media playback")

                        override fun setSpeed(
                            itemId: String,
                            speed: Float,
                        ) = error("Unexpected media playback")
                    }
                val canonical =
                    MessageEntity(
                        bufferId = bufferId,
                        serverTime = 1_000,
                        sender = "renamed",
                        normalizedActor = "renamed",
                        senderAccount = fool,
                        kind = MessageKind.PRIVMSG,
                        text = "reply without a literal nickname",
                        replyToMsgid = "self-parent",
                        dedupKey = "canonical-sound",
                    )
                val message =
                    IrcEvent.ChatMessage(
                        ctx = MessageContext(msgid = null, serverTime = 1_000, account = null, batchId = null, label = null),
                        kind = IrcEvent.ChatKind.PRIVMSG,
                        source = Prefix("renamed"),
                        target = "#sound",
                        text = canonical.text,
                        isSelf = false,
                        replyToMsgid = "self-parent",
                    )
                for (hasMention in listOf(false, true)) {
                    val player = AndroidChatSoundPlayer(db, tracker, repo, prefs, backend, playback, AudioActivityTracker())
                    player.onCanonicalIncoming(bufferId, BufferType.CHANNEL, message, canonical.copy(hasMention = hasMention))
                    // Observe accepted cues before SoundPool's asynchronous asset-loading boundary.
                    val conversation = ReflectionHelpers.getField<ChatSoundConversation>(player, "conversation")
                    assertEquals(
                        if (hasMention) 4 else 0,
                        conversation.next(ChatSoundCue.RECEIVE, bufferId, android.os.SystemClock.elapsedRealtimeNanos(), ChatSoundConfig())?.semitones,
                    )
                }
            } finally {
                repo.setFool(fool, false)
                repo.setChatSoundsEnabled(originalSettings.chatSoundsEnabled)
                prefs.replace(originalConfig)
                db.close()
            }
        }
}
