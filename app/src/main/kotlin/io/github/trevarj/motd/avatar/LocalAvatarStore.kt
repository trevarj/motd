package io.github.trevarj.motd.avatar

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.trevarj.motd.attachment.isLocalMediaUri
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalAvatarStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val directory = File(context.filesDir, DIRECTORY).also(File::mkdirs)
        private val resolver = context.contentResolver

        /** Copy an image into app-owned storage without buffering untrusted input in memory. */
        fun import(source: Uri): Result<String> =
            runCatching {
                if (!source.isLocalMediaUri()) throw IOException("Avatar imports require a local content, file, or android.resource URI")
                val temporary = File(directory, ".${UUID.randomUUID()}.tmp")
                try {
                    resolver.openInputStream(source)?.use { input ->
                        temporary.outputStream().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > MAX_BYTES) throw IOException("Avatar image is larger than 8 MiB")
                                output.write(buffer, 0, read)
                            }
                            output.fd.sync()
                        }
                    } ?: throw IOException("Unable to open image")
                    if (!hasSupportedImageHeader(temporary)) throw IOException("Selected file is not an image")
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(temporary.path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Selected file is not an image")
                    val target = File(directory, "${UUID.randomUUID()}.image")
                    if (!temporary.renameTo(target)) throw IOException("Unable to store avatar image")
                    target.toUri().toString()
                } finally {
                    temporary.delete()
                }
            }

        fun owns(model: String): Boolean {
            val uri = runCatching { model.toUri() }.getOrNull() ?: return false
            if (uri.scheme != "file") return false
            val file = uri.path?.let(::File) ?: return false
            return runCatching { file.canonicalFile.parentFile == directory.canonicalFile }.getOrDefault(false)
        }

        fun delete(model: String?) {
            if (model != null && owns(model)) {
                model
                    .toUri()
                    .path
                    ?.let(::File)
                    ?.delete()
            }
        }

        /** Remove only files inside this store that no live Room row references. */
        fun prune(liveModels: Collection<String>) {
            val live = liveModels.filter(::owns).mapNotNull { it.toUri().path }.toSet()
            directory.listFiles()?.forEach { file ->
                if (file.path !in live) file.delete()
            }
        }

        private fun hasSupportedImageHeader(file: File): Boolean {
            val header = ByteArray(12)
            val count = file.inputStream().use { it.read(header) }
            if (count < 4) return false
            return header.copyOfRange(0, 8).contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)) ||
                (header[0] == 0xff.toByte() && header[1] == 0xd8.toByte()) ||
                String(header, 0, 4, Charsets.US_ASCII) == "GIF8" ||
                (
                    String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
                )
        }

        internal companion object {
            const val MAX_BYTES = 8L * 1024L * 1024L
            const val DIRECTORY = "conversation-avatars"
        }
    }
