package io.github.trevarj.motd.audio

import io.github.trevarj.motd.attachment.AttachmentBackend
import io.github.trevarj.motd.attachment.PasteBackendConfig
import io.github.trevarj.motd.data.db.BufferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.Instant

class VoiceMessageSenderTest {
    private val uploadedAt = Instant.parse("2026-08-28T12:00:00.123Z").toEpochMilli()

    @Test fun fixedRetentionProducesExactUtcExpiry() {
        val expected =
            mapOf(
                PasteBackendConfig(backend = AttachmentBackend.UGUU) to "2026-08-28T15:00:00.123Z",
                litterbox("1h") to "2026-08-28T13:00:00.123Z",
                litterbox("12h") to "2026-08-29T00:00:00.123Z",
                litterbox("24h") to "2026-08-29T12:00:00.123Z",
                litterbox("72h") to "2026-08-31T12:00:00.123Z",
            )

        expected.forEach { (config, expiry) ->
            assertEquals(expiry, voiceExpiryFor(config, uploadedAt))
        }
    }

    @Test fun nonExactOrInvalidRetentionOmitsExpiry() {
        listOf(
            AttachmentBackend.CRAFTERBIN,
            AttachmentBackend.ZERO_X_ZERO,
            AttachmentBackend.CUSTOM_0X0,
            AttachmentBackend.X0_AT,
            AttachmentBackend.CNET,
            AttachmentBackend.CATBOX,
            AttachmentBackend.SOJU_FILEHOST,
            AttachmentBackend.TERMBIN,
        ).forEach { backend ->
            assertNull(voiceExpiryFor(PasteBackendConfig(backend = backend), uploadedAt))
        }
        listOf("", "3h", "bogus").forEach { expiry ->
            assertNull(voiceExpiryFor(litterbox(expiry), uploadedAt))
        }
        assertNull(voiceExpiryFor(PasteBackendConfig(backend = AttachmentBackend.UGUU), Long.MAX_VALUE))
    }

    @Test fun dickordConversationsForceNativeUnencryptedDelivery() {
        val request =
            VoiceSendRequest(
                bufferId = 7,
                file = java.io.File("voice.ogg"),
                durationMs = 1_000,
                mimeType = "audio/ogg",
                extension = ".ogg",
                sizeBytes = 1,
                encrypt = true,
                destination = PasteBackendConfig(backend = AttachmentBackend.CRAFTERBIN),
            )

        val resolved = request.forConversation(BufferType.CHANNEL, "#discord.me.chat.crispy")

        assertFalse(resolved.encrypt)
        assertEquals(AttachmentBackend.SOJU_FILEHOST, resolved.destination?.backend)
        assertSame(request, request.forConversation(BufferType.CHANNEL, "#discord.control"))
        assertSame(request, request.forConversation(BufferType.CHANNEL, "#ordinary"))
    }

    private fun litterbox(expiry: String) =
        PasteBackendConfig(
            backend = AttachmentBackend.LITTERBOX,
            litterboxExpiry = expiry,
        )
}
