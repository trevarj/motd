package io.github.trevarj.motd.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentModelsTest {
    @Test fun endpointPresetsAreHttps() {
        assertEquals("https://crafterbin.glennstack.dev", EndpointPreset.CRAFTERBIN.endpoint)
        assertEquals("https://0x0.st", EndpointPreset.ZERO_X_ZERO.endpoint)
    }

    @Test fun endpointValidationRejectsCredentialsAndHttp() {
        assertNull(validateEndpoint("http://example.com"))
        assertNull(validateEndpoint("https://user:pass@example.com"))
        assertEquals("https://paste.example/upload", validateEndpoint("https://paste.example/upload/"))
    }

    @Test fun publicLimitIsCappedAt25MiB() {
        val config = normalizedConfig(PasteBackendConfig(sizeLimitBytes = MAX_CUSTOM_LIMIT_BYTES))
        assertEquals(DEFAULT_PUBLIC_LIMIT_BYTES, config.sizeLimitBytes)
    }

    @Test fun customLimitAllows512MiB() {
        val config = normalizedConfig(PasteBackendConfig(endpoint = "https://paste.example", sizeLimitBytes = MAX_CUSTOM_LIMIT_BYTES))
        assertEquals(MAX_CUSTOM_LIMIT_BYTES, config.sizeLimitBytes)
    }

    @Test fun multipartEncodingIncludesOptionsAndSafeFilename() {
        val boundary = "test"
        assertTrue(MultipartEncoding.field(boundary, "expires", "7d").decodeToString().contains("name=\"expires\""))
        val header = MultipartEncoding.fileHeader(boundary, "bad\"\nname.txt", "text/plain").decodeToString()
        assertTrue(header.contains("filename=\"bad__name.txt\""))
        assertTrue(header.contains("Content-Type: text/plain"))
        assertTrue(MultipartEncoding.ending(boundary).decodeToString().endsWith("--test--\r\n"))
    }
}
