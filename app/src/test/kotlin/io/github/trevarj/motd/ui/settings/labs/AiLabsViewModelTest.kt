package io.github.trevarj.motd.ui.settings.labs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.ai.AiDerivedCacheCleaner
import io.github.trevarj.motd.ai.AiFeature
import io.github.trevarj.motd.ai.AiFeatureAssignment
import io.github.trevarj.motd.ai.AiImportState
import io.github.trevarj.motd.ai.AiLabsState
import io.github.trevarj.motd.ai.AiModelCapability
import io.github.trevarj.motd.ai.AiModelFormat
import io.github.trevarj.motd.ai.AiModelMetadata
import io.github.trevarj.motd.ai.AiModelRecord
import io.github.trevarj.motd.ai.AiTranscriptCache
import io.github.trevarj.motd.ai.AiTranscriptionSettingsRecord
import io.github.trevarj.motd.ai.TranscriptionSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiLabsViewModelTest {
    @Test
    fun `voice is unavailable until the assigned model has compatible recognition settings`() {
        val speech = model('a')
        val invalid =
            AiLabsState(
                enabledFeatures = setOf(AiFeature.TRANSCRIPTION),
                models = listOf(speech),
                assignments = listOf(AiFeatureAssignment(AiFeature.TRANSCRIPTION, speech.id)),
                transcriptionSettings = listOf(AiTranscriptionSettingsRecord(speech.id, TranscriptionSettings(language = "not-a-language"))),
            )
        val unavailable = deriveAiLabsUiState(invalid, null, false).feature(AiFeature.TRANSCRIPTION)
        assertFalse(unavailable.enabled)
        assertFalse(unavailable.ready)
        assertTrue(unavailable.selectableModels.isEmpty())

        val valid = invalid.copy(transcriptionSettings = listOf(AiTranscriptionSettingsRecord(speech.id, TranscriptionSettings(language = "en"))))
        val available = deriveAiLabsUiState(valid, null, false).feature(AiFeature.TRANSCRIPTION)
        assertTrue(available.enabled)
        assertTrue(available.ready)
        assertEquals(listOf(speech.id), available.selectableModels.map { it.id })
    }

    @Test
    fun `provider size estimates cannot put import progress outside its bounds`() {
        fun progress(
            copied: Long,
            total: Long?,
        ) = deriveAiLabsUiState(AiLabsState(importState = AiImportState.Importing(copied, total)), null, false).importProgress?.fraction

        assertEquals(0.25f, progress(25, 100))
        assertEquals(1f, progress(150, 100))
        assertEquals(0f, progress(-1, 100))
        assertNull(progress(25, 0))
        assertNull(progress(25, null))
    }

    @Test
    fun `clearing transcripts preserves imported models and settings`() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val transcriptCache = AiTranscriptCache(context, Dispatchers.Unconfined)
            transcriptCache.clear()
            val transcriptKey =
                AiTranscriptCache.key(
                    "a".repeat(64),
                    "b".repeat(64),
                    TranscriptionSettings(language = "en", initialPrompt = "test", cpuThreads = 1),
                )
            transcriptCache.put(transcriptKey, "derived")
            val model =
                File(context.noBackupFilesDir, "ai-models/viewmodel-test.model").apply {
                    parentFile!!.mkdirs()
                    writeText("model")
                }
            val settings =
                File(context.filesDir, "datastore/ai_labs.preferences_pb").apply {
                    parentFile!!.mkdirs()
                    writeText("settings")
                }

            try {
                assertTrue(AiDerivedCacheCleaner(transcriptCache).clear().isSuccess)
                assertNull(transcriptCache.get(transcriptKey))
                assertEquals("model", model.readText())
                assertEquals("settings", settings.readText())
            } finally {
                model.delete()
                settings.delete()
                transcriptCache.clear()
            }
        }

    @Test
    fun `disable replacement delete and manual clear invalidate completed transcripts`() =
        withTestMain {
            val speech = model('a')
            val persisted =
                MutableStateFlow(
                    AiLabsState(
                        models = listOf(speech),
                        assignments = listOf(AiFeatureAssignment(AiFeature.TRANSCRIPTION, speech.id)),
                    ),
                )
            val cache = AiTranscriptCache(ApplicationProvider.getApplicationContext(), Dispatchers.Unconfined)
            val key = AiTranscriptCache.key(speech.id, "b".repeat(64), TranscriptionSettings(cpuThreads = 1))
            val vm = AiLabsViewModel(calls(persisted, AiDerivedCacheCleaner(cache)::clear))
            cache.put(key, "completed transcript")

            vm.setFeatureEnabled(AiFeature.TRANSCRIPTION, enabled = true)
            vm.assignModel(AiFeature.TRANSCRIPTION, speech.id)
            runCurrent()
            assertEquals("completed transcript", cache.get(key))

            vm.setFeatureEnabled(AiFeature.TRANSCRIPTION, enabled = false)
            runCurrent()
            assertNull(cache.get(key))

            cache.put(key, "completed transcript")
            vm.assignModel(AiFeature.TRANSCRIPTION, "c".repeat(64))
            runCurrent()
            assertNull(cache.get(key))

            cache.put(key, "completed transcript")
            vm.deleteModel(speech.id)
            runCurrent()
            assertNull(cache.get(key))

            cache.put(key, "completed transcript")
            vm.clearCaches()
            runCurrent()
            assertNull(cache.get(key))
        }

    @Test
    fun `failed configuration mutation preserves completed transcripts`() =
        withTestMain {
            val speech = model('a')
            val persisted =
                MutableStateFlow(
                    AiLabsState(
                        models = listOf(speech),
                        assignments = listOf(AiFeatureAssignment(AiFeature.TRANSCRIPTION, speech.id)),
                    ),
                )
            val cache = AiTranscriptCache(ApplicationProvider.getApplicationContext(), Dispatchers.Unconfined)
            val key = AiTranscriptCache.key(speech.id, "b".repeat(64), TranscriptionSettings(cpuThreads = 1))
            val failure = Result.failure<Unit>(IOException("settings write failed"))
            val vm = AiLabsViewModel(calls(persisted, AiDerivedCacheCleaner(cache)::clear, failure))
            cache.put(key, "completed transcript")
            try {
                vm.setFeatureEnabled(AiFeature.TRANSCRIPTION, enabled = false)
                vm.assignModel(AiFeature.TRANSCRIPTION, "c".repeat(64))
                vm.deleteModel(speech.id)
                runCurrent()

                assertEquals("completed transcript", cache.get(key))
            } finally {
                cache.clear()
            }
        }

    private fun withTestMain(block: suspend TestScope.() -> Unit) {
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                Dispatchers.resetMain()
            }
        }
    }

    private fun calls(
        persisted: MutableStateFlow<AiLabsState>,
        clearCaches: suspend () -> Result<Unit>,
        mutationResult: Result<Unit> = Result.success(Unit),
    ) = AiLabsCalls(
        persistedState = persisted,
        setFeatureEnabled = { _, _ -> mutationResult },
        importModel = { Result.failure(IllegalStateException("unused")) },
        assignModel = { _, _ -> mutationResult },
        updateSettings = { _, _, _ -> mutationResult },
        deleteModel = { mutationResult },
        clearCaches = clearCaches,
    )

    private fun model(idCharacter: Char) =
        AiModelRecord(
            id = idCharacter.toString().repeat(64),
            displayName = "Whisper model",
            sizeBytes = 1_024,
            format = AiModelFormat.WHISPER_GGML,
            capabilities = setOf(AiModelCapability.TRANSCRIPTION),
            metadata = AiModelMetadata(architecture = "whisper", quantization = "q4", maximumAudioSeconds = 900),
            importedAtEpochMillis = 1,
        )
}
