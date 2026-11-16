package io.github.trevarj.motd.ui.settings

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import io.github.trevarj.motd.data.prefs.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PaletteFilesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val uri = Uri.parse("content://palette-files/document")
    private lateinit var file: File
    private lateinit var activity: Activity
    private lateinit var provider: PaletteStreamProvider
    private val palette get() = decodePalette(requireNotNull(javaClass.getResourceAsStream("/palettes/duplicate-last-valid.motd-palette.json")).use { it.readBytes() })

    @Before fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        file = File.createTempFile("palette-stream-", ".json", context.cacheDir)
        provider = PaletteStreamProvider(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = uri.authority })
        ShadowContentResolver.registerProviderInternal(requireNotNull(uri.authority), provider)
        val authority = "${context.packageName}.camera"
        val info = requireNotNull(context.packageManager.resolveContentProvider(authority, PackageManager.GET_META_DATA))
        ShadowContentResolver.registerProviderInternal(authority, FileProvider().also { it.attachInfo(context, info) })
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun exportAndImportPreserveCanonicalAuthoredColorsAndMetadata() =
        runTest {
            val authored = palette.copy(light = palette.light!!.copy(primary = "#aaaaaa", onSurface = "#aaaaaa"))
            writePaletteFile(context, uri, authored)
            assertArrayEquals(encodePalette(authored), file.readBytes())
            val imported = readPaletteFile(context, uri)
            assertEquals("#AAAAAA", imported.light!!.primary)
            assertEquals("#AAAAAA", imported.light!!.onSurface)
            assertEquals(authored.notices, imported.notices)
            assertEquals(authored.license, imported.license)
            assertEquals(authored.sourceUrl, imported.sourceUrl)
            assertArrayEquals(encodePalette(authored), encodePalette(imported))
            assertFalse(provider.openedOnMain)
        }

    @Test fun validationHappensBeforeOpeningOrTruncatingDestination() =
        runTest {
            file.writeText("retained destination")
            expectError(PaletteError.INVALID) { writePaletteFile(context, uri, palette.copy(id = "../escape")) }
            assertEquals("retained destination", file.readText())
            assertEquals(0, provider.opens)
        }

    @Test fun nullAndRevokedSourcesReportReadError() =
        runTest {
            provider.missing = true
            expectError(PaletteError.READ) { readPaletteFile(context, uri) }
            provider.missing = false
            provider.revoked = true
            expectError(PaletteError.READ) { readPaletteFile(context, uri) }
        }

    @Test fun actualBytesRejectOversizeDespiteLyingProviderLength() =
        runTest {
            file.writeBytes(ByteArray(PALETTE_MAX_BYTES + 1) { ' '.code.toByte() })
            expectError(PaletteError.TOO_LARGE) { readPaletteFile(context, uri) }
            assertFalse(provider.openedOnMain)
        }

    @Test fun repeatedSharingRetainsIndependentReadableGrantedSnapshots() =
        runTest {
            val first = palette
            sharePaletteFile(activity, first)
            val firstSend = nextShare()
            val firstUri = firstSend.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            val second = first.copy(name = "Changed name", notices = "Changed notice")
            sharePaletteFile(activity, second)
            val secondSend = nextShare()
            val secondUri = secondSend.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            assertNotEquals(firstUri, secondUri)
            for ((send, stream, document) in listOf(Triple(firstSend, firstUri, first), Triple(secondSend, secondUri, second))) {
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("application/json", send.type)
                assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals(stream, send.clipData!!.getItemAt(0).uri)
                assertArrayEquals(encodePalette(document), context.contentResolver.openInputStream(stream)!!.use { it.readBytes() })
                context.contentResolver.query(stream, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!.use {
                    assertTrue(it.moveToFirst())
                    assertEquals("${document.id}.motd-palette.json", it.getString(0))
                }
            }
        }

    private fun nextShare(): Intent {
        val chooser = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        return requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT))
    }

    private suspend fun expectError(
        expected: PaletteError,
        action: suspend () -> Unit,
    ) {
        try {
            action()
            fail("Expected $expected")
        } catch (failure: PaletteException) {
            assertEquals(expected, failure.error)
        }
    }
}

private class PaletteStreamProvider(
    private val file: File,
) : ContentProvider() {
    var missing = false
    var revoked = false
    var opens = 0
    var openedOnMain = false

    override fun onCreate() = true

    override fun getType(uri: Uri) = "application/json"

    override fun openAssetFile(
        uri: Uri,
        mode: String,
    ): AssetFileDescriptor? {
        if (missing) return null
        if (revoked) throw SecurityException("Grant revoked")
        return AssetFileDescriptor(openFile(uri, mode), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        opens++
        openedOnMain = openedOnMain || Looper.myLooper() == Looper.getMainLooper()
        if (revoked) throw FileNotFoundException("Grant revoked")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>("document.json", 1L)) }

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ) = 0
}
