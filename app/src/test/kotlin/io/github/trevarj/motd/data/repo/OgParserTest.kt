package io.github.trevarj.motd.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Plain JVM fixture tests for bounded HTML metadata extraction.
class OgParserTest {
    private val url = "https://example.com/page"

    @Test
    fun extractsAllOgTags_doubleQuotes() {
        val html =
            """
            <html><head>
            <meta property="og:title" content="The Title">
            <meta property="og:description" content="A description here">
            <meta property="og:image" content="https://example.com/img.png">
            <meta property="og:site_name" content="Example Site">
            </head></html>
            """.trimIndent()

        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("The Title", p.title)
        assertEquals("A description here", p.description)
        assertEquals("https://example.com/img.png", p.imageUrl)
        // Provenance is the fetched host; the self-declared og:site_name is not trusted.
        assertEquals("example.com", p.siteName)
        assertEquals(url, p.url)
        assertEquals(LinkPreviewKind.WEB, p.kind)
    }

    @Test
    fun contentBeforeProperty_attributeOrderIndependent() {
        val html = """<meta content='Reversed Title' property='og:title'>"""
        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("Reversed Title", p.title)
    }

    @Test
    fun fallsBackToTitleTag_whenNoOgTitle() {
        val html = "<html><head><title>Plain Title</title></head><body>hi</body></html>"
        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("Plain Title", p.title)
        assertNull(p.description)
        assertNull(p.imageUrl)
    }

    @Test
    fun decodesHtmlEntities() {
        val html = """<meta property="og:title" content="Tom &amp; Jerry &lt;3">"""
        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("Tom & Jerry <3", p.title)
    }

    @Test
    fun decodesNumericAndNamedEntities() {
        val html =
            """<meta property="og:title" content="Tor is back &#8211; PTirc &mdash; &#x2026;">"""
        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("Tor is back – PTirc — …", p.title)
    }

    @Test
    fun decodesOneEntityLayerAndPreservesSurrogates() {
        val html =
            """<meta property="og:title" content="&amp;#8211; &amp;amp;#8211; &#X2026; &#55296;">"""
        val p = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!
        assertEquals("&#8211; &amp;#8211; … &#55296;", p.title)
    }

    @Test
    fun emptyHtml_usesSafePathFallback() {
        val preview = LinkPreviewRepositoryImpl.parseOgTags(url, "<html><body>no metadata</body></html>")!!
        assertEquals("page", preview.title)
        assertEquals("example.com", preview.siteName)
    }

    @Test
    fun video_pages_use_a_playable_thumbnail_card() {
        val preview =
            LinkPreviewRepositoryImpl.parseOgTags(
                "https://www.youtube.com/watch?v=abc123",
                """
                <meta property="og:title" content="Demo video">
                <meta property="og:image" content="https://i.ytimg.com/vi/abc123/hqdefault.jpg">
                """.trimIndent(),
            )!!

        assertEquals("Demo video", preview.title)
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", preview.imageUrl)
        assertEquals(LinkPreviewKind.VIDEO, preview.kind)
    }

    @Test
    fun video_metadata_and_host_detection_do_not_match_lookalikes() {
        assertTrue(LinkPreviewRepositoryImpl.isPopularVideoUrl("https://youtu.be/abc123"))
        assertTrue(LinkPreviewRepositoryImpl.isPopularVideoUrl("https://www.vimeo.com/12345"))
        assertFalse(LinkPreviewRepositoryImpl.isPopularVideoUrl("https://youtube.com.example.test/watch"))

        val preview =
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                """
                <meta property="og:type" content="video.other">
                <meta property="og:video" content="https://cdn.example.com/clip.mp4">
                """.trimIndent(),
            )!!
        assertEquals(LinkPreviewKind.VIDEO, preview.kind)
    }

    @Test
    fun youtubeVideoUrls_useOnlyTheValidatedIdInCanonicalOEmbedTarget() {
        val expected =
            "https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3DdQw4w9WgXcQ&format=json"
        for (
        videoUrl in
        listOf(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=private&url=https://evil.test#t=30",
            "https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ?si=tracking",
            "https://www.youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/shorts/dQw4w9WgXcQ",
            "https://youtube.com/embed/dQw4w9WgXcQ/",
            "https://www.youtube.com/live/dQw4w9WgXcQ",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
            "https://youtube-nocookie.com/embed/dQw4w9WgXcQ",
        )
        ) {
            assertEquals(videoUrl, expected, LinkPreviewRepositoryImpl.youtubeOEmbedUrl(videoUrl))
        }
    }

    @Test
    fun youtubeOEmbed_rejectsLookalikesNonVideosAndInvalidIds() {
        for (
        videoUrl in
        listOf(
            "https://youtube.com.evil.test/watch?v=dQw4w9WgXcQ",
            "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be.evil.test/dQw4w9WgXcQ",
            "https://youtube-nocookie.com.evil.test/embed/dQw4w9WgXcQ",
            "https://www.youtube.com@evil.test/watch?v=dQw4w9WgXcQ",
            "https://evil.test@www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com:8443/watch?v=dQw4w9WgXcQ",
            "ftp://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/channel/dQw4w9WgXcQ",
            "https://youtube.com/@dQw4w9WgXcQ",
            "https://youtube.com/playlist?list=dQw4w9WgXcQ",
            "https://youtube.com/watch?list=dQw4w9WgXcQ",
            "https://youtube-nocookie.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=short",
            "https://youtube.com/watch?v=dQw4w9WgXcQx",
            "https://youtube.com/watch?v=dQw4w9WgXcQ&v=aaaaaaaaaaa",
            "https://youtube.com/watch?v=dQw4w9WgXc%51",
            "https://youtube.com/watch?v=dQw4w9WgXcQ%26url=evil",
            "https://youtu.be/dQw4w9WgXcQ/extra",
            "https://youtube.com/embed/dQw4w9WgXcQ/extra",
        )
        ) {
            assertNull(videoUrl, LinkPreviewRepositoryImpl.youtubeOEmbedUrl(videoUrl))
        }
    }

    @Test
    fun youtubeOEmbed_sanitizesBoundedTextAndRejectsUnsafeThumbnailsAndInvalidJson() {
        val videoUrl = "https://youtu.be/dQw4w9WgXcQ"
        for (thumbnail in listOf("file:///etc/passwd", "http://127.0.0.1/private", "http://[::1]/private", "http://localhost/private")) {
            val preview =
                LinkPreviewRepositoryImpl.parseYoutubeOEmbed(
                    videoUrl,
                    """{"type":"video","title":" Safe\u202e\u0000\n title ","author_name":"${"a".repeat(3_000)}","thumbnail_url":"$thumbnail"}""",
                )!!
            assertEquals("Safe title", preview.title)
            assertEquals(2_048, preview.description?.length)
            assertNull(preview.imageUrl)
        }
        for (json in listOf("not json", "{}", """{"type":"video","title":123}""", """{"type":"photo","title":"Not a video"}""")) {
            assertNull(LinkPreviewRepositoryImpl.parseYoutubeOEmbed(videoUrl, json))
        }
        // The old HTML path turns YouTube's empty browser-block page into this bogus cached title.
        assertEquals(
            "supported_browsers",
            LinkPreviewRepositoryImpl
                .parseOgTags(
                    "https://m.youtube.com/supported_browsers?next_url=watch",
                    "<html><head></head><body></body></html>",
                )?.title,
        )
    }

    @Test
    fun wikipediaArticleUrls_useLanguageEditionSummaryEndpoint() {
        assertEquals(
            "https://en.wikipedia.org/api/rest_v1/page/summary/Alan_Turing",
            LinkPreviewRepositoryImpl.wikipediaSummaryUrl(
                "https://en.wikipedia.org/wiki/Alan_Turing#Early_life",
            ),
        )
        assertEquals(
            "https://en.wikipedia.org/api/rest_v1/page/summary/Alan%20Turing",
            LinkPreviewRepositoryImpl.wikipediaSummaryUrl(
                "https://en.wikipedia.org/wiki/Alan%20Turing",
            ),
        )
        assertEquals(
            "https://fr.wikipedia.org/api/rest_v1/page/summary/Alan_Turing",
            LinkPreviewRepositoryImpl.wikipediaSummaryUrl(
                "https://fr.m.wikipedia.org/wiki/Alan_Turing",
            ),
        )
        assertEquals(
            "https://de.wikipedia.org/api/rest_v1/page/summary/Alan_Turing",
            LinkPreviewRepositoryImpl.wikipediaSummaryUrl(
                "https://de.wikipedia.org/w/index.php?title=Alan_Turing&oldid=1",
            ),
        )
        assertEquals(
            "https://de.wikipedia.org/api/rest_v1/page/summary/Alan%20Turing",
            LinkPreviewRepositoryImpl.wikipediaSummaryUrl(
                "https://de.wikipedia.org/w/index.php?title=Alan+Turing",
            ),
        )
    }

    @Test
    fun wikipediaSummaryRouting_rejectsNonArticlesAndLookalikeHosts() {
        assertNull(LinkPreviewRepositoryImpl.wikipediaSummaryUrl("https://en.wikipedia.org/"))
        assertNull(LinkPreviewRepositoryImpl.wikipediaSummaryUrl("https://wikipedia.org.example.test/wiki/Test"))
        assertNull(LinkPreviewRepositoryImpl.wikipediaSummaryUrl("https://example.test/wiki/Test"))
    }

    @Test
    fun parsesWikipediaSummaryIntoRicherArticlePreview() {
        val summary =
            """
            {
              "title": "Alan Turing",
              "description": "English computer scientist",
              "extract": "Alan Turing was an English mathematician,\ncomputer scientist.",
              "thumbnail": {
                "source": "https://upload.wikimedia.org/turing.jpg",
                "width": 320,
                "height": 427
              },
              "ignored": true
            }
            """.trimIndent()

        val preview =
            LinkPreviewRepositoryImpl.parseWikipediaSummary(
                "https://en.wikipedia.org/wiki/Alan_Turing",
                summary,
            )!!

        assertEquals("Alan Turing", preview.title)
        assertEquals(
            "Alan Turing was an English mathematician, computer scientist.",
            preview.description,
        )
        assertEquals("https://upload.wikimedia.org/turing.jpg", preview.imageUrl)
        assertEquals("Wikipedia", preview.siteName)
        assertEquals(LinkPreviewKind.WIKIPEDIA, preview.kind)
    }

    @Test
    fun wikipediaSummary_ignoresUnsafeThumbnailAndMalformedJson() {
        val preview =
            LinkPreviewRepositoryImpl.parseWikipediaSummary(
                url,
                """{"title":"Safe","thumbnail":{"source":"file:///tmp/private"}}""",
            )!!
        assertEquals("Safe", preview.title)
        assertNull(preview.imageUrl)
        assertNull(LinkPreviewRepositoryImpl.parseWikipediaSummary(url, "not json"))
        assertNull(LinkPreviewRepositoryImpl.parseWikipediaSummary(url, "{}"))
    }

    @Test
    fun site_name_is_the_fetched_host_never_the_self_declared_brand() {
        val preview =
            LinkPreviewRepositoryImpl.parseOgTags(
                "https://evil.example/login",
                """<meta property="og:title" content="Sign in"><meta property="og:site_name" content="GitHub">""",
            )!!
        assertEquals("evil.example", preview.siteName)
    }

    @Test
    fun og_fields_are_sanitized_and_capped() {
        val spoofedTitle = "paypal.com\u202Emoc.live\u0000"
        val html =
            "<meta property=\"og:title\" content=\"$spoofedTitle\">" +
                // Kept inside the bounded head; oversized fields are covered separately.
                "<meta property=\"og:description\" content=\"" + "a".repeat(10_000) + "\">"
        val preview = LinkPreviewRepositoryImpl.parseOgTags(url, html)!!

        // RTL-override FORMAT and CONTROL code points are stripped, exactly as the TEXT path does.
        assertEquals("paypal.commoc.live", preview.title)
        // A count-based LRU cache means each entry must be small; the sanitizer caps retention.
        assertEquals(2_048, preview.description?.length)
    }

    @Test
    fun og_image_requires_an_http_scheme() {
        for (bad in listOf("file:///etc/passwd", "content://media/external/images/1", "javascript:alert(1)")) {
            val preview =
                LinkPreviewRepositoryImpl.parseOgTags(
                    url,
                    """<meta property="og:title" content="t"><meta property="og:image" content="$bad">""",
                )!!
            assertNull(preview.imageUrl)
        }
        val ok =
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                """<meta property="og:image" content="https://example.com/i.png">""",
            )!!
        assertEquals("https://example.com/i.png", ok.imageUrl)
    }

    @Test
    fun scanner_coversFullBoundedHead_andStopsBeforeBody() {
        val injected =
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                """<html><head></head><body><meta property="og:title" content="Injected"></body></html>""",
            )!!
        assertEquals("page", injected.title)

        val late =
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                "<html><head>" + " ".repeat(80 * 1024) + """<meta property="og:title" content="Late">""",
            )!!
        assertEquals("Late", late.title)

        val beyondCap =
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                "<html><head>" + " ".repeat(513 * 1024) + """<meta property="og:title" content="Too late">""",
            )!!
        assertEquals("page", beyondCap.title)
    }

    @Test
    fun title_extraction_is_linear_and_stops_at_markup() {
        // An unterminated <title> over a large body must fail fast instead of backtracking.
        val started = System.nanoTime()
        assertEquals("page", LinkPreviewRepositoryImpl.parseOgTags(url, "<title>" + "a ".repeat(30_000))?.title)
        assertTrue((System.nanoTime() - started) < 2_000_000_000L)

        // Title markup is ignored safely and falls back to the URL-derived title.
        assertEquals("safe", LinkPreviewRepositoryImpl.parseOgTags(url, "<title>safe</title>")?.title)
        assertEquals("page", LinkPreviewRepositoryImpl.parseOgTags(url, "<title>safe <b>rest</b></title>")?.title)
    }

    @Test
    fun fixtures_coverMetadataPrecedence_andFlexibleAttributes() {
        val github = LinkPreviewRepositoryImpl.parseOgTags(url, fixture("github-head.html"))!!
        assertEquals("owner/project", github.title)
        assertEquals("GitHub repository", github.description)
        assertEquals("https://example.com/owner/project/social.png", github.imageUrl)

        val twitter = LinkPreviewRepositoryImpl.parseOgTags(url, fixture("twitter-only.html"))!!
        assertEquals("Twitter title", twitter.title)
        assertEquals("Twitter description", twitter.description)
        assertEquals("https://cdn.example.test/card.png", twitter.imageUrl)
        assertEquals(LinkPreviewKind.VIDEO, twitter.kind)

        val standard = LinkPreviewRepositoryImpl.parseOgTags(url, fixture("standard-only.html"))!!
        assertEquals("Standard title", standard.title)
        assertEquals("Standard description", standard.description)
    }

    @Test
    fun imageSelectionSkipsUnsafePreferredAndRepeatedCandidates() {
        val preview =
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                """
                <meta property=og:image:secure_url content=javascript:alert(1)>
                <meta property=og:image content=//127.0.0.1/private.png>
                <meta property=og:image content=/safe.png>
                <meta name=twitter:image content=https://cdn.example.test/lower-priority.png>
                """.trimIndent(),
            )!!

        assertEquals("https://example.com/safe.png", preview.imageUrl)
    }

    @Test
    fun metadataImagesRejectLocalhostNames() {
        for (host in listOf("localhost", "assets.localhost")) {
            val preview =
                LinkPreviewRepositoryImpl.parseOgTags(
                    url,
                    """<meta property=og:image content=https://$host/private.png>""",
                )!!
            assertNull(preview.imageUrl)
        }
    }

    @Test
    fun scannerIgnoresCommentAndScriptMarkup_andRespectsQuotedTagEnds() {
        val preview =
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                """
                <!-- <meta property=og:title content=Comment> -->
                <script>const fake = '<meta property=og:title content=Script>';</script>
                <meta content='Real > title' property='og:title'>
                """.trimIndent(),
            )!!
        assertEquals("Real > title", preview.title)
    }

    @Test
    fun metadataPrecedence_prefersOgThenTwitterThenStandard() {
        val preview =
            LinkPreviewRepositoryImpl.parseOgTags(
                url,
                """
                <title>Standard</title>
                <meta name=description content=standard-description>
                <meta name=twitter:title content=Twitter>
                <meta content='Twitter description' name='twitter:description'>
                <meta NAME=og:title CONTENT=OpenGraph>
                <meta property=og:description content='OG description'>
                <meta property=og:image:secure_url content='/safe image.png'>
                """.trimIndent(),
            )!!
        assertEquals("OpenGraph", preview.title)
        assertEquals("OG description", preview.description)
        assertEquals("https://example.com/safe image.png", preview.imageUrl)
    }

    @Test
    fun relativeImagesResolveAgainstFinalUrl_andRestrictedLiteralsAreRejected() {
        assertEquals(
            "https://example.com/a/image.png",
            LinkPreviewRepositoryImpl
                .parseOgTags(
                    "https://example.com/a/page",
                    """<meta property=og:image content=image.png>""",
                )?.imageUrl,
        )
        assertNull(
            LinkPreviewRepositoryImpl
                .parseOgTags(
                    url,
                    """<meta property=og:image content=//127.0.0.1/private.png>""",
                )?.imageUrl,
        )
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/link-preview/$name")).readText()

    @Test
    fun wikipediaSummary_fallsBackToShortDescriptionWhenExtractIsMissing() {
        val preview =
            LinkPreviewRepositoryImpl.parseWikipediaSummary(
                url,
                """{"title":"Alan Turing","description":"English computer scientist"}""",
            )!!
        assertEquals("English computer scientist", preview.description)
    }
}
