package io.github.trevarj.motd.ui.chat

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
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
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.cornerRadius

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
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).testTag("chat_links_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            items(state.occurrences, key = { "${it.eventId}_${it.urlIndex}" }) { occurrence ->
                ConversationLinkRow(
                    occurrence = occurrence,
                    networkId = networkId,
                    preview = if (showLinkPreviews) cachedPreview(occurrence.url, networkId)?.preview else null,
                    showImages = showImages,
                    onLinkActions = { selected = occurrence },
                    onJump = { onJump(occurrence) },
                )
                HorizontalDivider()
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
private fun ConversationLinkRow(
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
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
        )
    val rowId = "${occurrence.eventId}_${occurrence.urlIndex}"
    val actionsLabel = stringResource(R.string.chat_link_actions)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = actionsLabel, role = Role.Button, onClick = onLinkActions)
                .testTag("chat_links_url_$rowId")
                .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showImages) {
            preview?.imageUrl?.takeIf(String::isHttpLink)?.let { url ->
                key(url, networkId) { CachedConversationLinkImage(url, networkId) }
            }
        }
        Column(Modifier.weight(1f)) {
            preview?.siteName?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            preview?.title?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                occurrence.url,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            preview?.description?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Text(
        stringResource(R.string.chat_links_source, occurrence.sender, time),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = onJump, modifier = Modifier.testTag("chat_links_jump_$rowId")) {
        Text(stringResource(R.string.chat_links_jump))
    }
}

@Composable
private fun CachedConversationLinkImage(
    url: String,
    networkId: Long,
) {
    val context = LocalContext.current
    val size = 56.dp
    val pixels = with(LocalDensity.current) { size.roundToPx() }
    val request =
        remember(context, url, networkId, pixels) {
            ImageRequest
                .Builder(context)
                .routedRemoteMediaData(url, networkId, networkAllowed = false)
                .size(pixels)
                .build()
        }
    val painter = rememberAsyncImagePainter(request, contentScale = ContentScale.Crop)
    if (painter.state is AsyncImagePainter.State.Success) {
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(size)
                    .clip(RoundedCornerShape(LocalMediaCornerStyle.current.cornerRadius(8.dp)))
                    .testTag("chat_links_cached_image"),
        )
    }
}
