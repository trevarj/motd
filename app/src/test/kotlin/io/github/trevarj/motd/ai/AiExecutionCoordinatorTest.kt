package io.github.trevarj.motd.ai

import io.github.trevarj.motd.service.AppVisibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
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

    private fun TestScope.world(
        unloadDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): World {
        val events = mutableListOf<String>()
        val speech = FakeSpeechRuntime(events)
        val visibility = FakeVisibility(true)
        val coordinator = AiExecutionCoordinator(speech, visibility, unloadDispatcher, backgroundScope)
        coordinator.start()
        runCurrent()
        return World(coordinator, speech, visibility, events)
    }

    private data class World(
        val coordinator: AiExecutionCoordinator,
        val speech: FakeSpeechRuntime,
        val visibility: FakeVisibility,
        val events: MutableList<String>,
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
