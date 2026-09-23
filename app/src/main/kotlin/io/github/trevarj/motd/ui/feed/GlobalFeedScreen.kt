package io.github.trevarj.motd.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val MENTIONS_NAVIGATION_FAB_HOLD_MS = 450
private const val MENTIONS_NAVIGATION_FAB_SETTLE_MS = 160

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

/** Read-only stream of stored mentions, with the same exact-message jump as Global Feed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MentionsScreen(
    onBack: () -> Unit = {},
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit = { _, _, _ -> },
    viewModel: MentionsViewModel = hiltViewModel(),
) {
    val rows = viewModel.items.collectAsLazyPagingItems()
    val showNetwork by viewModel.showNetwork.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    ReportMentionsViewportAtNewest(
        listState = listState,
        onAtNewestChanged = viewModel::reportViewportAtNewest,
    )
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(stringResource(R.string.mentions_title)) },
            )
        },
        floatingActionButton = {
            MentionsNavigationFab(
                listState = listState,
                itemCount = rows.itemCount,
            )
        },
    ) { padding ->
        GlobalFeedContent(
            rows = rows,
            showNetwork = showNetwork,
            onOpenMessage = onOpenMessage,
            modifier = Modifier.padding(padding),
            mentions = true,
            listState = listState,
        )
    }
}

/**
 * A refresh can temporarily clear a LazyColumn's layout. Ignore that empty layout so the last
 * real viewport remains authoritative until Paging presents rows again.
 */
@Composable
internal fun ReportMentionsViewportAtNewest(
    listState: LazyListState,
    onAtNewestChanged: (Boolean) -> Unit,
) {
    val latestCallback by rememberUpdatedState(onAtNewestChanged)
    androidx.compose.runtime.LaunchedEffect(listState) {
        snapshotFlow {
            if (listState.layoutInfo.totalItemsCount == 0) {
                null
            } else {
                !listState.canScrollBackward
            }
        }.filterNotNull()
            .distinctUntilChanged()
            .collect(latestCallback)
    }
}

@Composable
internal fun GlobalFeedContent(
    rows: LazyPagingItems<SearchHit>,
    showNetwork: Boolean,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
    modifier: Modifier = Modifier,
    mentions: Boolean = false,
    listState: LazyListState = rememberLazyListState(),
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
                icon = if (mentions) Icons.Outlined.AlternateEmail else Icons.Outlined.DynamicFeed,
                title = stringResource(if (mentions) R.string.mentions_empty_title else R.string.feed_empty_title),
                message = stringResource(if (mentions) R.string.mentions_empty_message else R.string.feed_empty_message),
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
                mentions = mentions,
                listState = listState,
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
    mentions: Boolean = false,
    listState: LazyListState,
) {
    // One list-scoped formatter: MessageBubble's per-row fallback ignores the app's timestamp
    // preference.
    val formatTime = rememberMessageTimeFormatter()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag(if (mentions) "mentions_list" else "feed_list"),
    ) {
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
                    mentions = mentions,
                )
            }
        }
    }
}

/** Adjacent-mention navigation. Holding Up returns to the newest mention. */
@Composable
internal fun MentionsNavigationFab(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val canScrollUp by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    val canScrollDown by remember(listState) { derivedStateOf { listState.canScrollForward } }
    if (itemCount == 0 || (!canScrollUp && !canScrollDown)) return

    Column(
        modifier = modifier.testTag("mentions_navigation_fab"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canScrollUp) {
            MentionsNavigationFabButton(
                icon = {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.mentions_scroll_up),
                    )
                },
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(listState.navigationTarget(MentionsNavigationDirection.UP, itemCount))
                    }
                },
                onLongClick = { scope.launch { listState.scrollToItem(0) } },
                modifier = Modifier.testTag("mentions_navigation_up"),
            )
        }
        if (canScrollDown) {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(listState.navigationTarget(MentionsNavigationDirection.DOWN, itemCount))
                    }
                },
                modifier = Modifier.testTag("mentions_navigation_down"),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.mentions_scroll_down))
            }
        }
    }
}

private enum class MentionsNavigationDirection { UP, DOWN }

private fun LazyListState.navigationTarget(
    direction: MentionsNavigationDirection,
    itemCount: Int,
): Int =
    when (direction) {
        MentionsNavigationDirection.UP -> {
            (firstVisibleItemIndex - 1).coerceAtLeast(0)
        }

        MentionsNavigationDirection.DOWN -> {
            (firstVisibleItemIndex + 1).coerceAtMost(itemCount - 1)
        }
    }

@Composable
private fun MentionsNavigationFabButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongClick by rememberUpdatedState(onLongClick)
    val jumpToTopLabel = stringResource(R.string.mentions_jump_to_top)
    val holdProgress = remember { Animatable(0f) }
    val ringColor = MaterialTheme.colorScheme.onPrimaryContainer

    fun settle() {
        scope.launch {
            holdProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = MENTIONS_NAVIGATION_FAB_SETTLE_MS, easing = FastOutSlowInEasing),
            )
        }
    }

    FloatingActionButton(
        onClick = latestOnClick,
        modifier =
            modifier
                .semantics {
                    onLongClick(label = jumpToTopLabel) {
                        latestOnLongClick()
                        true
                    }
                }.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        val holdJob =
                            scope.launch {
                                holdProgress.snapTo(0f)
                                holdProgress.animateTo(
                                    1f,
                                    tween(durationMillis = MENTIONS_NAVIGATION_FAB_HOLD_MS, easing = LinearEasing),
                                )
                            }
                        val releaseResult =
                            withTimeoutOrNull(MENTIONS_NAVIGATION_FAB_HOLD_MS.toLong()) {
                                Result.success(waitForUpOrCancellation(PointerEventPass.Initial))
                            }
                        holdJob.cancel()
                        when {
                            releaseResult == null -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                settle()
                                latestOnLongClick()
                                waitForUpOrCancellation(PointerEventPass.Initial)?.consume()
                            }

                            releaseResult.getOrNull() != null -> {
                                releaseResult.getOrNull()?.consume()
                                settle()
                                latestOnClick()
                            }

                            else -> {
                                settle()
                            }
                        }
                    }
                }.drawWithContent {
                    drawContent()
                    val progress = holdProgress.value
                    if (progress > 0f) {
                        val stroke = 3.dp.toPx()
                        val inset = stroke / 2 + 2.dp.toPx()
                        val diameter = minOf(size.width, size.height) - inset * 2
                        if (diameter > 0f) {
                            drawArc(
                                color = ringColor.copy(alpha = 0.22f * (progress * 4f).coerceAtMost(1f)),
                                startAngle = -90f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
                                size = Size(diameter, diameter),
                                style =
                                    androidx.compose.ui.graphics.drawscope
                                        .Stroke(width = stroke),
                            )
                            drawArc(
                                color = ringColor,
                                startAngle = -90f,
                                sweepAngle = 360f * progress,
                                useCenter = false,
                                topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f),
                                size = Size(diameter, diameter),
                                style =
                                    androidx.compose.ui.graphics.drawscope
                                        .Stroke(width = stroke),
                            )
                        }
                    }
                },
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        icon()
    }
}

@Composable
private fun GlobalFeedLineRow(
    row: SearchHit,
    newer: SearchHit?,
    showNetwork: Boolean,
    formatTime: (Long) -> String,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
    mentions: Boolean,
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
    // Mention rows skip intervening chat lines, so each one needs its own conversation label.
    val sameBuffer = !mentions && newerMessage?.bufferId == message.bufferId
    // Headers are drawn above the bubble, so both are decided against the newer row above.
    // showsSender takes the older of the pair — true means this row opens the group.
    val showSender = newerMessage == null || !sameBuffer || showsSender(newerMessage, message)
    Column(modifier = Modifier.fillMaxWidth().testTag("${if (mentions) "mentions" else "feed"}_row_${message.id}")) {
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
            onClickLabel = stringResource(if (mentions) R.string.mentions_open_message else R.string.feed_open_message),
        )
    }
}
