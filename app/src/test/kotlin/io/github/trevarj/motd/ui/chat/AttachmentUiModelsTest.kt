package io.github.trevarj.motd.ui.chat

import android.net.Uri
import io.github.trevarj.motd.attachment.AttachmentSource
import io.github.trevarj.motd.attachment.EndpointPreset
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
        assertEquals(listOf("Termbin", "CrafterBin", "0x0.st"), options.map { it.label })
    }

    @Test fun filesNeverOfferTermbin() {
        val source = AttachmentSource.Document(Uri.EMPTY, "file.bin", null, null)
        assertFalse(uploadDestinations(source, PasteBackendConfig()).any { it.label == "Termbin" })
    }

    @Test fun configuredCustomEndpointIsAvailable() {
        val options = uploadDestinations(
            AttachmentSource.Text("hello"),
            PasteBackendConfig(endpoint = "https://paste.example"),
        )
        assertTrue(options.any { it.label == "Custom" && it.config.endpoint == "https://paste.example" })
        assertTrue(options.any { it.config.endpoint == EndpointPreset.CRAFTERBIN.endpoint })
    }

    @Test fun byteFormattingUsesReadableUnits() {
        assertEquals("900 B", formatBytes(900))
        assertEquals("1.5 KiB", formatBytes(1536))
        assertEquals("2.0 MiB", formatBytes(2L * 1024 * 1024))
    }
}
