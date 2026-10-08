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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.R
import io.github.trevarj.motd.UiDispatcherResetRule
import io.github.trevarj.motd.ai.AiFeature
import io.github.trevarj.motd.ai.AiFeatureAssignment
import io.github.trevarj.motd.ai.AiLabsRepository
import io.github.trevarj.motd.ai.AiLabsRuntimeBoundary
import io.github.trevarj.motd.ai.AiLabsState
import io.github.trevarj.motd.ai.AiModelCapability
import io.github.trevarj.motd.ai.AiModelFormat
import io.github.trevarj.motd.ai.AiModelMetadata
import io.github.trevarj.motd.ai.AiModelRecord
import io.github.trevarj.motd.ai.AiModelSource
import io.github.trevarj.motd.ai.AiModelSourceMetadata
import io.github.trevarj.motd.ai.AiTranscriptionSettingsRecord
import io.github.trevarj.motd.ai.AiTranslationTarget
import io.github.trevarj.motd.ai.TextModelArtifact
import io.github.trevarj.motd.ai.TranscriptionSettings
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
class AiLabsScreenUiTest {
    @get:Rule(order = 1)
    val uiDispatcher = UiDispatcherResetRule()

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun textSetupStylesAndTargetPersistWithoutImplicitEnable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "text-ui-${System.nanoTime()}").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(directory, "state.preferences_pb") })
        val model = AiModelRecord(TextModelArtifact.Pinned.sha256, "Qwen", TextModelArtifact.Pinned.sizeBytes, AiModelFormat.QWEN35_GGUF, setOf(AiModelCapability.TEXT_TOOLS), AiModelMetadata("qwen35", "Q4_K_M", maximumContextTokens = 4096, textTemplateId = "qwen35-nonthinking-v1"), 1)
        runBlocking { store.edit { it[stringPreferencesKey("state_v1")] = Json.encodeToString(AiLabsState(models = listOf(model))) } }

        fun createRepository() =
            AiLabsRepository(
                store,
                File(directory, "models").apply { mkdirs() },
                object : AiModelSource {
                    override fun metadata(uri: Uri) = AiModelSourceMetadata()

                    override fun open(uri: Uri): InputStream = error("never download implicitly")
                },
                object : AiLabsRuntimeBoundary {
                    override suspend fun inspect(
                        modelId: String,
                        modelFile: File,
                        capability: AiModelCapability,
                    ) = model.metadata

                    override suspend fun unloadForDeletion(modelId: String) = Unit

                    override suspend fun cancelTextTools(unload: Boolean) = Unit
                },
                scope,
                availableBytes = { Long.MAX_VALUE },
                allocateBytes = { _, _ -> },
            )
        var repository by mutableStateOf(createRepository())
        var library by mutableStateOf(false)
        var downloads = 0
        compose.setContent {
            val raw by repository.state.collectAsState()
            MotdTheme {
                if (library) {
                    AiModelLibraryContent(deriveAiLabsUiState(raw, null, false), onBack = { library = false }, onImport = { _, _ -> }, onUpdateTranscriptionSettings = { _, _ -> }, onDelete = {}, onDownloadTextModel = { downloads++ })
                } else {
                    AiLabsContent(
                        deriveAiLabsUiState(raw, null, false),
                        onBack = {},
                        onOpenModelLibrary = { library = true },
                        onFeatureEnabled = { feature, enabled -> runBlocking { repository.setFeatureEnabled(feature, enabled).getOrThrow() } },
                        onAssignModel = { feature, id -> runBlocking { repository.assignModel(feature, id).getOrThrow() } },
                        onUpdateTranscriptionSettings = { _, _ -> },
                        onClearCaches = {},
                        target = SettingsTarget.AI_TEXT_TOOLS,
                        onUpsertCustomStyle = { runBlocking { repository.upsertCustomStyle(it).getOrThrow() } },
                        onDeleteCustomStyle = { runBlocking { repository.deleteCustomStyle(it).getOrThrow() } },
                        onTranslationTarget = { runBlocking { repository.setTranslationTarget(it).getOrThrow() } },
                    )
                }
            }
        }
        try {
            compose.waitUntil(5_000) {
                runBlocking {
                    repository
                        .textToolsConfiguration()!!
                        .state.models
                        .isNotEmpty()
                }
            }
            compose
                .onNodeWithTag("ai_text_tools_switch_row", useUnmergedTree = true)
                .performScrollTo()
                .assertIsOff()
                .performClick()
            compose.onNodeWithTag("ai_text_tools_model_option_${model.id}", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("ai_text_tools_switch_row", useUnmergedTree = true).assertIsOff()
            compose.onNodeWithTag("ai_text_tools_advanced", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("ai_manage_styles", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("ai_style_add").performClick()
            compose.onNodeWithTag("ai_style_name").performTextReplacement("Warm")
            compose.onNodeWithTag("ai_style_instruction").performTextReplacement("Be warmer.\\nKeep facts.")
            compose.onNodeWithTag("ai_style_save").performClick()
            compose.waitUntil(5_000) { repository.state.value.customStyles.size == 1 }
            val styleId =
                repository.state.value.customStyles
                    .single()
                    .id
            compose.onNodeWithTag("ai_style_edit_$styleId").performClick()
            compose.onNodeWithTag("ai_style_name").performTextReplacement("Warmer")
            compose.onNodeWithTag("ai_style_save").performClick()
            compose.runOnIdle {
                assertEquals(
                    styleId,
                    repository.state.value.customStyles
                        .single()
                        .id,
                )
            }
            compose.runOnIdle { library = true }
            compose.onNodeWithTag("ai_download_text_model", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
            assertEquals(0, downloads)
            compose.runOnIdle { library = false }
            compose.onNodeWithTag("ai_translation_target", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("ai_language_other").performScrollTo().performClick()
            compose.onNodeWithTag("ai_language_other_name").performTextReplacement("x")
            compose.onNodeWithTag("ai_language_other_save").assertIsNotEnabled()
            compose.onNodeWithTag("ai_language_other_name").performTextReplacement("Welsh (Cymraeg)")
            compose.onNodeWithTag("ai_language_other_save").performClick()
            compose.waitUntil(5_000) {
                repository.state.value.translationTarget
                    ?.code == "other"
            }
            compose.runOnIdle { repository = createRepository() }
            compose.waitUntil(5_000) { repository.state.value.customStyles.size == 1 }
            compose.runOnIdle {
                assertEquals(
                    styleId,
                    repository.state.value.customStyles
                        .single()
                        .id,
                )
                assertEquals(
                    "Warmer",
                    repository.state.value.customStyles
                        .single()
                        .name,
                )
                assertEquals(AiTranslationTarget("other", "Welsh (Cymraeg)"), repository.state.value.translationTarget)
                assertTrue(
                    repository.state.value.enabledFeatures
                        .isEmpty(),
                )
            }
            compose.onNodeWithTag("ai_manage_styles", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("ai_style_delete_$styleId").performClick()
            compose.waitUntil(5_000) {
                repository.state.value.customStyles
                    .isEmpty()
            }
            assertEquals(1, repository.state.value.models.size)
        } finally {
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun labsParentOpensCombinedAiWithIndependentDefaultOffSetup() {
        var opened by mutableStateOf(false)
        var setupOpens = 0
        val enableRequests = mutableListOf<AiFeature>()
        compose.setContent {
            MotdTheme {
                if (opened) {
                    AiLabsContent(
                        state = deriveAiLabsUiState(AiLabsState(), null, false),
                        onBack = { opened = false },
                        onOpenModelLibrary = { setupOpens++ },
                        onFeatureEnabled = { feature, _ -> enableRequests += feature },
                        onAssignModel = { _, _ -> },
                        onUpdateTranscriptionSettings = { _, _ -> },
                        onClearCaches = {},
                        target = SettingsTarget.AI,
                    )
                } else {
                    LabsContent(
                        state = LabsUiState(),
                        onBack = {},
                        onGesturesChanged = {},
                        onAgentwireChanged = {},
                        onDickordChanged = {},
                        onOpenAi = { opened = true },
                        target = SettingsTarget.AI,
                    )
                }
            }
        }

        compose.onNodeWithTag("settings_target_highlight_AI", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("labs_ai", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("screen_ai_labs", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("ai_model_library", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(1, setupOpens)
        compose
            .onNodeWithTag("ai_transcription_switch_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        assertEquals(2, setupOpens)
        compose
            .onNodeWithTag("ai_text_tools_switch_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOff()
            .performClick()
        assertEquals(3, setupOpens)
        compose.onNodeWithTag("ai_text_tools_model", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(4, setupOpens)
        compose.onNodeWithTag("ai_transcription_model", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(5, setupOpens)
        compose.onNodeWithTag("ai_transcription_switch_row", useUnmergedTree = true).performScrollTo().assertIsOff()
        compose.onNodeWithTag("ai_text_tools_switch_row", useUnmergedTree = true).performScrollTo().assertIsOff()
        assertTrue(enableRequests.isEmpty())
    }

    @Test
    fun `Labs parent exposes the anchored Dickord switch`() {
        var changed: Boolean? = null
        compose.setContent {
            MotdTheme {
                LabsContent(
                    state = LabsUiState(dickordEnabled = true),
                    onBack = {},
                    onGesturesChanged = {},
                    onAgentwireChanged = {},
                    onDickordChanged = { changed = it },
                    target = SettingsTarget.DICKORD,
                )
            }
        }

        compose
            .onNodeWithTag("labs_dickord_switch_row", useUnmergedTree = true)
            .performScrollTo()
            .assertIsOn()
            .performClick()
        compose.onNodeWithTag("settings_target_highlight_DICKORD", useUnmergedTree = true).assertExists()
        assertEquals(false, changed)
    }

    @Test
    fun `Dickord project link is available with the mode off or on without changing Labs`() {
        val application = ApplicationProvider.getApplicationContext<Context>() as Application
        var enabled by mutableStateOf(false)
        var toggleRequests = 0
        compose.setContent {
            MotdTheme {
                LabsContent(
                    state = LabsUiState(dickordEnabled = enabled),
                    onBack = {},
                    onGesturesChanged = { toggleRequests++ },
                    onAgentwireChanged = { toggleRequests++ },
                    onGlobalFeedChanged = { toggleRequests++ },
                    onDickordChanged = { toggleRequests++ },
                    onEbooksChanged = { toggleRequests++ },
                    target = SettingsTarget.DICKORD,
                )
            }
        }

        listOf(false, true).forEach { modeEnabled ->
            compose.runOnIdle { enabled = modeEnabled }
            compose.onNodeWithTag("labs_dickord_project", useUnmergedTree = true).performScrollTo().performClick()
            val intent = shadowOf(application).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("https://github.com/trevarj/dickord", intent.dataString)
            val toggle = compose.onNodeWithTag("labs_dickord_switch_row", useUnmergedTree = true).performScrollTo()
            if (modeEnabled) toggle.assertIsOn() else toggle.assertIsOff()
            assertEquals(0, toggleRequests)
        }
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
                    onUpdateTranscriptionSettings = { _, settings -> saves += settings },
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
                    onUpdateTranscriptionSettings = { _, _ -> },
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
                        onImport = { uri, _ -> imported += uri },
                        onUpdateTranscriptionSettings = { _, _ -> },
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
                    onImport = { _, _ -> },
                    onUpdateTranscriptionSettings = { _, _ -> },
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
                    onImport = { _, _ -> },
                    onUpdateTranscriptionSettings = { _, settings -> saves += settings },
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
    fun `model information links open only approved addresses without starting setup`() {
        val application = ApplicationProvider.getApplicationContext<Context>() as Application
        var setupRequests = 0
        compose.setContent {
            MotdTheme {
                AiModelLibraryContent(
                    state = deriveAiLabsUiState(AiLabsState(), null, false),
                    onBack = {},
                    onImport = { _, _ -> setupRequests++ },
                    onUpdateTranscriptionSettings = { _, _ -> },
                    onDelete = {},
                    onDownloadTextModel = { setupRequests++ },
                )
            }
        }
        val expected =
            listOf(
                "ai_link_whisper_cpp" to "https://huggingface.co/ggerganov/whisper.cpp/tree/main",
                "ai_link_openai_whisper" to "https://github.com/openai/whisper",
                "ai_link_qwen" to "https://huggingface.co/Qwen/Qwen3.5-2B",
                "ai_link_qwen_gguf" to "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF",
            )

        expected.forEach { (tag, url) ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
            val intent = shadowOf(application).nextStartedActivity
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
            compose.onNodeWithTag("ai_download_text_confirm", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("ai_model_library_empty", useUnmergedTree = true).assertExists()
            assertEquals(0, setupRequests)
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
