package io.github.trevarj.motd.dcc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import io.github.trevarj.motd.data.db.DccAddressKind
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferProtocol
import io.github.trevarj.motd.data.db.DccTransferState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DccDownloadsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var source: File
    private lateinit var provider: DownloadProvider

    @Before
    fun setUp() {
        source = File(requireNotNull(EbooksResultCache(context).uriFor(901).path))
        ZipOutputStream(source.outputStream()).use {
            it.putNextEntry(ZipEntry("unsupported-results.txt"))
            it.write("not a parsed book result".toByteArray())
            it.closeEntry()
        }
        provider = DownloadProvider(source, context.cacheDir)
        provider.attachInfo(context, ProviderInfo().apply { authority = MediaStore.AUTHORITY })
        ShadowContentResolver.registerProviderInternal(MediaStore.AUTHORITY, provider)
    }

    @Test
    fun `private results ZIP exports original bytes and display filename before publication`() =
        runTest {
            val original = source.readBytes()
            val destination = saveDccToDownloads(context, transfer())

            assertEquals(MediaStore.Downloads.EXTERNAL_CONTENT_URI, provider.collection)
            assertEquals("Search results.zip", provider.insertedValues!!.getAsString(MediaStore.Downloads.DISPLAY_NAME))
            assertEquals("application/zip", provider.insertedValues!!.getAsString(MediaStore.Downloads.MIME_TYPE))
            assertEquals(Environment.DIRECTORY_DOWNLOADS, provider.insertedValues!!.getAsString(MediaStore.Downloads.RELATIVE_PATH))
            assertEquals(1, provider.insertedValues!!.getAsInteger(MediaStore.Downloads.IS_PENDING))
            assertArrayEquals(original, provider.publishedBytes)
            assertArrayEquals(original, context.contentResolver.openInputStream(destination)!!.use { it.readBytes() })
            assertArrayEquals(original, source.readBytes())
            assertEquals(0, provider.deletions)
        }

    @Test
    fun `content URI destination can also be copied without changing the retained document`() =
        runTest {
            saveDccToDownloads(context, transfer().copy(destinationUri = "content://media/retained/source"))
            assertArrayEquals(source.readBytes(), provider.publishedBytes)
            assertTrue(source.exists())
        }

    @Test
    fun `write and publication failures discard only the pending download`() =
        runTest {
            val original = source.readBytes()
            provider.failOutput = true
            expectFailure { saveDccToDownloads(context, transfer()) }
            assertEquals(1, provider.deletions)
            assertTrue(provider.files.isEmpty())
            assertArrayEquals(original, source.readBytes())

            provider.failOutput = false
            provider.publishResult = 0
            expectFailure { saveDccToDownloads(context, transfer()) }
            assertEquals(2, provider.deletions)
            assertTrue(provider.files.isEmpty())
            assertArrayEquals(original, source.readBytes())
        }

    @Test
    fun `mid copy read failure closes the source and deletes the partial download`() =
        runTest {
            val original = source.readBytes()
            val retainedUri = Uri.parse("content://media/retained/failing")
            var closed = false
            val input =
                object : InputStream() {
                    var reads = 0

                    override fun read(): Int = throw UnsupportedOperationException()

                    override fun read(
                        buffer: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (reads++ > 0) throw IOException("retained file read failed")
                        buffer[offset] = 80
                        return 1
                    }

                    override fun close() {
                        closed = true
                    }
                }
            shadowOf(context.contentResolver).registerInputStream(retainedUri, input)
            expectFailure { saveDccToDownloads(context, transfer().copy(destinationUri = retainedUri.toString())) }
            assertTrue(closed)
            assertEquals(1, provider.deletions)
            assertTrue(provider.files.isEmpty())
            assertEquals(null, provider.publishedBytes)
            assertArrayEquals(original, source.readBytes())
        }

    @Test
    fun `missing retained file fails without leaving a download`() =
        runTest {
            assertTrue(source.delete())
            expectFailure { saveDccToDownloads(context, transfer()) }
            assertEquals(1, provider.deletions)
            assertTrue(provider.files.isEmpty())
        }

    @Test
    fun `cancellation deletes the unpublished destination and preserves the source`() =
        runTest {
            val original = source.readBytes()
            val export = launch(start = CoroutineStart.LAZY) { saveDccToDownloads(context, transfer()) }
            provider.onOutputOpened = { export.cancel() }
            export.start()
            export.join()
            assertTrue(export.isCancelled)
            assertEquals(1, provider.deletions)
            assertTrue(provider.files.isEmpty())
            assertArrayEquals(original, source.readBytes())
        }

    @Test
    fun `only completed incoming records with a retained destination are exportable`() =
        runTest {
            val invalid =
                DccTransferState.entries.filter { it != DccTransferState.COMPLETED }.map { transfer().copy(state = it) } +
                    transfer().copy(direction = DccDirection.OUTGOING) + transfer().copy(destinationUri = null)
            invalid.forEach { record ->
                assertFalse(record.canSaveToDownloads())
                try {
                    saveDccToDownloads(context, record)
                    fail("Expected ineligible record to fail")
                } catch (_: IllegalStateException) {
                    assertEquals(null, provider.collection)
                }
            }
            assertTrue(transfer().canSaveToDownloads())
        }

    @Test
    @Config(sdk = [28])
    @Suppress("DEPRECATION")
    fun `legacy Downloads publishes complete bytes without overwriting an existing file`() =
        runTest {
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            val existing = File(directory, "Search results.zip").apply { writeText("keep this download") }
            var exported: File? = null
            try {
                exported = File(requireNotNull(saveDccToDownloads(context, transfer()).path))
                assertEquals("Search results (1).zip", exported.name)
                assertArrayEquals(source.readBytes(), exported.readBytes())
                assertEquals("keep this download", existing.readText())
                assertTrue(source.exists())
                val beforeFailure = directory.list()!!.toSet()
                expectFailure { saveDccToDownloads(context, transfer().copy(destinationUri = "file:///missing/results.zip")) }
                assertEquals(beforeFailure, directory.list()!!.toSet())
            } finally {
                existing.delete()
                exported?.delete()
            }
        }

    private fun transfer() =
        DccTransferEntity(
            id = 901,
            networkId = 1,
            timelineEventId = null,
            offerKey = "fixture",
            direction = DccDirection.INCOMING,
            protocol = DccTransferProtocol.SEND,
            peerNick = "books_bot",
            normalizedPeer = "books_bot",
            filename = "../../Search results.zip",
            displayFilename = "Search results.zip",
            address = "8.8.8.8",
            addressKind = DccAddressKind.IPV4_DOTTED,
            port = 5000,
            sizeBytes = source.length(),
            token = null,
            state = DccTransferState.COMPLETED,
            bytesTransferred = source.length(),
            destinationUri = Uri.fromFile(source).toString(),
            createdAt = 1,
            expiresAt = null,
            completedAt = 2,
            updatedAt = 2,
        )

    private suspend fun expectFailure(action: suspend () -> Unit) {
        try {
            action()
            error("Expected download export to fail")
        } catch (_: IOException) {
            // Only storage failures are expected; assertion and programming errors must escape.
        }
    }
}

private class DownloadProvider(
    private val source: File,
    private val directory: File,
) : ContentProvider() {
    var collection: Uri? = null
    var insertedValues: ContentValues? = null
    var publishedBytes: ByteArray? = null
    var failOutput = false
    var publishResult = 1
    var deletions = 0
    var onOutputOpened: () -> Unit = {}
    val files = linkedMapOf<Uri, File>()
    private var nextId = 0

    override fun onCreate() = true

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri {
        collection = uri
        insertedValues = ContentValues(requireNotNull(values))
        val destination = Uri.withAppendedPath(uri, (++nextId).toString())
        files[destination] = File.createTempFile("dcc-download-", ".zip", directory)
        return destination
    }

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        if (uri.lastPathSegment == "source") return ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)
        if (mode.contains('w')) {
            if (failOutput) throw FileNotFoundException("Downloads is unavailable")
            onOutputOpened()
        }
        return ParcelFileDescriptor.open(requireNotNull(files[uri]), ParcelFileDescriptor.parseMode(mode))
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        assertEquals(0, requireNotNull(values).getAsInteger(MediaStore.Downloads.IS_PENDING))
        if (publishResult == 1) publishedBytes = requireNotNull(files[uri]).readBytes()
        return publishResult
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        deletions++
        files.remove(uri)?.delete()
        return 1
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "application/zip"
}
