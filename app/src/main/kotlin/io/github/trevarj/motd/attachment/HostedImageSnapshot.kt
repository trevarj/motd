package io.github.trevarj.motd.attachment

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** One private input for review, sanitization and any explicitly approved original upload. */
internal class HostedImageSnapshot private constructor(
    val file: File,
    name: String,
    mimeType: String,
) : Closeable {
    val source = AttachmentSource.LocalFile(this, name, mimeType)
    private var owners = 1

    @Synchronized
    fun retain(): Closeable? {
        if (owners == 0) return null
        owners++
        return Closeable { close() }
    }

    @Synchronized
    override fun close() {
        check(owners > 0)
        if (--owners == 0) file.delete()
    }

    companion object {
        private const val BUFFER_BYTES = 32 * 1024
        private val reapedDirectories = mutableSetOf<File>()

        @Synchronized
        fun reapStale(context: Context): File =
            File(context.cacheDir.canonicalFile, "hosted-image-review").also { directory ->
                if (directory.canonicalFile != directory) throw IOException("Invalid image review directory")
                if (directory !in reapedDirectories) {
                    if (directory.exists()) {
                        val stale = directory.listFiles() ?: throw IOException("Unable to read image review directory")
                        for (file in stale) {
                            if (!file.delete() && file.exists()) throw IOException("Unable to clear image review directory")
                        }
                    }
                    reapedDirectories.add(directory)
                }
            }

        suspend fun capture(
            context: Context,
            source: AttachmentSource,
            limitBytes: Long,
        ): HostedImageSnapshot? {
            var snapshot: HostedImageSnapshot? = null
            try {
                return withContext(Dispatchers.IO) {
                    stage(context, source, limitBytes).also { snapshot = it }
                }
            } catch (failure: Throwable) {
                snapshot?.close()
                throw failure
            }
        }

        private suspend fun stage(
            context: Context,
            source: AttachmentSource,
            limitBytes: Long,
        ): HostedImageSnapshot? {
            require(limitBytes > 0)
            val (name, declaredMime) =
                when (source) {
                    is AttachmentSource.Photo -> source.name to source.mimeType
                    is AttachmentSource.Document -> source.name to source.mimeType
                    is AttachmentSource.LocalFile -> source.name to source.mimeType
                    is AttachmentSource.Text -> return null
                }
            val mime = declaredMime?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            val expectsImage = source is AttachmentSource.Photo || mime?.startsWith("image/") == true
            val stream =
                try {
                    when (source) {
                        is AttachmentSource.Photo -> context.openLocalImageSource(source.uri)
                        is AttachmentSource.Document -> context.openLocalImageSource(source.uri)
                        is AttachmentSource.LocalFile -> source.file.inputStream()
                        is AttachmentSource.Text -> return null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (expectsImage) throw failure
                    return null // Leave unrelated documents to the normal on-upload path.
                }
            if (stream == null) {
                if (expectsImage) throw IOException("Unable to open image")
                return null
            }
            var temporary: File? = null
            try {
                val actualMime =
                    stream.use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        val prefixSize =
                            if (expectsImage) {
                                0
                            } else {
                                try {
                                    input.readPrefix(buffer)
                                } catch (_: IOException) {
                                    return null
                                }
                            }
                        val probedMime = if (prefixSize == 0) null else detectableMime(buffer, prefixSize)
                        if (!expectsImage && probedMime == null) return null
                        var total = prefixSize.toLong()
                        if (total > limitBytes) throw HostedImageTooLarge(total, limitBytes)
                        val directory = reapStale(context)
                        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Unable to create image review directory")
                        val file = File.createTempFile("image-", ".tmp", directory).also { temporary = it }
                        file.outputStream().use { output ->
                            output.write(buffer, 0, prefixSize)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limitBytes - total + 1).toInt())
                                if (count < 0) break
                                total += count
                                if (total > limitBytes) throw HostedImageTooLarge(total, limitBytes)
                                output.write(buffer, 0, count)
                            }
                            if (total == 0L) throw IOException("Image is empty")
                        }
                        probedMime ?: detectableMime(file)
                    }
                return HostedImageSnapshot(checkNotNull(temporary), name, actualMime ?: mime ?: guessMimeType(name)).also { temporary = null }
            } finally {
                temporary?.delete()
            }
        }

        private fun Context.openLocalImageSource(uri: Uri): InputStream? {
            require(uri.isHostedAttachmentUri()) { "Image review requires a local file" }
            return contentResolver.openInputStream(uri)
        }

        private suspend fun InputStream.readPrefix(buffer: ByteArray): Int {
            var size = 0
            while (size < buffer.size) {
                currentCoroutineContext().ensureActive()
                val count = read(buffer, size, buffer.size - size)
                if (count < 0) break
                size += count
            }
            return size
        }

        private fun detectableMime(file: File): String? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            return bounds.outMimeType
        }

        private fun detectableMime(
            buffer: ByteArray,
            size: Int,
        ): String? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(buffer, 0, size, bounds)
            return bounds.outMimeType
        }
    }
}

internal class HostedImageTooLarge(
    val minimumSize: Long,
    val limitBytes: Long,
) : IOException("Image exceeds the configured upload limit")

internal fun Uri.isHostedAttachmentUri(): Boolean = isLocalMediaUri() && (scheme != ContentResolver.SCHEME_FILE || authority.isNullOrEmpty())
