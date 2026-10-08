package io.github.trevarj.motd.ui.ai

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.ai.AiExecutionCoordinator
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
import io.github.trevarj.motd.ai.AiTranscriptionRequest
import io.github.trevarj.motd.ai.AiTranslationTarget
import io.github.trevarj.motd.ai.SpeechModelRuntime
import io.github.trevarj.motd.ai.TextModelArtifact
import io.github.trevarj.motd.ai.TextModelRuntime
import io.github.trevarj.motd.ai.text.TextOperation
import io.github.trevarj.motd.ai.text.TextTermination
import io.github.trevarj.motd.ai.text.TextTransformRequest
import io.github.trevarj.motd.ai.text.TextTransformResult
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import io.github.trevarj.motd.service.AppVisibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class, androidx.paging.ExperimentalPagingApi::class)
@RunWith(RobolectricTestRunner::class)
class AiTextViewModelTest {
    private class Store(
        initial: Preferences,
    ) : DataStore<Preferences> {
        val values = MutableStateFlow(initial)
        var entered: CompletableDeferred<Unit>? = null
        var release: CompletableDeferred<Unit>? = null
        var failNext = false
        override val data: Flow<Preferences> = values

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            if (failNext) {
                failNext = false
                throw java.io.IOException()
            }
            entered?.complete(Unit)
            release?.await()
            return transform(values.value).also { values.value = it }
        }
    }

    private class Text : TextModelRuntime {
        var entered = CompletableDeferred<Unit>()
        var release = CompletableDeferred<Unit>()
        var cleanup = CompletableDeferred<Unit>()
        var cleanupRelease = CompletableDeferred<Unit>()
        var blocked = true
        val requests = mutableListOf<TextTransformRequest>()

        override suspend fun inspect(modelFile: File) = metadata()

        override suspend fun load(modelFile: File) = Unit

        override fun unload() = Unit

        override suspend fun transform(request: TextTransformRequest): TextTransformResult {
            requests += request
            entered.complete(Unit)
            if (blocked) {
                try {
                    release.await()
                } catch (_: kotlinx.coroutines.CancellationException) {
                    // A synchronous native worker can finish after its caller cancels.
                } finally {
                    withContext(NonCancellable) {
                        cleanup.complete(Unit)
                        cleanupRelease.await()
                    }
                }
            }
            return TextTransformResult("Corrected result", TextTermination.EOG)
        }
    }

    private companion object {
        fun metadata() = AiModelMetadata("qwen35", "Q4_K_M", maximumContextTokens = 4096, textTemplateId = "qwen35-nonthinking-v1")
    }

    private suspend fun TestScope.fixture(): Fixture {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db =
            Room
                .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                .allowMainThreadQueries()
                .setQueryExecutor { it.run() }
                .setTransactionExecutor { it.run() }
                .build()
        val network = db.networkDao().insert(NetworkEntity(name = "test", host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me", role = NetworkRole.DIRECT))
        val room = db.bufferDao().insert(BufferEntity(networkId = network, name = "#room", displayName = "#room", type = BufferType.CHANNEL))
        val model = AiModelRecord(TextModelArtifact.Pinned.sha256, "Qwen", TextModelArtifact.Pinned.sizeBytes, AiModelFormat.QWEN35_GGUF, setOf(AiModelCapability.TEXT_TOOLS), metadata(), 1)
        val initial = AiLabsState(enabledFeatures = setOf(AiFeature.TEXT_TOOLS), models = listOf(model), assignments = listOf(AiFeatureAssignment(AiFeature.TEXT_TOOLS, model.id)))
        val prefs = mutablePreferencesOf(stringPreferencesKey("state_v1") to Json.encodeToString(initial))
        val store = Store(prefs)
        val text = Text()
        val speech =
            object : SpeechModelRuntime {
                override suspend fun inspect(
                    modelFile: File,
                    capability: AiModelCapability,
                ) = error("speech")

                override suspend fun load(
                    modelFile: File,
                    capability: AiModelCapability,
                ) = Unit

                override suspend fun transcribe(
                    pcmWav: File,
                    request: AiTranscriptionRequest,
                    onProgress: (Int) -> Unit,
                ) = error("speech")

                override fun unload() = Unit
            }
        val coordinator =
            AiExecutionCoordinator(
                speech,
                text,
                object : AppVisibility {
                    override val onScreen: StateFlow<Boolean> = MutableStateFlow(true)
                },
                dispatcher,
                backgroundScope,
            )
        coordinator.start()
        val directory = File(context.cacheDir, "text-vm-${System.nanoTime()}").apply { mkdirs() }
        File(directory, "${model.id}.model").writeBytes(byteArrayOf())
        val repository =
            AiLabsRepository(
                store,
                directory,
                object : AiModelSource {
                    override fun metadata(uri: Uri) = AiModelSourceMetadata()

                    override fun open(uri: Uri): InputStream = error("no download or import")
                },
                object : AiLabsRuntimeBoundary {
                    override suspend fun inspect(
                        modelId: String,
                        modelFile: File,
                        capability: AiModelCapability,
                    ) = metadata()

                    override suspend fun unloadForDeletion(modelId: String) = coordinator.unloadForDeletion(modelId)

                    override suspend fun cancelTextTools(unload: Boolean) = coordinator.cancelTextTools(unload)
                },
                backgroundScope,
                dispatcher,
                availableBytes = { Long.MAX_VALUE },
                allocateBytes = { _, _ -> },
            )
        val messages = MessageRepositoryImpl(db.bufferDao(), db.networkIdentityDao(), db, db.reactionDao(), ChatHistoryMediatorFactory { _, _, _ -> error("no paging") }, db.historyGapDao())
        val vm = AiTextViewModel(repository, coordinator, messages)
        runCurrent()
        return Fixture(vm, repository, text, store, db, room, directory)
    }

    private data class Fixture(
        val vm: AiTextViewModel,
        val repository: AiLabsRepository,
        val text: Text,
        val store: Store,
        val db: MotdDatabase,
        val room: Long,
        val directory: File,
    ) {
        fun dispose() {
            vm.close()
            db.close()
            directory.deleteRecursively()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun localeDefaultsRespectChineseScriptRegionAndFallback() {
        assertEquals(AiTranslationTarget("zh-Hant", "Chinese (Traditional)"), defaultTranslationTarget(java.util.Locale.forLanguageTag("zh-Hant-CN")))
        for (region in listOf("TW", "HK", "MO")) assertEquals("zh-Hant", defaultTranslationTarget(java.util.Locale("zh", region)).code)
        assertEquals("zh-Hans", defaultTranslationTarget(java.util.Locale.forLanguageTag("zh-Hans-CN")).code)
        assertEquals("fr", defaultTranslationTarget(java.util.Locale.CANADA_FRENCH).code)
        assertEquals("en", defaultTranslationTarget(java.util.Locale("cy")).code)
    }

    @Test
    fun cancelConfigAbaAndNewRequestExcludeLateResults() =
        runTest {
            val f = fixture()
            try {
                val source = AiComposerDraftSnapshot(1, f.room, 7, "original", 12)
                f.vm.openComposer(source)
                f.vm.generate(AiTextAction(TextOperation.CORRECT))
                f.text.entered.await()
                val mutation = launch { f.repository.setFeatureEnabled(AiFeature.TEXT_TOOLS, false).getOrThrow() }
                f.text.cleanup.await()
                assertEquals(AiTextUiState.Closed, f.vm.state.value)
                assertFalse(mutation.isCompleted)
                f.text.cleanupRelease.complete(Unit)
                mutation.join()
                f.repository.setFeatureEnabled(AiFeature.TEXT_TOOLS, true).getOrThrow()
                runCurrent()
                assertEquals(AiTextUiState.Closed, f.vm.state.value)
                f.text.blocked = false
                f.vm.openComposer(source.copy(requestId = 2))
                f.store.entered = CompletableDeferred()
                f.store.release = CompletableDeferred()
                f.vm.selectTranslationTarget(AiTranslationTarget("fr", "French"))
                f.store.entered!!.await()
                val choosing = f.vm.state.value as AiTextUiState.Choosing
                assertTrue(choosing.isSavingTarget)
                assertEquals(source.copy(requestId = 2), (choosing.source as AiTextSource.Composer).draft)
                f.vm.selectTranslationTarget(AiTranslationTarget("ja", "Japanese"))
                f.vm.generate(AiTextAction(TextOperation.TRANSLATE))
                runCurrent()
                assertEquals(1, f.text.requests.size)
                assertFalse(f.repository.isTextToolsVersionCurrent(0))
                f.store.release!!.complete(Unit)
                runCurrent()
                assertFalse((f.vm.state.value as AiTextUiState.Choosing).isSavingTarget)
                assertEquals(1, f.text.requests.size)
                f.vm.generate(AiTextAction(TextOperation.TRANSLATE))
                runCurrent()
                val result = f.vm.state.value as AiTextUiState.Result
                assertEquals(
                    "French",
                    f.text.requests
                        .last()
                        .targetLanguage,
                )
                f.store.entered = CompletableDeferred()
                f.store.release = CompletableDeferred()
                val delayedMutation = launch { f.repository.setTranslationTarget(AiTranslationTarget("en", "English")).getOrThrow() }
                f.store.entered!!.await()
                assertEquals(AiTranslationTarget("fr", "French"), f.repository.state.value.translationTarget)
                var staleApplied = false
                assertFalse(
                    f.repository.applyIfTextToolsVersion(result.configurationVersion) {
                        staleApplied = true
                        true
                    },
                )
                assertFalse(staleApplied)
                f.store.release!!.complete(Unit)
                delayedMutation.join()
                f.repository.setTranslationTarget(AiTranslationTarget("fr", "French")).getOrThrow()
                var applied = false
                assertFalse(
                    f.vm.applyComposerResult { _, _ ->
                        applied = true
                        true
                    },
                )
                assertFalse(applied)
                assertFalse(f.repository.isTextToolsVersionCurrent(result.configurationVersion))
                f.text.blocked = true
                f.text.entered = CompletableDeferred()
                f.text.release = CompletableDeferred()
                f.text.cleanup = CompletableDeferred()
                f.text.cleanupRelease = CompletableDeferred()
                f.vm.openComposer(source.copy(requestId = 3))
                f.vm.generate(AiTextAction(TextOperation.CORRECT))
                f.text.entered.await()
                f.vm.close()
                f.vm.openComposer(source.copy(requestId = 4))
                f.vm.generate(AiTextAction(TextOperation.CORRECT))
                f.text.cleanup.await()
                assertEquals(3, f.text.requests.size)
                assertTrue(f.vm.state.value is AiTextUiState.Running)
                f.text.blocked = false
                f.text.cleanupRelease.complete(Unit)
                runCurrent()
                assertEquals(4L, (((f.vm.state.value as AiTextUiState.Result).source as AiTextSource.Composer).draft.requestId))
                f.vm.openComposer(source.copy(requestId = 5))
                f.store.failNext = true
                f.vm.selectTranslationTarget(AiTranslationTarget("ja", "Japanese"))
                runCurrent()
                val failed = f.vm.state.value as AiTextUiState.Failed
                assertEquals(source.copy(requestId = 5), (failed.source as AiTextSource.Composer).draft)
                assertEquals(AiTranslationTarget("fr", "French"), f.repository.state.value.translationTarget)
                f.vm.selectTranslationTarget(AiTranslationTarget("ja", "Japanese"))
                runCurrent()
                assertTrue(f.vm.state.value is AiTextUiState.Choosing)
                assertEquals(4, f.text.requests.size)
            } finally {
                f.dispose()
            }
        }

    @Test
    fun redactionClearsSourceResultAndBlocksTransientMsgid() =
        runTest {
            val f = fixture()
            try {
                val row = MessageEntity(bufferId = f.room, msgid = "m", serverTime = 1, sender = "alice", kind = MessageKind.PRIVMSG, text = "private original", dedupKey = "m")
                val id =
                    f.db
                        .messageDao()
                        .insertAll(listOf(row))
                        .single()
                f.vm.openStoredMessage(f.room, id)
                runCurrent()
                assertEquals("private original", (f.vm.state.value as AiTextUiState.Choosing).source.let { (it as AiTextSource.StoredMessage).text })
                f.vm.generate(AiTextAction(TextOperation.TRANSLATE, translationTarget = AiTranslationTarget("fr", "French")))
                f.text.entered.await()
                f.db.messageDao().update(row.copy(id = id, kind = MessageKind.REDACTED, text = "removed"))
                runCurrent()
                assertEquals(AiTextUiState.Closed, f.vm.state.value)
                f.text.cleanupRelease.complete(Unit)
                runCurrent()
                assertEquals(AiTextUiState.Closed, f.vm.state.value)
                f.vm.openTransientMessage(AiTextSource.TransientMessage("server-search:1:0", row.text, f.room, "m"))
                runCurrent()
                assertEquals(AiTextUiState.Closed, f.vm.state.value)
                f.vm.openTransientMessage(AiTextSource.TransientMessage("server-search:2:0", "snapshot", f.room, "unmatched"))
                runCurrent()
                assertTrue(f.vm.state.value is AiTextUiState.Choosing)
                f.text.blocked = false
                f.vm.generate(AiTextAction(TextOperation.TRANSLATE, translationTarget = AiTranslationTarget("fr", "French")))
                runCurrent()
                assertTrue(f.vm.state.value is AiTextUiState.Result)
                val match = row.copy(msgid = "unmatched", dedupKey = "unmatched", text = "snapshot")
                val matchId =
                    f.db
                        .messageDao()
                        .insertAll(listOf(match))
                        .single()
                runCurrent()
                f.db.messageDao().update(match.copy(id = matchId, kind = MessageKind.REDACTED, text = "removed"))
                runCurrent()
                assertEquals(AiTextUiState.Closed, f.vm.state.value)
            } finally {
                f.dispose()
            }
        }
}
