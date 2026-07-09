package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel, seeds the edit form from the network id. */
@Composable
fun NetworkSettingsScreen(
    networkId: Long,
    onBack: () -> Unit = {},
    // Round 5 (plans/16): soju bouncer manager + server-messages buffer.
    onOpenBouncerNetworks: (Long) -> Unit = {},
    onOpenBuffer: (Long) -> Unit = {},
    viewModel: NetworkSettingsViewModel = hiltViewModel(),
) {
    LaunchedEffect(networkId) { viewModel.init(networkId) }
    val state by viewModel.state.collectAsState()
    NetworkSettingsContent(
        state = state,
        onBack = onBack,
        onDisplayNameChange = viewModel::editDisplayName,
        onServerChange = viewModel::editServer,
        onAuthChange = viewModel::editAuth,
        onSave = { viewModel.save(onBack) },
        onDelete = { viewModel.delete(onBack) },
        onConnect = viewModel::connect,
        onDisconnect = viewModel::disconnect,
        onSetAutoConnect = viewModel::setAutoConnect,
        onOpenBouncerNetworks = { onOpenBouncerNetworks(networkId) },
        onOpenServerMessages = { viewModel.openServerBuffer(onOpenBuffer) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsContent(
    state: NetworkSettingsUiState,
    onBack: () -> Unit,
    onDisplayNameChange: (String) -> Unit = {},
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onSetAutoConnect: (Boolean) -> Unit = {},
    onOpenBouncerNetworks: () -> Unit = {},
    onOpenServerMessages: () -> Unit = {},
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.entity?.name ?: stringResource(R.string.network_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.canSave) {
                FloatingActionButton(onClick = onSave) {
                    Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.network_settings_save))
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Status header: live connection state + connect/disconnect/reconnect (plans/16 §5.3).
            StatusCard(
                connState = state.connState,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
            )
            // Editable display name (alias): rename a network so the drawer/list show a friendly
            // label (e.g. "soju") instead of the raw host/IP. Persisted with the Save (check) FAB.
            OutlinedTextField(
                value = state.displayName,
                onValueChange = onDisplayNameChange,
                label = { Text(stringResource(R.string.network_settings_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("network_display_name"),
            )
            // autoConnect toggle — persisted immediately.
            AutoConnectRow(checked = state.autoConnect, onCheckedChange = onSetAutoConnect)
            when (state.entity?.role) {
                NetworkRole.BOUNCER_ROOT -> ListItem(
                    headlineContent = { Text(stringResource(R.string.network_settings_bouncer_networks)) },
                    supportingContent = { Text(stringResource(R.string.network_settings_bouncer_networks_desc)) },
                    modifier = Modifier.clickable { onOpenBouncerNetworks() },
                )
                NetworkRole.BOUNCER_CHILD -> ListItem(
                    headlineContent = {
                        Text(stringResource(R.string.network_settings_managed_by, state.parentName.orEmpty()))
                    },
                    supportingContent = { Text(stringResource(R.string.network_settings_managed_by_desc)) },
                )
                else -> Unit
            }
            ListItem(
                headlineContent = { Text(stringResource(R.string.network_settings_server_messages)) },
                modifier = Modifier.clickable { onOpenServerMessages() },
            )
            NetworkForm(
                server = state.server,
                auth = state.auth,
                onServerChange = onServerChange,
                onAuthChange = onAuthChange,
                // Bouncer root/child edit uses the collapsed soju form (username/password only).
                soju = state.entity?.role == NetworkRole.BOUNCER_ROOT ||
                    state.entity?.role == NetworkRole.BOUNCER_CHILD,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.network_settings_delete), color = Color.Red)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.network_settings_delete_confirm_title)) },
            text = { Text(stringResource(R.string.network_settings_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete))
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

/** Status header: a dot + state label with a state-appropriate action button. */
@Composable
private fun StatusCard(
    connState: IrcClientState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(statusColor(connState)),
            )
        },
        headlineContent = {
            Text(statusLabel(connState), modifier = Modifier.testTag("network_settings_status"))
        },
        trailingContent = {
            // Single stable handle; the label varies Connect/Disconnect/Reconnect by state.
            val connButton = Modifier.testTag("network_settings_conn_button")
            when (connState) {
                is IrcClientState.Disconnected ->
                    FilledTonalButton(onClick = onConnect, modifier = connButton) {
                        Text(stringResource(R.string.network_settings_status_connect))
                    }
                is IrcClientState.Failed ->
                    FilledTonalButton(onClick = onConnect, modifier = connButton) {
                        Text(stringResource(R.string.network_settings_status_reconnect))
                    }
                else ->
                    FilledTonalButton(onClick = onDisconnect, modifier = connButton) {
                        Text(stringResource(R.string.network_settings_status_disconnect))
                    }
            }
        },
    )
}

@Composable
private fun statusLabel(connState: IrcClientState): String = when (connState) {
    is IrcClientState.Ready -> stringResource(R.string.network_settings_status_ready, connState.nick)
    IrcClientState.Connecting -> stringResource(R.string.network_settings_status_connecting)
    IrcClientState.Registering -> stringResource(R.string.network_settings_status_registering)
    IrcClientState.Disconnected -> stringResource(R.string.network_settings_status_disconnected)
    is IrcClientState.Failed -> stringResource(R.string.network_settings_status_failed, connState.reason)
}

/** Static semaphore matching the drawer's status dots (theme-independent). */
@Composable
private fun statusColor(connState: IrcClientState): Color = when (connState) {
    is IrcClientState.Ready -> Color(0xFF4CAF50)
    IrcClientState.Connecting, IrcClientState.Registering -> Color(0xFFFFB300)
    is IrcClientState.Failed -> MaterialTheme.colorScheme.error
    IrcClientState.Disconnected -> MaterialTheme.colorScheme.outlineVariant
}

@Composable
private fun AutoConnectRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.network_settings_autoconnect))
            Text(
                stringResource(R.string.network_settings_autoconnect_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Preview
@Composable
private fun NetworkSettingsContentPreview() {
    MotdTheme {
        NetworkSettingsContent(
            state = NetworkSettingsUiState(
                loaded = true,
                server = ServerForm(host = "irc.libera.chat", port = "6697", nick = "me"),
                auth = AuthForm(mode = AuthMode.PLAIN, saslUser = "me", saslPassword = "secret"),
            ),
            onBack = {}, onServerChange = {}, onAuthChange = {}, onSave = {}, onDelete = {},
        )
    }
}
