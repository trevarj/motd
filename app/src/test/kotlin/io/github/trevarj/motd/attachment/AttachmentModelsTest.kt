package io.github.trevarj.motd.attachment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentModelsTest {
    @Test fun endpointPresetsAreHttps() {
        assertEquals("https://crafterbin.glennstack.dev", EndpointPreset.CRAFTERBIN.endpoint)
        assertEquals("https://0x0.st", EndpointPreset.ZERO_X_ZERO.endpoint)
    }

    @Test fun crafterBinRemainsTheWorkingDefault() {
        val config = PasteBackendConfig()
        assertEquals(AttachmentBackend.CRAFTERBIN, config.backend)
        assertEquals(EndpointPreset.CRAFTERBIN.endpoint, config.endpoint)
    }

    @Test fun curatedBackendsHaveExpectedProtocolsAndHttpsEndpoints() {
        val public = AttachmentBackend.entries.filter { it.endpoint != null }
        assertTrue(public.all { validateEndpoint(it.endpoint!!) != null })
        assertEquals(PasteProtocol.RAW_CNET, AttachmentBackend.CNET.protocol)
        assertEquals(PasteProtocol.MULTIPART_UGUU, AttachmentBackend.UGUU.protocol)
        assertEquals(PasteProtocol.MULTIPART_CATBOX, AttachmentBackend.LITTERBOX.protocol)
        assertEquals(PasteProtocol.SOJU_FILEHOST, AttachmentBackend.SOJU_FILEHOST.protocol)
    }

    @Test fun endpointValidationRejectsCredentialsAndHttp() {
        assertNull(validateEndpoint("http://example.com"))
        assertNull(validateEndpoint("https://user:pass@example.com"))
        assertEquals("https://paste.example/upload", validateEndpoint("https://paste.example/upload/"))
    }

    @Test fun sojuFileHostEndpointRequiresHttpsWithoutCredentials() {
        assertEquals(
            SojuFileHostEndpoint.Usable("https://irc.example/uploads/"),
            sojuFileHostEndpoint(mapOf(SOJU_FILEHOST_TOKEN to "https://irc.example/uploads/"), "irc.example"),
        )
        assertEquals(SojuFileHostEndpoint.Unavailable, sojuFileHostEndpoint(emptyMap(), "irc.example"))
        assertEquals(
            SojuFileHostEndpoint.Unavailable,
            sojuFileHostEndpoint(mapOf(SOJU_FILEHOST_TOKEN to "http://irc.example/uploads"), "irc.example"),
        )
        assertEquals(
            SojuFileHostEndpoint.Unavailable,
            sojuFileHostEndpoint(mapOf(SOJU_FILEHOST_TOKEN to "https://user:pass@irc.example/uploads"), "irc.example"),
        )
    }

    @Test fun sojuFileHostEndpointBindsToTheConnectedNetworkHost() {
        // Host comparison is case-insensitive; the advertised URL is otherwise untouched.
        assertEquals(
            SojuFileHostEndpoint.Usable("https://IRC.Example/uploads"),
            validateSojuFileHostEndpoint("https://IRC.Example/uploads", "irc.example"),
        )
        // soju serves IRC and the file host from one process on two ports, so the port must differ
        // freely — a port-sensitive check would break every real deployment.
        assertEquals(
            SojuFileHostEndpoint.Usable("https://irc.example:6696/uploads"),
            validateSojuFileHostEndpoint("https://irc.example:6696/uploads", "irc.example"),
        )
        // A dedicated upload host remains inside the connected host owner's DNS namespace.
        assertEquals(
            SojuFileHostEndpoint.Usable("https://files.irc.starlightnet.work/uploads"),
            validateSojuFileHostEndpoint("https://files.irc.starlightnet.work/uploads", "starlightnet.work"),
        )
        // VLESS may route to an internal Docker name while its user-configured public ingress owns
        // the advertised HTTPS file host.
        assertEquals(
            SojuFileHostEndpoint.Usable("https://edge.example/uploads"),
            validateSojuFileHostEndpoint("https://edge.example/uploads", "soju", "edge.example"),
        )
        assertEquals(
            SojuFileHostEndpoint.OffHost("files.edge.example", "soju"),
            validateSojuFileHostEndpoint("https://files.edge.example/uploads", "soju", "edge.example"),
        )
        // A server naming a third-party host is refused, and the advertised host is reported.
        assertEquals(
            SojuFileHostEndpoint.OffHost("evil.example", "irc.example"),
            validateSojuFileHostEndpoint("https://evil.example/uploads", "irc.example", "edge.example"),
        )
        // A sibling domain is outside the connected host's namespace.
        assertEquals(
            SojuFileHostEndpoint.OffHost("files.other.example", "irc.example"),
            validateSojuFileHostEndpoint("https://files.other.example/uploads", "irc.example"),
        )
        // A suffix without a label boundary must not impersonate a subdomain.
        assertEquals(
            SojuFileHostEndpoint.OffHost("evilirc.example", "irc.example"),
            validateSojuFileHostEndpoint("https://evilirc.example/uploads", "irc.example"),
        )
        // Fails closed when the network host is unknown rather than accepting anything.
        assertEquals(
            SojuFileHostEndpoint.OffHost("irc.example", ""),
            validateSojuFileHostEndpoint("https://irc.example/uploads", "  "),
        )
    }

    @Test fun trustedFileHostIsExactAndNormalized() {
        assertEquals("files.example", normalizeTrustedFileHost(" Files.Example. "))
        assertNull(normalizeTrustedFileHost("files.example.."))
        assertNull(normalizeTrustedFileHost("https://files.example"))
        assertNull(normalizeTrustedFileHost("files.example:443"))
        assertNull(normalizeTrustedFileHost("127.0.0.1"))
        assertEquals(
            SojuFileHostEndpoint.Usable("https://FILES.example/uploads"),
            validateSojuFileHostEndpoint("https://FILES.example/uploads", "irc.example", trustedFileHost = "files.example"),
        )
        assertEquals(
            SojuFileHostEndpoint.OffHost("cdn.files.example", "irc.example"),
            validateSojuFileHostEndpoint("https://cdn.files.example/uploads", "irc.example", trustedFileHost = "files.example"),
        )
    }

    @Test fun sojuFileHostAdvertisementDrivesTheOfferOnly() {
        assertTrue(sojuFileHostAdvertised(mapOf(SOJU_FILEHOST_TOKEN to "https://evil.example/uploads")))
        assertFalse(sojuFileHostAdvertised(emptyMap()))
        assertFalse(sojuFileHostAdvertised(mapOf(SOJU_FILEHOST_TOKEN to "http://irc.example/uploads")))
        assertFalse(sojuFileHostAdvertised(mapOf(SOJU_FILEHOST_TOKEN to "https://user:pass@irc.example/uploads")))
    }

    @Test fun offHostRefusalNamesTheAdvertisedHost() {
        val message = sojuOffHostMessage(SojuFileHostEndpoint.OffHost("evil.example", "irc.example"))
        assertTrue(message.contains("evil.example"))
        assertTrue(message.contains("irc.example"))
    }

    @Test fun sojuFileHostLocationResolvesRelativeHttpsUrls() {
        assertEquals(
            "https://irc.example/files/voice.ogg",
            resolveSojuLocation("https://irc.example/uploads/", "../files/voice.ogg"),
        )
        assertEquals(
            "https://cdn.example/voice.ogg",
            resolveSojuLocation("https://irc.example/uploads/", "https://cdn.example/voice.ogg"),
        )
    }

    @Test fun disabled0x0BackendFallsBackToCrafterbin() {
        assertFalse(AVAILABLE_ATTACHMENT_BACKENDS.contains(AttachmentBackend.ZERO_X_ZERO))
        val config = normalizedConfig(PasteBackendConfig(backend = AttachmentBackend.ZERO_X_ZERO))
        assertEquals(AttachmentBackend.CRAFTERBIN, config.backend)
        assertEquals(AttachmentBackend.CRAFTERBIN.endpoint, config.endpoint)
    }

    @Test fun publicLimitIsCappedAt25MiB() {
        val config =
            normalizedConfig(
                PasteBackendConfig(
                    backend = AttachmentBackend.CRAFTERBIN,
                    sizeLimitBytes = MAX_CUSTOM_LIMIT_BYTES,
                ),
            )
        assertEquals(DEFAULT_PUBLIC_LIMIT_BYTES, config.sizeLimitBytes)
    }

    @Test fun sojuFileHostUsesDefaultUploadCeiling() {
        val config =
            normalizedConfig(
                PasteBackendConfig(
                    backend = AttachmentBackend.SOJU_FILEHOST,
                    sizeLimitBytes = MAX_CUSTOM_LIMIT_BYTES,
                ),
            )
        assertEquals(DEFAULT_PUBLIC_LIMIT_BYTES, config.sizeLimitBytes)
        assertTrue(AttachmentBackend.SOJU_FILEHOST.acceptsBinary)
    }

    @Test fun customLimitAllows512MiB() {
        val config =
            normalizedConfig(
                PasteBackendConfig(
                    backend = AttachmentBackend.CUSTOM_0X0,
                    endpoint = "https://paste.example",
                    customEndpoint = "https://paste.example",
                    sizeLimitBytes = MAX_CUSTOM_LIMIT_BYTES,
                ),
            )
        assertEquals(MAX_CUSTOM_LIMIT_BYTES, config.sizeLimitBytes)
    }

    @Test fun x0atBackendUsesMultipart0x0AndOneGiBCeiling() {
        assertEquals(PasteProtocol.MULTIPART_0X0, AttachmentBackend.X0_AT.protocol)
        assertEquals("https://x0.at", AttachmentBackend.X0_AT.endpoint)
        assertTrue(AttachmentBackend.X0_AT.acceptsBinary)
        assertEquals(MAX_X0AT_LIMIT_BYTES, backendMaxBytes(AttachmentBackend.X0_AT))
        val capped =
            normalizedConfig(
                PasteBackendConfig(
                    backend = AttachmentBackend.X0_AT,
                    sizeLimitBytes = MAX_X0AT_LIMIT_BYTES,
                ),
            )
        assertEquals(MAX_X0AT_LIMIT_BYTES, capped.sizeLimitBytes)
        assertEquals("https://x0.at", capped.endpoint)
    }

    @Test fun durationSuffixesAreConvertedToProviderHours() {
        assertEquals("168", PasteBackendConfig().expiry)
        assertEquals("168", normalizedConfig(PasteBackendConfig(expiry = "7d")).expiry)
        assertEquals("24", normalizedConfig(PasteBackendConfig(expiry = "24h")).expiry)
        assertEquals("1735689600000", normalizedConfig(PasteBackendConfig(expiry = "1735689600000")).expiry)
    }

    @Test fun litterboxExpiryIsNormalized() {
        assertEquals(DEFAULT_LITTERBOX_EXPIRY, normalizedConfig(PasteBackendConfig(litterboxExpiry = "7d")).litterboxExpiry)
        assertEquals("72h", normalizedConfig(PasteBackendConfig(litterboxExpiry = "72h")).litterboxExpiry)
    }

    @Test fun legacyProtocolAndEndpointMapToStableBackend() {
        assertEquals(AttachmentBackend.TERMBIN, legacyAttachmentBackend("TERMBIN", null))
        assertEquals(
            AttachmentBackend.CRAFTERBIN,
            legacyAttachmentBackend("MULTIPART_0X0", EndpointPreset.CRAFTERBIN.endpoint),
        )
        assertEquals(
            AttachmentBackend.CUSTOM_0X0,
            legacyAttachmentBackend("MULTIPART_0X0", "https://paste.example"),
        )
    }

    @Test fun multipartEncodingIncludesOptionsAndSafeFilename() {
        val boundary = "test"
        assertTrue(MultipartEncoding.field(boundary, "expires", "7d").decodeToString().contains("name=\"expires\""))
        val header = MultipartEncoding.fileHeader(boundary, "files[]", "bad\"\nname.txt", "text/plain").decodeToString()
        assertTrue(header.contains("name=\"files[]\""))
        assertTrue(header.contains("filename=\"bad__name.txt\""))
        assertTrue(header.contains("Content-Type: text/plain"))
        assertTrue(MultipartEncoding.ending(boundary).decodeToString().endsWith("--test--\r\n"))
    }

    @Test fun backendResponsesParseUrlsAndDeletionKeys() {
        assertEquals(
            "https://uguu.se/file.txt",
            BackendResponses.uguu("""{"files":[{"url":"https://uguu.se/file.txt"}]}"""),
        )
        assertEquals(
            "https://paste.c-net.org/id" to "delete-me",
            BackendResponses.cnet("""{"url":"https://paste.c-net.org/id","delete_key":"delete-me"}"""),
        )
        assertEquals("https://files.catbox.moe/a.png", BackendResponses.plain("https://files.catbox.moe/a.png\n"))
    }

    @Test fun x0atUploadSendsNoSecretOrExpiryFields() {
        // x0.at ignores secret/expires; only the file field is sent.
        val config =
            PasteBackendConfig(
                backend = AttachmentBackend.X0_AT,
                secretUrl = true,
                expiry = "7d",
            )
        assertEquals(emptyList<Pair<String, String>>(), multipart0x0Fields(normalizedConfig(config)))
    }

    @Test fun acceptPostMatchingToleratesParametersAndUnknownTypes() {
        // A sent type with parameters must match its bare Accept-Post entry (the text-file report).
        assertTrue("text/plain, image/jpeg".acceptsMime("text/plain; charset=utf-8"))
        assertTrue("image/*, video/*".acceptsMime("image/jpeg"))
        assertTrue("*/*".acceptsMime("application/zip"))
        // Wildcard or unspecified sent types cannot be judged against a concrete list.
        assertTrue("image/png, image/jpeg".acceptsMime("image/*"))
        assertTrue("image/png".acceptsMime("application/octet-stream"))
        // A genuinely excluded concrete type still reads as a mismatch (for the failure hint).
        assertFalse("image/png; q=1, video/mp4".acceptsMime("image/jpeg"))
    }

    @Test fun sojuFailureMessageCarriesServerBodyAndAcceptPostHint() {
        assertEquals(
            "Soju file host upload failed (HTTP 415): unsupported charset.",
            sojuUploadFailureMessage(415, "unsupported charset", null, "image/jpeg"),
        )
        assertEquals(
            "Soju file host upload failed (HTTP 415). The server says it accepts: image/png.",
            sojuUploadFailureMessage(415, "", "image/png", "image/jpeg"),
        )
        // No hint when the advertised list covers the sent type.
        assertEquals(
            "Soju file host upload failed (HTTP 500).",
            sojuUploadFailureMessage(500, "", "image/*", "image/jpeg"),
        )
    }

    @Test fun unknownAttachmentMimeFallsBackToFilenameExtension() {
        assertEquals("image/jpeg", guessMimeType("Photo.JPG"))
        assertEquals("text/plain", guessMimeType("notes.txt"))
        assertEquals("application/octet-stream", guessMimeType("blob"))
    }

    @Test fun compatibleBackendsStillSendSecretAndExpiry() {
        val config =
            normalizedConfig(
                PasteBackendConfig(
                    backend = AttachmentBackend.ZERO_X_ZERO,
                    secretUrl = true,
                    expiry = "7d",
                ),
            )
        assertEquals(listOf("secret" to "", "expires" to "168"), multipart0x0Fields(config))
    }
}
