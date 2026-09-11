package io.github.trevarj.motd.audio

import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploadContext
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.normalizedConfig
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.dickord.isDickordPortalConversation
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.onEach
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class VoiceSendRequest(
    val bufferId: Long,
    val file: File,
    val durationMs: Long,
    val mimeType: String,
    val extension: String,
    val sizeBytes: Long,
    val waveform: AudioWaveform = AudioWaveform.EMPTY,
    val encrypt: Boolean,
    val destination: PasteBackendConfig? = null,
)

sealed interface VoiceSendProgress {
    data class Uploading(
        val bytesSent: Long,
        val totalBytes: Long?,
    ) : VoiceSendProgress

    data class Complete(
        val url: String,
    ) : VoiceSendProgress
}

interface VoiceMessageSender {
    fun send(request: VoiceSendRequest): Flow<VoiceSendProgress>
}

@Singleton
class VoiceMessageSenderImpl
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val connectionManager: ConnectionManager,
        private val attachmentPrefs: AttachmentPrefs,
        private val attachmentUploader: AttachmentUploader,
        private val crypto: VoiceCrypto,
    ) : VoiceMessageSender {
        override fun send(request: VoiceSendRequest): Flow<VoiceSendProgress> =
            channelFlow {
                val buffer =
                    db.bufferDao().observeById(request.bufferId)
                        ?: throw VoiceSendException("Conversation no longer exists.")
                val delivery = request.forConversation(buffer.type, buffer.displayName)
                val encrypted = if (delivery.encrypt) crypto.encrypt(request.file) else null
                val uploadFile = encrypted?.file ?: request.file
                val uploadMime = encrypted?.mimeType ?: request.mimeType
                val uploadName = voiceFileName(request, encrypted != null)
                val record =
                    try {
                        uploadVoiceFile(
                            networkId = buffer.networkId,
                            file = uploadFile,
                            name = uploadName,
                            mimeType = uploadMime,
                            sizeBytes = uploadFile.length(),
                            destination = delivery.destination,
                            progress = { sent, total -> send(VoiceSendProgress.Uploading(sent, total)) },
                        )
                    } finally {
                        encrypted?.file?.delete()
                    }
                val baseWireUrl =
                    if (encrypted != null) {
                        "${record.url}#${encrypted.keyFragment}"
                    } else {
                        record.url
                    }
                val waveformUrl = appendAudioWaveform(baseWireUrl, request.waveform)
                val waveformText =
                    voiceFallback(
                        durationMs = request.durationMs,
                        mimeType = request.mimeType,
                        url = waveformUrl,
                        encrypted = encrypted != null,
                        expiry = record.expiry,
                    )
                val wireUrl = if (wireBytes(buffer.name, waveformText) <= MAX_IRC_WIRE_BYTES) waveformUrl else baseWireUrl
                val wireText =
                    if (wireUrl == waveformUrl) {
                        waveformText
                    } else {
                        voiceFallback(
                            durationMs = request.durationMs,
                            mimeType = request.mimeType,
                            url = wireUrl,
                            encrypted = encrypted != null,
                            expiry = record.expiry,
                        )
                    }
                when (val acceptance = connectionManager.sendMessage(request.bufferId, wireText)) {
                    is SendAcceptance.Accepted -> send(VoiceSendProgress.Complete(wireUrl))

                    is SendAcceptance.Rejected -> throw VoiceSendException(
                        "Upload finished, but IRC rejected the message (${acceptance.reason.name.lowercase()}).",
                    )
                }
            }

        private suspend fun uploadVoiceFile(
            networkId: Long,
            file: File,
            name: String,
            mimeType: String,
            sizeBytes: Long,
            destination: PasteBackendConfig?,
            progress: suspend (Long, Long?) -> Unit,
        ): VoiceUploadRecord {
            val selected =
                destination?.let(::normalizedConfig)
                    ?: normalizedConfig(PasteBackendConfig(backend = AttachmentBackend.SOJU_FILEHOST))
            val source = AttachmentSource.LocalFile(file, name, mimeType, sizeBytes)
            val result =
                try {
                    attachmentUploader
                        .upload(source, selected, AttachmentUploadContext(networkId))
                        .onEach { update ->
                            if (update is UploadProgress.Transferring) progress(update.bytesSent, update.totalBytes)
                        }.last()
                } catch (error: IOException) {
                    val prefix =
                        if (selected.backend == AttachmentBackend.SOJU_FILEHOST) {
                            "Could not upload to the Soju file host"
                        } else {
                            "Could not upload the voice message"
                        }
                    throw VoiceSendException("$prefix: ${error.message ?: "connection failed"}.", error)
                }
            val record =
                (result as? UploadProgress.Complete)?.record
                    ?: throw VoiceSendException("Upload did not complete.")
            attachmentPrefs.addUpload(record)
            return VoiceUploadRecord(record.url, voiceExpiryFor(selected, record.uploadedAt))
        }

        private data class VoiceUploadRecord(
            val url: String,
            val expiry: String?,
        )

        private fun voiceFileName(
            request: VoiceSendRequest,
            encrypted: Boolean,
        ): String =
            if (encrypted) {
                "voice-${UUID.randomUUID()}.motdvoice"
            } else {
                "voice-${UUID.randomUUID()}${request.extension}"
            }

        private fun voiceFallback(
            durationMs: Long,
            mimeType: String,
            url: String,
            encrypted: Boolean,
            expiry: String?,
        ): String =
            buildString {
                append("[voice ")
                if (encrypted) append("encrypted ")
                append(formatAudioDuration(durationMs))
                append(' ')
                append(mimeType)
                expiry?.takeIf(String::isNotBlank)?.let {
                    append(" expires=")
                    append(it)
                }
                append("] ")
                append(url)
            }

        private fun wireBytes(
            target: String,
            text: String,
        ): Int = "PRIVMSG $target :$text\r\n".toByteArray(StandardCharsets.UTF_8).size

        private companion object {
            const val MAX_IRC_WIRE_BYTES = 480
        }
    }

internal fun VoiceSendRequest.forConversation(
    type: io.github.trevarj.motd.data.db.BufferType,
    displayName: String,
): VoiceSendRequest =
    if (isDickordPortalConversation(type, displayName)) {
        copy(
            encrypt = false,
            destination = PasteBackendConfig(backend = AttachmentBackend.SOJU_FILEHOST),
        )
    } else {
        this
    }

internal fun voiceExpiryFor(
    config: PasteBackendConfig,
    uploadedAt: Long,
): String? {
    val hours =
        when (config.backend) {
            AttachmentBackend.UGUU -> {
                3L
            }

            AttachmentBackend.LITTERBOX -> {
                when (config.litterboxExpiry) {
                    "1h" -> 1L
                    "12h" -> 12L
                    "24h" -> 24L
                    "72h" -> 72L
                    else -> null
                }
            }

            else -> {
                null
            }
        } ?: return null
    return try {
        Instant.ofEpochMilli(Math.addExact(uploadedAt, Math.multiplyExact(hours, 3_600_000L))).toString()
    } catch (_: ArithmeticException) {
        null
    }
}

class VoiceSendException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)
