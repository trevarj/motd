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
import java.nio.ShortBuffer
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class ChatSoundAssetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `configured sound catalog contains every voice cue tone and take`() {
        val keys =
            ChatSoundVoice.entries.flatMap { voice ->
                ChatSoundCue.entries.flatMap { cue ->
                    ChatSoundTone.entries.flatMap { tone ->
                        (0..4).map { take -> ChatSoundAssetKey(voice, cue, tone, take) }
                    }
                }
            }
        val expectedFiles = keys.map { it.path.substringAfterLast('/') }.toSet()
        assertEquals(240, expectedFiles.size)
        val packagedFiles =
            context.assets
                .list("chat-sounds")
                .orEmpty()
                .toSet()
        assertEquals(expectedFiles + "catalog.json", packagedFiles)

        for ((voiceAndCue, cueKeys) in keys.groupBy { it.voice to it.cue }) {
            val acoustic = voiceAndCue.first in acousticVoices
            val maxDuration = if (acoustic) 270.0 else 140.0
            val samples =
                cueKeys.map { key ->
                    val sound = readWav(key.path)
                    assertEquals(key.path, 1, sound.channels)
                    assertEquals(key.path, 48_000, sound.sampleRate)
                    assertEquals(key.path, 16, sound.bitsPerSample)
                    assertEquals(key.path, 0, sound.samples.first().toInt())
                    assertEquals(key.path, 0, sound.samples.last().toInt())
                    assertTrue("${key.path}: ${sound.durationMillis} ms", sound.durationMillis in 70.0..maxDuration)
                    assertTrue("${key.path}: peak ${sound.peak}", sound.peak > 0.0 && sound.peak <= 0.16)
                    ShortBuffer.wrap(sound.samples)
                }
            if (acoustic) {
                assertEquals("$voiceAndCue must have distinct audio for each tone/take", samples.size, samples.toSet().size)
            }
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
        val catalogIds =
            candidates.map {
                it.jsonObject
                    .getValue("id")
                    .jsonPrimitive.content
            }
        assertEquals(ChatSoundVoice.entries.map { it.assetId }.sorted(), catalogIds.sorted())
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
            listOf(ChatSoundAssetKey(ChatSoundVoice.SOFT_VIBES, ChatSoundCue.RECEIVE, ChatSoundTone.BALANCED, 2)),
            previewChatSoundAssetKeys(config, ChatSoundCue.RECEIVE, selections),
        )
    }

    @Test
    fun `warming only loads the current enabled sound choices`() {
        val defaults = warmChatSoundAssetKeys(ChatSoundConfig())
        assertEquals(
            listOf(
                ChatSoundAssetKey(ChatSoundVoice.SOFT_VIBES, ChatSoundCue.SEND, ChatSoundTone.BALANCED, 2),
                ChatSoundAssetKey(ChatSoundVoice.SOFT_VIBES, ChatSoundCue.RECEIVE, ChatSoundTone.BALANCED, 2),
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
        assertEquals("$path: PCM encoding", 1, data.getShort(20).toInt())
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
        val acousticVoices =
            setOf(
                ChatSoundVoice.SOFT_VIBES,
                ChatSoundVoice.WIRE_ZITHER,
                ChatSoundVoice.MALLET_XYLOPHONE,
                ChatSoundVoice.DRY_WOODBLOCK,
            )
    }
}
