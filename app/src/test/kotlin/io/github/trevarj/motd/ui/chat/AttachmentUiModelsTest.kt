package io.github.trevarj.motd.ui.chat

import android.net.Uri
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.AttachmentBackend
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
        assertEquals(AttachmentBackend.entries.map { it.label }, options.map { it.label })
    }

    @Test fun filesNeverOfferTermbin() {
        val source = AttachmentSource.Document(Uri.EMPTY, "file.bin", null, null)
        assertFalse(uploadDestinations(source, PasteBackendConfig()).any { it.label == "Termbin" })
    }

    @Test fun configuredCustomEndpointIsAvailable() {
        val options = uploadDestinations(
            AttachmentSource.Text("hello"),
            PasteBackendConfig(
                backend = AttachmentBackend.CUSTOM_0X0,
                endpoint = "https://paste.example",
                customEndpoint = "https://paste.example",
            ),
        )
        assertTrue(options.any {
            it.config.backend == AttachmentBackend.CUSTOM_0X0 &&
                it.config.endpoint == "https://paste.example"
        })
        assertTrue(options.any { it.config.backend == AttachmentBackend.CRAFTERBIN })
    }

    @Test fun backendRetentionReflectsServicePolicy() {
        assertEquals("3 hours", backendRetention(PasteBackendConfig(backend = AttachmentBackend.UGUU)))
        assertEquals("24 hours", backendRetention(PasteBackendConfig(backend = AttachmentBackend.LITTERBOX)))
        assertEquals("rolling 180 days", backendRetention(PasteBackendConfig(backend = AttachmentBackend.CNET)))
    }

    @Test fun byteFormattingUsesReadableUnits() {
        assertEquals("900 B", formatBytes(900))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("2.0 MiB", formatBytes(2L * 1024 * 1024))
    }
}
