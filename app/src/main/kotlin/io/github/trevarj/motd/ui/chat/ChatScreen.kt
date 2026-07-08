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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
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
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.ComposerReply
import io.github.trevarj.motd.ui.components.TypingIndicatorRow
import io.github.trevarj.motd.ui.components.typingText
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.launch

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
    // Read marker frozen on entry so the divider/badge don't flash away (plans/15 #2).
    val readMarkerSnapshot by viewModel.readMarkerSnapshot.collectAsStateWithLifecycle()

    ChatContent(
        state = state,
        items = items,
        composerEnabled = state.connState is IrcClientState.Ready,
        reactionChips = { msgid -> chipsByMsgid[msgid].orEmpty() },
        readMarkerSnapshot = readMarkerSnapshot,
        onMarkRead = viewModel::markRead,
        onBack = onBack,
        onOpenChannelInfo = onOpenChannelInfo,
        onOpenSearch = onOpenSearch,
        onOpenImage = onOpenImage,
        nickNormalizer = viewModel.nickNormalizer(),
        onSubmit = { raw -> viewModel.submit(raw, onOpenBuffer = onOpenBuffer) },
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
    )
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
    onReact: (String, String) -> Unit,
    onRetry: (MessageEntity) -> Unit,
    loadPreview: suspend (String) -> io.github.trevarj.motd.data.repo.LinkPreview?,
    reactionChips: (String) -> List<io.github.trevarj.motd.ui.components.ReactionChip> = { emptyList() },
    readMarkerSnapshot: Long? = null,
    onMarkRead: (Long) -> Unit = {},
    onDelete: (MessageEntity) -> Unit = {},
    consumePrefill: () -> String? = { null },
    jumpTarget: ChatJumpResolver.Result.Target? = null,
    jumpFailed: Boolean = false,
    onJumpFailedShown: () -> Unit = {},
    onJumpHandled: () -> Unit = {},
    onReresolveJump: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard: Clipboard = LocalClipboard.current
    val ctx = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    // rememberSaveable survives ChannelInfo round-trips + config changes (fixes v1 draft loss).
    var composerText by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var highlightMsgid by rememberSaveable { mutableStateOf<String?>(null) }

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

    // At-bottom detection (reversed list: bottom == index 0).
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    val buffer = state.buffer
    // Mark read on new-message-while-at-bottom only (plans/07/15 #2): syncing while scrolled up
    // reading history would clear unread on other clients and destroy the local unread UX.
    val newestTime = if (items.itemCount > 0) items.peek(0)?.serverTime ?: 0L else 0L
    LaunchedEffect(newestTime, atBottom) {
        if (atBottom && newestTime > 0) onMarkRead(newestTime)
    }
    val recentSpeakers = remember(items.itemCount) {
        // Exclude system-event senders and self so recency ranking reflects real conversation
        // partners (plans/15 #30).
        (0 until items.itemCount)
            .mapNotNull { items.peek(it) }
            .filterNot { isSystemKind(it.kind) || it.isSelf }
            .map { it.sender }
    }
    val memberNicks = state.members.map { it.nick }

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
        // imePadding keeps the composer above the soft keyboard under targetSdk 35 edge-to-edge
        // (plans/15 #9).
        Box(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
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
                    )

                    // Empty buffer once the first refresh settles → placeholder (plans/15 #27).
                    if (items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading) {
                        io.github.trevarj.motd.ui.components.EmptyState(
                            icon = Icons.Outlined.Forum,
                            title = stringResource(R.string.chat_empty_title),
                            message = stringResource(R.string.chat_empty_message),
                        )
                    }

                    // Scroll-to-bottom FAB with unread count (against the frozen marker, plans/15 #2).
                    ScrollToBottomFab(
                        visible = !atBottom,
                        unread = unreadCount(items, readMarkerSnapshot),
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }

                TypingIndicatorRow(nicks = state.typingNicks)

                val completions = remember(composerText, memberNicks, recentSpeakers) {
                    autocompleteFor(composerText, memberNicks, recentSpeakers, nickNormalizer)
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
                        }
                    },
                    enabled = composerEnabled,
                    reply = state.replyTo?.let { ComposerReply(it.sender, it.text) },
                    onCancelReply = { onSetReply(null) },
                    autocomplete = if (completions.isNotEmpty()) {
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
            onDismiss = { sheetTarget = null },
            onReply = { hideThen { onSetReply(target) } },
            onReact = { emoji -> hideThen { target.msgid?.let { onReact(it, emoji) } } },
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

/** Unread estimate for the FAB badge: messages newer than the frozen read marker. */
private fun unreadCount(items: LazyPagingItems<MessageEntity>, marker: Long?): Int {
    if (marker == null) return 0
    var count = 0
    for (i in 0 until items.itemCount) {
        val t = items.peek(i)?.serverTime ?: continue
        if (t > marker) count++ else break
    }
    return count
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
