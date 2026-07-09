package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import io.github.trevarj.motd.data.prefs.normalizeNick
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
    // Round 5 (plans/16): drawer/network-management pass-throughs. Bodies land in WP-V1.
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
        onJoinChannel = viewModel::joinChannel,
        onMessageUser = { networkId, nick -> viewModel.messageUser(networkId, nick, onOpenBuffer) },
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
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val multiNetwork = state.networks.size > 1

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        stringResource(R.string.chatlist_title),
                        fontWeight = FontWeight.Bold,
                    )
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

            if (state.rows.isEmpty() && !state.loading && state.networks.isNotEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Forum,
                    title = stringResource(R.string.chatlist_empty_title),
                    message = stringResource(R.string.chatlist_empty_message),
                )
            } else {
                ChatList(
                    rows = state.rows,
                    friends = state.friends,
                    fools = state.fools,
                    multiNetwork = multiNetwork,
                    onOpenBuffer = onOpenBuffer,
                    onSetPinned = onSetPinned,
                    onSetMuted = onSetMuted,
                )
            }
        }
    }

    if (showSheet) {
        NewConversationSheet(
            networks = state.networks,
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
) {
    // Precedence: pinned > friend > fool > regular (plans/13 §3.5, Confirmed decision #6).
    val sections = sectionChatList(rows, friends, fools)
    // Fools section is collapsed by default; state is local to the screen (accepted, plans/13).
    var foolsExpanded by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 88.dp),
    ) {
        if (sections.pinned.isNotEmpty()) {
            item(key = "pinned-header") {
                SectionHeader(stringResource(R.string.chatlist_pinned))
            }
            items(sections.pinned, key = { "p-${it.bufferId}" }) { row ->
                // A pinned friend keeps its star even while under Pinned.
                RowWithMenu(row, isFriend(row, friends), multiNetwork, onOpenBuffer, onSetPinned, onSetMuted)
            }
        }
        if (sections.friends.isNotEmpty()) {
            item(key = "friends-header") {
                SectionHeader(stringResource(R.string.chatlist_friends))
            }
            items(sections.friends, key = { "f-${it.bufferId}" }) { row ->
                RowWithMenu(row, isFriend = true, multiNetwork, onOpenBuffer, onSetPinned, onSetMuted)
            }
        }
        items(sections.regular, key = { it.bufferId }) { row ->
            RowWithMenu(row, isFriend = false, multiNetwork, onOpenBuffer, onSetPinned, onSetMuted)
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
                        RowWithMenu(row, isFriend = false, multiNetwork, onOpenBuffer, onSetPinned, onSetMuted)
                    }
                }
            }
        }
    }
}

/** A non-pinned QUERY row whose nick is a friend (used for the star on pinned friend rows). */
private fun isFriend(row: ChatListRow, friends: Set<String>): Boolean =
    row.type == BufferType.QUERY && normalizeNick(row.displayName) in friends

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

@Composable
private fun RowWithMenu(
    row: ChatListRow,
    isFriend: Boolean,
    multiNetwork: Boolean,
    onOpenBuffer: (Long) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ChatListRowItem(
        row = row,
        showNetworkChip = multiNetwork,
        onClick = { onOpenBuffer(row.bufferId) },
        onLongClick = { menuOpen = true },
        isFriend = isFriend,
    )
    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        DropdownMenuItem(
            text = { Text(if (row.pinned) "Unpin" else "Pin") },
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
            text = { Text(if (row.muted) "Unmute" else "Mute") },
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
    }
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
