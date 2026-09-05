package io.github.trevarj.motd.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AiModelsTest {
    @Test
    fun `persisted settings clamp to model and runtime limits`() {
        val metadata =
            AiModelMetadata(
                architecture = "test",
                quantization = "q8",
                maximumCpuThreads = 6,
            )

        assertEquals(
            6,
            TranscriptionSettings(cpuThreads = Int.MAX_VALUE)
                .clampedTo(metadata, availableProcessors = 8)
                .cpuThreads,
        )
        assertEquals(
            TranscriptionSettings(language = "auto", initialPrompt = " unchanged ", cpuThreads = 1),
            TranscriptionSettings(language = "", initialPrompt = " unchanged ", cpuThreads = 0)
                .clampedTo(metadata, availableProcessors = 8),
        )
    }

    @Test
    fun `model ids are lowercase sha256 and import progress is never serialized`() {
        assertThrows(IllegalArgumentException::class.java) {
            AiFeatureAssignment(AiFeature.TRANSCRIPTION, "A".repeat(64))
        }
        val state = AiLabsState(importState = AiImportState.Importing(10, 100))

        val encoded = Json.encodeToString(state)

        assertEquals(AiImportState.Idle, Json.decodeFromString<AiLabsState>(encoded).importState)
    }
}
