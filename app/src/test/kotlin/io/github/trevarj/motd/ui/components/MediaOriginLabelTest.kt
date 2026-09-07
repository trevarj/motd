package io.github.trevarj.motd.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaOriginLabelTest {
    @Test
    fun origin_uses_destination_not_credentials_path_or_query() {
        assertEquals(
            "media.cdn.example:8443",
            mediaOriginLabel("https://trusted.example:secret@media.cdn.example:8443/trusted.example/image.png?token=secret#details"),
        )
        assertEquals("xn--bcher-kva.example", mediaOriginLabel("https://bücher.example/image.png"))
    }

    @Test
    fun origin_omits_only_scheme_default_port_and_brackets_ipv6() {
        assertEquals("cdn.example", mediaOriginLabel("https://cdn.example:443/image.png"))
        assertEquals("cdn.example", mediaOriginLabel("http://cdn.example:80/image.png"))
        assertEquals("cdn.example:443", mediaOriginLabel("http://cdn.example:443/image.png"))
        assertEquals("[2001:db8::1]", mediaOriginLabel("https://[2001:db8::1]:443/image.png"))
        assertEquals("[2001:db8::1]:8443", mediaOriginLabel("https://[2001:db8::1]:8443/image.png"))
    }

    @Test
    fun malformed_and_non_http_urls_have_no_origin() {
        listOf(
            "https:cdn.example/image.png",
            "https:///cdn.example/image.png",
            "https://trusted.example\\@evil.example/image.png",
            "https://cdn.example:65536/image.png",
            "https://cdn.example:wrong/image.png",
            "https://[not-ipv6]/image.png",
            "https://cdn.example/%zz",
            "https://cdn.example/\nimage.png",
            "file://cdn.example/image.png",
            "javascript:alert(1)",
            "//cdn.example/image.png",
        ).forEach { url -> assertNull(url, mediaOriginLabel(url)) }
    }
}
