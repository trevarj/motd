package io.github.trevarj.motd.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Plain JVM fixture tests for the OG-tag extractor (no Android deps: parseOgTags uses only Regex).
class OgParserTest {
    private val url = "https://example.com/page"

    @Test
    fun extractsAllOgTags_doubleQuotes() {
        val html = """
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
        assertEquals("Example Site", p.siteName)
        assertEquals(url, p.url)
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
    fun noExtractableTags_returnsNull() {
        val html = "<html><body>no metadata at all</body></html>"
        assertNull(LinkPreviewRepositoryImpl.parseOgTags(url, html))
    }
}
