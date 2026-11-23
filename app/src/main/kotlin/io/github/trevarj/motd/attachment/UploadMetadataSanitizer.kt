package io.github.trevarj.motd.attachment

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

class MetadataNotSupportedException : UploadException("Metadata removal is not supported for this file. Sending the original may expose location, author or other private information.")

class PreparedUpload internal constructor(
    val source: AttachmentSource,
    private val temporaryFile: File? = null,
) : Closeable {
    override fun close() {
        temporaryFile?.delete()
    }
}

internal data class SanitizedMedia(
    val mimeType: String,
    val extension: String,
)

class UploadMetadataSanitizer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        suspend fun prepare(
            source: AttachmentSource,
            limitBytes: Long,
            allowOriginal: Boolean = false,
            stripMetadata: Boolean = true,
            stagingDirectory: File? = null,
        ): PreparedUpload {
            var prepared: PreparedUpload? = null
            try {
                return withContext(Dispatchers.IO) {
                    stage(source, limitBytes, allowOriginal, stripMetadata, stagingDirectory).also { prepared = it }
                }
            } catch (failure: Throwable) {
                prepared?.close()
                throw failure
            }
        }

        private suspend fun stage(
            source: AttachmentSource,
            limitBytes: Long,
            allowOriginal: Boolean,
            stripMetadata: Boolean,
            stagingDirectory: File?,
        ): PreparedUpload {
            require(limitBytes > 0)
            if (source is AttachmentSource.Text) return PreparedUpload(source)
            val directory = stagingDirectory ?: context.cacheDir
            if (stagingDirectory != null && !directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Unable to create upload staging directory")
            }
            val input = File.createTempFile("upload-input-", ".tmp", directory)
            var output: File? = null
            var retained: File? = null
            try {
                val original =
                    when (source) {
                        is AttachmentSource.Document -> {
                            require(source.uri.isLocalMediaUri()) { "Uploads require a local file" }
                            context.contentResolver.openInputStream(source.uri)
                        }

                        is AttachmentSource.Photo -> {
                            require(source.uri.isLocalMediaUri()) { "Uploads require a local file" }
                            context.contentResolver.openInputStream(source.uri)
                        }

                        is AttachmentSource.LocalFile -> {
                            source.file.inputStream()
                        }

                        is AttachmentSource.Text -> {
                            error("Text does not require staging")
                        }
                    } ?: throw IOException("Unable to open attachment")
                original.use { stream ->
                    input.outputStream().buffered().use { staged ->
                        val buffer = ByteArray(32 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > limitBytes) throw UploadException("File exceeds the configured upload limit")
                            staged.write(buffer, 0, count)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val media =
                    if (stripMetadata) {
                        val sanitized = File.createTempFile("upload-clean-", ".tmp", directory)
                        output = sanitized
                        stripImageMetadata(input, sanitized) ?: stripAudioVideoMetadata(input, sanitized)
                    } else {
                        null
                    }
                val prepared =
                    if (media != null) {
                        val sanitized = checkNotNull(output)
                        if (sanitized.length() > limitBytes) throw UploadException("Sanitized file exceeds the configured upload limit")
                        AttachmentSource.LocalFile(sanitized, "upload-${UUID.randomUUID()}${media.extension}", media.mimeType)
                    } else {
                        if (stripMetadata && !allowOriginal) throw MetadataNotSupportedException()
                        val name =
                            when (source) {
                                is AttachmentSource.Document -> source.name
                                is AttachmentSource.Photo -> source.name
                                is AttachmentSource.LocalFile -> source.name
                                is AttachmentSource.Text -> error("Text does not require staging")
                            }
                        val mime =
                            when (source) {
                                is AttachmentSource.Document -> source.mimeType
                                is AttachmentSource.Photo -> source.mimeType
                                is AttachmentSource.LocalFile -> source.mimeType
                                is AttachmentSource.Text -> error("Text does not require staging")
                            } ?: guessMimeType(name)
                        AttachmentSource.LocalFile(input, name, mime)
                    }
                currentCoroutineContext().ensureActive()
                retained = prepared.file
                return PreparedUpload(prepared, prepared.file)
            } finally {
                if (retained !== input) input.delete()
                if (retained !== output) output?.delete()
            }
        }
    }
