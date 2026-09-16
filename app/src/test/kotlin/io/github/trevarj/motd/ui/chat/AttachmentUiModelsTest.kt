package io.github.trevarj.motd.ui.chat

import android.net.Uri
import io.github.trevarj.motd.attachment.AVAILABLE_ATTACHMENT_BACKENDS
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.attachment.forBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AttachmentUiModelsTest {
    @Test fun everySourceOffersEachCompatibleDestinationExactlyOnce() {
        val sources =
            listOf(
                AttachmentSource.Text("hello"),
                AttachmentSource.Photo(Uri.EMPTY, "photo.jpg", "image/jpeg", 1),
                AttachmentSource.Document(Uri.EMPTY, "file.bin", "application/octet-stream", 1),
                AttachmentSource.LocalFile(File("capture.jpg"), "capture.jpg", "image/jpeg", 1),
                AttachmentSource.LocalFile(File("voice.ogg"), "voice.ogg", "audio/ogg", 1),
            )
        for (source in sources) {
            for (sojuAvailable in listOf(false, true)) {
                val expected =
                    AVAILABLE_ATTACHMENT_BACKENDS.filterNot {
                        it == AttachmentBackend.SOJU_FILEHOST && !sojuAvailable ||
                            it == AttachmentBackend.TERMBIN && source !is AttachmentSource.Text
                    }
                val offered = uploadDestinations(source, PasteBackendConfig(), sojuAvailable).map { it.config.backend }
                assertEquals(
                    "$source, sojuAvailable=$sojuAvailable",
                    expected.associateWith { 1 },
                    offered.groupingBy { it }.eachCount(),
                )
            }
        }
    }

    @Test fun configuredCustomEndpointIsAvailable() {
        val options =
            uploadDestinations(
                AttachmentSource.Text("hello"),
                PasteBackendConfig(
                    backend = AttachmentBackend.CUSTOM_0X0,
                    endpoint = "https://paste.example",
                    customEndpoint = "https://paste.example",
                ),
            )
        assertTrue(
            options.any {
                it.config.backend == AttachmentBackend.CUSTOM_0X0 &&
                    it.config.endpoint == "https://paste.example"
            },
        )
        assertTrue(options.any { it.config.backend == AttachmentBackend.CRAFTERBIN })
    }

    @Test fun backendRetentionReflectsServicePolicy() {
        assertEquals("3 hours", backendRetention(PasteBackendConfig(backend = AttachmentBackend.UGUU)))
        assertEquals("24 hours", backendRetention(PasteBackendConfig(backend = AttachmentBackend.LITTERBOX)))
        assertEquals("rolling 180 days", backendRetention(PasteBackendConfig(backend = AttachmentBackend.CNET)))
        assertEquals("3–100 days by size", backendRetention(PasteBackendConfig(backend = AttachmentBackend.X0_AT)))
        assertEquals("server policy", backendRetention(PasteBackendConfig(backend = AttachmentBackend.SOJU_FILEHOST)))
    }

    @Test fun dickordBinaryMediaPrefersAdvertisedFileHostOnly() {
        val configured = PasteBackendConfig(backend = AttachmentBackend.CRAFTERBIN)
        val photo = AttachmentSource.Photo(Uri.EMPTY, "photo.jpg", "image/jpeg", 1)
        val document = AttachmentSource.Document(Uri.EMPTY, "file.bin", "application/octet-stream", 1)
        val camera = AttachmentSource.LocalFile(File("capture.jpg"), "capture.jpg", "image/jpeg", 1)

        listOf(photo, document, camera).forEach { source ->
            assertEquals(
                AttachmentBackend.SOJU_FILEHOST,
                preferredUploadConfig(source, configured, sojuFileHostAvailable = true, preferSojuFileHost = true).backend,
            )
            assertEquals(
                AttachmentBackend.CRAFTERBIN,
                preferredUploadConfig(
                    source,
                    configured.forBackend(AttachmentBackend.TERMBIN),
                    sojuFileHostAvailable = false,
                    preferSojuFileHost = true,
                ).backend,
            )
        }
        assertEquals(
            configured,
            preferredUploadConfig(photo, configured, sojuFileHostAvailable = false, preferSojuFileHost = true),
        )
        assertEquals(
            configured,
            preferredUploadConfig(photo, configured, sojuFileHostAvailable = true, preferSojuFileHost = false),
        )
        assertEquals(
            configured,
            preferredUploadConfig(AttachmentSource.Text("paste"), configured, sojuFileHostAvailable = true, preferSojuFileHost = true),
        )
    }

    @Test fun byteFormattingUsesReadableUnits() {
        assertEquals("900 B", formatBytes(900))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("2.0 MiB", formatBytes(2L * 1024 * 1024))
    }
}
