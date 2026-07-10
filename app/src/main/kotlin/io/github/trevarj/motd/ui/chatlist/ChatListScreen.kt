package io.github.trevarj.motd.ui.chatlist

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.CenterAlignedTopAppBar
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
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
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
    val state by viewModel.state.collectAsState()

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
) {
    var showSheet by remember { mutableStateOf(false) }
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
            )
        },
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            // Title shows the scoped network name, else the app name.
                            text = state.selectedNetworkName ?: stringResource(R.string.chatlist_title),
                            fontWeight = FontWeight.Bold,
                        )
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
                        IconButton(onClick = onOpenSettings) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = stringResource(R.string.chatlist_settings),
                            )
                        }
                    },
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showSheet = true }) {
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

@Composable
private fun ChatList(
    rows: List<ChatListRow>,
    friends: Set<String>,
    fools: Set<String>,
    multiNetwork: Boolean,
    onOpenBuffer: (Long) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onDeleteBuffer: (ChatListRow) -> Unit,
) {
    // Precedence: friend > fool > regular (plans/13 §3.5). Pinned rows are no longer a separate
    // section; they sort first within their section (query orders pinned-first) and carry an inline
    // pin icon on the row itself.
    val sections = sectionChatList(rows, friends, fools)
    // Fools section is collapsed by default; state is local to the screen (accepted, plans/13).
    var foolsExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        if (sections.friends.isNotEmpty()) {
            item(key = "friends-header") {
                SectionHeader(stringResource(R.string.chatlist_friends))
            }
            items(sections.friends, key = { "f-${it.bufferId}" }) { row ->
                RowWithMenu(row, isFriend = true, multiNetwork, onOpenBuffer, onSetPinned, onSetMuted, onDeleteBuffer)
            }
        }
        items(sections.regular, key = { it.bufferId }) { row ->
            RowWithMenu(row, isFriend = false, multiNetwork, onOpenBuffer, onSetPinned, onSetMuted, onDeleteBuffer)
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
                items(sections.fools, key = { "o-${it.bufferId}" }) { row ->
                    Box(modifier = Modifier.alpha(0.55f)) {
                        RowWithMenu(row, isFriend = false, multiNetwork, onOpenBuffer, onSetPinned, onSetMuted, onDeleteBuffer)
                    }
                }
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
    isFriend: Boolean,
    multiNetwork: Boolean,
    onOpenBuffer: (Long) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onDeleteBuffer: (ChatListRow) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

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

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = { DeleteSwipeBackground() },
    ) {
        // Row background must be opaque so the swipe background stays hidden until swiped.
        Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
            ChatListRowItem(
                row = row,
                showNetworkChip = multiNetwork,
                onClick = { onOpenBuffer(row.bufferId) },
                onLongClick = { menuOpen = true },
                isFriend = isFriend,
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
                    text = { Text(stringResource(R.string.chatlist_delete)) },
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

/** Red end-aligned trash background revealed while swiping a chat-list row toward delete. */
@Composable
private fun DeleteSwipeBackground() {
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
            contentDescription = stringResource(R.string.chatlist_delete),
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
    val message = if (row.type == BufferType.CHANNEL) {
        stringResource(R.string.chatlist_delete_confirm_channel, row.displayName)
    } else {
        stringResource(R.string.chatlist_delete_confirm_message, row.displayName)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chatlist_delete_confirm_title)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.action_delete),
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
