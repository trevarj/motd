package io.github.trevarj.motd.ui.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.dickord.dickordChannelLabel
import io.github.trevarj.motd.dickord.dickordNickLabel
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.chat.messageContentType
import io.github.trevarj.motd.ui.chat.showsSender
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.conversationTag
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter

/** Read-only merged stream of conversation lines from every channel and DM, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalFeedScreen(
    onBack: () -> Unit = {},
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit = { _, _, _ -> },
    viewModel: GlobalFeedViewModel = hiltViewModel(),
) {
    val rows = viewModel.items.collectAsLazyPagingItems()
    val showNetwork by viewModel.showNetwork.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.feed_title)) },
            )
        },
    ) { padding ->
        GlobalFeedContent(
            rows = rows,
            showNetwork = showNetwork,
            onOpenMessage = onOpenMessage,
            modifier = Modifier.padding(padding),
        )
    }
}

@Composable
internal fun GlobalFeedContent(
    rows: LazyPagingItems<SearchHit>,
    showNetwork: Boolean,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val refresh = rows.loadState.refresh
    when {
        // Error state only when nothing is on screen; loaded lines survive a failed refresh.
        refresh is LoadState.Error && rows.itemCount == 0 -> {
            EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(R.string.feed_error_title),
                message = stringResource(R.string.feed_error_message),
                modifier = modifier,
                actionLabel = stringResource(R.string.feed_retry),
                onAction = rows::retry,
            )
        }

        // NotLoading, so the empty state never flashes over an arriving first page.
        rows.itemCount == 0 && refresh is LoadState.NotLoading -> {
            EmptyState(
                icon = Icons.Outlined.DynamicFeed,
                title = stringResource(R.string.feed_empty_title),
                message = stringResource(R.string.feed_empty_message),
                modifier = modifier,
                ghostRows = true,
            )
        }

        else -> {
            GlobalFeedList(
                rows = rows,
                showNetwork = showNetwork,
                onOpenMessage = onOpenMessage,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun GlobalFeedList(
    rows: LazyPagingItems<SearchHit>,
    showNetwork: Boolean,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One list-scoped formatter: MessageBubble's per-row fallback ignores the app's timestamp
    // preference.
    val formatTime = rememberMessageTimeFormatter()
    LazyColumn(modifier = modifier.fillMaxSize().testTag("feed_list")) {
        items(
            count = rows.itemCount,
            // The canonical row id: stable across invalidation, unlike a merged-stream position.
            key = rows.itemKey { it.message.id },
            contentType = rows.itemContentType { messageContentType(it.message, collapseSystemEvents = false) },
        ) { index ->
            rows[index]?.let { row ->
                GlobalFeedLineRow(
                    row = row,
                    // Newest first, so the row above is newer. peek, not get: get would report a
                    // second viewport hint.
                    newer = if (index > 0) rows.peek(index - 1) else null,
                    showNetwork = showNetwork,
                    formatTime = formatTime,
                    onOpenMessage = onOpenMessage,
                )
            }
        }
    }
}

@Composable
private fun GlobalFeedLineRow(
    row: SearchHit,
    newer: SearchHit?,
    showNetwork: Boolean,
    formatTime: (Long) -> String,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
) {
    val message = row.message
    val dickordEnabled = LocalDickordLabsEnabled.current
    val displaySender = dickordNickLabel(message.sender, dickordEnabled)
    // Rows arrive from many networks: each carries its own advertised casemap/chantypes, so mention
    // detection is decided per row rather than from one screen-wide default.
    val identityRules =
        remember(row.caseMapping, row.chanTypes) {
            IrcIdentityRules.from(row.caseMapping, row.chanTypes)
        }
    val newerMessage = newer?.message
    val sameBuffer = newerMessage?.bufferId == message.bufferId
    // Headers are drawn above the bubble, so both are decided against the newer row above.
    // showsSender takes the older of the pair — true means this row opens the group.
    val showSender = newerMessage == null || !sameBuffer || showsSender(newerMessage, message)
    Column(modifier = Modifier.fillMaxWidth().testTag("feed_row_${message.id}")) {
        if (!sameBuffer) {
            Text(
                text =
                    conversationTag(
                        dickordChannelLabel(row.bufferDisplayName, dickordEnabled),
                        row.networkName,
                        showNetwork,
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
        }
        MessageBubble(
            sender = message.sender,
            displaySender = displaySender,
            senderAccount = message.senderAccount,
            // Same rule as MessageList: the stored IRC-formatted body wins when there is one.
            text = message.ircFormattedText ?: message.text,
            timeMs = message.serverTime,
            isSelf = message.isSelf,
            kind = message.kind,
            showSender = showSender,
            isBot = message.isBot,
            hasMention = message.hasMention,
            // The contentType pool already splits SELF_FAILED out; the row must render it too.
            failed = message.failed,
            pending = message.pendingLabel != null,
            networkId = row.networkId,
            identityRules = identityRules,
            formattedTime = remember(message.serverTime, formatTime) { formatTime(message.serverTime) },
            onClick = { onOpenMessage(message.bufferId, message.id, message.serverTime) },
            onClickLabel = stringResource(R.string.feed_open_message),
        )
    }
}
