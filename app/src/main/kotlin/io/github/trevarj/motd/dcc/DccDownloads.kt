package io.github.trevarj.motd.dcc

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import io.github.trevarj.motd.data.db.DccDirection
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.DccTransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.Locale
import kotlin.coroutines.coroutineContext

internal fun DccTransferEntity.canSaveToDownloads(): Boolean = direction == DccDirection.INCOMING && state == DccTransferState.COMPLETED && !destinationUri.isNullOrBlank()

/** Copies retained bytes, not parsed ebooks results, and never changes the original destination. */
internal suspend fun saveDccToDownloads(
    context: Context,
    transfer: DccTransferEntity,
): Uri =
    withContext(Dispatchers.IO) {
        check(transfer.canSaveToDownloads()) { "Only completed received files can be saved" }
        val filename =
            sanitizeDccDisplayFilename(transfer.displayFilename.ifBlank { transfer.filename })
                .trimStart('.')
                .ifBlank { "download" }
        val mimeType =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
        val source = requireNotNull(transfer.destinationUri).toUri()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savePendingDownload(context, source, filename, mimeType)
        } else {
            saveLegacyDownload(context, source, filename, mimeType)
        }
    }

@RequiresApi(Build.VERSION_CODES.Q)
private suspend fun savePendingDownload(
    context: Context,
    source: Uri,
    filename: String,
    mimeType: String,
): Uri {
    val resolver = context.contentResolver
    val values =
        ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
    val destination =
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Cannot create download")
    var published = false
    try {
        val output = resolver.openOutputStream(destination) ?: throw IOException("Cannot write download")
        output.use { destinationStream ->
            val input = resolver.openInputStream(source) ?: throw IOException("Received file is unavailable")
            input.use { copyDownload(it, destinationStream) }
        }
        coroutineContext.ensureActive()
        if (resolver.update(
                destination,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            ) != 1
        ) {
            throw IOException("Cannot publish download")
        }
        published = true
        return destination
    } finally {
        if (!published) resolver.delete(destination, null, null)
    }
}

@Suppress("DEPRECATION")
private suspend fun saveLegacyDownload(
    context: Context,
    source: Uri,
    filename: String,
    mimeType: String,
): Uri {
    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Downloads is unavailable")
    val pending = File.createTempFile(".motd-", ".part", directory)
    try {
        pending.outputStream().use { output ->
            val input = context.contentResolver.openInputStream(source) ?: throw IOException("Received file is unavailable")
            input.use { copyDownload(it, output) }
        }
        coroutineContext.ensureActive()
        val stem = filename.substringBeforeLast('.', filename)
        val extension = filename.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var duplicate = 0
        while (true) {
            val destination = File(directory, if (duplicate == 0) filename else "$stem ($duplicate)$extension")
            try {
                Files.move(pending.toPath(), destination.toPath())
                MediaScannerConnection.scanFile(context, arrayOf(destination.path), arrayOf(mimeType), null)
                return Uri.fromFile(destination)
            } catch (_: FileAlreadyExistsException) {
                duplicate++
            }
        }
    } finally {
        pending.delete()
    }
}

private suspend fun copyDownload(
    input: InputStream,
    output: OutputStream,
) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        coroutineContext.ensureActive()
        val count = input.read(buffer)
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}
