package io.github.trevarj.motd.ui.chat

import android.net.Uri
import io.github.trevarj.motd.attachment.AVAILABLE_ATTACHMENT_BACKENDS
import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.PasteBackendConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AttachmentUiModelsTest {
    @Test fun textOffersTermbinAndCompatibleDestinations() {
        val options = uploadDestinations(AttachmentSource.Text("hello"), PasteBackendConfig())
        assertEquals(
            AVAILABLE_ATTACHMENT_BACKENDS.filterNot { it == AttachmentBackend.SOJU_FILEHOST }.map { it.label },
            options.map { it.label },
        )
    }

    @Test fun sojuFileHostIsOnlyOfferedWhenAdvertised() {
        val source = AttachmentSource.Text("hello")
        assertFalse(
            uploadDestinations(source, PasteBackendConfig()).any {
                it.config.backend == AttachmentBackend.SOJU_FILEHOST
            },
        )
        assertTrue(
            uploadDestinations(source, PasteBackendConfig(), sojuFileHostAvailable = true).any {
                it.config.backend == AttachmentBackend.SOJU_FILEHOST
            },
        )
    }

    @Test fun filesNeverOfferTermbin() {
        val source = AttachmentSource.Document(Uri.EMPTY, "file.bin", null, null)
        assertFalse(uploadDestinations(source, PasteBackendConfig()).any { it.label == "Termbin" })
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

        listOf(photo, document).forEach { source ->
            assertEquals(
                AttachmentBackend.SOJU_FILEHOST,
                preferredUploadConfig(source, configured, sojuFileHostAvailable = true, preferSojuFileHost = true).backend,
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
