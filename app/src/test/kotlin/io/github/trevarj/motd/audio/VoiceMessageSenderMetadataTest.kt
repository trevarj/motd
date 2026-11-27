package io.github.trevarj.motd.audio

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentPrefs
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentUploadContext
import io.github.trevarj.motd.attachment.AttachmentUploader
import io.github.trevarj.motd.attachment.LocalUploadPreview
import io.github.trevarj.motd.attachment.MetadataNotSupportedException
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.UploadMetadataSanitizer
import io.github.trevarj.motd.attachment.UploadProgress
import io.github.trevarj.motd.attachment.UploadRecord
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.testing.NoopConnectionManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoiceMessageSenderMetadataTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun encryptedPlaintextUsesCapturedGlobalPolicyAndEnvelopeIsOpaque() =
        runBlocking {
            for (strip in listOf(true, false)) {
                withSender(strip) { sender, uploader, prefs, crypto, bufferId ->
                    val original = taggedAudio()
                    val before = original.readBytes()
                    val result = sender.send(request(bufferId, original, encrypt = true, stalePolicy = !strip)).last() as VoiceSendProgress.Complete
                    assertFalse(uploader.selected!!.stripMetadata)
                    assertFalse(uploader.uploadContext!!.allowOriginalMetadata)
                    val encrypted = temporary.newFile().apply { writeBytes(uploader.bytes!!) }
                    val decrypted = crypto.decrypt(encrypted, result.url.substringAfter('#'), temporary.newFile())
                    if (strip) {
                        assertFalse(decrypted.readText(Charsets.ISO_8859_1).contains("private-location"))
                        assertEquals("OggS", decrypted.readBytes().copyOfRange(0, 4).toString(Charsets.US_ASCII))
                        assertEquals(audioPacket.toList(), decrypted.readBytes().takeLast(audioPacket.size))
                    } else {
                        assertArrayEquals(before, decrypted.readBytes())
                    }
                    assertArrayEquals(before, original.readBytes())
                    assertEquals(1, prefs.reads)
                    assertEquals(!strip, prefs.current.stripMetadata)
                }
            }
        }

    @Test fun unencryptedUploadUsesSameSnapshotRatherThanStaleDestinationOrLaterPreference() =
        runBlocking {
            for (strip in listOf(true, false)) {
                withSender(strip) { sender, uploader, prefs, _, bufferId ->
                    val original = taggedAudio()
                    sender.send(request(bufferId, original, encrypt = false, stalePolicy = !strip)).last()
                    assertEquals(strip, uploader.selected!!.stripMetadata)
                    assertEquals(AttachmentBackend.CUSTOM_0X0, uploader.selected!!.backend)
                    assertEquals("https://uploads.example/upload", uploader.selected!!.customEndpoint)
                    if (strip) {
                        assertFalse(uploader.bytes!!.toString(Charsets.ISO_8859_1).contains("private-location"))
                    } else {
                        assertArrayEquals(original.readBytes(), uploader.bytes)
                    }
                    assertEquals(1, prefs.reads)
                }
            }
        }

    @Test fun malformedAudioIsOnlyAllowedWhenStrippingIsDisabled() =
        runBlocking {
            for (encrypt in listOf(true, false)) {
                for (strip in listOf(true, false)) {
                    withSender(strip) { sender, uploader, _, crypto, bufferId ->
                        val original = temporary.newFile().apply { writeText("OggSbroken-private-location") }
                        if (strip) {
                            try {
                                sender.send(request(bufferId, original, encrypt, stalePolicy = false)).last()
                                fail("Malformed audio fell back to original")
                            } catch (_: java.io.IOException) {
                                assertNull(uploader.bytes)
                            }
                        } else {
                            val result = sender.send(request(bufferId, original, encrypt, stalePolicy = true)).last() as VoiceSendProgress.Complete
                            val uploaded = temporary.newFile().apply { writeBytes(uploader.bytes!!) }
                            val plaintext =
                                if (encrypt) crypto.decrypt(uploaded, result.url.substringAfter('#'), temporary.newFile()) else uploaded
                            assertArrayEquals(original.readBytes(), plaintext.readBytes())
                        }
                    }
                }
            }
        }

    @Test fun strippingFailureNeverEncryptsOrUploadsOriginal() =
        runBlocking {
            for (encrypt in listOf(true, false)) {
                withSender(true) { sender, uploader, _, _, bufferId ->
                    val original = temporary.newFile().apply { writeText("%PDF-1.7 /Author(private-location)") }
                    try {
                        sender.send(request(bufferId, original, encrypt, stalePolicy = false)).last()
                        fail("Unsupported plaintext was uploaded")
                    } catch (_: MetadataNotSupportedException) {
                        assertEquals(true, encrypt)
                        assertNull(uploader.bytes)
                    } catch (failure: VoiceSendException) {
                        assertFalse(encrypt)
                        assertEquals(MetadataNotSupportedException::class.java, generateSequence<Throwable>(failure) { it.cause }.last().javaClass)
                        assertNull(uploader.bytes)
                    }
                }
            }
        }

    private fun request(
        bufferId: Long,
        file: File,
        encrypt: Boolean,
        stalePolicy: Boolean,
    ) = VoiceSendRequest(
        bufferId = bufferId,
        file = file,
        durationMs = 1_000,
        mimeType = "audio/ogg",
        extension = ".ogg",
        sizeBytes = file.length(),
        encrypt = encrypt,
        destination = PasteBackendConfig(backend = AttachmentBackend.CUSTOM_0X0, customEndpoint = "https://uploads.example/upload", stripMetadata = stalePolicy),
    )

    private suspend fun withSender(
        strip: Boolean,
        body: suspend (VoiceMessageSenderImpl, InspectingUploader, SnapshotPrefs, VoiceCrypto, Long) -> Unit,
    ) {
        val cache = temporary.newFolder()
        val context =
            object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
                override fun getCacheDir(): File = cache
            }
        val db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        try {
            val networkId = db.networkDao().insert(NetworkEntity(name = "test", role = NetworkRole.DIRECT, host = "irc.example", port = 6697, nick = "me", username = "me", realname = "Me"))
            val bufferId = db.bufferDao().insert(BufferEntity(networkId = networkId, name = "peer", displayName = "peer", type = BufferType.QUERY))
            val sanitizer = UploadMetadataSanitizer(context)
            val uploader = InspectingUploader(sanitizer)
            val prefs = SnapshotPrefs(strip)
            val crypto = VoiceCrypto(AudioCacheStore(context, AudioMediaCache(context), AudioWaveformRepository(context)))
            body(VoiceMessageSenderImpl(db, NoopConnectionManager(), prefs, uploader, crypto, sanitizer), uploader, prefs, crypto, bufferId)
        } finally {
            db.close()
        }
    }

    private class SnapshotPrefs(
        strip: Boolean,
    ) : AttachmentPrefs {
        var current = PasteBackendConfig(stripMetadata = strip)
        var reads = 0
        override val config: Flow<PasteBackendConfig> =
            flow {
                reads++
                try {
                    emit(current)
                } finally {
                    current = current.copy(stripMetadata = !current.stripMetadata)
                }
            }
        override val recentUploads = flowOf(emptyList<UploadRecord>())

        override suspend fun setConfig(config: PasteBackendConfig) {
            current = config
        }

        override suspend fun updateConfig(transform: (PasteBackendConfig) -> PasteBackendConfig) {
            current = transform(current)
        }

        override suspend fun addUpload(record: UploadRecord) = Unit

        override suspend fun setUploadFavorite(
            url: String,
            favorite: Boolean,
        ) = Unit

        override suspend fun updateUploadPreview(
            record: UploadRecord,
            preview: LocalUploadPreview,
        ) = Unit

        override suspend fun removeUpload(url: String) = Unit

        override suspend fun clearUploadHistory() = Unit
    }

    private class InspectingUploader(
        private val sanitizer: UploadMetadataSanitizer,
    ) : AttachmentUploader {
        var selected: PasteBackendConfig? = null
        var uploadContext: AttachmentUploadContext? = null
        var bytes: ByteArray? = null

        override fun upload(
            source: AttachmentSource,
            config: PasteBackendConfig,
            context: AttachmentUploadContext,
        ): Flow<UploadProgress> =
            flow {
                selected = config
                uploadContext = context
                sanitizer.prepare(source, config.sizeLimitBytes, allowOriginal = context.allowOriginalMetadata, stripMetadata = config.stripMetadata).use {
                    val staged = it.source as AttachmentSource.LocalFile
                    bytes = staged.file.readBytes()
                    emit(UploadProgress.Complete(UploadRecord("https://uploads.example/voice", config.backend, staged.name)))
                }
            }

        override suspend fun delete(record: UploadRecord) = Unit
    }

    private val audioPacket = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())

    private fun taggedAudio(): File {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0, 0) + little(48000) + byteArrayOf(0, 0, 0)
        val vendor = "private-location".toByteArray()
        val tags = "OpusTags".toByteArray() + little(vendor.size) + vendor + little(0)
        return temporary.newFile().apply {
            writeBytes(oggPage(0, 2, 0, head) + oggPage(1, 0, 0, tags) + oggPage(2, 4, 960, audioPacket))
        }
    }

    private fun little(value: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()

    private fun oggPage(
        sequence: Int,
        flags: Int,
        granule: Long,
        packet: ByteArray,
    ): ByteArray {
        val bytes =
            ByteBuffer
                .allocate(28 + packet.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .put("OggS".toByteArray())
                .put(0.toByte())
                .put(flags.toByte())
                .putLong(granule)
                .putInt(1234)
                .putInt(sequence)
                .putInt(0)
                .put(1.toByte())
                .put(packet.size.toByte())
                .put(packet)
                .array()
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 255) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04c11db7 else crc shl 1 }
        }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc)
        return bytes
    }
}
