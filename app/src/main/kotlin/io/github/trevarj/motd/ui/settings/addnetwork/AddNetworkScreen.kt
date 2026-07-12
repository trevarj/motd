package io.github.trevarj.motd.ui.settings.addnetwork

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.ConnectionChoice
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.settings.NetworkForm
import io.github.trevarj.motd.ui.settings.SettingsGroup
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel and drives navigation (plans/16 §5.4). */
@Composable
fun AddNetworkScreen(
    onBack: () -> Unit = {},
    onOpenBouncerNetworks: (Long) -> Unit = {},
    viewModel: AddNetworkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    AddNetworkContent(
        state = state,
        onBack = onBack,
        onSetKind = viewModel::setKind,
        onServerChange = viewModel::editServer,
        onAuthChange = viewModel::editAuth,
        onSubmit = { viewModel.submit(onOpenBouncerNetworks, onBack) },
        onRetry = { viewModel.retry(onOpenBouncerNetworks, onBack) },
        onSaveAnyway = { viewModel.saveAnyway(onBack) },
        onEditForm = viewModel::editForm,
        onAbandon = { viewModel.abandon(onBack) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNetworkContent(
    state: AddNetworkUiState,
    onBack: () -> Unit,
    onSetKind: (ConnectionChoice) -> Unit,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onSaveAnyway: () -> Unit,
    onEditForm: () -> Unit,
    onAbandon: () -> Unit,
) {
    // During TESTING/FAILED a row already exists; back-press must delete it first.
    val hasHalfCreated = state.phase != AddNetworkPhase.FORM
    BackHandler(enabled = hasHalfCreated) { onAbandon() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_network_title)) },
                navigationIcon = {
                    IconButton(onClick = { if (hasHalfCreated) onAbandon() else onBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SettingsGroup(title = stringResource(R.string.add_network_type_section)) {
                    KindSelector(kind = state.kind, enabled = state.phase == AddNetworkPhase.FORM, onSetKind = onSetKind)
                }
                SettingsGroup(title = stringResource(R.string.add_network_details_section)) {
                    NetworkForm(
                        server = state.server,
                        auth = state.auth,
                        onServerChange = onServerChange,
                        onAuthChange = onAuthChange,
                        soju = state.isSoju,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
                Button(
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().testTag("connect_save_button"),
                ) { Text(stringResource(R.string.add_network_connect_save)) }

                when (state.phase) {
                    AddNetworkPhase.TESTING -> TestingRow(state.connState)
                    AddNetworkPhase.FAILED -> FailedSection(
                        error = state.error,
                        onEditForm = onEditForm,
                        onSaveAnyway = onSaveAnyway,
                        onRetry = onRetry,
                    )
                    AddNetworkPhase.FORM -> Unit
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindSelector(
    kind: ConnectionChoice,
    enabled: Boolean,
    onSetKind: (ConnectionChoice) -> Unit,
) {
    val options = listOf(
        ConnectionChoice.NETWORK to R.string.add_network_kind_network,
        ConnectionChoice.SOJU to R.string.add_network_kind_soju,
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        options.forEachIndexed { index, (choice, labelRes) ->
            SegmentedButton(
                selected = kind == choice,
                onClick = { if (enabled) onSetKind(choice) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@Composable
private fun TestingRow(connState: IrcClientState?) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.add_network_testing)) },
        supportingContent = { Text(connStateLabel(connState)) },
        leadingContent = { CircularProgressIndicator(modifier = Modifier.size(24.dp)) },
    )
}

@Composable
private fun connStateLabel(connState: IrcClientState?): String = when (connState) {
    is IrcClientState.Ready -> stringResource(R.string.network_settings_status_ready, connState.nick)
    IrcClientState.Connecting, null -> stringResource(R.string.network_settings_status_connecting)
    IrcClientState.Registering -> stringResource(R.string.network_settings_status_registering)
    IrcClientState.Disconnected -> stringResource(R.string.network_settings_status_disconnected)
    is IrcClientState.Failed -> stringResource(R.string.network_settings_status_failed, connState.reason)
}

@Composable
private fun FailedSection(
    error: String?,
    onEditForm: () -> Unit,
    onSaveAnyway: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.add_network_failed, error.orEmpty()),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onEditForm) { Text(stringResource(R.string.add_network_edit)) }
            TextButton(onClick = onSaveAnyway) { Text(stringResource(R.string.add_network_save_anyway)) }
            Button(onClick = onRetry) { Text(stringResource(R.string.add_network_retry)) }
        }
    }
}

@Preview
@Composable
private fun AddNetworkFormPreview() {
    MotdTheme {
        AddNetworkContent(
            state = AddNetworkUiState(
                server = ServerForm(host = "irc.libera.chat", port = "6697", nick = "me"),
            ),
            onBack = {}, onSetKind = {}, onServerChange = {}, onAuthChange = {},
            onSubmit = {}, onRetry = {}, onSaveAnyway = {}, onEditForm = {}, onAbandon = {},
        )
    }
}

@Preview
@Composable
private fun AddNetworkFailedPreview() {
    MotdTheme {
        AddNetworkContent(
            state = AddNetworkUiState(
                server = ServerForm(host = "irc.libera.chat", port = "6697", nick = "me"),
                phase = AddNetworkPhase.FAILED,
                error = "SASL authentication failed",
            ),
            onBack = {}, onSetKind = {}, onServerChange = {}, onAuthChange = {},
            onSubmit = {}, onRetry = {}, onSaveAnyway = {}, onEditForm = {}, onAbandon = {},
        )
    }
}
