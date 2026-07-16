package io.github.trevarj.motd.ui.chat

import android.content.ClipData
import android.content.Intent
import android.os.Trace
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
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
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.visibility.MessageVisibilityPolicy
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.diagnostics.AutoFollowTrace
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.client.canSendReactionTags
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.HistoryRefreshRange
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.ComposerReply
import io.github.trevarj.motd.ui.components.typingText
import io.github.trevarj.motd.ui.theme.ConversationTypography
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Pause after the last keystroke before the nick-autocomplete panel becomes visible, so fast
 *  typing doesn't flash suggestions on every character. */
private const val AUTOCOMPLETE_SHOW_DEBOUNCE_MS = 250L
private const val REACTION_PREFETCH_ROWS = 12
private const val MAX_VISIBLE_REACTION_MSGIDS = 80
private const val MAX_UNREAD_BADGE_COUNT = 100

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
    var mentionRequest by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val items = viewModel.messages.collectAsLazyPagingItems()
    val memberNicks by viewModel.memberNicks.collectAsStateWithLifecycle()
    val knownNicks by viewModel.knownNicks.collectAsStateWithLifecycle()

    // Foreground-buffer tracker on resume/pause (notification suppression, plans/05). Read state
    // is deliberately deferred until the one-shot entry position has settled below.
    DisposableEffect(Unit) {
        viewModel.onResume()
        onDispose { viewModel.onPause() }
    }

    val chipsByMsgid by viewModel.reactionChips.collectAsStateWithLifecycle()
    val reactionChipsForMessage = remember(chipsByMsgid) {
        { msgid: String -> chipsByMsgid[msgid].orEmpty() }
    }
    // The VM resolves the live ISUPPORT normalizer. Memoize the returned lambda so unrelated
    // header/composer state changes do not invalidate every lazy-list row through a new function
    // identity.
    val nickNormalizer = remember(state.buffer?.networkId, state.connState) {
        viewModel.nickNormalizer()
    }

    val jumpTarget by viewModel.jumpTarget.collectAsStateWithLifecycle()
    val initialTarget by viewModel.initialTarget.collectAsStateWithLifecycle()
    val entryPositionSettled by viewModel.entryPositionSettled.collectAsStateWithLifecycle()
    val entryPositionUnresolved by viewModel.entryPositionUnresolved.collectAsStateWithLifecycle()
    val jumpFailed by viewModel.jumpFailed.collectAsStateWithLifecycle()
    // Read marker frozen on entry so the "— New messages —" divider doesn't flash away (plans/15 #2).
    val readMarkerSnapshot by viewModel.readMarkerSnapshot.collectAsStateWithLifecycle()
    // Live read marker drives the FAB unread badge so it clears as messages are read (not on exit).
    val readMarkerTime by viewModel.readMarkerTime.collectAsStateWithLifecycle()
    val rawNewestTime by viewModel.rawNewestTime.collectAsStateWithLifecycle()
    // Timeline behavioral settings collected separately from ChatState (plans/13 §2.5).
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val appearance by viewModel.appearance.collectAsStateWithLifecycle(
        initialValue = io.github.trevarj.motd.data.prefs.AppearanceConfig(),
    )
    val contentPreviews by viewModel.contentPreviews.collectAsStateWithLifecycle()
    val replyConfig by viewModel.replyConfig.collectAsStateWithLifecycle()
    // Round 5: nick sheet + raw-send snackbar (plans/16 §5.6/§5.8).
    val nickSheet by viewModel.nickSheet.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbar.collectAsStateWithLifecycle()
    val historyResyncState by viewModel.historyResyncState.collectAsStateWithLifecycle()
    val isServerBuffer = state.buffer?.type == BufferType.SERVER
    val titleTarget = chatTitleTarget(state.buffer?.type)

    ChatContent(
        state = state,
        items = items,
        composerEnabled = state.connState is IrcClientState.Ready,
        friends = settings.friends,
        fools = settings.fools,
        foolsMode = settings.foolsMode,
        showJoinPartQuit = settings.showJoinPartQuit,
        chatWallpaper = appearance.wallpaper,
        conversationFontScalePercent = appearance.conversationFontScalePercent,
        showComposerEmoji = settings.showComposerEmoji,
        visibleReplyPrefix = replyConfig.visibleChannelPrefix,
        showImages = contentPreviews.showImages,
        showLinkPreviews = contentPreviews.showLinkPreviews,
        reactionChips = reactionChipsForMessage,
        replyPreview = viewModel::replyPreview,
        memberNicks = memberNicks,
        knownNicks = knownNicks,
        readMarkerSnapshot = readMarkerSnapshot,
        readMarkerLive = readMarkerTime,
        rawNewestTime = rawNewestTime,
        onMarkRead = viewModel::markRead,
        onBack = onBack,
        // Channel titles open Channel Info; query titles describe the other user. SERVER buffers
        // have neither channel nor peer details, so their title remains inert.
        onOpenChannelInfo = { id ->
            when (titleTarget) {
                ChatTitleTarget.CHANNEL_INFO -> onOpenChannelInfo(id)
                ChatTitleTarget.NICK_DETAILS -> state.buffer?.displayName?.let(viewModel::openNickSheet)
                ChatTitleTarget.NONE -> Unit
            }
        },
        onOpenSearch = onOpenSearch,
        onOpenImage = onOpenImage,
        nickNormalizer = nickNormalizer,
        onSubmit = { raw -> viewModel.submit(raw, onOpenBuffer = onOpenBuffer, onOpenChannelList = onOpenChannelList) },
        onTyping = viewModel::sendTyping,
        onSetReply = viewModel::setReply,
        onReact = viewModel::react,
        onRetry = viewModel::retry,
        onDelete = viewModel::deleteFailed,
        onAcceptInvite = viewModel::acceptInvite,
        onDismissInvite = viewModel::dismissInvite,
        loadPreview = viewModel::linkPreview,
        cachedPreview = viewModel::cachedLinkPreview,
        consumePrefill = viewModel::consumePrefill,
        loadDraft = viewModel::loadDraft,
        onDraftChanged = viewModel::saveDraft,
        onDraftCleared = viewModel::clearDraft,
        mentionPrefill = mentionRequest,
        jumpTarget = jumpTarget,
        initialTarget = initialTarget,
        entryPositionInitiallySettled = entryPositionSettled,
        entryPositionUnresolved = entryPositionUnresolved,
        jumpFailed = jumpFailed,
        onJumpFailedShown = viewModel::onJumpFailedShown,
        onJumpHandled = viewModel::onJumpHandled,
        onInitialPositionHandled = viewModel::onInitialPositionHandled,
        onInitialPositionUnresolved = viewModel::onInitialPositionUnresolved,
        onScrollPositionChanged = viewModel::saveScrollPosition,
        onClearScrollPosition = viewModel::clearScrollPosition,
        onVisibleMsgidsChanged = viewModel::setVisibleMsgids,
        onNeedMembers = viewModel::ensureMembersObserved,
        onJumpUnresolved = viewModel::onJumpUnresolved,
        onReresolveJump = viewModel::reresolveJumpOnce,
        isServerBuffer = isServerBuffer,
        onSenderClick = viewModel::openNickSheet,
        rawSendSnackbar = snackbarMessage,
        onSnackbarShown = viewModel::consumeSnackbar,
        historyResyncState = historyResyncState,
        onRefreshHistory = viewModel::refreshHistory,
        onCancelHistoryRefresh = viewModel::cancelHistoryRefresh,
        onHistoryResyncShown = viewModel::consumeHistoryResyncState,
    )

    // Nick sheet (plans/16 §5.8): actions render immediately; whois fills in when it lands.
    nickSheet?.let { sheet ->
        val norm = viewModel.nickNormalizer()
        val myNick = (state.connState as? IrcClientState.Ready)?.nick
        val isSelf = myNick != null && norm(sheet.nick) == norm(myNick)
        val normSelf = io.github.trevarj.motd.data.prefs.normalizeNick(sheet.nick)
        NickActionSheet(
            nick = sheet.nick,
            networkId = state.buffer?.networkId,
            isSelf = isSelf,
            isFriend = normSelf in settings.friends,
            isFool = normSelf in settings.fools,
            canModerate = viewModel.canModerate(),
            whois = sheet.details,
            presence = state.buffer?.networkId?.let { networkId ->
                state.presence[
                    io.github.trevarj.motd.service.PresenceKey(networkId, norm(sheet.nick)),
                ]
            },
            onDismiss = viewModel::dismissNickSheet,
            onMessage = { viewModel.dismissNickSheet(); viewModel.submit("/query ${sheet.nick}", onOpenBuffer) },
            onMention = {
                mentionRequest = System.nanoTime() to "${sheet.nick}: "
                viewModel.dismissNickSheet()
            },
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
    cachedPreview: (String) -> io.github.trevarj.motd.data.repo.CachedLinkPreview? = { null },
    reactionChips: (String) -> List<io.github.trevarj.motd.ui.components.ReactionChip> = { emptyList() },
    replyPreview: (String) -> kotlinx.coroutines.flow.Flow<io.github.trevarj.motd.ui.components.ReplyPreviewData?> = { kotlinx.coroutines.flow.flowOf(null) },
    memberNicks: List<String> = emptyList(),
    knownNicks: Set<String> = emptySet(),
    friends: Set<String> = emptySet(),
    fools: Set<String> = emptySet(),
    foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    showJoinPartQuit: Boolean = true,
    chatWallpaper: io.github.trevarj.motd.data.prefs.WallpaperSelection = io.github.trevarj.motd.data.prefs.WallpaperSelection(),
    conversationFontScalePercent: Int = io.github.trevarj.motd.data.prefs.DEFAULT_FONT_SCALE_PERCENT,
    showComposerEmoji: Boolean = true,
    visibleReplyPrefix: Boolean = false,
    showImages: Boolean = true,
    showLinkPreviews: Boolean = true,
    readMarkerSnapshot: Long? = null,
    // Live buffer read marker (advances with markRead); drives the FAB unread badge count.
    readMarkerLive: Long? = null,
    rawNewestTime: Long? = null,
    onMarkRead: (Long) -> Unit = {},
    onDelete: (MessageEntity) -> Unit = {},
    onAcceptInvite: (Long) -> Unit = {},
    onDismissInvite: (Long) -> Unit = {},
    consumePrefill: () -> String? = { null },
    loadDraft: () -> String? = { null },
    onDraftChanged: (String) -> Unit = {},
    onDraftCleared: () -> Unit = {},
    // Immediate nick-sheet mention request. The nonce permits mentioning the same nick repeatedly.
    mentionPrefill: Pair<Long, String>? = null,
    jumpTarget: ChatJumpResolver.Result.Target? = null,
    initialTarget: ChatInitialPosition? = null,
    entryPositionInitiallySettled: Boolean = false,
    entryPositionUnresolved: Boolean = false,
    jumpFailed: Boolean = false,
    onJumpFailedShown: () -> Unit = {},
    onJumpHandled: () -> Unit = {},
    onInitialPositionHandled: () -> Unit = {},
    onInitialPositionUnresolved: () -> Unit = {},
    onScrollPositionChanged: (ChatScrollPosition) -> Unit = {},
    onClearScrollPosition: () -> Unit = {},
    onVisibleMsgidsChanged: (List<String>) -> Unit = {},
    onNeedMembers: () -> Unit = {},
    onJumpUnresolved: () -> Unit = {},
    onReresolveJump: () -> Unit = {},
    // Round 5 (plans/16 §5.6/§5.8): SERVER-buffer raw-send + nick sheet plumbing.
    isServerBuffer: Boolean = false,
    onSenderClick: (String) -> Unit = {},
    rawSendSnackbar: String? = null,
    onSnackbarShown: () -> Unit = {},
    historyResyncState: HistoryResyncState = HistoryResyncState.Idle,
    onRefreshHistory: (HistoryRefreshRange) -> Unit = {},
    onCancelHistoryRefresh: () -> Unit = {},
    onHistoryResyncShown: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val autoFollow = remember { AutoFollowTracker(items.itemCount) }
    var liveEntryId by remember { mutableStateOf<Long?>(null) }
    val visibilityPolicy = remember(showJoinPartQuit, fools, foolsMode) {
        MessageVisibilityPolicy(MessageVisibilitySpec(showJoinPartQuit, fools, foolsMode))
    }
    val traceBufferId = state.buffer?.id
    val traceSessionId = remember(traceBufferId) {
        traceBufferId?.let { AutoFollowTrace.nextSessionId() }
    }
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
    var attachmentSheetOpen by rememberSaveable { mutableStateOf(false) }
    var uploadCurrentDraftDirectly by rememberSaveable { mutableStateOf(false) }
    var longDraftPrompt by rememberSaveable { mutableStateOf(false) }
    var overflowOpen by rememberSaveable { mutableStateOf(false) }
    var historyRefreshSheetOpen by rememberSaveable { mutableStateOf(false) }
    var highlightMsgid by rememberSaveable { mutableStateOf<String?>(null) }
    // Global fool expand/collapse toggle (plans/13 §2.4): when true every collapsed fool row in the
    // buffer renders expanded; per-row toggles still override individually via [expandedFools] and
    // [collapsedFools]. Ephemeral per composition, like expandedFools.
    var expandAllFools by remember { mutableStateOf(false) }
    // Rows the user explicitly re-collapsed while expand-all is on (so a global expand is still
    // individually reversible). Cleared whenever expand-all is toggled off.
    var collapsedFools by remember { mutableStateOf(setOf<Long>()) }

    LaunchedEffect(traceBufferId) {
        if (traceBufferId == null) return@LaunchedEffect
        withFrameNanos {
            AutoFollowTrace.record("first_frame", traceBufferId, traceSessionId) {
                "item_count=${items.itemCount}"
            }
            Trace.beginSection("MOTD chat first frame")
            try {
                // Instant-like marker for Perfetto/gfx correlation without spanning suspension.
            } finally {
                Trace.endSection()
            }
        }
    }

    // Entry position is resolved once after refresh. Until then do not expose a transient FAB or
    // advance read state from a default index-0 layout.
    var initialPositionSettled by remember(entryPositionInitiallySettled) {
        mutableStateOf(entryPositionInitiallySettled)
    }
    // The first Paging emission after entry settlement reflects data loaded for the target, not a
    // live arrival. Consume it without auto-follow so an unread target remains on screen.
    var suppressNextAutoFollow by remember { mutableStateOf(!entryPositionInitiallySettled) }

    var draftLoaded by remember(traceBufferId) { mutableStateOf(false) }

    // Restore the per-buffer draft before consuming a one-shot mention prefill. This preserves an
    // existing draft when a user selects a nick from ChannelInfo and returns to the chat.
    LaunchedEffect(traceBufferId) {
        if (traceBufferId == null) return@LaunchedEffect
        composerText = restoreComposerDraft(loadDraft(), consumePrefill())
        draftLoaded = true
    }
    LaunchedEffect(traceBufferId, composerText.text, draftLoaded) {
        if (traceBufferId != null && draftLoaded) onDraftChanged(composerText.text)
    }
    LaunchedEffect(mentionPrefill) {
        mentionPrefill?.second?.let { composerText = appendPrefill(composerText, it) }
    }

    // Cap-miss / not-loaded → transient snackbar. jumpFailed is a latch StateFlow (replay-safe), so
    // a NotFound resolved before this collector subscribed is not lost (plans/15 #13).
    val jumpNotLoaded = stringResource(R.string.chat_jump_not_loaded)
    LaunchedEffect(jumpFailed) {
        if (jumpFailed) {
            onJumpFailedShown()
            onJumpUnresolved()
        }
    }

    // This survives configuration recreation, unlike the one-shot jump failure StateFlow. The
    // target is deliberately not acknowledged, so make the remaining read gate understandable.
    LaunchedEffect(entryPositionUnresolved) {
        if (shouldPresentUnresolvedEntrySnackbar(entryPositionUnresolved)) {
            snackbarHostState.showSnackbar(jumpNotLoaded)
        }
    }

    // Transient snackbars off the VM's one-shot sentinel channel: raw-send parse failure on a SERVER
    // buffer (plans/16 §5.6), or a queued reaction whose message never confirmed (plans/15 reactions).
    val invalidCommand = stringResource(R.string.chat_server_invalid_command)
    val reactFailed = stringResource(R.string.chat_react_failed)
    val reactionBlocked = stringResource(R.string.chat_reaction_blocked)
    val reactionSendFailed = stringResource(R.string.chat_reaction_send_failed)
    val historyOffline = stringResource(R.string.chat_history_offline)
    LaunchedEffect(rawSendSnackbar) {
        val sentinel = rawSendSnackbar ?: return@LaunchedEffect
        val text = when (sentinel) {
            "react_failed" -> reactFailed
            "reaction_blocked" -> reactionBlocked
            "reaction_send_failed" -> reactionSendFailed
            "history_offline" -> historyOffline
            else -> invalidCommand
        }
        snackbarHostState.showSnackbar(text)
        onSnackbarShown()
    }

    val historyUpdatedCount = (historyResyncState as? HistoryResyncState.Updated)?.inserted ?: 0
    val historyUpdated = pluralStringResource(
        R.plurals.chat_history_updated,
        historyUpdatedCount,
        historyUpdatedCount,
    )
    val historyUpToDate = stringResource(R.string.chat_history_up_to_date)
    val historyUnsupported = stringResource(R.string.chat_history_unsupported)
    val historyFailed = stringResource(R.string.chat_history_failed)
    LaunchedEffect(historyResyncState) {
        val text = when (val result = historyResyncState) {
            is HistoryResyncState.Updated -> historyUpdated
            HistoryResyncState.UpToDate -> historyUpToDate
            HistoryResyncState.Unsupported -> historyUnsupported
            is HistoryResyncState.Failed -> result.reason.ifBlank { historyFailed }
            HistoryResyncState.Idle,
            HistoryResyncState.WaitingForCapability,
            is HistoryResyncState.Running,
            -> return@LaunchedEffect
        }
        snackbarHostState.showSnackbar(text)
        onHistoryResyncShown()
    }

    // Deep-jump scroll: bounded APPEND loop (placeholders OFF, so tail loads never shift indices).
    LaunchedEffect(jumpTarget) {
        val j = jumpTarget ?: return@LaunchedEffect
        AutoFollowTrace.record("deep_jump_start", traceBufferId, traceSessionId) {
            "target_index=${j.index} item_count=${items.itemCount}"
        }
        // Guard #1: the jump resolves in VM init, often before the first paging emission. Touching
        // items[itemCount-1] with itemCount == 0 throws IndexOutOfBounds; wait for the first page.
        snapshotFlow { Triple(items.loadState.refresh, items.loadState.append, items.itemCount) }
            .first { (refresh, append, count) ->
                refresh is LoadState.NotLoading &&
                    initialPagingPage(count, append) != InitialPagingPage.Pending
            }
        if (initialPagingPage(items.itemCount, items.loadState.append) == InitialPagingPage.TerminalEmpty) {
            onJumpUnresolved()
            return@LaunchedEffect
        }

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
            // A live message may have shifted indices between resolve and scroll; re-resolve once.
            if (!deepJumpTargetMatches(j.highlightMsgid, items.peek(j.index)?.msgid)) {
                onReresolveJump()
            } else {
                AutoFollowTrace.record("deep_jump_settled", traceBufferId, traceSessionId) {
                    "target_index=${j.index} item_count=${items.itemCount} rounds=$rounds"
                }
                val targetRow = items.peek(j.index)
                if (targetRow != null && visibilityPolicy.isFool(targetRow)) {
                    expandedFools += targetRow.id
                }
                highlightMsgid = j.highlightMsgid
                initialPositionSettled = true
                suppressNextAutoFollow = true
                onJumpHandled()
            }
        } else {
            // Ran past the cap / end of pagination without reaching the target.
            onJumpUnresolved()
        }
    }

    // Normal entry shares the deep-link paging mechanics but has no highlight. It is separate so
    // a deep link always wins and so a completed normal entry cannot be replayed on recomposition.
    LaunchedEffect(initialTarget) {
        val target = initialTarget ?: return@LaunchedEffect
        AutoFollowTrace.record("initial_position_start", traceBufferId, traceSessionId) {
            "target_index=${target.index} target_offset=${target.offset} " +
                "saved=${target.fromSavedPosition} item_count=${items.itemCount}"
        }
        snapshotFlow { Triple(items.loadState.refresh, items.loadState.append, items.itemCount) }
            .first { (refresh, append, count) ->
                refresh is LoadState.NotLoading &&
                    initialPagingPage(count, append) != InitialPagingPage.Pending
            }

        var rounds = 0
        while (items.itemCount <= target.index &&
            target.index > 0 &&
            rounds++ < 64 &&
            items.itemCount > 0 &&
            !items.loadState.append.endOfPaginationReached
        ) {
            val before = items.itemCount
            items[items.itemCount - 1]
            snapshotFlow { items.loadState.append to items.itemCount }
                .first { (append, count) ->
                    (append is LoadState.NotLoading && count > before) ||
                        append.endOfPaginationReached || append is LoadState.Error
                }
        }
        if (items.itemCount > target.index) {
            // A fresh reverse list is already at index 0. If this destination retained a previous
            // off-bottom list state, explicitly restore newest; older unread targets also position.
            val currentlyAtBottom = listState.firstVisibleItemIndex == 0 &&
                listState.firstVisibleItemScrollOffset <= AUTOSCROLL_BOTTOM_TOLERANCE_PX
            if (shouldScrollToInitialTarget(target, currentlyAtBottom)) {
                listState.scrollToItem(target.index, target.offset)
            }
            AutoFollowTrace.record("initial_position_settled", traceBufferId, traceSessionId) {
                "target_index=${target.index} index=${listState.firstVisibleItemIndex} " +
                    "offset=${listState.firstVisibleItemScrollOffset} at_bottom=$currentlyAtBottom rounds=$rounds"
            }
            initialPositionSettled = true
            suppressNextAutoFollow = true
            onInitialPositionHandled()
        } else if (target.index == 0 &&
            initialPagingPage(items.itemCount, items.loadState.append) == InitialPagingPage.TerminalEmpty
        ) {
            // A newest-row target may settle on an empty buffer only after its append is terminal.
            initialPositionSettled = true
            suppressNextAutoFollow = true
            onInitialPositionHandled()
        } else {
            // An APPEND cap/error must not turn an unread target into a read acknowledgement.
            AutoFollowTrace.record("initial_position_unresolved", traceBufferId, traceSessionId) {
                "target_index=${target.index} item_count=${items.itemCount} rounds=$rounds " +
                    "append=${loadStateName(items.loadState.append)}"
            }
            onInitialPositionUnresolved()
        }
    }

    // Clear the highlight after the pulse settles (~1.6s).
    LaunchedEffect(highlightMsgid) {
        if (highlightMsgid != null) {
            kotlinx.coroutines.delay(1_600)
            highlightMsgid = null
        }
    }

    fun saveCurrentScrollPosition() {
        if (!initialPositionSettled) return
        val index = listState.firstVisibleItemIndex
        if (isAtEffectiveBottom(
                firstVisibleIndex = index,
                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                itemCount = items.itemCount,
                peek = items::peek,
                policy = visibilityPolicy,
            )
        ) {
            onClearScrollPosition()
            return
        }
        // Paging can replace the outgoing buffer's snapshot with the incoming empty QUERY snapshot
        // between itemCount/index reads and onDispose. Treat that transition as no anchor to save;
        // never index the stale snapshot (the previous direct peek crashed DM navigation).
        val (anchorIndex, row) = nearestAnchorRow(
            firstVisibleIndex = index,
            itemCount = items.itemCount,
            peek = items::peek,
            policy = visibilityPolicy,
        ) ?: run {
            onClearScrollPosition()
            return
        }
        AutoFollowTrace.record("position_saved", traceBufferId, traceSessionId) {
            "index=$index anchor_index=$anchorIndex offset=${listState.firstVisibleItemScrollOffset} " +
                "row=${row.id} at_bottom=false following=${autoFollow.following}"
        }
        onScrollPositionChanged(
            ChatScrollPosition(
                index = anchorIndex,
                offset = listState.firstVisibleItemScrollOffset.takeIf { anchorIndex == index } ?: 0,
                msgid = row.msgid,
                serverTime = row.serverTime,
                rowId = row.id,
            ),
        )
    }

    // The previous collector allocated and wrote to the position cache for nearly every pixel of a
    // fling. We only need the final anchor: persist when scrolling settles, plus once on disposal so
    // a back gesture during an active fling still retains the current location.
    LaunchedEffect(initialPositionSettled, listState, visibilityPolicy) {
        if (!initialPositionSettled) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling -> if (!scrolling) saveCurrentScrollPosition() }
    }
    DisposableEffect(initialPositionSettled, listState, visibilityPolicy) {
        onDispose { saveCurrentScrollPosition() }
    }

    LaunchedEffect(initialPositionSettled, listState) {
        snapshotFlow {
            // While scrolling, deliberately stop observing itemCount/index. snapshotFlow then
            // unregisters those hot reads until the idle edge, preventing a DB query restart for
            // every row crossed by a fling while keeping the last reaction map on screen.
            if (!initialPositionSettled || listState.isScrollInProgress) {
                null
            } else {
                items.itemCount to listState.firstVisibleItemIndex
            }
        }
            .distinctUntilChanged()
            .collect { idleWindow ->
                if (idleWindow != null) {
                    onVisibleMsgidsChanged(visibleReactionMsgids(items, listState))
                }
            }
    }

    // Long-press action sheet target.
    var sheetTarget by remember { mutableStateOf<MessageEntity?>(null) }
    val sheetState = rememberModalBottomSheetState()
    val historyRefreshSheetState = rememberModalBottomSheetState()

    // Raw ignored tails do not make the user leave the meaningful bottom of the conversation.
    val atBottom by remember(listState, items, visibilityPolicy) {
        derivedStateOf {
            isAtEffectiveBottom(
                firstVisibleIndex = listState.firstVisibleItemIndex,
                firstVisibleOffset = listState.firstVisibleItemScrollOffset,
                itemCount = items.itemCount,
                peek = items::peek,
                policy = visibilityPolicy,
            )
        }
    }

    // Count nested programmatic scrolls rather than using a Boolean: an incoming pin may supersede
    // an explicit animation, and the cancelled animation must not briefly masquerade as a user drag.
    var programmaticScrolls by remember { mutableIntStateOf(0) }
    val autoScrolling = programmaticScrolls > 0
    suspend fun scrollToNewest(animate: Boolean, reason: String) {
        AutoFollowTrace.record("scroll_start", traceBufferId, traceSessionId) {
            "reason=$reason animate=$animate index=${listState.firstVisibleItemIndex} " +
                "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
        }
        autoFollow.requestFollow()
        programmaticScrolls++
        try {
            if (animate) listState.animateScrollToItem(0) else listState.scrollToItem(0)
        } finally {
            programmaticScrolls--
            AutoFollowTrace.record("scroll_end", traceBufferId, traceSessionId) {
                "reason=$reason index=${listState.firstVisibleItemIndex} " +
                    "offset=${listState.firstVisibleItemScrollOffset} following=${autoFollow.following}"
            }
        }
    }

    // Record only actual scroll-state/programmatic edges. An index/offset change caused by a Paging
    // prepend does not emit here, so it cannot be mistaken for the user leaving the bottom.
    LaunchedEffect(listState, initialPositionSettled) {
        if (!initialPositionSettled) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress to (programmaticScrolls > 0) }
            .distinctUntilChanged()
            .collect { (scrolling, programmatic) ->
                val before = autoFollow.following
                autoFollow.onScrollStateChanged(scrolling, programmatic, atBottom)
                AutoFollowTrace.record("scroll_intent", traceBufferId, traceSessionId) {
                    "scrolling=$scrolling programmatic=$programmatic at_bottom=$atBottom " +
                        "following_before=$before following_after=${autoFollow.following} " +
                        "index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
                }
                if (!scrolling) {
                    AutoFollowTrace.record("viewport_settled", traceBufferId, traceSessionId) {
                        "at_bottom=$atBottom following=${autoFollow.following} " +
                            "index=${listState.firstVisibleItemIndex} offset=${listState.firstVisibleItemScrollOffset}"
                    }
                }
            }
    }

    // Keep one collector alive for the whole settled entry. Keying a LaunchedEffect directly on
    // itemCount cancelled an in-flight animateScrollToItem when the next message arrived; because
    // that animation also set isScrollInProgress, the replacement effect believed the user had
    // scrolled away and permanently stopped following a burst. Live arrivals snap to index zero;
    // animation is reserved for explicit send/FAB actions.
    LaunchedEffect(items, initialPositionSettled, visibilityPolicy) {
        if (!initialPositionSettled) return@LaunchedEffect
        snapshotFlow {
            items.itemCount to newestEffectiveMessageId(items.itemCount, items::peek, visibilityPolicy)
        }
            .distinctUntilChanged()
            .collect { (newCount, newestEffectiveId) ->
                val oldCount = autoFollow.presentedItemCount
                val followingBefore = autoFollow.following
                if (suppressNextAutoFollow) {
                    autoFollow.reset(newCount, atBottom, newestEffectiveId)
                    liveEntryId = null
                    suppressNextAutoFollow = false
                    AutoFollowTrace.record("paging_initial", traceBufferId, traceSessionId) {
                        "old_count=$oldCount new_count=$newCount at_bottom=$atBottom " +
                            "following=${autoFollow.following} refresh=${loadStateName(items.loadState.refresh)} " +
                            "append=${loadStateName(items.loadState.append)}"
                    }
                } else {
                    val change = autoFollow.onTimelineChangedWithEntry(newCount, newestEffectiveId)
                    liveEntryId = change.liveEntryId
                    val newest = if (newCount > 0) items.peek(0) else null
                    AutoFollowTrace.record("follow_decision", traceBufferId, traceSessionId) {
                        "old_count=$oldCount new_count=$newCount at_bottom=$atBottom " +
                            "following_before=$followingBefore following_after=${autoFollow.following} " +
                            "follow=${change.shouldFollow} live_entry=${change.liveEntryId ?: -1} " +
                            "newest_row=${newest?.id ?: -1} " +
                            "newest_kind=${newest?.kind?.name ?: "NONE"} " +
                            "refresh=${loadStateName(items.loadState.refresh)} " +
                            "append=${loadStateName(items.loadState.append)}"
                    }
                    if (change.shouldFollow) scrollToNewest(animate = false, reason = "live_arrival")
                }
            }
    }
    val buffer = state.buffer
    // Mark read on new-message-while-at-bottom only (plans/07/15 #2): syncing while scrolled up
    // reading history would clear unread on other clients and destroy the local unread UX.
    LaunchedEffect(rawNewestTime, atBottom, initialPositionSettled) {
        val newestTime = rawNewestTime ?: return@LaunchedEffect
        if (initialPositionSettled && atBottom && newestTime > 0) {
            AutoFollowTrace.record("viewport_markread", traceBufferId, traceSessionId) {
                "marker=$newestTime item_count=${items.itemCount}"
            }
            onMarkRead(newestTime)
        }
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
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        modifier = Modifier
                            .clickable(enabled = buffer != null) {
                                buffer?.let { onOpenChannelInfo(it.id) }
                            }
                            .testTag("chat_title"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(
                            name = buffer?.displayName ?: "",
                            size = 34.dp,
                            isChannel = buffer?.type == BufferType.CHANNEL,
                            networkId = buffer?.networkId,
                        )
                        Column(modifier = Modifier.padding(start = 10.dp)) {
                            Text(
                                text = buffer?.displayName ?: "",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            AnimatedContent(
                                targetState = chatSubtitle(state, ctx),
                                transitionSpec = {
                                    fadeIn(MotdMotion.microFadeIn) togetherWith
                                        fadeOut(MotdMotion.microFadeOut)
                                },
                                label = "chat_subtitle",
                            ) { subtitle ->
                                if (subtitle != null) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
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
                    IconButton(
                        onClick = { overflowOpen = true },
                        modifier = Modifier.testTag("chat_overflow"),
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                        val running = historyResyncState as? HistoryResyncState.Running
                        val historyBusy = running != null ||
                            historyResyncState == HistoryResyncState.WaitingForCapability
                        if (historyBusy) {
                            DropdownMenuItem(
                                modifier = Modifier.testTag("chat_cancel_history_refresh"),
                                text = {
                                    Text(
                                        if (running?.limit != null) {
                                            stringResource(
                                                R.string.chat_history_refreshing_progress,
                                                running.fetched,
                                                running.limit,
                                            )
                                        } else {
                                            stringResource(R.string.chat_history_refreshing)
                                        },
                                    )
                                },
                                onClick = {
                                    overflowOpen = false
                                    onCancelHistoryRefresh()
                                },
                                leadingIcon = { CircularProgressIndicator(modifier = Modifier.size(24.dp)) },
                            )
                        } else {
                            DropdownMenuItem(
                                modifier = Modifier.testTag("chat_refresh_history"),
                                text = { Text(stringResource(R.string.chat_refresh_history)) },
                                onClick = {
                                    overflowOpen = false
                                    historyRefreshSheetOpen = true
                                },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                            )
                        }
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
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            // BoxWithConstraints subcomposes with the resized window constraints during Android's
            // adjustResize measure pass. Passing this exact height to the composer lets its emoji
            // replacement occupy the released IME space in the same frame instead of reacting to
            // an OnLayoutChange callback one frame later.
            val chatWindowHeightPx = constraints.maxHeight
            ConversationTypography(conversationFontScalePercent) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    // Subtle IRC-themed wallpaper layered UNDER the message list only (never over the
                    // composer). NONE renders the plain theme background; MessageList is untouched.
                    ChatWallpaperBackground(chatWallpaper, modifier = Modifier.matchParentSize())
                    MessageList(
                        items = items,
                        listState = listState,
                        liveEntryId = liveEntryId,
                        onLiveEntryConsumed = { id ->
                            liveEntryId = consumeLiveEntryId(liveEntryId, id)
                        },
                        networkId = state.buffer?.networkId,
                        // Frozen read-marker so the "— New messages —" divider stays put (plans/15 #2).
                        readMarkerTime = readMarkerSnapshot,
                        reactionChips = reactionChips,
                        replyPreview = replyPreview,
                        onLongPress = { sheetTarget = it },
                        onReply = { target ->
                            onSetReply(target)
                            composerText = composerTextForReply(
                                value = composerText,
                                sender = target.sender,
                                bufferType = state.buffer?.type,
                                visibleReplyPrefix = visibleReplyPrefix,
                            )
                        },
                        onReact = onReact,
                        onImageClick = onOpenImage,
                        onRetry = onRetry,
                        onDelete = onDelete,
                        onAcceptInvite = onAcceptInvite,
                        onDismissInvite = onDismissInvite,
                        loadPreview = loadPreview,
                        richContentReady = initialPositionSettled,
                        showImages = showImages,
                        showLinkPreviews = showLinkPreviews,
                        cachedPreview = cachedPreview,
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

                    // Keep the hot firstVisibleItemIndex read inside the FAB subtree. Reading it in
                    // ChatContent made every row boundary re-run the entire Scaffold/list/composer.
                    ViewportScrollToBottomFab(
                        items = items,
                        listState = listState,
                        readMarker = readMarkerLive,
                        visibilityPolicy = visibilityPolicy,
                        visible = initialPositionSettled && !atBottom && !autoScrolling,
                        onClick = { scope.launch { scrollToNewest(animate = true, reason = "jump_fab") } },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }

                val completions = remember(composerText, memberNicks, recentSpeakers) {
                    autocompleteFor(composerText, memberNicks, recentSpeakers, nickNormalizer)
                }
                val needsMemberCompletion = remember(composerText) {
                    composerNeedsMemberNicks(composerText)
                }
                LaunchedEffect(needsMemberCompletion) {
                    if (needsMemberCompletion) onNeedMembers()
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
                            if (isLongDraft(text)) {
                                AutoFollowTrace.record("long_draft_prompt_open", traceBufferId, traceSessionId)
                                longDraftPrompt = true
                            } else {
                                AutoFollowTrace.record("composer_submit", traceBufferId, traceSessionId) {
                                    "long_draft=false"
                                }
                                onSubmit(text)
                                composerText = TextFieldValue("")
                                onDraftCleared()
                                scope.launch {
                                    scrollToNewest(animate = true, reason = "composer_send_action")
                                }
                            }
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
                    onAttachment = { uploadCurrentDraftDirectly = false; attachmentSheetOpen = true },
                    chatWindowHeightPx = chatWindowHeightPx,
                    autocomplete = if (showAutocomplete && completions.isNotEmpty()) {
                        {
                            AutocompletePanel(
                                candidates = completions.map { it.display },
                                isCommand = completions.firstOrNull()?.isCommand == true,
                                networkId = state.buffer?.networkId,
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
    }

    AttachmentSheets(
        open = attachmentSheetOpen,
        currentDraft = composerText.text,
        startWithCurrentDraft = uploadCurrentDraftDirectly,
        onDismiss = { attachmentSheetOpen = false; uploadCurrentDraftDirectly = false },
        onInsertUrl = { composerText = io.github.trevarj.motd.ui.components.insertAtCursor(composerText, it) },
        onReplaceDraft = { composerText = TextFieldValue(it, androidx.compose.ui.text.TextRange(it.length)) },
    )
    if (historyRefreshSheetOpen) {
        HistoryRefreshSheet(
            sheetState = historyRefreshSheetState,
            onDismiss = { historyRefreshSheetOpen = false },
            onRange = {
                historyRefreshSheetOpen = false
                onRefreshHistory(it)
            },
        )
    }
    if (longDraftPrompt) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                AutoFollowTrace.record("long_draft_dismiss", traceBufferId, traceSessionId)
                longDraftPrompt = false
            },
            title = { Text("Long draft") },
            text = { Text("Upload the draft as a paste, or send it as ordinary IRC messages?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = {
                AutoFollowTrace.record("long_draft_upload", traceBufferId, traceSessionId)
                longDraftPrompt = false
                uploadCurrentDraftDirectly = true
                attachmentSheetOpen = true
            }) { Text("Upload as paste") } },
            dismissButton = { Row {
                androidx.compose.material3.TextButton(onClick = {
                    AutoFollowTrace.record("long_draft_send_messages", traceBufferId, traceSessionId)
                    longDraftPrompt = false
                    onSubmit(composerText.text)
                    composerText = TextFieldValue("")
                    onDraftCleared()
                    scope.launch { scrollToNewest(animate = true, reason = "long_draft_send") }
                }) { Text("Send as messages") }
                androidx.compose.material3.TextButton(onClick = {
                    AutoFollowTrace.record("long_draft_cancel", traceBufferId, traceSessionId)
                    longDraftPrompt = false
                }) { Text("Cancel") }
            } },
        )
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
            onReply = {
                hideThen {
                    onSetReply(target)
                    composerText = composerTextForReply(
                        value = composerText,
                        sender = target.sender,
                        bufferType = state.buffer?.type,
                        visibleReplyPrefix = visibleReplyPrefix,
                    )
                }
            },
            // Pass the whole target: the VM queues the react when target.msgid is still null (own
            // pending message) instead of silently dropping it.
            onReact = { emoji -> hideThen { onReact(target, emoji) } },
            reactionEnabled = { emoji ->
                val ready = state.connState as? IrcClientState.Ready
                val mine = target.msgid?.let { msgid ->
                    reactionChips(msgid).firstOrNull { it.emoji == emoji }?.mine
                } == true
                ready != null && canSendReactionTags(ready.caps, ready.isupport, remove = mine)
            },
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

internal enum class ChatTitleTarget { CHANNEL_INFO, NICK_DETAILS, NONE }

/** The title bar destination is a property of the buffer, not its display-name prefix. */
internal fun chatTitleTarget(type: BufferType?): ChatTitleTarget = when (type) {
    BufferType.CHANNEL -> ChatTitleTarget.CHANNEL_INFO
    BufferType.QUERY -> ChatTitleTarget.NICK_DETAILS
    BufferType.SERVER, null -> ChatTitleTarget.NONE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryRefreshSheet(
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onRange: (HistoryRefreshRange) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("chat_history_refresh_sheet"),
    ) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.chat_history_refresh_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                stringResource(R.string.chat_history_refresh_description),
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            HistoryRefreshOption(HistoryRefreshRange.MISSING, R.string.chat_history_range_missing, onRange)
            HistoryRefreshOption(HistoryRefreshRange.HOURS_24, R.string.chat_history_range_24h, onRange)
            HistoryRefreshOption(HistoryRefreshRange.DAYS_7, R.string.chat_history_range_7d, onRange)
            HistoryRefreshOption(HistoryRefreshRange.DAYS_30, R.string.chat_history_range_30d, onRange)
            HistoryRefreshOption(HistoryRefreshRange.ALL_AVAILABLE, R.string.chat_history_range_all, onRange)
            androidx.compose.material3.TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@Composable
private fun HistoryRefreshOption(
    range: HistoryRefreshRange,
    @androidx.annotation.StringRes label: Int,
    onRange: (HistoryRefreshRange) -> Unit,
) {
    androidx.compose.material3.TextButton(
        onClick = { onRange(range) },
        modifier = Modifier.fillMaxWidth().testTag("chat_history_range_${range.name.lowercase()}"),
    ) {
        Text(stringResource(label))
    }
}

/** Safely read a Paging snapshot that may be replaced between its count read and item lookup. */
internal inline fun <T> pagingSnapshotItemOrNull(
    index: Int,
    itemCount: Int,
    lookup: (Int) -> T?,
): T? {
    if (index !in 0 until itemCount) return null
    return try {
        lookup(index)
    } catch (_: IndexOutOfBoundsException) {
        null
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

/** Restore a buffer draft, then merge a one-shot mention prefill without losing the cursor. */
internal fun restoreComposerDraft(draft: String?, prefill: String?): TextFieldValue {
    val saved = draft.orEmpty()
    val value = TextFieldValue(saved, TextRange(saved.length))
    return prefill?.let { appendPrefill(value, it) } ?: value
}

/** Add the visible channel-reply prefix while preserving the current selection. */
fun prependReplyPrefix(value: TextFieldValue, sender: String): TextFieldValue {
    if (sender.isBlank()) return value
    val prefix = "$sender: "
    if (value.text.startsWith(prefix)) return value
    val text = prefix + value.text
    return value.copy(
        text = text,
        selection = androidx.compose.ui.text.TextRange(
            value.selection.start + prefix.length,
            value.selection.end + prefix.length,
        ),
    )
}

/** Apply the configured visible prefix consistently for every reply gesture. */
internal fun composerTextForReply(
    value: TextFieldValue,
    sender: String,
    bufferType: BufferType?,
    visibleReplyPrefix: Boolean,
): TextFieldValue = if (visibleReplyPrefix && bufferType == BufferType.CHANNEL) {
    prependReplyPrefix(value, sender)
} else {
    value
}

/**
 * Header subtitle: typing summary if anyone is typing, else a localized member count for channels.
 * Uses the [Context] typing overload and a plural for the count (plans/15 #25).
 */
/** The durable unresolved-entry state is the sole source of the not-loaded snackbar. */
internal fun shouldPresentUnresolvedEntrySnackbar(entryPositionUnresolved: Boolean): Boolean =
    entryPositionUnresolved

/**
 * A completed REFRESH may still be followed by a Room/RemoteMediator APPEND. Do not decide entry
 * positioning from a transient empty window; only rows or a terminal empty append are conclusive.
 */
internal fun initialPagingPage(itemCount: Int, append: LoadState): InitialPagingPage = when {
    itemCount > 0 -> InitialPagingPage.RowsAvailable
    append is LoadState.Error -> InitialPagingPage.TerminalEmpty
    append is LoadState.NotLoading && append.endOfPaginationReached -> InitialPagingPage.TerminalEmpty
    else -> InitialPagingPage.Pending
}

internal enum class InitialPagingPage { Pending, RowsAvailable, TerminalEmpty }

internal fun loadStateName(state: LoadState): String = when (state) {
    is LoadState.Loading -> "LOADING"
    is LoadState.NotLoading -> if (state.endOfPaginationReached) "DONE" else "IDLE"
    is LoadState.Error -> "ERROR"
}

internal fun visibleReactionMsgids(
    items: LazyPagingItems<MessageEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState,
): List<String> {
    val visible = listState.layoutInfo.visibleItemsInfo
        .map { it.index }
        .filter { it >= 0 && it < items.itemCount }
    val start: Int
    val endExclusive: Int
    if (visible.isEmpty()) {
        start = 0
        endExclusive = minOf(items.itemCount, REACTION_PREFETCH_ROWS * 2)
    } else {
        start = (visible.minOrNull() ?: 0).minus(REACTION_PREFETCH_ROWS).coerceAtLeast(0)
        endExclusive = ((visible.maxOrNull() ?: 0) + REACTION_PREFETCH_ROWS + 1)
            .coerceAtMost(items.itemCount)
    }
    if (start >= endExclusive) return emptyList()
    return (start until endExclusive)
        .asSequence()
        .mapNotNull { items.peek(it)?.msgid }
        .distinct()
        .take(MAX_VISIBLE_REACTION_MSGIDS)
        .toList()
}

internal fun composerNeedsMemberNicks(value: TextFieldValue): Boolean {
    val text = value.text
    if (text.startsWith("/") && !text.startsWith("//") && !text.contains(' ')) return false
    val token = nickTokenAt(text, value.selection.end) ?: return false
    val atPrefixed = token.start < text.length && text[token.start] == '@'
    return token.text.length >= 2 || atPrefixed
}

internal fun chatSubtitle(state: ChatState, context: android.content.Context): String? {
    when (val connection = state.connState) {
        IrcClientState.Connecting -> return context.getString(R.string.drawer_state_connecting)
        IrcClientState.Registering -> return context.getString(R.string.drawer_state_registering)
        IrcClientState.Disconnected -> return context.getString(R.string.drawer_state_disconnected)
        is IrcClientState.Failed -> return if (connection.fatal) {
            connection.reason
        } else {
            context.getString(R.string.drawer_state_connecting)
        }
        is IrcClientState.Ready -> Unit
    }
    if (state.typingNicks.isNotEmpty()) {
        return typingText(context, state.typingNicks)
    }
    val buffer = state.buffer ?: return null
    return if (buffer.type == BufferType.CHANNEL && state.memberCount != null) {
        val n = state.memberCount
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
@Composable
private fun ScrollToBottomFab(
    visible: Boolean,
    unread: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = visible, enter = scaleIn(), exit = scaleOut(), modifier = modifier) {
        BadgedBox(
            badge = {
                if (unread > 0) {
                    Badge { Text(if (unread >= MAX_UNREAD_BADGE_COUNT) "99+" else "$unread") }
                }
            },
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

/**
 * Viewport-aware FAB wrapper. This is intentionally its own restart scope: the first visible index
 * changes repeatedly during a fling, while the expensive chat scaffold and lazy-list declaration do
 * not. Only this small badge subtree recomposes at message boundaries.
 */
@Composable
private fun ViewportScrollToBottomFab(
    items: LazyPagingItems<MessageEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    readMarker: Long?,
    visibilityPolicy: MessageVisibilityPolicy,
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstVisible by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    val unreadIndex = remember(visibilityPolicy) { UnreadViewportIndex() }
    val unread = readMarker?.let { marker ->
        // History growth appends older Paging rows. The compact index reads only the unread prefix
        // (and never more than the displayed 99+ cap); index-zero identity detects refreshes.
        unreadIndex.update(
            itemCount = items.itemCount,
            peek = items::peek,
            maxNonSelf = MAX_UNREAD_BADGE_COUNT,
            stopAtOrBefore = marker,
            include = visibilityPolicy::visibleUnread,
        )
        unreadIndex.count(firstVisible, marker)
    } ?: 0
    ScrollToBottomFab(
        visible = visible,
        unread = unread,
        onClick = onClick,
        modifier = modifier,
    )
}

@Preview
@Composable
private fun ChatContentPreview() {
    MotdTheme {
        ChatContentPreviewBody()
    }
}

@Preview(name = "Conversation 140% + large system font", fontScale = 1.5f)
@Composable
private fun ChatContentLargeTextPreview() {
    MotdTheme {
        ChatContentPreviewBody(conversationFontScalePercent = 140)
    }
}
