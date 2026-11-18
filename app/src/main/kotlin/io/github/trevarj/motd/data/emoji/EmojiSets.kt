package io.github.trevarj.motd.data.emoji

data class EmojiSetSpec(
    val id: String,
    val displayName: String,
    val version: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    val licenseName: String,
    val licenseUrl: String,
    val sourceUrl: String,
)

object EmojiSets {
    const val SYSTEM_ID = "system"

    val all: List<EmojiSetSpec> =
        listOf(
            EmojiSetSpec(
                id = "noto",
                displayName = "Noto Color Emoji",
                version = "2.051",
                downloadUrl = "https://raw.githubusercontent.com/googlefonts/noto-emoji/8998f5dd683424a73e2314a8c1f1e359c19e8742/fonts/NotoColorEmoji.ttf",
                sizeBytes = 10_673_480,
                sha256 = "72a635cb3d2f3524c51620cdde406b217204e8a6a06c6a096ff8ed4b5fd6e27b",
                licenseName = "SIL Open Font License 1.1",
                licenseUrl = "https://github.com/googlefonts/noto-emoji/blob/8998f5dd683424a73e2314a8c1f1e359c19e8742/fonts/LICENSE",
                sourceUrl = "https://github.com/googlefonts/noto-emoji",
            ),
            EmojiSetSpec(
                id = "openmoji",
                displayName = "OpenMoji",
                version = "16.0",
                downloadUrl = "https://raw.githubusercontent.com/hfg-gmuend/openmoji/66e17da0f2d4347f64ee9d78c367fc5234283863/font/OpenMoji-color-cbdt/OpenMoji-color-cbdt.ttf",
                sizeBytes = 6_334_960,
                sha256 = "de2cf500a9fe40c696205648300a2c00dcd6017c6d211909cb4b434472a7745d",
                licenseName = "Creative Commons Attribution-ShareAlike 4.0",
                licenseUrl = "https://github.com/hfg-gmuend/openmoji/blob/66e17da0f2d4347f64ee9d78c367fc5234283863/LICENSE.txt",
                sourceUrl = "https://github.com/hfg-gmuend/openmoji",
            ),
        )

    fun find(id: String): EmojiSetSpec? = all.find { it.id == id }
}
