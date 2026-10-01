package io.github.trevarj.motd.ai

import io.github.trevarj.motd.service.AppVisibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

private val TRANSCRIPTION_ID = "a".repeat(64)
private val OTHER_TRANSCRIPTION_ID = "b".repeat(64)

@OptIn(ExperimentalCoroutinesApi::class)
class AiExecutionCoordinatorTest {
    @Test
    fun `transcription reuses its resident model and unloads before switching Whisper models`() =
        runTest {
            val world = world()
            val progress = mutableListOf<Int>()

            assertEquals("transcript", world.transcribe(onProgress = progress::add))
            assertEquals("transcript", world.transcribe(onProgress = progress::add))
            assertEquals("transcript", world.transcribe(OTHER_TRANSCRIPTION_ID, progress::add))

            assertEquals(listOf(10, 100, 10, 100, 10, 100), progress)
            assertEquals(
                listOf(
                    "speech.load:tiny.bin",
                    "speech.transcribe",
                    "speech.transcribe",
                    "speech.unload",
                    "speech.load:base.bin",
                    "speech.transcribe",
                ),
                world.events,
            )
        }

    @Test
    fun `waiting transcription cannot switch models until active native cleanup finishes`() =
        runTest {
            val world = world()
            world.speech.blockTranscription = true
            val active = async { world.transcribe() }
            world.speech.transcriptionStarted.await()
            val waiter = async { world.transcribe(OTHER_TRANSCRIPTION_ID) }
            runCurrent()

            assertFalse(waiter.isCompleted)
            assertFalse("speech.load:base.bin" in world.events)
            world.speech.allowTranscription.complete(Unit)
            runCurrent()
            assertFalse("speech.unload" in world.events)
            assertFalse(waiter.isCompleted)

            world.speech.blockTranscription = false
            world.speech.allowTranscriptionCleanup.complete(Unit)
            assertEquals("transcript", active.await())
            assertEquals("transcript", waiter.await())
            assertTrue(
                world.events.indexOf("speech.transcribe.cleanup.end") < world.events.indexOf("speech.unload"),
            )
        }

    @Test
    fun `live background state rejects acquisition before the visibility collector runs`() =
        runTest {
            val world = world()

            world.visibility.state.value = false
            val rejected = runCatching { world.transcribe() }.exceptionOrNull()

            assertTrue(rejected is AiExecutionUnavailableException)
            assertTrue("rejected work never reached the runtime seam", world.events.isEmpty())
        }

    @Test
    fun `inspection unloads its temporary model and leaves no resident lease`() =
        runTest {
            val world = world()
            world.transcribe()
            world.events.clear()

            assertEquals(
                speechMetadata(),
                world.coordinator.inspect(
                    OTHER_TRANSCRIPTION_ID,
                    File("base.bin"),
                    AiModelCapability.TRANSCRIPTION,
                ),
            )
            assertEquals(
                listOf("speech.unload", "speech.inspect:base.bin", "speech.unload"),
                world.events,
            )
            world.events.clear()

            assertEquals("transcript", world.transcribe())
            assertEquals(listOf("speech.load:tiny.bin", "speech.transcribe"), world.events)
        }

    @Test
    fun `deletion unloads only the matching resident off the caller thread before returning`() =
        runTest {
            val callingThread = Thread.currentThread()
            Executors
                .newSingleThreadExecutor { runnable -> Thread(runnable, "ai-unload-test") }
                .asCoroutineDispatcher()
                .use { unloadDispatcher ->
                    val world = world(unloadDispatcher)
                    world.transcribe()
                    world.events.clear()

                    world.coordinator.unloadForDeletion(OTHER_TRANSCRIPTION_ID)
                    assertTrue(world.events.isEmpty())
                    world.coordinator.unloadForDeletion(TRANSCRIPTION_ID)
                    world.events += "returned"
                    world.coordinator.unloadForDeletion(TRANSCRIPTION_ID)

                    assertEquals(listOf("speech.unload", "returned"), world.events)
                    assertEquals("ai-unload-test", world.speech.unloadThread?.name)
                    assertFalse("native unload must not run on the caller thread", world.speech.unloadThread === callingThread)
                }
        }

    @Test
    fun `background cancels active and waiting work then joins cleanup before unloading`() =
        runTest {
            val world = world()
            val progress = mutableListOf<Int>()
            world.speech.blockTranscription = true
            val active = async { runCatching { world.transcribe(onProgress = progress::add) }.exceptionOrNull() }
            world.speech.transcriptionStarted.await()
            val waiter = async { runCatching { world.transcribe(OTHER_TRANSCRIPTION_ID) }.exceptionOrNull() }
            runCurrent()

            world.visibility.state.value = false
            runCurrent()

            assertTrue("active native cleanup started", "speech.transcribe.cleanup.start" in world.events)
            assertFalse("resident was retained until cleanup finished", "speech.unload" in world.events)
            assertTrue("mutex waiter was cancelled", waiter.isCompleted)
            val rejected = runCatching { world.transcribe() }.exceptionOrNull()
            assertTrue(rejected is AiExecutionUnavailableException)

            world.speech.allowTranscriptionCleanup.complete(Unit)
            runCurrent()

            assertTrue(active.await() is CancellationException)
            assertTrue(waiter.await() is CancellationException)
            assertEquals(listOf(10), progress)
            assertTrue(
                world.events.indexOf("speech.transcribe.cleanup.end") < world.events.indexOf("speech.unload"),
            )
            assertFalse("cancelled waiter never reached the runtime seam", "speech.load:base.bin" in world.events)
            assertEquals(1, world.events.count { it == "speech.transcribe" })

            world.speech.blockTranscription = false
            world.visibility.state.value = true
            runCurrent()
            assertEquals("transcript", world.transcribe(OTHER_TRANSCRIPTION_ID))
        }

    @Test
    fun switchingSpeechAndTextJoinsCleanupBeforeLoad() =
        runTest {
            val world = world()
            world.speech.blockTranscription = true
            val active = async { runCatching { world.transcribe() } }
            world.speech.transcriptionStarted.await()
            val text =
                async {
                    world.coordinator.transform(
                        TRANSCRIPTION_ID,
                        File("tiny.bin"),
                        io.github.trevarj.motd.ai.text
                            .TextTransformRequest(io.github.trevarj.motd.ai.text.TextOperation.CORRECT, "Draft"),
                        { true },
                    )
                }
            runCurrent()
            active.cancel()
            runCurrent()
            assertFalse("text.load" in world.events)
            world.speech.allowTranscriptionCleanup.complete(Unit)
            active.join()
            text.await()
            assertTrue(world.events.indexOf("speech.transcribe.cleanup.end") < world.events.indexOf("speech.unload"))
            assertTrue(world.events.indexOf("speech.unload") < world.events.indexOf("text.load"))
            world.speech.blockTranscription = false
            world.transcribe()
            assertTrue(world.events.indexOf("text.unload") < world.events.lastIndexOf("speech.load:tiny.bin"))
        }

    @Test
    fun backgroundAndDeletionCancelTextWaitersBeforeUnloading() =
        runTest {
            for (background in listOf(false, true)) {
                val world = world()
                world.text.block = true

                suspend fun transform() =
                    world.coordinator.transform(
                        TRANSCRIPTION_ID,
                        File("text.model"),
                        io.github.trevarj.motd.ai.text
                            .TextTransformRequest(io.github.trevarj.motd.ai.text.TextOperation.CORRECT, "Draft"),
                        { true },
                    )
                val active = async { runCatching { transform() } }
                world.text.started.await()
                val waiter = async { runCatching { transform() } }
                runCurrent()
                val deletion = if (!background) async { world.coordinator.unloadForDeletion(TRANSCRIPTION_ID) } else null
                if (background) world.visibility.state.value = false
                runCurrent()
                assertTrue(waiter.isCompleted)
                assertFalse("text.unload" in world.events)
                assertFalse(deletion?.isCompleted == true)
                if (background) assertTrue(runCatching { transform() }.exceptionOrNull() is AiExecutionUnavailableException)
                world.text.cleanup.complete(Unit)
                active.join()
                waiter.join()
                deletion?.await()
                runCurrent()
                assertTrue(active.await().exceptionOrNull() is CancellationException)
                assertTrue(waiter.await().exceptionOrNull() is CancellationException)
                assertEquals(1, world.events.count { it == "text.load" })
                assertTrue(world.events.indexOf("text.cleanup") < world.events.indexOf("text.unload"))
            }
        }

    @Test
    fun blockingKokoroCancellationHoldsResidencyAndDeletionUntilWorkerWritesDrain() =
        runTest {
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
                for (background in listOf(false, true)) {
                    val world = world(worker)
                    val directory =
                        java.nio.file.Files
                            .createTempDirectory("kokoro-coordinator")
                            .toFile()
                    val output = File(directory, "speech.wav")
                    world.kokoro.block = true
                    val active =
                        async {
                            try {
                                runCatching {
                                    world.coordinator.synthesize(
                                        "c".repeat(64),
                                        directory,
                                        "Hello",
                                        io.github.trevarj.motd.audio
                                            .ReadAloudConfig(),
                                        output,
                                    ) { true }
                                }
                            } finally {
                                output.delete()
                            }
                        }
                    var waiter: Job? = null
                    var deletion: Job? = null
                    try {
                        runCurrent()
                        world.kokoro.started.await()
                        val queued =
                            async {
                                runCatching {
                                    world.coordinator.synthesize(
                                        "c".repeat(64),
                                        directory,
                                        "Never spoken",
                                        io.github.trevarj.motd.audio
                                            .ReadAloudConfig(),
                                        File(directory, "waiter.wav"),
                                    ) { true }
                                }
                            }
                        waiter = queued
                        runCurrent()
                        val deleting =
                            if (!background) {
                                async {
                                    world.coordinator.unloadForDeletion("c".repeat(64))
                                    directory.deleteRecursively()
                                }
                            } else {
                                null
                            }
                        deletion = deleting
                        if (background) world.visibility.state.value = false
                        runCurrent()
                        world.kokoro.cancelled.await()
                        assertFalse(active.isCompleted)
                        assertFalse(deleting?.isCompleted == true)
                        assertTrue(directory.exists())
                        assertFalse(world.events.contains("kokoro.unload"))
                        world.kokoro.finish.countDown()
                        active.join()
                        queued.join()
                        deleting?.await()
                        // runCurrent alone cannot wait for unloading on the real worker dispatcher.
                        world.kokoro.unloaded.await()
                        runCurrent()
                        assertTrue(active.await().exceptionOrNull() is CancellationException)
                        assertTrue(queued.await().exceptionOrNull() is CancellationException)
                        assertFalse(output.exists())
                        assertEquals(1, world.events.count { it == "kokoro.generate" })
                        assertTrue(world.events.indexOf("kokoro.write.end") < world.events.indexOf("kokoro.unload"))
                    } finally {
                        world.kokoro.finish.countDown()
                        withContext(NonCancellable) {
                            active.cancelAndJoin()
                            waiter?.cancelAndJoin()
                            deletion?.cancelAndJoin()
                        }
                        directory.deleteRecursively()
                    }
                }
            }
        }

    @Test
    fun kokoroColdLoadsAreValidatedOnceAndSwitchThroughTheSameWhisperAndTextResidency() =
        runTest {
            val world = world()
            val output =
                java.nio.file.Files
                    .createTempFile("speech", ".wav")
                    .toFile()
            try {
                world.transcribe()
                repeat(2) {
                    world.coordinator.synthesize(
                        "c".repeat(64),
                        File("model-directory"),
                        "Hello",
                        io.github.trevarj.motd.audio
                            .ReadAloudConfig(voice = "16"),
                        output,
                    ) { true }
                }
                assertEquals(1, world.events.count { it == "kokoro.validate" })
                assertTrue(world.events.indexOf("speech.unload") < world.events.indexOf("kokoro.load"))
                world.coordinator.transform(
                    "d".repeat(64),
                    File("text.gguf"),
                    io.github.trevarj.motd.ai.text
                        .TextTransformRequest(io.github.trevarj.motd.ai.text.TextOperation.CORRECT, "helo"),
                ) { true }
                assertTrue(world.events.indexOf("kokoro.unload") < world.events.indexOf("text.load"))
                world.coordinator.synthesize(
                    "c".repeat(64),
                    File("model-directory"),
                    "Again",
                    io.github.trevarj.motd.audio
                        .ReadAloudConfig(),
                    output,
                ) { true }
                assertEquals(2, world.events.count { it == "kokoro.validate" })
            } finally {
                output.delete()
            }
        }

    @Test
    fun rejectedColdAssetValidationNeverLoadsOrGeneratesEvenWithReadyPersistedMetadata() =
        runTest {
            val world = world()
            world.kokoro.validationFailure = AiLabsException(AiLabsFailureKind.CHECKSUM_MISMATCH)
            val output =
                java.nio.file.Files
                    .createTempFile("speech-invalid", ".wav")
                    .toFile()
            try {
                val failure =
                    runCatching {
                        world.coordinator.synthesize(
                            "c".repeat(64),
                            File("untrusted-model"),
                            "Hello",
                            io.github.trevarj.motd.audio
                                .ReadAloudConfig(),
                            output,
                        ) { true }
                    }.exceptionOrNull()
                assertTrue(failure is AiLabsException)
                assertFalse(world.events.contains("kokoro.load"))
                assertFalse(world.events.contains("kokoro.generate"))
                assertEquals(0L, output.length())
            } finally {
                output.delete()
            }
        }

    private fun TestScope.world(
        unloadDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): World {
        val events: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val speech = FakeSpeechRuntime(events)
        val text = FakeTextRuntime(events)
        val kokoro = FakeKokoroRuntime(events)
        val visibility = FakeVisibility(true)
        val coordinator = AiExecutionCoordinator(speech, text, kokoro, visibility, unloadDispatcher, backgroundScope)
        coordinator.start()
        runCurrent()
        return World(coordinator, speech, text, visibility, events, kokoro)
    }

    private data class World(
        val coordinator: AiExecutionCoordinator,
        val speech: FakeSpeechRuntime,
        val text: FakeTextRuntime,
        val visibility: FakeVisibility,
        val events: MutableList<String>,
        val kokoro: FakeKokoroRuntime,
    ) {
        suspend fun transcribe(
            modelId: String = TRANSCRIPTION_ID,
            onProgress: (Int) -> Unit = {},
        ): String =
            coordinator.transcribe(
                modelId,
                File(if (modelId == TRANSCRIPTION_ID) "tiny.bin" else "base.bin"),
                AiModelCapability.TRANSCRIPTION,
                File("audio.wav"),
                AiTranscriptionRequest(TranscriptionSettings()),
                onProgress,
            )
    }

    private class FakeVisibility(
        initiallyVisible: Boolean,
    ) : AppVisibility {
        val state = MutableStateFlow(initiallyVisible)
        override val onScreen: StateFlow<Boolean> = state
    }

    private class FakeTextRuntime(
        private val events: MutableList<String>,
    ) : TextModelRuntime {
        var block = false
        val started = CompletableDeferred<Unit>()
        val cleanup = CompletableDeferred<Unit>()

        override suspend fun inspect(modelFile: File) = AiModelMetadata("qwen35", "Q4_K_M", maximumContextTokens = 4096, textTemplateId = "qwen35-nonthinking-v1")

        override suspend fun load(modelFile: File) {
            events += "text.load"
        }

        override suspend fun transform(request: io.github.trevarj.motd.ai.text.TextTransformRequest): io.github.trevarj.motd.ai.text.TextTransformResult {
            if (block) {
                started.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    withContext(NonCancellable) { cleanup.await() }
                    events += "text.cleanup"
                }
            }
            return io.github.trevarj.motd.ai.text
                .TextTransformResult("Corrected", io.github.trevarj.motd.ai.text.TextTermination.EOG)
        }

        override fun unload() {
            events += "text.unload"
        }
    }

    private class FakeKokoroRuntime(
        private val events: MutableList<String>,
    ) : KokoroModelRuntime {
        var block = false
        var validationFailure: Throwable? = null
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val unloaded = CompletableDeferred<Unit>()
        val finish = java.util.concurrent.CountDownLatch(1)
        private val stopped =
            java.util.concurrent.atomic
                .AtomicBoolean(false)

        override suspend fun validate(modelDirectory: File) {
            events += "kokoro.validate"
            validationFailure?.let { throw it }
        }

        override fun load(modelDirectory: File): AiModelMetadata {
            events += "kokoro.load"
            return AiModelMetadata("kokoro", "int8", sampleRateHz = 24_000, voiceCount = 54)
        }

        override fun generate(
            text: String,
            config: io.github.trevarj.motd.audio.ReadAloudConfig,
            output: File,
            isCancelled: () -> Boolean,
        ) {
            events += "kokoro.generate"
            started.complete(Unit)
            if (block) finish.await()
            output.writeText(text)
            events += "kokoro.write.end"
            if (stopped.get() || isCancelled()) throw CancellationException("native callback stopped")
        }

        override fun cancel() {
            stopped.set(true)
            cancelled.complete(Unit)
        }

        override fun unload() {
            events += "kokoro.unload"
            stopped.set(false)
            unloaded.complete(Unit)
        }
    }

    private class FakeSpeechRuntime(
        private val events: MutableList<String>,
    ) : SpeechModelRuntime {
        var blockTranscription = false
        val transcriptionStarted = CompletableDeferred<Unit>()
        val allowTranscription = CompletableDeferred<Unit>()
        val allowTranscriptionCleanup = CompletableDeferred<Unit>()
        var unloadThread: Thread? = null
        private var residentFile: File? = null

        override suspend fun inspect(
            modelFile: File,
            capability: AiModelCapability,
        ): AiModelMetadata {
            check(residentFile == null) { "Previous model has not been unloaded" }
            residentFile = modelFile
            events += "speech.inspect:${modelFile.name}"
            return speechMetadata()
        }

        override suspend fun load(
            modelFile: File,
            capability: AiModelCapability,
        ) {
            check(residentFile == null) { "Previous model has not been unloaded" }
            residentFile = modelFile
            events += "speech.load:${modelFile.name}"
        }

        override suspend fun transcribe(
            pcmWav: File,
            request: AiTranscriptionRequest,
            onProgress: (Int) -> Unit,
        ): String {
            check(residentFile != null) { "No model is loaded" }
            events += "speech.transcribe"
            onProgress(10)
            if (blockTranscription) {
                transcriptionStarted.complete(Unit)
                try {
                    allowTranscription.await()
                } finally {
                    events += "speech.transcribe.cleanup.start"
                    withContext(NonCancellable) { allowTranscriptionCleanup.await() }
                    onProgress(90)
                    events += "speech.transcribe.cleanup.end"
                }
            }
            onProgress(100)
            return "transcript"
        }

        override fun unload() {
            unloadThread = Thread.currentThread()
            events += "speech.unload"
            residentFile = null
        }
    }
}

private fun speechMetadata() =
    AiModelMetadata(
        architecture = "whisper",
        quantization = "test",
        maximumAudioSeconds = 900,
        maximumCpuThreads = 4,
        isMultilingual = true,
    )
