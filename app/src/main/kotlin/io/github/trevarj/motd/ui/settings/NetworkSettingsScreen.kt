package io.github.trevarj.motd.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.RadioButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
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
        onWsUrlChange = viewModel::editWsUrl,
        onObfsModeChange = viewModel::editObfsMode,
        onProxyHostChange = viewModel::editProxyHost,
        onProxyPortChange = viewModel::editProxyPort,
        onObfsLinkChange = viewModel::editObfsLink,
        onServerChange = viewModel::editServer,
        onAuthChange = viewModel::editAuth,
        onSave = { viewModel.save(onBack) },
        onCancelBouncerIdentityChange = viewModel::cancelBouncerIdentityChange,
        onKeepLocalMirrors = { viewModel.keepLocalMirrors(onBack) },
        onRemoveLocalMirrors = { viewModel.removeLocalMirrors(onBack) },
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
    onWsUrlChange: (String) -> Unit = {},
    onObfsModeChange: (ObfsMode) -> Unit = {},
    onProxyHostChange: (String) -> Unit = {},
    onProxyPortChange: (String) -> Unit = {},
    onObfsLinkChange: (String) -> Unit = {},
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    onSave: () -> Unit,
    onCancelBouncerIdentityChange: () -> Unit = {},
    onKeepLocalMirrors: () -> Unit = {},
    onRemoveLocalMirrors: () -> Unit = {},
    onDelete: () -> Unit,
    onConnect: () -> Unit = {},
    onDisconnect: () -> Unit = {},
    onSetAutoConnect: (Boolean) -> Unit = {},
    onOpenBouncerNetworks: () -> Unit = {},
    onOpenServerMessages: () -> Unit = {},
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val requestBack = { if (state.hasUnsavedChanges) showDiscardConfirm = true else onBack() }
    BackHandler(enabled = state.hasUnsavedChanges) { showDiscardConfirm = true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(state.entity?.name ?: stringResource(R.string.network_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = state.canSave,
                        modifier = Modifier.testTag("network_settings_save"),
                    ) { Text(stringResource(R.string.network_settings_save)) }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsGroup { StatusCard(state.connState, onConnect, onDisconnect) }
                SettingsGroup(title = stringResource(R.string.network_settings_general_section)) {
                    OutlinedTextField(
                        value = state.displayName,
                        onValueChange = onDisplayNameChange,
                        label = { Text(stringResource(R.string.network_settings_display_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("network_display_name"),
                    )
                    AutoConnectRow(checked = state.autoConnect, onCheckedChange = onSetAutoConnect)
                }
                if (state.entity?.role == NetworkRole.BOUNCER_CHILD) {
                    SettingsGroup(title = stringResource(R.string.network_settings_connection_section)) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.network_settings_managed_by, state.parentName.orEmpty())) },
                            supportingContent = { Text(stringResource(R.string.network_settings_managed_by_desc)) },
                            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                } else {
                    SettingsGroup(title = stringResource(R.string.network_settings_connection_section)) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            NetworkEndpointFields(state.server, onServerChange)
                        }
                        androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        TransportSection(wsUrl = state.wsUrl, onWsUrlChange = onWsUrlChange)
                        androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        ObfuscationSection(
                            mode = state.obfsMode,
                            proxyHost = state.proxyHost,
                            proxyPort = state.proxyPort,
                            obfsLink = state.obfsLink,
                            onModeChange = onObfsModeChange,
                            onProxyHostChange = onProxyHostChange,
                            onProxyPortChange = onProxyPortChange,
                            onObfsLinkChange = onObfsLinkChange,
                        )
                    }
                    SettingsGroup(title = stringResource(R.string.network_settings_identity_section)) {
                        Column(Modifier.padding(16.dp)) {
                            NetworkIdentityFields(
                                server = state.server,
                                auth = state.auth,
                                onServerChange = onServerChange,
                                onAuthChange = onAuthChange,
                                soju = state.entity?.role == NetworkRole.BOUNCER_ROOT,
                            )
                        }
                    }
                }
                SettingsGroup(title = stringResource(R.string.network_settings_tools_section)) {
                    if (state.entity?.role == NetworkRole.BOUNCER_ROOT) {
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.network_settings_bouncer_networks)) },
                            supportingContent = { Text(stringResource(R.string.network_settings_bouncer_networks_desc)) },
                            modifier = Modifier.clickable { onOpenBouncerNetworks() },
                            colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.network_settings_server_messages)) },
                        modifier = Modifier.clickable { onOpenServerMessages() },
                        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                SettingsGroup(title = stringResource(R.string.network_settings_danger_section)) {
                    OutlinedButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) { Text(stringResource(R.string.network_settings_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.network_settings_unsaved_title)) },
            text = { Text(stringResource(R.string.network_settings_unsaved_message)) },
            confirmButton = {
                TextButton(onClick = { showDiscardConfirm = false; onSave() }, enabled = state.isValid) {
                    Text(stringResource(R.string.network_settings_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDiscardConfirm = false }) { Text(stringResource(R.string.network_settings_keep_editing)) }
                    TextButton(onClick = { showDiscardConfirm = false; onBack() }) {
                        Text(stringResource(R.string.network_settings_discard), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
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

    state.pendingBouncerIdentityChange?.let { change ->
        AlertDialog(
            onDismissRequest = onCancelBouncerIdentityChange,
            title = { Text("Update bouncer connection?") },
            text = {
                Text(
                    "This changes the connection or login inherited by " +
                        "${change.localMirrorCount} local bouncer " +
                        (if (change.localMirrorCount == 1) "mirror." else "mirrors.") +
                        " You can keep them, or remove their local buffers and history. " +
                        "Removing mirrors does not change anything on the bouncer.",
                )
            },
            confirmButton = {
                TextButton(onClick = onRemoveLocalMirrors) {
                    Text("Remove local mirrors")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = onCancelBouncerIdentityChange) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(onClick = onKeepLocalMirrors) {
                        Text("Keep local mirrors")
                    }
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

/**
 * Collapsible "Connection / Obfuscation" section (plans/20 Phase 1). Off by default: a header row
 * with an expand toggle reveals the mode selector; SOCKS5 reveals host/port and embedded REALITY
 * accepts only a share link. The header always exposes the current value and expansion affordance.
 */
@Composable
private fun ObfuscationSection(
    mode: ObfsMode,
    proxyHost: String,
    proxyPort: String,
    obfsLink: String,
    onModeChange: (ObfsMode) -> Unit,
    onProxyHostChange: (String) -> Unit,
    onProxyPortChange: (String) -> Unit,
    onObfsLinkChange: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(mode == ObfsMode.EMBEDDED_REALITY && vlessLinkValidationError(obfsLink) != null) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag("network_obfs_header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.network_settings_routing), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.network_settings_current_value, obfsModeLabel(mode)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!expanded) Text(
                    stringResource(R.string.network_settings_tap_configure),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.network_settings_collapse else R.string.network_settings_expand,
                ),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(start = 8.dp, end = 16.dp, bottom = 16.dp)) {
                Text(
                    stringResource(R.string.network_settings_obfs_section_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 6.dp),
                )
                Column(Modifier.selectableGroup()) {
                    ObfsOption(ObfsMode.NONE, mode, stringResource(R.string.network_settings_obfs_mode_none), onModeChange)
                    ObfsOption(ObfsMode.SOCKS5, mode, stringResource(R.string.network_settings_obfs_mode_socks5), onModeChange)
                    ObfsOption(ObfsMode.TOR, mode, stringResource(R.string.network_settings_obfs_mode_tor), onModeChange)
                    ObfsOption(ObfsMode.EMBEDDED_REALITY, mode, stringResource(R.string.network_settings_obfs_mode_reality), onModeChange)
                }
                when (mode) {
            ObfsMode.TOR -> Text(
                stringResource(R.string.network_settings_obfs_tor_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ObfsMode.SOCKS5 -> Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = proxyHost,
                    onValueChange = onProxyHostChange,
                    label = { Text(stringResource(R.string.network_settings_obfs_proxy_host)) },
                    placeholder = { Text("127.0.0.1") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("network_obfs_host"),
                )
                OutlinedTextField(
                    value = proxyPort,
                    onValueChange = onProxyPortChange,
                    label = { Text(stringResource(R.string.network_settings_obfs_proxy_port)) },
                    placeholder = { Text("1080") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().testTag("network_obfs_port"),
                )
                Text(
                    stringResource(R.string.network_settings_obfs_dns_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ObfsMode.EMBEDDED_REALITY -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The VLESS URI's host and port are the public tunnel ingress on your VPS. " +
                        "The Server host and port fields below remain the bouncer destination " +
                        "inside that tunnel (for example Docker soju:6697), not the VPS IP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(R.string.network_settings_obfs_reality_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = obfsLink,
                    onValueChange = onObfsLinkChange,
                    label = { Text(stringResource(R.string.network_settings_obfs_reality_link)) },
                    placeholder = { Text("vless://uuid@host:443?type=tcp&security=reality&…") },
                    singleLine = false,
                    isError = vlessLinkValidationError(obfsLink) != null,
                    supportingText = {
                        vlessLinkValidationError(obfsLink)?.let { Text(it) }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("network_obfs_link"),
                )
            }
            ObfsMode.NONE -> Unit
                }
            }
        }
    }
}

@Composable
private fun TransportSection(wsUrl: String, onWsUrlChange: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var websocketSelected by rememberSaveable { mutableStateOf(wsUrl.isNotBlank()) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp).testTag("network_transport_header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.network_settings_transport), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(
                        R.string.network_settings_current_value,
                        stringResource(if (websocketSelected) R.string.network_settings_transport_websocket else R.string.network_settings_transport_tcp),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!expanded) Text(
                    stringResource(R.string.network_settings_tap_configure),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.network_settings_collapse else R.string.network_settings_expand),
            )
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 8.dp, end = 16.dp, bottom = 16.dp).selectableGroup()) {
                RadioRow(
                    label = stringResource(R.string.network_settings_transport_tcp),
                    subtitle = stringResource(R.string.network_settings_transport_tcp_desc),
                    selected = !websocketSelected,
                    enabled = true,
                    onClick = { websocketSelected = false; onWsUrlChange("") },
                )
                RadioRow(
                    label = stringResource(R.string.network_settings_transport_websocket),
                    subtitle = stringResource(R.string.network_settings_transport_websocket_desc),
                    selected = websocketSelected,
                    enabled = true,
                    onClick = { websocketSelected = true },
                )
                if (websocketSelected) {
                    OutlinedTextField(
                        value = wsUrl,
                        onValueChange = onWsUrlChange,
                        label = { Text(stringResource(R.string.network_settings_ws_url)) },
                        placeholder = { Text("wss://bnc.example.com:443/") },
                        singleLine = true,
                        supportingText = { Text(stringResource(R.string.network_settings_ws_url_desc)) },
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp).testTag("network_ws_url"),
                    )
                }
            }
        }
    }
}

@Composable
private fun obfsModeLabel(mode: ObfsMode): String = stringResource(
    when (mode) {
        ObfsMode.NONE -> R.string.network_settings_obfs_mode_none
        ObfsMode.SOCKS5 -> R.string.network_settings_obfs_mode_socks5
        ObfsMode.TOR -> R.string.network_settings_obfs_mode_tor
        ObfsMode.EMBEDDED_REALITY -> R.string.network_settings_obfs_mode_reality
    },
)

@Composable
private fun ObfsOption(
    mode: ObfsMode,
    selected: ObfsMode,
    label: String,
    onSelect: (ObfsMode) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().selectable(
            selected = mode == selected,
            role = androidx.compose.ui.semantics.Role.RadioButton,
            onClick = { onSelect(mode) },
        ).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = mode == selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
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
