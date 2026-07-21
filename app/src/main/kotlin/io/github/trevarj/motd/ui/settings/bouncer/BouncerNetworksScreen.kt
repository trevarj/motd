package io.github.trevarj.motd.ui.settings.bouncer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.bouncer.ChannelCommandFields
import io.github.trevarj.motd.bouncer.NetworkCommandFields
import io.github.trevarj.motd.bouncer.UserCommandFields
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.MotdTheme

data class BouncerControlCallbacks(
    val onConnect: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onProbe: () -> Unit = {},
    val onSelectTab: (BouncerControlTab) -> Unit = {},
    val onClearFeedback: () -> Unit = {},
    val onToggleImport: (BouncerNetRow) -> Unit = {},
    val onCreateNetwork: (NetworkCommandFields) -> Unit = {},
    val onUpdateNetwork: (String, NetworkCommandFields) -> Unit = { _, _ -> },
    val onDeleteNetwork: (BouncerNetRow) -> Unit = {},
    val onChannelStatus: (String) -> Unit = {},
    val onCreateChannel: (String, String, ChannelCommandFields) -> Unit = { _, _, _ -> },
    val onUpdateChannel: (String, String, ChannelCommandFields) -> Unit = { _, _, _ -> },
    val onDeleteChannel: (String, String) -> Unit = { _, _ -> },
    val onUpdateAccount: (String?, String?) -> Unit = { _, _ -> },
    val onSaslStatus: (String) -> Unit = {},
    val onSetSaslPlain: (String, String, String) -> Unit = { _, _, _ -> },
    val onResetSasl: (String) -> Unit = {},
    val onGenerateCertFp: (String, String) -> Unit = { _, _ -> },
    val onShowCertFp: (String) -> Unit = {},
    val onUserStatus: (String?) -> Unit = {},
    val onCreateUser: (String, String, Boolean, Boolean) -> Unit = { _, _, _, _ -> },
    val onUpdateUser: (String?, UserCommandFields) -> Unit = { _, _ -> },
    val onRequestUserDeletion: (String) -> Unit = {},
    val onConfirmUserDeletion: () -> Unit = {},
    val onCancelUserDeletion: () -> Unit = {},
    val onRunAsUser: (String, String) -> Unit = { _, _ -> },
    val onServerStatus: () -> Unit = {},
    val onServerNotice: (String) -> Unit = {},
    val onServerDebug: (Boolean) -> Unit = {},
    val onSubmitConsole: (String) -> Unit = {},
)

@Composable
fun BouncerNetworksScreen(
    rootNetworkId: Long,
    onBack: () -> Unit = {},
    viewModel: BouncerNetworksViewModel = hiltViewModel(),
) {
    LaunchedEffect(rootNetworkId) { viewModel.init(rootNetworkId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BouncerNetworksContent(
        state = state,
        onBack = onBack,
        callbacks = BouncerControlCallbacks(
            onConnect = viewModel::connect,
            onRefresh = viewModel::refresh,
            onProbe = viewModel::probeCapabilities,
            onSelectTab = viewModel::selectTab,
            onClearFeedback = viewModel::clearFeedback,
            onToggleImport = { row ->
                if (row.childNetworkId == null) viewModel.importNetwork(row) else viewModel.removeLocal(row)
            },
            onCreateNetwork = viewModel::createNetwork,
            onUpdateNetwork = viewModel::updateNetwork,
            onDeleteNetwork = viewModel::deleteFromBouncer,
            onChannelStatus = viewModel::channelStatus,
            onCreateChannel = viewModel::createChannel,
            onUpdateChannel = viewModel::updateChannel,
            onDeleteChannel = viewModel::deleteChannel,
            onUpdateAccount = viewModel::updateAccount,
            onSaslStatus = viewModel::saslStatus,
            onSetSaslPlain = viewModel::setSaslPlain,
            onResetSasl = viewModel::resetSasl,
            onGenerateCertFp = viewModel::generateCertFp,
            onShowCertFp = viewModel::showCertFp,
            onUserStatus = viewModel::userStatus,
            onCreateUser = viewModel::createUser,
            onUpdateUser = viewModel::updateUser,
            onRequestUserDeletion = viewModel::requestUserDeletion,
            onConfirmUserDeletion = viewModel::confirmUserDeletion,
            onCancelUserDeletion = viewModel::cancelUserDeletion,
            onRunAsUser = viewModel::runAsUser,
            onServerStatus = viewModel::serverStatus,
            onServerNotice = viewModel::sendServerNotice,
            onServerDebug = viewModel::setServerDebug,
            onSubmitConsole = viewModel::submitConsole,
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BouncerNetworksContent(
    state: BouncerNetworksUiState,
    onBack: () -> Unit,
    callbacks: BouncerControlCallbacks,
) {
    val ready = state.rootState is IrcClientState.Ready
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bouncer_control_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = callbacks.onRefresh, enabled = ready && !state.loading) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.loading || state.probing || state.commandBusy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            ConnectionAndCapabilityCard(state, callbacks)
            BouncerTabs(state.selectedTab, state.capabilities.administrator, callbacks.onSelectTab)
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (state.selectedTab) {
                    BouncerControlTab.NETWORKS -> NetworksPanel(state, ready, callbacks)
                    BouncerControlTab.CHANNELS -> ChannelsPanel(state, ready, callbacks)
                    BouncerControlTab.ACCOUNT -> AccountPanel(state, ready, callbacks)
                    BouncerControlTab.ADMIN -> AdminPanel(state, ready, callbacks)
                    BouncerControlTab.CONSOLE -> ConsolePanel(state, ready, callbacks)
                }
            }
        }
    }
    state.pendingUserDeletion?.let { pending ->
        AlertDialog(
            onDismissRequest = callbacks.onCancelUserDeletion,
            title = { Text(stringResource(R.string.bouncer_user_delete_final_title)) },
            text = { Text(stringResource(R.string.bouncer_user_delete_final_message, pending.username)) },
            confirmButton = {
                TextButton(onClick = callbacks.onConfirmUserDeletion) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = callbacks.onCancelUserDeletion) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun ConnectionAndCapabilityCard(
    state: BouncerNetworksUiState,
    callbacks: BouncerControlCallbacks,
) {
    val ready = state.rootState is IrcClientState.Ready
    val container = when {
        !ready -> MaterialTheme.colorScheme.errorContainer
        !state.capabilities.verified -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(
        color = container,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when {
                    !ready -> stringResource(R.string.bouncer_offline_cached)
                    state.capabilities.verified -> stringResource(R.string.bouncer_commands_verified)
                    else -> stringResource(R.string.bouncer_commands_unverified)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!ready) {
                    Button(onClick = callbacks.onConnect) { Text(stringResource(R.string.bouncer_connect)) }
                } else if (!state.capabilities.verified) {
                    OutlinedButton(onClick = callbacks.onProbe, enabled = !state.probing) {
                        Text(stringResource(R.string.bouncer_probe_again))
                    }
                }
            }
            state.notice?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.testTag("bouncer_command_notice"),
                )
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = callbacks.onClearFeedback) { Text(stringResource(R.string.action_dismiss)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BouncerTabs(
    selected: BouncerControlTab,
    administrator: Boolean,
    onSelect: (BouncerControlTab) -> Unit,
) {
    val tabs = buildList {
        add(BouncerControlTab.NETWORKS to R.string.bouncer_tab_networks)
        add(BouncerControlTab.CHANNELS to R.string.bouncer_tab_channels)
        add(BouncerControlTab.ACCOUNT to R.string.bouncer_tab_account)
        if (administrator) add(BouncerControlTab.ADMIN to R.string.bouncer_tab_admin)
        add(BouncerControlTab.CONSOLE to R.string.bouncer_tab_console)
    }
    val visibleSelected = tabs.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    PrimaryTabRow(selectedTabIndex = visibleSelected) {
        tabs.forEach { (tab, label) ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { Text(stringResource(label), maxLines = 1) },
                modifier = Modifier.testTag("bouncer_tab_${tab.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun NetworksPanel(
    state: BouncerNetworksUiState,
    enabled: Boolean,
    callbacks: BouncerControlCallbacks,
) {
    var showCreate by remember { mutableStateOf(false) }
    var editRow by remember { mutableStateOf<BouncerNetRow?>(null) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("bouncer_networks_panel"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                stringResource(R.string.bouncer_networks_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.rows, key = { it.netId }) { row ->
            BouncerRow(
                row = row,
                enabled = enabled && !state.commandBusy,
                onToggleImport = callbacks.onToggleImport,
                onEdit = { editRow = it },
                onDeleteFromBouncer = callbacks.onDeleteNetwork,
            )
        }
        item {
            Button(
                onClick = { showCreate = true },
                enabled = enabled && state.capabilities.supports("network create") && !state.commandBusy,
                modifier = Modifier.fillMaxWidth().testTag("bouncer_add_network"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(stringResource(R.string.bouncer_add_to_bouncer), Modifier.padding(start = 8.dp))
            }
        }
    }
    if (showCreate) {
        NetworkEditorDialog(
            existingName = null,
            onDismiss = { showCreate = false },
            onSubmit = { fields -> showCreate = false; callbacks.onCreateNetwork(fields) },
        )
    }
    editRow?.let { row ->
        NetworkEditorDialog(
            existingName = row.name,
            onDismiss = { editRow = null },
            onSubmit = { fields -> editRow = null; callbacks.onUpdateNetwork(row.name, fields) },
        )
    }
}

@Composable
private fun BouncerRow(
    row: BouncerNetRow,
    enabled: Boolean,
    onToggleImport: (BouncerNetRow) -> Unit,
    onEdit: (BouncerNetRow) -> Unit,
    onDeleteFromBouncer: (BouncerNetRow) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val showInMotdLabel = stringResource(R.string.bouncer_show_in_motd)
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        ListItem(
            modifier = Modifier.testTag("bouncer_row_${row.netId}"),
            headlineContent = { Text(row.name) },
            supportingContent = { row.host?.let { Text(it) } },
            leadingContent = {
                Box(
                    Modifier.size(10.dp).clip(CircleShape)
                        .background(bouncerStateColor(row.bouncerState)),
                )
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = row.childNetworkId != null,
                        onCheckedChange = { onToggleImport(row) },
                        enabled = enabled,
                        modifier = Modifier.testTag("bouncer_switch_${row.netId}").semantics {
                            contentDescription = showInMotdLabel
                        },
                    )
                    Box {
                        IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_edit)) },
                                onClick = { menuOpen = false; onEdit(row) },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.bouncer_delete_from_bouncer),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = { menuOpen = false; showDeleteConfirm = true },
                            )
                        }
                    }
                }
            },
            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.bouncer_delete_confirm_title)) },
            text = { Text(stringResource(R.string.bouncer_delete_confirm_message, row.name)) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDeleteFromBouncer(row) }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun bouncerStateColor(state: String?): Color {
    val semanticColors = LocalMotdSemanticColors.current
    return when (state) {
        "connected" -> semanticColors.success
        "connecting" -> semanticColors.warning
        else -> MaterialTheme.colorScheme.outlineVariant
    }
}

@Preview
@Composable
private fun BouncerNetworksReadyPreview() {
    MotdTheme {
        BouncerNetworksContent(
            state = BouncerNetworksUiState(
                rootState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                capabilities = io.github.trevarj.motd.bouncer.BouncerServCapabilities(
                    setOf("network create", "network update", "server status"),
                    verified = true,
                ),
                rows = listOf(
                    BouncerNetRow("1", "Libera", "irc.libera.chat", "connected", childNetworkId = 5),
                    BouncerNetRow("2", "OFTC", "irc.oftc.net", "disconnected", childNetworkId = null),
                ),
            ),
            onBack = {},
            callbacks = BouncerControlCallbacks(),
        )
    }
}
