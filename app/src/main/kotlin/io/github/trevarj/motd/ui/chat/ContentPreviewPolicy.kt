package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.repo.LinkPreview

/** Pure request/render policy shared by every timeline density. */
internal fun MessageUrls.gated(showImages: Boolean, showLinkPreviews: Boolean): MessageUrls =
    MessageUrls(
        imageUrl = imageUrl.takeIf { showImages },
        linkUrl = linkUrl.takeIf { showLinkPreviews },
    )

/** Link-card text remains visible when only thumbnail loading is disabled. */
internal fun LinkPreview.withImageGate(showImages: Boolean): LinkPreview =
    if (showImages) this else copy(imageUrl = null)
