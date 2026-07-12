package io.github.trevarj.motd.attachment

import android.net.Uri
import kotlinx.coroutines.flow.Flow

enum class PasteProtocol { TERMBIN, MULTIPART_0X0, RAW_CNET, MULTIPART_UGUU, MULTIPART_CATBOX }

enum class AttachmentBackend(
    val label: String,
    val protocol: PasteProtocol,
    val endpoint: String?,
    val acceptsBinary: Boolean,
) {
    CRAFTERBIN("CrafterBin", PasteProtocol.MULTIPART_0X0, "https://crafterbin.glennstack.dev", true),
    ZERO_X_ZERO("0x0.st", PasteProtocol.MULTIPART_0X0, "https://0x0.st", true),
    CUSTOM_0X0("Custom 0x0-compatible", PasteProtocol.MULTIPART_0X0, null, true),
    CNET("paste.c-net.org", PasteProtocol.RAW_CNET, "https://paste.c-net.org", true),
    UGUU("Uguu", PasteProtocol.MULTIPART_UGUU, "https://uguu.se/upload", true),
    LITTERBOX(
        "Litterbox",
        PasteProtocol.MULTIPART_CATBOX,
        "https://litterbox.catbox.moe/resources/internals/api.php",
        true,
    ),
    CATBOX("Catbox", PasteProtocol.MULTIPART_CATBOX, "https://catbox.moe/user/api.php", true),
    TERMBIN("Termbin", PasteProtocol.TERMBIN, null, false),
}

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
    val backend: AttachmentBackend = AttachmentBackend.CRAFTERBIN,
    val endpoint: String = EndpointPreset.CRAFTERBIN.endpoint!!,
    val customEndpoint: String = EndpointPreset.CRAFTERBIN.endpoint!!,
    val expiry: String? = "7d",
    val litterboxExpiry: String = DEFAULT_LITTERBOX_EXPIRY,
    val secretUrl: Boolean = true,
    val sizeLimitBytes: Long = DEFAULT_PUBLIC_LIMIT_BYTES,
) {
    val protocol: PasteProtocol get() = backend.protocol
}

data class UploadRecord(
    val url: String,
    val backend: AttachmentBackend,
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
const val DEFAULT_LITTERBOX_EXPIRY = "24h"
val LITTERBOX_EXPIRIES = listOf("1h", "12h", "24h", "72h")

fun validateEndpoint(value: String): String? = runCatching {
    val url = java.net.URL(value.trim().trimEnd('/'))
    require(url.protocol == "https" && !url.host.isNullOrBlank() && url.userInfo == null)
    url.toString().trimEnd('/')
}.getOrNull()

fun normalizedConfig(config: PasteBackendConfig): PasteBackendConfig {
    val customEndpoint = validateEndpoint(config.customEndpoint)
        ?: validateEndpoint(config.endpoint)
        ?: AttachmentBackend.CRAFTERBIN.endpoint!!
    val backend = config.backend
    val endpoint = backend.endpoint ?: customEndpoint
    val maximum = if (backend == AttachmentBackend.CUSTOM_0X0) MAX_CUSTOM_LIMIT_BYTES else DEFAULT_PUBLIC_LIMIT_BYTES
    return config.copy(
        backend = backend,
        endpoint = endpoint,
        customEndpoint = customEndpoint,
        litterboxExpiry = config.litterboxExpiry.takeIf(LITTERBOX_EXPIRIES::contains)
            ?: DEFAULT_LITTERBOX_EXPIRY,
        sizeLimitBytes = config.sizeLimitBytes.coerceIn(1, maximum),
    )
}

fun PasteBackendConfig.forBackend(backend: AttachmentBackend): PasteBackendConfig =
    normalizedConfig(copy(backend = backend, endpoint = backend.endpoint ?: customEndpoint))

fun AttachmentBackend.supports(source: AttachmentSource): Boolean =
    source is AttachmentSource.Text || acceptsBinary
