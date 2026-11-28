package io.github.trevarj.motd.ui.chat

import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.repo.CachedLinkPreview
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet
import io.github.trevarj.motd.ui.components.LinkActionsSheet
import io.github.trevarj.motd.ui.components.routedRemoteMediaData
import io.github.trevarj.motd.ui.theme.LocalMediaCornerStyle
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.cornerRadius
import io.github.trevarj.motd.ui.theme.readableForeground
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationLinksSheet(
    state: ConversationLinksState,
    networkId: Long,
    showLinkPreviews: Boolean,
    showImages: Boolean,
    cachedPreview: (String, Long?) -> CachedLinkPreview?,
    onLoadOlder: () -> Unit,
    onJump: (ConversationLinkOccurrence) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<ConversationLinkOccurrence?>(null) }
    val visibleSelection = selected?.takeIf { it in state.occurrences }
    LaunchedEffect(visibleSelection) {
        if (visibleSelection == null) selected = null
    }
    EffectModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("chat_links_sheet"),
    ) {
        SheetSystemBars()
        Text(
            text = stringResource(R.string.chat_links_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp).semantics { heading() }.testTag("chat_links_title"),
        )
        Text(
            text = stringResource(R.string.chat_links_device_only),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("chat_links_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.occurrences, key = { "${it.eventId}_${it.urlIndex}" }) { occurrence ->
                ConversationLinkCard(
                    occurrence = occurrence,
                    networkId = networkId,
                    preview = if (showLinkPreviews) cachedPreview(occurrence.url, networkId)?.preview else null,
                    showImages = showImages,
                    onLinkActions = { selected = occurrence },
                    onJump = { onJump(occurrence) },
                )
            }
            item {
                when {
                    state.loading -> {
                        CircularProgressIndicator(Modifier.padding(vertical = 12.dp).size(24.dp).testTag("chat_links_loading"))
                    }

                    state.failed -> {
                        Text(stringResource(R.string.chat_links_failed), Modifier.padding(top = 12.dp))
                        TextButton(onClick = onLoadOlder, modifier = Modifier.testTag("chat_links_retry")) {
                            Text(stringResource(R.string.chat_retry))
                        }
                    }

                    else -> {
                        if (state.occurrences.isEmpty()) {
                            Text(
                                stringResource(if (state.hasMore) R.string.chat_links_empty_page else R.string.chat_links_empty),
                                Modifier.padding(vertical = 12.dp).testTag("chat_links_empty"),
                            )
                        }
                        if (state.hasMore) {
                            TextButton(onClick = onLoadOlder, modifier = Modifier.testTag("chat_links_load_older")) {
                                Text(stringResource(R.string.chat_links_load_older))
                            }
                        }
                    }
                }
            }
        }
        visibleSelection?.let { occurrence ->
            LinkActionsSheet(listOf(occurrence.url), onDismiss = { selected = null })
        }
    }
}

@Composable
private fun ConversationLinkCard(
    occurrence: ConversationLinkOccurrence,
    networkId: Long,
    preview: LinkPreview?,
    showImages: Boolean,
    onLinkActions: () -> Unit,
    onJump: () -> Unit,
) {
    val context = LocalContext.current
    val time =
        DateUtils.formatDateTime(
            context,
            occurrence.serverTime,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
        )
    val rowId = "${occurrence.eventId}_${occurrence.urlIndex}"
    val actionsLabel = stringResource(R.string.chat_link_actions)
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clickable(onClickLabel = actionsLabel, role = Role.Button, onClick = onLinkActions)
                    .testTag("chat_links_url_$rowId")
                    .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val imageUrl = if (showImages) preview?.imageUrl?.takeIf(String::isHttpLink) else null
            key(imageUrl, networkId) { ConversationLinkThumbnail(imageUrl, networkId) }
            Column(
                modifier = Modifier.weight(1f).padding(start = 12.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                preview?.siteName?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                preview?.title?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    occurrence.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                preview?.description?.takeIf(String::isNotBlank)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    occurrence.sender,
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalNickColors.current.nick(occurrence.sender, scheme.onSurface),
                )
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.readableForeground(lerp(scheme.onSurfaceVariant, scheme.surfaceContainerLow, 0.18f), scheme.surfaceContainerLow),
                )
            }
            FilledTonalIconButton(onClick = onJump, modifier = Modifier.testTag("chat_links_jump_$rowId")) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.chat_links_jump),
                    tint = scheme.readableForeground(lerp(scheme.onSurfaceVariant, scheme.secondaryContainer, 0.15f), scheme.secondaryContainer, minimumContrast = 3.0),
                )
            }
        }
    }
}

@Composable
private fun ConversationLinkThumbnail(
    url: String?,
    networkId: Long,
) {
    val size = 48.dp
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(LocalMediaCornerStyle.current.cornerRadius(8.dp)),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        val painter =
            url?.let {
                val context = LocalContext.current
                val pixels = with(LocalDensity.current) { size.roundToPx() }
                val request =
                    remember(context, url, networkId, pixels) {
                        ImageRequest
                            .Builder(context)
                            .routedRemoteMediaData(url, networkId, networkAllowed = false)
                            .size(pixels)
                            .build()
                    }
                rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
            }
        if (painter?.state is AsyncImagePainter.State.Success) {
            Image(
                painter = painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.testTag("chat_links_cached_image"),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
