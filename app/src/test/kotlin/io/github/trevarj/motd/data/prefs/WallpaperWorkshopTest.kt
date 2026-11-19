package io.github.trevarj.motd.data.prefs

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WallpaperWorkshopTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = AppearancePrefsImpl(context)
    private val store = WallpaperWorkshopStore(context, prefs, CustomWallpaperStore(context))
    private val backupJson = Json { ignoreUnknownKeys = true }

    @Test fun bundledCatalogHasDistinctValidGlyphsAndStarterRecipes() {
        val entries = WallpaperCatalog.entries(context)
        val minimums =
            mapOf(
                WallpaperCatalogCategory.MOTD to 32,
                WallpaperCatalogCategory.TECH to 26,
                WallpaperCatalogCategory.COMPUTER_GEAR to 12,
                WallpaperCatalogCategory.EMOJI_MEMES to 18,
                WallpaperCatalogCategory.GAMING to 22,
                WallpaperCatalogCategory.SPACE_RADIO to 14,
                WallpaperCatalogCategory.DOODLES to 16,
            )
        for ((category, minimum) in minimums) {
            assertTrue("${category.name} needs at least $minimum motifs", entries.count { it.category == category } >= minimum)
        }
        val ids = entries.map { it.id }.toSet()
        assertEquals(entries.size, ids.size)
        val drawings = HashSet<String>()
        for (entry in entries) {
            assertTrue(WallpaperCatalog.contains(entry.id))
            val bytes = context.assets.open(entry.svgPath).use { it.readBytes() }
            assertTrue("${entry.id} duplicates another drawing", drawings.add(bytes.decodeToString()))
            WallpaperSvg.decode(bytes)
        }
        val starters = WallpaperStarters.entries
        assertEquals("motd", starters.first().id)
        for (starter in starters) {
            validateWallpaperPattern(starter.pattern, emptySet())
            assertTrue(starter.pattern.icons.size in 8..MAX_WALLPAPER_ICONS)
            assertTrue(starter.pattern.icons.all { it.assetId in ids })
            assertTrue("${starter.id} must use Auto size and rotation", starter.pattern.icons.all { it.scale == null && it.rotation == null })
            assertEquals(if (starter.id == "motd") 0x4D4F5444 else starter.id.hashCode(), starter.pattern.seed)
        }
    }

    @Test fun oldRingRecipesAndExplicitAnglesRemainPortable() {
        val legacy =
            """{"designs":[{"id":"00000000-0000-4000-8000-000000000011","name":"Old design","pattern":{"layout":"RINGS","seed":42,"variation":30,"icons":[{"assetId":"builtin:rocket","scale":100,"rotation":0},{"assetId":"builtin:comet","scale":150,"rotation":-15}]}}]}"""
        val library = decodeWallpaperLibrary(legacy)
        val old = library.designs.single()
        assertEquals(WallpaperPatternLayout.SCATTERED, old.pattern.layout.effective)
        assertEquals(WallpaperTexture.NONE, old.pattern.texture)
        assertEquals(listOf(0, -15), old.pattern.icons.map { it.rotation })
        assertEquals(listOf(100, 150), old.pattern.icons.map { it.scale })
        assertFalse(old.pattern.icons.any { it.filler })
        val (rocket, comet) = old.pattern.icons

        val authored =
            old.copy(
                pattern =
                    old.pattern.copy(
                        size = 250,
                        texture = WallpaperTexture.LINEN,
                        icons = listOf(rocket.copy(scale = 225), comet.copy(scale = null, rotation = null, filler = true)),
                    ),
            )
        val modern = library.copy(designs = listOf(authored))
        val encoded = encodeWallpaperLibrary(modern)
        assertEquals(modern, decodeWallpaperLibrary(encoded))
        assertTrue(runCatching { decodeWallpaperLibrary(encoded.replace("\"filler\":true", "\"filler\":\"true\"")) }.isFailure)
        assertTrue(runCatching { decodeWallpaperLibrary(encoded.replace("\"LINEN\"", "\"unsupported\"")) }.isFailure)
    }

    @Test fun outlineImportIsPortableAndRejectsUnsupportedInput() =
        runTest {
            val first = importSvg("First motif", outline)
            val duplicate = importSvg("Second motif", outline)
            assertEquals(first.id, duplicate.id)
            assertEquals("First motif", first.name)
            val pattern = WallpaperPattern(seed = 37, icons = listOf(WallpaperPatternIcon(first.id)))
            val firstDesign = SavedWallpaperDesign(UUID.randomUUID().toString(), "Arc and line", pattern)
            store.saveDesign(firstDesign, null, listOf(first), applyGlobally = true)
            store.saveDesign(firstDesign.copy(id = UUID.randomUUID().toString(), name = "Another design"), null, listOf(duplicate))
            val current = prefs.config.first()
            val library = requireNotNull(current.wallpaperLibrary)
            assertEquals(listOf(first), library.imports)
            assertEquals(library, decodeWallpaperLibrary(encodeWallpaperLibrary(library)))
            assertEquals(listOf(first), resolveWallpaper(current).imports)
            val invalid =
                listOf(
                    outline.replace("fill=\"none\"", "fill=\"#FFFFFF\"") to WallpaperSvgError.NOT_OUTLINE,
                    outline.replace("<line ", "<line href=\"https://example.invalid/art.svg\" ") to WallpaperSvgError.UNSUPPORTED,
                    "<!DOCTYPE svg [<!ENTITY sample 'broken'>]>$outline" to WallpaperSvgError.UNSUPPORTED,
                    outline + " ".repeat(16_384) to WallpaperSvgError.TOO_LARGE,
                    outline.replace("M3 9", "M3e999 9") to WallpaperSvgError.UNSUPPORTED,
                )
            for ((svg, expected) in invalid) {
                val failure = runCatching { importSvg("Rejected", svg) }.exceptionOrNull() as WallpaperSvgException
                assertEquals(expected, failure.error)
            }
            val forged = first.copy(svg = outline.replace("M3 9", "M4 9"))
            val tampered = library.copy(imports = listOf(forged))
            assertTrue(runCatching { encodeWallpaperLibrary(tampered) }.isFailure)
            assertEquals(current, prefs.config.first())
        }

    @Test fun legacySelectionKeepsInkIntensityAndPhotoOrScenicFallback() =
        runTest {
            val ink = 0xff12ab34.toInt()
            val legacy =
                backupJson.decodeFromString<AppearanceConfig>(
                    """{"wallpaper":{"preset":"CHATTER","intensity":73,"outlineColor":$ink,"localImageName":"not-portable.image"}}""",
                )
            assertNull(legacy.wallpaperLibrary)
            assertNull(legacy.wallpaper.localImageName)
            val motd = requireNotNull(WallpaperStarters.find("motd")).pattern
            assertEquals(motd.copy(intensity = 73, ink = ink), resolveWallpaper(legacy).pattern)
            context.appearanceDataStore.edit { preferences ->
                preferences[stringPreferencesKey("wallpaper_preset_v1")] = "SYSTEMCRAFTERS"
                preferences[intPreferencesKey("wallpaper_intensity_v1")] = 73
                preferences[intPreferencesKey("wallpaper_outline_color_v1")] = ink
                preferences.remove(stringPreferencesKey("wallpaper_pattern_ref_v1"))
            }
            assertEquals(resolveWallpaper(legacy), resolveWallpaper(prefs.config.first()))
            val photo = legacy.copy(wallpaper = legacy.wallpaper.copy(localImageName = "owned.image"))
            val spec = resolveWallpaper(photo)
            assertEquals("owned.image", spec.selection.localImageName)
            assertEquals(73, spec.selection.intensity)
            assertEquals(motd.copy(intensity = 73, ink = ink), spec.pattern)
            val starterRef = WallpaperPatternRef(WallpaperPatternRefKind.STARTER, "motd")
            val linkedPhoto = resolveWallpaper(photo.copy(wallpaper = photo.wallpaper.copy(patternRef = starterRef)))
            assertEquals(motd, linkedPhoto.pattern)
            assertEquals(73, linkedPhoto.selection.intensity)
            val explicit = WallpaperChatChoice(WallpaperChatChoiceKind.PATTERN, starterRef)
            assertEquals(motd, resolveWallpaper(photo, explicit).pattern)
            assertNull(resolveWallpaper(photo, explicit).selection.localImageName)
            val none = WallpaperChatChoice(WallpaperChatChoiceKind.NONE)
            assertEquals(WallpaperRenderSpec(WallpaperSelection(ChatWallpaperPreset.NONE), null), resolveWallpaper(photo, none))
            for (preset in listOf(ChatWallpaperPreset.NONE, ChatWallpaperPreset.RAINY_CAFE)) {
                val selection = legacy.wallpaper.copy(preset = preset, scenicAnimationEnabled = false)
                assertEquals(WallpaperRenderSpec(selection, null), resolveWallpaper(legacy.copy(wallpaper = selection)))
            }
        }

    @Test fun savedDesignEditsUpdateLinksAndDeletionRepairsReferences() =
        runTest {
            val motd = requireNotNull(WallpaperStarters.find("motd")).pattern
            val original = SavedWallpaperDesign(UUID.randomUUID().toString(), "Own design", motd.copy(rotation = 40))
            store.saveDesign(original, null, emptyList(), applyGlobally = true)
            val ref = WallpaperPatternRef(WallpaperPatternRefKind.SAVED, original.id)
            var chat: WallpaperChatChoice? = WallpaperChatChoice(WallpaperChatChoiceKind.PATTERN, ref)
            val duplicate = original.copy(id = UUID.randomUUID().toString(), name = "Own design copy")
            store.saveDesign(duplicate, null, emptyList())
            val edited = original.copy(pattern = original.pattern.copy(rotation = 80))
            store.saveDesign(edited, original, emptyList())
            val current = prefs.config.first()
            assertEquals(edited.pattern, resolveWallpaper(current).pattern)
            assertEquals(edited.pattern, resolveWallpaper(current, chat).pattern)
            assertEquals(
                duplicate.pattern,
                current.wallpaperLibrary
                    ?.designs
                    ?.single { it.id == duplicate.id }
                    ?.pattern,
            )
            assertTrue(runCatching { store.saveDesign(original, original, emptyList()) }.isFailure)
            assertEquals(current, prefs.config.first())
            store.deleteDesign(edited) { deleted -> if (chat?.pattern == deleted) chat = null }
            val fallback = prefs.config.first()
            assertNull(fallback.wallpaper.patternRef)
            assertNull(chat)
            assertEquals(motd, resolveWallpaper(fallback).pattern)
            assertEquals(resolveWallpaper(fallback), resolveWallpaper(fallback, WallpaperChatChoice(WallpaperChatChoiceKind.PATTERN, ref)))
            assertEquals(motd, resolveWallpaper(fallback.copy(wallpaper = fallback.wallpaper.copy(patternRef = ref, intensity = 0))).pattern)
            assertFalse(
                fallback.wallpaperLibrary
                    ?.designs
                    .orEmpty()
                    .any { it.id == ref.id },
            )
            assertNotEquals(original.pattern, resolveWallpaper(fallback).pattern)
        }

    private suspend fun importSvg(
        name: String,
        document: String,
    ): ImportedWallpaperIcon {
        val file = File(context.cacheDir, "$name.svg").apply { writeText(document) }
        try {
            return WallpaperSvg.import(context, Uri.fromFile(file))
        } finally {
            file.delete()
        }
    }

    private companion object {
        val outline =
            """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
              <g transform="rotate(15 12 12)">
                <path d="M3 9 A7 7 0 0 1 17 9"/>
                <line x1="6" y1="13" x2="17" y2="18"/>
              </g>
            </svg>
            """.trimIndent()
    }
}
