package io.github.trevarj.motd.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * OG-tag link preview card. Shows a shimmer skeleton while [loading]; when [preview] is null and no
 * longer loading the caller hides the card entirely (this composable simply renders nothing).
 */
@Composable
fun LinkPreviewCard(
    preview: LinkPreview?,
    loading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        loading -> LinkPreviewSkeleton(modifier)
        preview != null -> LinkPreviewContent(preview, onClick, modifier)
        else -> Unit // unfetchable/not HTML -> hidden
    }
}

@Composable
private fun LinkPreviewContent(preview: LinkPreview, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
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
private fun LinkPreviewSkeleton(modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "shimmerAlpha",
    )
    val block = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.4f)
    Row(
        modifier = modifier
            .fillMaxWidth()
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
private fun SkeletonBar(width: androidx.compose.ui.unit.Dp, color: Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .width(width)
            .height(10.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color),
    )
}

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
