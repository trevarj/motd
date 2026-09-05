package io.github.trevarj.motd.ai

import androidx.test.platform.app.InstrumentationRegistry
import io.github.trevarj.motd.ai.whisper.WhisperCorruptModelException
import io.github.trevarj.motd.ai.whisper.WhisperModelMagicException
import io.github.trevarj.motd.ai.whisper.WhisperRuntime
import io.github.trevarj.motd.ai.whisper.WhisperTruncatedModelException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object WhisperNativeAssertions {
    fun assertMalformedModelsDoNotAbortOrPoisonLaterInspections() =
        runBlocking {
            val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            val files =
                listOf(
                    tempModel(cache, "wrong-magic", ByteArray(64) { 0x7f }),
                    tempModel(
                        cache,
                        "truncated-whisper",
                        ByteBuffer
                            .allocate(Int.SIZE_BYTES)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(0x67676d6c)
                            .array(),
                    ),
                    tempModel(
                        cache,
                        "oversized-whisper-header",
                        ByteBuffer
                            .allocate(12 * Int.SIZE_BYTES)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(0x67676d6c)
                            .putInt(100_000)
                            .putInt(4096)
                            .putInt(2048)
                            .putInt(128)
                            .putInt(32)
                            .putInt(4096)
                            .putInt(2048)
                            .putInt(128)
                            .putInt(64)
                            .putInt(128)
                            .putInt(0)
                            .array(),
                    ),
                )
            val (wrongMagic, truncatedWhisper, oversizedWhisperHeader) = files

            try {
                assertTyped<WhisperModelMagicException> { WhisperRuntime.inspect(wrongMagic) }
                assertTyped<WhisperTruncatedModelException> { WhisperRuntime.inspect(truncatedWhisper) }
                assertTyped<WhisperCorruptModelException> {
                    WhisperRuntime.inspect(oversizedWhisperHeader)
                }

                // A malformed header must leave the native runtime usable for the next inspection.
                assertTyped<WhisperModelMagicException> { WhisperRuntime.inspect(wrongMagic) }
            } finally {
                files.forEach(File::delete)
            }
        }

    private fun tempModel(
        cache: File,
        prefix: String,
        bytes: ByteArray,
    ): File = File.createTempFile("ai-native-$prefix-", ".model", cache).apply { writeBytes(bytes) }

    private suspend inline fun <reified T : Throwable> assertTyped(noinline call: suspend () -> Unit) {
        val failure = runCatching { call() }.exceptionOrNull()
        assertTrue(
            "Expected ${T::class.java.simpleName}, got ${failure?.javaClass?.name ?: "success"}",
            failure is T,
        )
    }
}
