package io.github.trevarj.motd.ui.chat

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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import io.github.trevarj.motd.data.db.BufferEntity
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

    // Foreground-buffer tracker on resume/pause (notification suppression, plans/05).
    DisposableEffect(Unit) {
        viewModel.onResume()
        onDispose { viewModel.onPause() }
    }

    // Mark read up to the newest loaded message on resume and as new ones arrive.
    val newestTime = if (items.itemCount > 0) items.peek(0)?.serverTime ?: 0L else 0L
    LaunchedEffect(newestTime) { viewModel.markRead(newestTime) }

    // Reactions for the loaded window, keyed by msgid, aggregated into display chips.
    val visibleMsgids = remember(items.itemCount) {
        (0 until items.itemCount).mapNotNull { items.peek(it)?.msgid }
    }
    val reactions by remember(visibleMsgids) { viewModel.reactions(visibleMsgids) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val myNick = (state.connState as? IrcClientState.Ready)?.nick
    val chipsByMsgid = remember(reactions, myNick) { aggregateReactions(reactions, myNick) }

    val jumpTarget by viewModel.jumpTarget.collectAsStateWithLifecycle()

    ChatContent(
        state = state,
        items = items,
        composerEnabled = state.connState is IrcClientState.Ready,
        reactionChips = { msgid -> chipsByMsgid[msgid].orEmpty() },
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
        loadPreview = viewModel::linkPreview,
        consumePrefill = viewModel::consumePrefill,
        jumpTarget = jumpTarget,
        jumpFailed = viewModel.jumpFailed,
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
    consumePrefill: () -> String? = { null },
    jumpTarget: ChatJumpResolver.Result.Target? = null,
    jumpFailed: SharedFlow<Unit>? = null,
    onJumpHandled: () -> Unit = {},
    onReresolveJump: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
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

    // Cap-miss / not-loaded → transient snackbar.
    val jumpNotLoaded = stringResource(R.string.chat_jump_not_loaded)
    LaunchedEffect(jumpFailed) {
        jumpFailed?.collect { snackbarHostState.showSnackbar(jumpNotLoaded) }
    }

    // Deep-jump scroll: bounded APPEND loop (placeholders OFF, so tail loads never shift indices).
    LaunchedEffect(jumpTarget) {
        val j = jumpTarget ?: return@LaunchedEffect
        var rounds = 0
        while (items.itemCount <= j.index &&
            rounds++ < 64 &&
            !items.loadState.append.endOfPaginationReached
        ) {
            items[items.itemCount - 1] // touch tail → triggers APPEND
            snapshotFlow { items.loadState.append }.first { it is LoadState.NotLoading }
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
    val recentSpeakers = remember(items.itemCount) {
        (0 until items.itemCount).mapNotNull { items.peek(it)?.sender }
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
                            val subtitle = chatSubtitle(state)
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { buffer?.let { onOpenSearch(it.id) } }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    MessageList(
                        items = items,
                        listState = listState,
                        readMarkerTime = buffer?.readMarkerTime,
                        reactionChips = reactionChips,
                        onLongPress = { sheetTarget = it },
                        onReact = onReact,
                        onImageClick = onOpenImage,
                        onRetry = onRetry,
                        loadPreview = loadPreview,
                        // Link-preview tap opens the URL in the system browser.
                        onOpenLink = { ctx.startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) },
                        highlightMsgid = highlightMsgid,
                    )

                    // Scroll-to-bottom FAB with unread count.
                    ScrollToBottomFab(
                        visible = !atBottom,
                        unread = unreadCount(items, buffer),
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
        MessageActionSheet(
            sheetState = sheetState,
            onDismiss = { sheetTarget = null },
            onReply = { onSetReply(target); sheetTarget = null },
            onReact = { emoji ->
                target.msgid?.let { onReact(it, emoji) }
                sheetTarget = null
            },
            onCopy = {
                clipboard.setText(AnnotatedString(target.text))
                sheetTarget = null
            },
            onQuote = {
                composerText = TextFieldValue("> ${target.text}\n")
                sheetTarget = null
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

/** Header subtitle: typing summary if anyone is typing, else member count for channels. */
private fun chatSubtitle(state: ChatState): String? {
    if (state.typingNicks.isNotEmpty()) {
        return typingText(state.typingNicks)
    }
    val buffer = state.buffer ?: return null
    return if (buffer.type == BufferType.CHANNEL && state.members.isNotEmpty()) {
        "${state.members.size} members"
    } else {
        null
    }
}

/** Unread estimate for the FAB badge: messages newer than the read marker. */
private fun unreadCount(items: LazyPagingItems<MessageEntity>, buffer: BufferEntity?): Int {
    val marker = buffer?.readMarkerTime ?: return 0
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
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to bottom")
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
