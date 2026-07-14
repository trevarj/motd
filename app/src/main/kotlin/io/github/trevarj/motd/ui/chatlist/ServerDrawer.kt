package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Settings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.IrcNetworkBadge
import io.github.trevarj.motd.ui.components.MentionBadge
import io.github.trevarj.motd.ui.components.UnreadBadge
import io.github.trevarj.motd.ui.theme.LocalAvatarStyle
import io.github.trevarj.motd.ui.theme.MotdTheme

// Static, theme-independent semaphore for the status dot (plans/16 §3.2). Ready green, in-flight
// amber; Failed/Disconnected come from the theme so they read as error/muted.
private val ReadyColor = Color(0xFF4CAF50)
private val PendingColor = Color(0xFFFFB300)

@Composable
private fun statusColor(state: IrcClientState): Color = when (state) {
    is IrcClientState.Ready -> ReadyColor
    IrcClientState.Connecting, IrcClientState.Registering -> PendingColor
    is IrcClientState.Failed -> MaterialTheme.colorScheme.error
    IrcClientState.Disconnected -> MaterialTheme.colorScheme.outlineVariant
}

/**
 * Server-drawer content (plans/16 §3.1). Stateless: takes the built [DrawerRow]s + rollups and
 * emits selection / connectivity / nav callbacks. Hosted by [ChatListScreen] inside a
 * `ModalNavigationDrawer`; previewable without a ViewModel.
 */
@Composable
fun ServerDrawerContent(
    drawerRows: List<DrawerRow>,
    selectedNetworkId: Long?,
    allUnread: Int,
    allMentions: Int,
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
    onMarkAllRead: () -> Unit,
) {
    ModalDrawerSheet {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            // Compact brand header: the lockup is deliberately smaller than a navigation row so
            // the network list, rather than the branding, owns the drawer's visual hierarchy.
            Image(
                painter = painterResource(R.drawable.motd_logo_lockup),
                contentDescription = stringResource(R.string.app_name),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface),
                contentScale = ContentScale.Fit,
                alignment = Alignment.CenterStart,
                modifier = Modifier
                    // 490:180 source ratio. Specifying both dimensions avoids the drawable's
                    // oversized 200x75dp intrinsic size expanding this header.
                    .padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                    .width(128.dp)
                    .height(47.dp),
            )

            // 1. Networks section header. The unscoped ("all chats") state is simply "no network
            // selected" — reflected by the title-bar wordmark — so there is no standalone row for
            // it. A subtle clear-filter action appears only while scoped.
            NetworksHeader(
                totalUnread = allUnread,
                totalMentions = allMentions,
                scoped = selectedNetworkId != null,
                onClearFilter = { onSelectNetwork(null) },
            )

            // 2. One entry per network (children indented under their soju root).
            for (row in drawerRows) {
                DrawerNetworkItem(
                    row = row,
                    selected = selectedNetworkId == row.networkId,
                    onSelect = { onSelectNetwork(row.networkId) },
                    onConnect = { onConnect(row.networkId) },
                    onDisconnect = { onDisconnect(row.networkId) },
                    onServerMessages = { onServerMessages(row.networkId) },
                    onOpenNetworkSettings = { onOpenNetworkSettings(row.networkId) },
                )
            }

            if (scopedUnreadCount > 0) {
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.DoneAll, contentDescription = null) },
                    label = { Text(stringResource(R.string.drawer_mark_all_read)) },
                    selected = false,
                    onClick = onMarkAllRead,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .testTag("drawer_mark_all_read"),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 3. App-level footer actions.
            NavigationDrawerItem(
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                label = { Text(stringResource(R.string.drawer_add_network)) },
                selected = false,
                onClick = onAddNetwork,
                modifier = Modifier.padding(horizontal = 12.dp),
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
    scoped: Boolean,
    onClearFilter: () -> Unit,
) {
    Row(
        modifier = Modifier
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
                if (totalMentions > 0) MentionBadge(totalMentions)
                if (totalUnread > 0) UnreadBadge(totalUnread)
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
) {
    var menuOpen by remember { mutableStateOf(false) }
    val background =
        if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Per-network handle so the harness targets a specific drawer row.
                .testTag("drawer_network_row_${row.networkId}")
                .padding(horizontal = 12.dp, vertical = 2.dp)
                // Selected row gets the M3 pill background.
                .background(background, RoundedCornerShape(28.dp))
                .combinedClickable(onClick = onSelect, onLongClick = { menuOpen = true })
                // Children indent one level under their soju root.
                .padding(start = (16 + row.depth * 16).dp, top = 8.dp, bottom = 8.dp, end = 16.dp)
                .heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // State is color-only in the dot; expose it as a CD ("status:Ready") + tag so the
            // harness can read a per-network connection state from a text dump.
            val statusCd = "status:${statusName(row.state)}"
            if (LocalAvatarStyle.current == AvatarStyle.IRC_SPRITE) {
                IrcNetworkBadge(
                    name = row.name,
                    networkId = row.networkId,
                    status = statusColor(row.state),
                    size = 32.dp,
                    modifier = Modifier
                        .testTag("drawer_status_dot_${row.networkId}")
                        .semantics { contentDescription = statusCd },
                )
            } else {
                Box(
                    modifier = Modifier
                        .testTag("drawer_status_dot_${row.networkId}")
                        .semantics { contentDescription = statusCd }
                        .size(10.dp)
                        .background(statusColor(row.state), CircleShape),
                )
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
                    color = if (row.state is IrcClientState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (row.mentions > 0) MentionBadge(row.mentions)
            if (row.unread > 0) UnreadBadge(row.unread)
        }

        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            val live = row.state.let { it !is IrcClientState.Disconnected && it !is IrcClientState.Failed }
            if (live) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drawer_disconnect)) },
                    onClick = { onDisconnect(); menuOpen = false },
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
                    onClick = { onConnect(); menuOpen = false },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_server_messages)) },
                onClick = { onServerMessages(); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_network_settings)) },
                onClick = { onOpenNetworkSettings(); menuOpen = false },
            )
        }
    }
}

/** Stable, non-localized state token for the status-dot CD (harness matches "status:Ready" etc.). */
private fun statusName(state: IrcClientState): String = when (state) {
    is IrcClientState.Ready -> "Ready"
    IrcClientState.Connecting -> "Connecting"
    IrcClientState.Registering -> "Registering"
    is IrcClientState.Failed -> "Failed"
    IrcClientState.Disconnected -> "Disconnected"
}

@Composable
private fun subtitleFor(state: IrcClientState, nick: String?): String = when (state) {
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
            drawerRows = listOf(
                DrawerRow(
                    networkId = 1, name = "Libera", role = NetworkRole.DIRECT, depth = 0,
                    state = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    nick = "me", unread = 5, mentions = 1,
                ),
                DrawerRow(
                    networkId = 2, name = "soju", role = NetworkRole.BOUNCER_ROOT, depth = 0,
                    state = IrcClientState.Connecting, nick = null, unread = 3, mentions = 0,
                ),
                DrawerRow(
                    networkId = 3, name = "OFTC", role = NetworkRole.BOUNCER_CHILD, depth = 1,
                    state = IrcClientState.Failed("SASL failed", fatal = true),
                    nick = null, unread = 3, mentions = 0,
                ),
            ),
            selectedNetworkId = 1,
            allUnread = 8, allMentions = 1, allOffline = false,
            scopedUnreadCount = 8,
            onSelectNetwork = {}, onConnect = {}, onDisconnect = {},
            onServerMessages = {}, onOpenNetworkSettings = {},
            onAddNetwork = {}, onToggleOffline = {}, onOpenSettings = {},
            onMarkAllRead = {},
        )
    }
}
