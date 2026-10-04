package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.ceramicLogoColorMatrix

/**
 * Server-drawer content. Takes the built [DrawerRow]s + rollups and emits selection /
 * connectivity / navigation callbacks. Only an active drag stays local.
 */
@Composable
fun ServerDrawerContent(
    drawerRows: List<DrawerRow>,
    selectedNetworkId: Long?,
    allMentions: Int,
    allMentionsIncomplete: Boolean = false,
    scopedUnreadCount: Int,
    allOffline: Boolean,
    onSelectNetwork: (Long?) -> Unit,
    onConnect: (Long) -> Unit,
    onDisconnect: (Long) -> Unit,
    onServerMessages: (Long) -> Unit,
    onOpenNetworkSettings: (Long) -> Unit,
    onOpenBouncerSettings: (Long) -> Unit = {},
    onAddNetwork: () -> Unit,
    onToggleOffline: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFeed: () -> Unit = {},
    onOpenMentions: () -> Unit = {},
    mentionsEnabled: Boolean = true,
    /** Global Feed lab flag; its shortcut exists only while the lab is on. */
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
        Column(modifier = Modifier.fillMaxHeight()) {
            // Compact brand header, kept smaller than a navigation row.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.motd_logo_mark),
                    contentDescription = null,
                    colorFilter =
                        ColorFilter.colorMatrix(
                            ColorMatrix(ceramicLogoColorMatrix(MaterialTheme.colorScheme.onSurface.toArgb())),
                        ),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(26.dp).testTag("drawer_logo_mark"),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                maxItemsInEachRow = 4,
            ) {
                if (mentionsEnabled) {
                    val label = stringResource(R.string.mentions_title)
                    DrawerActionTile(
                        icon = Icons.Outlined.AlternateEmail,
                        visibleLabel = label,
                        accessibilityLabel = label,
                        onClick = onOpenMentions,
                        modifier = Modifier.weight(1f).testTag("drawer_open_mentions"),
                    )
                }
                if (globalFeedEnabled) {
                    val label = stringResource(R.string.drawer_feed)
                    DrawerActionTile(
                        icon = Icons.Outlined.DynamicFeed,
                        visibleLabel = label,
                        accessibilityLabel = label,
                        onClick = onOpenFeed,
                        modifier = Modifier.weight(1f).testTag("drawer_open_feed"),
                    )
                }
                DrawerActionTile(
                    icon = Icons.Filled.QrCode2,
                    visibleLabel = stringResource(R.string.invite_share),
                    accessibilityLabel = stringResource(R.string.contact_invite_create_title),
                    onClick = { onCreateContactInvite(selectedNetworkId) },
                    modifier = Modifier.weight(1f).testTag("drawer_create_contact_invite"),
                )
                val scanLabel = stringResource(R.string.invite_scan_title)
                DrawerActionTile(
                    icon = Icons.Filled.QrCodeScanner,
                    visibleLabel = scanLabel,
                    accessibilityLabel = scanLabel,
                    onClick = onScanInvite,
                    modifier = Modifier.weight(1f).testTag("drawer_scan_invite"),
                )
                val settingsLabel = stringResource(R.string.drawer_settings)
                DrawerActionTile(
                    icon = Icons.Outlined.Settings,
                    visibleLabel = settingsLabel,
                    accessibilityLabel = settingsLabel,
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f).testTag("drawer_open_settings"),
                )
                // Weighted blanks keep a partial last row in quarter-width cells.
                val actionCount = 3 + (if (mentionsEnabled) 1 else 0) + (if (globalFeedEnabled) 1 else 0)
                val missingSlots = (4 - actionCount % 4) % 4
                repeat(missingSlots) { Spacer(Modifier.weight(1f)) }
            }

            NetworksHeader(
                totalMentions = allMentions,
                mentionsIncomplete = allMentionsIncomplete,
                scoped = selectedNetworkId != null,
                allOffline = allOffline,
                onToggleOffline = onToggleOffline,
                onAddNetwork = onAddNetwork,
                showMarkAllRead = scopedUnreadCount > 0,
                onMarkAllRead = onMarkAllRead,
                onClearFilter = { onSelectNetwork(null) },
            )

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                // A local drag overlay keeps the published row state flowing into reordered entries.
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
                            onOpenBouncerSettings = { onOpenBouncerSettings(row.networkId) },
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
            }
        }
    }
}

@Composable
private fun DrawerActionTile(
    icon: ImageVector,
    visibleLabel: String,
    accessibilityLabel: String,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    TextButton(
        onClick = onClick,
        modifier =
            modifier.heightIn(min = 48.dp).clearAndSetSemantics {
                contentDescription = accessibilityLabel
                role = Role.Button
                this.onClick {
                    onClick()
                    true
                }
            },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                text = visibleLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/**
 * Network actions sit beside the label; scoped filter clearing and rollups get
 * a second line only when needed, leaving room for all three 48dp controls.
 */
@Composable
private fun NetworksHeader(
    totalMentions: Int,
    mentionsIncomplete: Boolean,
    scoped: Boolean,
    allOffline: Boolean,
    onToggleOffline: () -> Unit,
    onAddNetwork: () -> Unit,
    showMarkAllRead: Boolean,
    onMarkAllRead: () -> Unit,
    onClearFilter: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 28.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.drawer_networks).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            val offlineLabel = stringResource(if (allOffline) R.string.drawer_go_online else R.string.drawer_go_offline)
            IconButton(
                onClick = onToggleOffline,
                modifier = Modifier.size(48.dp).testTag("drawer_toggle_offline").semantics { contentDescription = offlineLabel },
            ) {
                Icon(if (allOffline) Icons.Outlined.Cloud else Icons.Outlined.CloudOff, contentDescription = null)
            }
            val addLabel = stringResource(R.string.drawer_add_network)
            IconButton(
                onClick = onAddNetwork,
                modifier = Modifier.size(48.dp).testTag("drawer_add_network").semantics { contentDescription = addLabel },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
            if (showMarkAllRead) {
                val label = stringResource(R.string.drawer_mark_all_read)
                IconButton(
                    onClick = onMarkAllRead,
                    modifier = Modifier.size(48.dp).testTag("drawer_mark_all_read").semantics { contentDescription = label },
                ) {
                    Icon(Icons.Outlined.DoneAll, contentDescription = null)
                }
            }
        }
        if (scoped) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onClearFilter,
                    modifier = Modifier.testTag("drawer_clear_filter"),
                ) {
                    Text(stringResource(R.string.drawer_clear_filter))
                }
            }
        } else if (totalMentions > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MentionBadge(totalMentions, lowerBound = mentionsIncomplete)
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
    onOpenBouncerSettings: () -> Unit,
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
                    .clip(MotdShapes.composer)
                    .background(background),
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
                        .semantics {
                            this.selected = selected
                            if (moveActions.isNotEmpty()) customActions = moveActions
                        }
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
                                .size(40.dp)
                                .testTag("drawer_network_icon_${row.networkId}")
                                .semantics { stateDescription = statusDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!iconLoaded) {
                            if (row.role == NetworkRole.BOUNCER_ROOT || row.isZnc) {
                                Icon(
                                    imageVector = Icons.Outlined.Dns,
                                    contentDescription = stringResource(R.string.drawer_bouncer_icon),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier =
                                        Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                            .padding(8.dp),
                                )
                            } else {
                                IrcNetworkBadge(size = 40.dp)
                            }
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
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                            )
                        }
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.background)
                                    .testTag("drawer_network_status_${row.networkId}"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .then(
                                        if (connected) {
                                            Modifier.clip(CircleShape).background(LocalMotdSemanticColors.current.success)
                                        } else {
                                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                                        },
                                    ).clearAndSetSemantics {},
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
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
                if (row.mentions > 0 || row.unread > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (row.mentions > 0) MentionBadge(row.mentions, lowerBound = row.mentionsIncomplete)
                        if (row.unread > 0) UnreadBadge(row.unread, lowerBound = row.unreadIncomplete)
                    }
                }
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
                            .size(48.dp)
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
            if (row.role == NetworkRole.BOUNCER_ROOT) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_bouncer_settings)) },
                    leadingIcon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                    onClick = {
                        onOpenBouncerSettings()
                        menuOpen = false
                    },
                )
            }
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
