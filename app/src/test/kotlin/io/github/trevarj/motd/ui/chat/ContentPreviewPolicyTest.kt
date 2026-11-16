package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.prefs.decodePalette
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContentPreviewPolicyTest {
    @Test fun palette_snapshot_survives_image_gate_but_disabled_links_remove_candidate() {
        val palette =
            decodePalette(
                requireNotNull(
                    javaClass.getResourceAsStream(
                        "/palettes/duplicate-last-valid.motd-palette.json",
                    ),
                ).use { it.readBytes() },
            )
        val url = "https://example.test/pair.motd-palette.json"
        val preview =
            LinkPreview(
                url = url,
                title = palette.name,
                description = null,
                imageUrl = null,
                siteName = null,
                kind = LinkPreviewKind.PALETTE,
                palette = palette,
            )
        assertEquals(preview, preview.withImageGate(showImages = false))
        assertEquals(palette, preview.withImageGate(showImages = false).palette)
        assertEquals(MessageUrls.Empty, messageUrls(url).gated(showImages = false, showLinkPreviews = false))
        assertEquals(url, messageUrls(url).gated(showImages = false, showLinkPreviews = true).linkUrl)
    }

    private val urls =
        MessageUrls(
            mediaUrls = listOf("https://example.test/image.png", "https://example.test/second.webp"),
            linkUrl = "https://example.test/article",
        )

    @Test
    fun image_and_link_gates_form_an_independent_matrix() {
        assertEquals(urls, urls.gated(showImages = true, showLinkPreviews = true))
        assertEquals(
            MessageUrls(mediaUrls = emptyList(), linkUrl = urls.linkUrl),
            urls.gated(showImages = false, showLinkPreviews = true),
        )
        assertEquals(
            MessageUrls(mediaUrls = urls.mediaUrls, linkUrl = null),
            urls.gated(showImages = true, showLinkPreviews = false),
        )
        assertEquals(
            MessageUrls.Empty,
            urls.gated(showImages = false, showLinkPreviews = false),
        )
    }

    @Test
    fun disabling_images_removes_link_card_thumbnail_but_keeps_metadata() {
        val preview =
            LinkPreview(
                url = urls.linkUrl!!,
                title = "Article",
                description = "Description",
                imageUrl = urls.mediaUrls.first(),
                siteName = "Example",
                kind = LinkPreviewKind.TEXT,
            )

        val gated = preview.withImageGate(showImages = false)

        assertNull(gated.imageUrl)
        assertEquals(preview.title, gated.title)
        assertEquals(preview.description, gated.description)
        assertEquals(LinkPreviewKind.TEXT, gated.kind)
        assertEquals(preview, preview.withImageGate(showImages = true))
    }
}
