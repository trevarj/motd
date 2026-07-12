package io.github.trevarj.motd.attachment

import android.net.Uri
import kotlinx.coroutines.flow.Flow

enum class PasteProtocol { TERMBIN, MULTIPART_0X0 }

enum class EndpointPreset(val endpoint: String?) {
    CRAFTERBIN("https://crafterbin.glennstack.dev"),
    ZERO_X_ZERO("https://0x0.st"),
    CUSTOM(null),
}

sealed interface AttachmentSource {
    data class Text(val text: String, val name: String = "paste.txt") : AttachmentSource
    data class Document(val uri: Uri, val name: String, val mimeType: String?, val size: Long?) : AttachmentSource
    data class Photo(val uri: Uri, val name: String, val mimeType: String?, val size: Long?) : AttachmentSource
}

data class PasteBackendConfig(
    val protocol: PasteProtocol = PasteProtocol.MULTIPART_0X0,
    val endpoint: String = EndpointPreset.CRAFTERBIN.endpoint!!,
    val expiry: String? = "7d",
    val secretUrl: Boolean = true,
    val sizeLimitBytes: Long = DEFAULT_PUBLIC_LIMIT_BYTES,
)

data class UploadRecord(
    val url: String,
    val backend: PasteProtocol,
    val displayName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val uploadedAt: Long = System.currentTimeMillis(),
    val deletionToken: String? = null,
    val endpoint: String? = null,
)

sealed interface UploadProgress {
    data class Transferring(val bytesSent: Long, val totalBytes: Long?) : UploadProgress
    data class Complete(val record: UploadRecord) : UploadProgress
}

interface AttachmentPrefs {
    val config: Flow<PasteBackendConfig>
    val recentUploads: Flow<List<UploadRecord>>
    suspend fun setConfig(config: PasteBackendConfig)
    suspend fun addUpload(record: UploadRecord)
    suspend fun removeUpload(url: String)
}

interface AttachmentUploader {
    fun upload(source: AttachmentSource, config: PasteBackendConfig): Flow<UploadProgress>
    suspend fun delete(record: UploadRecord)
}

const val DEFAULT_PUBLIC_LIMIT_BYTES = 25L * 1024 * 1024
const val MAX_CUSTOM_LIMIT_BYTES = 512L * 1024 * 1024
const val MAX_UPLOAD_HISTORY = 20

fun validateEndpoint(value: String): String? = runCatching {
    val url = java.net.URL(value.trim().trimEnd('/'))
    require(url.protocol == "https" && !url.host.isNullOrBlank() && url.userInfo == null)
    url.toString().trimEnd('/')
}.getOrNull()

fun normalizedConfig(config: PasteBackendConfig): PasteBackendConfig {
    val endpoint = validateEndpoint(config.endpoint) ?: EndpointPreset.CRAFTERBIN.endpoint!!
    val public = EndpointPreset.entries.any { it.endpoint == endpoint }
    val maximum = if (public) DEFAULT_PUBLIC_LIMIT_BYTES else MAX_CUSTOM_LIMIT_BYTES
    return config.copy(endpoint = endpoint, sizeLimitBytes = config.sizeLimitBytes.coerceIn(1, maximum))
}
