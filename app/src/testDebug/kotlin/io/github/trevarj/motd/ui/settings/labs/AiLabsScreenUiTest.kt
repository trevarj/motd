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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.core.app.ActivityOptionsCompat
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.room.Room
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
import io.github.trevarj.motd.ai.kokoroEnglishVoices
import io.github.trevarj.motd.audio.ReadAloudVoices
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.testing.ReadAloudHarness
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

                    override suspend fun cancelReadAloud(unload: Boolean) = Unit
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
                    AiModelLibraryContent(deriveAiLabsUiState(raw, null, false), onBack = { library = false }, onImport = { _, _ -> }, onUpdateTranscriptionSettings = { _, _ -> }, onDelete = {}, onDownloadTextModel = { downloads++ }, onDownloadKokoroAndUse = {})
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
                        readerConfig =
                            io.github.trevarj.motd.audio
                                .ReadAloudSelection(),
                        readerState =
                            io.github.trevarj.motd.audio
                                .ReadAloudState(),
                        readerVoices =
                            io.github.trevarj.motd.audio
                                .ReadAloudVoices(),
                        onDownloadKokoroAndUse = {},
                        onCancelSetup = {},
                        onOpenVoiceOptions = {},
                        onSaveVoiceOptions = {},
                        onPreviewLocal = {},
                        onStopPreview = {},
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
            compose.onNodeWithText("Setup contacts Hugging Face", substring = true).assertExists()
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
                        readerConfig =
                            io.github.trevarj.motd.audio
                                .ReadAloudSelection(),
                        readerState =
                            io.github.trevarj.motd.audio
                                .ReadAloudState(),
                        readerVoices =
                            io.github.trevarj.motd.audio
                                .ReadAloudVoices(),
                        onDownloadKokoroAndUse = {},
                        onCancelSetup = {},
                        onOpenVoiceOptions = {},
                        onSaveVoiceOptions = {},
                        onPreviewLocal = {},
                        onStopPreview = {},
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
                    readerConfig =
                        io.github.trevarj.motd.audio
                            .ReadAloudSelection(),
                    readerState =
                        io.github.trevarj.motd.audio
                            .ReadAloudState(),
                    readerVoices =
                        io.github.trevarj.motd.audio
                            .ReadAloudVoices(),
                    onDownloadKokoroAndUse = {},
                    onCancelSetup = {},
                    onOpenVoiceOptions = {},
                    onSaveVoiceOptions = {},
                    onPreviewLocal = {},
                    onStopPreview = {},
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
                    readerConfig =
                        io.github.trevarj.motd.audio
                            .ReadAloudSelection(),
                    readerState =
                        io.github.trevarj.motd.audio
                            .ReadAloudState(),
                    readerVoices =
                        io.github.trevarj.motd.audio
                            .ReadAloudVoices(),
                    onDownloadKokoroAndUse = {},
                    onCancelSetup = {},
                    onOpenVoiceOptions = {},
                    onSaveVoiceOptions = {},
                    onPreviewLocal = {},
                    onStopPreview = {},
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
                        onDownloadKokoroAndUse = {},
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
                    onDownloadKokoroAndUse = {},
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
                    onDownloadKokoroAndUse = {},
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
                    onImport = { _, _ -> },
                    onUpdateTranscriptionSettings = { _, _ -> },
                    onDelete = {},
                    onDownloadKokoroAndUse = {},
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

    private fun withLocalLabs(block: (ReadAloudHarness, AiLabsViewModel, LifecycleRegistry, () -> Unit) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reader = ReadAloudHarness(context, db, scope)
        reader.synth.voices.value = ReadAloudVoices(kokoroEnglishVoices)
        val vm =
            AiLabsViewModel(
                AiLabsCalls(
                    reader.labs.state,
                    reader.labs::setFeatureEnabled,
                    { uri, capability -> reader.labs.importModel(uri, capability) },
                    reader.labs::assignModel,
                    reader.labs::updateTranscriptionSettings,
                    reader.labs::deleteModel,
                    { Result.success(Unit) },
                    { error("text download not exercised") },
                    reader.labs::upsertCustomStyle,
                    reader.labs::deleteCustomStyle,
                    reader.labs::setTranslationTarget,
                    { reader.labs.downloadKokoroAndUse() },
                    reader.controller.config,
                    reader.controller.state,
                    reader.controller.voices,
                    reader.controller::openVoiceOptions,
                    reader.controller::saveVoiceOptions,
                    reader.controller::previewLocal,
                    reader.controller::stopPreview,
                ),
            )
        val owner =
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        var shown by mutableStateOf(true)
        compose.setContent {
            val state by vm.state.collectAsState()
            val config by vm.readAloudConfig.collectAsState()
            val playback by vm.readAloudState.collectAsState()
            val voices by vm.readAloudVoices.collectAsState()
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MotdTheme(dynamicColor = false) {
                    if (shown) {
                        AiLabsContent(
                            state,
                            onBack = {},
                            onOpenModelLibrary = {},
                            onFeatureEnabled = vm::setFeatureEnabled,
                            onAssignModel = vm::assignModel,
                            onUpdateTranscriptionSettings = vm::updateTranscriptionSettings,
                            onClearCaches = vm::clearCaches,
                            readerConfig = config,
                            readerState = playback,
                            readerVoices = voices,
                            onDownloadKokoroAndUse = vm::downloadKokoroAndUse,
                            onCancelSetup = vm::cancelSetup,
                            onOpenVoiceOptions = vm::openVoiceOptions,
                            onSaveVoiceOptions = vm::saveVoiceOptions,
                            onPreviewLocal = vm::previewLocal,
                            onStopPreview = vm::stopPreview,
                            target = SettingsTarget.AI_READ_ALOUD,
                        )
                    }
                }
            }
        }
        try {
            block(reader, vm, owner.lifecycle) { shown = false }
        } finally {
            compose.runOnIdle {
                reader.controller.stop()
                scope.cancel()
            }
            compose.waitForIdle()
            db.close()
        }
    }

    private fun downloadLocal(reader: ReadAloudHarness) {
        compose.onNodeWithTag("ai_download_kokoro", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            reader.controller.config.value.localEnabled && reader.controller.config.value.localReady
        }
        assertTrue(!reader.controller.state.value.enabled && reader.synth.utterances.isEmpty())
    }

    @Test
    fun localSetupVoiceRateAndLifecyclePreviewUseRealRepositoryWithoutChatOptIn() =
        withLocalLabs { reader, _, lifecycle, hide ->
            compose.onNodeWithTag("ai_read_aloud_voice_options", useUnmergedTree = true).performScrollTo().assertIsNotEnabled()
            downloadLocal(reader)
            compose.onNodeWithTag("ai_read_aloud_switch_row", useUnmergedTree = true).performScrollTo().assertIsOn()
            compose.onNodeWithTag("ai_read_aloud_voice_options", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("read_aloud_pitch").assertDoesNotExist()
            compose.onNodeWithTag("read_aloud_voice").performClick()
            compose.onNodeWithTag("read_aloud_voice_16").performScrollTo().performClick()
            compose.onNodeWithTag("read_aloud_rate").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.2f) }
            compose.onNodeWithTag("read_aloud_gap").performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(600f) }
            compose.onNodeWithTag("read_aloud_save").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                reader.controller.config.value.options.voice == "16"
            }
            val saved = runBlocking { reader.labs.readAloudConfiguration()!! }
            assertEquals(1.2f, saved.options.rate, .01f)
            assertEquals(600, saved.options.gapMs)
            assertEquals(1f, saved.options.pitch, 0f)
            compose.onNodeWithTag("ai_read_aloud_voice_options", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithTag("read_aloud_preview").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                reader.output.played.isNotEmpty()
            }
            assertTrue(!reader.controller.state.value.enabled && reader.controller.state.value.roomId == null)
            compose.runOnIdle { lifecycle.currentState = Lifecycle.State.STARTED }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                !reader.controller.state.value.previewing && reader.output.played.none { it.exists() }
            }
            compose.runOnIdle { lifecycle.currentState = Lifecycle.State.RESUMED }
            assertTrue(!reader.controller.state.value.previewing)
            compose.onNodeWithTag("read_aloud_preview").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                reader.output.played.size == 2
            }
            compose.runOnIdle { hide() }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                !reader.controller.state.value.previewing && reader.output.played.none { it.exists() }
            }
        }

    @Test
    fun unavailableEnabledLocalOverrideCanBeTurnedOffInsteadOfPreviewingSystem() =
        withLocalLabs { reader, _, _, _ ->
            downloadLocal(reader)
            val id = reader.controller.config.value.modelId!!
            compose.runOnIdle {
                runBlocking {
                    reader.labs
                        .modelFile(id)
                        .resolve("model.int8.onnx")
                        .writeText("corrupt")
                    reader.labs.reconcile().getOrThrow()
                }
            }
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                !reader.controller.config.value.localReady
            }
            compose.onNodeWithTag("ai_read_aloud_switch_row", useUnmergedTree = true).performScrollTo().assertIsOn()
            compose.onNodeWithTag("ai_read_aloud_voice_options", useUnmergedTree = true).performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("ai_read_aloud_switch_row", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.waitForIdle()
                !reader.controller.config.value.localEnabled
            }
            assertTrue(reader.synth.utterances.isEmpty())
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
