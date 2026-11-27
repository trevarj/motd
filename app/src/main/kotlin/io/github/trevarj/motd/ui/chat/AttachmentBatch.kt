package io.github.trevarj.motd.ui.chat

import androidx.annotation.StringRes
import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploadContext
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.attachment.forBackend
import io.github.trevarj.motd.attachment.httpsUploadUri
import io.github.trevarj.motd.attachment.normalizedConfig
import io.github.trevarj.motd.attachment.validateEndpoint
import io.github.trevarj.motd.ui.share.PendingShare

internal data class AttachmentBatchRequest(
    val id: String,
    val bufferId: Long,
    val files: List<PendingShare.File>,
) {
    val binarySource = AttachmentSource.Document(files.first().uri, "", null, null)
}

internal data class AttachmentBatchTarget(
    val networkId: Long?,
    val sojuFileHostUrl: String?,
    val preferSojuFileHost: Boolean,
) {
    fun destination(
        request: AttachmentBatchRequest,
        configured: PasteBackendConfig,
        selected: AttachmentBackend?,
    ): AttachmentBatchDestination {
        val config =
            selected?.let(configured::forBackend)
                ?: normalizedConfig(preferredUploadConfig(request.binarySource, configured, sojuFileHostUrl != null, preferSojuFileHost))
        return AttachmentBatchDestination(
            config,
            AttachmentUploadContext(networkId, expectedSojuFileHostUrl = sojuFileHostUrl.takeIf { config.backend == AttachmentBackend.SOJU_FILEHOST }),
        )
    }
}

internal data class AttachmentBatchDestination(
    val config: PasteBackendConfig,
    val context: AttachmentUploadContext,
) {
    val endpoint: String? =
        if (config.backend == AttachmentBackend.SOJU_FILEHOST) {
            context.expectedSojuFileHostUrl?.takeIf { context.networkId != null && httpsUploadUri(it) != null }
        } else {
            validateEndpoint(config.endpoint)
        }
}

internal data class AttachmentBatchReview(
    val request: AttachmentBatchRequest,
    val target: AttachmentBatchTarget,
    val rows: List<AttachmentBatchRow>,
    val settings: PasteBackendConfig? = null,
    val selected: AttachmentBackend? = null,
    val destination: AttachmentBatchDestination? = null,
    val busy: Boolean = false,
    val started: Boolean = false,
    val stopping: Boolean = false,
    val destinationChanged: Boolean = false,
    val finishing: Boolean = false,
    val finishError: Boolean = false,
) {
    val hasUploads: Boolean get() = rows.any { it.status is AttachmentBatchStatus.Uploaded }
}

internal data class AttachmentBatchRow(
    val name: String,
    val source: AttachmentSource.Document? = null,
    val status: AttachmentBatchStatus = AttachmentBatchStatus.Checking,
    val reviewedDigest: ByteArray? = null,
) {
    fun isUploadable(destination: AttachmentBatchDestination): Boolean = destination.endpoint != null && !needsCheck(destination)

    fun needsCheck(destination: AttachmentBatchDestination?): Boolean = source == null || reviewedDigest == null || (destination != null && isOversized(destination))

    fun isOversized(destination: AttachmentBatchDestination): Boolean = (source?.size ?: 0L) > destination.config.sizeLimitBytes
}

internal sealed interface AttachmentBatchStatus {
    data object Checking : AttachmentBatchStatus

    data object Ready : AttachmentBatchStatus

    data class Uploading(
        val progress: UploadProgress.Transferring,
    ) : AttachmentBatchStatus

    data class Uploaded(
        val record: UploadRecord,
    ) : AttachmentBatchStatus

    data class Failed(
        @StringRes val reason: Int = R.string.upload_failed,
        val details: String? = null,
        val needsOriginalConsent: Boolean = false,
    ) : AttachmentBatchStatus
}
