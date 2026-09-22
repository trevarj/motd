package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.prefs.ChatSoundConfig
import io.github.trevarj.motd.data.prefs.ChatSoundMelody
import io.github.trevarj.motd.data.prefs.ChatSoundVariation
import io.github.trevarj.motd.data.prefs.ChatSoundVoice
import io.github.trevarj.motd.data.prefs.matchesConfiguredNick
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
                senderIsFool = false,
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
                senderIsFool = false,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 8,
                bufferId = 7,
                type = BufferType.CHANNEL,
                muted = false,
                senderIsFool = false,
            ),
        )
    }

    @Test
    fun `incoming sound respects master mute fools and server buffers`() {
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = false,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = false,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = true,
                senderIsFool = false,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = true,
            ),
        )
        assertFalse(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.SERVER,
                muted = false,
                senderIsFool = false,
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
                senderIsFool = rfc.matchesConfiguredNick("listener^", configured),
            ),
        )
        assertTrue(
            shouldPlayIncomingChatSound(
                enabled = true,
                foregroundBufferId = 7,
                bufferId = 7,
                type = BufferType.QUERY,
                muted = false,
                senderIsFool = strict.matchesConfiguredNick("listener^", configured),
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
}
