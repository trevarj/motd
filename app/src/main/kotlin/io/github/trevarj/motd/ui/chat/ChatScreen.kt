package io.github.trevarj.motd.ui.chat

import android.content.ClipData
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import io.github.trevarj.motd.R
import kotlinx.coroutines.flow.first
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.ComposerReply
import io.github.trevarj.motd.ui.components.typingText
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.launch

/** Pause after the last keystroke before the nick-autocomplete panel becomes visible, so fast
 *  typing doesn't flash suggestions on every character. */
private const val AUTOCOMPLETE_SHOW_DEBOUNCE_MS = 250L

/** Stateful entry: wires the ViewModel, lifecycle mark-read, and navigation. */
@Composable
fun ChatScreen(
    bufferId: Long,
    onBack: () -> Unit = {},
    onOpenChannelInfo: (Long) -> Unit = {},
    onOpenSearch: (Long) -> Unit = {},
    onOpenImage: (String) -> Unit = {},
    // /msg and /query resolve-or-create a QUERY buffer via the VM, then navigate to it.
    onOpenBuffer: (Long) -> Unit = {},
    // Round 5 (plans/16): /list opens the channel browser. Body lands in WP-V3.
    onOpenChannelList: (Long) -> Unit = {},
    viewModel: ChatViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = viewModel.messages.collectAsLazyPagingItems()

    // Foreground-buffer tracker on resume/pause (notification suppression, plans/05). On resume we
    // also mark read up to the newest loaded message (plans/07: mark-read on resume).
    DisposableEffect(Unit) {
        viewModel.onResume()
        val newestOnResume = if (items.itemCount > 0) items.peek(0)?.serverTime ?: 0L else 0L
        viewModel.markRead(newestOnResume)
        onDispose { viewModel.onPause() }
    }

    // Keep the VM's visible-msgid set current so its reaction aggregation subscribes to the right
    // rows (picks up echo-confirm msgid swaps too, plans/15 #18).
    val visibleMsgids = remember(items.itemCount) {
        (0 until items.itemCount).mapNotNull { items.peek(it)?.msgid }
    }
    LaunchedEffect(visibleMsgids) { viewModel.setVisibleMsgids(visibleMsgids) }

    // Aggregated chips retained in the VM (no blank frame on arrivals, plans/15 #18).
    val chipsByMsgid by viewModel.reactionChips.collectAsStateWithLifecycle()

    val jumpTarget by viewModel.jumpTarget.collectAsStateWithLifecycle()
    val jumpFailed by viewModel.jumpFailed.collectAsStateWithLifecycle()
    // Read marker frozen on entry so the "— New messages —" divider doesn't flash away (plans/15 #2).
    val readMarkerSnapshot by viewModel.readMarkerSnapshot.collectAsStateWithLifecycle()
    // Live read marker drives the FAB unread badge so it clears as messages are read (not on exit).
    val readMarkerTime by viewModel.readMarkerTime.collectAsStateWithLifecycle()
    // Timeline behavioral settings collected separately from ChatState (plans/13 §2.5).
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    // Round 5: nick sheet + raw-send snackbar (plans/16 §5.6/§5.8).
    val nickSheet by viewModel.nickSheet.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbar.collectAsStateWithLifecycle()
    val isServerBuffer = state.buffer?.type == BufferType.SERVER

    ChatContent(
        state = state,
        items = items,
        composerEnabled = state.connState is IrcClientState.Ready,
        friends = settings.friends,
        fools = settings.fools,
        foolsMode = settings.foolsMode,
        chatWallpaper = settings.chatWallpaper,
        showComposerEmoji = settings.showComposerEmoji,
        reactionChips = { msgid -> chipsByMsgid[msgid].orEmpty() },
        readMarkerSnapshot = readMarkerSnapshot,
        readMarkerLive = readMarkerTime,
        onMarkRead = viewModel::markRead,
        onBack = onBack,
        // SERVER buffers have no ChannelInfo (no members/topic); tapping the title is inert.
        onOpenChannelInfo = if (isServerBuffer) ({}) else onOpenChannelInfo,
        onOpenSearch = onOpenSearch,
        onOpenImage = onOpenImage,
        nickNormalizer = viewModel.nickNormalizer(),
        onSubmit = { raw -> viewModel.submit(raw, onOpenBuffer = onOpenBuffer, onOpenChannelList = onOpenChannelList) },
        onTyping = viewModel::sendTyping,
        onSetReply = viewModel::setReply,
        onReact = viewModel::react,
        onRetry = viewModel::retry,
        onDelete = viewModel::deleteFailed,
        loadPreview = viewModel::linkPreview,
        consumePrefill = viewModel::consumePrefill,
        jumpTarget = jumpTarget,
        jumpFailed = jumpFailed,
        onJumpFailedShown = viewModel::onJumpFailedShown,
        onJumpHandled = viewModel::onJumpHandled,
        onReresolveJump = viewModel::reresolveJumpOnce,
        isServerBuffer = isServerBuffer,
        onSenderClick = viewModel::openNickSheet,
        rawSendSnackbar = snackbarMessage,
        onSnackbarShown = viewModel::consumeSnackbar,
    )

    // Nick sheet (plans/16 §5.8): actions render immediately; whois fills in when it lands.
    nickSheet?.let { sheet ->
        val norm = viewModel.nickNormalizer()
        val myNick = (state.connState as? IrcClientState.Ready)?.nick
        val isSelf = myNick != null && norm(sheet.nick) == norm(myNick)
        val normSelf = io.github.trevarj.motd.data.prefs.normalizeNick(sheet.nick)
        NickActionSheet(
            nick = sheet.nick,
            isSelf = isSelf,
            isFriend = normSelf in settings.friends,
            isFool = normSelf in settings.fools,
            canModerate = viewModel.canModerate(),
            whois = sheet.whois,
            onDismiss = viewModel::dismissNickSheet,
            onMessage = { viewModel.dismissNickSheet(); viewModel.submit("/query ${sheet.nick}", onOpenBuffer) },
            onMention = { viewModel.dismissNickSheet() },
            onToggleFriend = { viewModel.toggleFriend(sheet.nick) },
            onToggleFool = { viewModel.toggleFool(sheet.nick) },
            onOp = { grant -> viewModel.setMemberMode(sheet.nick, 'o', grant) },
            onVoice = { grant -> viewModel.setMemberMode(sheet.nick, 'v', grant) },
            onKick = { reason -> viewModel.dismissNickSheet(); viewModel.kick(sheet.nick, reason) },
            onBan = { viewModel.dismissNickSheet(); viewModel.ban(sheet.nick) },
            showMention = state.buffer?.type == BufferType.CHANNEL,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatContent(
    state: ChatState,
    items: LazyPagingItems<MessageEntity>,
    composerEnabled: Boolean,
    onBack: () -> Unit,
    onOpenChannelInfo: (Long) -> Unit,
    onOpenSearch: (Long) -> Unit,
    onOpenImage: (String) -> Unit,
    nickNormalizer: (String) -> String,
    onSubmit: (String) -> Unit,
    onTyping: (Boolean) -> Unit,
    onSetReply: (MessageEntity?) -> Unit,
    // React to a message. Takes the whole entity so a still-pending own message (msgid == null) can
    // be queued rather than silently dropped; the VM defers the send until the msgid lands.
    onReact: (MessageEntity, String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    loadPreview: suspend (String) -> io.github.trevarj.motd.data.repo.LinkPreview?,
    reactionChips: (String) -> List<io.github.trevarj.motd.ui.components.ReactionChip> = { emptyList() },
    friends: Set<String> = emptySet(),
    fools: Set<String> = emptySet(),
    foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    chatWallpaper: io.github.trevarj.motd.data.prefs.ChatWallpaper = io.github.trevarj.motd.data.prefs.ChatWallpaper.NONE,
    showComposerEmoji: Boolean = true,
    readMarkerSnapshot: Long? = null,
    // Live buffer read marker (advances with markRead); drives the FAB unread badge count.
    readMarkerLive: Long? = null,
    onMarkRead: (Long) -> Unit = {},
    onDelete: (MessageEntity) -> Unit = {},
    consumePrefill: () -> String? = { null },
    jumpTarget: ChatJumpResolver.Result.Target? = null,
    jumpFailed: Boolean = false,
    onJumpFailedShown: () -> Unit = {},
    onJumpHandled: () -> Unit = {},
    onReresolveJump: () -> Unit = {},
    // Round 5 (plans/16 §5.6/§5.8): SERVER-buffer raw-send + nick sheet plumbing.
    isServerBuffer: Boolean = false,
    onSenderClick: (String) -> Unit = {},
    rawSendSnackbar: String? = null,
    onSnackbarShown: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Expanded fool rows (plans/13 §2.4): keyed by MessageEntity.id, expand-only for the session.
    // Ephemeral by design (lost on config change; accepted per plans/13 Risks #6).
    var expandedFools by remember { mutableStateOf(setOf<Long>()) }
    val clipboard: Clipboard = LocalClipboard.current
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    // rememberSaveable survives ChannelInfo round-trips + config changes (fixes v1 draft loss).
    var composerText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var highlightMsgid by rememberSaveable { mutableStateOf<String?>(null) }
    // Global fool expand/collapse toggle (plans/13 §2.4): when true every collapsed fool row in the
    // buffer renders expanded; per-row toggles still override individually via [expandedFools] and
    // [collapsedFools]. Ephemeral per composition, like expandedFools.
    var expandAllFools by remember { mutableStateOf(false) }
    // Rows the user explicitly re-collapsed while expand-all is on (so a global expand is still
    // individually reversible). Cleared whenever expand-all is toggled off.
    var collapsedFools by remember { mutableStateOf(setOf<Long>()) }

    // A fresh reverse-layout list is already anchored at index 0 (the newest row).  Do not issue a
    // second scroll when Paging's initial page arrives: even a non-animated scroll invalidates the
    // just-measured viewport while the route enter transition is rendering, causing a visible
    // main-thread hitch. Deep links retain their explicit anchor below.

    // Consume any mention prefill queued by ChannelInfo. Runs once per composition entry; the
    // store is already emptied by consume() and the text survives via rememberSaveable, so a
    // config change cannot double-prefill.
    LaunchedEffect(Unit) {
        consumePrefill()?.let { composerText = appendPrefill(composerText, it) }
    }

    // Cap-miss / not-loaded → transient snackbar. jumpFailed is a latch StateFlow (replay-safe), so
    // a NotFound resolved before this collector subscribed is not lost (plans/15 #13).
    val jumpNotLoaded = stringResource(R.string.chat_jump_not_loaded)
    LaunchedEffect(jumpFailed) {
        if (jumpFailed) {
            snackbarHostState.showSnackbar(jumpNotLoaded)
            onJumpFailedShown()
        }
    }

    // Transient snackbars off the VM's one-shot sentinel channel: raw-send parse failure on a SERVER
    // buffer (plans/16 §5.6), or a queued reaction whose message never confirmed (plans/15 reactions).
    val invalidCommand = stringResource(R.string.chat_server_invalid_command)
    val reactFailed = stringResource(R.string.chat_react_failed)
    LaunchedEffect(rawSendSnackbar) {
        val sentinel = rawSendSnackbar ?: return@LaunchedEffect
        val text = if (sentinel == "react_failed") reactFailed else invalidCommand
        snackbarHostState.showSnackbar(text)
        onSnackbarShown()
    }

    // Deep-jump scroll: bounded APPEND loop (placeholders OFF, so tail loads never shift indices).
    LaunchedEffect(jumpTarget) {
        val j = jumpTarget ?: return@LaunchedEffect
        // Guard #1: the jump resolves in VM init, often before the first paging emission. Touching
        // items[itemCount-1] with itemCount == 0 throws IndexOutOfBounds; wait for the first page.
        snapshotFlow { items.loadState.refresh to items.itemCount }
            .first { (refresh, count) -> refresh is LoadState.NotLoading && count > 0 }

        var rounds = 0
        while (items.itemCount <= j.index &&
            rounds++ < 64 &&
            items.itemCount > 0 &&
            !items.loadState.append.endOfPaginationReached
        ) {
            val before = items.itemCount
            items[items.itemCount - 1] // touch tail → triggers APPEND
            // Wait for the triggered load to complete: first the Loading edge, then back to
            // NotLoading (or the row count to actually grow). Matching the current NotLoading would
            // spin through all 64 rounds without ever waiting for a load (plans/15 #14).
            snapshotFlow { items.loadState.append to items.itemCount }
                .first { (append, count) ->
                    (append is LoadState.NotLoading && count > before) ||
                        append.endOfPaginationReached ||
                        append is LoadState.Error
                }
        }
        if (items.itemCount > j.index) {
            listState.scrollToItem(j.index)
            highlightMsgid = j.highlightMsgid
            // A live message may have shifted indices between resolve and scroll; re-resolve once.
            if (j.highlightMsgid != null && items.peek(j.index)?.msgid != j.highlightMsgid) {
                onReresolveJump()
            } else {
                onJumpHandled()
            }
        } else {
            // Ran past the cap / end of pagination without reaching the target.
            snackbarHostState.showSnackbar(jumpNotLoaded)
            onJumpHandled()
        }
    }

    // Clear the highlight after the pulse settles (~1.6s).
    LaunchedEffect(highlightMsgid) {
        if (highlightMsgid != null) {
            kotlinx.coroutines.delay(1_600)
            highlightMsgid = null
        }
    }

    // Long-press action sheet target.
    var sheetTarget by remember { mutableStateOf<MessageEntity?>(null) }
    val sheetState = rememberModalBottomSheetState()

    // At-bottom detection (reversed list: bottom == index 0). A small scroll-offset tolerance keeps
    // "at bottom" true while the newest row is only marginally scrolled, so autoscroll still pins.
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= AUTOSCROLL_BOTTOM_TOLERANCE_PX
        }
    }

    // Suppresses the scroll-to-bottom FAB while a programmatic scroll-to-newest is in flight. When a
    // new row inserts at index 0 the reverse list momentarily reads !atBottom for a sub-frame before
    // the animate-to-0 settles; without this gate the FAB flashes on every send / auto-follow. A real
    // user scroll-up doesn't set this, so the FAB still appears promptly when reading history.
    var autoScrolling by remember { mutableStateOf(false) }
    // Animate the reverse list to the newest row (index 0) with the FAB gated off for the duration.
    suspend fun scrollToNewest() {
        autoScrolling = true
        try {
            listState.animateScrollToItem(0)
        } finally {
            autoScrolling = false
        }
    }

    // Auto-stick-to-bottom for incoming messages (autoscroll-to-newest bug). When a new row is
    // prepended at index 0 the reverse-layout viewport does NOT follow it (stable keys anchor the
    // existing rows), so an explicit scroll is needed. We snapshot whether the user was at the bottom
    // *before* the count grew via [wasAtBottom]; a user scrolled up reading history is never yanked.
    // Own-send scrolls unconditionally in the composer, so it doesn't route through this path.
    var wasAtBottom by remember { mutableStateOf(true) }
    var prevItemCount by remember { mutableStateOf(items.itemCount) }
    LaunchedEffect(items.itemCount) {
        val newCount = items.itemCount
        if (shouldAutoscrollToNewest(wasAtBottom, prevItemCount, newCount)) {
            scrollToNewest()
        }
        prevItemCount = newCount
    }
    // Track the at-bottom state on every settle so the next arrival reads the pre-add position.
    LaunchedEffect(atBottom) { wasAtBottom = atBottom }

    val buffer = state.buffer
    // Mark read on new-message-while-at-bottom only (plans/07/15 #2): syncing while scrolled up
    // reading history would clear unread on other clients and destroy the local unread UX.
    val newestTime = if (items.itemCount > 0) items.peek(0)?.serverTime ?: 0L else 0L
    LaunchedEffect(newestTime, atBottom) {
        if (atBottom && newestTime > 0) onMarkRead(newestTime)
    }
    val recentSpeakers = remember(items.itemCount) {
        // Exclude system-event senders and self so recency ranking reflects real conversation
        // partners (plans/15 #30). Only the newest rows matter for recency, so cap the scan (the list
        // is reverse-laid-out, so index 0.. are the newest) to stay cheap on large loaded windows.
        (0 until minOf(items.itemCount, 60))
            .mapNotNull { items.peek(it) }
            .filterNot { isSystemKind(it.kind) || it.isSelf }
            .map { it.sender }
    }
    val memberNicks = state.members.map { it.nick }
    // Normalized member nicks for @mention coloring in message bodies (plans/17). Normalized with
    // the same normalizeNick the row renderers use so token lookups match; memoized on the member
    // list so the set isn't rebuilt every recomposition.
    val knownNicks = remember(memberNicks) {
        memberNicks.map { io.github.trevarj.motd.data.prefs.normalizeNick(it) }.toSet()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier.clickable(enabled = buffer != null) {
                            buffer?.let { onOpenChannelInfo(it.id) }
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            name = buffer?.displayName ?: "",
                            size = 34.dp,
                            isChannel = buffer?.type == BufferType.CHANNEL,
                        )
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(
                                text = buffer?.displayName ?: "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val subtitle = chatSubtitle(state, ctx)
                            if (subtitle != null) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back),
                        )
                    }
                },
                actions = {
                    // Global fool expand/collapse (bug #9): only meaningful with configured fools in
                    // COLLAPSE mode. Toggling clears the per-row overrides so it acts as a clean reset.
                    if (foolsMode == FoolsMode.COLLAPSE && fools.isNotEmpty()) {
                        IconButton(onClick = {
                            expandAllFools = !expandAllFools
                            expandedFools = emptySet()
                            collapsedFools = emptySet()
                        }) {
                            Icon(
                                if (expandAllFools) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(
                                    if (expandAllFools) R.string.chat_fool_collapse_all
                                    else R.string.chat_fool_expand_all,
                                ),
                            )
                        }
                    }
                    IconButton(onClick = { buffer?.let { onOpenSearch(it.id) } }) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.chat_search),
                        )
                    }
                },
            )
        },
    ) { padding ->
        // The activity is NOT edge-to-edge (decor fits system windows) and the manifest sets
        // windowSoftInputMode=adjustResize, so the window itself shrinks when the IME opens: the
        // Scaffold content area gets smaller and the composer stays pinned at the new bottom while
        // the reverse list stays anchored at index 0. An imePadding() here would double-count the
        // IME inset (window already resized) and shove the whole column up above the keyboard.
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    // Subtle IRC-themed wallpaper layered UNDER the message list only (never over the
                    // composer). NONE renders the plain theme background; MessageList is untouched.
                    ChatWallpaperBackground(chatWallpaper, modifier = Modifier.matchParentSize())
                    MessageList(
                        items = items,
                        listState = listState,
                        // Frozen read-marker so the "— New messages —" divider stays put (plans/15 #2).
                        readMarkerTime = readMarkerSnapshot,
                        reactionChips = reactionChips,
                        onLongPress = { sheetTarget = it },
                        onReact = onReact,
                        onImageClick = onOpenImage,
                        onRetry = onRetry,
                        onDelete = onDelete,
                        loadPreview = loadPreview,
                        // Link-preview tap opens the URL in the system browser.
                        onOpenLink = { ctx.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) },
                        highlightMsgid = highlightMsgid,
                        knownNicks = knownNicks,
                        friends = friends,
                        fools = fools,
                        foolsMode = foolsMode,
                        // Effective expansion: global expand-all default, minus rows the user
                        // re-collapsed; otherwise only individually expanded rows show (bug #9).
                        foolExpanded = { id ->
                            if (expandAllFools) id !in collapsedFools else id in expandedFools
                        },
                        // Bidirectional per-row toggle, respecting the global default.
                        onToggleFool = { id ->
                            if (expandAllFools) {
                                collapsedFools =
                                    if (id in collapsedFools) collapsedFools - id else collapsedFools + id
                            } else {
                                expandedFools =
                                    if (id in expandedFools) expandedFools - id else expandedFools + id
                            }
                        },
                        onSenderClick = onSenderClick,
                    )

                    // Empty buffer once the first refresh settles → placeholder (plans/15 #27).
                    if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
                        io.github.trevarj.motd.ui.components.EmptyState(
                            icon = Icons.Outlined.Forum,
                            title = stringResource(R.string.chat_empty_title),
                            message = stringResource(R.string.chat_empty_message),
                        )
                    }

                    // Scroll-to-bottom FAB with unread count. Viewport-aware: only messages below the
                    // fold (newer than the topmost visible row) and past the LIVE read marker count,
                    // so the badge shrinks as the user scrolls down and clears at the bottom once
                    // markRead advances the live marker — instead of counting already-read messages
                    // against the frozen snapshot until buffer re-entry. firstVisibleItemIndex is
                    // read reactively so scrolls recompute it.
                    val firstVisible by remember {
                        derivedStateOf { listState.firstVisibleItemIndex }
                    }
                    // Gated on !autoScrolling so a sub-frame !atBottom blip during a programmatic
                    // scroll-to-newest (send / auto-follow) can't flash the FAB; a genuine user
                    // scroll-up leaves autoScrolling false, so it still appears promptly.
                    ScrollToBottomFab(
                        visible = !atBottom && !autoScrolling,
                        unread = unreadBelowViewport(items, readMarkerLive, firstVisible),
                        onClick = { scope.launch { scrollToNewest() } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }

                val completions = remember(composerText, memberNicks, recentSpeakers) {
                    autocompleteFor(composerText, memberNicks, recentSpeakers, nickNormalizer)
                }
                // Debounce the SHOW so fast typing doesn't flash the suggestion panel on every
                // keystroke: only reveal completions after a brief pause. Hiding stays immediate
                // (an empty result clears the panel at once) so the panel never lingers stale.
                var showAutocomplete by remember { mutableStateOf(false) }
                LaunchedEffect(completions) {
                    if (completions.isEmpty()) {
                        showAutocomplete = false
                    } else {
                        kotlinx.coroutines.delay(AUTOCOMPLETE_SHOW_DEBOUNCE_MS)
                        showAutocomplete = true
                    }
                }
                Composer(
                    value = composerText,
                    onValueChange = {
                        val wasBlank = composerText.text.isBlank()
                        composerText = it
                        if (it.text.isNotBlank()) onTyping(true)
                        else if (!wasBlank) onTyping(false)
                    },
                    onSend = {
                        val text = composerText.text
                        if (text.isNotBlank()) {
                            onSubmit(text)
                            composerText = TextFieldValue("")
                            // Sending should always reveal the just-sent message: snap the reverse
                            // list back to the newest row (index 0) even if the user had scrolled up.
                            // Routes through scrollToNewest so the FAB stays gated off for the scroll.
                            scope.launch { scrollToNewest() }
                        }
                    },
                    enabled = composerEnabled,
                    reply = state.replyTo?.let { ComposerReply(it.sender, it.text) },
                    onCancelReply = { onSetReply(null) },
                    // SERVER buffers send raw commands; hint that in the placeholder (plans/16 §5.6).
                    placeholder = if (isServerBuffer) {
                        stringResource(R.string.chat_server_composer_hint)
                    } else {
                        stringResource(R.string.chat_composer_placeholder)
                    },
                    showEmojiButton = showComposerEmoji,
                    autocomplete = if (showAutocomplete && completions.isNotEmpty()) {
                        {
                            AutocompletePanel(
                                candidates = completions.map { it.display },
                                isCommand = completions.firstOrNull()?.isCommand == true,
                                onPick = { picked ->
                                    composerText = applyPick(composerText, picked)
                                },
                            )
                        }
                    } else null,
                )
            }
        }
    }

    sheetTarget?.let { target ->
        // Dismiss with the M3 hide animation, clearing the target only once it settles (plans/15 #31).
        val hideThen: (() -> Unit) -> Unit = { after ->
            scope.launch { sheetState.hide() }.invokeOnCompletion {
                sheetTarget = null
                after()
            }
        }
        MessageActionSheet(
            sheetState = sheetState,
            isServerBuffer = isServerBuffer,
            onDismiss = { sheetTarget = null },
            onReply = { hideThen { onSetReply(target) } },
            // Pass the whole target: the VM queues the react when target.msgid is still null (own
            // pending message) instead of silently dropping it.
            onReact = { emoji -> hideThen { onReact(target, emoji) } },
            onCopy = {
                hideThen {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("message", target.text)))
                    }
                }
            },
            onQuote = {
                // Append the quote to the existing draft with the cursor at the end (plans/15 #19).
                hideThen { composerText = appendPrefill(composerText, "> ${target.text}\n") }
            },
        )
    }
}

/**
 * Append [prefill] to [value], inserting a single space when the current text is non-empty and
 * doesn't already end in whitespace. Places the cursor at the end (plans/11 §A).
 */
fun appendPrefill(value: TextFieldValue, prefill: String): TextFieldValue {
    val current = value.text
    val sep = if (current.isNotEmpty() && !current.last().isWhitespace()) " " else ""
    val text = current + sep + prefill
    return TextFieldValue(text = text, selection = androidx.compose.ui.text.TextRange(text.length))
}

/**
 * Header subtitle: typing summary if anyone is typing, else a localized member count for channels.
 * Uses the [Context] typing overload and a plural for the count (plans/15 #25).
 */
private fun chatSubtitle(state: ChatState, context: android.content.Context): String? {
    if (state.typingNicks.isNotEmpty()) {
        return typingText(context, state.typingNicks)
    }
    val buffer = state.buffer ?: return null
    return if (buffer.type == BufferType.CHANNEL && state.members.isNotEmpty()) {
        val n = state.members.size
        context.resources.getQuantityString(R.plurals.chat_member_count, n, n)
    } else {
        null
    }
}

/**
 * Unread count for the FAB badge, viewport-aware. In the reversed list, rows with index <
 * [firstVisibleIndex] are below the fold (newer than the topmost visible row, scrolled off toward
 * the bottom). We count those that are also newer than the frozen read [marker]. At the bottom
 * (firstVisibleIndex == 0) this is 0, and it shrinks as the user scrolls down toward the newest
 * message — never a monotonic "arrived since entry" tally. Delegates to the pure
 * [unreadBelowViewport] helper for unit testing.
 */
private fun unreadBelowViewport(
    items: LazyPagingItems<MessageEntity>,
    marker: Long?,
    firstVisibleIndex: Int,
): Int {
    if (marker == null || firstVisibleIndex <= 0) return 0
    // Skip our own sent rows: they are never "unread" to us, so they must not inflate the badge.
    val times = (0 until firstVisibleIndex.coerceAtMost(items.itemCount))
        .mapNotNull { items.peek(it)?.takeUnless { m -> m.isSelf }?.serverTime }
    return unreadBelowViewport(times, marker)
}

@Composable
private fun ScrollToBottomFab(
    visible: Boolean,
    unread: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = scaleIn(), exit = scaleOut(), modifier = modifier) {
        BadgedBox(
            badge = { if (unread > 0) Badge { Text(if (unread > 99) "99+" else "$unread") } },
        ) {
            FloatingActionButton(onClick = onClick) {
                Icon(
                    Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.chat_scroll_to_bottom),
                )
            }
        }
    }
}

@Preview
@Composable
private fun ChatContentPreview() {
    MotdTheme {
        ChatContentPreviewBody()
    }
}
