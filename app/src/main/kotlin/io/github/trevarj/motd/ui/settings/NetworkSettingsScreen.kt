package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel, seeds the edit form from the network id. */
@Composable
fun NetworkSettingsScreen(
    networkId: Long,
    onBack: () -> Unit = {},
    // Round 5 (plans/16): soju bouncer manager + server-messages buffer. Bodies land in WP-V2.
    onOpenBouncerNetworks: (Long) -> Unit = {},
    onOpenBuffer: (Long) -> Unit = {},
    viewModel: NetworkSettingsViewModel = hiltViewModel(),
) {
    LaunchedEffect(networkId) { viewModel.init(networkId) }
    val state by viewModel.state.collectAsState()
    NetworkSettingsContent(
        state = state,
        onBack = onBack,
        onServerChange = viewModel::editServer,
        onAuthChange = viewModel::editAuth,
        onSave = { viewModel.save(onBack) },
        onDelete = { viewModel.delete(onBack) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsContent(
    state: NetworkSettingsUiState,
    onBack: () -> Unit,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
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
            NetworkForm(
                server = state.server,
                auth = state.auth,
                onServerChange = onServerChange,
                onAuthChange = onAuthChange,
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
