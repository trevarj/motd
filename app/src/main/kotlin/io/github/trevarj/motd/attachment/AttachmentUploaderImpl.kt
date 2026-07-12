package io.github.trevarj.motd.attachment

import android.content.ContentResolver
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal object MultipartEncoding {
    fun field(boundary: String, name: String, value: String): ByteArray =
        ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
            .toByteArray(StandardCharsets.UTF_8)

    fun fileHeader(boundary: String, name: String, mime: String): ByteArray =
        ("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${escape(name)}\"\r\n" +
            "Content-Type: $mime\r\n\r\n").toByteArray(StandardCharsets.UTF_8)

    fun ending(boundary: String): ByteArray = "\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8)
    private fun escape(value: String) = value.replace("\\", "_").replace("\"", "_").replace("\r", "_").replace("\n", "_")
}

@Singleton
class AttachmentUploaderImpl @Inject constructor(
    @ApplicationContext context: Context,
) : AttachmentUploader {
    private val resolver: ContentResolver = context.contentResolver

    override fun upload(source: AttachmentSource, config: PasteBackendConfig): Flow<UploadProgress> = flow {
        val safe = normalizedConfig(config)
        val knownSize = source.sizeOrNull()
        if (knownSize != null && knownSize > safe.sizeLimitBytes) throw UploadException("File exceeds the configured upload limit")
        if (safe.protocol == PasteProtocol.TERMBIN) {
            require(source is AttachmentSource.Text) { "Termbin supports text only" }
            emit(uploadTermbin(source, safe))
        } else {
            emit(uploadMultipart(source, safe) { sent, total -> emit(UploadProgress.Transferring(sent, total)) })
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun uploadTermbin(source: AttachmentSource.Text, config: PasteBackendConfig): UploadProgress.Complete {
        val bytes = source.text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > config.sizeLimitBytes) throw UploadException("Paste exceeds the configured upload limit")
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress("termbin.com", 9999), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val output = socket.getOutputStream()
            output.write(bytes); output.write('\n'.code); output.flush()
            socket.shutdownOutput()
            val url = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine()?.trim()
            if (url.isNullOrBlank()) throw UploadException("Termbin returned an empty response")
            return UploadProgress.Complete(UploadRecord(url, PasteProtocol.TERMBIN, source.name, "text/plain", bytes.size.toLong()))
        }
    }

    private suspend fun uploadMultipart(
        source: AttachmentSource,
        config: PasteBackendConfig,
        progress: suspend (Long, Long?) -> Unit,
    ): UploadProgress.Complete {
        val boundary = "motd-${java.util.UUID.randomUUID()}"
        val connection = (URL(config.endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; useCaches = false
            connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setChunkedStreamingMode(STREAM_BUFFER_BYTES)
        }
        // HttpURLConnection uses blocking I/O. Disconnect from the cancellation callback so a
        // dismissed progress sheet does not wait for a stalled write/read timeout.
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
            connection.disconnect()
        }
        try {
            connection.outputStream.use { output ->
                if (config.secretUrl) output.write(MultipartEncoding.field(boundary, "secret", ""))
                config.expiry?.takeIf { it.isNotBlank() }?.let { output.write(MultipartEncoding.field(boundary, "expires", it)) }
                output.write(MultipartEncoding.fileHeader(boundary, source.displayName(), source.mimeType()))
                source.open(resolver).use { input ->
                    val buffer = ByteArray(STREAM_BUFFER_BYTES)
                    var sent = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        sent += count
                        if (sent > config.sizeLimitBytes) throw UploadException("Upload exceeds the configured limit")
                        output.write(buffer, 0, count)
                        progress(sent, source.sizeOrNull())
                    }
                }
                output.write(MultipartEncoding.ending(boundary))
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }?.trim().orEmpty()
            if (code !in 200..299) throw UploadException("Upload failed (HTTP $code): ${body.take(160)}")
            val resultUrl = body.lineSequence().map(String::trim).firstOrNull { it.startsWith("https://") }
                ?: throw UploadException("Backend did not return an HTTPS URL")
            return UploadProgress.Complete(UploadRecord(resultUrl, PasteProtocol.MULTIPART_0X0,
                source.displayName(), source.mimeType(), source.sizeOrNull(), deletionToken = connection.getHeaderField("X-Token"), endpoint = config.endpoint))
        } finally {
            cancellationHandle?.dispose()
            connection.disconnect()
        }
    }

    override suspend fun delete(record: UploadRecord) {
        val token = record.deletionToken ?: throw UploadException("This upload has no deletion token")
        val connection = (URL(record.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"; connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = READ_TIMEOUT_MS
            setRequestProperty("X-Token", token)
        }
        try {
            if (connection.responseCode !in 200..299) throw UploadException("Delete failed (HTTP ${connection.responseCode})")
        } finally { connection.disconnect() }
    }

    private fun AttachmentSource.open(resolver: ContentResolver): InputStream = when (this) {
        is AttachmentSource.Text -> text.byteInputStream(StandardCharsets.UTF_8)
        is AttachmentSource.Document -> resolver.openInputStream(uri)
        is AttachmentSource.Photo -> resolver.openInputStream(uri)
    } ?: throw IOException("Unable to open attachment")
}

private fun AttachmentSource.displayName() = when (this) {
    is AttachmentSource.Text -> name
    is AttachmentSource.Document -> name
    is AttachmentSource.Photo -> name
}
private fun AttachmentSource.mimeType() = when (this) {
    is AttachmentSource.Text -> "text/plain; charset=utf-8"
    is AttachmentSource.Document -> mimeType ?: "application/octet-stream"
    is AttachmentSource.Photo -> mimeType ?: "image/*"
}
private fun AttachmentSource.sizeOrNull() = when (this) {
    is AttachmentSource.Text -> text.toByteArray(StandardCharsets.UTF_8).size.toLong()
    is AttachmentSource.Document -> size
    is AttachmentSource.Photo -> size
}

class UploadException(message: String) : IOException(message)
private const val CONNECT_TIMEOUT_MS = 15_000
private const val READ_TIMEOUT_MS = 60_000
private const val STREAM_BUFFER_BYTES = 32 * 1024
