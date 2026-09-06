package io.github.trevarj.motd.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewKind
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme

internal val LocalLinkPreviewAwaiting = staticCompositionLocalOf { false }
internal val LocalLinkPreviewFailed = staticCompositionLocalOf { false }

internal fun shouldShowLinkPreview(
    preview: LinkPreview?,
    loading: Boolean,
    resolved: Boolean,
    awaiting: Boolean = false,
): Boolean = preview != null || loading || resolved || awaiting

internal sealed interface LinkPreviewRenderState {
    data object Awaiting : LinkPreviewRenderState

    data object Loading : LinkPreviewRenderState

    data class Available(
        val preview: LinkPreview,
    ) : LinkPreviewRenderState

    data object Failed : LinkPreviewRenderState

    data object Unavailable : LinkPreviewRenderState
}

internal enum class LinkPreviewTransitionKey {
    AWAITING,
    LOADING,
    AVAILABLE,
    FAILED,
    UNAVAILABLE,
}

internal fun resolveLinkPreviewRenderState(
    preview: LinkPreview?,
    loading: Boolean,
    awaiting: Boolean = false,
    failed: Boolean = false,
): LinkPreviewRenderState =
    when {
        loading -> LinkPreviewRenderState.Loading
        preview != null -> LinkPreviewRenderState.Available(preview)
        failed -> LinkPreviewRenderState.Failed
        awaiting -> LinkPreviewRenderState.Awaiting
        else -> LinkPreviewRenderState.Unavailable
    }

internal val LinkPreviewRenderState.transitionKey: LinkPreviewTransitionKey
    get() =
        when (this) {
            LinkPreviewRenderState.Awaiting -> LinkPreviewTransitionKey.AWAITING
            LinkPreviewRenderState.Loading -> LinkPreviewTransitionKey.LOADING
            is LinkPreviewRenderState.Available -> LinkPreviewTransitionKey.AVAILABLE
            LinkPreviewRenderState.Failed -> LinkPreviewTransitionKey.FAILED
            LinkPreviewRenderState.Unavailable -> LinkPreviewTransitionKey.UNAVAILABLE
        }

/**
 * Metadata link preview card. Each state retains a shared 72 dp minimum footprint; completed
 * metadata may be taller and grows through the card-local content-size transition.
 */
@Composable
fun LinkPreviewCard(
    preview: LinkPreview?,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    awaiting: Boolean = false,
    failed: Boolean = false,
) {
    val renderState =
        resolveLinkPreviewRenderState(
            preview,
            loading,
            awaiting || LocalLinkPreviewAwaiting.current,
            failed || LocalLinkPreviewFailed.current,
        )
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
            LinkPreviewRenderState.Awaiting -> LinkPreviewAwaiting(onClick)
            LinkPreviewRenderState.Loading -> LinkPreviewSkeleton()
            is LinkPreviewRenderState.Available -> LinkPreviewContent(state.preview, onClick)
            LinkPreviewRenderState.Failed -> LinkPreviewFailed(onClick)
            LinkPreviewRenderState.Unavailable -> LinkPreviewUnavailable(onClick)
        }
    }
}

@Composable
private fun LinkPreviewContent(
    preview: LinkPreview,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = LINK_PREVIEW_MIN_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable { onClick() }
                .padding(8.dp),
    ) {
        LinkPreviewLeading(preview)
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
                    fontFamily = if (preview.kind == LinkPreviewKind.TEXT) FontFamily.Monospace else FontFamily.Default,
                    maxLines =
                        when (preview.kind) {
                            LinkPreviewKind.TEXT -> 4
                            LinkPreviewKind.WIKIPEDIA -> 3
                            LinkPreviewKind.WEB, LinkPreviewKind.VIDEO, LinkPreviewKind.FILE -> 2
                        },
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        when (preview.kind) {
                            LinkPreviewKind.TEXT -> Modifier.testTag("link_preview_text_body")
                            LinkPreviewKind.WIKIPEDIA -> Modifier.testTag("link_preview_wikipedia_body")
                            LinkPreviewKind.WEB, LinkPreviewKind.VIDEO, LinkPreviewKind.FILE -> Modifier
                        },
                )
            }
        }
    }
}

@Composable
private fun LinkPreviewLeading(preview: LinkPreview) {
    when {
        preview.kind == LinkPreviewKind.FILE -> {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp).testTag("link_preview_file"),
            )
            Spacer(Modifier.width(10.dp))
        }

        preview.imageUrl != null -> {
            val context = LocalContext.current
            val automatic = LocalAutomaticRemoteMedia.current
            val consent = LocalLinkMediaConsent.current.granted
            val request =
                remember(context, preview.imageUrl, automatic, consent) {
                    ImageRequest
                        .Builder(context)
                        .remoteMediaData(preview.imageUrl, automatic || consent)
                        .build()
                }
            Box(
                contentAlignment = androidx.compose.ui.Alignment.Center,
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .testTag(if (preview.kind == LinkPreviewKind.VIDEO) "link_preview_video_thumbnail" else "link_preview_thumbnail"),
            ) {
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (preview.kind == LinkPreviewKind.VIDEO) {
                    Icon(
                        imageVector = Icons.Filled.PlayCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.56f)),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
        }

        preview.kind == LinkPreviewKind.VIDEO -> {
            Icon(
                imageVector = Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(56.dp).testTag("link_preview_video_thumbnail"),
            )
            Spacer(Modifier.width(10.dp))
        }
    }
}

@Composable
private fun LinkPreviewAwaiting(onClick: () -> Unit) {
    val description = stringResource(R.string.chat_link_preview_download)
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = LINK_PREVIEW_MIN_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClickLabel = description, onClick = onClick)
                .semantics { contentDescription = description }
                .testTag("link_preview_awaiting")
                .padding(8.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LinkPreviewSkeleton() {
    val description = stringResource(R.string.chat_link_preview_loading)
    // A per-card infinite transition continuously invalidated the chat while previews were in
    // flight. A static skeleton communicates the same loading state without consuming scroll-frame
    // work; the card is replaced as soon as the preview resolves.
    val block = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = LINK_PREVIEW_MIN_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .semantics { contentDescription = description }
                .testTag("link_preview_loading")
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
private fun LinkPreviewFailed(onClick: () -> Unit) {
    val description = stringResource(R.string.chat_link_preview_failed)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = LINK_PREVIEW_MIN_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClickLabel = description, onClick = onClick)
                .semantics { contentDescription = description }
                .testTag("link_preview_failed")
                .padding(8.dp),
    ) {
        Icon(Icons.Outlined.Link, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(10.dp))
        Text(description, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun LinkPreviewUnavailable(onClick: () -> Unit) {
    val description = stringResource(R.string.chat_link_preview_unavailable)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = LINK_PREVIEW_MIN_HEIGHT)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClickLabel = description, onClick = onClick)
                .semantics { contentDescription = description }
                .testTag("link_preview_unavailable")
                .padding(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SkeletonBar(
    width: androidx.compose.ui.unit.Dp,
    color: Color,
) {
    androidx.compose.foundation.layout.Box(
        modifier =
            Modifier
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
            preview =
                LinkPreview(
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
