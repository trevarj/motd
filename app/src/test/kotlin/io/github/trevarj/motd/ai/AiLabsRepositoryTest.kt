package io.github.trevarj.motd.ai

import android.net.Uri
import android.os.CancellationSignal
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiLabsRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val names = AtomicInteger()

    @Test
    fun `defaults are disabled and transient progress is never persisted`() =
        runTest {
            val fixture = fixture()
            assertEquals(AiLabsState(), fixture.repository.state.first())

            val progress = mutableListOf<Long>()
            val model =
                fixture.repository
                    .importModel(fixture.source.add(whisper(1), "tiny.bin"), AiModelCapability.TRANSCRIPTION) { copied, _ ->
                        progress += copied
                    }.getOrThrow()

            val state =
                fixture.repository.state.first {
                    it.models.any { record -> record.id == model.id } && it.importState is AiImportState.Idle
                }
            assertTrue(state.enabledFeatures.isEmpty())
            assertTrue(progress.first() == 0L && progress.last() == whisper(1).size.toLong())
            assertTrue(state.importState is AiImportState.Idle)
            val persisted = fixture.store.data.first()[stringPreferencesKey("state_v1")]
            assertNotNull(persisted)
            assertFalse(persisted!!.contains("importState"))
        }

    @Test
    fun `Whisper imports keep app-owned weights and voice settings`() =
        runTest {
            val fixture = fixture(now = 1_234L)
            val speech =
                fixture.repository
                    .importModel(fixture.source.add(whisper(3), "speech.bin"), AiModelCapability.TRANSCRIPTION)
                    .getOrThrow()

            assertEquals(AiModelFormat.WHISPER_GGML, speech.format)
            assertEquals(1_234L, speech.importedAtEpochMillis)
            val state = fixture.repository.state.first { it.models.size == 1 }
            assertNotNull(state.transcriptionSettingsFor(speech.id))
            assertTrue(whisper(3).contentEquals(fixture.repository.modelFile(speech.id).readBytes()))
            assertTrue(fixture.temporaryFiles().isEmpty())
        }

    @Test
    fun `same SHA is physically deduplicated without resetting voice settings or assignment`() =
        runTest {
            val fixture = fixture()
            val uri = fixture.source.add(whisper(4), "speech.bin")
            val first = fixture.repository.importModel(uri, AiModelCapability.TRANSCRIPTION).getOrThrow()
            val settings = TranscriptionSettings(language = "ja", initialPrompt = "IRC", cpuThreads = 1)
            fixture.repository.updateTranscriptionSettings(first.id, settings).getOrThrow()
            fixture.repository.assignModel(AiFeature.TRANSCRIPTION, first.id).getOrThrow()
            fixture.repository.setFeatureEnabled(AiFeature.TRANSCRIPTION, true).getOrThrow()

            val duplicate = fixture.repository.importModel(uri, AiModelCapability.TRANSCRIPTION).getOrThrow()
            val state = fixture.repository.state.first { AiFeature.TRANSCRIPTION in it.enabledFeatures }
            assertEquals(first.id, duplicate.id)
            assertEquals(listOf(first.id), state.models.map { it.id })
            assertEquals(first.id, state.assignedModelId(AiFeature.TRANSCRIPTION))
            assertEquals(settings, state.transcriptionSettingsFor(first.id))
            assertEquals(1, fixture.modelFiles().size)
            assertTrue(whisper(4).contentEquals(fixture.repository.modelFile(first.id).readBytes()))
        }

    @Test
    fun `failed oversized out-of-space cancelled and persistence-failed imports are atomic`() =
        runTest {
            val fixture = fixture(maximumBytes = 24)
            val baseline =
                fixture.repository
                    .importModel(fixture.source.add(whisper(5), "kept.bin"), AiModelCapability.TRANSCRIPTION)
                    .getOrThrow()
            val committedState =
                fixture.repository.state.first {
                    it.models.singleOrNull()?.id == baseline.id && it.importState is AiImportState.Idle
                }
            val committedFiles = fixture.diskSnapshot()

            fixture.runtime.inspectFailure = AiRuntimeException(AiRuntimeFailure.CORRUPT_MODEL)
            assertEquals(
                AiLabsFailureKind.CORRUPT_MODEL,
                fixture.repository
                    .importModel(fixture.source.add(whisper(6), "bad.bin"), AiModelCapability.TRANSCRIPTION)
                    .failureKind(),
            )
            fixture.runtime.inspectFailure = null
            fixture.assertUnchanged(committedState, committedFiles)

            assertEquals(
                AiLabsFailureKind.TOO_LARGE,
                fixture.repository
                    .importModel(fixture.source.add(whisper(7, 40), "huge.bin"), AiModelCapability.TRANSCRIPTION)
                    .failureKind(),
            )
            fixture.assertUnchanged(committedState, committedFiles)

            fixture.space.bytes = 3
            assertEquals(
                AiLabsFailureKind.INSUFFICIENT_SPACE,
                fixture.repository
                    .importModel(fixture.source.add(whisper(8), "no-space.bin"), AiModelCapability.TRANSCRIPTION)
                    .failureKind(),
            )
            fixture.space.bytes = Long.MAX_VALUE
            fixture.assertUnchanged(committedState, committedFiles)

            fixture.store.failUpdates = true
            assertEquals(
                AiLabsFailureKind.PERSISTENCE,
                fixture.repository
                    .importModel(fixture.source.add(whisper(9), "store-fails.bin"), AiModelCapability.TRANSCRIPTION)
                    .failureKind(),
            )
            fixture.store.failUpdates = false
            fixture.assertUnchanged(committedState, committedFiles)

            lateinit var request: kotlinx.coroutines.Job
            request =
                launch(start = CoroutineStart.LAZY) {
                    fixture.repository.importModel(
                        fixture.source.add(whisper(10, 20), "cancel.bin"),
                        AiModelCapability.TRANSCRIPTION,
                    ) { copied, _ ->
                        if (copied > 0) request.cancel()
                    }
                }
            request.start()
            request.join()
            assertTrue(request.isCancelled)
            fixture.assertUnchanged(committedState, committedFiles)
            assertTrue(fixture.temporaryFiles().isEmpty())
        }

    @Test
    fun `reclaimed storage is rechecked and losing it mid-import preserves installed models`() =
        runTest {
            val fixture = fixture()
            fixture.space.bytes = 3
            fixture.space.allocate = { required -> fixture.space.bytes = required + 12 }
            val baseline =
                fixture.repository
                    .importModel(fixture.source.add(whisper(27)), AiModelCapability.TRANSCRIPTION)
                    .getOrThrow()
            val committedState =
                fixture.repository.state.first {
                    it.models.singleOrNull()?.id == baseline.id && it.importState is AiImportState.Idle
                }
            val committedFiles = fixture.diskSnapshot()
            assertTrue(whisper(27).contentEquals(fixture.repository.modelFile(baseline.id).readBytes()))

            // Another writer can consume the reclaimed space before our next physical check.
            fixture.space.allocate = {}
            assertEquals(
                AiLabsFailureKind.INSUFFICIENT_SPACE,
                fixture.repository
                    .importModel(fixture.source.add(whisper(28)), AiModelCapability.TRANSCRIPTION) { copied, _ ->
                        if (copied > 0) fixture.space.bytes = 4
                    }.failureKind(),
            )
            fixture.assertUnchanged(committedState, committedFiles)
        }

    @Test
    fun `provider permission open and read failures have safe distinct kinds`() =
        runTest {
            val fixture = fixture()
            val denied = fixture.source.add { throw SecurityException("secret uri") }
            val missing = fixture.source.add { throw FileNotFoundException("secret path") }
            val unreadable =
                fixture.source.add {
                    object : InputStream() {
                        override fun read(): Int = throw IOException("secret provider")
                    }
                }

            assertEquals(
                AiLabsFailureKind.SOURCE_PERMISSION,
                fixture.repository.importModel(denied, AiModelCapability.TRANSCRIPTION).failureKind(),
            )
            assertEquals(
                AiLabsFailureKind.SOURCE_OPEN,
                fixture.repository.importModel(missing, AiModelCapability.TRANSCRIPTION).failureKind(),
            )
            assertEquals(
                AiLabsFailureKind.SOURCE_READ,
                fixture.repository.importModel(unreadable, AiModelCapability.TRANSCRIPTION).failureKind(),
            )
            assertTrue(
                fixture.modelDirectory
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun `cancelling a blocked provider read closes it and releases the mutation lock`() =
        runTest {
            val fixture = fixture(ioDispatcher = Dispatchers.IO)
            val blocked = BlockingInputStream()
            val request =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.repository
                        .importModel(fixture.source.add { blocked }, AiModelCapability.TRANSCRIPTION)
                        .getOrThrow()
                }

            assertTrue(blocked.awaitRead())
            assertEquals(1, fixture.temporaryFiles().size)
            request.cancelAndJoin()

            assertTrue(blocked.closed.get())
            assertTrue(fixture.source.openWasCancelled)
            assertTrue(fixture.temporaryFiles().isEmpty())
            val imported =
                withContext(Dispatchers.IO) {
                    withTimeout(5_000) {
                        fixture.repository
                            .importModel(fixture.source.add(whisper(20)), AiModelCapability.TRANSCRIPTION)
                            .getOrThrow()
                    }
                }
            assertTrue(fixture.repository.modelFile(imported.id).isFile)
        }

    @Test
    fun `enable requires a compatible assignment and import or assignment never enables`() =
        runTest {
            val fixture = fixture()
            assertEquals(
                AiLabsFailureKind.MODEL_NOT_READY,
                fixture.repository.setFeatureEnabled(AiFeature.TRANSCRIPTION, true).failureKind(),
            )
            val model =
                fixture.repository
                    .importModel(fixture.source.add(whisper(11)), AiModelCapability.TRANSCRIPTION)
                    .getOrThrow()
            assertTrue(
                fixture.repository.state
                    .first { it.models.isNotEmpty() }
                    .enabledFeatures
                    .isEmpty(),
            )
            fixture.repository.assignModel(AiFeature.TRANSCRIPTION, model.id).getOrThrow()
            assertTrue(
                fixture.repository.state
                    .first { it.assignments.isNotEmpty() }
                    .enabledFeatures
                    .isEmpty(),
            )
            fixture.repository.setFeatureEnabled(AiFeature.TRANSCRIPTION, true).getOrThrow()
            assertEquals(
                setOf(AiFeature.TRANSCRIPTION),
                fixture.repository.state
                    .first { it.enabledFeatures.isNotEmpty() }
                    .enabledFeatures,
            )
            fixture.repository.setFeatureEnabled(AiFeature.TRANSCRIPTION, false).getOrThrow()
            assertTrue(
                fixture.repository.state
                    .first { it.enabledFeatures.isEmpty() }
                    .enabledFeatures
                    .isEmpty(),
            )
        }

    @Test
    fun `transcription settings normalize and invalid language or prompt is never ready`() =
        runTest {
            val fixture = fixture()
            val model =
                fixture.repository
                    .importModel(fixture.source.add(whisper(21)), AiModelCapability.TRANSCRIPTION)
                    .getOrThrow()

            fixture.repository.updateTranscriptionSettings(model.id, TranscriptionSettings(language = " YUE ", cpuThreads = 1)).getOrThrow()
            fixture.repository.state.first {
                it.transcriptionSettingsFor(model.id)?.language == "yue"
            }
            fixture.repository.assignModel(AiFeature.TRANSCRIPTION, model.id).getOrThrow()
            fixture.repository.setFeatureEnabled(AiFeature.TRANSCRIPTION, true).getOrThrow()
            val maximumPrompt = "é".repeat(32_768)
            fixture.repository.updateTranscriptionSettings(model.id, TranscriptionSettings(language = "yue", initialPrompt = maximumPrompt, cpuThreads = 1)).getOrThrow()
            val promptReady =
                fixture.repository.state.first {
                    it.transcriptionSettingsFor(model.id)?.initialPrompt == maximumPrompt
                }
            assertTrue(promptReady.isModelReadyFor(model, AiModelCapability.TRANSCRIPTION))

            listOf(
                TranscriptionSettings(language = "not-a-language", cpuThreads = 1),
                TranscriptionSettings(language = "yue", initialPrompt = "embedded\u0000nul", cpuThreads = 1),
                TranscriptionSettings(language = "yue", initialPrompt = "$maximumPrompt!", cpuThreads = 1),
            ).forEach { invalidSettings ->
                assertEquals(
                    AiLabsFailureKind.INVALID_SETTINGS,
                    fixture.repository
                        .updateTranscriptionSettings(model.id, invalidSettings)
                        .failureKind(),
                )
                assertFalse(invalidSettings.isRuntimeCompatible(model.metadata))
            }

            val invalid =
                fixture.repository.state
                    .first { AiFeature.TRANSCRIPTION in it.enabledFeatures }
                    .copy(
                        transcriptionSettings =
                            listOf(
                                AiTranscriptionSettingsRecord(
                                    model.id,
                                    TranscriptionSettings(language = "yue", initialPrompt = "embedded\u0000nul", cpuThreads = 1),
                                ),
                            ),
                    )
            fixture.store.edit {
                it[stringPreferencesKey("state_v1")] = Json.encodeToString(invalid)
            }
            val notReady =
                fixture.repository.state.first {
                    it.transcriptionSettingsFor(model.id)?.initialPrompt == "embedded\u0000nul" &&
                        it.assignments.isEmpty()
                }
            assertFalse(AiFeature.TRANSCRIPTION in notReady.enabledFeatures)
            assertFalse(notReady.isModelReadyFor(model, AiModelCapability.TRANSCRIPTION))
            assertEquals(
                AiLabsFailureKind.MODEL_NOT_READY,
                fixture.repository.assignModel(AiFeature.TRANSCRIPTION, model.id).failureKind(),
            )

            val monolingual = fixture()
            monolingual.runtime.isMultilingual = false
            val english =
                monolingual.repository
                    .importModel(monolingual.source.add(whisper(22)), AiModelCapability.TRANSCRIPTION)
                    .getOrThrow()
            assertEquals(
                AiLabsFailureKind.INVALID_SETTINGS,
                monolingual.repository.updateTranscriptionSettings(english.id, TranscriptionSettings(language = "fr", cpuThreads = 1)).failureKind(),
            )
            monolingual.repository.updateTranscriptionSettings(english.id, TranscriptionSettings(language = " AUTO ", cpuThreads = 1)).getOrThrow()
            val automatic =
                monolingual.repository.state.first {
                    it.transcriptionSettingsFor(english.id)?.language == "auto"
                }
            assertTrue(automatic.isModelReadyFor(english, AiModelCapability.TRANSCRIPTION))
        }

    @Test
    fun `delete unloads first clears linked voice settings and preserves other imports`() =
        runTest {
            val fixture = fixture()
            val selected = fixture.repository.importModel(fixture.source.add(whisper(12)), AiModelCapability.TRANSCRIPTION).getOrThrow()
            val other = fixture.repository.importModel(fixture.source.add(whisper(13)), AiModelCapability.TRANSCRIPTION).getOrThrow()
            fixture.repository.assignModel(AiFeature.TRANSCRIPTION, selected.id).getOrThrow()
            fixture.repository.setFeatureEnabled(AiFeature.TRANSCRIPTION, true).getOrThrow()

            val selectedFile = fixture.repository.modelFile(selected.id)
            fixture.runtime.onUnload = { id -> if (id == selected.id) assertTrue(selectedFile.exists()) }
            fixture.repository.deleteModel(selected.id).getOrThrow()
            val state = fixture.repository.state.first { it.models.map { model -> model.id } == listOf(other.id) }
            assertFalse(selectedFile.exists())
            assertTrue(state.enabledFeatures.isEmpty())
            assertTrue(state.assignments.isEmpty())
            assertEquals(listOf(other.id), state.transcriptionSettings.map { it.modelId })
            assertTrue(fixture.repository.modelFile(other.id).exists())

            fixture.runtime.unloadFailure = IOException()
            assertEquals(AiLabsFailureKind.DELETION, fixture.repository.deleteModel(other.id).failureKind())
            assertTrue(fixture.repository.modelFile(other.id).exists())
            assertEquals(state, fixture.repository.state.first { it.models.size == 1 })
        }

    @Test
    fun `public state clamps persisted voice settings to model runtime limits`() =
        runTest {
            val fixture = fixture(availableProcessors = 8)
            val model = fixture.repository.importModel(fixture.source.add(whisper(15)), AiModelCapability.TRANSCRIPTION).getOrThrow()
            val current = fixture.repository.state.first { it.models.singleOrNull()?.id == model.id }
            fixture.store.edit {
                it[stringPreferencesKey("state_v1")] =
                    Json.encodeToString(
                        current.copy(
                            transcriptionSettings =
                                listOf(
                                    AiTranscriptionSettingsRecord(
                                        model.id,
                                        TranscriptionSettings(language = " FR ", initialPrompt = "IRC", cpuThreads = 99),
                                    ),
                                ),
                        ),
                    )
            }

            val clamped =
                fixture.repository.state.first {
                    it.transcriptionSettingsFor(model.id)?.language == "fr"
                }
            assertEquals(
                TranscriptionSettings(language = "fr", initialPrompt = "IRC", cpuThreads = 2),
                clamped.transcriptionSettingsFor(model.id),
            )
        }

    @Test
    fun `reconcile removes invalid voice files and temporaries but retains unregistered weights`() =
        runTest {
            val fixture = fixture()
            val missing = fixture.repository.importModel(fixture.source.add(whisper(16)), AiModelCapability.TRANSCRIPTION).getOrThrow()
            val corrupt = fixture.repository.importModel(fixture.source.add(whisper(17)), AiModelCapability.TRANSCRIPTION).getOrThrow()
            val kept = fixture.repository.importModel(fixture.source.add(whisper(18)), AiModelCapability.TRANSCRIPTION).getOrThrow()
            fixture.repository.assignModel(AiFeature.TRANSCRIPTION, kept.id).getOrThrow()
            fixture.repository.setFeatureEnabled(AiFeature.TRANSCRIPTION, true).getOrThrow()

            assertTrue(fixture.repository.modelFile(missing.id).delete())
            fixture.repository.modelFile(corrupt.id).writeBytes(ByteArray(corrupt.sizeBytes.toInt()))
            val orphanBytes = gguf(19)
            File(fixture.modelDirectory, "${orphanBytes.sha256()}.model").writeBytes(orphanBytes)
            File(fixture.modelDirectory, ".import-dead.tmp").writeText("partial")
            File(fixture.modelDirectory, "not-a-model").writeText("orphan")

            fixture.repository.reconcile().getOrThrow()
            val state = fixture.repository.state.first { it.models.map(AiModelRecord::id) == listOf(kept.id) }
            assertEquals(setOf(AiFeature.TRANSCRIPTION), state.enabledFeatures)
            assertEquals(listOf(AiFeature.TRANSCRIPTION), state.assignments.map { it.feature })
            assertEquals(
                setOf("${kept.id}.model", "${orphanBytes.sha256()}.model"),
                fixture.modelDirectory
                    .listFiles()
                    .orEmpty()
                    .mapTo(mutableSetOf()) { it.name },
            )
        }

    @Test
    fun `corrupt or empty preferences never delete unregistered model weights`() =
        runTest {
            val fixture = fixture()
            val model =
                fixture.repository
                    .importModel(fixture.source.add(whisper(23)), AiModelCapability.TRANSCRIPTION)
                    .getOrThrow()
            fixture.repository.state.first { it.models.singleOrNull()?.id == model.id }
            val orphanBytes = gguf(24)
            File(fixture.modelDirectory, "${orphanBytes.sha256()}.model").writeBytes(orphanBytes)
            val before = fixture.diskSnapshot()

            fixture.store.edit {
                it[stringPreferencesKey("state_v1")] = "{not valid JSON"
            }
            assertEquals(AiLabsState(), fixture.repository.state.first { it.models.isEmpty() })
            assertEquals(AiLabsFailureKind.PERSISTENCE, fixture.repository.reconcile().failureKind())
            assertEquals(before, fixture.diskSnapshot())
            assertEquals(
                "{not valid JSON",
                fixture.store.data.first()[stringPreferencesKey("state_v1")],
            )

            fixture.store.edit {
                it[stringPreferencesKey("state_v1")] = Json.encodeToString(AiLabsState())
            }
            fixture.repository.reconcile().getOrThrow()
            assertEquals(before, fixture.diskSnapshot())
        }

    @Test
    fun `legacy mixed Labs state preserves voice configuration and retired weight files`() =
        runTest {
            val fixture = fixture()
            val voiceBytes = whisper(25)
            val retiredBytes = gguf(26)
            val voiceId = voiceBytes.sha256()
            val retiredId = retiredBytes.sha256()
            assertTrue(fixture.modelDirectory.mkdirs())
            fixture.repository.modelFile(voiceId).writeBytes(voiceBytes)
            fixture.repository.modelFile(retiredId).writeBytes(retiredBytes)
            val before = fixture.diskSnapshot()
            fixture.store.edit {
                it[stringPreferencesKey("state_v1")] =
                    """
                    {
                      "enabledFeatures": ["BRIEFS", "SEMANTIC_SEARCH", "TRANSCRIPTION"],
                      "models": [
                        {
                          "id": "$retiredId", "displayName": "Retired text model", "sizeBytes": ${retiredBytes.size},
                          "format": "GGUF", "capabilities": ["GENERATION", "EMBEDDING"],
                          "metadata": {
                            "architecture": "legacy", "quantization": "Q4", "maximumContextTokens": 2048,
                            "embeddingDimensions": 64, "requiresPromptTemplate": true
                          },
                          "importedAtEpochMillis": 1000
                        },
                        {
                          "id": "$voiceId", "displayName": "Whisper Japanese", "sizeBytes": ${voiceBytes.size},
                          "format": "WHISPER_GGML", "capabilities": ["TRANSCRIPTION"],
                          "metadata": {
                            "architecture": "whisper", "quantization": "F16", "maximumAudioSeconds": 900,
                            "maximumCpuThreads": 2, "isMultilingual": true
                          },
                          "importedAtEpochMillis": 2000
                        }
                      ],
                      "assignments": [
                        {"feature": "BRIEFS", "modelId": "$retiredId"},
                        {"feature": "SEMANTIC_SEARCH", "modelId": "$retiredId"},
                        {"feature": "TRANSCRIPTION", "modelId": "$voiceId"}
                      ],
                      "generationSettings": [{"modelId": "$retiredId", "settings": {"promptTemplateOverride": "{messages}"}}],
                      "embeddingSettings": [{"modelId": "$retiredId", "settings": {"dimensions": 64}}],
                      "transcriptionSettings": [
                        {"modelId": "$voiceId", "settings": {"language": "ja", "initialPrompt": "nicknames", "cpuThreads": 1}}
                      ]
                    }
                    """.trimIndent()
            }

            val migrated = fixture.repository.state.first { it.models.singleOrNull()?.id == voiceId }
            assertEquals(setOf(AiFeature.TRANSCRIPTION), migrated.enabledFeatures)
            assertEquals(voiceId, migrated.assignedModelId(AiFeature.TRANSCRIPTION))
            assertEquals(
                TranscriptionSettings(language = "ja", initialPrompt = "nicknames", cpuThreads = 1),
                migrated.transcriptionSettingsFor(voiceId),
            )
            assertEquals("Whisper Japanese", migrated.models.single().displayName)
            assertEquals(2000L, migrated.models.single().importedAtEpochMillis)

            fixture.repository.reconcile().getOrThrow()
            val persisted = fixture.store.data.first()[stringPreferencesKey("state_v1")]!!
            assertEquals(migrated, Json.decodeFromString<AiLabsState>(persisted))
            fixture.repository.reconcile().getOrThrow()
            assertEquals(before, fixture.diskSnapshot())
        }

    @Test
    fun textToolsRestartPreservesVoiceStylesAndTarget() =
        runTest {
            val bytes = gguf(42)
            val artifact = TextModelArtifact("https://huggingface.co/test", bytes.size.toLong(), bytes.sha256())
            val fixture = fixture(textArtifact = artifact)
            val voice = fixture.repository.importModel(fixture.source.add(whisper(41)), AiModelCapability.TRANSCRIPTION).getOrThrow()
            fixture.repository.updateTranscriptionSettings(voice.id, TranscriptionSettings(language = "ja", cpuThreads = 1)).getOrThrow()
            fixture.repository.assignModel(AiFeature.TRANSCRIPTION, voice.id).getOrThrow()
            fixture.repository.setFeatureEnabled(AiFeature.TRANSCRIPTION, true).getOrThrow()
            val text = fixture.repository.importModel(fixture.source.add(bytes), AiModelCapability.TEXT_TOOLS).getOrThrow()
            val style =
                AiCustomStyle(
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                    "Warm",
                    "Be warm.\nKeep facts.",
                )
            val target = AiTranslationTarget("ja", "Japanese")
            fixture.repository.upsertCustomStyle(style).getOrThrow()
            fixture.repository.setTranslationTarget(target).getOrThrow()
            fixture.repository.assignModel(AiFeature.TEXT_TOOLS, text.id).getOrThrow()
            fixture.repository.setFeatureEnabled(AiFeature.TEXT_TOOLS, true).getOrThrow()
            val retiredBytes = gguf(99)
            val retiredId = retiredBytes.sha256()
            val retiredFile = fixture.repository.modelFile(retiredId).apply { writeBytes(retiredBytes) }
            val committed = fixture.repository.textToolsConfiguration()!!.state
            val mixed = Json.parseToJsonElement(Json.encodeToString(committed)) as kotlinx.serialization.json.JsonObject
            val records = mixed["models"] as kotlinx.serialization.json.JsonArray
            val retired = Json.parseToJsonElement(Json.encodeToString(text.copy(id = retiredId))) as kotlinx.serialization.json.JsonObject
            val retiredRecord =
                kotlinx.serialization.json.JsonObject(
                    retired.toMutableMap().apply {
                        put("format", kotlinx.serialization.json.JsonPrimitive("GGUF"))
                        put("capabilities", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("GENERATION"))))
                    },
                )
            val seeded =
                kotlinx.serialization.json.JsonObject(
                    mixed.toMutableMap().apply {
                        put("models", kotlinx.serialization.json.JsonArray(records + retiredRecord))
                        put(
                            "customStyles",
                            kotlinx.serialization.json.JsonArray(
                                (mixed["customStyles"] as kotlinx.serialization.json.JsonArray) + kotlinx.serialization.json.JsonObject(emptyMap()),
                            ),
                        )
                        put("generationSettings", kotlinx.serialization.json.JsonArray(emptyList()))
                        put("embeddingSettings", kotlinx.serialization.json.JsonArray(emptyList()))
                    },
                )
            fixture.store.edit { it[stringPreferencesKey("state_v1")] = seeded.toString() }
            val restarted =
                AiLabsRepository(
                    fixture.store,
                    fixture.modelDirectory,
                    fixture.source,
                    fixture.runtime,
                    backgroundScope,
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                    availableBytes = { Long.MAX_VALUE },
                    allocateBytes = { _, _ -> },
                    freeSpaceReserveBytes = 4,
                    textArtifact = artifact,
                )
            restarted.reconcile().getOrThrow()
            val state = restarted.textToolsConfiguration()!!.state
            assertEquals(setOf(AiFeature.TRANSCRIPTION, AiFeature.TEXT_TOOLS), state.enabledFeatures)
            assertEquals("ja", state.transcriptionSettingsFor(voice.id)?.language)
            assertEquals(listOf(style), state.customStyles)
            assertEquals(target, state.translationTarget)
            assertTrue(state.isModelReadyFor(text, AiModelCapability.TEXT_TOOLS))
            assertNull(state.transcriptionSettingsFor(text.id))
            assertEquals(setOf(voice.id, text.id), state.models.map { it.id }.toSet())
            assertTrue(retiredBytes.contentEquals(retiredFile.readBytes()))
        }

    @Test
    fun textDisableAndDeleteRetainCustomStyles() =
        runTest {
            val bytes = gguf(43)
            val fixture = fixture(textArtifact = TextModelArtifact("https://huggingface.co/test", bytes.size.toLong(), bytes.sha256()))
            val text = fixture.repository.importModel(fixture.source.add(bytes), AiModelCapability.TEXT_TOOLS).getOrThrow()
            val style =
                AiCustomStyle(
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                    "Same",
                    "Keep it short",
                )
            val target = AiTranslationTarget("fr", "French")
            fixture.repository.upsertCustomStyle(style).getOrThrow()
            fixture.repository.setTranslationTarget(target).getOrThrow()
            fixture.repository.assignModel(AiFeature.TEXT_TOOLS, text.id).getOrThrow()
            fixture.repository.setFeatureEnabled(AiFeature.TEXT_TOOLS, true).getOrThrow()
            val version = fixture.repository.textToolsConfiguration()!!.version
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            fixture.store.beforeUpdate = {
                entered.complete(Unit)
                release.await()
            }
            val saving = launch { fixture.repository.setTranslationTarget(AiTranslationTarget("en", "English")).getOrThrow() }
            entered.await()
            assertFalse(fixture.repository.isTextToolsVersionCurrent(version))
            assertFalse(fixture.repository.applyIfTextToolsVersion(version) { error("pending apply") })
            val queued = launch { fixture.repository.setTranslationTarget(target).getOrThrow() }
            runCurrent()
            release.complete(Unit)
            saving.join()
            queued.join()
            fixture.store.beforeUpdate = {}
            assertFalse(fixture.repository.isTextToolsVersionCurrent(version))
            assertEquals(
                target,
                fixture.repository
                    .textToolsConfiguration()!!
                    .state.translationTarget,
            )
            fixture.repository.setFeatureEnabled(AiFeature.TEXT_TOOLS, false).getOrThrow()
            assertFalse(fixture.repository.applyIfTextToolsVersion(version) { error("stale apply") })
            fixture.repository.deleteModel(text.id).getOrThrow()
            val state = fixture.repository.textToolsConfiguration()!!.state
            assertTrue(state.models.isEmpty())
            assertTrue(state.assignments.isEmpty())
            assertEquals(listOf(style), state.customStyles)
            assertEquals(target, state.translationTarget)
        }

    @Test
    fun textChecksumFailureAndCancellationKeepInstalledModel() =
        runTest {
            val bytes = gguf(44)
            val fixture = fixture(textArtifact = TextModelArtifact("https://huggingface.co/test", bytes.size.toLong(), bytes.sha256()))
            val model = fixture.repository.importModel(fixture.source.add(bytes), AiModelCapability.TEXT_TOOLS).getOrThrow()
            val snapshot = fixture.diskSnapshot()
            for (bad in listOf(gguf(45), bytes.copyOf(8), bytes + byteArrayOf(0), "<html>login</html>".toByteArray())) {
                assertEquals(
                    AiLabsFailureKind.CHECKSUM_MISMATCH,
                    fixture.repository.importModel(fixture.source.add(bad), AiModelCapability.TEXT_TOOLS).failureKind(),
                )
                assertEquals(snapshot, fixture.diskSnapshot())
            }
            fixture.runtime.inspectFailure = AiRuntimeException(AiRuntimeFailure.CORRUPT_MODEL)
            assertEquals(
                AiLabsFailureKind.CORRUPT_MODEL,
                fixture.repository.importModel(fixture.source.add(bytes), AiModelCapability.TEXT_TOOLS).failureKind(),
            )
            fixture.runtime.inspectFailure = null
            fixture.store.failUpdates = true
            assertEquals(
                AiLabsFailureKind.PERSISTENCE,
                fixture.repository.importModel(fixture.source.add(bytes), AiModelCapability.TEXT_TOOLS).failureKind(),
            )
            fixture.store.failUpdates = false
            lateinit var request: kotlinx.coroutines.Job
            request =
                launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    fixture.repository.importModel(fixture.source.add(bytes), AiModelCapability.TEXT_TOOLS) { copied, _ ->
                        if (copied > 0) request.cancel()
                    }
                }
            request.start()
            request.join()
            assertTrue(request.isCancelled)
            assertEquals(snapshot, fixture.diskSnapshot())
            assertTrue(fixture.temporaryFiles().isEmpty())
            assertTrue(
                fixture.repository
                    .textToolsConfiguration()!!
                    .state
                    .isModelReadyFor(model, AiModelCapability.TEXT_TOOLS),
            )
            val blocking =
                fixture(
                    ioDispatcher = Dispatchers.IO,
                    textArtifact = TextModelArtifact("https://huggingface.co/test", bytes.size.toLong(), bytes.sha256()),
                )
            val kept = blocking.repository.importModel(blocking.source.add(bytes), AiModelCapability.TEXT_TOOLS).getOrThrow()
            val blocked = BlockingInputStream()
            val reader =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    blocking.repository.importModel(blocking.source.add { blocked }, AiModelCapability.TEXT_TOOLS).getOrThrow()
                }
            assertTrue(blocked.awaitRead())
            reader.cancelAndJoin()
            assertTrue(blocked.closed.get())
            assertTrue(blocking.source.openWasCancelled)
            assertTrue(blocking.temporaryFiles().isEmpty())
            assertTrue(bytes.contentEquals(blocking.repository.modelFile(kept.id).readBytes()))
            val committing = fixture(textArtifact = TextModelArtifact("https://huggingface.co/test", bytes.size.toLong(), bytes.sha256()))
            val voice = committing.repository.importModel(committing.source.add(whisper(46)), AiModelCapability.TRANSCRIPTION).getOrThrow()
            committing.store.failUpdates = true
            assertEquals(
                AiLabsFailureKind.PERSISTENCE,
                committing.repository.importModel(committing.source.add(bytes), AiModelCapability.TEXT_TOOLS).failureKind(),
            )
            assertEquals(listOf(voice.id), committing.modelFiles().map { it.name.removeSuffix(".model") })
            committing.store.failUpdates = false
            val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            committing.store.beforeUpdate = {
                entered.complete(Unit)
                release.await()
            }
            val late =
                launch {
                    committing.repository.importModel(committing.source.add(bytes), AiModelCapability.TEXT_TOOLS).getOrThrow()
                }
            entered.await()
            late.cancel()
            release.complete(Unit)
            late.join()
            committing.store.beforeUpdate = {}
            val committed = committing.repository.textToolsConfiguration()!!.state
            assertTrue(committed.models.any { it.id == bytes.sha256() })
            assertTrue(bytes.contentEquals(committing.repository.modelFile(bytes.sha256()).readBytes()))
            assertTrue(committing.temporaryFiles().isEmpty())
        }

    private fun TestScope.fixture(
        now: Long = 9_876L,
        maximumBytes: Long = 128L,
        availableProcessors: Int = 8,
        ioDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        textArtifact: TextModelArtifact = TextModelArtifact.Pinned,
    ): Fixture {
        val root = temporaryFolder.newFolder("repository-${names.incrementAndGet()}")
        val delegate =
            PreferenceDataStoreFactory.create(
                scope = backgroundScope,
                produceFile = { File(root, "state.preferences_pb") },
            )
        val store = FailingDataStore(delegate)
        val source = FakeSource()
        val runtime = FakeRuntime()
        val directory = File(root, "no-backup-ai-models")
        val space = Space(Long.MAX_VALUE)
        val repository =
            AiLabsRepository(
                store = store,
                modelDirectory = directory,
                source = source,
                runtime = runtime,
                scope = backgroundScope,
                ioDispatcher = ioDispatcher,
                now = { now },
                availableProcessors = availableProcessors,
                availableBytes = { space.bytes },
                allocateBytes = { _, required -> space.allocate(required) },
                maximumModelBytes = maximumBytes,
                freeSpaceReserveBytes = 4,
                copyBufferBytes = 4,
                textArtifact = textArtifact,
            )
        return Fixture(repository, store, source, runtime, directory, space)
    }

    private class Fixture(
        val repository: AiLabsRepository,
        val store: FailingDataStore,
        val source: FakeSource,
        val runtime: FakeRuntime,
        val modelDirectory: File,
        val space: Space,
    ) {
        fun modelFiles(): List<File> = modelDirectory.listFiles().orEmpty().filter { it.name.endsWith(".model") }

        fun temporaryFiles(): List<File> = modelDirectory.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }

        fun diskSnapshot(): Map<String, List<Byte>> = modelDirectory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }

        suspend fun assertUnchanged(
            expectedState: AiLabsState,
            expectedFiles: Map<String, List<Byte>>,
        ) {
            assertEquals(expectedState, repository.state.first { it.importState is AiImportState.Idle })
            assertEquals(expectedFiles, diskSnapshot())
        }
    }

    private class Space(
        var bytes: Long,
    ) {
        var allocate: (Long) -> Unit = { throw IOException("no reclaimable space") }
    }

    private class FailingDataStore(
        private val delegate: DataStore<Preferences>,
    ) : DataStore<Preferences> {
        var failUpdates = false
        var beforeUpdate: suspend () -> Unit = {}

        override val data = delegate.data

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            beforeUpdate()
            if (failUpdates) throw IOException("injected")
            return delegate.updateData(transform)
        }
    }

    private class FakeSource : AiModelSource {
        private val entries = mutableMapOf<Uri, Entry>()
        private var next = 0
        private val latestOpenCancellationSignal = AtomicReference<CancellationSignal?>()

        val openWasCancelled: Boolean
            get() = latestOpenCancellationSignal.get()?.isCanceled == true

        fun add(
            bytes: ByteArray,
            displayName: String? = null,
        ): Uri = add(displayName, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

        fun add(open: () -> InputStream): Uri = add(null, null, open)

        private fun add(
            displayName: String?,
            size: Long?,
            open: () -> InputStream,
        ): Uri {
            val uri = Uri.parse("content://ai-model-test/${next++}")
            entries[uri] = Entry(AiModelSourceMetadata(displayName, size, "application/octet-stream"), open)
            return uri
        }

        override fun metadata(uri: Uri): AiModelSourceMetadata = entries.getValue(uri).metadata

        override fun open(uri: Uri): InputStream = entries.getValue(uri).open()

        override fun open(
            uri: Uri,
            cancellationSignal: CancellationSignal,
        ): InputStream {
            latestOpenCancellationSignal.set(cancellationSignal)
            return open(uri)
        }

        private data class Entry(
            val metadata: AiModelSourceMetadata,
            val open: () -> InputStream,
        )
    }

    private class BlockingInputStream : InputStream() {
        private val started = CountDownLatch(1)
        private val released = CountDownLatch(1)
        val closed = AtomicBoolean()

        override fun read(): Int {
            started.countDown()
            try {
                released.await()
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(failure)
            }
            throw IOException("closed")
        }

        override fun close() {
            closed.set(true)
            released.countDown()
        }

        fun awaitRead(): Boolean = started.await(5, TimeUnit.SECONDS)
    }

    private class FakeRuntime : AiLabsRuntimeBoundary {
        var inspectFailure: Throwable? = null
        var isMultilingual = true
        var unloadFailure: Throwable? = null
        var onUnload: (String) -> Unit = {}

        override suspend fun inspect(
            modelId: String,
            modelFile: File,
            capability: AiModelCapability,
        ): AiModelMetadata {
            inspectFailure?.let { throw it }
            if (capability == AiModelCapability.TEXT_TOOLS) {
                return AiModelMetadata(
                    architecture = "qwen35",
                    quantization = "Q4_K_M",
                    maximumCpuThreads = 2,
                    maximumContextTokens = 4096,
                    textTemplateId = "qwen35-nonthinking-v1",
                )
            }
            return AiModelMetadata(
                architecture = "fake-whisper",
                quantization = "F16",
                maximumAudioSeconds = 900,
                maximumCpuThreads = 2,
                isMultilingual = isMultilingual,
            )
        }

        override suspend fun unloadForDeletion(modelId: String) {
            unloadFailure?.let { throw it }
            onUnload(modelId)
        }

        override suspend fun cancelTextTools(unload: Boolean) {}
    }

    private fun Result<*>.failureKind(): AiLabsFailureKind = (exceptionOrNull() as AiLabsException).kind

    private fun gguf(
        marker: Int,
        size: Int = 12,
    ): ByteArray = ByteArray(size) { index -> if (index < 4) byteArrayOf(0x47, 0x47, 0x55, 0x46)[index] else marker.toByte() }

    private fun whisper(
        marker: Int,
        size: Int = 12,
    ): ByteArray = ByteArray(size) { index -> if (index < 4) byteArrayOf(0x6c, 0x6d, 0x67, 0x67)[index] else marker.toByte() }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
}
