package io.github.trevarj.motd.data.prefs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

class CustomPaletteTest {
    private val colors =
        PaletteColors(
            primary = "#006C70",
            secondary = "#8B4FA5",
            tertiary = "#B44C46",
            background = "#FFFFFF",
            surface = "#FFFFFF",
            surfaceContainer = "#F0F4F4",
            onBackground = "#171717",
            onSurface = "#171717",
            onSurfaceVariant = "#414949",
            outlineVariant = "#737B7B",
        )
    private val document =
        PaletteDocument(
            id = "example-palette",
            name = "Example palette",
            revision = 1,
            dark = colors,
            sourceUrl = "https://example.org/source?q=1#notice",
            license = "MIT",
            notices = "Preserved notice ©",
        )

    @Test fun darkOnlyAndPairedDocumentsRoundTripAllAuthoredValuesAndProvenance() {
        for (palette in listOf(document, document.copy(light = colors.copy(nicknameColors = listOf("#123ABC"))))) {
            assertEquals(palette, decodePalette(encodePalette(palette)))
            assertArrayEquals(encodePalette(palette), encodePalette(decodePalette(encodePalette(palette))))
        }
    }

    @Test fun encodingNormalizesColorsAndTrimsNameWithoutChangingAuthoredContrast() {
        val authored = document.copy(name = "  Gray  ", dark = colors.copy(primary = "#abcdef", onSurface = "#777777", surface = "#777777", nicknameColors = listOf("#abc123")))
        val decoded = decodePalette(encodePalette(authored))
        assertEquals("Gray", decoded.name)
        assertEquals("#ABCDEF", decoded.dark!!.primary)
        assertEquals(listOf("#ABC123"), decoded.dark.nicknameColors)
        assertEquals("#777777", decoded.dark.onSurface)
        assertEquals("#777777", decoded.dark.surface)
        assertEquals(authored.notices, decoded.notices)
    }

    @Test fun omittedOrEmptyNicknameColorsMeanAutomaticFallback() {
        val root = tree()
        val dark = JsonObject(root.getValue("dark").jsonObject - "nicknameColors")
        val decoded = decodePalette(bytes(JsonObject(root + ("dark" to dark))))
        assertTrue(decoded.dark!!.nicknameColors.isEmpty())
        assertEquals(decoded, decodePalette(encodePalette(decoded)))
    }

    @Test fun omittedOrNullUnavailableModesAreAcceptedButNoModesAreRejected() {
        val root = tree()
        assertEquals(document, decodePalette(bytes(JsonObject(root - "light"))))
        assertEquals(document, decodePalette(bytes(JsonObject(root + ("light" to JsonNull)))))
        invalid(JsonObject(root - "light" - "dark"))
        invalid(JsonObject(root + mapOf("light" to JsonNull, "dark" to JsonNull)))
        assertError(PaletteError.INVALID) { validatePalette(document, requireBothModes = true) }
        validatePalette(document.copy(light = colors), requireBothModes = true)
    }

    @Test fun everyPresentModeRequiresEveryRoleAndStringColorTypes() {
        val root = tree()
        val dark = root.getValue("dark").jsonObject
        for (role in dark.keys - "nicknameColors") {
            invalid(JsonObject(root + ("dark" to JsonObject(dark - role))))
            for (value in listOf(JsonPrimitive(123456), JsonPrimitive(false), JsonNull)) {
                invalid(JsonObject(root + ("dark" to JsonObject(dark + (role to value)))))
            }
        }
        for (value in listOf(JsonPrimitive(false), JsonPrimitive("mode"), JsonArray(emptyList()))) {
            invalid(JsonObject(root + ("dark" to value)))
        }
    }

    @Test fun fileColorsRejectAlphaShorthandMissingHashAndNonHex() {
        for (color in listOf("#00112233", "#abc", "123456", "#12GG00", " #123456", "#123456\n")) {
            assertError(PaletteError.INVALID) { encodePalette(document.copy(dark = colors.copy(primary = color))) }
        }
        for (nicknames in listOf(List(25) { "#123456" }, listOf("#abc"), listOf("#11223344"))) {
            assertError(PaletteError.INVALID) { validatePalette(document.copy(dark = colors.copy(nicknameColors = nicknames))) }
        }
        validatePalette(document.copy(dark = colors.copy(nicknameColors = List(24) { "#123456" })))
        val root = tree()
        val dark = root.getValue("dark").jsonObject
        for (value in listOf(JsonNull, JsonPrimitive("#123456"), JsonArray(listOf(JsonPrimitive(123456))))) {
            invalid(JsonObject(root + ("dark" to JsonObject(dark + ("nicknameColors" to value)))))
        }
    }

    @Test fun markerAndVersionAreRequiredAndStrictlyTyped() {
        val root = tree()
        invalid(JsonObject(root - "format"))
        invalid(JsonObject(root - "version"))
        for (value in listOf(JsonPrimitive(false), JsonPrimitive(1), JsonPrimitive("other"), JsonNull)) {
            invalid(JsonObject(root + ("format" to value)))
        }
        for (value in listOf(JsonPrimitive("1"), JsonPrimitive(false), JsonPrimitive(1.0), JsonNull)) {
            invalid(JsonObject(root + ("version" to value)))
        }
        for (version in listOf("0", "2", "-1", "99999999999999999999999999")) {
            assertError(PaletteError.UNSUPPORTED_VERSION) { decodePalette(raw().replace("\"version\":1", "\"version\":$version").toByteArray()) }
        }
        assertError(PaletteError.INVALID) { decodePalette(raw().replace("\"version\":1", "\"version\":1e0").toByteArray()) }
    }

    @Test fun revisionRequiresPositiveIntWithoutCoercion() {
        for (revision in listOf("true", "false", "1.0", "1e0", "\"1\"", "null", "0", "-1", "2147483648")) {
            assertError(PaletteError.INVALID) { decodePalette(raw().replace("\"revision\":1", "\"revision\":$revision").toByteArray()) }
        }
        invalid(JsonObject(tree() - "revision"))
        assertEquals(Int.MAX_VALUE, decodePalette(encodePalette(document.copy(revision = Int.MAX_VALUE))).revision)
    }

    @Test fun unknownKeysAreRejectedAtEverySchemaLevelIncludingInstallMetadata() {
        val root = tree()
        for (key in listOf("terminal", "catalogId", "catalogRevision", "sourceLink", "unknown")) {
            invalid(JsonObject(root + (key to JsonPrimitive("value"))))
        }
        invalid(JsonObject(root + ("dark" to JsonObject(root.getValue("dark").jsonObject + ("onPrimary" to JsonPrimitive("#123456"))))))
    }

    @Test fun namesAndIdsAreBoundedAndStrictlyTyped() {
        for (id in listOf("", "UPPER", "-first", "a_b", "a".repeat(65))) {
            assertError(PaletteError.INVALID) { validatePalette(document.copy(id = id)) }
        }
        for (name in listOf("", "   ", "x".repeat(65), "\nName", "Name\u007f", "Name\u0085")) {
            assertError(PaletteError.INVALID) { decodePalette(bytes(JsonObject(tree() + ("name" to JsonPrimitive(name))))) }
        }
        for (key in listOf("id", "name", "sourceUrl", "license", "notices")) {
            for (value in listOf(JsonPrimitive(123), JsonPrimitive(false))) invalid(JsonObject(tree() + (key to value)))
        }
        validatePalette(document.copy(id = "a".repeat(64), name = "x".repeat(64)))
        assertEquals("Trimmed", decodePalette(bytes(JsonObject(tree() + ("name" to JsonPrimitive("  Trimmed  "))))).name)
    }

    @Test fun provenanceLimitsAndHttpsRestrictionsApplyToDocumentsAndInstallations() {
        for (url in listOf("http://example.org", "https://user:password@example.org", "https://user@example.org", "https:///path", "https://example.org/\n", "https://example.org/" + "x".repeat(2048), "https://example.org../palette", "not a URL")) {
            assertError(PaletteError.INVALID) { validatePalette(document.copy(sourceUrl = url)) }
            assertError(PaletteError.INVALID) { validateInstalledPalette(InstalledPalette(document, sourceLink = url)) }
        }
        val trailingDot = "https://example.org./palette"
        assertEquals(trailingDot, decodePalette(encodePalette(document.copy(sourceUrl = trailingDot))).sourceUrl)
        val prefix = "https://example.org/"
        validatePalette(document.copy(sourceUrl = prefix + "x".repeat(2048 - prefix.length), license = "x".repeat(128), notices = "x".repeat(4096)))
        assertError(PaletteError.INVALID) { validatePalette(document.copy(license = "x".repeat(129))) }
        assertError(PaletteError.INVALID) { validatePalette(document.copy(notices = "x".repeat(4097))) }
        assertEquals(document.copy(sourceUrl = null, license = null, notices = null), decodePalette(encodePalette(document.copy(sourceUrl = null, license = null, notices = null))))
    }

    @Test fun malformedJsonNonUtf8AndOversizedInputHaveDistinctErrors() {
        for (raw in listOf("", "[]", "null", "{", "{\"format\":\"motd-palette\",}", "{} trailing")) {
            assertError(PaletteError.INVALID) { decodePalette(raw.toByteArray()) }
        }
        for (suffix in listOf(byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()))) {
            assertError(PaletteError.INVALID) { decodePalette(raw().toByteArray() + suffix) }
        }
        assertError(PaletteError.TOO_LARGE) { decodePalette(ByteArray(PALETTE_MAX_BYTES + 1)) }
        val encoded = encodePalette(document)
        assertEquals(document, decodePalette(encoded + ByteArray(PALETTE_MAX_BYTES - encoded.size) { 32 }))
    }

    @Test fun canonicalOutputSizeIsEnforcedDuringDecodeEvenWhenInputFits() {
        val base = document.copy(sourceUrl = null, license = null, notices = "")
        val overhead = encodePalette(base).size
        val count = (PALETTE_MAX_BYTES - overhead) / 6 + 1
        val root = Json.parseToJsonElement(encodePalette(base).toString(Charsets.UTF_8)).jsonObject
        val compact =
            JsonObject(
                (root - "light" - "sourceUrl" - "license") +
                    mapOf(
                        "notices" to JsonPrimitive("\u0001".repeat(count)),
                        "dark" to JsonObject(root.getValue("dark").jsonObject - "nicknameColors"),
                    ),
            )
        val input = bytes(compact)
        assertTrue(input.size <= PALETTE_MAX_BYTES)
        assertError(PaletteError.TOO_LARGE) { decodePalette(input) }
    }

    @Test fun addingModeNearLimitRejectsWithoutTruncatingNoticesOrMutatingOriginal() {
        val base = document.copy(notices = "")
        val count = (PALETTE_MAX_BYTES - encodePalette(base).size) / 6
        val original = base.copy(notices = "\u0001".repeat(count))
        val encoded = encodePalette(original)
        assertTrue(encoded.size <= PALETTE_MAX_BYTES)
        assertEquals(original, decodePalette(encoded))
        val edited = original.copy(light = colors)
        assertError(PaletteError.TOO_LARGE) { validatePalette(edited) }
        assertError(PaletteError.TOO_LARGE) { encodePalette(edited) }
        assertEquals(count, original.notices!!.length)
        assertArrayEquals(encoded, encodePalette(original))
        assertError(PaletteError.TOO_LARGE) { encodePalette(document.copy(notices = "\u0001".repeat(4096))) }
    }

    @Test fun boundedReaderIgnoresProviderSizeReadsCapPlusOneAndDoesNotCloseStream() {
        var consumed = 0
        var closed = false
        val input =
            object : InputStream() {
                override fun available() = 1

                override fun read(): Int {
                    consumed++
                    return 32
                }

                override fun close() {
                    closed = true
                }
            }
        assertError(PaletteError.TOO_LARGE) { readPaletteBytes(input) }
        assertEquals(PALETTE_MAX_BYTES + 1, consumed)
        assertFalse(closed)
        assertArrayEquals(ByteArray(PALETTE_MAX_BYTES), readPaletteBytes(ByteArrayInputStream(ByteArray(PALETTE_MAX_BYTES))))
        assertArrayEquals(byteArrayOf(), readPaletteBytes(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun readerHandlesZeroLengthReadsAndReportsReadFailuresWithoutSwallowingCancellation() {
        var zero = true
        val input =
            object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (zero) {
                        zero = false
                        return 0
                    }
                    return super.read(buffer, offset, length)
                }
            }
        assertArrayEquals(byteArrayOf(1, 2, 3), readPaletteBytes(input))
        val failure = IOException("revoked")
        val exception =
            assertThrows(PaletteException::class.java) {
                readPaletteBytes(
                    object : InputStream() {
                        override fun read(): Int = throw failure
                    },
                )
            }
        assertEquals(PaletteError.READ, exception.error)
        assertEquals(failure, exception.cause)
        val cancellation = CancellationException("cancelled")
        assertEquals(
            cancellation,
            assertThrows(CancellationException::class.java) {
                readPaletteBytes(
                    object : InputStream() {
                        override fun read(): Int = throw cancellation
                    },
                )
            },
        )
    }

    @Test fun installedTrackingRequiresPairBothModesAndMatchingPositiveRevision() {
        validateInstalledPalette(InstalledPalette(document, sourceLink = "https://example.org/import"))
        val tracked = InstalledPalette(document.copy(light = colors), catalogId = "upstream-id", catalogRevision = 1, sourceLink = "https://example.org/catalog")
        validateInstalledPalette(tracked)
        for (invalid in listOf(tracked.copy(catalogId = null), tracked.copy(catalogRevision = null), tracked.copy(catalogRevision = 0), tracked.copy(catalogRevision = 2), tracked.copy(catalogId = "Invalid"), tracked.copy(document = document))) {
            assertError(PaletteError.INVALID) { validateInstalledPalette(invalid) }
        }
        val root = Json.parseToJsonElement(encodePalette(tracked.document).toString(Charsets.UTF_8)).jsonObject
        assertFalse(root.containsKey("catalogId"))
        assertFalse(root.containsKey("catalogRevision"))
        assertFalse(root.containsKey("sourceLink"))
    }

    @Test fun sharedDuplicateKeyFixturesUseOnlyLastValuesConsistently() {
        val valid = decodePalette(fixture("duplicate-last-valid.motd-palette.json"))
        assertEquals("duplicate-fixture", valid.id)
        assertEquals("Duplicate fixture", valid.name)
        assertEquals(1, valid.revision)
        assertEquals("#006C70", valid.light!!.primary)
        assertEquals(listOf("#006C70", "#8B4FA5", "#B44C46"), valid.light.nicknameColors)
        assertEquals("https://example.org/palette", valid.sourceUrl)
        validatePalette(valid, requireBothModes = true)
        assertEquals(valid, decodePalette(encodePalette(valid)))
        assertError(PaletteError.INVALID) { decodePalette(fixture("duplicate-last-invalid.motd-palette.json")) }
    }

    @Test fun unicodeCharacterLimitsPreserveSupplementaryCharactersAndRejectUnpairedSurrogates() {
        val character = String(Character.toChars(0x1F600))
        val unicode = document.copy(name = character.repeat(64), license = character.repeat(128), notices = character.repeat(1024))
        assertEquals(unicode, decodePalette(encodePalette(unicode)))
        assertError(PaletteError.INVALID) { encodePalette(unicode.copy(name = character.repeat(65))) }
        assertError(PaletteError.INVALID) { encodePalette(unicode.copy(license = character.repeat(129))) }
        assertError(PaletteError.INVALID) { encodePalette(document.copy(notices = character.repeat(4097))) }
        for (surrogate in listOf("\uD800", "\uDC00", "before\uD800after")) {
            assertError(PaletteError.INVALID) { encodePalette(document.copy(notices = surrogate)) }
            assertError(PaletteError.INVALID) { encodePalette(document.copy(name = surrogate)) }
            assertError(PaletteError.INVALID) { encodePalette(document.copy(license = surrogate)) }
        }
        assertError(PaletteError.INVALID) { decodePalette(raw().replace("Preserved notice ©", "\\uD800").toByteArray()) }
    }

    private fun fixture(name: String) = requireNotNull(javaClass.getResourceAsStream("/palettes/$name")).use { it.readBytes() }

    private fun raw() = encodePalette(document).toString(Charsets.UTF_8)

    private fun tree() = Json.parseToJsonElement(raw()).jsonObject

    private fun bytes(tree: JsonElement) = tree.toString().toByteArray(Charsets.UTF_8)

    private fun invalid(tree: JsonElement) = assertError(PaletteError.INVALID) { decodePalette(bytes(tree)) }

    private fun assertError(
        error: PaletteError,
        action: () -> Unit,
    ) {
        assertEquals(error, assertThrows(PaletteException::class.java) { action() }.error)
    }
}
