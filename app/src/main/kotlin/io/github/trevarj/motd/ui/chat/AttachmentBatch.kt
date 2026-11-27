package io.github.trevarj.motd.ui.chat

import androidx.annotation.StringRes
import io.github.trevarj.motd.R
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploadContext
import io.github.trevarj.motd.attachment.CompressedUploadVideo
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.attachment.forBackend
import io.github.trevarj.motd.attachment.guessMimeType
import io.github.trevarj.motd.attachment.httpsUploadUri
import io.github.trevarj.motd.attachment.isVideoCompressionInputUri
import io.github.trevarj.motd.attachment.normalizedConfig
import io.github.trevarj.motd.attachment.sizeOrNull
import io.github.trevarj.motd.attachment.validateEndpoint
import io.github.trevarj.motd.ui.share.PendingShare
import java.io.Closeable

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
    val hasCompression: Boolean get() = rows.any { it.compression != null }

    fun withoutCompression(): AttachmentBatchReview = if (hasCompression) copy(rows = rows.map(AttachmentBatchRow::withoutCompression)) else this
}

internal data class AttachmentBatchRow(
    val name: String,
    val source: AttachmentSource.Document? = null,
    val status: AttachmentBatchStatus = AttachmentBatchStatus.Checking,
    val reviewedDigest: ByteArray? = null,
    val compression: UploadVideoReview? = null,
    val originalSizeIsLowerBound: Boolean = false,
) {
    val compressedSource: AttachmentSource.LocalFile?
        get() = (compression as? UploadVideoReview.Ready)?.video?.source

    fun isUploadable(destination: AttachmentBatchDestination): Boolean = destination.endpoint != null && !needsCheck(destination)

    fun needsCheck(destination: AttachmentBatchDestination?): Boolean =
        source == null || reviewedDigest == null ||
            (destination != null && ((compressedSource ?: source).sizeOrNull() ?: 0L) > destination.config.sizeLimitBytes)

    fun isOversized(destination: AttachmentBatchDestination): Boolean = (source?.size ?: 0L) > destination.config.sizeLimitBytes

    fun canCompress(destination: AttachmentBatchDestination): Boolean =
        destination.endpoint != null && source?.canCompressVideo(destination.config.sizeLimitBytes) == true &&
            (status is AttachmentBatchStatus.Ready || status is AttachmentBatchStatus.Failed)

    fun closeCompression() {
        (compression as? UploadVideoReview.Ready)?.close()
    }

    fun withoutCompression(): AttachmentBatchRow {
        closeCompression()
        return if (compression == null) this else copy(compression = null, reviewedDigest = if (compressedSource == null) reviewedDigest else null)
    }
}

internal sealed interface UploadVideoReview {
    data object Working : UploadVideoReview

    data object Failed : UploadVideoReview

    class Ready(
        val video: CompressedUploadVideo,
    ) : UploadVideoReview,
        Closeable {
        private var reviewOpen = true
        private var owners = 1

        @Synchronized
        fun retain(): Closeable? {
            if (!reviewOpen) return null
            owners++
            return Closeable(::release)
        }

        @Synchronized
        override fun close() {
            if (!reviewOpen) return
            reviewOpen = false
            release()
        }

        @Synchronized
        private fun release() {
            if (--owners == 0) video.close()
        }
    }
}

internal fun AttachmentSource.canCompressVideo(limitBytes: Long): Boolean = (sizeOrNull() ?: 0L) > limitBytes && isLocalUploadVideo()

internal fun AttachmentSource.isLocalUploadVideo(): Boolean {
    val mime =
        when (this) {
            is AttachmentSource.Document -> {
                if (!uri.isVideoCompressionInputUri()) return false
                mimeType.takeUnless { it.isNullOrBlank() || it.equals("application/octet-stream", ignoreCase = true) } ?: guessMimeType(name)
            }

            is AttachmentSource.LocalFile -> {
                mimeType
            }

            else -> {
                return false
            }
        }
    return mime.startsWith("video/", ignoreCase = true)
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
