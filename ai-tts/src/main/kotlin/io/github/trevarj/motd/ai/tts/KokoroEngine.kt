package io.github.trevarj.motd.ai.tts

import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Blocking, CPU-only synthesis. The caller owns the checked model directory and completed WAV. */
class KokoroEngine private constructor(
    private val native: OfflineTts,
) {
    private val cancelled = AtomicBoolean(false)
    private val releasing = AtomicBoolean(false)
    private var released = false

    val sampleRate: Int = native.sampleRate()
    val numSpeakers: Int = native.numSpeakers()

    init {
        if (sampleRate != 24_000 || numSpeakers != 54) {
            native.release()
            error("Kokoro requires the checked 54-voice, 24 kHz model; got $numSpeakers voices at $sampleRate Hz")
        }
    }

    /**
     * Never call on the UI thread. Output may be absent or an empty caller-owned temporary file.
     * Cancellation drains JNI before deleting any partial output.
     */
    @Synchronized
    fun generate(
        text: String,
        outputFile: File,
        voiceId: Int = 3,
        language: String = "en-us",
        rate: Float = 1f,
        isCancelled: () -> Boolean = { false },
    ): File {
        check(!released && !releasing.get()) { "Kokoro engine has been released" }
        require(text.isNotBlank()) { "Speech text is empty" }
        require(voiceId in 0 until numSpeakers) { "Invalid Kokoro voice ID" }
        require(language == "en-us" || language == "en") { "Only English pronunciation data is installed" }
        require(rate.isFinite() && rate > 0f) { "Speech rate must be positive and finite" }
        validateKokoroOutputFile(outputFile)
        cancelled.set(false)
        var callbackFailure: Throwable? = null

        fun stopped(): Boolean {
            // JNI must never receive a thrown callback exception, including caller cancellation.
            return try {
                cancelled.get() || releasing.get() || isCancelled()
            } catch (failure: Throwable) {
                callbackFailure = failure
                true
            }
        }

        fun checkCancellation() {
            val stop = stopped()
            callbackFailure?.let { throw it }
            if (stop) throw CancellationException("Kokoro synthesis cancelled")
        }
        var complete = false
        try {
            checkCancellation()
            val audio =
                native.generateWithConfigAndCallback(
                    text,
                    GenerationConfig(sid = voiceId, speed = rate, extra = mapOf("lang" to language)),
                ) { _ ->
                    if (stopped()) {
                        cancelled.set(true)
                        0
                    } else {
                        1
                    }
                }
            checkCancellation()
            check(audio.sampleRate == sampleRate && audio.samples.isNotEmpty()) { "Kokoro produced no valid audio" }
            check(audio.save(outputFile.absolutePath)) { "Could not save speech WAV: $outputFile" }
            checkCancellation()
            check(outputFile.isFile && outputFile.length() == 44L + audio.samples.size * 2L) { "Speech WAV was not completed" }
            complete = true
            return outputFile
        } finally {
            if (!complete) outputFile.delete()
        }
    }

    /** Requests stop at the next native chunk callback; cannot interrupt an ongoing ORT Run. */
    fun cancel() {
        cancelled.set(true)
    }

    /** Blocks until generation has actually returned; safe to delete the model directory afterward. */
    fun release() {
        releasing.set(true)
        cancel()
        synchronized(this) {
            if (!released) {
                native.release()
                released = true
            }
        }
    }

    companion object {
        fun load(checkedAssetDirectory: File): KokoroEngine {
            val directory = checkedAssetDirectory.canonicalFile
            require(directory.isDirectory) { "Kokoro asset directory is missing" }
            for (name in listOf("model.int8.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-gb-en.txt")) {
                require(File(directory, name).isFile) { "Missing Kokoro asset: $name" }
            }
            require(File(directory, "espeak-ng-data").isDirectory) { "Missing English pronunciation data" }
            require(File(directory, "voices.bin").length() == 54L * 510L * 256L * 4L) { "Invalid Kokoro voice data" }
            // Upstream loads JNI itself. Preload its separately source-built, unversioned dependency.
            System.loadLibrary("onnxruntime")
            return KokoroEngine(
                OfflineTts(
                    config =
                        OfflineTtsConfig(
                            model =
                                OfflineTtsModelConfig(
                                    kokoro =
                                        OfflineTtsKokoroModelConfig(
                                            model = File(directory, "model.int8.onnx").path,
                                            voices = File(directory, "voices.bin").path,
                                            tokens = File(directory, "tokens.txt").path,
                                            dataDir = File(directory, "espeak-ng-data").path,
                                            lexicon = "${File(directory, "lexicon-us-en.txt").path},${File(directory, "lexicon-gb-en.txt").path}",
                                            lang = "en-us",
                                        ),
                                    numThreads = 1,
                                    provider = "cpu",
                                ),
                            maxNumSentences = 1,
                        ),
                ),
            )
        }
    }
}

internal fun validateKokoroOutputFile(outputFile: File) {
    require(
        !Files.isSymbolicLink(outputFile.toPath()) &&
            (!outputFile.exists() || (outputFile.isFile && outputFile.length() == 0L)),
    ) { "Speech output must be absent or an empty regular temporary file: $outputFile" }
}
