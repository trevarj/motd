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
    private val reviewLease: Closeable? = null,
) : Closeable {
    override fun close() {
        temporaryFile?.delete()
        reviewLease?.close()
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
            val reviewed = (source as? AttachmentSource.LocalFile)?.takeIf { stagingDirectory == null && it.reviewedImage != null }
            val lease = reviewed?.let { it.reviewedImage?.retain() ?: throw IOException("Image review is no longer available") }
            var prepared: PreparedUpload? = null
            try {
                return withContext(Dispatchers.IO) {
                    stage(source, limitBytes, allowOriginal, stripMetadata, stagingDirectory, reviewed, lease).also { prepared = it }
                }
            } catch (failure: Throwable) {
                if (prepared == null) lease?.close() else prepared.close()
                throw failure
            }
        }

        private suspend fun stage(
            source: AttachmentSource,
            limitBytes: Long,
            allowOriginal: Boolean,
            stripMetadata: Boolean,
            stagingDirectory: File?,
            reviewed: AttachmentSource.LocalFile?,
            lease: Closeable?,
        ): PreparedUpload {
            require(limitBytes > 0)
            if (source is AttachmentSource.Text) return PreparedUpload(source)
            val directory = stagingDirectory ?: reviewed?.file?.parentFile ?: context.cacheDir
            if (stagingDirectory != null && !directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                throw IOException("Unable to create upload staging directory")
            }
            val input = reviewed?.file ?: File.createTempFile("upload-input-", ".tmp", directory)
            var output: File? = null
            var retained: File? = null
            try {
                if (reviewed == null) {
                    copyInput(source, input, limitBytes)
                } else if (!input.isFile || input.length() != reviewed.size || input.length() > limitBytes) {
                    throw UploadException("Reviewed image is unavailable or exceeds the configured upload limit")
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
                        if (reviewed != null) return PreparedUpload(reviewed, reviewLease = lease)
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
                return PreparedUpload(prepared, prepared.file, lease)
            } finally {
                if (reviewed == null && retained !== input) input.delete()
                if (retained !== output) output?.delete()
            }
        }

        private suspend fun copyInput(
            source: AttachmentSource,
            input: File,
            limitBytes: Long,
        ) {
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
                input.outputStream().use { staged ->
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
        }
    }
