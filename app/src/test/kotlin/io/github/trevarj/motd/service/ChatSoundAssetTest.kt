package io.github.trevarj.motd.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.prefs.ChatSoundConfig
import io.github.trevarj.motd.data.prefs.ChatSoundMelody
import io.github.trevarj.motd.data.prefs.ChatSoundTone
import io.github.trevarj.motd.data.prefs.ChatSoundVariation
import io.github.trevarj.motd.data.prefs.ChatSoundVoice
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class ChatSoundAssetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `configured sound catalog contains every voice cue tone and take`() {
        val files =
            context.assets
                .list("chat-sounds")
                ?.filter { it.endsWith(".wav") }
                .orEmpty()
        assertEquals(120, files.size)
        ChatSoundVoice.entries.forEach { voice ->
            ChatSoundCue.entries.forEach { cue ->
                ChatSoundTone.entries.forEach { tone ->
                    (0..4).forEach { take ->
                        assertTrue(ChatSoundAssetKey(voice, cue, tone, take).path.substringAfterLast('/') in files)
                    }
                }
            }
        }
        assertTrue(context.assets.list("chat-sounds")?.contains("catalog.json") == true)
        files.map { readWav("chat-sounds/$it") }.forEach { sound ->
            assertEquals(1, sound.channels)
            assertEquals(48_000, sound.sampleRate)
            assertEquals(16, sound.bitsPerSample)
            assertEquals(0, sound.samples.first().toInt())
            assertEquals(0, sound.samples.last().toInt())
            assertTrue(sound.durationMillis in 70.0..140.0)
            assertTrue(sound.peak <= 0.16)
        }
    }

    @Test
    fun `kotlin melody phrases match every catalog voice and melody`() {
        val catalog =
            context.assets
                .open("chat-sounds/catalog.json")
                .use { Json.parseToJsonElement(it.readBytes().decodeToString()) }
                .jsonObject
        val candidates = catalog.getValue("candidates").jsonArray
        ChatSoundVoice.entries.forEach { voice ->
            val candidate =
                candidates
                    .single {
                        it.jsonObject
                            .getValue("id")
                            .jsonPrimitive.content == voice.assetId
                    }.jsonObject
            val catalogMelodies = candidate.getValue("melodies").jsonArray
            ChatSoundMelody.entries.forEach { melody ->
                val catalogMotif =
                    catalogMelodies
                        .single {
                            it.jsonObject
                                .getValue("id")
                                .jsonPrimitive.content == melody.name.lowercase()
                        }.jsonObject
                        .getValue("motif")
                        .jsonArray
                        .map { it.jsonPrimitive.int }
                assertEquals(ChatSoundSequence.motifs.getValue(voice to melody).toList(), catalogMotif)
            }
        }
    }

    @Test
    fun `preview requests only its selected assets`() {
        val config = ChatSoundConfig()
        val selections = listOf(ChatSoundSelection(2, 0), ChatSoundSelection(2, 4), ChatSoundSelection(2, 7))

        assertEquals(
            listOf(ChatSoundAssetKey(ChatSoundVoice.SOFT_GLASS, ChatSoundCue.RECEIVE, ChatSoundTone.BALANCED, 2)),
            previewChatSoundAssetKeys(config, ChatSoundCue.RECEIVE, selections),
        )
    }

    @Test
    fun `warming only loads the current enabled sound choices`() {
        val defaults = warmChatSoundAssetKeys(ChatSoundConfig())
        assertEquals(
            listOf(
                ChatSoundAssetKey(ChatSoundVoice.SOFT_GLASS, ChatSoundCue.SEND, ChatSoundTone.BALANCED, 2),
                ChatSoundAssetKey(ChatSoundVoice.SOFT_GLASS, ChatSoundCue.RECEIVE, ChatSoundTone.BALANCED, 2),
            ),
            defaults,
        )

        val natural = warmChatSoundAssetKeys(ChatSoundConfig(variation = ChatSoundVariation.NATURAL))
        assertEquals(10, natural.size)
        assertEquals((0..4).toSet(), natural.filter { it.cue == ChatSoundCue.SEND }.map { it.take }.toSet())
        assertEquals((0..4).toSet(), natural.filter { it.cue == ChatSoundCue.RECEIVE }.map { it.take }.toSet())

        val disabled = ChatSoundConfig(send = ChatSoundConfig().send.copy(enabled = false), receive = ChatSoundConfig().receive.copy(volume = 0))
        assertTrue(warmChatSoundAssetKeys(disabled).isEmpty())
    }

    private fun readWav(path: String): WavInfo {
        val bytes = context.assets.open(path).use { it.readBytes() }
        assertEquals("RIFF", bytes.decodeToString(0, 4))
        assertEquals("WAVE", bytes.decodeToString(8, 12))
        assertEquals("fmt ", bytes.decodeToString(12, 16))
        assertEquals("data", bytes.decodeToString(36, 40))
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val dataSize = data.getInt(40)
        assertEquals(bytes.size - WAV_HEADER_BYTES, dataSize)
        val samples = ShortArray(dataSize / Short.SIZE_BYTES)
        data.position(WAV_HEADER_BYTES)
        data.asShortBuffer().get(samples)
        return WavInfo(
            channels = data.getShort(22).toInt(),
            sampleRate = data.getInt(24),
            bitsPerSample = data.getShort(34).toInt(),
            samples = samples,
            peak = samples.maxOf { abs(it.toInt()) } / Short.MAX_VALUE.toDouble(),
            durationMillis = samples.size * 1_000.0 / data.getInt(24),
        )
    }

    private data class WavInfo(
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val samples: ShortArray,
        val peak: Double,
        val durationMillis: Double,
    )

    private companion object {
        const val WAV_HEADER_BYTES = 44
    }
}
