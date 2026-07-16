package io.github.trevarj.motd.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatSoundAssetTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `chat cues are deterministic short mono PCM with click-free boundaries`() {
        val send = readWav(R.raw.chat_send)
        val receive = readWav(R.raw.chat_receive)

        listOf(send, receive).forEach { sound ->
            assertEquals(1, sound.channels)
            assertEquals(48_000, sound.sampleRate)
            assertEquals(16, sound.bitsPerSample)
            assertEquals(0, sound.samples.first().toInt())
            assertEquals(0, sound.samples.last().toInt())
        }
        assertTrue(send.durationMillis in 55.0..65.0)
        assertTrue(receive.durationMillis in 55.0..65.0)
        assertEquals(send.durationMillis, receive.durationMillis, 0.01)
        assertTrue(send.peak in 0.35..0.36)
        assertTrue(receive.peak in 0.50..0.51)
        assertTrue(send.peak < receive.peak)
        assertTrue(send.magnitudeAt(SEND_PITCH) > receive.magnitudeAt(SEND_PITCH))
        assertTrue(receive.magnitudeAt(RECEIVE_PITCH) > send.magnitudeAt(RECEIVE_PITCH))
        assertTrue(send.magnitudeAt(SEND_PITCH) > send.magnitudeAt(800.0) * 2)
        assertTrue(receive.magnitudeAt(RECEIVE_PITCH) > receive.magnitudeAt(800.0) * 2)
        assertFalse(send.samples.contentEquals(receive.samples))
    }

    private fun readWav(resourceId: Int): WavInfo {
        val bytes = resources.openRawResource(resourceId).use { it.readBytes() }
        assertEquals("RIFF", bytes.decodeToString(0, 4))
        assertEquals("WAVE", bytes.decodeToString(8, 12))
        assertEquals("fmt ", bytes.decodeToString(12, 16))
        assertEquals("data", bytes.decodeToString(36, 40))

        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val channels = data.getShort(22).toInt()
        val sampleRate = data.getInt(24)
        val bitsPerSample = data.getShort(34).toInt()
        val dataSize = data.getInt(40)
        assertEquals(bytes.size - WAV_HEADER_BYTES, dataSize)
        val samples = ShortArray(dataSize / Short.SIZE_BYTES)
        data.position(WAV_HEADER_BYTES)
        data.asShortBuffer().get(samples)
        val peak = samples.maxOf { abs(it.toInt()) } / Short.MAX_VALUE.toDouble()
        val durationMillis = samples.size * 1_000.0 / sampleRate
        return WavInfo(channels, sampleRate, bitsPerSample, samples, peak, durationMillis)
    }

    private data class WavInfo(
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val samples: ShortArray,
        val peak: Double,
        val durationMillis: Double,
    ) {
        val zeroCrossingRate: Double
            get() {
                var crossings = 0
                for (index in 1 until samples.size) {
                    val left = samples[index - 1]
                    val right = samples[index]
                    if ((left < 0 && right >= 0) || (left >= 0 && right < 0)) {
                        crossings += 1
                    }
                }
                return crossings.toDouble() / (samples.size - 1).coerceAtLeast(1)
            }

        fun magnitudeAt(frequency: Double): Double {
            var real = 0.0
            var imaginary = 0.0
            samples.forEachIndexed { index, sample ->
                val angle = 2.0 * Math.PI * frequency * index / sampleRate
                real += sample * kotlin.math.cos(angle)
                imaginary -= sample * kotlin.math.sin(angle)
            }
            return kotlin.math.hypot(real, imaginary)
        }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44
        const val SEND_PITCH = 1_108.73
        val RECEIVE_PITCH = SEND_PITCH * 2.0.pow(5.0 / 12.0)
    }
}
