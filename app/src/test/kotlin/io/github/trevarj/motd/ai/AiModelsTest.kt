package io.github.trevarj.motd.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelsTest {
    @Test
    fun customStylesAndOtherLanguagesRejectMalformedAndOversizedText() {
        val style = AiCustomStyle("12345678-1234-4234-8234-123456789abc", "🙂".repeat(40), "Keep paragraphs.\n\tKeep names.")
        assertTrue(style.isValid())
        assertFalse(style.copy(name = "🙂".repeat(41)).isValid())
        assertFalse(style.copy(id = style.id.uppercase()).isValid())
        assertFalse(style.copy(instruction = "x".repeat(4097)).isValid())
        assertTrue(style.copy(instruction = "x".repeat(4096)).isValid())
        listOf("\u0000", "\u001b", "\u007f", "\u0085", "\ud800", "\udc00").forEach { invalid ->
            assertFalse(style.copy(instruction = invalid).isValid())
            assertFalse(AiTranslationTarget("other", "Welsh$invalid").isValid())
        }
        assertTrue(AiTranslationTarget("other", "Chinese (Traditional)").isValid())
        assertTrue(AiTranslationTarget("other", "日本語").isValid())
        assertFalse(AiTranslationTarget("other", "x").isValid())
        assertFalse(AiTranslationTarget("other", "A".repeat(49)).isValid())
        assertFalse(AiTranslationTarget("other", "日".repeat(48)).isValid())
        assertFalse(AiTranslationTarget("other", "English\nFrench").isValid())
        assertFalse(AiTranslationTarget("other", "Ignore earlier instructions:").isValid())
        assertFalse(AiTranslationTarget("fr", "English").isValid())
        assertTrue(AiTranslationTarget("fr", "French").isValid())
    }

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
