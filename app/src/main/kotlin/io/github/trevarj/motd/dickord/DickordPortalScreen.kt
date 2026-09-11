package io.github.trevarj.motd.dickord

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.ui.chatlist.ChatListRowItem
import io.github.trevarj.motd.ui.chatlist.chatListBadgeState
import io.github.trevarj.motd.ui.components.AdvertisedActivityDot
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.EmptyState
import io.github.trevarj.motd.ui.components.LocalRemoteAvatars
import io.github.trevarj.motd.ui.components.MentionBadge
import io.github.trevarj.motd.ui.components.MutedActivityBadge
import io.github.trevarj.motd.ui.components.RemoteAvatarState
import io.github.trevarj.motd.ui.components.UnreadBadge
import io.github.trevarj.motd.ui.theme.LocalAvatarStyle

private val noRemoteAvatars = RemoteAvatarState()

/** Stateful production entry; navigation supplies the root-scoped portal VM and raw-row actions. */
@Composable
internal fun DickordPortalScreen(
    viewModel: DickordPortalViewModel,
    selectedBufferId: Long? = null,
    onBack: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onConversationInfo: (Long) -> Unit,
    onMarkRead: (Long) -> Unit,
    onMarkAllRead: () -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetArchived: (Long, Boolean) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val refreshRequested = stringResource(R.string.dickord_portal_refresh_requested)
    val refreshUnavailable = stringResource(R.string.dickord_portal_refresh_unavailable)
    LaunchedEffect(viewModel, refreshRequested, refreshUnavailable) {
        viewModel.refreshResults.collect { requested ->
            snackbarHostState.showSnackbar(if (requested) refreshRequested else refreshUnavailable)
        }
    }
    DickordPortalContent(
        state = state,
        selectedBufferId = selectedBufferId,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onSelectGroup = viewModel::selectGroup,
        onShowArchived = viewModel::setShowArchived,
        onRefresh = viewModel::refresh,
        onOpenConversation = onOpenConversation,
        onConversationInfo = onConversationInfo,
        onMarkRead = onMarkRead,
        onMarkAllRead = onMarkAllRead,
        onSetMuted = onSetMuted,
        onSetPinned = onSetPinned,
        onSetArchived = onSetArchived,
    )
}

/** Stateless navigator used by both the navigator-only route and a portal-owned ChatRoute shell. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DickordPortalContent(
    state: DickordPortalState,
    selectedBufferId: Long? = null,
    snackbarHostState: SnackbarHostState? = null,
    onBack: () -> Unit = {},
    onSelectGroup: (String) -> Unit = {},
    onShowArchived: (Boolean) -> Unit = {},
    onRefresh: () -> Unit = {},
    onOpenConversation: (Long) -> Unit = {},
    onConversationInfo: (Long) -> Unit = {},
    onMarkRead: (Long) -> Unit = {},
    onMarkAllRead: () -> Unit = {},
    onSetMuted: (Long, Boolean) -> Unit = { _, _ -> },
    onSetPinned: (Long, Boolean) -> Unit = { _, _ -> },
    onSetArchived: (Long, Boolean) -> Unit = { _, _ -> },
) {
    val dmsLabel = stringResource(R.string.dickord_dms)
    val pendingLabel = stringResource(R.string.dickord_portal_pending)
    val serverGroups = state.groups.filter { it.guildId != null }
    val duplicateServerNames =
        serverGroups
            .groupingBy { it.displayName.orEmpty().lowercase() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    val selectedGroup = state.groups.firstOrNull { it.key == state.selectedGroupKey }
    val selectedTitle =
        when (selectedGroup?.key ?: state.selectedGroupKey) {
            DICKORD_PORTAL_DMS_KEY -> dmsLabel
            DICKORD_PORTAL_PENDING_KEY -> pendingLabel
            else -> selectedGroup?.serverLabel(duplicateServerNames) ?: dmsLabel
        }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("screen_dickord_portal"),
        snackbarHost = {
            if (snackbarHostState != null) SnackbarHost(snackbarHostState)
        },
        topBar = {
            DickordPortalHeader(
                title = selectedTitle,
                showArchived = state.showArchived,
                onBack = onBack,
                onShowArchived = onShowArchived,
                onRefresh = onRefresh,
                onMarkAllRead = onMarkAllRead,
            )
        },
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize().padding(padding)) {
            DickordServerRail(
                groups = state.groups,
                selectedKey = state.selectedGroupKey,
                duplicateServerNames = duplicateServerNames,
                dmsLabel = dmsLabel,
                pendingLabel = pendingLabel,
                onSelectGroup = onSelectGroup,
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DickordGroupPane(
                state = state,
                selectedGroup = selectedGroup,
                selectedBufferId = selectedBufferId,
                onRefresh = onRefresh,
                onOpenConversation = onOpenConversation,
                onConversationInfo = onConversationInfo,
                onMarkRead = onMarkRead,
                onSetMuted = onSetMuted,
                onSetPinned = onSetPinned,
                onSetArchived = onSetArchived,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DickordServerRail(
    groups: List<DickordPortalGroup>,
    selectedKey: String,
    duplicateServerNames: Set<String>,
    dmsLabel: String,
    pendingLabel: String,
    onSelectGroup: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.width(72.dp).fillMaxHeight().testTag("dickord_server_rail"),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PortalRailAction(
                label = dmsLabel,
                tag = "dickord_group_dms",
                selected = selectedKey == DICKORD_PORTAL_DMS_KEY,
                onClick = { onSelectGroup(DICKORD_PORTAL_DMS_KEY) },
            ) {
                Icon(Icons.Outlined.Forum, contentDescription = null)
            }
            HorizontalDivider(modifier = Modifier.width(40.dp).padding(vertical = 4.dp))
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(groups.filter { it.guildId != null }, key = DickordPortalGroup::key) { group ->
                    val label = group.serverLabel(duplicateServerNames)
                    PortalRailAction(
                        label = label,
                        tag = "dickord_server_${group.networkId}_${group.guildId}",
                        selected = selectedKey == group.key,
                        onClick = { onSelectGroup(group.key) },
                    ) {
                        DickordServerArtwork(group = group, label = group.displayName.orEmpty())
                    }
                }
                if (groups.any { it.key == DICKORD_PORTAL_PENDING_KEY }) {
                    item(key = DICKORD_PORTAL_PENDING_KEY) {
                        PortalRailAction(
                            label = pendingLabel,
                            tag = "dickord_group_pending",
                            selected = selectedKey == DICKORD_PORTAL_PENDING_KEY,
                            onClick = { onSelectGroup(DICKORD_PORTAL_PENDING_KEY) },
                        ) {
                            Icon(Icons.Outlined.Info, contentDescription = null)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PortalRailAction(
    label: String,
    tag: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .testTag(tag)
                    .semantics {
                        this.selected = selected
                        contentDescription = label
                        role = Role.Button
                    }.clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .width(4.dp)
                        .heightIn(min = 32.dp)
                        .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(if (selected) 18.dp else 28.dp))
                        .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center, content = content)
            }
        }
    }
}

@Composable
private fun DickordServerArtwork(
    group: DickordPortalGroup,
    label: String,
) {
    val outerAvatarStyle = LocalAvatarStyle.current
    val sharedImagesEnabled = LocalRemoteAvatars.current.enabled
    if (outerAvatarStyle == AvatarStyle.NONE) {
        PlainServerInitial(label)
        return
    }
    CompositionLocalProvider(
        LocalAvatarStyle provides AvatarStyle.INITIALS,
        LocalRemoteAvatars provides noRemoteAvatars,
    ) {
        Avatar(
            name = label,
            size = 48.dp,
            networkId = group.networkId,
            conversationModel = group.iconUrl.takeIf { sharedImagesEnabled },
        )
    }
}

@Composable
private fun PlainServerInitial(label: String) {
    val initial = remember(label) { navigationInitial(label) }
    Surface(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(initial, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

private fun navigationInitial(label: String): String {
    val value = label.trim()
    if (value.isEmpty()) return "?"
    val index = value.indexOfFirst(Char::isLetterOrDigit).takeIf { it >= 0 } ?: 0
    return String(Character.toChars(value.codePointAt(index))).uppercase()
}

private fun DickordPortalGroup.serverLabel(duplicateServerNames: Set<String>): String {
    val name = displayName.orEmpty()
    if (name.lowercase() !in duplicateServerNames) return name
    val network = conversations.firstOrNull()?.row?.networkName ?: networkId?.toString().orEmpty()
    return "$name — $network"
}

@Composable
private fun DickordGroupPane(
    state: DickordPortalState,
    selectedGroup: DickordPortalGroup?,
    selectedBufferId: Long?,
    onRefresh: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onConversationInfo: (Long) -> Unit,
    onMarkRead: (Long) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetArchived: (Long, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val allConversationCount = state.groups.sumOf { it.conversations.size }
    val waitingForDetails =
        selectedGroup?.conversations?.any { it.descriptor?.channelName == null } == true
    Column(modifier = modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
        if (state.offline && allConversationCount > 0) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.fillMaxWidth().testTag("dickord_portal_offline"),
            ) {
                Text(
                    text = stringResource(R.string.dickord_portal_offline),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        if (waitingForDetails) {
            PendingDetailsNotice(onRefresh)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth().testTag("dickord_channels")) {
            when {
                state.loading && allConversationCount == 0 -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                selectedGroup?.conversations?.isNotEmpty() == true -> {
                    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(selectedGroup.conversations, key = { it.row.bufferId }) { conversation ->
                            DickordConversationRow(
                                conversation = conversation,
                                dm = selectedGroup.key == DICKORD_PORTAL_DMS_KEY,
                                selected = selectedBufferId == conversation.row.bufferId,
                                onOpenConversation = onOpenConversation,
                                onConversationInfo = onConversationInfo,
                                onMarkRead = onMarkRead,
                                onSetMuted = onSetMuted,
                                onSetPinned = onSetPinned,
                                onSetArchived = onSetArchived,
                            )
                        }
                    }
                }

                else -> {
                    val title =
                        when {
                            state.showArchived -> stringResource(R.string.chatlist_archived_empty_title)
                            allConversationCount == 0 -> stringResource(R.string.dickord_portal_empty)
                            selectedGroup?.key == DICKORD_PORTAL_DMS_KEY -> stringResource(R.string.dickord_portal_no_dms)
                            else -> stringResource(R.string.dickord_portal_empty)
                        }
                    EmptyState(
                        icon = if (state.showArchived) Icons.Outlined.Archive else Icons.Outlined.Forum,
                        title = title,
                        message = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingDetailsNotice(onRefresh: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().testTag("dickord_pending_notice"),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = stringResource(R.string.dickord_portal_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRefresh, modifier = Modifier.defaultMinSize(minHeight = 48.dp)) {
                Text(stringResource(R.string.dickord_portal_refresh))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DickordPortalHeader(
    title: String,
    showArchived: Boolean,
    onBack: () -> Unit,
    onShowArchived: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onMarkAllRead: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    TopAppBar(
        modifier = Modifier.testTag("dickord_portal_top_app_bar"),
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onBack,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).testTag("dickord_portal_exit"),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.dickord_portal_exit),
                )
            }
        },
        actions = {
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).testTag("dickord_portal_refresh"),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.dickord_portal_refresh))
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).testTag("dickord_portal_more"),
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chatlist_more_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.drawer_mark_all_read)) },
                        leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                        modifier = Modifier.testTag("dickord_portal_mark_all_read"),
                        onClick = {
                            menuOpen = false
                            onMarkAllRead()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (showArchived) R.string.dickord_portal_channels else R.string.chatlist_archived_chats,
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(if (showArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = null)
                        },
                        modifier = Modifier.testTag("dickord_portal_archive"),
                        onClick = {
                            menuOpen = false
                            onShowArchived(!showArchived)
                        },
                    )
                }
            }
        },
        colors =
            TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
    )
}

@Composable
private fun DickordConversationRow(
    conversation: DickordPortalConversation,
    dm: Boolean,
    selected: Boolean,
    onOpenConversation: (Long) -> Unit,
    onConversationInfo: (Long) -> Unit,
    onMarkRead: (Long) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetArchived: (Long, Boolean) -> Unit,
) {
    val row = conversation.row
    val title = conversation.descriptor?.channelName ?: stringResource(R.string.dickord_portal_conversation_pending, row.bufferId)
    var menuOpen by remember(row.bufferId) { mutableStateOf(false) }
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("dickord_conversation_${row.bufferId}")
                .semantics(mergeDescendants = true) {
                    onClick {
                        onOpenConversation(row.bufferId)
                        true
                    }
                },
    ) {
        if (dm) {
            val sharedImagesEnabled = LocalRemoteAvatars.current.enabled
            CompositionLocalProvider(LocalRemoteAvatars provides noRemoteAvatars) {
                ChatListRowItem(
                    row = row,
                    showNetworkChip = false,
                    onClick = { onOpenConversation(row.bufferId) },
                    onLongClick = { menuOpen = true },
                    selected = selected,
                    displayTitle = title,
                    showDickordBadge = false,
                    avatarName = title,
                    avatarIsChannel = false,
                    avatarModel = if (sharedImagesEnabled) conversation.descriptor?.channelIconUrl else null,
                )
            }
        } else {
            DickordChannelRow(
                row = row,
                title = title,
                selected = selected,
                onClick = { onOpenConversation(row.bufferId) },
                onLongClick = { menuOpen = true },
            )
        }
        DickordConversationMenu(
            expanded = menuOpen,
            row = row,
            onDismiss = { menuOpen = false },
            onMarkRead = onMarkRead,
            onSetMuted = onSetMuted,
            onSetPinned = onSetPinned,
            onSetArchived = onSetArchived,
            onConversationInfo = onConversationInfo,
        )
    }
}

@Composable
private fun DickordChannelRow(
    row: ChatListRow,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val badges = chatListBadgeState(row)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .semantics { this.selected = selected }
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Forum,
            contentDescription = null,
            tint = if (row.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (!row.muted && row.unreadCount > 0) FontWeight.Bold else FontWeight.Medium,
            color = if (row.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.pinned) {
            Icon(
                Icons.Outlined.PushPin,
                contentDescription = stringResource(R.string.chatlist_pinned),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(14.dp),
            )
        }
        if (row.muted) {
            Icon(
                Icons.Filled.NotificationsOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp).size(14.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 6.dp)) {
            badges.mutedActivity?.let { MutedActivityBadge(it, lowerBound = badges.mutedActivityIncomplete) }
            badges.mentions?.let { MentionBadge(it, lowerBound = badges.mentionsIncomplete) }
            badges.unread?.let { UnreadBadge(it, lowerBound = badges.unreadIncomplete) }
            if (badges.advertisedActivity) AdvertisedActivityDot()
        }
    }
}

@Composable
private fun DickordConversationMenu(
    expanded: Boolean,
    row: ChatListRow,
    onDismiss: () -> Unit,
    onMarkRead: (Long) -> Unit,
    onSetMuted: (Long, Boolean) -> Unit,
    onSetPinned: (Long, Boolean) -> Unit,
    onSetArchived: (Long, Boolean) -> Unit,
    onConversationInfo: (Long) -> Unit,
) {
    fun act(block: () -> Unit) {
        onDismiss()
        block()
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.chatlist_mark_read)) },
            leadingIcon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
            modifier = Modifier.testTag("dickord_menu_mark_read"),
            onClick = { act { onMarkRead(row.bufferId) } },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (row.muted) R.string.chatlist_unmute else R.string.chatlist_mute)) },
            leadingIcon = {
                Icon(if (row.muted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff, contentDescription = null)
            },
            modifier = Modifier.testTag("dickord_menu_mute"),
            onClick = { act { onSetMuted(row.bufferId, !row.muted) } },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (row.pinned) R.string.chatlist_unpin else R.string.chatlist_pin)) },
            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
            modifier = Modifier.testTag("dickord_menu_pin"),
            onClick = { act { onSetPinned(row.bufferId, !row.pinned) } },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (row.archived) R.string.chatlist_unarchive else R.string.chatlist_archive)) },
            leadingIcon = {
                Icon(if (row.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, contentDescription = null)
            },
            modifier = Modifier.testTag("dickord_menu_archive"),
            onClick = { act { onSetArchived(row.bufferId, !row.archived) } },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.dickord_portal_info)) },
            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            modifier = Modifier.testTag("dickord_menu_info"),
            onClick = { act { onConversationInfo(row.bufferId) } },
        )
    }
}
