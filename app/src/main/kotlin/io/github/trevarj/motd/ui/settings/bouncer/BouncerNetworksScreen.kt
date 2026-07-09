package io.github.trevarj.motd.ui.settings.bouncer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel, scoped to [rootNetworkId] (plans/16 §5.5). */
@Composable
fun BouncerNetworksScreen(
    rootNetworkId: Long,
    onBack: () -> Unit = {},
    viewModel: BouncerNetworksViewModel = hiltViewModel(),
) {
    LaunchedEffect(rootNetworkId) { viewModel.init(rootNetworkId) }
    val state by viewModel.state.collectAsState()
    BouncerNetworksContent(
        state = state,
        onBack = onBack,
        onConnect = viewModel::connect,
        onToggleImport = { row ->
            if (row.childNetworkId == null) viewModel.importNetwork(row) else viewModel.removeLocal(row)
        },
        onDeleteFromBouncer = viewModel::deleteFromBouncer,
        onAddNetwork = viewModel::addNetwork,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BouncerNetworksContent(
    state: BouncerNetworksUiState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onToggleImport: (BouncerNetRow) -> Unit,
    onDeleteFromBouncer: (BouncerNetRow) -> Unit,
    onAddNetwork: (name: String, host: String, port: String?, nick: String?) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.bouncer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.rootState !is IrcClientState.Ready) {
                ConnectCard(onConnect = onConnect)
                return@Column
            }
            if (state.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.rows, key = { it.netId }) { row ->
                    BouncerRow(row = row, onToggleImport = onToggleImport, onDeleteFromBouncer = onDeleteFromBouncer)
                }
            }
            OutlinedButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) {
                Text(stringResource(R.string.bouncer_add_to_bouncer))
            }
        }
    }

    if (showAddDialog) {
        AddBouncerNetworkDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { name, host, port, nick ->
                showAddDialog = false
                onAddNetwork(name, host, port, nick)
            },
        )
    }
}

@Composable
private fun ConnectCard(onConnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.bouncer_connect_to_manage))
        Button(onClick = onConnect) { Text(stringResource(R.string.bouncer_connect)) }
    }
}

@Composable
private fun BouncerRow(
    row: BouncerNetRow,
    onToggleImport: (BouncerNetRow) -> Unit,
    onDeleteFromBouncer: (BouncerNetRow) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(row.name) },
        supportingContent = { row.host?.let { Text(it) } },
        leadingContent = {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(bouncerStateColor(row.bouncerState)),
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = row.childNetworkId != null,
                    onCheckedChange = { onToggleImport(row) },
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
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
    )

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.bouncer_delete_confirm_title)) },
            text = { Text(stringResource(R.string.bouncer_delete_confirm_message, row.name)) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDeleteFromBouncer(row) }) {
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

@Composable
private fun AddBouncerNetworkDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, host: String, port: String, nick: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var nick by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bouncer_add_to_bouncer)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.bouncer_field_name)) },
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it }, singleLine = true,
                    label = { Text(stringResource(R.string.bouncer_field_host)) },
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it.filter(Char::isDigit) }, singleLine = true,
                    label = { Text(stringResource(R.string.bouncer_field_port)) },
                )
                OutlinedTextField(
                    value = nick, onValueChange = { nick = it }, singleLine = true,
                    label = { Text(stringResource(R.string.bouncer_field_nick)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, host, port, nick) },
                enabled = name.isNotBlank() && host.isNotBlank(),
            ) {
                Text(stringResource(R.string.bouncer_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Static semaphore for the bouncer-reported per-network state string. */
@Composable
private fun bouncerStateColor(state: String?): Color = when (state) {
    "connected" -> Color(0xFF4CAF50)
    "connecting" -> Color(0xFFFFB300)
    else -> MaterialTheme.colorScheme.outlineVariant
}

@Preview
@Composable
private fun BouncerNetworksReadyPreview() {
    MotdTheme {
        BouncerNetworksContent(
            state = BouncerNetworksUiState(
                rootState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                rows = listOf(
                    BouncerNetRow("1", "Libera", "irc.libera.chat", "connected", childNetworkId = 5),
                    BouncerNetRow("2", "OFTC", "irc.oftc.net", "disconnected", childNetworkId = null),
                ),
            ),
            onBack = {}, onConnect = {}, onToggleImport = {}, onDeleteFromBouncer = {},
            onAddNetwork = { _, _, _, _ -> },
        )
    }
}

@Preview
@Composable
private fun BouncerNetworksDisconnectedPreview() {
    MotdTheme {
        BouncerNetworksContent(
            state = BouncerNetworksUiState(rootState = IrcClientState.Disconnected),
            onBack = {}, onConnect = {}, onToggleImport = {}, onDeleteFromBouncer = {},
            onAddNetwork = { _, _, _, _ -> },
        )
    }
}
