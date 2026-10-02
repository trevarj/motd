package io.github.trevarj.motd.ai

import android.os.CancellationSignal
import io.github.trevarj.motd.ai.text.BuildConfig
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

internal data class TextModelArtifact(
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
) {
    companion object {
        val Pinned = TextModelArtifact(BuildConfig.TEXT_MODEL_URL, BuildConfig.TEXT_MODEL_BYTES, BuildConfig.TEXT_MODEL_SHA256)
    }
}

/** No credentials, cookies, signed URL diagnostics, or automatic redirect handling. */
internal fun openModelDownload(
    url: String,
    cancellationSignal: CancellationSignal = CancellationSignal(),
    onHop: (String) -> Unit = {},
): InputStream {
    var next = url
    try {
        repeat(6) { hop ->
            val uri = URI(next)
            val host = uri.host?.lowercase() ?: throw AiLabsException(AiLabsFailureKind.NETWORK)
            if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443) ||
                !(host == "huggingface.co" || host.endsWith(".huggingface.co") || host.endsWith(".hf.co"))
            ) {
                throw AiLabsException(AiLabsFailureKind.NETWORK)
            }
            onHop(host)
            val connection = uri.toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Cookie", "")
            connection.useCaches = false
            cancellationSignal.setOnCancelListener { connection.disconnect() }
            cancellationSignal.throwIfCanceled()
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    if (hop == 5) throw AiLabsException(AiLabsFailureKind.NETWORK)
                    val location = connection.getHeaderField("Location") ?: throw AiLabsException(AiLabsFailureKind.NETWORK)
                    next = uri.resolve(location).toString()
                    connection.disconnect()
                } else {
                    if (status != 200) throw AiLabsException(AiLabsFailureKind.NETWORK)
                    val mime =
                        connection.contentType
                            ?.substringBefore(';')
                            ?.trim()
                            ?.lowercase()
                    if (mime != null && mime !in setOf("application/octet-stream", "binary/octet-stream", "application/gguf", "application/x-gguf")) {
                        throw AiLabsException(AiLabsFailureKind.NETWORK)
                    }
                    val encoding = connection.contentEncoding
                    if (encoding != null && !encoding.equals("identity", ignoreCase = true)) throw AiLabsException(AiLabsFailureKind.NETWORK)
                    return object : FilterInputStream(connection.inputStream) {
                        override fun read(): Int = networkRead { super.read() }

                        override fun read(
                            buffer: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int = networkRead { super.read(buffer, offset, length) }

                        override fun close() {
                            try {
                                super.close()
                            } finally {
                                connection.disconnect()
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                connection.disconnect()
                throw failure
            }
        }
    } catch (failure: AiLabsException) {
        throw failure
    } catch (_: Exception) {
        throw AiLabsException(AiLabsFailureKind.NETWORK)
    }
    throw AiLabsException(AiLabsFailureKind.NETWORK)
}

private inline fun <T> networkRead(read: () -> T): T =
    try {
        read()
    } catch (_: IOException) {
        throw AiLabsException(AiLabsFailureKind.NETWORK)
    }
