package io.github.trevarj.motd.ui.chatlist

import android.app.Activity
import android.os.SystemClock
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.HapticFeedbackConstantsCompat
import androidx.core.view.ViewCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.audio.AudioPlaybackOrigin
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.InviteState
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.repo.FolderIconRef
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.sidecar.SidecarContract
import io.github.trevarj.motd.sidecar.SidecarJson
import io.github.trevarj.motd.sidecar.SidecarTargetKind
import io.github.trevarj.motd.ui.components.AudioMiniPlayer
import io.github.trevarj.motd.ui.components.AudioPlaybackViewModel
import io.github.trevarj.motd.ui.components.ConnectionBanner
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.components.FolderIcon
import io.github.trevarj.motd.ui.components.HistorySyncSpinner
import io.github.trevarj.motd.ui.components.MuteBacklogUndoEffect
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Stateful entry: wires the ViewModel and drives navigation/empty-state. */
@Composable
fun ChatListScreen(
    onOpenBuffer: (Long) -> Unit = {},
    onOpenAudioOrigin: (AudioPlaybackOrigin) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenFeed: () -> Unit = {},
    onOpenManageFolders: () -> Unit = {},
    onOpenFolderEditor: (Long) -> Unit = {},
    onOpenAutoGroup: (Long?) -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    // Round 5: drawer/network-management pass-throughs.
    onOpenNetworkSettings: (Long) -> Unit = {},
    onOpenAddNetwork: () -> Unit = {},
    onScanInvite: () -> Unit = {},
    onOpenChannelList: (Long) -> Unit = {},
    selectedBufferId: Long? = null,
    suppressOnboarding: Boolean = false,
    onDefaultBufferAvailable: (Long) -> Unit = {},
    viewModel: ChatListViewModel = hiltViewModel(),
    audioViewModel: AudioPlaybackViewModel = hiltViewModel(),
    sidecarTargetViewModel: SidecarTargetViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val audioPlaybackState by audioViewModel.state.collectAsStateWithLifecycle()
    // Collected once here, outside the ChatListState combine (see ChatListViewModel), so a
    // buffer's sync transition recomposes only its own row rather than the whole list.
    val syncIndicators by viewModel.syncIndicators.collectAsStateWithLifecycle()
    val syncChrome by viewModel.syncChrome.collectAsStateWithLifecycle()
    val titleConnecting by viewModel.titleConnecting.collectAsStateWithLifecycle()
    val sidecarScope = rememberCoroutineScope()
    var pendingSidecarNetworkId by remember { mutableStateOf<Long?>(null) }
    val sidecarTargetLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val networkId = pendingSidecarNetworkId
            val raw = result.data?.getStringExtra(SidecarContract.RESULT_TARGET_JSON)
            pendingSidecarNetworkId = null
            if (networkId != null && result.resultCode == Activity.RESULT_OK && raw != null) {
                runCatching { SidecarJson.decodeTarget(raw) }.getOrNull()?.let { target ->
                    if (target.kind == SidecarTargetKind.ROOM) {
                        viewModel.joinChannel(networkId, target.wireTarget, null)
                    } else {
                        sidecarScope.launch {
                            onOpenBuffer(
                                sidecarTargetViewModel.openPerson(
                                    networkId,
                                    target.wireTarget,
                                    target.displayName,
                                ),
                            )
                        }
                    }
                }
            }
        }

    // Fresh installs enter onboarding once state is loaded; a durable skip keeps the empty main UI.
    LaunchedEffect(state.loading, state.networks.isEmpty(), state.onboardingComplete, suppressOnboarding) {
        if (shouldOpenOnboarding(state, suppressOnboarding)) {
            onOpenOnboarding()
        }
    }
    LaunchedEffect(state.loading, state.rows) {
        if (!state.loading) defaultChatBufferId(state.rows)?.let(onDefaultBufferAvailable)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    MuteBacklogUndoEffect(
        suppressions = viewModel.muteBacklogSuppressions,
        hostState = snackbarHostState,
        onUndo = viewModel::undoMuteBacklogSuppression,
    )

    ChatListContent(
        state = state,
        syncIndicators = syncIndicators,
        syncChrome = syncChrome,
        titleConnecting = titleConnecting,
        snackbarHostState = snackbarHostState,
        audioPlaybackState = audioPlaybackState,
        onAudioToggle = audioViewModel::toggle,
        onAudioCancelLoading = audioViewModel::cancelLoading,
        onAudioRetry = audioViewModel::retry,
        onAudioDismiss = audioViewModel::dismiss,
        onAudioSeek = audioViewModel::seek,
        onAudioSpeed = audioViewModel::setSpeed,
        onOpenAudioOrigin = onOpenAudioOrigin,
        onOpenBuffer = onOpenBuffer,
        onOpenSettings = onOpenSettings,
        onOpenSearch = onOpenSearch,
        onOpenFeed = onOpenFeed,
        onOpenManageFolders = onOpenManageFolders,
        onOpenFolderEditor = onOpenFolderEditor,
        onOpenAutoGroup = onOpenAutoGroup,
        onAssignFolder = viewModel::assignFolder,
        onCreateFolderAndAssign = viewModel::createFolderAndAssign,
        onSetFolderExpanded = viewModel::setFolderExpanded,
        onSetPinned = viewModel::setPinned,
        onSetMuted = viewModel::setMuted,
        onSetArchived = viewModel::setArchived,
        onDeleteBuffers = viewModel::deleteBuffers,
        onAcceptInvitation = viewModel::acceptInvitation,
        onIgnoreInvitation = viewModel::ignoreInvitation,
        onJoinChannel = viewModel::joinChannel,
        onMessageUser = { networkId, nick -> viewModel.messageUser(networkId, nick, onOpenBuffer) },
        // Round 5: drawer selection + connectivity + nav.
        onSelectNetwork = viewModel::selectNetwork,
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onGoOffline = viewModel::goOffline,
        onGoOnline = viewModel::goOnline,
        onServerMessages = { networkId -> viewModel.openServerBuffer(networkId, onOpenBuffer) },
        onOpenNetworkSettings = onOpenNetworkSettings,
        onOpenAddNetwork = onOpenAddNetwork,
        onScanInvite = onScanInvite,
        onOpenChannelList = onOpenChannelList,
        onChooseProviderTarget = { networkId ->
            sidecarScope.launch {
                sidecarTargetViewModel.createIntent(networkId)?.let { intent ->
                    pendingSidecarNetworkId = networkId
                    sidecarTargetLauncher.launch(intent)
                }
            }
        },
        onMarkAllRead = viewModel::markCurrentScopeRead,
        onMarkSelectedRead = viewModel::markSelectedRead,
        onMoveNetwork = viewModel::moveNetwork,
        onCommitNetworkOrder = viewModel::commitNetworkOrder,
        selectedBufferId = selectedBufferId,
    )
}

internal fun defaultChatBufferId(rows: List<ChatListRow>): Long? =
    rows
        .maxWithOrNull(
            compareBy<ChatListRow> { it.lastMessageTime ?: Long.MIN_VALUE }
                .thenBy(ChatListRow::bufferId),
        )?.bufferId

/**
 * The top bar's transition key: title/actions cross-fade only when the mode itself changes, so a
 * count tick or scoped-name change within a mode updates its text without re-animating.
 */
private enum class ChatListTopBarMode { SELECTION, INVITATIONS, ARCHIVE, SCOPED, DEFAULT }

private fun chatListTopBarMode(
    selectionActive: Boolean,
    invitationMode: Boolean,
    archiveMode: Boolean,
    scoped: Boolean,
): ChatListTopBarMode =
    when {
        selectionActive -> ChatListTopBarMode.SELECTION
        invitationMode -> ChatListTopBarMode.INVITATIONS
        archiveMode -> ChatListTopBarMode.ARCHIVE
        scoped -> ChatListTopBarMode.SCOPED
        else -> ChatListTopBarMode.DEFAULT
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListContent(
    state: ChatListState,
    syncIndicators: Map<Long, ChatListSyncIndicator> = emptyMap(),
    syncChrome: ChatListSyncChrome = ChatListSyncChrome.Hidden,
    titleConnecting: Boolean = false,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    audioPlaybackState: AudioPlaybackState = AudioPlaybackState(),
    onAudioToggle: () -> Unit = {},
    onAudioCancelLoading: () -> Unit = {},
    onAudioRetry: () -> Unit = {},
    onAudioDismiss: () -> Unit = {},
    onAudioSeek: (Long) -> Unit = {},
    onAudioSpeed: (Float) -> Unit = {},
    onOpenAudioOrigin: (AudioPlaybackOrigin) -> Unit = {},
    onOpenBuffer: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenFeed: () -> Unit = {},
    onOpenManageFolders: () -> Unit = {},
    onOpenFolderEditor: (Long) -> Unit = {},
    onOpenAutoGroup: (Long?) -> Unit = {},
    onAssignFolder: (Collection<Long>, Long?, (Boolean) -> Unit) -> Unit = { _, _, done -> done(false) },
    onCreateFolderAndAssign: (String, FolderIconRef, Collection<Long>, (Boolean) -> Unit) -> Unit = { _, _, _, done -> done(false) },
    onSetFolderExpanded: (Long, Boolean) -> Unit = { _, _ -> },
    onSetPinned: (Collection<Long>, Boolean) -> Unit,
    onSetMuted: (Collection<Long>, Boolean) -> Unit,
    onSetArchived: (Collection<Long>, Boolean) -> Unit = { _, _ -> },
    onJoinChannel: (Long, String, String?) -> Unit,
    onMessageUser: (Long, String) -> Unit,
    onDeleteBuffers: (Collection<ChatListRow>) -> Unit = {},
    onAcceptInvitation: (Long) -> Unit = {},
    onIgnoreInvitation: (Long) -> Unit = {},
    // Round 5: drawer + scoping. Defaulted so previews stay terse.
    onSelectNetwork: (Long?) -> Unit = {},
    onConnect: (Long) -> Unit = {},
    onDisconnect: (Long) -> Unit = {},
    onGoOffline: () -> Unit = {},
    onGoOnline: () -> Unit = {},
    onServerMessages: (Long) -> Unit = {},
    onOpenNetworkSettings: (Long) -> Unit = {},
    onOpenAddNetwork: () -> Unit = {},
    onScanInvite: () -> Unit = {},
    onOpenChannelList: (Long) -> Unit = {},
    onChooseProviderTarget: (Long) -> Unit = {},
    onMarkAllRead: () -> Unit = {},
    onMarkSelectedRead: (Collection<Long>) -> Unit = {},
    // Manual drawer order (see DrawerReorder.kt); defaulted so previews and tests stay terse.
    onMoveNetwork: (Long, Int) -> Unit = { _, _ -> },
    onCommitNetworkOrder: (List<Long>) -> Unit = {},
    selectedBufferId: Long? = null,
) {
    var archiveMode by rememberSaveable { mutableStateOf(false) }
    var invitationMode by rememberSaveable { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var showMarkAllReadDialog by remember { mutableStateOf(false) }
    var showFolderAssignment by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // The per-row network tag is redundant once the list is scoped to one network.
    val showNetworkChip = state.networks.size > 1 && state.selectedNetworkId == null
    val visibleRows = if (archiveMode) state.archivedRows else state.rows
    var selectedIds by rememberSaveable(archiveMode, invitationMode, state.selectedNetworkId) { mutableStateOf(emptyList<Long>()) }
    val selectedRows = orderedSelectedRows(visibleRows, selectedIds)
    val selectionActive = selectedRows.isNotEmpty()
    val topBarMode =
        chatListTopBarMode(
            selectionActive = selectionActive,
            invitationMode = invitationMode,
            archiveMode = archiveMode,
            scoped = state.selectedNetworkName != null,
        )
    // Exit latches: the selection empties and the scope name nulls on the same frame their mode
    // leaves, so the outgoing top-bar/chip content holds the last real value while it fades.
    var lastSelectedRows by remember { mutableStateOf(listOf<ChatListRow>()) }
    if (selectedRows.isNotEmpty()) lastSelectedRows = selectedRows
    var lastScopeName by remember { mutableStateOf("") }
    state.selectedNetworkName?.let { lastScopeName = it }
    var confirmRemoval by remember { mutableStateOf(false) }
    var archiveRevealSignal by rememberSaveable(state.selectedNetworkId) { mutableStateOf(0) }

    fun setArchivedWithReveal(
        ids: Collection<Long>,
        archived: Boolean,
    ) {
        onSetArchived(ids, archived)
        if (!archiveMode && archived && ids.isNotEmpty()) archiveRevealSignal += 1
    }

    LaunchedEffect(visibleRows) {
        selectedIds = pruneSelectedIds(selectedIds, visibleRows)
        if (confirmRemoval && orderedSelectedRows(visibleRows, selectedIds).isEmpty()) confirmRemoval = false
    }

    // One ordered Back policy keeps drawer, transient selection, and archive mode independent.
    BackHandler(enabled = drawerState.isOpen || selectionActive || archiveMode || invitationMode) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            selectionActive -> selectedIds = emptyList()
            archiveMode -> archiveMode = false
            else -> invitationMode = false
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ServerDrawerContent(
                drawerRows = state.drawerRows,
                selectedNetworkId = state.selectedNetworkId,
                allUnread = state.allUnread,
                allMentions = state.allMentions,
                allUnreadIncomplete = state.allUnreadIncomplete,
                allMentionsIncomplete = state.allMentionsIncomplete,
                scopedUnreadCount = state.scopedUnreadCount,
                allOffline = state.allOffline,
                onSelectNetwork = { id ->
                    onSelectNetwork(id)
                    scope.launch { drawerState.close() }
                },
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onServerMessages = { id ->
                    onServerMessages(id)
                    scope.launch { drawerState.close() }
                },
                onOpenNetworkSettings = { id ->
                    onOpenNetworkSettings(id)
                    scope.launch { drawerState.close() }
                },
                onAddNetwork = {
                    onOpenAddNetwork()
                    scope.launch { drawerState.close() }
                },
                onScanInvite = {
                    onScanInvite()
                    scope.launch { drawerState.close() }
                },
                onToggleOffline = { if (state.allOffline) onGoOnline() else onGoOffline() },
                onOpenSettings = {
                    onOpenSettings()
                    scope.launch { drawerState.close() }
                },
                onOpenFeed = {
                    scope.launch { drawerState.close() }
                    onOpenFeed()
                },
                globalFeedEnabled = state.globalFeedEnabled,
                onMarkAllRead = {
                    scope.launch { drawerState.close() }
                    showMarkAllReadDialog = true
                },
                onMoveNetwork = onMoveNetwork,
                onCommitNetworkOrder = onCommitNetworkOrder,
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.testTag("screen_chat_list"),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                ChatListTopBar(
                    modifier = Modifier.testTag(if (selectionActive) "chatlist_selection_top_app_bar" else "chatlist_top_app_bar"),
                    title = {
                        AnimatedContent(
                            targetState = topBarMode,
                            transitionSpec = {
                                // Cross-fade only; using(null) drops the default SizeTransform so
                                // the bar never pumps when the title width changes.
                                (fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)).using(null)
                            },
                            label = "chatlist_top_bar_title",
                        ) { mode ->
                            when (mode) {
                                ChatListTopBarMode.SELECTION -> {
                                    // Never wrap: a growing action row must shrink this font instead
                                    // of pushing the count to multiple lines and blowing out the bar's
                                    // height. Ellipsis is only the last-resort floor once even the
                                    // smallest step can't fit (e.g. a huge selected count on a tiny
                                    // screen).
                                    Text(
                                        pluralStringResource(R.plurals.chatlist_selected_count, lastSelectedRows.size, lastSelectedRows.size),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 22.sp),
                                    )
                                }

                                ChatListTopBarMode.INVITATIONS -> {
                                    Text(text = stringResource(R.string.chatlist_invitations), fontWeight = FontWeight.Bold)
                                }

                                ChatListTopBarMode.ARCHIVE -> {
                                    Text(text = stringResource(R.string.chatlist_archived_chats), fontWeight = FontWeight.Bold)
                                }

                                // Scoped: show the network name so the active filter is legible.
                                ChatListTopBarMode.SCOPED -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = lastScopeName, fontWeight = FontWeight.Bold)
                                        ChatListTitleConnectingSpinner(visible = titleConnecting)
                                    }
                                }

                                // Use the platform typography instead of the stylized brand asset here.
                                ChatListTopBarMode.DEFAULT -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = stringResource(R.string.app_name),
                                            fontWeight = FontWeight.Bold,
                                        )
                                        ChatListTitleConnectingSpinner(visible = titleConnecting)
                                    }
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                when {
                                    selectionActive -> selectedIds = emptyList()
                                    invitationMode -> invitationMode = false
                                    archiveMode -> archiveMode = false
                                    else -> scope.launch { drawerState.open() }
                                }
                            },
                            modifier = Modifier.testTag("chatlist_selection_close"),
                        ) {
                            // One IconButton across every mode (it owns the close/back/menu tag);
                            // only the glyph cross-fades when the mode changes what it means.
                            Crossfade(
                                targetState = if (selectionActive || archiveMode || invitationMode) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Menu,
                                animationSpec = MotdMotion.microFadeIn,
                                label = "chatlist_nav_icon",
                            ) { icon ->
                                Icon(
                                    icon,
                                    contentDescription =
                                        stringResource(
                                            if (selectionActive) {
                                                R.string.chatlist_selection_close
                                            } else if (archiveMode || invitationMode) {
                                                R.string.action_back
                                            } else {
                                                R.string.drawer_open
                                            },
                                        ),
                                )
                            }
                        }
                    },
                    actions = {
                        AnimatedContent(
                            targetState = topBarMode,
                            transitionSpec = {
                                // Cross-fade only; using(null) drops the default SizeTransform so
                                // the bar never pumps when the action set changes width.
                                (fadeIn(MotdMotion.microFadeIn) togetherWith fadeOut(MotdMotion.microFadeOut)).using(null)
                            },
                            label = "chatlist_top_bar_actions",
                        ) { mode ->
                            Row {
                                when (mode) {
                                    ChatListTopBarMode.SELECTION -> {
                                        // Glyph targets read the exit latch; the actions themselves
                                        // still apply to the live selection.
                                        val pinTarget = aggregateToggleTarget(lastSelectedRows) { it.pinned }
                                        val muteTarget = aggregateToggleTarget(lastSelectedRows) { it.muted }
                                        var overflowOpen by remember { mutableStateOf(false) }
                                        // Only the three most-reached-for actions stay inline; pin and
                                        // archive move behind "more" so a growing action set never
                                        // starves the title (the autoSize/ellipsis fallback above is
                                        // the last resort, not the primary fix) or forces icons to
                                        // shrink/wrap on narrower phones.
                                        IconButton(
                                            onClick = {
                                                onMarkSelectedRead(selectedRows.map(ChatListRow::bufferId))
                                                selectedIds = emptyList()
                                            },
                                            modifier = Modifier.testTag("chatlist_selection_mark_read"),
                                        ) { Icon(Icons.Outlined.DoneAll, stringResource(R.string.chatlist_mark_read)) }
                                        IconButton(
                                            onClick = {
                                                onSetMuted(selectedRows.map(ChatListRow::bufferId), muteTarget)
                                                selectedIds = emptyList()
                                            },
                                            modifier = Modifier.testTag("chatlist_selection_mute"),
                                        ) { Icon(if (muteTarget) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications, stringResource(if (muteTarget) R.string.chatlist_mute else R.string.chatlist_unmute)) }
                                        IconButton(onClick = { confirmRemoval = true }, modifier = Modifier.testTag("chatlist_selection_remove")) {
                                            Icon(Icons.Outlined.Delete, stringResource(R.string.chatlist_remove))
                                        }
                                        Box {
                                            IconButton(
                                                onClick = { overflowOpen = true },
                                                modifier = Modifier.testTag("chatlist_selection_more"),
                                            ) {
                                                Icon(Icons.Filled.MoreVert, stringResource(R.string.chatlist_more_actions))
                                            }
                                            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.folders_add_to)) },
                                                    leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                                                    modifier = Modifier.testTag("chatlist_selection_add_folder"),
                                                    onClick = {
                                                        showFolderAssignment = true
                                                        overflowOpen = false
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(if (pinTarget) R.string.chatlist_pin else R.string.chatlist_unpin)) },
                                                    leadingIcon = { Icon(Icons.Filled.PushPin, contentDescription = null) },
                                                    modifier = Modifier.testTag("chatlist_selection_pin"),
                                                    onClick = {
                                                        onSetPinned(selectedRows.map(ChatListRow::bufferId), pinTarget)
                                                        selectedIds = emptyList()
                                                        overflowOpen = false
                                                    },
                                                )
                                                DropdownMenuItem(
                                                    text = {
                                                        Text(
                                                            stringResource(
                                                                if (archiveMode) R.string.chatlist_unarchive else R.string.chatlist_archive,
                                                            ),
                                                        )
                                                    },
                                                    leadingIcon = { Icon(archiveActionIcon(archiveMode), contentDescription = null) },
                                                    modifier = Modifier.testTag("chatlist_selection_archive"),
                                                    onClick = {
                                                        setArchivedWithReveal(selectedRows.map(ChatListRow::bufferId), !archiveMode)
                                                        selectedIds = emptyList()
                                                        overflowOpen = false
                                                    },
                                                )
                                            }
                                        }
                                    }

                                    ChatListTopBarMode.DEFAULT, ChatListTopBarMode.SCOPED -> {
                                        IconButton(onClick = onOpenSearch) {
                                            Icon(
                                                Icons.Outlined.Search,
                                                contentDescription = stringResource(R.string.chatlist_search),
                                            )
                                        }
                                        var moreOpen by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(onClick = { moreOpen = true }, modifier = Modifier.testTag("chatlist_more")) {
                                                Icon(Icons.Filled.MoreVert, stringResource(R.string.chatlist_more_actions))
                                            }
                                            DropdownMenu(moreOpen, { moreOpen = false }) {
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.folders_manage)) },
                                                    onClick = {
                                                        moreOpen = false
                                                        onOpenManageFolders()
                                                    },
                                                    modifier = Modifier.testTag("chatlist_manage_folders"),
                                                )
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.folders_auto_group)) },
                                                    onClick = {
                                                        moreOpen = false
                                                        onOpenAutoGroup(state.selectedNetworkId)
                                                    },
                                                    modifier = Modifier.testTag("chatlist_auto_group"),
                                                )
                                                if (state.globalFeedEnabled) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.chatlist_feed)) },
                                                        onClick = {
                                                            moreOpen = false
                                                            onOpenFeed()
                                                        },
                                                        modifier = Modifier.testTag("chatlist_open_feed"),
                                                    )
                                                }
                                                DropdownMenuItem(
                                                    text = { Text(stringResource(R.string.chatlist_settings)) },
                                                    onClick = {
                                                        moreOpen = false
                                                        onOpenSettings()
                                                    },
                                                    modifier = Modifier.testTag("chatlist_open_settings"),
                                                )
                                            }
                                        }
                                    }

                                    ChatListTopBarMode.INVITATIONS, ChatListTopBarMode.ARCHIVE -> {}
                                }
                            }
                        }
                    },
                )
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = !selectionActive && !archiveMode && !invitationMode,
                    enter = scaleIn(initialScale = 0.85f, animationSpec = MotdMotion.softSpring) + fadeIn(MotdMotion.microFadeIn),
                    exit = scaleOut(targetScale = 0.85f, animationSpec = MotdMotion.softSpring) + fadeOut(MotdMotion.microFadeOut),
                ) {
                    FloatingActionButton(onClick = { showSheet = true }, modifier = Modifier.testTag("chatlist_new_conversation")) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.chatlist_new_conversation),
                        )
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ConnectionBanner(
                        states = state.connection,
                        networkName = { id -> state.networks.firstOrNull { it.id == id }?.name },
                    )

                    // Aggregate history-sync line, pinned above the list. Scoped views (archive,
                    // invitations) deliberately omit it: it reports on the whole app, not on them.
                    if (!archiveMode && !invitationMode) {
                        ChatListSyncHeader(chrome = syncChrome)
                    }

                    // Active-scope chip: keeps the filter discoverable/escapable without the drawer.
                    AnimatedVisibility(
                        visible = state.selectedNetworkId != null,
                        enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
                        exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
                    ) {
                        // The latched name keeps the chip's text through its exit animation.
                        ScopeChip(
                            name = lastScopeName,
                            onClear = { onSelectNetwork(null) },
                        )
                    }

                    val hasInvitationRoute = state.invitations.any(ChatListInvitation::actionable)
                    if (!invitationMode && !shouldRenderChatList(archiveMode, state.rows, state.archivedRows) && !hasInvitationRoute && !state.loading) {
                        val noNetworks = !archiveMode && state.networks.isEmpty()
                        EmptyState(
                            icon = if (archiveMode) Icons.Outlined.Archive else Icons.Outlined.Forum,
                            title =
                                stringResource(
                                    if (noNetworks) {
                                        R.string.chatlist_no_networks_title
                                    } else if (archiveMode) {
                                        R.string.chatlist_archived_empty_title
                                    } else if (state.selectedNetworkId != null) {
                                        R.string.chatlist_scoped_empty_title
                                    } else {
                                        R.string.chatlist_empty_title
                                    },
                                ),
                            message =
                                if (archiveMode) {
                                    null
                                } else if (noNetworks) {
                                    stringResource(R.string.chatlist_no_networks_message)
                                } else {
                                    stringResource(
                                        if (state.selectedNetworkId != null) {
                                            R.string.chatlist_scoped_empty_message
                                        } else {
                                            R.string.chatlist_empty_message
                                        },
                                    )
                                },
                            actionLabel = if (noNetworks) stringResource(R.string.drawer_add_network) else null,
                            onAction = if (noNetworks) onOpenAddNetwork else null,
                            // The list that has no rows is exactly what the ghost rows stand in for.
                            ghostRows = true,
                        )
                    } else {
                        ChatList(
                            rows = visibleRows,
                            archivedRows = state.archivedRows,
                            folders = state.folders,
                            invitations = state.invitations,
                            archiveMode = archiveMode,
                            invitationMode = invitationMode,
                            archiveRevealSignal = archiveRevealSignal,
                            onOpenArchive = { archiveMode = true },
                            onOpenInvitations = { invitationMode = true },
                            onAcceptInvitation = onAcceptInvitation,
                            onIgnoreInvitation = onIgnoreInvitation,
                            presence = state.queryPresence,
                            syncIndicators = syncIndicators,
                            friends = state.friends,
                            fools = state.fools,
                            multiNetwork = showNetworkChip,
                            onOpenBuffer = onOpenBuffer,
                            onSetPinned = onSetPinned,
                            onSetMuted = onSetMuted,
                            onSetArchived = ::setArchivedWithReveal,
                            onDeleteBuffers = onDeleteBuffers,
                            onSetFolderExpanded = onSetFolderExpanded,
                            onOpenFolderEditor = onOpenFolderEditor,
                            activeBufferId = selectedBufferId,
                            selectedIds = selectedIds.toSet(),
                            selectionActive = selectionActive,
                            onToggleSelection = { id -> selectedIds = toggleSelectedId(selectedIds, id) },
                            onStartSelection = { id -> selectedIds = addSelectedId(selectedIds, id) },
                            onRemoveSelection = { ids -> selectedIds = selectedIds.filterNot(ids::contains) },
                        )
                    }
                }
                AudioMiniPlayer(
                    state = audioPlaybackState,
                    onToggle = onAudioToggle,
                    onCancelLoading = onAudioCancelLoading,
                    onRetry = onAudioRetry,
                    onDismiss = onAudioDismiss,
                    onSeek = onAudioSeek,
                    onOpenOrigin = onOpenAudioOrigin,
                    onSpeed = onAudioSpeed,
                    includeNetwork = state.networks.size > 1,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }

    if (showFolderAssignment && selectedRows.isNotEmpty()) {
        val ids = selectedRows.map(ChatListRow::bufferId)
        FolderAssignmentSheet(
            folders = state.folders,
            onAssign = { folderId, result ->
                onAssignFolder(ids, folderId) { success ->
                    if (success) {
                        selectedIds = emptyList()
                        showFolderAssignment = false
                    }
                    result(success)
                }
            },
            onCreate = { name, icon, result ->
                onCreateFolderAndAssign(name, icon, ids) { success ->
                    if (success) {
                        selectedIds = emptyList()
                        showFolderAssignment = false
                    }
                    result(success)
                }
            },
            onDismiss = { showFolderAssignment = false },
        )
    }

    if (showSheet) {
        NewConversationSheet(
            networks = state.networks,
            preselectedNetworkId = state.selectedNetworkId,
            sheetState = sheetState,
            onDismiss = { showSheet = false },
            onJoinChannel = { networkId, channel, key ->
                onJoinChannel(networkId, channel, key)
                scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
            },
            onMessageUser = { networkId, nick ->
                onMessageUser(networkId, nick)
                scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
            },
            onBrowseChannels = { networkId ->
                onOpenChannelList(networkId)
                scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
            },
            onChooseProviderTarget = { networkId ->
                onChooseProviderTarget(networkId)
                scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
            },
        )
    }

    if (showMarkAllReadDialog) {
        val networkName = state.selectedNetworkName
        AlertDialog(
            onDismissRequest = { showMarkAllReadDialog = false },
            title = {
                Text(
                    if (networkName == null) {
                        stringResource(R.string.mark_all_read_dialog_title_all)
                    } else {
                        stringResource(R.string.mark_all_read_dialog_title_network, networkName)
                    },
                )
            },
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.mark_all_read_dialog_message,
                        state.scopedUnreadCount,
                        state.scopedUnreadCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMarkAllReadDialog = false
                        onMarkAllRead()
                    },
                    modifier = Modifier.testTag("drawer_mark_all_read_confirm"),
                ) {
                    Text(stringResource(R.string.mark_all_read_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMarkAllReadDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (confirmRemoval && selectedRows.isNotEmpty()) {
        val onConfirmRemoval = {
            onDeleteBuffers(selectedRows)
            confirmRemoval = false
            selectedIds = emptyList()
        }
        if (selectedRows.size == 1) {
            DeleteConfirmDialog(selectedRows.single(), onConfirmRemoval) { confirmRemoval = false }
        } else {
            MultiDeleteConfirmDialog(selectedRows, onConfirmRemoval) { confirmRemoval = false }
        }
    }
}

internal fun shouldOpenOnboarding(
    state: ChatListState,
    suppressed: Boolean = false,
): Boolean = !suppressed && !state.loading && state.networks.isEmpty() && !state.onboardingComplete

@Composable
private fun ScopeChip(
    name: String,
    onClear: () -> Unit,
) {
    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        FilterChip(
            selected = true,
            onClick = onClear,
            label = { Text(name) },
            trailingIcon = {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chatlist_scope_clear),
                )
            },
        )
    }
}

/** Signals that move the chat list between "reorders are watched" and "reorders are replayed". */
internal enum class ChatListPlacementSignal {
    /** The pane's own destination reached RESUMED: on screen, on top, and being drawn. */
    PaneResumed,

    /** The pane left RESUMED: navigated away from, backgrounded, or covered mid-transition. */
    PaneHidden,

    /** A frame was rendered while resumed, so whatever data was pending has already landed. */
    FrameRendered,
}

/**
 * Whether a chat-list row that changes position should spring there or simply appear there.
 *
 * Composition outlives visibility: the pane stays composed across Home/recents and across the
 * navigation transition that covers it, and `collectAsStateWithLifecycle` parks collection while
 * it is away. Every reorder accumulated in that window therefore arrives as a single catch-up
 * snapshot the moment collection restarts. Animating that snapshot replays minutes of unseen
 * activity as one shuffle, which is the stale reorder this gate exists to suppress.
 */
internal data class ChatListPlacementGate(
    val visible: Boolean = false,
    val rendered: Boolean = false,
) {
    /** Only a resumed pane that has already drawn its catch-up snapshot may animate placement. */
    val animatesPlacement: Boolean get() = visible && rendered
}

internal fun reduceChatListPlacement(
    gate: ChatListPlacementGate,
    signal: ChatListPlacementSignal,
): ChatListPlacementGate =
    when (signal) {
        // Re-entering visibility always re-arms from scratch, so the catch-up snapshot snaps again.
        ChatListPlacementSignal.PaneResumed -> {
            if (gate.visible) gate else ChatListPlacementGate(visible = true, rendered = false)
        }

        ChatListPlacementSignal.PaneHidden -> {
            ChatListPlacementGate(visible = false, rendered = false)
        }

        // Frames rendered while hidden prove nothing about what the user saw.
        ChatListPlacementSignal.FrameRendered -> {
            if (gate.visible) gate.copy(rendered = true) else gate
        }
    }

/**
 * Chat-list rows fade for inserts/removals and spring to their new position when activity
 * re-sorts them under a watching user. Keys are stable buffer ids, so a row springs even when the
 * move crosses a pinned/friends/recent/fools boundary. Reorders the user could not have seen are
 * applied without placement animation; see [ChatListPlacementGate].
 */
internal object ChatListItemMotion {
    val fadeInSpec: FiniteAnimationSpec<Float> = MotdMotion.microFadeIn
    val fadeOutSpec: FiniteAnimationSpec<Float> = MotdMotion.microFadeOut

    /** The spec every row-level `animateItem` reads; null means "move without animating". */
    fun placementSpec(gate: ChatListPlacementGate): FiniteAnimationSpec<IntOffset>? = if (gate.animatesPlacement) MotdMotion.rowPlacement else null

    /**
     * Tracks the pane's own destination lifecycle rather than treating "still composed" as
     * visible, mirroring the chat screen's foreground gate. Arming is deferred by two frame
     * boundaries: the first carries the restarted flow's catch-up snapshot through layout
     * unanimated, the second leaves a frame of slack for a snapshot that lands a beat later.
     * Without a frame clock (pane composed but never drawn) the gate simply stays closed, which
     * is the safe direction.
     */
    @Composable
    fun rememberPlacementSpec(): FiniteAnimationSpec<IntOffset>? {
        val lifecycleOwner = LocalLifecycleOwner.current
        var gate by remember(lifecycleOwner) { mutableStateOf(ChatListPlacementGate()) }
        DisposableEffect(lifecycleOwner) {
            val observer =
                LifecycleEventObserver { _, event ->
                    val signal =
                        when (event) {
                            Lifecycle.Event.ON_RESUME -> ChatListPlacementSignal.PaneResumed

                            Lifecycle.Event.ON_PAUSE,
                            Lifecycle.Event.ON_STOP,
                            Lifecycle.Event.ON_DESTROY,
                            -> ChatListPlacementSignal.PaneHidden

                            else -> null
                        }
                    if (signal != null) gate = reduceChatListPlacement(gate, signal)
                }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        LaunchedEffect(gate.visible) {
            if (!gate.visible) return@LaunchedEffect
            withFrameNanos { }
            withFrameNanos { }
            gate = reduceChatListPlacement(gate, ChatListPlacementSignal.FrameRendered)
        }
        return placementSpec(gate)
    }
}

@Composable
private fun ChatList(
    rows: List<ChatListRow>,
    archivedRows: List<ChatListRow>,
    folders: List<io.github.trevarj.motd.data.db.ChatFolderEntity>,
    invitations: List<ChatListInvitation>,
    archiveMode: Boolean,
    invitationMode: Boolean,
    archiveRevealSignal: Int,
    onOpenArchive: () -> Unit,
    onOpenInvitations: () -> Unit,
    onAcceptInvitation: (Long) -> Unit,
    onIgnoreInvitation: (Long) -> Unit,
    presence: Map<Long, io.github.trevarj.motd.service.PresenceState>,
    syncIndicators: Map<Long, ChatListSyncIndicator>,
    friends: Set<String>,
    fools: Set<String>,
    multiNetwork: Boolean,
    onOpenBuffer: (Long) -> Unit,
    onSetPinned: (Collection<Long>, Boolean) -> Unit,
    onSetMuted: (Collection<Long>, Boolean) -> Unit,
    onSetArchived: (Collection<Long>, Boolean) -> Unit,
    onDeleteBuffers: (Collection<ChatListRow>) -> Unit,
    onSetFolderExpanded: (Long, Boolean) -> Unit,
    onOpenFolderEditor: (Long) -> Unit,
    activeBufferId: Long?,
    selectedIds: Set<Long>,
    selectionActive: Boolean,
    onToggleSelection: (Long) -> Unit,
    onStartSelection: (Long) -> Unit,
    onRemoveSelection: (Collection<Long>) -> Unit,
) {
    // Pinned rows escape folders; non-empty folders follow in manual order, then legacy tiers.
    val presentation = presentChatFolders(rows, folders, friends, fools, activeBufferId)
    val sections = presentation.remaining
    // Fools section is collapsed by default; state is local to the screen (accepted).
    var foolsExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Resolved once so every row in every section shares one placement decision per frame.
    val placementSpec = ChatListItemMotion.rememberPlacementSpec()
    val scope = rememberCoroutineScope()
    val actionableInvitationCount = invitations.count(ChatListInvitation::actionable)
    // LazyColumn re-anchors to the first visible item's key when the dataset changes, so a row
    // promoted to the top would land above a viewport resting at the true top and stay hidden
    // until the user scrolls up to find it. Re-pin index 0 in the same remeasure that presents
    // the new order: the promoted row surfaces in place and the rows it displaced spring down
    // via their placement animation. Scroll state is peeked without observation because this is
    // a same-frame decision, not a recomposition dependency.
    val topItemKey = chatListTopItemKey(invitationMode, invitations, actionableInvitationCount, sections, presentation.folders, presentation.pinned)
    val topItemTracker = remember { ChatListTopItemTracker(topItemKey) }
    if (topItemTracker.key != topItemKey) {
        val repin =
            Snapshot.withoutReadObservation {
                shouldRepinChatListTop(
                    previousTopKey = topItemTracker.key,
                    topKey = topItemKey,
                    canScrollBackward = listState.canScrollBackward,
                    scrollInProgress = listState.isScrollInProgress,
                )
            }
        topItemTracker.key = topItemKey
        if (repin) listState.requestScrollToItem(0)
    }
    val hasActiveRows = rows.isNotEmpty() || actionableInvitationCount > 0
    val hasArchivedRows = archivedRows.isNotEmpty()
    val archiveFolderHeight = 56.dp
    val archiveFolderGeometry = ArchiveFolderPullGeometry(with(LocalDensity.current) { archiveFolderHeight.toPx() })
    val archiveFolderPullEligible = !archiveMode && !invitationMode && hasActiveRows && hasArchivedRows
    val archivedOnly = !archiveMode && !invitationMode && !hasActiveRows && hasArchivedRows
    var archivePullState by remember { mutableStateOf(ArchiveFolderPullState()) }
    var archiveDisplayExposurePx by remember { mutableFloatStateOf(0f) }
    var archiveSettling by remember { mutableStateOf(false) }
    var archiveSettleJob by remember { mutableStateOf<Job?>(null) }
    var archiveAnnouncement by remember { mutableStateOf<String?>(null) }
    var handledArchiveRevealSignal by remember { mutableStateOf(archiveRevealSignal) }
    val view = LocalView.current
    val archivedRevealedAnnouncement = stringResource(R.string.chatlist_archived_revealed_announcement)
    val archivedHiddenAnnouncement = stringResource(R.string.chatlist_archived_hidden_announcement)

    fun dispatchArchiveEvent(event: ArchiveFolderPullEvent): ArchiveFolderPullResult {
        val result = reduceArchiveFolderPull(archivePullState, event, archiveFolderGeometry)
        archivePullState = result.state
        result.effects.forEach { effect ->
            when (effect) {
                ArchiveFolderPullEffect.HapticThresholdActivated -> view.performArchiveThresholdHaptic()
                ArchiveFolderPullEffect.AnnounceShown -> archiveAnnouncement = archivedRevealedAnnouncement
                ArchiveFolderPullEffect.AnnounceHidden -> archiveAnnouncement = archivedHiddenAnnouncement
            }
        }
        return result
    }

    fun settleArchivePull(targetPx: Float) {
        archiveSettleJob?.cancel()
        if (!archiveFolderGeometry.isValid || targetPx == archiveDisplayExposurePx) {
            archiveDisplayExposurePx = targetPx
            archiveSettling = false
            return
        }
        archiveSettling = true
        val remaining = abs(targetPx - archiveDisplayExposurePx) / archiveFolderGeometry.rowPx
        archiveSettleJob =
            scope.launch {
                animate(
                    initialValue = archiveDisplayExposurePx,
                    targetValue = targetPx,
                    animationSpec = MotdMotion.archiveSettleSpec(remaining),
                ) { value, _ ->
                    archiveDisplayExposurePx = value
                }
                archiveDisplayExposurePx = targetPx
                archiveSettling = false
                archiveSettleJob = null
            }
    }

    fun applyArchiveFolderPull(
        deltaY: Float,
        atTop: Boolean,
    ): Float {
        archiveSettleJob?.cancel()
        archiveSettleJob = null
        archiveSettling = false
        val result =
            dispatchArchiveEvent(
                ArchiveFolderPullEvent.DragDelta(deltaY, SystemClock.uptimeMillis(), ArchiveFolderPullSource.USER_INPUT, atTop),
            )
        archiveDisplayExposurePx = result.state.exposurePx
        return result.consumedY
    }

    LaunchedEffect(archiveFolderPullEligible) {
        if (!archiveFolderPullEligible) {
            archiveSettleJob?.cancel()
            archiveSettleJob = null
            archiveSettling = false
            archiveDisplayExposurePx = 0f
            dispatchArchiveEvent(ArchiveFolderPullEvent.Reset)
        }
    }

    LaunchedEffect(archivePullState.gestureActive, archivePullState.phase, archivePullState.dwellStartedAtMs, archivePullState.exposurePx) {
        val dwellStart = archivePullState.dwellStartedAtMs
        if (archivePullState.gestureActive && archivePullState.phase != ArchiveFolderPullPhase.ARMED && dwellStart != null) {
            delay((ArchiveFolderPull.DwellMillis - (SystemClock.uptimeMillis() - dwellStart)).coerceAtLeast(0L))
            val result = dispatchArchiveEvent(ArchiveFolderPullEvent.Tick(SystemClock.uptimeMillis()))
            archiveDisplayExposurePx = result.state.exposurePx
        }
    }

    DisposableEffect(Unit) { onDispose { archiveSettleJob?.cancel() } }

    LaunchedEffect(archiveRevealSignal, archiveFolderPullEligible, archivedOnly) {
        if (archiveRevealSignal == handledArchiveRevealSignal || archiveMode) return@LaunchedEffect
        if (archivedOnly) {
            handledArchiveRevealSignal = archiveRevealSignal
            return@LaunchedEffect
        }
        if (!archiveFolderPullEligible) return@LaunchedEffect
        val result = dispatchArchiveEvent(ArchiveFolderPullEvent.RevealAccessibilityAction)
        archiveDisplayExposurePx = result.state.exposurePx
        handledArchiveRevealSignal = archiveRevealSignal
    }

    val archiveFolderPullConnection =
        remember(archiveFolderPullEligible, archiveSettling) {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput || !archiveFolderPullEligible || archiveSettling) return Offset.Zero
                    if (available.y < 0f && archivePullState.phase == ArchiveFolderPullPhase.REVEALED) {
                        val result =
                            scrollRevealedArchiveFolder(
                                archiveDisplayExposurePx,
                                available.y,
                                archiveFolderGeometry,
                            )
                        archiveDisplayExposurePx = result.exposurePx
                        if (result.hidden) dispatchArchiveEvent(ArchiveFolderPullEvent.RevealedRowHidden)
                        return Offset(0f, result.consumedY)
                    }
                    return if (available.y < 0f && archivePullState.gestureActive && archiveDisplayExposurePx > 0f) {
                        Offset(0f, applyArchiveFolderPull(available.y, atTop = true))
                    } else {
                        Offset.Zero
                    }
                }

                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source != NestedScrollSource.UserInput || !archiveFolderPullEligible || archiveSettling) return Offset.Zero
                    if (available.y > 0f && !listState.canScrollBackward &&
                        archivePullState.phase == ArchiveFolderPullPhase.REVEALED
                    ) {
                        val result =
                            scrollRevealedArchiveFolder(
                                archiveDisplayExposurePx,
                                available.y,
                                archiveFolderGeometry,
                            )
                        archiveDisplayExposurePx = result.exposurePx
                        return Offset(0f, result.consumedY)
                    }
                    return if (available.y > 0f && !listState.canScrollBackward && archivePullState.gestureActive) {
                        Offset(0f, applyArchiveFolderPull(available.y, atTop = true))
                    } else {
                        Offset.Zero
                    }
                }

                override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity,
                ): Velocity = Velocity.Zero
            }
        }
    val currentDispatchArchiveEvent by rememberUpdatedState(::dispatchArchiveEvent)
    val archiveFolderRevealed = archivePullState.phase == ArchiveFolderPullPhase.REVEALED
    val revealArchiveActionLabel = stringResource(R.string.chatlist_archived_reveal_action)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .nestedScroll(archiveFolderPullConnection)
                .pointerInput(archiveFolderPullEligible, archiveFolderRevealed) {
                    // Once revealed, leave taps to the folder and use nested scroll to hide it.
                    if (!archiveFolderPullEligible || archiveFolderRevealed) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        archiveSettleJob?.cancel()
                        archiveSettleJob = null
                        archiveSettling = false
                        currentDispatchArchiveEvent(ArchiveFolderPullEvent.StartGesture(SystemClock.uptimeMillis()))
                        // Observe release after children without treating LazyColumn drag consumption as
                        // cancellation. The observer never consumes input from scrolling or row taps.
                        var pointerEvent = awaitPointerEvent(PointerEventPass.Final)
                        while (pointerEvent.changes.any { it.pressed }) {
                            pointerEvent = awaitPointerEvent(PointerEventPass.Final)
                        }
                        val event =
                            if (pointerEvent.type == PointerEventType.Release) {
                                ArchiveFolderPullEvent.Release(SystemClock.uptimeMillis())
                            } else {
                                ArchiveFolderPullEvent.Cancel
                            }
                        val result = currentDispatchArchiveEvent(event)
                        settleArchivePull(archiveFolderPullSettleTarget(result.state, archiveFolderGeometry))
                    }
                }.semantics {
                    if (archiveFolderPullEligible && archivePullState.phase != ArchiveFolderPullPhase.REVEALED) {
                        customActions =
                            listOf(
                                CustomAccessibilityAction(revealArchiveActionLabel) {
                                    val result = dispatchArchiveEvent(ArchiveFolderPullEvent.RevealAccessibilityAction)
                                    archiveDisplayExposurePx = result.state.exposurePx
                                    true
                                },
                            )
                    }
                }.testTag("chatlist_archive_pull_target"),
    ) {
        ArchiveAccessibilityAnnouncement(archiveAnnouncement)

        if (archivedOnly) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(archiveFolderHeight),
            ) {
                ArchivedChatsFolder(archivedRows.size, onOpenArchive)
            }
        }

        LazyColumn(
            state = listState,
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY =
                            when {
                                archivedOnly -> archiveFolderGeometry.rowPx
                                archiveFolderPullEligible -> archiveDisplayExposurePx
                                else -> 0f
                            }
                    },
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (invitationMode) {
                if (invitations.isEmpty()) {
                    item(key = "invitations-empty") {
                        EmptyState(
                            icon = Icons.Outlined.Mail,
                            title = stringResource(R.string.chatlist_invitations_empty),
                            message = null,
                        )
                    }
                } else {
                    items(invitations, key = { "invitation-${it.messageId}" }) { invitation ->
                        InvitationListItem(
                            invitation = invitation,
                            onJoin = { onAcceptInvitation(invitation.messageId) },
                            onIgnore = { onIgnoreInvitation(invitation.messageId) },
                            modifier =
                                Modifier.animateItem(
                                    fadeInSpec = ChatListItemMotion.fadeInSpec,
                                    fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                    placementSpec = placementSpec,
                                ),
                        )
                    }
                }
            } else {
                if (actionableInvitationCount > 0) {
                    item(key = "invitations-folder") {
                        // Box wrapper (as for fools rows below) so the header items animate with
                        // their neighbor rows without threading a Modifier into each composable.
                        Box(
                            modifier =
                                Modifier.animateItem(
                                    fadeInSpec = ChatListItemMotion.fadeInSpec,
                                    fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                    placementSpec = placementSpec,
                                ),
                        ) {
                            InvitationsFolder(
                                count = invitations.size,
                                actionableCount = actionableInvitationCount,
                                onOpen = onOpenInvitations,
                            )
                        }
                    }
                }
                items(presentation.pinned, key = { it.bufferId }) { row ->
                    SelectableChatListRow(
                        row,
                        presence[row.bufferId],
                        isFriend = isFriendQuery(row, friends),
                        multiNetwork,
                        onOpenBuffer,
                        archiveMode,
                        selected = row.bufferId in selectedIds,
                        active = row.bufferId == activeBufferId,
                        selectionActive = selectionActive,
                        onToggleSelection = onToggleSelection,
                        onStartSelection = onStartSelection,
                        onArchive = { onSetArchived(listOf(row.bufferId), !archiveMode) },
                        syncIndicator = syncIndicators[row.bufferId] ?: ChatListSyncIndicator.NONE,
                        modifier =
                            Modifier.animateItem(
                                fadeInSpec = ChatListItemMotion.fadeInSpec,
                                fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                placementSpec = placementSpec,
                            ),
                    )
                }
                presentation.folders.forEach { folder ->
                    item(key = "folder-${folder.folder.id}") {
                        ChatFolderHeader(
                            folder = folder,
                            onToggle = {
                                if (folder.expanded) onRemoveSelection(folder.children.map(ChatListRow::bufferId))
                                onSetFolderExpanded(folder.folder.id, !folder.folder.expanded)
                            },
                            onEdit = { onOpenFolderEditor(folder.folder.id) },
                            modifier =
                                Modifier.animateItem(
                                    fadeInSpec = ChatListItemMotion.fadeInSpec,
                                    fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                    placementSpec = placementSpec,
                                ),
                        )
                    }
                    if (folder.expanded) {
                        items(folder.children, key = { it.bufferId }) { row ->
                            SelectableChatListRow(
                                row,
                                presence[row.bufferId],
                                isFriend = isFriendQuery(row, friends),
                                multiNetwork,
                                onOpenBuffer,
                                archiveMode,
                                selected = row.bufferId in selectedIds,
                                active = row.bufferId == activeBufferId,
                                selectionActive = selectionActive,
                                onToggleSelection = onToggleSelection,
                                onStartSelection = onStartSelection,
                                onArchive = { onSetArchived(listOf(row.bufferId), !archiveMode) },
                                syncIndicator = syncIndicators[row.bufferId] ?: ChatListSyncIndicator.NONE,
                                modifier =
                                    Modifier.animateItem(
                                        fadeInSpec = ChatListItemMotion.fadeInSpec,
                                        fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                        placementSpec = placementSpec,
                                    ),
                            )
                        }
                    }
                }
                if (sections.friends.isNotEmpty()) {
                    item(key = "friends-header") {
                        Box(
                            modifier =
                                Modifier.animateItem(
                                    fadeInSpec = ChatListItemMotion.fadeInSpec,
                                    fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                    placementSpec = placementSpec,
                                ),
                        ) {
                            SectionHeader(stringResource(R.string.chatlist_friends))
                        }
                    }
                    items(sections.friends, key = { it.bufferId }) { row ->
                        SelectableChatListRow(
                            row,
                            presence[row.bufferId],
                            isFriend = true,
                            multiNetwork,
                            onOpenBuffer,
                            archiveMode,
                            selected = row.bufferId in selectedIds,
                            active = row.bufferId == activeBufferId,
                            selectionActive = selectionActive,
                            onToggleSelection = onToggleSelection,
                            onStartSelection = onStartSelection,
                            onArchive = { onSetArchived(listOf(row.bufferId), !archiveMode) },
                            syncIndicator = syncIndicators[row.bufferId] ?: ChatListSyncIndicator.NONE,
                            modifier =
                                Modifier.animateItem(
                                    fadeInSpec = ChatListItemMotion.fadeInSpec,
                                    fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                    placementSpec = placementSpec,
                                ),
                        )
                    }
                }
                if (sections.showRecentHeader) {
                    item(key = "recent-header") {
                        Box(
                            modifier =
                                Modifier.animateItem(
                                    fadeInSpec = ChatListItemMotion.fadeInSpec,
                                    fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                    placementSpec = placementSpec,
                                ),
                        ) {
                            SectionHeader(stringResource(R.string.chatlist_recent))
                        }
                    }
                }
                items(sections.regular, key = { it.bufferId }) { row ->
                    SelectableChatListRow(
                        row,
                        presence[row.bufferId],
                        isFriend = false,
                        multiNetwork,
                        onOpenBuffer,
                        archiveMode,
                        selected = row.bufferId in selectedIds,
                        active = row.bufferId == activeBufferId,
                        selectionActive = selectionActive,
                        onToggleSelection = onToggleSelection,
                        onStartSelection = onStartSelection,
                        onArchive = { onSetArchived(listOf(row.bufferId), !archiveMode) },
                        syncIndicator = syncIndicators[row.bufferId] ?: ChatListSyncIndicator.NONE,
                        modifier =
                            Modifier.animateItem(
                                fadeInSpec = ChatListItemMotion.fadeInSpec,
                                fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                placementSpec = placementSpec,
                            ),
                    )
                }
                if (sections.fools.isNotEmpty()) {
                    item(key = "fools-header") {
                        Box(
                            modifier =
                                Modifier.animateItem(
                                    fadeInSpec = ChatListItemMotion.fadeInSpec,
                                    fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                    placementSpec = placementSpec,
                                ),
                        ) {
                            FoolsSectionHeader(
                                count = sections.fools.size,
                                expanded = foolsExpanded,
                                onToggle = {
                                    if (foolsExpanded) onRemoveSelection(sections.fools.map(ChatListRow::bufferId))
                                    foolsExpanded = !foolsExpanded
                                },
                            )
                        }
                    }
                    if (foolsExpanded) {
                        items(sections.fools, key = { it.bufferId }) { row ->
                            Box(
                                modifier =
                                    Modifier
                                        .animateItem(
                                            fadeInSpec = ChatListItemMotion.fadeInSpec,
                                            fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                            placementSpec = placementSpec,
                                        ),
                            ) {
                                SelectableChatListRow(
                                    row = row,
                                    presence = presence[row.bufferId],
                                    isFriend = false,
                                    multiNetwork = multiNetwork,
                                    onOpenBuffer = onOpenBuffer,
                                    archiveMode = archiveMode,
                                    selected = row.bufferId in selectedIds,
                                    active = row.bufferId == activeBufferId,
                                    selectionActive = selectionActive,
                                    onToggleSelection = onToggleSelection,
                                    onStartSelection = onStartSelection,
                                    onArchive = { onSetArchived(listOf(row.bufferId), !archiveMode) },
                                    syncIndicator = syncIndicators[row.bufferId] ?: ChatListSyncIndicator.NONE,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (archiveFolderPullEligible && archiveDisplayExposurePx > 0f) {
            val overlayModifier =
                Modifier
                    .fillMaxWidth()
                    .height(archiveFolderHeight)
                    .graphicsLayer { translationY = archiveDisplayExposurePx - archiveFolderGeometry.rowPx }
            ArchiveFolderPullOverlay(
                phase = archivePullState.phase,
                exposurePx = archiveDisplayExposurePx,
                geometry = archiveFolderGeometry,
                archivedCount = archivedRows.size,
                onOpenArchive = onOpenArchive,
                modifier = overlayModifier,
            )
        }

        if (!selectionActive && !invitationMode) {
            ViewportScrollToTopFab(
                listState = listState,
                sections = sections,
                foolsExpanded = foolsExpanded,
                onClick = { scope.launch { listState.animateScrollToItem(0) } },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 88.dp),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChatFolderHeader(
    folder: PresentedChatFolder,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val expandLabel = stringResource(if (folder.expanded) R.string.folders_collapse else R.string.folders_expand)
    val editLabel = stringResource(R.string.folders_edit)
    val tint = remember(folder.folder.displayName) { Color.hsv((folderColorSeed(folder.folder.displayName).toUInt() % 360u).toFloat(), .55f, .72f) }
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = onEdit)
                .semantics {
                    stateDescription = expandLabel
                    customActions =
                        listOf(
                            CustomAccessibilityAction(expandLabel) {
                                onToggle()
                                true
                            },
                            CustomAccessibilityAction(editLabel) {
                                onEdit()
                                true
                            },
                        )
                }.testTag("chatlist_folder_${folder.folder.id}")
                .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderIcon(
            FolderIconRef(folder.folder.iconKind, folder.folder.iconKey),
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = tint,
        )
        Column(modifier = Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Row {
                Text(folder.folder.displayName, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(folder.summary.visibleCount.toString(), style = MaterialTheme.typography.labelMedium)
            }
            if (!folder.expanded) {
                val emphasized = folder.summary.unreadCount > 0 || folder.summary.mentionCount > 0
                Row(verticalAlignment = Alignment.CenterVertically) {
                    folder.summary.previewSender?.let { sender ->
                        SenderLabel(
                            sender = sender,
                            color = LocalNickColors.current.nick(sender, MaterialTheme.colorScheme.onSurfaceVariant),
                            unread = emphasized,
                            modifier = Modifier.testTag("chatlist_folder_preview_sender"),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        folder.summary.previewText.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    folder.summary.previewTime?.let { Text(relativeChatTime(it), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        if (!folder.expanded) {
            val badge = if (folder.summary.mentionCount > 0) folder.summary.mentionCount else folder.summary.unreadCount
            when {
                badge > 0 -> Badge { Text(if (badge > 999) "999+" else badge.toString()) }
                folder.summary.unreadIncomplete || folder.summary.mentionIncomplete -> Badge { Text("?") }
                folder.summary.advertisedActivity -> Text("•", color = tint)
            }
        }
        Icon(if (folder.expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.ExpandMore, contentDescription = null)
    }
}

@Composable
private fun InvitationsFolder(
    count: Int,
    actionableCount: Int,
    onOpen: () -> Unit,
) {
    val detail =
        pluralStringResource(
            R.plurals.chatlist_invitations_pending_count,
            actionableCount,
            actionableCount,
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .testTag("chatlist_invitations_folder")
                .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Mail, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
            Text(
                text = pluralStringResource(R.plurals.chatlist_invitations_count, count, count),
                fontWeight = FontWeight.Medium,
            )
            Text(text = detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InvitationListItem(
    invitation: ChatListInvitation,
    onJoin: () -> Unit,
    onIgnore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val resolved = !invitation.actionable
    val status =
        when (invitation.state) {
            InviteState.JOINING -> stringResource(R.string.chatlist_invitation_joining)
            InviteState.JOINED -> stringResource(R.string.chatlist_invitation_joined)
            InviteState.DISMISSED -> stringResource(R.string.chatlist_invitation_ignored)
            InviteState.FAILED -> stringResource(R.string.chatlist_invitation_failed)
            else -> stringResource(R.string.chatlist_invitation_pending)
        }
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .alpha(if (resolved) 0.56f else 1f)
                .semantics { stateDescription = status }
                .testTag("chatlist_invitation_${invitation.messageId}"),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.chatlist_invitation_title, invitation.channel),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.chatlist_invitation_from, invitation.inviter, invitation.networkName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (invitation.state == InviteState.FAILED) {
                Text(
                    text = stringResource(R.string.chatlist_invitation_failed_hint),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (invitation.actionable) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onJoin,
                        enabled = invitation.state != InviteState.JOINING,
                        modifier = Modifier.testTag("chatlist_invitation_join_${invitation.messageId}"),
                    ) {
                        Text(if (invitation.state == InviteState.JOINING) status else stringResource(R.string.chatlist_invitation_join))
                    }
                    OutlinedButton(
                        onClick = onIgnore,
                        modifier = Modifier.testTag("chatlist_invitation_ignore_${invitation.messageId}"),
                    ) {
                        Text(stringResource(R.string.chatlist_invitation_ignore))
                    }
                }
            } else {
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun ArchivedChatsFolder(
    count: Int,
    onOpenArchive: () -> Unit,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenArchive)
                .testTag("chatlist_archived_folder")
                .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Archive, contentDescription = null, tint = contentColor)
        Text(
            text = stringResource(R.string.chatlist_archived_chats_count, count),
            modifier = Modifier.padding(start = 16.dp),
            fontWeight = FontWeight.Medium,
            color = contentColor,
        )
    }
}

@Composable
private fun ArchiveFolderPullOverlay(
    phase: ArchiveFolderPullPhase,
    exposurePx: Float,
    geometry: ArchiveFolderPullGeometry,
    archivedCount: Int,
    onOpenArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val armed = phase == ArchiveFolderPullPhase.ARMED
    val committed = phase == ArchiveFolderPullPhase.REVEALED
    val activeProgress by animateFloatAsState(
        targetValue = if (armed) 1f else 0f,
        animationSpec = tween(durationMillis = 230, easing = FastOutLinearInEasing),
        label = "archive pull color",
    )
    val activeBackground = MaterialTheme.colorScheme.primaryContainer
    val pullBackground = MaterialTheme.colorScheme.background
    val inactiveContent = MaterialTheme.colorScheme.onSurface
    val activeContent = MaterialTheme.colorScheme.onPrimaryContainer
    val backgroundColor = lerp(pullBackground, activeBackground, activeProgress)
    val contentColor = lerp(inactiveContent, activeContent, activeProgress)
    val prompt =
        stringResource(
            if (armed) R.string.chatlist_archived_pull_armed else R.string.chatlist_archived_pull_hint,
        )
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = if (armed || committed) 1f else archiveFolderPullHintAlpha(exposurePx, geometry)
                }.background(backgroundColor),
    ) {
        if (committed) {
            // Keep rendering the same pull surface after release so the list never changes shape.
            ArchivedChatsFolder(
                count = archivedCount,
                onOpenArchive = onOpenArchive,
                contentColor = contentColor,
            )
        } else {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clearAndSetSemantics { stateDescription = prompt }
                        .testTag("chatlist_archived_pull_${phase.name.lowercase()}")
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(24.dp)
                            .background(contentColor.copy(alpha = .14f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = contentColor,
                        modifier =
                            Modifier
                                .size(18.dp)
                                .graphicsLayer { rotationZ = 180f * activeProgress },
                    )
                }
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chatlist_archived_pull_hint),
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                        modifier =
                            Modifier.graphicsLayer {
                                alpha = 1f - activeProgress
                                translationY = -8.dp.toPx() * activeProgress
                                scaleX = 1f - .1f * activeProgress
                                scaleY = scaleX
                            },
                    )
                    Text(
                        text = stringResource(R.string.chatlist_archived_pull_armed),
                        color = contentColor,
                        fontWeight = FontWeight.Medium,
                        modifier =
                            Modifier.graphicsLayer {
                                alpha = activeProgress
                                translationY = 8.dp.toPx() * (1f - activeProgress)
                                scaleX = .9f + .1f * activeProgress
                                scaleY = scaleX
                            },
                    )
                }
            }
        }
    }
}

/** Use the action-specific compat effect while honoring the user's touch-feedback preference. */
private fun View.performArchiveThresholdHaptic() {
    ViewCompat.performHapticFeedback(
        this,
        HapticFeedbackConstantsCompat.GESTURE_THRESHOLD_ACTIVATE,
    )
}

/** A stable semantic host lets accessibility services announce reducer-driven state changes. */
@Composable
internal fun ArchiveAccessibilityAnnouncement(message: String?) {
    if (message == null) return
    Text(
        text = message,
        color = Color.Transparent,
        modifier =
            Modifier
                .size(1.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("chatlist_archive_announcement"),
    )
}

@Composable
private fun ViewportScrollToTopFab(
    listState: androidx.compose.foundation.lazy.LazyListState,
    sections: ChatListSections,
    foolsExpanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canScrollToTop by remember(listState) {
        derivedStateOf { listState.canScrollBackward }
    }
    val firstVisibleItemIndex by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex }
    }
    val unreadAbove =
        remember(sections, foolsExpanded, firstVisibleItemIndex) {
            unreadActivityBeforeDisplayIndex(sections, foolsExpanded, firstVisibleItemIndex)
        }
    // The exit fade must not render the zero that triggered it; hold the last positive count for
    // the badge's outgoing frames, like the other latched exits in this file.
    var lastPositiveUnreadAbove by remember { mutableIntStateOf(0) }
    if (unreadAbove > 0) lastPositiveUnreadAbove = unreadAbove
    val description =
        if (unreadAbove > 0) {
            pluralStringResource(
                R.plurals.chatlist_scroll_to_top_with_unread,
                unreadAbove,
                unreadAbove,
            )
        } else {
            stringResource(R.string.chatlist_scroll_to_top)
        }

    AnimatedVisibility(
        visible = canScrollToTop,
        enter = fadeIn(MotdMotion.microFadeIn) + scaleIn(MotdMotion.microFadeIn, initialScale = 0.96f),
        exit = fadeOut(MotdMotion.microFadeOut) + scaleOut(MotdMotion.microFadeOut, targetScale = 0.96f),
        modifier = modifier,
    ) {
        BadgedBox(
            badge = {
                // Count is read inside the badge content so a tick updates the text in place;
                // only crossing zero runs the visibility transition.
                AnimatedVisibility(
                    visible = unreadAbove > 0,
                    enter = fadeIn(MotdMotion.microFadeIn) + scaleIn(MotdMotion.microFadeIn, initialScale = 0.96f),
                    exit = fadeOut(MotdMotion.microFadeOut) + scaleOut(MotdMotion.microFadeOut, targetScale = 0.96f),
                ) {
                    Badge {
                        Text(if (lastPositiveUnreadAbove > 99) "99+" else lastPositiveUnreadAbove.toString())
                    }
                }
            },
        ) {
            FloatingActionButton(
                onClick = onClick,
                modifier = Modifier.testTag("chatlist_scroll_to_top"),
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = description,
                )
            }
        }
    }
}

@Composable
private fun FoolsSectionHeader(
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.chatlist_fools, count).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        // One glyph rotated in place (as the archive pull overlay rotates its chevron), so the
        // expand/collapse toggle turns instead of swapping icons.
        val rotation by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            animationSpec = MotdMotion.softSpring,
            label = "fools_chevron",
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectableChatListRow(
    row: ChatListRow,
    presence: io.github.trevarj.motd.service.PresenceState?,
    isFriend: Boolean,
    multiNetwork: Boolean,
    onOpenBuffer: (Long) -> Unit,
    archiveMode: Boolean,
    selected: Boolean,
    active: Boolean,
    selectionActive: Boolean,
    onToggleSelection: (Long) -> Unit,
    onStartSelection: (Long) -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
    syncIndicator: ChatListSyncIndicator = ChatListSyncIndicator.NONE,
) {
    val currentArchive by rememberUpdatedState(onArchive)
    val dismissState = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = !selectionActive,
        onDismiss = { direction ->
            if (direction == SwipeToDismissBoxValue.EndToStart) {
                scope.launch {
                    // Lazy items may retain composition after moving between active/archive lists.
                    // Settle before moving the row so a reused state cannot fire the inverse action.
                    dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                    currentArchive()
                }
            }
        },
        backgroundContent = { ArchiveSwipeBackground(archiveMode) },
        modifier = modifier,
    ) {
        // Keep the normal foreground opaque so the archive affordance appears only during drag.
        Box(
            modifier =
                Modifier
                    .background(MaterialTheme.colorScheme.surface)
                    .testTag("chatlist_row_surface_${row.bufferId}"),
        ) {
            ChatListRowItem(
                row = row,
                showNetworkChip = multiNetwork,
                onClick = { if (selectionActive) onToggleSelection(row.bufferId) else onOpenBuffer(row.bufferId) },
                onLongClick = { onStartSelection(row.bufferId) },
                isFriend = isFriend,
                presence = presence,
                selected = selected,
                active = active,
                syncIndicator = syncIndicator,
            )
        }
    }
}

const val CHAT_LIST_TITLE_CONNECTING_TAG = "chatlist_title_connecting"

/**
 * The title's socket-establishment cue, in the chat title's `ChatTitleSyncSpinner` shape: a trailing 12 dp
 * ring that fades beside the title text, never over the list. The text keeps its position — the
 * cue only occupies trailing space, so appearing and settling shift no layout the reader is using.
 *
 * [visible] is the presenter-resolved value from [TitleConnectingPresenter], so this composable
 * carries no timing of its own — a sub-grace reconnect never reaches it. No liveRegion, matching
 * the chat title's spinner: reconnects recur, and announcing each would spam TalkBack; the
 * ConnectionBanner remains the announcing surface for connection trouble.
 */
@Composable
internal fun ChatListTitleConnectingSpinner(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(MotdMotion.microFadeIn),
        exit = fadeOut(MotdMotion.microFadeOut),
    ) {
        HistorySyncSpinner(
            contentDescription = stringResource(R.string.chatlist_title_connecting),
            // Padding inside the visibility scope so a settled connection leaves no gap.
            modifier =
                Modifier
                    .padding(start = 8.dp)
                    .testTag(CHAT_LIST_TITLE_CONNECTING_TAG),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatListTopBar(
    modifier: Modifier = Modifier,
    title: @Composable () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    val colors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    TopAppBar(
        modifier = modifier,
        title = title,
        navigationIcon = navigationIcon,
        actions = actions,
        colors = colors,
    )
}

/** End-to-start archive action uses a neutral archive container, never destructive styling. */
@Composable
private fun ArchiveSwipeBackground(archiveMode: Boolean) {
    Row(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = archiveActionIcon(archiveMode),
            contentDescription = stringResource(if (archiveMode) R.string.chatlist_unarchive else R.string.chatlist_archive),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Keep every archive affordance's glyph aligned with the action it will perform. */
internal fun archiveActionIcon(archiveMode: Boolean): ImageVector = if (archiveMode) Icons.Outlined.Unarchive else Icons.Outlined.Archive

/** Destructive-delete confirmation; channel copy mentions the implicit part/leave. */
@Composable
private fun DeleteConfirmDialog(
    row: ChatListRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = chatRemovalCopy(row.type)
    val message =
        if (copy.messageFormatsDisplayName) {
            stringResource(copy.message, row.displayName)
        } else {
            stringResource(copy.message)
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(copy.confirmTitle)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(copy.confirmAction),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Type totals retain the compatibility SERVER branch even though ordinary list queries hide it. */
internal data class ChatRemovalCounts(
    val channels: Int,
    val queries: Int,
    val servers: Int,
)

internal fun removalCounts(rows: Collection<ChatListRow>): ChatRemovalCounts =
    ChatRemovalCounts(
        channels = rows.count { it.type == BufferType.CHANNEL },
        queries = rows.count { it.type == BufferType.QUERY },
        servers = rows.count { it.type == BufferType.SERVER },
    )

@Composable
private fun MultiDeleteConfirmDialog(
    rows: List<ChatListRow>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val counts = removalCounts(rows)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.chatlist_remove_confirm_title, rows.size, rows.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (counts.channels > 0) Text(pluralStringResource(R.plurals.chatlist_remove_channels, counts.channels, counts.channels))
                if (counts.queries > 0) Text(pluralStringResource(R.plurals.chatlist_remove_queries, counts.queries, counts.queries))
                if (counts.servers > 0) Text(pluralStringResource(R.plurals.chatlist_remove_servers, counts.servers, counts.servers))
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.chatlist_remove), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

internal fun pruneSelectedIds(
    selectedIds: Collection<Long>,
    visibleRows: Collection<ChatListRow>,
): List<Long> {
    val visible = visibleRows.map(ChatListRow::bufferId).toSet()
    return selectedIds.distinct().filter(visible::contains)
}

internal fun orderedSelectedRows(
    rows: List<ChatListRow>,
    selectedIds: Collection<Long>,
): List<ChatListRow> {
    val selected = selectedIds.toSet()
    return rows.filter { it.bufferId in selected }
}

internal fun toggleSelectedId(
    selectedIds: Collection<Long>,
    id: Long,
): List<Long> = if (id in selectedIds) selectedIds.filterNot { it == id } else addSelectedId(selectedIds, id)

internal fun addSelectedId(
    selectedIds: Collection<Long>,
    id: Long,
): List<Long> = (selectedIds + id).distinct()

internal fun aggregateToggleTarget(
    rows: Collection<ChatListRow>,
    value: (ChatListRow) -> Boolean,
): Boolean = rows.isNotEmpty() && !rows.all(value)

internal data class ChatRemovalCopy(
    @get:StringRes val actionLabel: Int,
    @get:StringRes val confirmTitle: Int,
    @get:StringRes val message: Int,
    @get:StringRes val confirmAction: Int,
    val messageFormatsDisplayName: Boolean,
)

internal fun chatRemovalCopy(type: BufferType): ChatRemovalCopy =
    when (type) {
        BufferType.QUERY -> {
            ChatRemovalCopy(
                actionLabel = R.string.chatlist_forget,
                confirmTitle = R.string.chatlist_forget_confirm_title,
                message = R.string.chatlist_forget_confirm_message,
                confirmAction = R.string.chatlist_forget_action,
                messageFormatsDisplayName = false,
            )
        }

        BufferType.CHANNEL -> {
            ChatRemovalCopy(
                actionLabel = R.string.chatlist_delete,
                confirmTitle = R.string.chatlist_delete_confirm_title,
                message = R.string.chatlist_delete_confirm_channel,
                confirmAction = R.string.action_delete,
                messageFormatsDisplayName = true,
            )
        }

        BufferType.SERVER -> {
            ChatRemovalCopy(
                actionLabel = R.string.chatlist_delete,
                confirmTitle = R.string.chatlist_delete_confirm_title,
                message = R.string.chatlist_delete_confirm_message,
                confirmAction = R.string.action_delete,
                messageFormatsDisplayName = true,
            )
        }
    }

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        // Same container as the row wrappers so the list reads as one continuous surface.
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Preview
@Composable
private fun ChatListContentPreview() {
    MotdTheme {
        ChatListContent(
            state =
                ChatListState(
                    rows =
                        listOf(
                            ChatListRow(
                                bufferId = 1,
                                networkId = 1,
                                networkName = "Libera",
                                displayName = "#kotlin",
                                type = BufferType.CHANNEL,
                                pinned = true,
                                muted = false,
                                lastMessageText = "check out the new coroutines API",
                                lastMessageSender = "alice",
                                lastMessageTime = System.currentTimeMillis() - 60_000,
                                unreadCount = 5,
                                mentionCount = 1,
                            ),
                            ChatListRow(
                                bufferId = 2,
                                networkId = 1,
                                networkName = "Libera",
                                displayName = "#libera",
                                type = BufferType.CHANNEL,
                                pinned = false,
                                muted = true,
                                lastMessageText = "welcome!",
                                lastMessageSender = "bob",
                                lastMessageTime = System.currentTimeMillis() - 3_600_000,
                                unreadCount = 0,
                                mentionCount = 0,
                            ),
                            ChatListRow(
                                bufferId = 3,
                                networkId = 1,
                                networkName = "Libera",
                                displayName = "carol",
                                type = BufferType.QUERY,
                                pinned = false,
                                muted = false,
                                lastMessageText = "ping me when you're around",
                                lastMessageSender = "carol",
                                lastMessageTime = System.currentTimeMillis() - 86_400_000,
                                unreadCount = 2,
                                mentionCount = 0,
                            ),
                        ),
                    connection = mapOf(1L to IrcClientState.Connecting),
                    networks =
                        listOf(
                            NetworkEntity(
                                id = 1,
                                name = "Libera",
                                role = NetworkRole.DIRECT,
                                host = "irc.libera.chat",
                                port = 6697,
                                nick = "me",
                                username = "me",
                                realname = "Me",
                            ),
                        ),
                    loading = false,
                ),
            onOpenBuffer = {},
            onOpenSettings = {},
            onOpenSearch = {},
            onSetPinned = { _, _ -> },
            onSetMuted = { _, _ -> },
            onJoinChannel = { _, _, _ -> },
            onMessageUser = { _, _ -> },
        )
    }
}

@Preview
@Composable
private fun ChatListEmptyPreview() {
    MotdTheme {
        ChatListContent(
            state =
                ChatListState(
                    rows = emptyList(),
                    networks =
                        listOf(
                            NetworkEntity(
                                id = 1,
                                name = "Libera",
                                role = NetworkRole.DIRECT,
                                host = "irc.libera.chat",
                                port = 6697,
                                nick = "me",
                                username = "me",
                                realname = "Me",
                            ),
                        ),
                    loading = false,
                ),
            onOpenBuffer = {},
            onOpenSettings = {},
            onOpenSearch = {},
            onSetPinned = { _, _ -> },
            onSetMuted = { _, _ -> },
            onJoinChannel = { _, _, _ -> },
            onMessageUser = { _, _ -> },
        )
    }
}
