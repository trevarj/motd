package io.github.trevarj.motd.fuzz

import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.random.Random

internal data class FuzzCase(
    val index: Int,
    val seed: String,
    val random: Random,
) {
    private val operations = mutableListOf<String>()

    fun record(operation: String) {
        operations += operation
    }

    internal fun trace(): List<String> = operations
}

/** Android-test counterpart of the pure-JVM deterministic generated-test runner. */
internal object SeededFuzz {
    fun run(
        target: String,
        version: Int,
        prCases: Int,
        nightlyCases: Int,
        replayTest: String,
        block: (FuzzCase) -> Unit,
    ) {
        val configuredSeed = System.getenv("MOTD_FUZZ_SEED")
        val configuredCase = System.getenv("MOTD_FUZZ_CASE")?.toIntOrNull()
        val profileCases = if (System.getenv("MOTD_FUZZ_PROFILE") == "nightly") nightlyCases else prCases
        val generatedCases = System.getenv("MOTD_FUZZ_CASES")?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: profileCases
        val requested = if (configuredCase != null) {
            listOf(CorpusCase(configuredSeed ?: DEFAULT_SEED, configuredCase))
        } else {
            buildList {
                addAll(regressions(target, version))
                repeat(generatedCases) { add(CorpusCase(configuredSeed ?: DEFAULT_SEED, it)) }
            }.distinct()
        }

        requested.forEach { request ->
            val fuzzCase = FuzzCase(
                request.index,
                request.seed,
                Random(derivedSeed(target, version, request.seed, request.index)),
            )
            try {
                block(fuzzCase)
            } catch (failure: Throwable) {
                val replay = "MOTD_FUZZ_SEED=${request.seed.shellQuote()} MOTD_FUZZ_CASE=${request.index} " +
                    "nix develop -c ./gradlew :app:testFossDebugUnitTest " +
                    "--tests '$replayTest' --stacktrace"
                writeFailure(target, version, fuzzCase, replay, failure)
                throw AssertionError(
                    "Generated test failed: target=$target version=$version seed=${request.seed} " +
                        "case=${request.index}\nReplay: $replay",
                    failure,
                )
            }
        }
    }

    suspend fun runSuspending(
        target: String,
        version: Int,
        prCases: Int,
        nightlyCases: Int,
        replayTest: String,
        block: suspend (FuzzCase) -> Unit,
    ) {
        val configuredSeed = System.getenv("MOTD_FUZZ_SEED")
        val configuredCase = System.getenv("MOTD_FUZZ_CASE")?.toIntOrNull()
        val profileCases = if (System.getenv("MOTD_FUZZ_PROFILE") == "nightly") nightlyCases else prCases
        val generatedCases = System.getenv("MOTD_FUZZ_CASES")?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: profileCases
        val requested = if (configuredCase != null) {
            listOf(CorpusCase(configuredSeed ?: DEFAULT_SEED, configuredCase))
        } else {
            buildList {
                addAll(regressions(target, version))
                repeat(generatedCases) { add(CorpusCase(configuredSeed ?: DEFAULT_SEED, it)) }
            }.distinct()
        }

        requested.forEach { request ->
            val fuzzCase = FuzzCase(
                request.index,
                request.seed,
                Random(derivedSeed(target, version, request.seed, request.index)),
            )
            try {
                block(fuzzCase)
            } catch (failure: Throwable) {
                val replay = "MOTD_FUZZ_SEED=${request.seed.shellQuote()} MOTD_FUZZ_CASE=${request.index} " +
                    "nix develop -c ./gradlew :app:testFossDebugUnitTest " +
                    "--tests '$replayTest' --stacktrace"
                writeFailure(target, version, fuzzCase, replay, failure)
                throw AssertionError(
                    "Generated test failed: target=$target version=$version seed=${request.seed} " +
                        "case=${request.index}\nReplay: $replay",
                    failure,
                )
            }
        }
    }

    private fun regressions(target: String, version: Int): List<CorpusCase> {
        val stream = SeededFuzz::class.java.getResourceAsStream("/fuzz/regressions.tsv")
            ?: return emptyList()
        return stream.bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith('#') }
                .mapNotNull { line ->
                    val fields = line.split('\t')
                    if (fields.size < 4 || fields[0] != target || fields[1].toIntOrNull() != version) {
                        null
                    } else {
                        fields[3].toIntOrNull()?.let { CorpusCase(fields[2], it) }
                    }
                }
                .toList()
        }
    }

    private fun derivedSeed(target: String, version: Int, seed: String, index: Int): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$target\u0000$version\u0000$seed\u0000$index".toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).fold(0L) { result, byte -> (result shl 8) or (byte.toLong() and 0xff) }
    }

    private fun writeFailure(
        target: String,
        version: Int,
        fuzzCase: FuzzCase,
        replay: String,
        failure: Throwable,
    ) {
        val dir = File(System.getenv("MOTD_FUZZ_FAILURE_DIR") ?: "build/fuzz-failures")
        if (!dir.exists() && !dir.mkdirs()) return
        val seedHash = MessageDigest.getInstance("SHA-256")
            .digest(fuzzCase.seed.toByteArray(StandardCharsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
        File(dir, "${target.safe()}-$seedHash-${fuzzCase.index}.txt").writeText(
            buildString {
                appendLine("target=$target")
                appendLine("generatorVersion=$version")
                appendLine("seed=${fuzzCase.seed}")
                appendLine("case=${fuzzCase.index}")
                appendLine("failure=${failure::class.qualifiedName}: ${failure.message}")
                appendLine("replay=$replay")
                appendLine("operations:")
                fuzzCase.trace().forEachIndexed { index, operation -> appendLine("$index\t$operation") }
            },
        )
    }

    private fun String.safe(): String = replace(Regex("[^A-Za-z0-9_.-]"), "_")

    private fun String.shellQuote(): String = "'${replace("'", "'\"'\"'")}'"

    private data class CorpusCase(val seed: String, val index: Int)

    private const val DEFAULT_SEED = "motd-fuzz-v1"
}

internal fun fuzzSteps(pr: Int, nightly: Int): Int =
    System.getenv("MOTD_FUZZ_STEPS")?.toIntOrNull()?.takeIf { it > 0 }
        ?: if (System.getenv("MOTD_FUZZ_PROFILE") == "nightly") nightly else pr
