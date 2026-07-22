package io.github.trevarj.motd.ui.components

import io.github.trevarj.motd.data.repo.LinkPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun loadingState_takesPrecedenceOverPreview() {
        assertSame(
            LinkPreviewRenderState.Loading,
            resolveLinkPreviewRenderState(preview = preview(imageUrl = null), loading = true),
        )
    }

    @Test
    fun completionStates_routeToAvailableAndUnavailable() {
        assertEquals(
            LinkPreviewRenderState.Available(preview(imageUrl = null)),
            resolveLinkPreviewRenderState(preview = preview(imageUrl = null), loading = false),
        )
        assertSame(
            LinkPreviewRenderState.Unavailable,
            resolveLinkPreviewRenderState(preview = null, loading = false),
        )
    }

    @Test
    fun transitionKeys_distinguishPhasesButShareAvailableUpdates() {
        assertEquals(
            LinkPreviewTransitionKey.LOADING,
            LinkPreviewRenderState.Loading.transitionKey,
        )
        assertEquals(
            LinkPreviewTransitionKey.AVAILABLE,
            LinkPreviewRenderState.Available(preview(imageUrl = null)).transitionKey,
        )
        assertEquals(
            LinkPreviewTransitionKey.AVAILABLE,
            LinkPreviewRenderState.Available(preview(imageUrl = "https://example.test/image.png")).transitionKey,
        )
        assertEquals(
            LinkPreviewTransitionKey.UNAVAILABLE,
            LinkPreviewRenderState.Unavailable.transitionKey,
        )
    }

    private fun preview(imageUrl: String?): LinkPreview = LinkPreview(
        url = "https://example.test",
        title = "Example",
        description = null,
        imageUrl = imageUrl,
        siteName = null,
    )
}
