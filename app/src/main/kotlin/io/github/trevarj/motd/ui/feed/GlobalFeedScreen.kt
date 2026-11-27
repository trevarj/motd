package io.github.trevarj.motd.ui.feed

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ai.AiFeature
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.SearchHit
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.visibility.ActivityFilter
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.dickord.LocalDickordLabsEnabled
import io.github.trevarj.motd.dickord.dickordChannelLabel
import io.github.trevarj.motd.dickord.dickordNickLabel
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.ui.ai.AiTextSheet
import io.github.trevarj.motd.ui.ai.AiTextViewModel
import io.github.trevarj.motd.ui.chat.FoolPlaceholder
import io.github.trevarj.motd.ui.chat.messageContentType
import io.github.trevarj.motd.ui.chat.showsSender
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.components.LocalSurfaceBackdrop
import io.github.trevarj.motd.ui.components.MessageBubble
import io.github.trevarj.motd.ui.components.SurfaceBackdropPage
import io.github.trevarj.motd.ui.components.captureSurfaceBackdrop
import io.github.trevarj.motd.ui.components.conversationTag
import io.github.trevarj.motd.ui.components.rememberMessageTimeFormatter
import io.github.trevarj.motd.ui.components.rememberSurfaceBackdropSource
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import io.github.trevarj.motd.ui.components.EffectFloatingActionButton as FloatingActionButton
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

private const val ACTIVITY_NAVIGATION_FAB_HOLD_MS = 450
private const val ACTIVITY_NAVIGATION_FAB_SETTLE_MS = 160

/** Read-only merged stream of conversation lines from every channel and DM, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalFeedScreen(
    onBack: () -> Unit = {},
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit = { _, _, _ -> },
    viewModel: GlobalFeedViewModel = hiltViewModel(),
    aiTextViewModel: AiTextViewModel = hiltViewModel(),
    onOpenAiSetup: () -> Unit = {},
) {
    val rows = viewModel.items.collectAsLazyPagingItems()
    val showNetwork by viewModel.showNetwork.collectAsStateWithLifecycle()
    val aiState by aiTextViewModel.state.collectAsStateWithLifecycle()
    val aiLabs by aiTextViewModel.labsState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, aiTextViewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) aiTextViewModel.close() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            aiTextViewModel.close()
        }
    }
    AiTextSheet(
        aiState,
        aiLabs.customStyles,
        aiLabs.translationTarget,
        aiTextViewModel::generate,
        aiTextViewModel::selectTranslationTarget,
        aiTextViewModel::close,
        onOpenAiSetup,
        onOpenAiSetup,
    )
    val pageBackdrop = rememberSurfaceBackdropSource()
    SurfaceBackdropPage(pageBackdrop) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            topBar = {
                TopAppBar(
                    colors =
                        androidx.compose.material3.TopAppBarDefaults
                            .topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
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
                onTranslateMessage =
                    if (AiFeature.TEXT_TOOLS in aiLabs.enabledFeatures) {
                        { message -> aiTextViewModel.openStoredMessage(message.bufferId, message.id) }
                    } else {
                        null
                    },
                modifier = Modifier.padding(padding),
            )
        }
    }
}

/** Read-only stream of mentions and keyword highlights, with exact-message jumps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    onBack: () -> Unit = {},
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit = { _, _, _ -> },
    viewModel: ActivityViewModel = hiltViewModel(),
    aiTextViewModel: AiTextViewModel = hiltViewModel(),
    onOpenAiSetup: () -> Unit = {},
) {
    val page by viewModel.page.collectAsStateWithLifecycle()
    val showNetwork by viewModel.showNetwork.collectAsStateWithLifecycle()
    val visibilitySpec by viewModel.visibilitySpec.collectAsStateWithLifecycle()
    val aiState by aiTextViewModel.state.collectAsStateWithLifecycle()
    val aiLabs by aiTextViewModel.labsState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, aiTextViewModel) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) aiTextViewModel.close() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            aiTextViewModel.close()
        }
    }
    AiTextSheet(
        aiState,
        aiLabs.customStyles,
        aiLabs.translationTarget,
        aiTextViewModel::generate,
        aiTextViewModel::selectTranslationTarget,
        aiTextViewModel::close,
        onOpenAiSetup,
        onOpenAiSetup,
    )
    ActivityFeed(
        page = page,
        onFilterSelected = viewModel::selectFilter,
        showNetwork = showNetwork,
        visibilitySpec = visibilitySpec,
        onBack = onBack,
        onOpenMessage = onOpenMessage,
        onTranslateMessage =
            if (AiFeature.TEXT_TOOLS in aiLabs.enabledFeatures) {
                { message -> aiTextViewModel.openStoredMessage(message.bufferId, message.id) }
            } else {
                null
            },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityFeed(
    page: ActivityPage,
    onFilterSelected: (ActivityFilter) -> Unit,
    showNetwork: Boolean,
    visibilitySpec: MessageVisibilitySpec,
    onBack: () -> Unit,
    onOpenMessage: (bufferId: Long, eventId: Long, serverTime: Long) -> Unit,
    onTranslateMessage: ((MessageEntity) -> Unit)? = null,
) {
    val rows = page.items.collectAsLazyPagingItems()
    val filter = page.filter
    val listState = key(filter) { rememberLazyListState() }
    ReportActivityViewportAtNewest(
        listState = listState,
        onAtNewestChanged = page.reportViewportAtNewest,
    )
    val pageBackdrop = rememberSurfaceBackdropSource()
    SurfaceBackdropPage(pageBackdrop) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            topBar = {
                TopAppBar(
                    colors =
                        androidx.compose.material3.TopAppBarDefaults
                            .topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    title = { Text(stringResource(R.string.activity_title)) },
                )
            },
            floatingActionButton = {
                ActivityNavigationFab(
                    listState = listState,
                    itemCount = rows.itemCount,
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ActivityFilterChips(filter, onFilterSelected)
                GlobalFeedContent(
                    rows = rows,
                    showNetwork = showNetwork,
                    onOpenMessage = onOpenMessage,
                    activityFilter = filter,
                    visibilitySpec = visibilitySpec,
                    listState = listState,
                    onTranslateMessage = onTranslateMessage,
                )
            }
        }
    }
}

@Composable
internal fun ActivityFilterChips(
    filter: ActivityFilter,
    onFilterSelected: (ActivityFilter) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectableGroup()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActivityFilter.entries.forEach { option ->
            val label =
                when (option) {
                    ActivityFilter.ALL -> R.string.activity_filter_all
                    ActivityFilter.MENTIONS -> R.string.activity_filter_mentions
                    ActivityFilter.KEYWORDS -> R.string.activity_filter_keywords
                }
            val selected = filter == option
            FilterChip(
                selected = selected,
                onClick = { onFilterSelected(option) },
                label = { Text(stringResource(label)) },
                leadingIcon = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = null) }) else null,
                modifier = Modifier.testTag("activity_filter_${option.name.lowercase()}"),
            )
        }
    }
}

/**
 * A refresh can temporarily clear a LazyColumn's layout. Ignore that empty layout so the last
 * real viewport remains authoritative until Paging presents rows again.
 */
@Composable
internal fun ReportActivityViewportAtNewest(
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
    activityFilter: ActivityFilter? = null,
    listState: LazyListState = rememberLazyListState(),
    onTranslateMessage: ((MessageEntity) -> Unit)? = null,
    visibilitySpec: MessageVisibilitySpec = MessageVisibilitySpec(),
) {
    val refresh = rows.loadState.refresh
    when {
        // Error state only when nothing is on screen; loaded lines survive a failed refresh.
        refresh is LoadState.Error && rows.itemCount == 0 -> {
            EmptyState(
                icon = Icons.Outlined.CloudOff,
                title = stringResource(if (activityFilter == null) R.string.feed_error_title else R.string.activity_error_title),
                message = stringResource(R.string.feed_error_message),
                modifier = modifier,
                actionLabel = stringResource(R.string.feed_retry),
                onAction = rows::retry,
            )
        }

        // NotLoading, so the empty state never flashes over an arriving first page.
        rows.itemCount == 0 && refresh is LoadState.NotLoading -> {
            val title =
                when (activityFilter) {
                    null -> R.string.feed_empty_title
                    ActivityFilter.ALL -> R.string.activity_empty
                    ActivityFilter.MENTIONS -> R.string.activity_mentions_empty
                    ActivityFilter.KEYWORDS -> R.string.activity_keywords_empty
                }
            EmptyState(
                icon = if (activityFilter == null) Icons.Outlined.DynamicFeed else Icons.Outlined.Bolt,
                title = stringResource(title),
                message = stringResource(if (activityFilter == null) R.string.feed_empty_message else R.string.activity_empty_message),
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
                activity = activityFilter != null,
                listState = listState,
                onTranslateMessage = onTranslateMessage,
                visibilitySpec = visibilitySpec,
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
    activity: Boolean,
    listState: LazyListState,
    onTranslateMessage: ((MessageEntity) -> Unit)? = null,
    visibilitySpec: MessageVisibilitySpec = MessageVisibilitySpec(),
) {
    // One list-scoped formatter: MessageBubble's per-row fallback ignores the app's timestamp
    // preference.
    val formatTime = rememberMessageTimeFormatter()
    if (activity) {
        // Re-pin before measurement can retain the displaced key, but never disturb a parked viewport.
        val topKey =
            rows.itemSnapshotList.items
                .firstOrNull()
                ?.message
                ?.id
        val previousTop = remember(listState) { ActivityTopItemTracker(topKey) }
        if (topKey != previousTop.key) {
            val repin =
                Snapshot.withoutReadObservation {
                    topKey != null && previousTop.key != null && !listState.canScrollBackward && !listState.isScrollInProgress
                }
            previousTop.key = topKey
            if (repin) listState.requestScrollToItem(0)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().then(LocalSurfaceBackdrop.current?.let { Modifier.captureSurfaceBackdrop(it) } ?: Modifier).testTag(if (activity) "activity_list" else "feed_list"),
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
                    newer = if (!activity && index > 0) rows.peek(index - 1) else null,
                    showNetwork = showNetwork,
                    formatTime = formatTime,
                    onOpenMessage = onOpenMessage,
                    activity = activity,
                    onTranslateMessage = onTranslateMessage,
                    visibilitySpec = visibilitySpec,
                )
            }
        }
    }
}

private class ActivityTopItemTracker(
    var key: Long?,
)

/** Adjacent-activity navigation. Holding Up returns to the newest event. */
@Composable
internal fun ActivityNavigationFab(
    listState: LazyListState,
    itemCount: Int,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val canScrollUp by remember(listState) { derivedStateOf { listState.canScrollBackward } }
    val canScrollDown by remember(listState) { derivedStateOf { listState.canScrollForward } }
    if (itemCount == 0 || (!canScrollUp && !canScrollDown)) return

    Column(
        modifier = modifier.testTag("activity_navigation_fab"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canScrollUp) {
            ActivityNavigationFabButton(
                icon = {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.mentions_scroll_up),
                    )
                },
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(listState.navigationTarget(ActivityNavigationDirection.UP, itemCount))
                    }
                },
                onLongClick = { scope.launch { listState.scrollToItem(0) } },
                modifier = Modifier.testTag("activity_navigation_up"),
            )
        }
        if (canScrollDown) {
            FloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(listState.navigationTarget(ActivityNavigationDirection.DOWN, itemCount))
                    }
                },
                modifier = Modifier.testTag("activity_navigation_down"),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.mentions_scroll_down))
            }
        }
    }
}

private enum class ActivityNavigationDirection { UP, DOWN }

private fun LazyListState.navigationTarget(
    direction: ActivityNavigationDirection,
    itemCount: Int,
): Int =
    when (direction) {
        ActivityNavigationDirection.UP -> {
            (firstVisibleItemIndex - 1).coerceAtLeast(0)
        }

        ActivityNavigationDirection.DOWN -> {
            (firstVisibleItemIndex + 1).coerceAtMost(itemCount - 1)
        }
    }

@Composable
private fun ActivityNavigationFabButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val latestOnClick by rememberUpdatedState(onClick)
    val latestOnLongClick by rememberUpdatedState(onLongClick)
    val jumpToTopLabel = stringResource(R.string.activity_jump_to_top)
    val holdProgress = remember { Animatable(0f) }
    val ringColor = MaterialTheme.colorScheme.onPrimaryContainer

    fun settle() {
        scope.launch {
            holdProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = ACTIVITY_NAVIGATION_FAB_SETTLE_MS, easing = FastOutSlowInEasing),
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
                                    tween(durationMillis = ACTIVITY_NAVIGATION_FAB_HOLD_MS, easing = LinearEasing),
                                )
                            }
                        val releaseResult =
                            withTimeoutOrNull(ACTIVITY_NAVIGATION_FAB_HOLD_MS.toLong()) {
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
    activity: Boolean,
    onTranslateMessage: ((MessageEntity) -> Unit)? = null,
    visibilitySpec: MessageVisibilitySpec,
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
    val policy = remember(visibilitySpec, identityRules) { MessageVisibilityPolicy(visibilitySpec, identityRules) }
    val foolMode = if (activity) policy.foolPreferences(message)?.visibility else null
    if (foolMode != null && !policy.mentionAttention(message, row.bufferType == BufferType.QUERY)) return
    val newerMessage = newer?.message
    // Activity skips intervening chat lines, so each row needs its own conversation label.
    val sameBuffer = !activity && newerMessage?.bufferId == message.bufferId
    // Headers are drawn above the bubble, so both are decided against the newer row above.
    // showsSender takes the older of the pair — true means this row opens the group.
    val showSender = newerMessage == null || !sameBuffer || showsSender(newerMessage, message)
    Column(modifier = Modifier.fillMaxWidth().testTag("${if (activity) "activity" else "feed"}_row_${message.id}")) {
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
        if (foolMode == FoolsMode.COLLAPSE) {
            FoolPlaceholder(sender = displaySender, onExpand = { onOpenMessage(message.bufferId, message.id, message.serverTime) })
            return@Column
        }
        val revealed = remember(message.id, foolMode) { androidx.compose.runtime.mutableStateOf(false) }
        io.github.trevarj.motd.ui.components.FoolMessageScope(
            concealed = foolMode == FoolsMode.BLUR && !revealed.value,
            onReveal = { revealed.value = true },
            tag = "activity_fool_reveal_${message.id}",
        ) {
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
                highlightNick = message.highlightNick,
                highlightCaseMapping = message.highlightCaseMapping,
                dimmed = foolMode == FoolsMode.DIMMED,
                // The contentType pool already splits SELF_FAILED out; the row must render it too.
                failed = message.failed,
                pending = message.pendingLabel != null,
                networkId = row.networkId,
                identityRules = identityRules,
                formattedTime = remember(message.serverTime, formatTime) { formatTime(message.serverTime) },
                onClick = { onOpenMessage(message.bufferId, message.id, message.serverTime) },
                onClickLabel = stringResource(if (activity) R.string.activity_open_message else R.string.feed_open_message),
                onLongPress = { onTranslateMessage?.invoke(message) },
                onLongPressLabel = onTranslateMessage?.let { stringResource(R.string.ai_text_translate_message) },
            )
        }
    }
}
