package io.github.trevarj.motd.ai

import io.github.trevarj.motd.audio.ReadAloudVoice
import io.github.trevarj.motd.audio.ReadAloudVoiceGender
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

internal data class KokoroAsset(
    val path: String,
    val sizeBytes: Long,
    val sha256: String,
)

/** One immutable English bundle, not an arbitrary remote model/import format. */
internal data class KokoroBundle(
    val repository: String,
    val revision: String,
    val assets: List<KokoroAsset>,
) {
    init {
        require(assets.isNotEmpty() && assets.map { it.path }.distinct().size == assets.size)
        require(
            assets.all { asset ->
                asset.sizeBytes > 0 && isValidAiModelId(asset.sha256) &&
                    asset.path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_.-]+")) }
            },
        )
    }

    val sizeBytes: Long = assets.sumOf { it.sizeBytes }

    // Canonical identity includes source revision and every relative path, size and digest.
    val id: String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                ("$repository\n$revision\n" + assets.sortedBy { it.path }.joinToString("") { "${it.path}\t${it.sizeBytes}\t${it.sha256}\n" }).toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }

    fun url(asset: KokoroAsset): String = "https://huggingface.co/$repository/resolve/$revision/${asset.path}"

    suspend fun validate(directory: File) {
        val root = directory.toPath()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS)) throw AiLabsException(AiLabsFailureKind.CORRUPT_MODEL)
        val expected = assets.mapTo(mutableSetOf()) { it.path }
        val directories =
            assets
                .flatMap { asset ->
                    val parts = asset.path.split('/')
                    (1 until parts.size).map { parts.take(it).joinToString("/") }
                }.toSet()
        Files.walk(root).use { paths ->
            paths.forEach { path ->
                if (path == root) return@forEach
                val relative = root.relativize(path).toString().replace(File.separatorChar, '/')
                if (Files.isSymbolicLink(path) ||
                    (Files.isDirectory(path, NOFOLLOW_LINKS) && relative !in directories) ||
                    (!Files.isDirectory(path, NOFOLLOW_LINKS) && (!Files.isRegularFile(path, NOFOLLOW_LINKS) || relative !in expected))
                ) {
                    throw AiLabsException(AiLabsFailureKind.CORRUPT_MODEL)
                }
            }
        }
        val buffer = ByteArray(1024 * 1024)
        for (asset in assets) {
            currentCoroutineContext().ensureActive()
            val file = File(directory, asset.path)
            if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || file.length() != asset.sizeBytes) throw AiLabsException(AiLabsFailureKind.CORRUPT_MODEL)
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != asset.sha256) throw AiLabsException(AiLabsFailureKind.CHECKSUM_MISMATCH)
        }
    }

    companion object {
        val Pinned =
            KokoroBundle(
                "csukuangfj/kokoro-int8-multi-lang-v1_0",
                "2a360693d79b88b49b88e29aec2b53577f41f206",
                listOf(
                    KokoroAsset("LICENSE", 11358, "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"),
                    KokoroAsset("espeak-ng-data/en_dict", 166944, "71bd330ba8a2e3e8076e631508208ef49449d6147c17b7bd2b4b1e1468292e35"),
                    KokoroAsset("espeak-ng-data/intonations", 2040, "3f8af65fd3eda9759a10f021d61361c120871f463515229c925995c7f90918cc"),
                    KokoroAsset("espeak-ng-data/lang/gmw/en", 140, "4605d5330801de3641c6e366d15f129ea1f5ffbce8722642aba01ace07ab9c83"),
                    KokoroAsset("espeak-ng-data/lang/gmw/en-029", 335, "faeb8cb201056775f733acbd908c66555fe57bf01cb063920f178f389cbf85d3"),
                    KokoroAsset("espeak-ng-data/lang/gmw/en-GB-scotland", 295, "1ce4282c1f4385dbaf0035e799b2ab1ee9e9a3ab4829100594a3047727b52353"),
                    KokoroAsset("espeak-ng-data/lang/gmw/en-GB-x-gbclan", 238, "2040e176f1f7f27bdce81c32d7e2b1662449d54d264afd3c0ed871b8e42b6e46"),
                    KokoroAsset("espeak-ng-data/lang/gmw/en-GB-x-gbcwmd", 188, "927a5ab891c65b30b4426b151d1623a01024f6b773a38497ba1c90cd77a95747"),
                    KokoroAsset("espeak-ng-data/lang/gmw/en-GB-x-rp", 249, "d0625af7f58561b1b8cf96fd7f93eee6553bcb3eadb9020ae0757bf96e5115e5"),
                    KokoroAsset("espeak-ng-data/lang/gmw/en-US", 257, "41534c2a22df5dd4f1052ff9e1a33a3ea7bff5a26b5c02bdad5ba8ddb7524704"),
                    KokoroAsset("espeak-ng-data/lang/gmw/en-US-nyc", 271, "e8ee9168376d1f7d9e7dcda7c4644c36c529a8bea816e6380895364db46ea2e3"),
                    KokoroAsset("espeak-ng-data/phondata", 550424, "4e0288957874029a8c3c9f41a8f517ad4bf18127046decbdd4b9d1d6807ce3a3"),
                    KokoroAsset("espeak-ng-data/phonindex", 39074, "3ca7b8fa3b42624e4b0f152707e7a39245fce569aa99ea47c055d9e622fcf0c4"),
                    KokoroAsset("espeak-ng-data/phontab", 55796, "886f3fa402cb0ba73d483aa8ad000af47a6b7cc06293c75a97913fba68a530f6"),
                    KokoroAsset("lexicon-gb-en.txt", 6366635, "c4cbb37316f62210dff52718a7afcaae24f50c032cc75ab47ae67b831d1049e7"),
                    KokoroAsset("lexicon-us-en.txt", 5956885, "7daaab53a181be9885b853a8582bf1838186317e5dadacbcef9c426d6fa0da14"),
                    KokoroAsset("tokens.txt", 687, "6ebb6bb288f20f3ae8d004d3c2ca27697da27c037d75e81a60e2a6a663f95425"),
                    KokoroAsset("voices.bin", 28200960, "1c5a5b983d3d50d8586d437a51f3faa2da7919ce76a013c081e65671a3447c29"),
                    KokoroAsset("model.int8.onnx", 114203756, "4b86207ef680e394d8343bee22dfc4c512e5c707c6d9578e3f35ab09bffd6b36"),
                ),
            )
    }
}

/** IDs/gender/dialect from the pinned Kokoro voice catalog; only checked English assets are exposed. */
val kokoroEnglishVoices: List<ReadAloudVoice> =
    listOf(
        ReadAloudVoice("3", "Heart", "en-US", ReadAloudVoiceGender.FEMALE),
        ReadAloudVoice("2", "Bella", "en-US", ReadAloudVoiceGender.FEMALE),
        ReadAloudVoice("9", "Sarah", "en-US", ReadAloudVoiceGender.FEMALE),
        ReadAloudVoice("16", "Michael", "en-US", ReadAloudVoiceGender.MALE),
        ReadAloudVoice("14", "Fenrir", "en-US", ReadAloudVoiceGender.MALE),
        ReadAloudVoice("18", "Puck", "en-US", ReadAloudVoiceGender.MALE),
        ReadAloudVoice("21", "Emma", "en-GB", ReadAloudVoiceGender.FEMALE),
        ReadAloudVoice("26", "George", "en-GB", ReadAloudVoiceGender.MALE),
    )

/** Walk without following symlinks: cleanup can only remove this owned entry and its descendants. */
internal fun deleteOwnedModelEntry(file: File) {
    if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return
    Files.walk(file.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
    }
}
