package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.data.repo.LinkPreview
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkPreviewVisibilityTest {
    @Test
    fun loadingToNegativeCompletion_keepsThePreviewFootprint() {
        assertTrue(shouldShowLinkPreview(preview = null, loading = true, resolved = false))
        assertTrue(shouldShowLinkPreview(preview = null, loading = false, resolved = true))
        assertFalse(shouldShowLinkPreview(preview = null, loading = false, resolved = false))
    }

    @Test
    fun successfulPreview_isVisible() {
        assertTrue(
            shouldShowLinkPreview(
                preview = LinkPreview(
                    url = "https://example.test",
                    title = "Example",
                    description = null,
                    imageUrl = null,
                    siteName = null,
                ),
                loading = false,
                resolved = true,
            ),
        )
    }
}
