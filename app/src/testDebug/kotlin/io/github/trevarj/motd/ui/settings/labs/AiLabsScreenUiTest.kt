package io.github.trevarj.motd.ui.settings.labs

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ai.AiFeatureAssignment
import io.github.trevarj.motd.ai.AiLabsState
import io.github.trevarj.motd.ai.AiModelCapability
import io.github.trevarj.motd.ai.AiModelFormat
import io.github.trevarj.motd.ai.AiModelMetadata
import io.github.trevarj.motd.ai.AiModelRecord
import io.github.trevarj.motd.ai.AiTranscriptionSettingsRecord
import io.github.trevarj.motd.ai.TranscriptionSettings
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AiLabsScreenUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `Labs parent opens the anchored local voice settings row`() {
        var opened = 0
        compose.setContent {
            MotdTheme {
                LabsContent(
                    state = LabsUiState(),
                    onBack = {},
                    onGesturesChanged = {},
                    onAgentwireChanged = {},
                    onOpenAi = { opened++ },
                    target = SettingsTarget.AI,
                )
            }
        }

        compose.onNodeWithTag("labs_ai", useUnmergedTree = true).assertExists().performClick()
        compose.onNodeWithTag("settings_target_highlight_AI", useUnmergedTree = true).assertExists()
        assertEquals(1, opened)
    }

    @Test
    fun `voice setup selects a model before enabling and rejects invalid settings`() {
        val transcription = model('c')
        var raw by mutableStateOf(
            AiLabsState(
                models = listOf(transcription),
                transcriptionSettings = listOf(AiTranscriptionSettingsRecord(transcription.id, TranscriptionSettings(cpuThreads = 1))),
            ),
        )
        var clearingCaches by mutableStateOf(false)
        val saves = mutableListOf<TranscriptionSettings>()
        compose.setContent {
            MotdTheme {
                AiLabsContent(
                    state = deriveAiLabsUiState(raw, null, clearingCaches),
                    onBack = {},
                    onOpenModelLibrary = {},
                    onFeatureEnabled = { feature, enabled -> raw = raw.copy(enabledFeatures = if (enabled) setOf(feature) else emptySet()) },
                    onAssignModel = { feature, id -> raw = raw.copy(assignments = listOf(AiFeatureAssignment(feature, id))) },
                    onUpdateSettings = { _, _, settings -> saves += settings },
                    onClearCaches = { clearingCaches = true },
                    target = SettingsTarget.AI_TRANSCRIPTION,
                )
            }
        }

        compose.onNodeWithTag("ai_briefs_group", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("ai_semantic_search_group", useUnmergedTree = true).assertDoesNotExist()
        compose
            .onNodeWithTag("ai_transcription_switch_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.onNodeWithTag("ai_transcription_model_sheet", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ai_transcription_model_option_${transcription.id}", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ai_transcription_model_sheet", useUnmergedTree = true).assertDoesNotExist()
        compose
            .onNodeWithTag("ai_transcription_switch_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.onNodeWithTag("ai_transcription_switch_row", useUnmergedTree = true).assertIsOn()
        compose.onNodeWithTag("settings_target_highlight_AI_TRANSCRIPTION", useUnmergedTree = true).assertExists()

        compose.onNodeWithTag("ai_transcription_advanced", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("ai_transcription_cpu_threads", useUnmergedTree = true).performScrollTo().performTextReplacement("0")
        compose.onNodeWithTag("ai_transcription_settings_save", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("ai_transcription_settings_error", useUnmergedTree = true).assertExists()
        assertTrue(saves.isEmpty())
        compose.onNodeWithTag("ai_transcription_cpu_threads", useUnmergedTree = true).performScrollTo().performTextReplacement("1")
        compose.onNodeWithTag("ai_transcription_language", useUnmergedTree = true).performScrollTo().performTextReplacement("en")
        compose.onNodeWithTag("ai_transcription_settings_save", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals("en", saves.single().language)
        compose.onNodeWithTag("ai_transcription_settings_error", useUnmergedTree = true).assertDoesNotExist()

        compose
            .onNodeWithTag("ai_clear_caches", useUnmergedTree = true)
            .performScrollTo()
            .performClick()
            .assertIsNotEnabled()
    }

    @Test
    fun `missing assignment stays off and sends setup to the model library`() {
        var opened = 0
        val state = deriveAiLabsUiState(AiLabsState(), null, false)
        compose.setContent {
            MotdTheme {
                AiLabsContent(
                    state = state,
                    onBack = {},
                    onOpenModelLibrary = { opened++ },
                    onFeatureEnabled = { _, _ -> error("feature must stay off") },
                    onAssignModel = { _, _ -> },
                    onUpdateSettings = { _, _, _ -> },
                    onClearCaches = {},
                )
            }
        }

        compose
            .onNodeWithTag("ai_transcription_switch_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOff()
            .performClick()
            .assertIsOff()
        assertEquals(1, opened)
    }

    @Test
    fun `library opens the document picker directly and imports only a selected file`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val imported = mutableListOf<Uri>()
        var lastRequestCode = 0
        var launchedIntent: Intent? = null
        val registry =
            object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?,
                ) {
                    lastRequestCode = requestCode
                    launchedIntent = contract.createIntent(context, input)
                }
            }
        val owner =
            object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MotdTheme {
                    AiModelLibraryContent(
                        state = deriveAiLabsUiState(AiLabsState(), null, false),
                        onBack = {},
                        onImport = { imported += it },
                        onUpdateSettings = { _, _, _ -> },
                        onDelete = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("ai_import_model", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("ai_import_role_sheet", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, launchedIntent?.action)
        compose.runOnIdle { registry.dispatchResult(lastRequestCode, Activity.RESULT_CANCELED, null) }
        assertTrue(imported.isEmpty())

        val uri = Uri.parse("content://models/whisper.bin")
        compose.onNodeWithTag("ai_import_model", useUnmergedTree = true).performClick()
        compose.runOnIdle { registry.dispatchResult(lastRequestCode, Activity.RESULT_OK, Intent().setData(uri)) }
        assertEquals(listOf(uri), imported)
    }

    @Test
    fun `library import progress and safe errors are observable`() {
        compose.setContent {
            MotdTheme {
                AiModelLibraryContent(
                    state =
                        AiLabsUiState(
                            importProgress = AiImportProgress(25, 100),
                            status = AiLabsStatus(R.string.ai_error_corrupt_model, error = true),
                        ),
                    onBack = {},
                    onImport = {},
                    onUpdateSettings = { _, _, _ -> },
                    onDelete = {},
                )
            }
        }

        compose.onNodeWithTag("ai_library_status", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ai_import_progress", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ai_import_model", useUnmergedTree = true).assertIsNotEnabled()
    }

    @Test
    fun `library edits voice settings and requires confirmation before deletion`() {
        val speech = model('d')
        val state =
            deriveAiLabsUiState(
                AiLabsState(models = listOf(speech), transcriptionSettings = listOf(AiTranscriptionSettingsRecord(speech.id, TranscriptionSettings(cpuThreads = 1)))),
                null,
                false,
            )
        val saves = mutableListOf<TranscriptionSettings>()
        var deleted: String? = null
        compose.setContent {
            MotdTheme {
                AiModelLibraryContent(
                    state = state,
                    onBack = {},
                    onImport = {},
                    onUpdateSettings = { _, _, settings -> saves += settings },
                    onDelete = { deleted = it },
                )
            }
        }

        compose.onNodeWithTag("ai_model_${speech.id}_transcription_advanced", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("ai_model_${speech.id}_transcription_language", useUnmergedTree = true).performScrollTo().performTextReplacement("de")
        compose.onNodeWithTag("ai_model_${speech.id}_transcription_settings_save", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals("de", saves.single().language)
        compose.onNodeWithTag("ai_model_${speech.id}_delete", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("ai_delete_cancel", useUnmergedTree = true).performClick()
        assertEquals(null, deleted)
        compose.onNodeWithTag("ai_delete_dialog", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("ai_model_${speech.id}_delete", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("ai_delete_confirm", useUnmergedTree = true).performClick()
        assertEquals(speech.id, deleted)
    }

    @Test
    fun `Whisper model links open only the approved upstream addresses`() {
        val application = ApplicationProvider.getApplicationContext<Context>() as Application
        compose.setContent {
            MotdTheme {
                AiModelLibraryContent(
                    state = deriveAiLabsUiState(AiLabsState(), null, false),
                    onBack = {},
                    onImport = {},
                    onUpdateSettings = { _, _, _ -> },
                    onDelete = {},
                )
            }
        }
        val expected =
            listOf(
                "ai_link_whisper_cpp" to "https://huggingface.co/ggerganov/whisper.cpp/tree/main",
                "ai_link_openai_whisper" to "https://github.com/openai/whisper",
            )

        expected.forEach { (tag, url) ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
            val intent = shadowOf(application).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
        }
    }

    private fun model(idCharacter: Char) =
        AiModelRecord(
            id = idCharacter.toString().repeat(64),
            displayName = "Whisper model",
            sizeBytes = 1_024,
            format = AiModelFormat.WHISPER_GGML,
            capabilities = setOf(AiModelCapability.TRANSCRIPTION),
            metadata = AiModelMetadata(architecture = "whisper", quantization = "q4", maximumAudioSeconds = 900, maximumCpuThreads = 1),
            importedAtEpochMillis = 1,
        )
}
