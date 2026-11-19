package io.github.trevarj.motd.data.prefs

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import java.util.UUID

@Serializable
enum class WallpaperPatternRefKind { STARTER, SAVED }

@Serializable
data class WallpaperPatternRef(
    val kind: WallpaperPatternRefKind,
    val id: String,
)

@Serializable
enum class WallpaperPatternLayout { SCATTERED, ROWS, DIAGONAL, WAVES, RINGS, KALEIDOSCOPE }

internal val WallpaperPatternLayout.effective: WallpaperPatternLayout
    get() = if (this == WallpaperPatternLayout.RINGS) WallpaperPatternLayout.SCATTERED else this

@Serializable
data class WallpaperPatternIcon(
    val assetId: String,
    val scale: Int? = null,
    val rotation: Int? = null,
    val weight: Int = 3,
    val ink: Int? = null,
    val filler: Boolean = false,
)

@Serializable
enum class WallpaperTexture { NONE, SPECKS, COMIC_PAPER, GRAIN, LINEN, SCANLINE, STARS }

@Serializable
data class WallpaperPattern(
    val layout: WallpaperPatternLayout = WallpaperPatternLayout.SCATTERED,
    val seed: Int,
    val size: Int = 100,
    val density: Int = 70,
    val rotation: Int = 0,
    val variation: Int = 180,
    val intensity: Int = DEFAULT_WALLPAPER_INTENSITY,
    val background: Int? = null,
    val ink: Int? = null,
    val icons: List<WallpaperPatternIcon>,
    val texture: WallpaperTexture = WallpaperTexture.NONE,
)

@Serializable
data class SavedWallpaperDesign(
    val id: String,
    val name: String,
    val pattern: WallpaperPattern,
)

@Serializable
data class ImportedWallpaperIcon(
    val id: String,
    val name: String,
    val svg: String,
)

@Serializable
data class WallpaperLibrary(
    val version: Int = 1,
    val designs: List<SavedWallpaperDesign> = emptyList(),
    val imports: List<ImportedWallpaperIcon> = emptyList(),
)

@Serializable
enum class WallpaperChatChoiceKind { NONE, PATTERN }

@Serializable
data class WallpaperChatChoice(
    val kind: WallpaperChatChoiceKind,
    val pattern: WallpaperPatternRef? = null,
) {
    init {
        require((kind == WallpaperChatChoiceKind.PATTERN) == (pattern != null))
    }
}

data class WallpaperRenderSpec(
    val selection: WallpaperSelection,
    val pattern: WallpaperPattern?,
    val imports: List<ImportedWallpaperIcon> = emptyList(),
)

enum class WallpaperLibraryReadError { INVALID, TOO_LARGE }

internal class WallpaperLibraryException(
    val error: WallpaperLibraryReadError,
    cause: Throwable? = null,
) : IllegalArgumentException(error.name, cause)

internal const val MAX_WALLPAPER_DESIGNS = 64
internal const val MAX_WALLPAPER_IMPORTS = 128
internal const val MAX_WALLPAPER_LIBRARY_BYTES = 512 * 1024
internal const val MAX_WALLPAPER_ICONS = 24
internal const val MIN_WALLPAPER_SCALE = 25
internal const val MAX_WALLPAPER_SCALE = 250
internal val wallpaperJson = Json { encodeDefaults = true }

internal fun isCanonicalWallpaperUuid(id: String): Boolean = id.length == 36 && runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)

internal fun isWallpaperName(name: String): Boolean = name.length in 1..64 && name == name.trim() && name.none(Char::isISOControl)

internal fun WallpaperLibrary.withReferencedImports(): WallpaperLibrary {
    val referenced = designs.flatMapTo(HashSet()) { design -> design.pattern.icons.map(WallpaperPatternIcon::assetId) }
    return copy(imports = imports.filter { it.id in referenced })
}

internal fun validateWallpaperPattern(
    pattern: WallpaperPattern,
    available: Set<String>,
    catalog: WallpaperCatalog = WallpaperCatalog,
) {
    require(pattern.size in MIN_WALLPAPER_SCALE..MAX_WALLPAPER_SCALE && pattern.density in 0..100 && pattern.intensity in 0..100)
    require(pattern.rotation in -180..180 && pattern.variation in 0..180)
    require(pattern.background.isOpaqueWallpaperColor() && pattern.ink.isOpaqueWallpaperColor())
    require(pattern.icons.size in 1..MAX_WALLPAPER_ICONS)
    val ids = HashSet<String>(pattern.icons.size)
    for (icon in pattern.icons) {
        require(ids.add(icon.assetId) && (catalog.contains(icon.assetId) || icon.assetId in available))
        require(icon.scale == null || icon.scale in MIN_WALLPAPER_SCALE..MAX_WALLPAPER_SCALE)
        require(icon.rotation == null || icon.rotation in -180..180)
        require(icon.weight in 1..5 && icon.ink.isOpaqueWallpaperColor())
    }
}

private fun Int?.isOpaqueWallpaperColor(): Boolean = this == null || this ushr 24 == 255

internal fun encodeWallpaperLibrary(
    library: WallpaperLibrary,
    catalog: WallpaperCatalog = WallpaperCatalog,
): String {
    if (library.designs.size > MAX_WALLPAPER_DESIGNS || library.imports.size > MAX_WALLPAPER_IMPORTS) tooLargeWallpaperLibrary()
    val encoded = wallpaperJson.encodeToString(library)
    if (encoded.length > MAX_WALLPAPER_LIBRARY_BYTES || encoded.toByteArray().size > MAX_WALLPAPER_LIBRARY_BYTES) tooLargeWallpaperLibrary()
    try {
        require(library.version == 1)
        val importedIds = HashSet<String>(library.imports.size)
        for (icon in library.imports) {
            require(importedIds.add(icon.id) && isWallpaperName(icon.name))
        }
        val designIds = HashSet<String>(library.designs.size)
        val referencedImports = HashSet<String>()
        for (design in library.designs) {
            require(isCanonicalWallpaperUuid(design.id) && designIds.add(design.id) && isWallpaperName(design.name))
            validateWallpaperPattern(design.pattern, importedIds, catalog)
            for (icon in design.pattern.icons) if (icon.assetId in importedIds) referencedImports.add(icon.assetId)
        }
        require(importedIds == referencedImports)
        library.imports.forEach { WallpaperSvg.validate(it) }
    } catch (error: IllegalArgumentException) {
        throw WallpaperLibraryException(WallpaperLibraryReadError.INVALID, error)
    }
    return encoded
}

internal fun decodeWallpaperLibrary(
    raw: String?,
    catalog: WallpaperCatalog = WallpaperCatalog,
): WallpaperLibrary {
    if (raw == null) return WallpaperLibrary()
    if (raw.length > MAX_WALLPAPER_LIBRARY_BYTES || raw.toByteArray().size > MAX_WALLPAPER_LIBRARY_BYTES) tooLargeWallpaperLibrary()
    val library =
        try {
            decodeWallpaperValue<WallpaperLibrary>(wallpaperJson.parseToJsonElement(raw))
        } catch (error: IllegalArgumentException) {
            throw WallpaperLibraryException(WallpaperLibraryReadError.INVALID, error)
        }
    encodeWallpaperLibrary(library, catalog)
    return library
}

private fun tooLargeWallpaperLibrary(): Nothing = throw WallpaperLibraryException(WallpaperLibraryReadError.TOO_LARGE)

internal inline fun <reified T> decodeWallpaperValue(raw: JsonElement): T =
    wallpaperJson.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), raw).also {
        require(matchesWallpaperValue(raw, wallpaperJson.encodeToJsonElement(it)))
    }

internal fun matchesWallpaperValue(
    raw: JsonElement,
    canonical: JsonElement,
): Boolean =
    when (raw) {
        is JsonObject -> canonical is JsonObject && raw.all { (key, value) -> canonical[key]?.let { matchesWallpaperValue(value, it) } == true }
        is JsonArray -> canonical is JsonArray && raw.size == canonical.size && raw.indices.all { matchesWallpaperValue(raw[it], canonical[it]) }
        else -> raw == canonical
    }

internal fun WallpaperPatternRef.resolvePattern(library: WallpaperLibrary?): WallpaperPattern? =
    when (kind) {
        WallpaperPatternRefKind.STARTER -> WallpaperStarters.find(id)?.pattern
        WallpaperPatternRefKind.SAVED -> library?.designs?.firstOrNull { it.id == id }?.pattern
    }

internal fun WallpaperPatternRef.isValidIn(library: WallpaperLibrary?): Boolean = resolvePattern(library) != null

internal fun decodeWallpaperChatChoice(encoded: String?): WallpaperChatChoice? =
    encoded?.takeIf { it.length <= 256 }?.let {
        runCatching { decodeWallpaperValue<WallpaperChatChoice>(wallpaperJson.parseToJsonElement(it)) }.getOrNull()
    }

fun resolveWallpaper(
    appearance: AppearanceConfig,
    override: WallpaperChatChoice? = null,
): WallpaperRenderSpec {
    val library = appearance.wallpaperLibrary.takeIf { appearance.wallpaperLibraryReadError == null }
    val explicit = override?.pattern
    if (override?.kind == WallpaperChatChoiceKind.NONE) return WallpaperRenderSpec(WallpaperSelection(ChatWallpaperPreset.NONE), null)
    if (explicit != null) {
        explicit.resolvePattern(library)?.let { pattern ->
            return WallpaperRenderSpec(WallpaperSelection(ChatWallpaperPreset.PATTERN, patternRef = explicit), pattern, pattern.importsFrom(library))
        }
    }
    val selection = appearance.wallpaper
    if (selection.preset != ChatWallpaperPreset.PATTERN) return WallpaperRenderSpec(selection, null)
    val base = checkNotNull(WallpaperStarters.find("motd")).pattern
    val ref = selection.patternRef
    if (ref == null) return WallpaperRenderSpec(selection, base.copy(intensity = selection.intensity, ink = selection.outlineColor))
    val pattern = ref.resolvePattern(library) ?: return WallpaperRenderSpec(selection.copy(patternRef = null), base)
    return WallpaperRenderSpec(selection, pattern, pattern.importsFrom(library))
}

private fun WallpaperPattern.importsFrom(library: WallpaperLibrary?): List<ImportedWallpaperIcon> = library?.imports?.filter { imported -> icons.any { it.assetId == imported.id } }.orEmpty()
