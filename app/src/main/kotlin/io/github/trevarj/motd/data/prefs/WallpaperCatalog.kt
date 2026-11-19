package io.github.trevarj.motd.data.prefs

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Collections

@Serializable
internal enum class WallpaperCatalogCategory(
    val label: String,
) {
    MOTD("MOTD"),
    TECH("Tech"),
    COMPUTER_GEAR("Computer Gear"),
    EMOJI_MEMES("Emoji & Memes"),
    GAMING("Gaming"),
    SPACE_RADIO("Space & Radio"),
    DOODLES("Doodles"),
    IMPORTS("Imports"),
}

@Serializable
internal data class WallpaperCatalogEntry(
    val id: String,
    val name: String,
    val category: WallpaperCatalogCategory,
    val aliases: List<String>,
    val svgPath: String,
    val source: String,
    val license: String,
) {
    fun matches(query: String): Boolean {
        val term = query.trim()
        return name.contains(term, ignoreCase = true) || aliases.any { it.contains(term, ignoreCase = true) }
    }
}

internal object WallpaperCatalog {
    private const val DIRECTORY = "wallpaper-icons/"
    private const val PREFIX = "builtin:"

    // Restore validation also runs without an Android Context.
    // Opening the manifest checks that this ID index still agrees with the packaged catalog.
    private val categories =
        buildMap {
            fun bundle(
                category: WallpaperCatalogCategory,
                names: String,
            ) {
                for (line in names.trimIndent().lineSequence()) {
                    for (name in line.split(' ')) check(put(PREFIX + name, category) == null)
                }
            }

            bundle(
                WallpaperCatalogCategory.MOTD,
                """
                motd-logo motd-wordmark terminal irc-server irc-channel irc-code
                chat-bubble chat-thread irc-join irc-buddies irc-mention irc-window
                graffiti-skull graffiti-peace-hand graffiti-ladybug graffiti-heart-arrow graffiti-crown graffiti-lightning
                graffiti-paper-roll graffiti-tally graffiti-motd-rulez graffiti-motd-was-here graffiti-irc-4ever graffiti-join-the-fun
                graffiti-star-trio graffiti-star-scribble graffiti-margin-marks graffiti-googly-eyes graffiti-crescent graffiti-daisy
                graffiti-spiral graffiti-orbit
                """,
            )
            bundle(
                WallpaperCatalogCategory.TECH,
                """
                network code-file globe cloud lock link
                email qr-code gear lab-flask wifi cursor
                bug robot error-404 package-box lisp tux
                debian-swirl alpine-arch fedora-hat community-circle nixos-flake guix-horns
                emacs-ribbon neovim-mark systemcrafters-logo
                """,
            )
            bundle(
                WallpaperCatalogCategory.COMPUTER_GEAR,
                """
                crt-monitor keyboard wired-mouse floppy-disk usb-plug desktop-tower
                ethernet microchip modem headphones laptop phone-handset
                """,
            )
            bundle(
                WallpaperCatalogCategory.EMOJI_MEMES,
                """
                grin laugh-cry gasp sunglasses side-eye happy-wink
                sleepy sad-tear angry-face heart-eyes crooked-smile frog-reaction
                shiba-reaction cat-monitor comic-burst lol-bubble trash-fire coffee-rage
                """,
            )
            bundle(
                WallpaperCatalogCategory.GAMING,
                """
                gamepad joystick handheld arcade-cabinet cartridge pixel-invader
                pixel-heart field-toolkit lan-tower quest-sword rune city-car
                city-block racing-flag d20 health-potion treasure-chest platform-boot
                quest-shield puzzle-block pinball rhythm-arrows
                """,
            )
            bundle(
                WallpaperCatalogCategory.SPACE_RADIO,
                """
                ringed-planet rocket satellite ufo astronaut crescent
                constellation comet radio-dish radio-tower tabletop-radio cassette
                walkie-talkie radio-mic
                """,
            )
            bundle(
                WallpaperCatalogCategory.DOODLES,
                """
                dot-cluster four-point-spark squiggle triangle-pair tiny-crosses rainbow-arcs
                diamond-pair star lightning small-heart curly-arrow dash-trio
                double-chevron orbit-doodle crown bandaged-skull
                """,
            )
        }

    @Serializable
    private data class Manifest(
        val version: Int,
        val entries: List<WallpaperCatalogEntry>,
    )

    private class Index(
        val entries: List<WallpaperCatalogEntry>,
    ) {
        val byId = entries.associateBy(WallpaperCatalogEntry::id)
    }

    @Volatile
    private var index: Index? = null

    fun entries(context: Context): List<WallpaperCatalogEntry> = index(context).entries

    fun entry(
        context: Context,
        assetId: String,
    ): WallpaperCatalogEntry? = index(context).byId[assetId]

    fun contains(assetId: String): Boolean = assetId in categories

    private fun index(context: Context): Index =
        index ?: synchronized(this) {
            index ?: load(context).also { index = it }
        }

    private fun load(context: Context): Index {
        val raw =
            context.assets
                .open(DIRECTORY + "manifest.json")
                .bufferedReader()
                .use { it.readText() }
        val manifest = Json.decodeFromString<Manifest>(raw)
        check(manifest.version == 1)
        // Check order as well as membership so section ordering cannot drift.
        check(manifest.entries.map { PREFIX + it.id to it.category } == categories.toList())
        val entries =
            manifest.entries.map { entry ->
                check(entry.svgPath == "${entry.id}.svg")
                entry.copy(
                    id = PREFIX + entry.id,
                    svgPath = DIRECTORY + entry.svgPath,
                    aliases = Collections.unmodifiableList(entry.aliases),
                )
            }
        return Index(Collections.unmodifiableList(entries))
    }
}
