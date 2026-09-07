package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.github.trevarj.motd.R
import io.github.trevarj.motd.avatar.expandAvatarUrl
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.IrcNetworkBadge
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.MentionBadge
import io.github.trevarj.motd.ui.components.UnreadBadge
import io.github.trevarj.motd.ui.components.routedRemoteMediaData
import io.github.trevarj.motd.ui.theme.LocalAvatarStyle
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Server-drawer content. Stateless: takes the built [DrawerRow]s + rollups and
 * emits selection / connectivity / nav callbacks. Hosted by [ChatListScreen] inside a
 * `ModalNavigationDrawer`; previewable without a ViewModel.
 */
@Composable
fun ServerDrawerContent(
    drawerRows: List<DrawerRow>,
    selectedNetworkId: Long?,
    allUnread: Int,
    allMentions: Int,
    allUnreadIncomplete: Boolean = false,
    allMentionsIncomplete: Boolean = false,
    scopedUnreadCount: Int,
    allOffline: Boolean,
    onSelectNetwork: (Long?) -> Unit,
    onConnect: (Long) -> Unit,
    onDisconnect: (Long) -> Unit,
    onServerMessages: (Long) -> Unit,
    onOpenNetworkSettings: (Long) -> Unit,
    onAddNetwork: () -> Unit,
    onToggleOffline: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFeed: () -> Unit = {},
    /** Global Feed lab flag; the feed row exists only while the lab is on. */
    globalFeedEnabled: Boolean = false,
    onMarkAllRead: () -> Unit,
    onCreateContactInvite: (Long?) -> Unit = {},
    onScanInvite: () -> Unit = {},
    // Manual ordering. onMoveNetwork is one finished intent (persisted immediately);
    // onCommitNetworkOrder receives the arrangement a drag terminated on, exactly once per drag.
    onMoveNetwork: (Long, Int) -> Unit = { _, _ -> },
    onCommitNetworkOrder: (List<Long>) -> Unit = {},
) {
    // A drag lives entirely in this composable: nothing leaves it until the gesture terminates, so
    // no ViewModel round trip can reorder the list (and restart pointer input) under the finger.
    // Measured extent of each drawer entry, so a drag knows how far a swap actually moves it.
    val rowHeights = remember { mutableStateMapOf<Long, Int>() }
    var draggedNetworkId by remember { mutableStateOf<Long?>(null) }
    // The arrangement the drag started from; every placement is recomputed from it (idempotent in
    // the total travel), never stepped incrementally against a moving target.
    var dragStartRows by remember { mutableStateOf<List<DrawerRow>?>(null) }
    // The arrangement the drag is currently showing, as an id overlay for [applyDrawerOrder].
    var dragOrderIds by remember { mutableStateOf<List<Long>?>(null) }
    // Raw finger travel and the extent already swapped past. Read only inside graphicsLayer, so
    // per-pixel movement never recomposes the drawer; written in the same snapshot as dragOrderIds,
    // so an order change can never render a frame ahead of the translation compensating for it.
    var dragTotal by remember { mutableFloatStateOf(0f) }
    var dragPassedExtent by remember { mutableIntStateOf(0) }

    fun endDrag() {
        val committed = dragOrderIds
        draggedNetworkId = null
        dragStartRows = null
        dragOrderIds = null
        dragTotal = 0f
        dragPassedExtent = 0
        // Commit on any termination, drop or cancel alike: the rows the user is looking at have
        // already moved, so silently reverting them would be the surprising outcome.
        committed?.let(onCommitNetworkOrder)
    }

    // Leaving the screen mid-drag cancels the pointer stream without a cancel event, so flush any
    // arrangement the drag reached rather than letting it die with the composition.
    val latestCommit by rememberUpdatedState(onCommitNetworkOrder)
    val latestDragOrder by rememberUpdatedState(dragOrderIds)
    DisposableEffect(Unit) { onDispose { latestDragOrder?.let(latestCommit) } }

    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // Compact brand header: bubble mark plus the app name in the same plain bold platform
            // typography as the chat-list title bar, kept smaller than a navigation row so the
            // network list, rather than the branding, owns the drawer's visual hierarchy.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.motd_logo_mark),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(26.dp),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            // 1. The one cross-buffer destination, above the per-network rows it merges. Lab-gated:
            // hiding the row is what keeps the feed unreachable while the lab is off.
            if (globalFeedEnabled) {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.DynamicFeed, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_feed)) },
                    selected = false,
                    onClick = onOpenFeed,
                    modifier =
                        Modifier
                            .padding(horizontal = 12.dp)
                            .testTag("drawer_open_feed"),
                )
            }

            // 2. Networks section header. The unscoped ("all chats") state is simply "no network
            // selected" — reflected by the title-bar wordmark — so there is no standalone row for
            // it. A subtle clear-filter action appears only while scoped.
            NetworksHeader(
                totalUnread = allUnread,
                totalMentions = allMentions,
                unreadIncomplete = allUnreadIncomplete,
                mentionsIncomplete = allMentionsIncomplete,
                scoped = selectedNetworkId != null,
                onClearFilter = { onSelectNetwork(null) },
            )

            // 3. One entry per network (children indented under their soju root). While a drag is
            // live its local order overlays the published rows, so fresh unread/connection state
            // keeps flowing into rows the drag has already moved.
            val displayRows = dragOrderIds?.let { applyDrawerOrder(drawerRows, it) } ?: drawerRows
            val dragUnit = draggedNetworkId?.let { drawerDragUnit(displayRows, it) }.orEmpty()
            for (row in displayRows) {
                // Keyed identity: when a swap reorders this list, each row's node (including the
                // active pointer-input coroutine on its drag handle) moves with the row instead of
                // being positionally rebound to a different network — an unkeyed reorder restarts
                // pointerInput mid-gesture and strands the drag with no end/cancel callback.
                key(row.networkId) {
                    val dragging = row.networkId in dragUnit
                    DrawerNetworkItem(
                        row = row,
                        selected = selectedNetworkId == row.networkId,
                        dragging = dragging,
                        canMoveUp = canMoveDrawerRow(displayRows, row.networkId, -1),
                        canMoveDown = canMoveDrawerRow(displayRows, row.networkId, 1),
                        onSelect = { onSelectNetwork(row.networkId) },
                        onConnect = { onConnect(row.networkId) },
                        onDisconnect = { onDisconnect(row.networkId) },
                        onServerMessages = { onServerMessages(row.networkId) },
                        onOpenNetworkSettings = { onOpenNetworkSettings(row.networkId) },
                        onMove = { delta -> onMoveNetwork(row.networkId, delta) },
                        onDragStart = {
                            draggedNetworkId = row.networkId
                            dragStartRows = displayRows
                            dragOrderIds = drawerOrderIds(displayRows)
                            dragTotal = 0f
                            dragPassedExtent = 0
                        },
                        onDrag = { delta ->
                            val start = dragStartRows
                            if (start != null) {
                                dragTotal += delta
                                val placement =
                                    drawerDragPlacement(start, rowHeights, row.networkId, dragTotal)
                                dragPassedExtent = placement.passedExtent
                                val ids = drawerOrderIds(placement.rows)
                                if (ids != dragOrderIds) dragOrderIds = ids
                            }
                        },
                        onDragEnd = ::endDrag,
                        modifier =
                            Modifier
                                .onSizeChanged { rowHeights[row.networkId] = it.height }
                                .then(
                                    // The dragged entry (a soju root carries its children) follows the
                                    // finger and draws above the rows it is passing. Translation is
                                    // finger travel minus the extent the swaps already moved it.
                                    if (dragging) {
                                        Modifier.zIndex(1f).graphicsLayer {
                                            translationY = dragTotal - dragPassedExtent
                                        }
                                    } else {
                                        Modifier
                                    },
                                ),
                    )
                }
            }

            // Eased in/out so the divider and footer below never jump a full row height when the
            // scoped unread count crosses zero while the drawer is open.
            AnimatedVisibility(
                visible = scopedUnreadCount > 0,
                enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
                exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
            ) {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_mark_all_read)) },
                    selected = false,
                    onClick = onMarkAllRead,
                    modifier =
                        Modifier
                            .padding(horizontal = 12.dp)
                            .testTag("drawer_mark_all_read"),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 4. App-level footer actions.
            NavigationDrawerItem(
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                label = { Text(stringResource(R.string.drawer_add_network)) },
                selected = false,
                onClick = onAddNetwork,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Filled.QrCode2, contentDescription = null) },
                label = { Text(stringResource(R.string.contact_invite_create_title)) },
                selected = false,
                onClick = { onCreateContactInvite(selectedNetworkId) },
                modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer_create_contact_invite"),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                label = { Text(stringResource(R.string.invite_scan_title)) },
                selected = false,
                onClick = onScanInvite,
                modifier = Modifier.padding(horizontal = 12.dp).testTag("drawer_scan_invite"),
            )
            NavigationDrawerItem(
                icon = {
                    Icon(
                        if (allOffline) Icons.Outlined.Cloud else Icons.Outlined.CloudOff,
                        contentDescription = null,
                    )
                },
                label = {
                    Text(
                        stringResource(
                            if (allOffline) R.string.drawer_go_online else R.string.drawer_go_offline,
                        ),
                    )
                },
                selected = false,
                onClick = onToggleOffline,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                label = { Text(stringResource(R.string.drawer_settings)) },
                selected = false,
                onClick = onOpenSettings,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}

/**
 * "NETWORKS" section label with rolled-up unread/mention badges. While a network is scoped, a
 * subtle "Show all chats" text button clears the filter (there is nothing to clear when unscoped,
 * so it stays hidden — keeping the header uncluttered).
 */
@Composable
private fun NetworksHeader(
    totalUnread: Int,
    totalMentions: Int,
    unreadIncomplete: Boolean,
    mentionsIncomplete: Boolean,
    scoped: Boolean,
    onClearFilter: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.drawer_networks).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (scoped) {
            // Clear-scope affordance; only meaningful while filtered.
            TextButton(
                onClick = onClearFilter,
                modifier = Modifier.testTag("drawer_clear_filter"),
            ) {
                Text(stringResource(R.string.drawer_clear_filter))
            }
        } else {
            // Unscoped: surface the aggregate unread/mention rollup where "All chats" used to.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (totalMentions > 0) MentionBadge(totalMentions, lowerBound = mentionsIncomplete)
                if (totalUnread > 0) UnreadBadge(totalUnread, lowerBound = unreadIncomplete)
            }
        }
    }
}

@Composable
private fun DrawerNetworkItem(
    row: DrawerRow,
    selected: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onServerMessages: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    modifier: Modifier = Modifier,
    dragging: Boolean = false,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onMove: (Int) -> Unit = {},
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    val background =
        when {
            // Lifted while dragging so the entry reads as picked up rather than merely selected.
            dragging -> MaterialTheme.colorScheme.surfaceContainerHighest

            selected -> MaterialTheme.colorScheme.secondaryContainer

            else -> Color.Transparent
        }
    val moveUpLabel = stringResource(R.string.drawer_move_up)
    val moveDownLabel = stringResource(R.string.drawer_move_down)
    // The drag handle is decorative inside this merged row, so the move actions live on the row
    // itself: TalkBack reaches them from the network it is already focused on, and a user who
    // cannot hold and drag never needs the handle at all.
    val moveActions =
        buildList {
            if (canMoveUp) {
                add(
                    CustomAccessibilityAction(moveUpLabel) {
                        onMove(-1)
                        true
                    },
                )
            }
            if (canMoveDown) {
                add(
                    CustomAccessibilityAction(moveDownLabel) {
                        onMove(1)
                        true
                    },
                )
            }
        }

    Box(modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    // Selected row gets the M3 pill background.
                    .background(background, RoundedCornerShape(28.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier =
                    Modifier
                        .weight(1f)
                        // Per-network handle so the harness targets a specific drawer row.
                        .testTag("drawer_network_row_${row.networkId}")
                        // The drag handle sits outside this clickable area on purpose: pressing and
                        // holding it must not race the row's own long-press menu.
                        .combinedClickable(onClick = onSelect, onLongClick = { menuOpen = true })
                        .semantics { if (moveActions.isNotEmpty()) customActions = moveActions }
                        // Children indent one level under their soju root.
                        .padding(start = (16 + row.depth * 16).dp, top = 8.dp, bottom = 8.dp, end = 16.dp)
                        .heightIn(min = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (LocalAvatarStyle.current == AvatarStyle.IRC_SPRITE) {
                    val connected = row.state is IrcClientState.Ready
                    val statusDescription =
                        stringResource(
                            if (connected) {
                                R.string.drawer_state_connected
                            } else {
                                R.string.drawer_state_disconnected
                            },
                        )
                    val iconUrl = expandAvatarUrl(row.iconUrl.orEmpty(), 64)
                    val context = LocalContext.current
                    val automaticRemoteMedia = LocalAutomaticRemoteMedia.current
                    var iconLoaded by remember(iconUrl, row.networkId) { mutableStateOf(false) }
                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .testTag("drawer_network_icon_${row.networkId}")
                                .semantics { stateDescription = statusDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!iconLoaded) {
                            IrcNetworkBadge(
                                name = row.name,
                                networkId = row.networkId,
                                status =
                                    if (connected) {
                                        LocalMotdSemanticColors.current.success
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                size = 32.dp,
                            )
                        }
                        iconUrl?.let { url ->
                            val iconRequest =
                                remember(context, url, row.networkId, automaticRemoteMedia) {
                                    ImageRequest
                                        .Builder(context)
                                        .routedRemoteMediaData(url, row.networkId, automaticRemoteMedia)
                                        .build()
                                }
                            AsyncImage(
                                model = iconRequest,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                onLoading = { iconLoaded = false },
                                onSuccess = { iconLoaded = true },
                                onError = { iconLoaded = false },
                                modifier = Modifier.size(32.dp).clip(CircleShape),
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitleFor(row.state, row.nick),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (row.state is IrcClientState.Failed) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (row.mentions > 0) MentionBadge(row.mentions, lowerBound = row.mentionsIncomplete)
                if (row.unread > 0) UnreadBadge(row.unread, lowerBound = row.unreadIncomplete)
            }

            // A lone network, or a lone child under its root, has nowhere to go: no dead affordance.
            if (canMoveUp || canMoveDown) {
                // pointerInput's block never re-runs on recomposition, so it would keep invoking
                // the lambdas captured when it first ran; route through rememberUpdatedState so the
                // gesture always drives the current composition's handlers.
                val currentOnDragStart by rememberUpdatedState(onDragStart)
                val currentOnDrag by rememberUpdatedState(onDrag)
                val currentOnDragEnd by rememberUpdatedState(onDragEnd)
                Box(
                    modifier =
                        Modifier
                            .padding(end = 8.dp)
                            .size(40.dp)
                            .testTag("drawer_network_drag_handle_${row.networkId}")
                            .pointerInput(row.networkId) {
                                detectDragGestures(
                                    onDragStart = { currentOnDragStart() },
                                    onDragEnd = { currentOnDragEnd() },
                                    onDragCancel = { currentOnDragEnd() },
                                    onDrag = { change, amount ->
                                        // Consume so neither the drawer's scroll nor its swipe-to-close
                                        // can take the gesture away mid-reorder.
                                        change.consume()
                                        currentOnDrag(amount.y)
                                    },
                                )
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.DragHandle,
                        // Decorative: the row above carries the equivalent move actions.
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            // Visible, tappable alternative to dragging — no long hold, no fine motor control.
            if (canMoveUp) {
                DropdownMenuItem(
                    text = { Text(moveUpLabel) },
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                    onClick = {
                        onMove(-1)
                        menuOpen = false
                    },
                )
            }
            if (canMoveDown) {
                DropdownMenuItem(
                    text = { Text(moveDownLabel) },
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                    onClick = {
                        onMove(1)
                        menuOpen = false
                    },
                )
            }
            val live = row.state.let { it !is IrcClientState.Disconnected && it !is IrcClientState.Failed }
            if (live) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_disconnect)) },
                    leadingIcon = { Icon(Icons.Outlined.CloudOff, contentDescription = null) },
                    onClick = {
                        onDisconnect()
                        menuOpen = false
                    },
                )
            } else {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (row.state is IrcClientState.Failed) {
                                    R.string.drawer_reconnect
                                } else {
                                    R.string.drawer_connect
                                },
                            ),
                        )
                    },
                    leadingIcon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                    onClick = {
                        onConnect()
                        menuOpen = false
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_server_messages)) },
                leadingIcon = { Icon(Icons.Outlined.Terminal, contentDescription = null) },
                onClick = {
                    onServerMessages()
                    menuOpen = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_network_settings)) },
                leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                onClick = {
                    onOpenNetworkSettings()
                    menuOpen = false
                },
            )
        }
    }
}

@Composable
private fun subtitleFor(
    state: IrcClientState,
    nick: String?,
): String =
    when (state) {
        is IrcClientState.Ready -> nick ?: stringResource(R.string.drawer_state_registering)
        IrcClientState.Connecting -> stringResource(R.string.drawer_state_connecting)
        IrcClientState.Registering -> stringResource(R.string.drawer_state_registering)
        is IrcClientState.Failed -> state.reason
        IrcClientState.Disconnected -> stringResource(R.string.drawer_state_disconnected)
    }

@Preview
@Composable
private fun ServerDrawerPreview() {
    MotdTheme {
        ServerDrawerContent(
            drawerRows =
                listOf(
                    DrawerRow(
                        networkId = 1,
                        name = "Libera",
                        role = NetworkRole.DIRECT,
                        depth = 0,
                        state = IrcClientState.Ready("me", emptySet(), emptyMap()),
                        nick = "me",
                        unread = 5,
                        mentions = 1,
                    ),
                    DrawerRow(
                        networkId = 2,
                        name = "soju",
                        role = NetworkRole.BOUNCER_ROOT,
                        depth = 0,
                        state = IrcClientState.Connecting,
                        nick = null,
                        unread = 3,
                        mentions = 0,
                    ),
                    DrawerRow(
                        networkId = 3,
                        name = "OFTC",
                        role = NetworkRole.BOUNCER_CHILD,
                        depth = 1,
                        state = IrcClientState.Failed("SASL failed", fatal = true),
                        nick = null,
                        unread = 3,
                        mentions = 0,
                    ),
                ),
            selectedNetworkId = 1,
            allUnread = 8,
            allMentions = 1,
            allOffline = false,
            scopedUnreadCount = 8,
            onSelectNetwork = {},
            onConnect = {},
            onDisconnect = {},
            onServerMessages = {},
            onOpenNetworkSettings = {},
            onAddNetwork = {},
            onToggleOffline = {},
            onOpenSettings = {},
            onMarkAllRead = {},
        )
    }
}
