package io.github.trevarj.motd.attachment

import android.net.Uri
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.io.File
import java.util.Locale

enum class PasteProtocol { TERMBIN, MULTIPART_0X0, RAW_CNET, MULTIPART_UGUU, MULTIPART_CATBOX, SOJU_FILEHOST }

enum class AttachmentBackend(
    val label: String,
    val protocol: PasteProtocol,
    val endpoint: String?,
    val acceptsBinary: Boolean,
) {
    CUSTOM_0X0("Custom", PasteProtocol.MULTIPART_0X0, null, true),
    CRAFTERBIN("CrafterBin", PasteProtocol.MULTIPART_0X0, "https://crafterbin.glennstack.dev", true),
    ZERO_X_ZERO("0x0.st", PasteProtocol.MULTIPART_0X0, "https://0x0.st", true),

    // x0.at is a filehost2 instance with a 0x0-compatible wire format but a 1 GiB ceiling and
    // no expires/secret/deletion support; retention is automatic and size-based (3-100 days).
    X0_AT("x0.at", PasteProtocol.MULTIPART_0X0, "https://x0.at", true),
    CNET("paste.c-net.org", PasteProtocol.RAW_CNET, "https://paste.c-net.org", true),
    UGUU("Uguu", PasteProtocol.MULTIPART_UGUU, "https://uguu.se/upload", true),
    LITTERBOX(
        "Litterbox",
        PasteProtocol.MULTIPART_CATBOX,
        "https://litterbox.catbox.moe/resources/internals/api.php",
        true,
    ),
    CATBOX("Catbox", PasteProtocol.MULTIPART_CATBOX, "https://catbox.moe/user/api.php", true),
    SOJU_FILEHOST("Soju file host", PasteProtocol.SOJU_FILEHOST, null, true),
    TERMBIN("Termbin", PasteProtocol.TERMBIN, null, false),
}

enum class EndpointPreset(
    val endpoint: String?,
) {
    CRAFTERBIN("https://crafterbin.glennstack.dev"),
    ZERO_X_ZERO("https://0x0.st"),
    CUSTOM(null),
}

sealed interface AttachmentSource {
    data class Text(
        val text: String,
        val name: String = "paste.txt",
    ) : AttachmentSource

    data class Document(
        val uri: Uri,
        val name: String,
        val mimeType: String?,
        val size: Long?,
    ) : AttachmentSource

    data class Photo(
        val uri: Uri,
        val name: String,
        val mimeType: String?,
        val size: Long?,
    ) : AttachmentSource

    data class LocalFile(
        val file: File,
        val name: String,
        val mimeType: String,
        val size: Long? = file.length(),
    ) : AttachmentSource
}

@Serializable
data class PasteBackendConfig(
    val backend: AttachmentBackend = AttachmentBackend.CRAFTERBIN,
    val endpoint: String = EndpointPreset.CRAFTERBIN.endpoint!!,
    val customEndpoint: String = EndpointPreset.CRAFTERBIN.endpoint!!,
    val expiry: String? = "168",
    val litterboxExpiry: String = DEFAULT_LITTERBOX_EXPIRY,
    val secretUrl: Boolean = true,
    val sizeLimitBytes: Long = DEFAULT_PUBLIC_LIMIT_BYTES,
    val username: String = "",
    val password: String = "",
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

data class AttachmentUploadContext(
    val networkId: Long? = null,
)

sealed interface UploadProgress {
    data class Transferring(
        val bytesSent: Long,
        val totalBytes: Long?,
    ) : UploadProgress

    data class Complete(
        val record: UploadRecord,
    ) : UploadProgress
}

interface AttachmentPrefs {
    val config: Flow<PasteBackendConfig>
    val recentUploads: Flow<List<UploadRecord>>

    suspend fun setConfig(config: PasteBackendConfig)

    suspend fun updateConfig(transform: (PasteBackendConfig) -> PasteBackendConfig)

    suspend fun addUpload(record: UploadRecord)

    suspend fun removeUpload(url: String)
}

interface AttachmentUploader {
    fun upload(
        source: AttachmentSource,
        config: PasteBackendConfig,
        context: AttachmentUploadContext = AttachmentUploadContext(),
    ): Flow<UploadProgress>

    suspend fun delete(record: UploadRecord)
}

const val DEFAULT_PUBLIC_LIMIT_BYTES = 25L * 1024 * 1024
const val MAX_CUSTOM_LIMIT_BYTES = 512L * 1024 * 1024
const val MAX_X0AT_LIMIT_BYTES = 1024L * 1024 * 1024

/** Per-backend upload ceiling. Custom endpoints and x0.at allow larger files than the public default. */
fun backendMaxBytes(backend: AttachmentBackend): Long =
    when (backend) {
        AttachmentBackend.CUSTOM_0X0 -> MAX_CUSTOM_LIMIT_BYTES
        AttachmentBackend.X0_AT -> MAX_X0AT_LIMIT_BYTES
        else -> DEFAULT_PUBLIC_LIMIT_BYTES
    }

const val MAX_UPLOAD_HISTORY = 20
const val DEFAULT_LITTERBOX_EXPIRY = "24h"
val LITTERBOX_EXPIRIES = listOf("1h", "12h", "24h", "72h")

// 0x0.st currently returns HTTP 503 for every upload. Keep the enum for stored-config
// compatibility, but do not offer a destination that cannot accept files.
val AVAILABLE_ATTACHMENT_BACKENDS = AttachmentBackend.entries.filterNot { it == AttachmentBackend.ZERO_X_ZERO }

fun validateEndpoint(value: String): String? =
    runCatching {
        val url = java.net.URL(value.trim().trimEnd('/'))
        require(url.protocol == "https" && !url.host.isNullOrBlank() && url.userInfo == null)
        url.toString().trimEnd('/')
    }.getOrNull()

const val SOJU_FILEHOST_TOKEN = "soju.im/FILEHOST"

/**
 * An `https` URI with a host and no embedded credentials, or null when [value] is not one.
 *
 * Shape only: it deliberately says nothing about *which* host serves the URL, so it can never
 * stand in for the credential binding [validateSojuFileHostEndpoint] performs.
 */
internal fun httpsUploadUri(value: String?): java.net.URI? =
    runCatching {
        val uri = java.net.URI(value?.trim()?.takeIf(String::isNotBlank) ?: return null)
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.userInfo == null)
        uri
    }.getOrNull()

/**
 * How the `soju.im/FILEHOST` endpoint a network advertises resolved against its configured
 * credential authorities.
 */
sealed interface SojuFileHostEndpoint {
    /** Advertised, well formed, and bound to the network host or exact VLESS ingress. */
    data class Usable(
        val url: String,
    ) : SojuFileHostEndpoint

    /**
     * Advertised and well formed, but served outside every configured credential authority.
     *
     * An upload authenticates with the network's SASL credential, so following this endpoint would
     * forward that credential to a third-party host the server merely named — with no pin and no
     * consent. Refused instead.
     */
    data class OffHost(
        val advertisedHost: String,
        val networkHost: String,
    ) : SojuFileHostEndpoint

    /** Not advertised at all, or not a usable https URL. */
    data object Unavailable : SojuFileHostEndpoint
}

/**
 * Whether [isupport] advertises a well-formed file host at all, whichever host serves it.
 *
 * Drives the upload sheet's offer only. The binding to the connected network's host is enforced on
 * the upload path itself, where the credential is attached, so a misconfigured or hostile
 * advertisement is refused with an explanation instead of silently disappearing from the sheet.
 */
fun sojuFileHostAdvertised(isupport: Map<String, String>): Boolean = httpsUploadUri(isupport[SOJU_FILEHOST_TOKEN]) != null

fun sojuFileHostEndpoint(
    isupport: Map<String, String>,
    networkHost: String,
    vlessIngressHost: String? = null,
    trustedFileHost: String? = null,
): SojuFileHostEndpoint = validateSojuFileHostEndpoint(isupport[SOJU_FILEHOST_TOKEN], networkHost, vlessIngressHost, trustedFileHost)

/**
 * Resolve an advertised file-host endpoint against [networkHost], the host of the IRC endpoint the
 * upload's credential belongs to, [vlessIngressHost], its user-configured VLESS ingress, or the
 * selected network's [trustedFileHost].
 *
 * The host must match or be a subdomain of [networkHost], or exactly match [vlessIngressHost],
 * case-insensitively. This permits a network owner to isolate uploads at e.g.
 * `files.irc.example` without treating every host below a third-party VLESS provider as trusted.
 * [trustedFileHost] is also exact: it never grants its subdomains authority.
 * **Ports must not** match: soju commonly serves IRC and uploads on separate ports.
 */
fun validateSojuFileHostEndpoint(
    value: String?,
    networkHost: String,
    vlessIngressHost: String? = null,
    trustedFileHost: String? = null,
): SojuFileHostEndpoint {
    val uri = httpsUploadUri(value) ?: return SojuFileHostEndpoint.Unavailable
    val advertised = uri.host.trimEnd('.')
    val expected = networkHost.trim().trimEnd('.')
    val tunnel = vlessIngressHost?.trim()?.trimEnd('.').orEmpty()
    val trusted = normalizeTrustedFileHost(trustedFileHost).orEmpty()
    // Fails closed when no configured authority owns the advertised DNS namespace.
    return if (
        (
            expected.isNotEmpty() &&
                (advertised.equals(expected, ignoreCase = true) || advertised.endsWith(".$expected", ignoreCase = true))
        ) ||
        (tunnel.isNotEmpty() && advertised.equals(tunnel, ignoreCase = true)) ||
        (trusted.isNotEmpty() && advertised.equals(trusted, ignoreCase = true))
    ) {
        SojuFileHostEndpoint.Usable(uri.toString())
    } else {
        SojuFileHostEndpoint.OffHost(advertisedHost = uri.host, networkHost = expected)
    }
}

/** Normalize a user-entered FILEHOST authority, accepting only a bare ASCII DNS hostname. */
fun normalizeTrustedFileHost(value: String?): String? {
    val entered = value?.trim().orEmpty()
    if (entered.endsWith("..")) return null
    val host = entered.removeSuffix(".").lowercase(Locale.ROOT)
    if (host.isEmpty() || host.length > 253 || host.any { it.code !in 0x21..0x7e }) return null
    val labels = host.split('.')
    if (labels.any { label ->
            label.isEmpty() || label.length > 63 ||
                !label.first().isLetterOrDigit() || !label.last().isLetterOrDigit() ||
                label.any { !it.isLetterOrDigit() && it != '-' }
        }
    ) {
        return null
    }
    // Numeric dotted quads are IP addresses, not DNS hostnames, and cannot be trusted here.
    if (labels.size == 4 && labels.all { it.all(Char::isDigit) }) return null
    return host
}

fun normalizedConfig(config: PasteBackendConfig): PasteBackendConfig {
    val customEndpoint =
        validateEndpoint(config.customEndpoint)
            ?: validateEndpoint(config.endpoint)
            ?: AttachmentBackend.CRAFTERBIN.endpoint!!
    val backend = config.backend.takeUnless { it == AttachmentBackend.ZERO_X_ZERO } ?: AttachmentBackend.CRAFTERBIN
    val endpoint = backend.endpoint ?: customEndpoint
    val maximum = backendMaxBytes(backend)
    return config.copy(
        backend = backend,
        endpoint = endpoint,
        customEndpoint = customEndpoint,
        expiry = normalizedExpiry(config.expiry),
        litterboxExpiry =
            config.litterboxExpiry.takeIf(LITTERBOX_EXPIRIES::contains)
                ?: DEFAULT_LITTERBOX_EXPIRY,
        sizeLimitBytes = config.sizeLimitBytes.coerceIn(1, maximum),
    )
}

private fun normalizedExpiry(value: String?): String? {
    val expiry = value?.trim()?.takeIf(String::isNotBlank) ?: return null
    val amount = expiry.dropLast(1).toLongOrNull() ?: return expiry
    return when (expiry.last().lowercaseChar()) {
        'd' -> runCatching { Math.multiplyExact(amount, 24L).toString() }.getOrDefault(expiry)
        'h' -> amount.toString()
        else -> expiry
    }
}

fun PasteBackendConfig.forBackend(backend: AttachmentBackend): PasteBackendConfig = normalizedConfig(copy(backend = backend, endpoint = backend.endpoint ?: customEndpoint))

fun AttachmentBackend.supports(source: AttachmentSource): Boolean = source is AttachmentSource.Text || acceptsBinary
