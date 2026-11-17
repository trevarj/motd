package io.github.trevarj.motd.data.prefs

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

const val PALETTE_MAX_BYTES = 16 * 1024
const val PALETTE_MAX_URL_CHARACTERS = 2048

@Serializable
data class PaletteDocument(
    val id: String,
    val name: String,
    val revision: Int,
    val light: PaletteColors? = null,
    val dark: PaletteColors? = null,
    val sourceUrl: String? = null,
    val license: String? = null,
    val notices: String? = null,
    val format: String = "motd-palette",
    val version: Int = 1,
)

@Serializable
data class PaletteColors(
    val primary: String,
    val secondary: String,
    val tertiary: String,
    val background: String,
    val surface: String,
    val surfaceContainer: String,
    val onBackground: String,
    val onSurface: String,
    val onSurfaceVariant: String,
    val outlineVariant: String,
    val nicknameColors: List<String> = emptyList(),
)

@Serializable
data class InstalledPalette(
    val document: PaletteDocument,
    val catalogId: String? = null,
    val catalogRevision: Int? = null,
    val sourceLink: String? = null,
)

@Serializable
enum class PaletteMode { SYSTEM, LIGHT, DARK }

@Serializable
data class PaletteChoice(
    val builtin: ColorThemePreset = ColorThemePreset.SYSTEM,
    val installedId: String? = null,
)

@Serializable
data class PaletteSelection(
    val light: PaletteChoice = PaletteChoice(),
    val dark: PaletteChoice = PaletteChoice(),
    val mode: PaletteMode = PaletteMode.SYSTEM,
) {
    fun choiceFor(dark: Boolean): PaletteChoice = if (dark) this.dark else light

    fun usesInstalled(id: String): Boolean = light.installedId == id || dark.installedId == id

    fun withChoice(
        choice: PaletteChoice,
        mode: PaletteMode,
        palettes: List<InstalledPalette> = emptyList(),
    ): PaletteSelection {
        require(choice.supportsMode(false, palettes) || choice.supportsMode(true, palettes)) { "Selected palette is unavailable" }
        if (mode != PaletteMode.SYSTEM) {
            require(choice.supportsMode(mode == PaletteMode.DARK, palettes)) { "Palette does not support selected mode" }
        }
        return copy(
            light = if (mode != PaletteMode.DARK && choice.supportsMode(false, palettes)) choice else light,
            dark = if (mode != PaletteMode.LIGHT && choice.supportsMode(true, palettes)) choice else dark,
        )
    }
}

enum class PaletteError { READ, TOO_LARGE, INVALID, UNSUPPORTED_VERSION }

class PaletteException(
    val error: PaletteError,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

private val paletteJson = Json { encodeDefaults = true }
private val paletteId = Regex("[a-z0-9][a-z0-9-]{0,63}")
private val paletteColor = Regex("#[0-9A-Fa-f]{6}")
private val integerToken = Regex("-?(0|[1-9][0-9]*)")
private val roleNames =
    listOf(
        "primary",
        "secondary",
        "tertiary",
        "background",
        "surface",
        "surfaceContainer",
        "onBackground",
        "onSurface",
        "onSurfaceVariant",
        "outlineVariant",
    )

fun decodePalette(bytes: ByteArray): PaletteDocument = decodePaletteTree(parsePaletteJson(bytes))

internal fun parsePaletteJson(
    bytes: ByteArray,
    maxBytes: Int = PALETTE_MAX_BYTES,
): JsonElement {
    if (bytes.size > maxBytes) throw PaletteException(PaletteError.TOO_LARGE, "Palette exceeds byte limit")
    val text =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw PaletteException(PaletteError.INVALID, "Palette is not UTF-8", error)
        }
    return try {
        paletteJson.parseToJsonElement(text)
    } catch (error: SerializationException) {
        throw PaletteException(PaletteError.INVALID, "Invalid palette JSON", error)
    }
}

internal fun decodePaletteTree(tree: JsonElement): PaletteDocument {
    val document =
        try {
            validatePaletteTree(tree)
            paletteJson.decodeFromJsonElement<PaletteDocument>(tree)
        } catch (error: SerializationException) {
            throw PaletteException(PaletteError.INVALID, "Invalid palette JSON", error)
        }
    validatePalette(document)
    return document.copy(name = document.name.trim())
}

fun encodePalette(palette: PaletteDocument): ByteArray {
    validatePaletteFields(palette, requireBothModes = false)
    return checkedCanonicalPaletteBytes(palette)
}

fun validatePalette(
    palette: PaletteDocument,
    requireBothModes: Boolean = false,
) {
    validatePaletteFields(palette, requireBothModes)
    checkedCanonicalPaletteBytes(palette)
}

private fun validatePaletteFields(
    palette: PaletteDocument,
    requireBothModes: Boolean,
) {
    paletteRequire(palette.format == "motd-palette", "Not a MOTD palette")
    if (palette.version != 1) throw PaletteException(PaletteError.UNSUPPORTED_VERSION, "Unsupported palette version")
    paletteRequire(paletteId.matches(palette.id), "Invalid palette ID")
    paletteRequire(palette.name.none(Char::isISOControl), "Palette name contains control characters")
    val name = palette.name.trim()
    paletteRequire(name.isNotEmpty() && name.paletteCharacterCount() <= 64, "Palette name must contain 1–64 characters")
    paletteRequire(palette.revision > 0, "Palette revision must be positive")
    paletteRequire(palette.light != null || palette.dark != null, "Palette needs at least one mode")
    paletteRequire(!requireBothModes || (palette.light != null && palette.dark != null), "Palette needs both modes")
    for (colors in listOfNotNull(palette.light, palette.dark)) {
        paletteRequire(colors.roles().all(paletteColor::matches), "Invalid palette color")
        paletteRequire(colors.nicknameColors.size <= 24, "Too many nickname colors")
        paletteRequire(colors.nicknameColors.all(paletteColor::matches), "Invalid nickname color")
    }
    palette.sourceUrl?.let(::validatePaletteHttpsUrl)
    palette.license?.let { paletteRequire(it.paletteCharacterCount() <= 128, "Palette license is too long") }
    palette.notices?.let { paletteRequire(it.paletteCharacterCount() <= 4096, "Palette notices are too long") }
}

fun validateInstalledPalette(palette: InstalledPalette) {
    paletteRequire((palette.catalogId == null) == (palette.catalogRevision == null), "Incomplete catalog tracking")
    palette.catalogId?.let {
        paletteRequire(paletteId.matches(it), "Invalid catalog ID")
        paletteRequire(palette.catalogRevision == palette.document.revision, "Catalog revision does not match document")
    }
    validatePalette(palette.document, requireBothModes = palette.catalogId != null)
    palette.sourceLink?.let(::validatePaletteHttpsUrl)
}

private fun checkedCanonicalPaletteBytes(palette: PaletteDocument): ByteArray {
    val bytes = canonicalPaletteBytes(palette)
    checkPaletteSize(bytes.size)
    return bytes
}

fun readPaletteBytes(
    input: InputStream,
    maxBytes: Int = PALETTE_MAX_BYTES,
): ByteArray {
    require(maxBytes in 1 until Int.MAX_VALUE)
    try {
        val output = ByteArrayOutputStream(minOf(maxBytes + 1, 8192))
        val buffer = ByteArray(minOf(maxBytes + 1, 8192))
        while (output.size() <= maxBytes) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
            if (count < 0) break
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) break
                output.write(byte)
            } else {
                output.write(buffer, 0, count)
            }
        }
        if (output.size() > maxBytes) throw PaletteException(PaletteError.TOO_LARGE, "Palette exceeds byte limit")
        return output.toByteArray()
    } catch (error: IOException) {
        throw PaletteException(PaletteError.READ, "Could not read palette", error)
    }
}

private fun validatePaletteTree(element: JsonElement) {
    val root = element as? JsonObject ?: throw PaletteException(PaletteError.INVALID, "Palette must be an object")
    paletteRequire(root["format"].isJsonString() && (root["format"] as JsonPrimitive).content == "motd-palette", "Not a MOTD palette")
    val version = root["version"] as? JsonPrimitive
    paletteRequire(version != null && !version.isString && integerToken.matches(version.content), "Invalid palette version")
    if (version!!.content != "1") throw PaletteException(PaletteError.UNSUPPORTED_VERSION, "Unsupported palette version")
    val revision = root["revision"] as? JsonPrimitive
    paletteRequire(revision != null && !revision.isString && integerToken.matches(revision.content), "Invalid palette revision")
    for (key in listOf("id", "name")) paletteRequire(root[key].isJsonString(), "Invalid $key")
    for (key in listOf("sourceUrl", "license", "notices")) {
        paletteRequire(root[key] == null || root[key] == JsonNull || root[key].isJsonString(), "Invalid $key")
    }
    for (mode in listOf("light", "dark")) {
        val value = root[mode]
        if (value == null || value == JsonNull) continue
        val colors = value as? JsonObject ?: throw PaletteException(PaletteError.INVALID, "Invalid $mode mode")
        for (role in roleNames) paletteRequire(colors[role].isJsonString(), "Missing or invalid $role")
        colors["nicknameColors"]?.let { nicknames ->
            paletteRequire(nicknames is kotlinx.serialization.json.JsonArray && nicknames.all { it.isJsonString() }, "Invalid nickname colors")
        }
    }
}

private fun JsonElement?.isJsonString() = this is JsonPrimitive && isString

private fun PaletteColors.roles() = listOf(primary, secondary, tertiary, background, surface, surfaceContainer, onBackground, onSurface, onSurfaceVariant, outlineVariant)

private fun PaletteColors.canonical() =
    copy(
        primary = primary.uppercase(Locale.ROOT),
        secondary = secondary.uppercase(Locale.ROOT),
        tertiary = tertiary.uppercase(Locale.ROOT),
        background = background.uppercase(Locale.ROOT),
        surface = surface.uppercase(Locale.ROOT),
        surfaceContainer = surfaceContainer.uppercase(Locale.ROOT),
        onBackground = onBackground.uppercase(Locale.ROOT),
        onSurface = onSurface.uppercase(Locale.ROOT),
        onSurfaceVariant = onSurfaceVariant.uppercase(Locale.ROOT),
        outlineVariant = outlineVariant.uppercase(Locale.ROOT),
        nicknameColors = nicknameColors.map { it.uppercase(Locale.ROOT) },
    )

private fun canonicalPaletteBytes(palette: PaletteDocument) =
    paletteJson
        .encodeToString(
            palette.copy(name = palette.name.trim(), light = palette.light?.canonical(), dark = palette.dark?.canonical()),
        ).toByteArray(Charsets.UTF_8)

private fun validatePaletteHttpsUrl(value: String) {
    paletteRequire(value.paletteCharacterCount() <= PALETTE_MAX_URL_CHARACTERS && value.none(Char::isISOControl), "Invalid palette HTTPS URL")
    val uri =
        try {
            URI(value)
        } catch (_: java.net.URISyntaxException) {
            throw PaletteException(PaletteError.INVALID, "Invalid palette HTTPS URL")
        }
    paletteRequire(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null, "Invalid palette HTTPS URL")
}

private fun String.paletteCharacterCount(): Int {
    var index = 0
    var count = 0
    while (index < length) {
        val character = this[index++]
        if (character.isHighSurrogate()) {
            paletteRequire(index < length && this[index].isLowSurrogate(), "Invalid palette Unicode")
            index++
        } else {
            paletteRequire(!character.isLowSurrogate(), "Invalid palette Unicode")
        }
        count++
    }
    return count
}

private fun checkPaletteSize(size: Int) {
    if (size > PALETTE_MAX_BYTES) throw PaletteException(PaletteError.TOO_LARGE, "Palette exceeds 16 KiB")
}

private fun paletteRequire(
    condition: Boolean,
    message: String,
) {
    if (!condition) throw PaletteException(PaletteError.INVALID, message)
}
