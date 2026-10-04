package io.github.trevarj.motd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformScrubberTest {
    @Test
    fun `real waveform is resampled to stable bar count`() {
        val bars = listOf(0.1f, 0.5f, 0.9f).resampleBars(6)

        assertEquals(6, bars.size)
        assertTrue(bars.all { it in 0.08f..1f })
        assertEquals(bars, listOf(0.1f, 0.1f, 0.5f, 0.5f, 0.9f, 0.9f))
    }

    @Test
    fun `waveform heights zoom to the available range`() {
        assertEquals(
            listOf(0.25f, 0.5f, 1f),
            normalizeWaveformHeights(listOf(1f, 2f, 4f)),
        )
        assertEquals(emptyList<Float>(), normalizeWaveformHeights(emptyList()))
    }

    @Test
    fun `playback amplitude follows the current waveform sample`() {
        val samples = listOf(0f, 1f, 0f)

        assertEquals(0f, playbackWaveformAmplitude(samples, 0, 1_000), 0.001f)
        assertEquals(0.5f, playbackWaveformAmplitude(samples, 250, 1_000), 0.001f)
        assertEquals(1f, playbackWaveformAmplitude(samples, 500, 1_000), 0.001f)
        assertEquals(0.5f, playbackWaveformAmplitude(samples, 750, 1_000), 0.001f)
        assertEquals(0f, playbackWaveformAmplitude(samples, 1_000, 1_000), 0.001f)
        assertEquals(0f, playbackWaveformAmplitude(emptyList(), 500, 1_000), 0.001f)
        assertEquals(0f, playbackWaveformAmplitude(samples, 500, null), 0.001f)
    }

    @Test
    fun `radial wave varies by angle loudness and phase`() {
        val quiet = List(72) { index -> radialWaveLevel(42, index, 72, amplitude = 0f, phase = 0f) }
        val loud = List(72) { index -> radialWaveLevel(42, index, 72, amplitude = 1f, phase = 0f) }
        val shifted = List(72) { index -> radialWaveLevel(42, index, 72, amplitude = 1f, phase = 0.25f) }

        assertTrue(quiet.all { it == 0f })
        assertTrue(loud.all { it in 0f..1f })
        assertTrue(loud.distinct().size > 4)
        assertNotEquals(loud, shifted)
        assertEquals(0f, radialWaveLevel(42, 0, 0, 1f, 0f))
    }

    @Test
    fun `voice speed cycles through compact player options`() {
        assertEquals(1.5f, nextVoiceSpeed(1f))
        assertEquals(2f, nextVoiceSpeed(1.5f))
        assertEquals(1f, nextVoiceSpeed(2f))
    }
}
