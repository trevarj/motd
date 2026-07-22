package io.github.trevarj.motd.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme

internal fun shouldShowLinkPreview(
    preview: LinkPreview?,
    loading: Boolean,
    resolved: Boolean,
): Boolean = preview != null || loading || resolved

internal sealed interface LinkPreviewRenderState {
    data object Loading : LinkPreviewRenderState

    data class Available(val preview: LinkPreview) : LinkPreviewRenderState

    data object Unavailable : LinkPreviewRenderState
}

internal enum class LinkPreviewTransitionKey {
    LOADING,
    AVAILABLE,
    UNAVAILABLE,
}

internal fun resolveLinkPreviewRenderState(
    preview: LinkPreview?,
    loading: Boolean,
): LinkPreviewRenderState = when {
    loading -> LinkPreviewRenderState.Loading
    preview != null -> LinkPreviewRenderState.Available(preview)
    else -> LinkPreviewRenderState.Unavailable
}

internal val LinkPreviewRenderState.transitionKey: LinkPreviewTransitionKey
    get() = when (this) {
        LinkPreviewRenderState.Loading -> LinkPreviewTransitionKey.LOADING
        is LinkPreviewRenderState.Available -> LinkPreviewTransitionKey.AVAILABLE
        LinkPreviewRenderState.Unavailable -> LinkPreviewTransitionKey.UNAVAILABLE
    }

/**
 * OG-tag link preview card. Each state retains a shared 72 dp minimum footprint; completed
 * metadata may be taller and grows through the card-local content-size transition.
 */
@Composable
fun LinkPreviewCard(
    preview: LinkPreview?,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val renderState = resolveLinkPreviewRenderState(preview, loading)
    AnimatedContent(
        targetState = renderState,
        modifier = modifier.fillMaxWidth(),
        transitionSpec = {
            (fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut))
                .using(
                    SizeTransform(
                        clip = true,
                        sizeAnimationSpec = { _, _ -> MotdMotion.contentSize },
                    ),
                )
        },
        contentAlignment = androidx.compose.ui.Alignment.TopStart,
        contentKey = { it.transitionKey },
        label = "link_preview_content",
    ) { state ->
        when (state) {
            LinkPreviewRenderState.Loading -> LinkPreviewSkeleton()
            is LinkPreviewRenderState.Available -> LinkPreviewContent(state.preview, onClick)
            LinkPreviewRenderState.Unavailable -> LinkPreviewUnavailable(onClick)
        }
    }
}

@Composable
private fun LinkPreviewContent(preview: LinkPreview, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LINK_PREVIEW_MIN_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onClick() }
            .padding(8.dp),
    ) {
        if (preview.imageUrl != null) {
            AsyncImage(
                model = preview.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            preview.siteName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = preview.title ?: preview.url,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            preview.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LinkPreviewSkeleton() {
    // A per-card infinite transition continuously invalidated the chat while previews were in
    // flight. A static skeleton communicates the same loading state without consuming scroll-frame
    // work; the card is replaced as soon as the preview resolves.
    val block = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LINK_PREVIEW_MIN_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(8.dp),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(block),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            SkeletonBar(width = 120.dp, color = block)
            Spacer(Modifier.height(6.dp))
            SkeletonBar(width = 180.dp, color = block)
            Spacer(Modifier.height(6.dp))
            SkeletonBar(width = 90.dp, color = block)
        }
    }
}

@Composable
private fun LinkPreviewUnavailable(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LINK_PREVIEW_MIN_HEIGHT)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable { onClick() }
            .padding(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun SkeletonBar(width: androidx.compose.ui.unit.Dp, color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .width(width)
            .height(10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
    )
}

private val LINK_PREVIEW_MIN_HEIGHT = 72.dp

@Preview
@Composable
private fun LinkPreviewCardPreview() {
    MotdTheme {
        LinkPreviewCard(
            preview = LinkPreview(
                url = "https://example.com/article",
                title = "A great article about Kotlin coroutines",
                description = "Everything you need to know about structured concurrency.",
                imageUrl = null,
                siteName = "example.com",
            ),
            loading = false,
            onClick = {},
        )
    }
}

@Preview
@Composable
private fun LinkPreviewSkeletonPreview() {
    MotdTheme {
        LinkPreviewCard(preview = null, loading = true, onClick = {})
    }
}
