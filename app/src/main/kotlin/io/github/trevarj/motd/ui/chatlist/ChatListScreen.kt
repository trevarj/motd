package io.github.trevarj.motd.ui.chatlist

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.ConnectionBanner
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.MotdMotion
import kotlinx.coroutines.launch

/** Stateful entry: wires the ViewModel and drives navigation/empty-state. */
@Composable
fun ChatListScreen(
    onOpenBuffer: (Long) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenOnboarding: () -> Unit = {},
    // Round 5 (plans/16): drawer/network-management pass-throughs.
    onOpenNetworkSettings: (Long) -> Unit = {},
    onOpenAddNetwork: () -> Unit = {},
    onOpenChannelList: (Long) -> Unit = {},
    viewModel: ChatListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // No networks configured -> jump straight into onboarding (once loaded).
    LaunchedEffect(state.loading, state.networks.isEmpty()) {
        if (!state.loading && state.networks.isEmpty()) {
            onOpenOnboarding()
        }
    }

    ChatListContent(
        state = state,
        onOpenBuffer = onOpenBuffer,
        onOpenSettings = onOpenSettings,
        onOpenSearch = onOpenSearch,
        onSetPinned = viewModel::setPinned,
        onSetMuted = viewModel::setMuted,
        onDeleteBuffer = viewModel::deleteBuffer,
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
        onOpenChannelList = onOpenChannelList,
        onMarkAllRead = viewModel::markCurrentScopeRead,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListContent(
    state: ChatListState,
    onOpenBuffer: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onJoinChannel: (Long, String) -> Unit,
    onMessageUser: (Long, String) -> Unit,
    onDeleteBuffer: (ChatListRow) -> Unit = {},
    // Round 5 (plans/16 §3): drawer + scoping. Defaulted so previews stay terse.
    onSelectNetwork: (Long?) -> Unit = {},
    onConnect: (Long) -> Unit = {},
    onDisconnect: (Long) -> Unit = {},
    onGoOffline: () -> Unit = {},
    onGoOnline: () -> Unit = {},
    onServerMessages: (Long) -> Unit = {},
    onOpenNetworkSettings: (Long) -> Unit = {},
    onOpenAddNetwork: () -> Unit = {},
    onOpenChannelList: (Long) -> Unit = {},
    onMarkAllRead: () -> Unit = {},
) {
    var showSheet by remember { mutableStateOf(false) }
    var showMarkAllReadDialog by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    // The per-row network tag is redundant once the list is scoped to one network.
    val showNetworkChip = state.networks.size > 1 && state.selectedNetworkId == null

    // Close the drawer with Back when it's open (before popping the back stack).
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ServerDrawerContent(
                drawerRows = state.drawerRows,
                selectedNetworkId = state.selectedNetworkId,
                allUnread = state.allUnread,
                allMentions = state.allMentions,
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
                onToggleOffline = { if (state.allOffline) onGoOnline() else onGoOffline() },
                onOpenSettings = {
                    onOpenSettings()
                    scope.launch { drawerState.close() }
                },
                onMarkAllRead = {
                    scope.launch { drawerState.close() }
                    showMarkAllReadDialog = true
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.testTag("screen_chat_list"),
            topBar = {
                TopAppBar(
                    title = {
                        val scopedName = state.selectedNetworkName
                        if (scopedName != null) {
                            // Scoped: show the network name so the active filter is legible.
                            Text(text = scopedName, fontWeight = FontWeight.Bold)
                        } else {
                            // Unscoped: brand the bar with the /motd wordmark. Tinted onSurface so
                            // it reads on every theme (including terminal); never hardcode periwinkle.
                            Icon(
                                painter = painterResource(R.drawable.motd_wordmark),
                                contentDescription = stringResource(R.string.app_name),
                                tint = MaterialTheme.colorScheme.onSurface,
                                // Keep the thin stroked lettering crisp without crowding the app bar.
                                // 330:100 source ratio, rendered at an exact 3.25:1 ratio.
                                modifier = Modifier.height(24.dp).width(78.dp),
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.drawer_open),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.chatlist_search),
                            )
                        }
                        IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("chatlist_open_settings")) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.chatlist_settings),
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showSheet = true }, modifier = Modifier.testTag("chatlist_new_conversation")) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.chatlist_new_conversation),
                    )
                }
            },
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                ConnectionBanner(
                    states = state.connection,
                    networkName = { id -> state.networks.firstOrNull { it.id == id }?.name },
                )

                // Active-scope chip: keeps the filter discoverable/escapable without the drawer.
                if (state.selectedNetworkId != null) {
                    ScopeChip(
                        name = state.selectedNetworkName.orEmpty(),
                        onClear = { onSelectNetwork(null) },
                    )
                }

                if (state.rows.isEmpty() && !state.loading && state.networks.isNotEmpty()) {
                    EmptyState(
                        icon = Icons.Outlined.Forum,
                        title = stringResource(
                            if (state.selectedNetworkId != null) {
                                R.string.chatlist_scoped_empty_title
                            } else {
                                R.string.chatlist_empty_title
                            },
                        ),
                        message = stringResource(
                            if (state.selectedNetworkId != null) {
                                R.string.chatlist_scoped_empty_message
                            } else {
                                R.string.chatlist_empty_message
                            },
                        ),
                    )
                } else {
                    ChatList(
                        rows = state.rows,
                        presence = state.queryPresence,
                        friends = state.friends,
                        fools = state.fools,
                        multiNetwork = showNetworkChip,
                        onOpenBuffer = onOpenBuffer,
                        onSetPinned = onSetPinned,
                        onSetMuted = onSetMuted,
                        onDeleteBuffer = onDeleteBuffer,
                    )
                }
            }
        }
    }

    if (showSheet) {
        NewConversationSheet(
            networks = state.networks,
            preselectedNetworkId = state.selectedNetworkId,
            sheetState = sheetState,
            onDismiss = { showSheet = false },
            onJoinChannel = { networkId, channel ->
                onJoinChannel(networkId, channel)
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScopeChip(name: String, onClear: () -> Unit) {
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

/** Chat-list rows fade for inserts/removals but snap when activity changes their position. */
internal object ChatListItemMotion {
    val fadeInSpec: FiniteAnimationSpec<Float> = MotdMotion.microFadeIn
    val fadeOutSpec: FiniteAnimationSpec<Float> = MotdMotion.microFadeOut
    val placementSpec: FiniteAnimationSpec<IntOffset>? = null
}

@Composable
private fun ChatList(
    rows: List<ChatListRow>,
    presence: Map<Long, io.github.trevarj.motd.service.PresenceState>,
    friends: Set<String>,
    fools: Set<String>,
    multiNetwork: Boolean,
    onOpenBuffer: (Long) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onDeleteBuffer: (ChatListRow) -> Unit,
) {
    // Pinning has global priority, then unpinned friends, recent chats, and collapsed fools.
    // Pin state remains an inline row marker; it does not add a visible section header.
    val sections = sectionChatList(rows, friends, fools)
    // Fools section is collapsed by default; state is local to the screen (accepted, plans/13).
    var foolsExpanded by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            items(sections.pinned, key = { it.bufferId }) { row ->
                RowWithMenu(
                    row,
                    presence[row.bufferId],
                    isFriend = isFriendQuery(row, friends),
                    multiNetwork,
                    onOpenBuffer,
                    onSetPinned,
                    onSetMuted,
                    onDeleteBuffer,
                    modifier = Modifier.animateItem(
                        fadeInSpec = ChatListItemMotion.fadeInSpec,
                        fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                        placementSpec = ChatListItemMotion.placementSpec,
                    ),
                )
            }
            if (sections.friends.isNotEmpty()) {
                item(key = "friends-header") {
                    SectionHeader(stringResource(R.string.chatlist_friends))
                }
                items(sections.friends, key = { it.bufferId }) { row ->
                    RowWithMenu(
                        row,
                        presence[row.bufferId],
                        isFriend = true,
                        multiNetwork,
                        onOpenBuffer,
                        onSetPinned,
                        onSetMuted,
                        onDeleteBuffer,
                        modifier = Modifier.animateItem(
                            fadeInSpec = ChatListItemMotion.fadeInSpec,
                            fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                            placementSpec = ChatListItemMotion.placementSpec,
                        ),
                    )
                }
            }
            if (sections.showRecentHeader) {
                item(key = "recent-header") {
                    SectionHeader(stringResource(R.string.chatlist_recent))
                }
            }
            items(sections.regular, key = { it.bufferId }) { row ->
                RowWithMenu(
                    row,
                    presence[row.bufferId],
                    isFriend = false,
                    multiNetwork,
                    onOpenBuffer,
                    onSetPinned,
                    onSetMuted,
                    onDeleteBuffer,
                    modifier = Modifier.animateItem(
                        fadeInSpec = ChatListItemMotion.fadeInSpec,
                        fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                        placementSpec = ChatListItemMotion.placementSpec,
                    ),
                )
            }
            if (sections.fools.isNotEmpty()) {
                item(key = "fools-header") {
                    FoolsSectionHeader(
                        count = sections.fools.size,
                        expanded = foolsExpanded,
                        onToggle = { foolsExpanded = !foolsExpanded },
                    )
                }
                if (foolsExpanded) {
                    items(sections.fools, key = { it.bufferId }) { row ->
                        Box(
                            modifier = Modifier
                                .alpha(0.55f)
                                .animateItem(
                                    fadeInSpec = ChatListItemMotion.fadeInSpec,
                                    fadeOutSpec = ChatListItemMotion.fadeOutSpec,
                                    placementSpec = ChatListItemMotion.placementSpec,
                                ),
                        ) {
                            RowWithMenu(row, presence[row.bufferId], isFriend = false, multiNetwork, onOpenBuffer, onSetPinned, onSetMuted, onDeleteBuffer)
                        }
                    }
                }
            }
        }

        ViewportScrollToTopFab(
            listState = listState,
            sections = sections,
            foolsExpanded = foolsExpanded,
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 88.dp),
        )
    }
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
    val unreadAbove = remember(sections, foolsExpanded, firstVisibleItemIndex) {
        unreadActivityBeforeDisplayIndex(sections, foolsExpanded, firstVisibleItemIndex)
    }
    val description = if (unreadAbove > 0) {
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
        enter = scaleIn(),
        exit = scaleOut(),
        modifier = modifier,
    ) {
        BadgedBox(
            badge = {
                if (unreadAbove > 0) {
                    Badge { Text(if (unreadAbove > 99) "99+" else unreadAbove.toString()) }
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
private fun FoolsSectionHeader(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RowWithMenu(
    row: ChatListRow,
    presence: io.github.trevarj.motd.service.PresenceState?,
    isFriend: Boolean,
    multiNetwork: Boolean,
    onOpenBuffer: (Long) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onDeleteBuffer: (ChatListRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val removalCopy = chatRemovalCopy(row.type)

    // Swipe end-to-start reveals a destructive red background; releasing arms the confirm dialog
    // (deleting drops history + parts a joined channel, so we never delete silently). We do NOT
    // let the box settle into the dismissed state — confirmValueChange returns false so the row
    // snaps back and the dialog drives the actual delete.
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                confirmDelete = true
            }
            false
        },
    )

    Box(modifier = modifier) {
        SwipeToDismissBox(
            state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = { DeleteSwipeBackground(removalCopy.actionLabel) },
        ) {
        // Row background must be opaque so the swipe background stays hidden until swiped.
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            ChatListRowItem(
                row = row,
                showNetworkChip = multiNetwork,
                onClick = { onOpenBuffer(row.bufferId) },
                onLongClick = { menuOpen = true },
                isFriend = isFriend,
                presence = presence,
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (row.pinned) R.string.chatlist_unpin else R.string.chatlist_pin,
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (row.pinned) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onSetPinned(row.bufferId, !row.pinned)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (row.muted) R.string.chatlist_unmute else R.string.chatlist_mute,
                            ),
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (row.muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
                            contentDescription = null,
                        )
                    },
                    onClick = {
                        onSetMuted(row.bufferId, !row.muted)
                        menuOpen = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(removalCopy.actionLabel)) },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        confirmDelete = true
                    },
                )
            }
        }
    }

    if (confirmDelete) {
        DeleteConfirmDialog(
            row = row,
            onConfirm = {
                confirmDelete = false
                onDeleteBuffer(row)
            },
            onDismiss = { confirmDelete = false },
        )
        }
    }
}


/** Red end-aligned trash background revealed while swiping a chat-list row toward delete. */
@Composable
private fun DeleteSwipeBackground(@StringRes actionLabel: Int) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Delete,
            contentDescription = stringResource(actionLabel),
            tint = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/** Destructive-delete confirmation; channel copy mentions the implicit part/leave. */
@Composable
private fun DeleteConfirmDialog(
    row: ChatListRow,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val copy = chatRemovalCopy(row.type)
    val message = if (copy.messageFormatsDisplayName) {
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

internal data class ChatRemovalCopy(
    @get:StringRes val actionLabel: Int,
    @get:StringRes val confirmTitle: Int,
    @get:StringRes val message: Int,
    @get:StringRes val confirmAction: Int,
    val messageFormatsDisplayName: Boolean,
)

internal fun chatRemovalCopy(type: BufferType): ChatRemovalCopy = when (type) {
    BufferType.QUERY -> ChatRemovalCopy(
        actionLabel = R.string.chatlist_forget,
        confirmTitle = R.string.chatlist_forget_confirm_title,
        message = R.string.chatlist_forget_confirm_message,
        confirmAction = R.string.chatlist_forget_action,
        messageFormatsDisplayName = false,
    )
    BufferType.CHANNEL -> ChatRemovalCopy(
        actionLabel = R.string.chatlist_delete,
        confirmTitle = R.string.chatlist_delete_confirm_title,
        message = R.string.chatlist_delete_confirm_channel,
        confirmAction = R.string.action_delete,
        messageFormatsDisplayName = true,
    )
    BufferType.SERVER -> ChatRemovalCopy(
        actionLabel = R.string.chatlist_delete,
        confirmTitle = R.string.chatlist_delete_confirm_title,
        message = R.string.chatlist_delete_confirm_message,
        confirmAction = R.string.action_delete,
        messageFormatsDisplayName = true,
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Preview
@Composable
private fun ChatListContentPreview() {
    MotdTheme {
        ChatListContent(
            state = ChatListState(
                rows = listOf(
                    ChatListRow(
                        bufferId = 1, networkId = 1, networkName = "Libera",
                        displayName = "#kotlin", type = BufferType.CHANNEL,
                        pinned = true, muted = false,
                        lastMessageText = "check out the new coroutines API",
                        lastMessageSender = "alice",
                        lastMessageTime = System.currentTimeMillis() - 60_000,
                        unreadCount = 5, mentionCount = 1,
                    ),
                    ChatListRow(
                        bufferId = 2, networkId = 1, networkName = "Libera",
                        displayName = "#libera", type = BufferType.CHANNEL,
                        pinned = false, muted = true,
                        lastMessageText = "welcome!", lastMessageSender = "bob",
                        lastMessageTime = System.currentTimeMillis() - 3_600_000,
                        unreadCount = 0, mentionCount = 0,
                    ),
                    ChatListRow(
                        bufferId = 3, networkId = 1, networkName = "Libera",
                        displayName = "carol", type = BufferType.QUERY,
                        pinned = false, muted = false,
                        lastMessageText = "ping me when you're around",
                        lastMessageSender = "carol",
                        lastMessageTime = System.currentTimeMillis() - 86_400_000,
                        unreadCount = 2, mentionCount = 0,
                    ),
                ),
                connection = mapOf(1L to IrcClientState.Connecting),
                networks = listOf(
                    NetworkEntity(
                        id = 1, name = "Libera", role = NetworkRole.DIRECT,
                        host = "irc.libera.chat", port = 6697,
                        nick = "me", username = "me", realname = "Me",
                    ),
                ),
                loading = false,
            ),
            onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {},
            onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> },
            onJoinChannel = { _, _ -> }, onMessageUser = { _, _ -> },
        )
    }
}

@Preview
@Composable
private fun ChatListEmptyPreview() {
    MotdTheme {
        ChatListContent(
            state = ChatListState(
                rows = emptyList(),
                networks = listOf(
                    NetworkEntity(
                        id = 1, name = "Libera", role = NetworkRole.DIRECT,
                        host = "irc.libera.chat", port = 6697,
                        nick = "me", username = "me", realname = "Me",
                    ),
                ),
                loading = false,
            ),
            onOpenBuffer = {}, onOpenSettings = {}, onOpenSearch = {},
            onSetPinned = { _, _ -> }, onSetMuted = { _, _ -> },
            onJoinChannel = { _, _ -> }, onMessageUser = { _, _ -> },
        )
    }
}
