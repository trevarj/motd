package io.github.trevarj.motd.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkPreviewTextTest {
    @Test fun declared_types_are_classified_without_extension_inference() {
        assertEquals(LinkPreviewKind.WEB, LinkPreviewRepositoryImpl.responseKind("text/html; charset=utf-8"))
        assertEquals(LinkPreviewKind.WEB, LinkPreviewRepositoryImpl.responseKind("application/xhtml+xml"))
        assertEquals(LinkPreviewKind.WEB, LinkPreviewRepositoryImpl.responseKind("IMAGE/PNG; charset=binary"))
        assertEquals(LinkPreviewKind.TEXT, LinkPreviewRepositoryImpl.responseKind("text/plain"))
        assertEquals(LinkPreviewKind.TEXT, LinkPreviewRepositoryImpl.responseKind("application/problem+json"))
        assertEquals(LinkPreviewKind.TEXT, LinkPreviewRepositoryImpl.responseKind("application/rss+xml"))
        assertEquals(LinkPreviewKind.TEXT, LinkPreviewRepositoryImpl.responseKind("application/javascript"))
        assertEquals(LinkPreviewKind.TEXT, LinkPreviewRepositoryImpl.responseKind("application/x-yaml"))
        assertEquals(LinkPreviewKind.VIDEO, LinkPreviewRepositoryImpl.responseKind("video/webm"))
        assertEquals(LinkPreviewKind.FILE, LinkPreviewRepositoryImpl.responseKind("application/octet-stream"))
    }

    @Test fun text_normalizes_controls_and_uses_file_name() {
        val preview = LinkPreviewRepositoryImpl.parseTextPreview("https://example.test/a/hello%20world.txt", "one\r\ntwo\u001b\u202E")!!
        assertEquals(LinkPreviewKind.TEXT, preview.kind)
        assertEquals("hello world.txt", preview.title)
        assertEquals("one\ntwo", preview.description)
        assertNull(preview.imageUrl)
    }

    @Test fun urlFallbackTitleStripsEncodedControlsAndFormatCharacters() {
        assertEquals("safe name.txt", LinkPreviewRepositoryImpl.textTitle("https://example.test/safe%0Aname%E2%80%AE.txt"))
    }

    @Test fun invalid_charset_falls_back_and_blank_text_is_negative() {
        assertEquals(Charsets.UTF_8, LinkPreviewRepositoryImpl.charsetFromContentType("text/plain; charset=does-not-exist"))
        assertNull(LinkPreviewRepositoryImpl.sanitizeText("\u0000\u001b\u202e"))
        assertNull(LinkPreviewRepositoryImpl.parseTextPreview("https://example.test/blob.txt", "safe\u0000binary"))
    }

    @Test fun commonCodeAndTextExtensionsAreRecognized() {
        for (extension in listOf("md", "jsonl", "yaml", "toml", "env", "kt", "tsx", "cpp", "rs", "sh", "sql", "nix", "graphql", "svelte", "scss")) {
            assertEquals(extension, true, LinkPreviewRepositoryImpl.hasTextExtension("https://example.test/file.$extension"))
        }
        assertEquals(true, LinkPreviewRepositoryImpl.hasTextExtension("https://example.test/.env"))
        assertEquals(false, LinkPreviewRepositoryImpl.hasTextExtension("https://example.test/file.pdf"))
    }

    @Test fun file_preview_uses_the_filename_and_declared_media_type() {
        val preview =
            LinkPreviewRepositoryImpl.filePreview(
                url = "https://example.test/downloads/report.pdf",
                contentType = "application/pdf; charset=binary",
                kind = LinkPreviewKind.FILE,
            )

        assertEquals("report.pdf", preview.title)
        assertEquals("application/pdf", preview.description)
        assertEquals(LinkPreviewKind.FILE, preview.kind)
    }

    @Test fun sanitizer_truncates_at_a_unicode_safe_code_point_limit() {
        val body = LinkPreviewRepositoryImpl.sanitizeText("😀".repeat(2_049))!!

        assertEquals(2_048, body.codePointCount(0, body.length))
        assertEquals("😀", body.takeLast(2))
    }
}
