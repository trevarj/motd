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
import java.security.MessageDigest
import java.util.Locale

/** One private input for review, sanitization and any explicitly approved original upload. */
internal class HostedImageSnapshot private constructor(
    val file: File,
    name: String,
    mimeType: String,
    val detectedImageMime: String?,
    val contentDigest: ByteArray?,
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

        suspend fun reviewBatchBytes(
            resolver: ContentResolver,
            source: AttachmentSource.Document,
            limitBytes: Long,
        ): BatchFingerprint {
            require(limitBytes > 0)
            val digest = MessageDigest.getInstance("SHA-256")
            val input = resolver.openLocalImageSource(source.uri) ?: throw IOException("Unable to open attachment")
            val size = input.use { it.readBounded(ByteArray(BUFFER_BYTES), limitBytes) { buffer, count -> digest.update(buffer, 0, count) } }
            return BatchFingerprint(size, digest.digest())
        }

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
        ): HostedImageSnapshot? = capture(context, source, limitBytes, stageAnyFile = false)

        suspend fun captureBatch(
            context: Context,
            source: AttachmentSource.Document,
            limitBytes: Long,
        ): HostedImageSnapshot = checkNotNull(capture(context, source, limitBytes, stageAnyFile = true))

        private suspend fun capture(
            context: Context,
            source: AttachmentSource,
            limitBytes: Long,
            stageAnyFile: Boolean,
        ): HostedImageSnapshot? {
            var snapshot: HostedImageSnapshot? = null
            try {
                return withContext(Dispatchers.IO) {
                    stage(context, source, limitBytes, stageAnyFile).also { snapshot = it }
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
            stageAnyFile: Boolean,
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
                        is AttachmentSource.Photo -> context.contentResolver.openLocalImageSource(source.uri)
                        is AttachmentSource.Document -> context.contentResolver.openLocalImageSource(source.uri)
                        is AttachmentSource.LocalFile -> source.file.inputStream()
                        is AttachmentSource.Text -> return null
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (stageAnyFile || expectsImage) throw failure
                    return null // Leave unrelated documents to the normal on-upload path.
                }
            if (stream == null) {
                if (stageAnyFile) throw IOException("Unable to open attachment")
                if (expectsImage) throw IOException("Unable to open image")
                return null
            }
            var temporary: File? = null
            val digest = if (stageAnyFile) MessageDigest.getInstance("SHA-256") else null
            try {
                val actualMime =
                    stream.use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        val prefixSize =
                            if (stageAnyFile || expectsImage) {
                                0
                            } else {
                                try {
                                    input.readPrefix(buffer)
                                } catch (_: IOException) {
                                    return null
                                }
                            }
                        val probedMime = if (prefixSize == 0) null else detectableMime(buffer, prefixSize)
                        if (!stageAnyFile && !expectsImage && probedMime == null) return null
                        if (prefixSize > limitBytes) throw HostedImageTooLarge(prefixSize.toLong(), limitBytes)
                        val directory = reapStale(context)
                        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) throw IOException("Unable to create image review directory")
                        val file = File.createTempFile("image-", ".tmp", directory).also { temporary = it }
                        file.outputStream().use { output ->
                            output.write(buffer, 0, prefixSize)
                            val total =
                                input.readBounded(buffer, limitBytes, prefixSize) { bytes, count ->
                                    output.write(bytes, 0, count)
                                    digest?.update(bytes, 0, count)
                                }
                            if (total == 0L && !stageAnyFile) throw IOException("Image is empty")
                        }
                        probedMime ?: detectableMime(file)
                    }
                return HostedImageSnapshot(
                    checkNotNull(temporary),
                    name,
                    actualMime ?: mime ?: guessMimeType(name),
                    actualMime,
                    digest?.digest(),
                ).also { temporary = null }
            } finally {
                temporary?.delete()
            }
        }

        private fun ContentResolver.openLocalImageSource(uri: Uri): InputStream? {
            require(uri.isHostedAttachmentUri()) { "Image review requires a local file" }
            return openInputStream(uri)
        }

        private suspend fun InputStream.readBounded(
            buffer: ByteArray,
            limitBytes: Long,
            prefixSize: Int = 0,
            onChunk: (ByteArray, Int) -> Unit,
        ): Long {
            var total = prefixSize.toLong()
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = read(buffer, 0, minOf(buffer.size.toLong(), limitBytes - total + 1).toInt())
                if (count < 0) return total
                total += count
                if (total > limitBytes) throw HostedImageTooLarge(total, limitBytes)
                onChunk(buffer, count)
            }
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

internal class BatchFingerprint(
    val size: Long,
    val sha256: ByteArray,
)

internal class HostedImageTooLarge(
    val minimumSize: Long,
    val limitBytes: Long,
) : IOException("Image exceeds the configured upload limit")

internal fun Uri.isHostedAttachmentUri(): Boolean = isLocalMediaUri() && (scheme != ContentResolver.SCHEME_FILE || authority.isNullOrEmpty())
