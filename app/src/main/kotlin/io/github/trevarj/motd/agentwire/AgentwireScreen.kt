package io.github.trevarj.motd.agentwire

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.agentwire.AgentwireTopicDefect
import io.github.trevarj.motd.irc.format.markdownToIrcFormatting
import io.github.trevarj.motd.ui.chat.ScrollToBottomFab
import io.github.trevarj.motd.ui.chat.shouldShowNewestFab
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.mircFormattedText
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

@Composable
fun AgentwireGateScreen(
    onBack: () -> Unit,
    showBack: Boolean,
    showComposerEmoji: Boolean,
    showComposerFormattingTools: Boolean,
    viewModel: AgentwireViewModel = hiltViewModel(),
    ordinaryChat: @Composable () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var composer by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    when {
        state.gate == AgentwireGate.LOADING -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 48.dp))
            }
        }

        state.gate == AgentwireGate.ORDINARY || state.transcriptOverride -> {
            ordinaryChat()
        }

        else -> {
            AgentwireScreen(
                state = state,
                viewModel = viewModel,
                composer = composer,
                onComposerChange = { composer = it },
                onComposerAccepted = { sent -> if (composer == sent) composer = TextFieldValue("") },
                onBack = onBack,
                showBack = showBack,
                showComposerEmoji = showComposerEmoji,
                showComposerFormattingTools = showComposerFormattingTools,
            )
        }
    }
}

private enum class AgentwireSheet { STATUS, QUEUE, QUESTION, LOG }

/** Log sheet filters. Each maps to the kind prefixes [agentwireLogQuery] matches against. */
private enum class AgentwireLogFilter(
    val label: String,
    val kinds: Set<String>,
) {
    ALL("All", emptySet()),
    TOOLS("Tools", setOf("tool")),
    ASSISTANT("Assistant", setOf("assistant", "user")),
    PLAN("Plan", setOf("plan")),
    REQUESTS("Requests", setOf("request")),
    TURNS("Turns", setOf("turn")),
}

private enum class AgentwireStatusTab { BROWSE, SETTINGS }

private const val AGENTWIRE_SEARCH_DEBOUNCE_MS = 300L

internal data class AgentwireWorkspaceRow(
    val item: AgentwireListItem,
    val depth: Int,
    val treeKey: String,
    /** False when the bridge said the directory has no child directories: render a leaf marker. */
    val hasChildren: Boolean = true,
    val expanded: Boolean = false,
    /** Loaded session count when known, else the bridge's cheap `sessionCount` hint. */
    val sessionCount: Int? = null,
)

/**
 * Busy/waiting label for one session. The pushed status registry is newer than any `session.page`
 * snapshot, so it wins whenever an entry exists for the session.
 */
internal fun agentwireSessionRuntimeStatus(
    session: AgentwireListItem,
    status: AgentwireSessionStatus? = null,
): String? {
    val busy = status?.busy ?: session.raw.bool("busy") ?: false
    if (!busy) return null
    val flags = status?.flags ?: session.raw.stringList("flags")
    return if (flags.any { it.startsWith("waiting", ignoreCase = true) }) "Waiting" else "Running"
}

internal fun workspaceRows(
    children: Map<String, List<AgentwireListItem>>,
    expanded: Set<String>,
    sessions: Map<String, List<AgentwireListItem>>,
    loadedSessionDirectories: Set<String> = emptySet(),
    query: String = "",
): List<AgentwireWorkspaceRow> {
    fun visit(
        parent: String,
        depth: Int,
        ancestors: Set<String>,
        branch: String,
    ): List<AgentwireWorkspaceRow> =
        buildList {
            children[parent].orEmpty().forEach { directory ->
                val treeKey = "$branch>${directory.id}"
                val hasChildren = directory.raw.bool("hasChildren") ?: true
                val isExpanded = directory.id in expanded
                // A search reveals matches wherever they are, without disturbing the stored expansion.
                val showChildren = (isExpanded || query.isNotBlank()) && hasChildren
                val descendants =
                    if (showChildren && directory.id !in ancestors) {
                        visit(directory.id, depth + 1, ancestors + directory.id, treeKey)
                    } else {
                        emptyList()
                    }
                val directMatch = directory.title.contains(query, true) || directory.id.contains(query, true)
                val directorySessions = sessions[directory.id].orEmpty()
                val sessionMatch =
                    directorySessions.any { session ->
                        session.title.contains(query, true) || session.id.contains(query, true)
                    }
                if (query.isBlank() || directMatch || sessionMatch || descendants.isNotEmpty()) {
                    add(
                        AgentwireWorkspaceRow(
                            item = directory,
                            depth = depth,
                            treeKey = treeKey,
                            hasChildren = hasChildren,
                            expanded = isExpanded || query.isNotBlank(),
                            sessionCount =
                                if (directory.id in loadedSessionDirectories) {
                                    directorySessions.size
                                } else {
                                    directory.raw.int("sessionCount")
                                },
                        ),
                    )
                    addAll(descendants)
                }
            }
        }
    return visit("", 0, emptySet(), "root")
}

@SuppressLint("HardcodedText")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentwireScreen(
    state: AgentwireUiState,
    viewModel: AgentwireViewModel,
    composer: TextFieldValue,
    onComposerChange: (TextFieldValue) -> Unit,
    onComposerAccepted: (TextFieldValue) -> Unit,
    onBack: () -> Unit,
    showBack: Boolean,
    showComposerEmoji: Boolean,
    showComposerFormattingTools: Boolean,
) {
    var sheet by remember { mutableStateOf<AgentwireSheet?>(null) }
    var questionRequestId by remember { mutableStateOf<String?>(null) }
    var overflow by remember { mutableStateOf(false) }
    val contextReview by viewModel.contextReview.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val stamp = agentwireTimelineStamp(state.timeline, state.requests.size)
    val autoFollow = remember { AgentwireAutoFollow(stamp) }
    // Distinguishes our own scroll animations from user drags so auto-follow intent survives them.
    var programmaticScrolls by remember { mutableIntStateOf(0) }
    var newItems by remember { mutableIntStateOf(0) }
    // Fold overrides for rows and their body sections, keyed on stable timeline keys so an
    // explicit user toggle survives tool lifecycle transitions. Reset with the bound session.
    val expandedKeys = remember(state.activeSid) { mutableStateMapOf<String, Boolean>() }
    val rows = remember(state.timeline) { agentwireDisplayRows(state.timeline) }
    val sessionRows = agentwireDrawerRows(state)

    // A plain animateScrollToItem(last) parks at the TOP of a taller-than-viewport streaming
    // card, so a second phase closes any remaining gap to the last item's bottom edge.
    suspend fun scrollToTimelineBottom(animate: Boolean) {
        autoFollow.requestFollow()
        newItems = 0
        programmaticScrolls++
        try {
            val last = listState.layoutInfo.totalItemsCount - 1
            if (last < 0) return
            if (animate) listState.animateScrollToItem(last) else listState.scrollToItem(last)
            val info = listState.layoutInfo.visibleItemsInfo.lastOrNull() ?: return
            val overshoot = info.offset + info.size - listState.layoutInfo.viewportEndOffset
            if (overshoot > 0) {
                if (animate) listState.animateScrollBy(overshoot.toFloat()) else listState.scrollBy(overshoot.toFloat())
            }
        } finally {
            programmaticScrolls--
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            val atBottom = isAtTimelineBottom(listState.layoutInfo)
            autoFollow.onScrollStateChanged(scrolling, programmaticScrolls > 0, atBottom)
            if (!scrolling && atBottom) newItems = 0
        }
    }
    LaunchedEffect(stamp) {
        val wasFollowing = autoFollow.following
        val previous = autoFollow.presentedStamp
        if (autoFollow.onTimelineChanged(stamp)) {
            scrollToTimelineBottom(animate = true)
        } else if (!wasFollowing && stamp.arrivedSince(previous)) {
            // At the timeline cap an arrival evicts an older row, so the count delta can be zero.
            newItems += maxOf(stamp.rowCount - previous.rowCount, 1)
        }
    }
    LaunchedEffect(state.activeSid) {
        autoFollow.reset(agentwireTimelineStamp(state.timeline, state.requests.size), atBottom = true)
        newItems = 0
        scrollToTimelineBottom(animate = false)
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // Opening the drawer is the refresh gesture: live status is only as fresh as the last page.
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) viewModel.listSessions(live = true)
    }
    BackHandler(enabled = contextReview?.destination != null && !drawerState.isOpen) {
        viewModel.keepContextForLater()
    }
    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = Modifier.testTag("agentwire_drawer"),
        drawerContent = {
            AgentwireSessionDrawer(
                rows = sessionRows,
                attached = state.activeSid != null,
                actions = state.actions,
                onSelect = { row ->
                    viewModel.attachSession(row.sid, row.cwd)
                    scope.launch { drawerState.close() }
                },
                onDetach = {
                    viewModel.detachSession()
                    scope.launch { drawerState.close() }
                },
                onNewSession = {
                    sheet = AgentwireSheet.STATUS
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${state.backend.orEmpty()}  ${state.activeSid?.take(12) ?: "detached"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        },
                        navigationIcon = {
                            Row {
                                if (showBack) {
                                    IconButton(onClick = { if (contextReview?.destination != null) viewModel.keepContextForLater() else onBack() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                                IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                    Icon(Icons.Default.Menu, contentDescription = "Sessions")
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { overflow = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(
                                    text = { Text("Session log") },
                                    leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                                    onClick = {
                                        overflow = false
                                        sheet = AgentwireSheet.LOG
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("View IRC transcript") },
                                    leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                                    onClick = {
                                        overflow = false
                                        viewModel.viewTranscript()
                                    },
                                )
                            }
                        },
                    )
                    AgentwireStatusStrip(state) { sheet = AgentwireSheet.STATUS }
                }
            },
        ) { padding ->
            // Match the regular chat layout: consume overlapping navigation and animated IME
            // insets around the whole content column so the composer remains above the keyboard.
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .navigationBarsPadding()
                        .imePadding(),
            ) {
                if (state.gate == AgentwireGate.INVALID_TOPIC) {
                    AgentwireInvalidTopic(state)
                } else if (state.gate == AgentwireGate.BLOCKED) {
                    AgentwireBlocked(state)
                } else {
                    Column(Modifier.fillMaxSize()) {
                        // Mirrors the pi-subagents TUI widget: a compact fleet header pinned above
                        // the transcript. Outside the LazyColumn, so auto-follow never measures it.
                        if (state.subagents.isNotEmpty()) AgentwireSubagentsCard(state.subagents)
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().testTag("agentwire_timeline"),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                when (val sync = state.sync) {
                                    is AgentwireSyncState.Syncing -> {
                                        item {
                                            LinearProgressIndicator(Modifier.fillMaxWidth())
                                        }
                                    }

                                    is AgentwireSyncState.Failed -> {
                                        item {
                                            AgentwireSyncFailureCard(
                                                sync = sync,
                                                channel = state.channel,
                                                onRetry = viewModel::retrySync,
                                            )
                                        }
                                    }

                                    is AgentwireSyncState.NotJoined -> {
                                        item {
                                            AgentwireNotJoinedCard(state.channel, viewModel::joinAgentwireChannel)
                                        }
                                    }

                                    else -> {}
                                }
                                if (!state.connected && state.timeline.isEmpty()) {
                                    item {
                                        Card(Modifier.fillMaxWidth().padding(16.dp)) {
                                            Column(Modifier.padding(16.dp)) {
                                                Text("Agentwire is offline", style = MaterialTheme.typography.titleMedium)
                                                Text("Structured state will be rebuilt after reconnecting.")
                                                TextButton(onClick = viewModel::viewTranscript) { Text("View IRC transcript") }
                                            }
                                        }
                                    }
                                }
                                if (state.error != null) {
                                    item {
                                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                                            TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                                        }
                                    }
                                }
                                if (state.historyLoading || state.olderHistoryAvailable) {
                                    item {
                                        TextButton(
                                            onClick = viewModel::loadOlderHistory,
                                            enabled = !state.historyLoading,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text(if (state.historyLoading) "Loading history…" else "Load older history")
                                        }
                                    }
                                }
                                items(rows, key = AgentwireDisplayRow::key) { row ->
                                    when (row) {
                                        is AgentwireDisplayRow.Card -> {
                                            AgentwireTimelineCard(
                                                item = row.item,
                                                actionStatus = state.actionStatus[row.item.id],
                                                expandedOverride = expandedKeys[row.key],
                                                onToggleExpanded = { expandedKeys[row.key] = it },
                                            )
                                        }

                                        is AgentwireDisplayRow.Tool -> {
                                            AgentwireToolCard(row.item, row.key, expandedKeys)
                                        }

                                        is AgentwireDisplayRow.ToolRun -> {
                                            AgentwireToolRunCard(row, expandedKeys)
                                        }
                                    }
                                }
                                items(state.requests, key = AgentwireRequest::rid) { request ->
                                    AgentwireRequestCard(
                                        request,
                                        request.sid == null || request.sid == state.activeSid,
                                        "session.attach" in state.actions,
                                        viewModel,
                                    ) {
                                        questionRequestId = request.rid
                                        sheet = AgentwireSheet.QUESTION
                                    }
                                }
                                if (state.queue.isNotEmpty()) {
                                    item {
                                        Surface(
                                            color = MaterialTheme.colorScheme.secondaryContainer,
                                            modifier = Modifier.fillMaxWidth().clickable { sheet = AgentwireSheet.QUEUE },
                                        ) {
                                            Text(
                                                "${state.queue.size} queued  •  tap to edit",
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        }
                                    }
                                }
                                item { Spacer(Modifier.height(8.dp)) }
                            }
                            AgentwireTimelineFab(
                                listState = listState,
                                autoScrolling = programmaticScrolls > 0,
                                newItems = newItems,
                                onJump = { scope.launch { scrollToTimelineBottom(animate = true) } },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                            )
                        }
                        if (state.gate == AgentwireGate.ACTIVE) {
                            contextReview?.let { review ->
                                AgentwireContextCard(
                                    review = review,
                                    state = state,
                                    sessionName = sessionRows.firstOrNull { it.attached }?.title,
                                    canReview = viewModel.canReviewContext(),
                                    onReview = viewModel::reviewContext,
                                    onKeep = viewModel::keepContextForLater,
                                    onDiscard = viewModel::discardContext,
                                    onSessions = { scope.launch { drawerState.open() } },
                                )
                            }
                            val reviewedContext = contextReview?.takeIf { it.destination != null }
                            if (reviewedContext != null) {
                                // Context stays process-local, including the shared field's internal saver.
                                CompositionLocalProvider(LocalSaveableStateRegistry provides null) {
                                    AgentwireContextComposer(
                                        review = reviewedContext,
                                        viewModel = viewModel,
                                        showComposerEmoji = showComposerEmoji,
                                        showComposerFormattingTools = showComposerFormattingTools,
                                    )
                                }
                            } else {
                                AgentwireComposer(
                                    value = composer,
                                    state = state,
                                    showComposerEmoji = showComposerEmoji,
                                    showComposerFormattingTools = showComposerFormattingTools,
                                    onValueChange = onComposerChange,
                                    onSend = {
                                        viewModel.submit(composer.text) {
                                            onComposerAccepted(composer)
                                            scope.launch { scrollToTimelineBottom(animate = true) }
                                        }
                                    },
                                    onCancel = viewModel::cancelTurn,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    when (sheet) {
        AgentwireSheet.STATUS -> {
            AgentwireStatusSheet(state, viewModel) { sheet = null }
        }

        AgentwireSheet.QUEUE -> {
            AgentwireQueueSheet(state, viewModel) { sheet = null }
        }

        AgentwireSheet.QUESTION -> {
            state.requests.firstOrNull { it.rid == questionRequestId }?.let {
                AgentwireQuestionSheet(it, viewModel) { sheet = null }
            }
        }

        AgentwireSheet.LOG -> {
            AgentwireLogSheet(viewModel) { sheet = null }
        }

        null -> {}
    }
}

@SuppressLint("HardcodedText")
@Composable
internal fun AgentwireSessionDrawer(
    rows: List<AgentwireDrawerRow>,
    attached: Boolean,
    actions: Set<String>,
    onSelect: (AgentwireDrawerRow) -> Unit,
    onDetach: () -> Unit,
    onNewSession: () -> Unit,
) {
    val sections = rows.groupBy(AgentwireDrawerRow::section)
    ModalDrawerSheet {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Sessions",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                if (rows.isEmpty()) {
                    item {
                        Text(
                            "No sessions yet",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AgentwireDrawerSection.entries.forEach { section ->
                    val sectionRows = sections[section].orEmpty()
                    if (sectionRows.isEmpty()) return@forEach
                    item(key = "header:$section") {
                        Text(
                            when (section) {
                                AgentwireDrawerSection.BOUND -> "BOUND"
                                AgentwireDrawerSection.LIVE -> "LIVE"
                                AgentwireDrawerSection.RECENT -> "RECENT"
                            },
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(sectionRows, key = { "row:${it.sid}" }) { row ->
                        AgentwireDrawerRowItem(row, "session.attach" in actions, onSelect)
                    }
                }
            }
            HorizontalDivider()
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (attached && "session.detach" in actions) {
                    TextButton(onClick = onDetach, modifier = Modifier.testTag("agentwire_drawer_detach")) {
                        Text("Detach")
                    }
                }
                if ("session.create" in actions) {
                    TextButton(onClick = onNewSession, modifier = Modifier.testTag("agentwire_drawer_new_session")) {
                        Text("New session…")
                    }
                }
            }
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireDrawerRowItem(
    row: AgentwireDrawerRow,
    selectable: Boolean,
    onSelect: (AgentwireDrawerRow) -> Unit,
) {
    Surface(
        color =
            if (row.attached) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        shape = MaterialTheme.shapes.medium,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .then(if (selectable && !row.attached) Modifier.clickable { onSelect(row) } else Modifier)
                .testTag("agentwire_drawer_row_${row.sid}"),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(row.backend, row.directory).joinToString("  •  "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.cwd?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when (row.status) {
                        AgentwireDrawerStatus.WORKING -> "working"
                        AgentwireDrawerStatus.BLOCKED -> "blocked"
                        AgentwireDrawerStatus.IDLE -> "idle"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.activeSubagents > 0) {
                        Text(
                            "${row.activeSubagents} agent${if (row.activeSubagents == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("agentwire_drawer_subagents_${row.sid}"),
                        )
                    }
                    if (row.tuiAttached) Text("TUI", style = MaterialTheme.typography.labelSmall)
                    if (row.attached) Text("Attached", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireSubagentsCard(subagents: List<AgentwireSubagent>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("agentwire_subagents"),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("● Agents", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            subagents.forEach { agent ->
                Column(Modifier.fillMaxWidth().testTag("agentwire_subagent_${agent.id}")) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (agent.status == "running") {
                            CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                when (agent.status) {
                                    "completed" -> "✓"
                                    "failed" -> "✗"
                                    else -> "…"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                        Text(agent.type, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            agent.description,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    agentwireSubagentDetail(agent)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireStatusStrip(
    state: AgentwireUiState,
    onClick: () -> Unit,
) {
    val sync = state.sync
    val status =
        when {
            !state.connected -> {
                "offline"
            }

            sync is AgentwireSyncState.NotJoined -> {
                "not joined"
            }

            sync is AgentwireSyncState.Syncing -> {
                if (sync.attempt >= 3) "syncing… (attempt ${sync.attempt})" else "syncing…"
            }

            sync is AgentwireSyncState.Failed -> {
                "sync failed"
            }

            state.busy -> {
                "running"
            }

            state.activeSid == null -> {
                "detached"
            }

            else -> {
                "ready"
            }
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("agentwire_status_strip"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("● $status", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
            Text(state.cwd ?: "No session", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Text("settings / sessions", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * Names which of the distinguishable sync failures happened, so a stalled handshake is never just
 * a spinner that never stops.
 */
@SuppressLint("HardcodedText")
@Composable
internal fun AgentwireSyncFailureCard(
    sync: AgentwireSyncState.Failed,
    channel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title: String
    val body: String
    when (val failure = sync.failure) {
        is AgentwireSyncFailure.Timeout -> {
            title = "Agent sync timed out"
            body =
                buildString {
                    append(
                        "No response from the agent after 30 seconds. The bridge may be offline, it " +
                            "may not accept this channel's topic, or the channel may not be delivering " +
                            "events to you.",
                    )
                    if (failure.counters.total > 0) {
                        append(" While syncing, ${failure.counters.total} agent event(s) arrived but ")
                        append("were ignored — open diagnostics for details.")
                    }
                }
        }

        is AgentwireSyncFailure.Rejected -> {
            title = "Agent sync rejected"
            body = "The bridge rejected the sync request: ${failure.detail}"
        }

        is AgentwireSyncFailure.ProtocolMismatch -> {
            title = "Incompatible agent messages"
            body = "The agent's replies failed protocol validation: ${failure.detail}. Update motd " +
                "and the bridge so both speak Agentwire v1 the same way."
        }

        is AgentwireSyncFailure.SendFailed -> {
            title = "Cannot reach the channel"
            body = "Sending the sync request failed: ${failure.detail}"
        }
    }
    AgentwireSyncFailureBody(title, body, "Retry sync", onRetry, modifier)
}

/** The gate is active but this device is not in the channel, so no handshake is even attempted. */
@SuppressLint("HardcodedText")
@Composable
internal fun AgentwireNotJoinedCard(
    channel: String,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) = AgentwireSyncFailureBody(
    title = "Not in this channel",
    body =
        "Agent events flow through $channel, but you are not joined to it, so nothing the " +
            "agent sends can reach you.",
    action = "Join and sync",
    onAction = onJoin,
    modifier = modifier,
)

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireSyncFailureBody(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier.fillMaxWidth().padding(16.dp).testTag("agentwire_sync_failure_card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onAction, modifier = Modifier.testTag("agentwire_sync_retry")) {
                Text(action)
            }
        }
    }
}

/** Names the one defect keeping a marked channel from activating, and the shape that would fix it. */
@SuppressLint("HardcodedText")
@Composable
internal fun AgentwireInvalidTopic(
    state: AgentwireUiState,
    modifier: Modifier = Modifier,
) {
    val defect = state.topicDefect ?: return
    val named = defect.fields.joinToString(", ")
    val explanation =
        when (defect.defect) {
            AgentwireTopicDefect.MISSING_FIELD -> {
                "The topic does not set ${defect.fields.joinToString(", ") { "$it=" }}."
            }

            AgentwireTopicDefect.MALFORMED_PARAMETER -> {
                "One of the topic's parameters is not a key=value pair."
            }

            AgentwireTopicDefect.DUPLICATE_PARAMETER -> {
                "The topic sets $named more than once."
            }

            AgentwireTopicDefect.INVALID_ENCODING -> {
                "The value of $named is not valid percent-encoded UTF-8."
            }
        }
    Column(
        modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag("agentwire_invalid_topic"),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("This channel's agent topic needs a fix", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "The topic starts with the Agentwire marker, so this channel was meant to run an " +
                "agent. It cannot start until the marker is complete.",
        )
        Spacer(Modifier.height(12.dp))
        Text(explanation, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Text("A complete marker looks like:", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        SelectionContainer {
            Text(
                "agentwire:v1;account=<owner>;agent=<bot>;backend=<engine> | Title",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "account= and agent= are IRC account names, never engine names: account= is the " +
                "account the bridge takes orders from, and agent= is the account whose messages " +
                "this client trusts. Only backend= names the engine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "The bridge also announces a corrected topic as a notice in this channel. Open " +
                "\"View IRC transcript\" from the menu to read it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireBlocked(
    state: AgentwireUiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Agentwire unavailable", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text("This channel is activated, but the IRC connection did not negotiate every required capability.")
        Spacer(Modifier.height(12.dp))
        Text(state.missingCaps.sorted().joinToString("\n"), fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun AgentwireContextCard(
    review: AgentwireContextReview,
    state: AgentwireUiState,
    sessionName: String?,
    canReview: Boolean,
    onReview: () -> Unit,
    onKeep: () -> Unit,
    onDiscard: () -> Unit,
    onSessions: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("agentwire_context_review")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.agentwire_context_source, review.share.sourceLabel), style = MaterialTheme.typography.titleSmall)
            Text(review.share.coverage, style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.agentwire_context_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            val selected = review.destination
            if (selected != null) {
                Text(
                    stringResource(R.string.agentwire_context_selected, selected.channel, sessionName ?: selected.sid, selected.backend),
                    style = MaterialTheme.typography.labelMedium,
                )
                val bytes =
                    remember(review.share.prompt) {
                        review.share.prompt
                            .toByteArray(Charsets.UTF_8)
                            .size
                    }
                Text(
                    stringResource(R.string.agentwire_context_bytes, bytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bytes > AGENTWIRE_MAX_PROMPT_BYTES) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onKeep) { Text(stringResource(R.string.agentwire_context_keep)) }
            } else {
                if (state.activeSid == null || !canReview) {
                    Text(
                        stringResource(
                            if (state.activeSid == null) R.string.agentwire_context_session_needed else R.string.agentwire_context_not_ready,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onReview, enabled = canReview && !review.sending, modifier = Modifier.weight(1f)) {
                        Text(
                            if (state.activeSid == null) {
                                stringResource(R.string.agentwire_context_resume)
                            } else {
                                stringResource(R.string.agentwire_context_review, sessionName ?: state.activeSid)
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onSessions) {
                        Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.agentwire_context_session_needed))
                    }
                    TextButton(onClick = onDiscard, enabled = !review.sending) {
                        Text(stringResource(R.string.agentwire_context_discard))
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentwireContextComposer(
    review: AgentwireContextReview,
    viewModel: AgentwireViewModel,
    showComposerEmoji: Boolean,
    showComposerFormattingTools: Boolean,
) {
    var value by remember { mutableStateOf(TextFieldValue(review.share.prompt)) }
    LaunchedEffect(review.share.prompt) {
        if (value.text != review.share.prompt) value = TextFieldValue(review.share.prompt)
    }
    Composer(
        value = value,
        onValueChange = { if (viewModel.editContext(it.text)) value = it },
        onSend = viewModel::submitContext,
        enabled = !review.sending,
        modifier = Modifier.testTag("agentwire_context_composer"),
        placeholder = stringResource(R.string.agentwire_context_placeholder),
        showEmojiTool = showComposerEmoji,
        showFormattingTools = showComposerFormattingTools,
        voiceEnabled = false,
    )
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireComposer(
    value: TextFieldValue,
    state: AgentwireUiState,
    showComposerEmoji: Boolean,
    showComposerFormattingTools: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(shadowElevation = 4.dp) {
        Column(Modifier.fillMaxWidth()) {
            if (state.busy) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val mode = state.settings["delivery"] ?: "queue"
                    AssistChip(onClick = {}, label = { Text(mode.replaceFirstChar(Char::uppercase)) })
                    Spacer(Modifier.weight(1f))
                    if ("turn.cancel" in state.actions) {
                        TextButton(onClick = onCancel) { Text("Cancel turn") }
                    }
                }
            }
            Composer(
                value = value,
                onValueChange = onValueChange,
                onSend = onSend,
                enabled = state.connected && state.activeSid != null,
                modifier = Modifier.testTag("agentwire_composer"),
                placeholder = if (state.busy) "Queue or steer the running turn" else "Message the agent",
                showEmojiTool = showComposerEmoji,
                showFormattingTools = showComposerFormattingTools,
                // Voice and attachments stay on the shared composer boundary. They can be enabled
                // when Agentwire defines safe attachment payloads instead of inventing a wire form.
                voiceEnabled = false,
            )
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
internal fun AgentwireTimelineCard(
    item: AgentwireTimelineItem,
    actionStatus: String?,
    expandedOverride: Boolean?,
    onToggleExpanded: (Boolean) -> Unit,
) {
    // Tool kinds never reach this card; they render as compact rows. The override is the user's
    // explicit fold choice, hoisted so it survives the item's lifecycle transitions.
    val collapsible = item.kind == "plan.updated" || item.kind == "usage.updated"
    val expanded = expandedOverride ?: (item.running || item.kind == "assistant.completed")
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (item.kind == "user.prompt") {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(
                    if (collapsible) {
                        Modifier.semantics {
                            role = Role.Button
                            stateDescription = if (expanded) "Expanded" else "Collapsed"
                        }
                    } else {
                        Modifier
                    },
                ).clickable(enabled = collapsible) { onToggleExpanded(!expanded) }
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    color = if (item.success == false) MaterialTheme.colorScheme.error else Color.Unspecified,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val metadata =
                    listOfNotNull(
                        actionStatus,
                        if (item.historical) "history" else null,
                        agentwireTimestamp(item.at),
                    ).joinToString(" • ")
                if (metadata.isNotEmpty()) {
                    Text(
                        metadata,
                        modifier = Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            if (item.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (!collapsible || expanded) {
                item.body?.let { body ->
                    val assistant = item.kind.startsWith("assistant.")
                    val text =
                        remember(body, assistant) {
                            if (assistant) mircFormattedText(markdownToIrcFormatting(body)) else AnnotatedString(body)
                        }
                    Text(
                        text,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (collapsible && !expanded) {
                Text("▸ Tap to expand", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The scroll-to-bottom affordance, isolated so the hot layout read restarts only this scope. */
@Composable
private fun AgentwireTimelineFab(
    listState: LazyListState,
    autoScrolling: Boolean,
    newItems: Int,
    onJump: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val atBottom by remember(listState) { derivedStateOf { isAtTimelineBottom(listState.layoutInfo) } }
    ScrollToBottomFab(
        visible = shouldShowNewestFab(atBottom, autoScrolling),
        unread = newItems,
        mentionPending = false,
        onClick = onJump,
        onLongClick = onJump,
        modifier = modifier,
    )
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireToolStatusGlyph(item: AgentwireTimelineItem) {
    when {
        item.running -> {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }

        else -> {
            val (glyph, color) =
                when (item.success) {
                    true -> "✓" to MaterialTheme.colorScheme.tertiary
                    false -> "✗" to MaterialTheme.colorScheme.error
                    null -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
                }
            Text(glyph, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, color = color)
        }
    }
}

/** One compact line per tool call; tapping it unfolds the full body (command/output/diff). */
@SuppressLint("HardcodedText")
@Composable
private fun AgentwireToolRow(
    item: AgentwireTimelineItem,
    rowKey: String,
    expandedKeys: SnapshotStateMap<String, Boolean>,
) {
    val expanded = expandedKeys[rowKey] ?: item.running
    Column(
        Modifier
            .fillMaxWidth()
            .semantics {
                role = Role.Button
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            }.clickable { expandedKeys[rowKey] = !expanded }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AgentwireToolStatusGlyph(item)
            Text(
                item.title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val exitCode = item.data.int("exitCode")
            val failed = exitCode != null && exitCode != 0
            Text(
                if (failed) "exit $exitCode" else agentwireTimestamp(item.at),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) AgentwireToolBody(item, rowKey, expandedKeys)
    }
}

/** A single tool call outside a foldable run — usually the one currently running. */
@Composable
private fun AgentwireToolCard(
    item: AgentwireTimelineItem,
    rowKey: String,
    expandedKeys: SnapshotStateMap<String, Boolean>,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            AgentwireToolRow(item, rowKey, expandedKeys)
            if (item.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** Consecutive settled tools folded behind one summary header, SystemEventPill-style. */
@SuppressLint("HardcodedText")
@Composable
private fun AgentwireToolRunCard(
    run: AgentwireDisplayRow.ToolRun,
    expandedKeys: SnapshotStateMap<String, Boolean>,
) {
    val expanded = expandedKeys[run.key] ?: false
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = MotdMotion.contentSize)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        stateDescription = if (expanded) "Expanded" else "Collapsed"
                    }.clickable { expandedKeys[run.key] = !expanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    if (expanded) "▾" else "▸",
                    style = MaterialTheme.typography.labelLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Ran ${run.tools.size} tools",
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (run.failedCount > 0) {
                    Text(
                        "${run.failedCount} failed",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (expanded) {
                run.tools.forEach { tool ->
                    // Same key a standalone Tool row would get, so a row's body expansion
                    // survives the run growing or the tool leaving the fold.
                    AgentwireToolRow(tool, "tool:${tool.timelineKey()}", expandedKeys)
                }
            }
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireToolBody(
    item: AgentwireTimelineItem,
    rowKey: String,
    expandedKeys: SnapshotStateMap<String, Boolean>,
) {
    val command = item.data.string("input")
    val output = item.data.string("output")
    val diff = item.data.string("diff")
    val status =
        listOfNotNull(
            item.data.string("status"),
            item.data.int("exitCode")?.let { "exit $it" },
        ).joinToString(" · ").ifBlank { null }

    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        command?.let { ToolTextSection("Command", it, "$rowKey#command", expandedKeys) }
        output?.let { ToolTextSection("Output", it, "$rowKey#output", expandedKeys) }
        diff?.let { AgentwireGitDiff(item.id, it, "$rowKey#diff", expandedKeys) }
        status?.let {
            Text(it, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
        }
        if (command == null && output == null && diff == null && status == null) {
            item.body?.let {
                SelectionContainer {
                    Text(it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/** Centered tap target for revealing/hiding the truncated middle of long content. */
@Composable
private fun HiddenLinesMarker(
    label: String,
    onClick: () -> Unit,
) {
    Text(
        label,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@SuppressLint("HardcodedText")
@Composable
private fun ToolTextSection(
    label: String,
    content: String,
    sectionKey: String,
    expandedKeys: SnapshotStateMap<String, Boolean>,
) {
    val truncated = remember(content) { truncateMiddle(content.lines()) }
    val showAll = expandedKeys[sectionKey] ?: false
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        if (truncated.hiddenCount == 0 || showAll) {
            SelectionContainer {
                Text(content, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            }
            if (truncated.hiddenCount > 0) {
                HiddenLinesMarker("Show fewer lines") { expandedKeys[sectionKey] = false }
            }
        } else {
            SelectionContainer {
                Text(
                    truncated.head.joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            HiddenLinesMarker("… ${truncated.hiddenCount} lines hidden — tap to show …") {
                expandedKeys[sectionKey] = true
            }
            SelectionContainer {
                Text(
                    truncated.tail.joinToString("\n"),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

private enum class DiffLineKind { HEADER, HUNK, ADDED, REMOVED, META, CONTEXT }

private val legacyDiffHeader = Regex("""^\{'type': '(add|delete|update)'(?:, .*)?\}\s+(.+)$""")

internal fun normalizeAgentwireDiff(diff: String): String {
    val normalized = diff.replace("\r\n", "\n").replace('\r', '\n').trim('\n')
    if (normalized.startsWith("diff --git ")) return normalized
    val lines = normalized.lines()
    val match = legacyDiffHeader.matchEntire(lines.firstOrNull().orEmpty()) ?: return normalized
    val kind = match.groupValues[1]
    val path = match.groupValues[2]
    val displayPath = path.removePrefix("/")
    val content = lines.drop(1).joinToString("\n")
    val oldPath = if (kind == "add") "/dev/null" else "a/$displayPath"
    val newPath = if (kind == "delete") "/dev/null" else "b/$displayPath"
    val headers = "diff --git a/$displayPath b/$displayPath\n--- $oldPath\n+++ $newPath"
    if (content.isEmpty()) return headers
    if (kind == "update") return "$headers\n$content"
    val contentLines = content.lines()
    val hunk =
        if (kind == "add") {
            "@@ -0,0 +1,${contentLines.size} @@"
        } else {
            "@@ -1,${contentLines.size} +0,0 @@"
        }
    val prefix = if (kind == "add") "+" else "-"
    return "$headers\n$hunk\n${contentLines.joinToString("\n") { "$prefix$it" }}"
}

private fun diffLineKind(line: String): DiffLineKind =
    when {
        line.startsWith("diff --git ") || line.startsWith("index ") -> DiffLineKind.HEADER
        line.startsWith("@@") -> DiffLineKind.HUNK
        line.startsWith("+++") -> DiffLineKind.ADDED
        line.startsWith("---") -> DiffLineKind.REMOVED
        line.startsWith('+') -> DiffLineKind.ADDED
        line.startsWith('-') -> DiffLineKind.REMOVED
        line.startsWith("\\ No newline") -> DiffLineKind.META
        else -> DiffLineKind.CONTEXT
    }

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireGitDiff(
    itemId: String,
    diff: String,
    sectionKey: String,
    expandedKeys: SnapshotStateMap<String, Boolean>,
) {
    val normalizedDiff = normalizeAgentwireDiff(diff)
    val truncated = remember(normalizedDiff) { truncateMiddle(normalizedDiff.lines()) }
    val showAll = expandedKeys[sectionKey] ?: false
    // One scroll state shared by the head and tail blocks so they pan together.
    val lineScroll = rememberScrollState()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Diff", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            modifier = Modifier.fillMaxWidth().testTag("agentwire_diff_$itemId"),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                if (truncated.hiddenCount == 0 || showAll) {
                    AgentwireDiffLines(normalizedDiff.lines(), lineScroll)
                    if (truncated.hiddenCount > 0) {
                        HiddenLinesMarker("Show fewer lines") { expandedKeys[sectionKey] = false }
                    }
                } else {
                    AgentwireDiffLines(truncated.head, lineScroll)
                    HiddenLinesMarker("… ${truncated.hiddenCount} lines hidden — tap to show …") {
                        expandedKeys[sectionKey] = true
                    }
                    AgentwireDiffLines(truncated.tail, lineScroll)
                }
            }
        }
    }
}

@Composable
private fun AgentwireDiffLines(
    lines: List<String>,
    scroll: ScrollState,
) {
    SelectionContainer {
        Column(Modifier.horizontalScroll(scroll)) {
            lines.forEach { line ->
                val kind = diffLineKind(line)
                val background =
                    when (kind) {
                        DiffLineKind.ADDED -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
                        DiffLineKind.REMOVED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
                        DiffLineKind.HUNK -> MaterialTheme.colorScheme.secondaryContainer
                        DiffLineKind.HEADER -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                val color =
                    when (kind) {
                        DiffLineKind.ADDED -> MaterialTheme.colorScheme.onTertiaryContainer
                        DiffLineKind.REMOVED -> MaterialTheme.colorScheme.onErrorContainer
                        DiffLineKind.HUNK -> MaterialTheme.colorScheme.onSecondaryContainer
                        DiffLineKind.META -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                Text(
                    text = line.ifEmpty { " " },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .background(background)
                            .padding(horizontal = 8.dp, vertical = 1.dp),
                    color = color,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight =
                        if (kind == DiffLineKind.HEADER || kind == DiffLineKind.HUNK) {
                            FontWeight.SemiBold
                        } else {
                            FontWeight.Normal
                        },
                    softWrap = false,
                )
            }
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireRequestCard(
    request: AgentwireRequest,
    canRespond: Boolean,
    canAttach: Boolean,
    viewModel: AgentwireViewModel,
    openQuestions: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("agentwire_request_${request.rid}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (request.type == "approval") "Approval required" else "Question", fontWeight = FontWeight.Bold)
            if (request.redacted) {
                // An approval stays answerable while redacted; a question does not, and saying so
                // is the difference between a considered limit and a card with a missing button.
                Text(
                    if (request.type == "approval") {
                        "Sensitive details were redacted. Review carefully."
                    } else {
                        "Sensitive details were redacted, so this question cannot be answered here. " +
                            "Skip it, or answer it where the session is running."
                    },
                    color = MaterialTheme.colorScheme.error,
                )
            }
            request.summary?.let { Text(it) }
            if (request.inactive) Text("This request belongs to an inactive session: ${request.sid}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (request.type == "approval") {
                    Button(onClick = { viewModel.respondApproval(request.rid, true) }, enabled = canRespond) { Text("Allow once") }
                    OutlinedButton(onClick = { viewModel.respondApproval(request.rid, false) }, enabled = canRespond) { Text("Deny") }
                } else if (!request.redacted) {
                    Button(onClick = openQuestions, enabled = canRespond) { Text("Answer") }
                }
                if (request.canSkip) TextButton(onClick = { viewModel.skipRequest(request.rid) }, enabled = canRespond) { Text("Skip") }
                if (canAttach && request.inactive && request.sid != null) {
                    TextButton(onClick = { viewModel.attachSession(request.sid) }) { Text("Reattach") }
                }
            }
        }
    }
}

@SuppressLint("HardcodedText")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentwireQueueSheet(
    state: AgentwireUiState,
    viewModel: AgentwireViewModel,
    dismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = dismiss) {
        SheetSystemBars()
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Queue", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = viewModel::clearQueue) { Text("Clear") }
            }
            state.queue.forEachIndexed { index, item ->
                var text by remember(item.iid, item.content) { mutableStateOf(item.content) }
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Item ${index + 1}") })
                        Row {
                            TextButton(onClick = { viewModel.moveQueue(item.iid, (index - 1).coerceAtLeast(0)) }, enabled = index > 0) { Text("Up") }
                            TextButton(onClick = { viewModel.moveQueue(item.iid, (index + 1).coerceAtMost(state.queue.lastIndex)) }, enabled = index < state.queue.lastIndex) { Text("Down") }
                            TextButton(onClick = { viewModel.editQueue(item.iid, text) }, enabled = text != item.content) { Text("Save") }
                            TextButton(onClick = { viewModel.deleteQueue(item.iid) }) { Text("Delete") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Queries the bounded payload log. Entries are read on open and whenever the store's revision
 * advances, so the thousands of retained payloads never travel through [AgentwireUiState].
 */
@SuppressLint("HardcodedText")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentwireLogSheet(
    viewModel: AgentwireViewModel,
    dismiss: () -> Unit,
) {
    val revision by viewModel.logRevision.collectAsStateWithLifecycle()
    val entries = remember(revision) { viewModel.logEntries() }
    var search by remember { mutableStateOf("") }
    var appliedSearch by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(AgentwireLogFilter.ALL) }
    // Section fold overrides, shared by every row so the sheet keeps one source of truth.
    val expandedKeys = remember { mutableStateMapOf<String, Boolean>() }

    // The field echoes every keystroke; filtering thousands of payloads waits for a typing pause.
    LaunchedEffect(search) {
        delay(AGENTWIRE_SEARCH_DEBOUNCE_MS)
        appliedSearch = search
    }
    val results =
        remember(entries, appliedSearch, filter) {
            agentwireLogQuery(entries, appliedSearch, filter.kinds)
        }
    ModalBottomSheet(
        onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.fillMaxHeight().testTag("agentwire_log_sheet"),
    ) {
        SheetSystemBars()
        LazyColumn(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text("Session log", style = MaterialTheme.typography.titleLarge) }
            item {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    label = { Text("Search captured payloads") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("agentwire_log_search"),
                )
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AgentwireLogFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            label = { Text(option.label) },
                        )
                    }
                }
            }
            if (results.isEmpty()) {
                item {
                    Text(
                        if (entries.isEmpty()) {
                            "Nothing captured for this session yet"
                        } else {
                            "No matching entries"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(results, key = AgentwireLogEntry::id) { entry ->
                AgentwireLogRow(entry, expandedKeys)
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireLogRow(
    entry: AgentwireLogEntry,
    expandedKeys: SnapshotStateMap<String, Boolean>,
) {
    val rowKey = "log:${entry.id}"
    var expanded by rememberSaveable(entry.id) { mutableStateOf(false) }
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("agentwire_log_row_${entry.id}")
                .clickable {
                    expanded = !expanded
                    // The log exists to answer for the whole payload, so an opened row starts
                    // unfolded; the section markers still fold it back.
                    if (expanded) {
                        listOf("command", "output", "diff", "body").forEach {
                            expandedKeys["$rowKey#$it"] = true
                        }
                    }
                },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (entry.success == false) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.title,
                    modifier = Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(
                        entry.status,
                        entry.exitCode?.let { "exit $it" },
                        agentwireTimestamp(entry.at),
                    ).joinToString(" · "),
                    modifier = Modifier.padding(start = 8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            if (expanded) {
                entry.input?.let { ToolTextSection("Command", it, "$rowKey#command", expandedKeys) }
                entry.output?.let { ToolTextSection("Output", it, "$rowKey#output", expandedKeys) }
                entry.diff?.let { AgentwireGitDiff(rowKey, it, "$rowKey#diff", expandedKeys) }
                entry.body?.let { ToolTextSection(entry.kind, it, "$rowKey#body", expandedKeys) }
            } else {
                Text(
                    entry.kind,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@SuppressLint("HardcodedText")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentwireQuestionSheet(
    request: AgentwireRequest,
    viewModel: AgentwireViewModel,
    dismiss: () -> Unit,
) {
    val answers = remember(request.rid) { mutableStateMapOf<String, Set<String>>() }
    val customAnswers = remember(request.rid) { mutableStateMapOf<String, String>() }
    val submission = agentwireQuestionAnswers(request.questions, answers, customAnswers)
    ModalBottomSheet(onDismissRequest = dismiss) {
        SheetSystemBars()
        Column(
            Modifier.fillMaxWidth().padding(16.dp).testTag("agentwire_question_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Questions", style = MaterialTheme.typography.titleLarge)
            request.questions.forEach { question ->
                Text(question.header ?: question.prompt, fontWeight = FontWeight.SemiBold)
                if (question.header != null) Text(question.prompt)
                question.options.forEachIndexed { index, option ->
                    val selected = option in answers[question.id].orEmpty()
                    val choose = {
                        val current = answers[question.id].orEmpty()
                        answers[question.id] =
                            if (question.multiple) {
                                if (option in current) current - option else current + option
                            } else {
                                setOf(option)
                            }
                    }
                    val tag = "agentwire_question_option_${question.id}_$index"
                    // One answer reads as a picker like the settings sheet; many stay chips.
                    if (question.multiple) {
                        FilterChip(
                            selected = selected,
                            onClick = choose,
                            label = { Text(option) },
                            modifier = Modifier.testTag(tag),
                        )
                    } else {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = choose),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = choose, modifier = Modifier.testTag(tag))
                            Text(option)
                        }
                    }
                }
                if (question.custom || question.options.isEmpty()) {
                    OutlinedTextField(
                        value = customAnswers[question.id].orEmpty(),
                        onValueChange = { customAnswers[question.id] = it },
                        label = { Text("Your own answer") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Button(
                onClick = {
                    submission?.let { viewModel.respondQuestions(request.rid, it) }
                    dismiss()
                },
                enabled = submission != null,
                modifier = Modifier.testTag("agentwire_question_submit"),
            ) { Text("Submit answers") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Answers for every question, or null while any one is still unanswered so the sheet can hold its
 * submit button disabled.
 *
 * Free-form text wins over a selection on a single-answer question: typing is the deliberate escape
 * from the offered options, and the bridge routes it back through pi's "Other (free-form)" row. Each
 * question keeps its own array because the bridge reads the first value of each.
 */
internal fun agentwireQuestionAnswers(
    questions: List<AgentwireQuestion>,
    selections: Map<String, Set<String>>,
    customs: Map<String, String>,
): List<JsonArray>? =
    questions.map { question ->
        val custom = customs[question.id]?.takeIf(String::isNotBlank)
        // Offered order, not selection order, keeps a multi-answer reply readable.
        val chosen = question.options.filter { it in selections[question.id].orEmpty() }
        val values = if (custom != null && !question.multiple) listOf(custom) else chosen + listOfNotNull(custom)
        if (values.isEmpty()) return null
        JsonArray(values.map(::JsonPrimitive))
    }

@SuppressLint("HardcodedText")
@Composable
internal fun AgentwireCloseChannel(onClose: () -> Unit) {
    var confirming by remember { mutableStateOf(false) }
    TextButton(
        onClick = { confirming = true },
        modifier = Modifier.testTag("agentwire_close_channel"),
    ) { Text("Close channel") }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Close channel?") },
            text = { Text("Owned Pi process stops. Session remains resumable from saved history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        onClose()
                    },
                    modifier = Modifier.testTag("agentwire_close_confirm"),
                ) { Text("Close") }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirming = false },
                    modifier = Modifier.testTag("agentwire_close_cancel"),
                ) { Text("Cancel") }
            },
        )
    }
}

@SuppressLint("HardcodedText")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentwireStatusSheet(
    state: AgentwireUiState,
    viewModel: AgentwireViewModel,
    dismiss: () -> Unit,
) {
    var tab by remember { mutableStateOf(AgentwireStatusTab.BROWSE) }
    var search by remember { mutableStateOf("") }
    var appliedSearch by remember { mutableStateOf("") }
    // The field echoes every keystroke; filtering the tree waits for a pause in typing.
    LaunchedEffect(search) {
        delay(AGENTWIRE_SEARCH_DEBOUNCE_MS)
        appliedSearch = search
    }
    // The browser opens full height: a directory tree is unusable in a half sheet.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialModel =
        state.settings["model"]?.takeIf(String::isNotBlank)
            ?: state.modelOptions.firstOrNull { it.default }?.value
            ?: state.modelOptions
                .firstOrNull()
                ?.value
                .orEmpty()
    var model by remember(state.settings["model"], state.modelOptions) { mutableStateOf(initialModel) }
    val initialOption = state.modelOptions.firstOrNull { it.value == model }
    var effort by remember(state.settings["effort"], initialOption) {
        mutableStateOf(
            state.settings["effort"]?.takeIf { it in initialOption?.efforts.orEmpty() }
                ?: initialOption?.defaultEffort?.takeIf { it in initialOption.efforts }
                ?: initialOption?.efforts?.firstOrNull().orEmpty(),
        )
    }
    var delivery by remember(state.settings["delivery"]) {
        mutableStateOf(state.settings["delivery"] ?: "queue")
    }
    var collaboration by remember(state.settings["collaboration"]) {
        mutableStateOf(state.settings["collaboration"] ?: "default")
    }
    var modelMenu by remember { mutableStateOf(false) }
    var confirmAutoReview by remember { mutableStateOf(false) }
    val canBrowse = "workspace.list.request" in state.actions
    LaunchedEffect(canBrowse) {
        if (canBrowse) viewModel.refreshSessionBrowser()
    }
    val rows =
        workspaceRows(
            children = state.workspaceChildren,
            expanded = state.expandedDirectories,
            sessions = state.workspaceSessions,
            loadedSessionDirectories = state.loadedSessionDirectories,
            query = appliedSearch,
        )
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState, modifier = Modifier.fillMaxHeight()) {
        SheetSystemBars()
        LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Agent session", style = MaterialTheme.typography.titleLarge) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tab == AgentwireStatusTab.BROWSE,
                        onClick = { tab = AgentwireStatusTab.BROWSE },
                        label = { Text("Browse") },
                    )
                    FilterChip(
                        selected = tab == AgentwireStatusTab.SETTINGS,
                        onClick = { tab = AgentwireStatusTab.SETTINGS },
                        label = { Text("Settings") },
                    )
                }
            }
            if (tab == AgentwireStatusTab.BROWSE) {
                item {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("Find a project or session") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("agentwire_session_search"),
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.cwd ?: "No workspace selected", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = viewModel::refreshSessionBrowser) { Text("Refresh") }
                        if (state.activeSid != null && "session.detach" in state.actions) {
                            TextButton(onClick = viewModel::detachSession) { Text("Detach") }
                        }
                        if (state.activeSid != null && "session.close" in state.actions) {
                            AgentwireCloseChannel {
                                viewModel.closeSession()
                                dismiss()
                            }
                        }
                    }
                }
                item {
                    AgentwireLiveSessions(
                        sessions =
                            state.liveSessions.filter { session ->
                                appliedSearch.isBlank() ||
                                    session.title.contains(appliedSearch, true) ||
                                    session.id.contains(appliedSearch, true) ||
                                    session.subtitle?.contains(appliedSearch, true) == true
                            },
                        activeSid = state.activeSid,
                        actions = state.actions,
                        statuses = state.sessionStatuses,
                        onAttach = { sid, cwd ->
                            viewModel.attachSession(sid, cwd)
                            dismiss()
                        },
                        onRename = viewModel::renameSession,
                        onFork = viewModel::forkSession,
                        onArchive = viewModel::archiveSession,
                    )
                }
                if (rows.isEmpty()) {
                    item {
                        Text(
                            if (appliedSearch.isBlank()) {
                                "No project directories advertised by the bridge"
                            } else {
                                "No matching projects"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                rows.forEach { row ->
                    val directory = row.item
                    val expanded = row.expanded
                    item(key = "directory:${row.treeKey}") {
                        Surface(
                            color = if (directory.id == state.cwd) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = (row.depth * 14).dp)
                                    .testTag("agentwire_browser_directory_${directory.id}")
                                    .clickable {
                                        viewModel.toggleWorkspaceExpansion(directory.id, row.hasChildren)
                                    },
                        ) {
                            Row(Modifier.padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                // A leaf directory has nothing to unfold, so it never offers a chevron.
                                Text(
                                    when {
                                        !row.hasChildren -> "·"
                                        expanded -> "▾"
                                        else -> "▸"
                                    },
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(directory.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(directory.id, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (row.sessionCount != null) {
                                    Text(
                                        if (row.sessionCount == 1) "1 session" else "${row.sessionCount} sessions",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier =
                                            Modifier
                                                .padding(horizontal = 4.dp)
                                                .testTag("agentwire_browser_count_${directory.id}"),
                                    )
                                }
                                if ("session.create" in state.actions) {
                                    TextButton(
                                        onClick = {
                                            viewModel.createSession(directory.id)
                                            dismiss()
                                        },
                                    ) { Text("Start") }
                                }
                            }
                        }
                    }
                    if (expanded) {
                        val matchingSessions =
                            state.workspaceSessions[directory.id].orEmpty().filter { session ->
                                appliedSearch.isBlank() ||
                                    session.title.contains(appliedSearch, true) ||
                                    session.id.contains(appliedSearch, true)
                            }
                        items(matchingSessions, key = { "session:${row.treeKey}:${it.id}" }) { session ->
                            Box(Modifier.padding(start = ((row.depth + 1) * 14).dp)) {
                                AgentwireSessionRow(
                                    session = session,
                                    active = session.id == state.activeSid,
                                    actions = state.actions,
                                    status = state.sessionStatuses[session.id],
                                    onAttach = { sid, cwd ->
                                        viewModel.attachSession(sid, cwd)
                                        dismiss()
                                    },
                                    onRename = viewModel::renameSession,
                                    onFork = viewModel::forkSession,
                                    onArchive = viewModel::archiveSession,
                                )
                            }
                        }
                        if (matchingSessions.isEmpty()) {
                            item(key = "session-state:${row.treeKey}") {
                                val sessionsLoaded = directory.id in state.loadedSessionDirectories
                                Text(
                                    if (sessionsLoaded) "No sessions in this directory" else "Loading sessions…",
                                    modifier = Modifier.padding(start = ((row.depth + 1) * 14).dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            } else {
                item { Text("Safe settings", style = MaterialTheme.typography.titleMedium) }
                val supportsModels = "model" in state.supportedSettings || state.backend == "codex"
                if (supportsModels && state.modelOptions.isEmpty()) {
                    item {
                        OutlinedButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().testTag("agentwire_model_picker")) {
                            Text("Waiting for bridge model catalog", modifier = Modifier.weight(1f))
                            Text("▾", fontFamily = FontFamily.Monospace)
                        }
                    }
                    item { Text("This backend did not advertise selectable models or efforts.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                } else if (supportsModels) {
                    item {
                        Box(Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { modelMenu = true }, modifier = Modifier.fillMaxWidth().testTag("agentwire_model_picker")) {
                                val option = state.modelOptions.firstOrNull { it.value == model }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                    Text(option?.label ?: model)
                                    if (option?.label != option?.value) Text(model, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                }
                                Text("▾", fontFamily = FontFamily.Monospace)
                            }
                            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                                state.modelOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(option.label)
                                                if (option.label != option.value) Text(option.value, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                                            }
                                        },
                                        onClick = {
                                            model = option.value
                                            effort = option.defaultEffort?.takeIf { it in option.efforts } ?: option.efforts.firstOrNull().orEmpty()
                                            modelMenu = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                    val selectedOption = state.modelOptions.firstOrNull { it.value == model }
                    item { Text("Reasoning effort", fontWeight = FontWeight.SemiBold) }
                    selectedOption?.efforts.orEmpty().forEach { optionEffort ->
                        item(key = "effort:$model:$optionEffort") {
                            Row(Modifier.fillMaxWidth().clickable { effort = optionEffort }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = effort == optionEffort, onClick = { effort = optionEffort }, modifier = Modifier.testTag("agentwire_effort_$optionEffort"))
                                Text(optionEffort.replaceFirstChar(Char::uppercase))
                            }
                        }
                    }
                }
                if ("delivery" in state.supportedSettings) {
                    item { Text("Delivery while running", fontWeight = FontWeight.SemiBold) }
                    listOf("queue", "steer").forEach { option ->
                        item(key = "delivery:$option") {
                            Row(Modifier.fillMaxWidth().clickable { delivery = option }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = delivery == option, onClick = { delivery = option })
                                Column {
                                    Text(option.replaceFirstChar(Char::uppercase))
                                    Text(
                                        if (option == "queue") "Run after the current turn" else "Guide the current turn",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if ("collaboration" in state.supportedSettings) {
                    item { Text("Collaboration mode", fontWeight = FontWeight.SemiBold) }
                    listOf("default", "plan").forEach { option ->
                        item(key = "collaboration:$option") {
                            Row(Modifier.fillMaxWidth().clickable { collaboration = option }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = collaboration == option, onClick = { collaboration = option })
                                Text(option.replaceFirstChar(Char::uppercase))
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            viewModel.updateSettings(
                                buildMap {
                                    if (supportsModels && model.isNotBlank()) put("model", model)
                                    if (supportsModels && effort.isNotBlank()) put("effort", effort)
                                    if ("delivery" in state.supportedSettings) put("delivery", delivery)
                                    if ("collaboration" in state.supportedSettings && model.isNotBlank()) put("collaboration", collaboration)
                                },
                            )
                        }, enabled = state.activeSid != null) { Text("Apply settings") }
                        if ("approvalReviewer" in state.supportedSettings) {
                            if (state.settings["approvalReviewer"] == "auto_review") {
                                OutlinedButton(onClick = viewModel::disableAutoReview, enabled = state.activeSid != null) {
                                    Text("Disable auto-review")
                                }
                            } else {
                                OutlinedButton(onClick = {
                                    if (state.autoReviewConfirmed) {
                                        viewModel.enableAutoReview()
                                    } else {
                                        confirmAutoReview = true
                                    }
                                }, enabled = state.activeSid != null) { Text("Enable auto-review") }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
    if (confirmAutoReview) {
        AlertDialog(
            onDismissRequest = { confirmAutoReview = false },
            title = { Text("Enable auto-review for this session?") },
            text = { Text("Interactive approval policy and sandbox restrictions remain in effect. Auto-review does not mean never ask.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.enableAutoReview()
                    confirmAutoReview = false
                }) { Text("Enable") }
            },
            dismissButton = { TextButton(onClick = { confirmAutoReview = false }) { Text("Cancel") } },
        )
    }
}

@SuppressLint("HardcodedText")
@Composable
internal fun AgentwireLiveSessions(
    sessions: List<AgentwireListItem>,
    activeSid: String?,
    actions: Set<String>,
    statuses: Map<String, AgentwireSessionStatus> = emptyMap(),
    onAttach: (String, String?) -> Unit,
    onRename: (String, String) -> Unit = { _, _ -> },
    onFork: (String) -> Unit = {},
    onArchive: (String, Boolean) -> Unit = { _, _ -> },
) {
    Column(
        Modifier.fillMaxWidth().testTag("agentwire_live_sessions"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Live sessions", style = MaterialTheme.typography.titleMedium)
        if (sessions.isEmpty()) {
            Text(
                "No desktop sessions detected",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            sessions.forEach { session ->
                AgentwireSessionRow(
                    session = session,
                    active = session.id == activeSid,
                    actions = actions,
                    status = statuses[session.id],
                    onAttach = onAttach,
                    onRename = onRename,
                    onFork = onFork,
                    onArchive = onArchive,
                )
            }
        }
    }
}

@SuppressLint("HardcodedText")
@Composable
private fun AgentwireSessionRow(
    session: AgentwireListItem,
    active: Boolean,
    actions: Set<String>,
    status: AgentwireSessionStatus? = null,
    onAttach: (String, String?) -> Unit,
    onRename: (String, String) -> Unit,
    onFork: (String) -> Unit,
    onArchive: (String, Boolean) -> Unit,
) {
    var expanded by remember(session.id) { mutableStateOf(false) }
    var title by remember(session.id, session.title) { mutableStateOf(session.title) }
    val archived = "archived" in session.raw.stringList("flags")
    val runtimeStatus = agentwireSessionRuntimeStatus(session, status)
    val tuiAttached = status?.tuiAttached ?: (session.raw.bool("tuiAttached") == true)
    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .testTag("agentwire_session_${session.id}"),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (active) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
            ),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(session.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(session.id.take(12), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                }
                if (active) {
                    Text("Attached", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (tuiAttached) {
                    Text("TUI", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (runtimeStatus != null) {
                    Text(runtimeStatus, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
                }
                if (!active && "session.attach" in actions) {
                    TextButton(onClick = { onAttach(session.id, session.subtitle) }) { Text("Attach") }
                }
            }
            if (expanded) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    if ("session.rename" in actions) {
                        OutlinedTextField(title, { title = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Session title") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if ("session.rename" in actions) {
                            TextButton(onClick = { onRename(session.id, title) }, enabled = title.isNotBlank() && title != session.title) { Text("Rename") }
                        }
                        if ("session.fork" in actions) TextButton(onClick = { onFork(session.id) }) { Text("Fork") }
                        if (archived && "session.unarchive" in actions) {
                            TextButton(onClick = { onArchive(session.id, false) }) { Text("Unarchive") }
                        } else if (!archived && "session.archive" in actions) {
                            TextButton(onClick = { onArchive(session.id, true) }) { Text("Archive") }
                        }
                    }
                }
            }
        }
    }
}
