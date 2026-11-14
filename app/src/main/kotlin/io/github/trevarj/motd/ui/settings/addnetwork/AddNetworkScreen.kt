package io.github.trevarj.motd.ui.settings.addnetwork

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.bouncer.BouncerKind
import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.bouncer.ZncLoginForm
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.ConnectionChoice
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.settings.BouncerLoginFields
import io.github.trevarj.motd.ui.settings.NetworkForm
import io.github.trevarj.motd.ui.settings.RadioRow
import io.github.trevarj.motd.ui.settings.SettingsGroup
import io.github.trevarj.motd.ui.settings.SettingsScaffold
import io.github.trevarj.motd.ui.settings.SubLabel
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

/** Stateful entry: wires the ViewModel and drives navigation. */
@Composable
fun AddNetworkScreen(
    onBack: () -> Unit = {},
    onOpenBouncerNetworks: (Long, Boolean) -> Unit = { _, _ -> },
    onScanInvite: () -> Unit = {},
    viewModel: AddNetworkViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val openBouncerNetworks = { rootId: Long ->
        onOpenBouncerNetworks(rootId, viewModel.state.value.provisionalCreated)
    }
    AddNetworkContent(
        state = state,
        onBack = onBack,
        onSetKind = viewModel::setKind,
        onSetBouncerKind = viewModel::setBouncerKind,
        onSelectPreset = viewModel::selectPreset,
        onDisplayNameChange = viewModel::editDisplayName,
        onServerChange = viewModel::editServer,
        onAuthChange = viewModel::editAuth,
        onSojuLoginChange = viewModel::editSojuLogin,
        onZncLoginChange = viewModel::editZncLogin,
        onSubmit = { viewModel.submit(openBouncerNetworks, onBack) },
        onRetry = { viewModel.retry(openBouncerNetworks, onBack) },
        onSaveAnyway = { viewModel.saveAnyway(onBack) },
        onEditForm = viewModel::editForm,
        onAbandon = { viewModel.abandon(onBack) },
        onConfirmPlaintext = { viewModel.confirmPlaintext(openBouncerNetworks, onBack) },
        onDismissPlaintext = viewModel::dismissPlaintextWarning,
        onScanInvite = onScanInvite,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNetworkContent(
    state: AddNetworkUiState,
    onBack: () -> Unit,
    onSetKind: (ConnectionChoice) -> Unit,
    onSetBouncerKind: (BouncerKind) -> Unit,
    onSelectPreset: (NetworkPresetId) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    onSojuLoginChange: (SojuLoginForm) -> Unit,
    onZncLoginChange: (ZncLoginForm) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onSaveAnyway: () -> Unit,
    onEditForm: () -> Unit,
    onAbandon: () -> Unit,
    onConfirmPlaintext: () -> Unit,
    onDismissPlaintext: () -> Unit,
    onScanInvite: () -> Unit = {},
) {
    // During TESTING/FAILED a row already exists; back-press must delete it first.
    val hasHalfCreated = state.phase != AddNetworkPhase.FORM
    BackHandler(enabled = hasHalfCreated) { onAbandon() }

    SettingsScaffold(
        title = stringResource(R.string.add_network_title),
        onBack = { if (hasHalfCreated) onAbandon() else onBack() },
    ) {
        FilledTonalButton(
            onClick = onScanInvite,
            modifier = Modifier.fillMaxWidth().testTag("add_network_scan_invite"),
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
            Text(stringResource(R.string.invite_scan_title), modifier = Modifier.padding(start = 8.dp))
        }
        // The type group and the preset picker share one Column child so the collapsed
        // picker sits outside the spacedBy flow; its 12dp gap lives inside the animation.
        Column {
            SettingsGroup(title = stringResource(R.string.add_network_type_section)) {
                KindSelector(kind = state.kind, enabled = state.phase == AddNetworkPhase.FORM, onSetKind = onSetKind)
                AnimatedVisibility(
                    visible = state.isBouncer,
                    enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
                    exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
                ) {
                    BouncerKindSelector(
                        kind = state.bouncerKind,
                        enabled = state.phase == AddNetworkPhase.FORM,
                        onSetKind = onSetBouncerKind,
                    )
                }
            }
            AnimatedVisibility(
                visible = !state.isBouncer && state.phase == AddNetworkPhase.FORM,
                enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
                exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
            ) {
                Box(Modifier.padding(top = 12.dp)) {
                    NetworkPresetPicker(selected = state.presetId, onSelect = onSelectPreset)
                }
            }
        }
        SettingsGroup(title = stringResource(R.string.add_network_details_section)) {
            // ZNC already collects its own per-bouncer network name (zncLogin.network,
            // required for auth); this field only needs to exist for DIRECT/soju, which
            // otherwise fall back to the preset name or bare host until edited later.
            if (!state.isZnc) {
                OutlinedTextField(
                    value = state.displayName,
                    onValueChange = onDisplayNameChange,
                    label = { Text(stringResource(R.string.network_settings_display_name)) },
                    placeholder = {
                        Text(
                            networkPreset(state.presetId)?.displayName
                                ?: state.server.host.ifBlank { stringResource(R.string.add_network_kind_network) },
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("add_network_display_name"),
                )
            }
            // Ease the height between the two form variants; the branch swap itself (and
            // its field state reset) is unchanged from today.
            Box(Modifier.animateContentSize(animationSpec = MotdMotion.contentSize)) {
                if (state.isBouncer) {
                    Column(Modifier.padding(16.dp)) {
                        BouncerLoginFields(
                            kind = state.bouncerKind,
                            server = state.server,
                            sojuLogin = state.sojuLogin,
                            zncLogin = state.zncLogin,
                            onServerChange = onServerChange,
                            onSojuLoginChange = onSojuLoginChange,
                            onZncLoginChange = onZncLoginChange,
                        )
                    }
                } else {
                    NetworkForm(
                        server = state.server,
                        auth = state.auth,
                        onServerChange = onServerChange,
                        onAuthChange = onAuthChange,
                        modifier = Modifier.padding(vertical = 16.dp),
                        preset = networkPreset(state.presetId),
                    )
                }
            }
        }
        if (state.duplicateConnection) {
            Text(
                text = stringResource(R.string.add_network_duplicate),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("add_network_duplicate"),
            )
        }
        Button(
            onClick = onSubmit,
            enabled = state.canSubmit,
            modifier = Modifier.fillMaxWidth().testTag("connect_save_button"),
        ) { Text(stringResource(R.string.add_network_connect_save)) }

        when (state.phase) {
            AddNetworkPhase.TESTING -> {
                TestingRow(state.connState)
            }

            AddNetworkPhase.FAILED -> {
                FailedSection(
                    error = state.error,
                    onEditForm = onEditForm,
                    onSaveAnyway = onSaveAnyway,
                    onRetry = onRetry,
                )
            }

            AddNetworkPhase.FORM -> {}
        }
    }

    if (state.showPlaintextWarning) {
        AlertDialog(
            modifier = Modifier.testTag("plaintext_network_warning"),
            onDismissRequest = onDismissPlaintext,
            title = { Text(stringResource(R.string.add_network_plaintext_title)) },
            text = { Text(stringResource(R.string.add_network_plaintext_message)) },
            confirmButton = {
                Button(onClick = onConfirmPlaintext) {
                    Text(stringResource(R.string.add_network_plaintext_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissPlaintext) { Text(stringResource(R.string.dialog_cancel)) }
            },
        )
    }
}

@Composable
internal fun NetworkPresetPicker(
    selected: NetworkPresetId,
    onSelect: (NetworkPresetId) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.add_network_preset_section)) {
        RadioRow(
            label = stringResource(R.string.add_network_preset_custom),
            subtitle = stringResource(R.string.add_network_preset_custom_desc),
            selected = selected == NetworkPresetId.CUSTOM,
            enabled = true,
            onClick = { onSelect(NetworkPresetId.CUSTOM) },
        )
        SubLabel(stringResource(R.string.add_network_preset_secure))
        COMMON_NETWORK_PRESETS.filterNot(NetworkPreset::legacyUnencrypted).forEach { preset ->
            val endpoint = stringResource(R.string.add_network_preset_tls_endpoint, preset.host, preset.port)
            RadioRow(
                label = preset.displayName,
                subtitle = endpoint,
                selected = selected == preset.id,
                enabled = true,
                onClick = { onSelect(preset.id) },
            )
        }
        SubLabel(stringResource(R.string.add_network_preset_legacy))
        COMMON_NETWORK_PRESETS.filter(NetworkPreset::legacyUnencrypted).forEach { preset ->
            RadioRow(
                label = preset.displayName,
                subtitle =
                    stringResource(
                        R.string.add_network_preset_plain_endpoint,
                        preset.host,
                        preset.port,
                        stringResource(R.string.add_network_preset_unencrypted),
                    ),
                selected = selected == preset.id,
                enabled = true,
                onClick = { onSelect(preset.id) },
            )
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
    val options =
        listOf(
            ConnectionChoice.NETWORK to R.string.add_network_kind_network,
            ConnectionChoice.BOUNCER to R.string.add_network_kind_bouncer,
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
                modifier = Modifier.testTag("add_network_kind_${choice.name.lowercase()}"),
            ) {
                Text(stringResource(labelRes))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BouncerKindSelector(
    kind: BouncerKind,
    enabled: Boolean,
    onSetKind: (BouncerKind) -> Unit,
) {
    val options =
        listOf(
            BouncerKind.SOJU to R.string.bouncer_kind_soju,
            BouncerKind.ZNC to R.string.bouncer_kind_znc,
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
                modifier = Modifier.testTag("add_network_bouncer_${choice.name.lowercase()}"),
            ) { Text(stringResource(labelRes)) }
        }
    }
}

@Composable
private fun TestingRow(connState: IrcClientState?) {
    ListItem(
        colors =
            androidx.compose.material3.ListItemDefaults
                .colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        headlineContent = { Text(stringResource(R.string.add_network_testing)) },
        supportingContent = { Text(connStateLabel(connState)) },
        leadingContent = { CircularProgressIndicator(modifier = Modifier.size(24.dp)) },
    )
}

@Composable
private fun connStateLabel(connState: IrcClientState?): String =
    when (connState) {
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
            state =
                AddNetworkUiState(
                    server = ServerForm(host = "irc.libera.chat", port = "6697", nick = "me"),
                ),
            onBack = {},
            onSetKind = {},
            onSetBouncerKind = {},
            onSelectPreset = {},
            onDisplayNameChange = {},
            onServerChange = {},
            onAuthChange = {},
            onSojuLoginChange = {},
            onZncLoginChange = {},
            onSubmit = {},
            onRetry = {},
            onSaveAnyway = {},
            onEditForm = {},
            onAbandon = {},
            onConfirmPlaintext = {},
            onDismissPlaintext = {},
        )
    }
}

@Preview
@Composable
private fun AddNetworkFailedPreview() {
    MotdTheme {
        AddNetworkContent(
            state =
                AddNetworkUiState(
                    server = ServerForm(host = "irc.libera.chat", port = "6697", nick = "me"),
                    phase = AddNetworkPhase.FAILED,
                    error = "SASL authentication failed",
                ),
            onBack = {},
            onSetKind = {},
            onSetBouncerKind = {},
            onSelectPreset = {},
            onDisplayNameChange = {},
            onServerChange = {},
            onAuthChange = {},
            onSojuLoginChange = {},
            onZncLoginChange = {},
            onSubmit = {},
            onRetry = {},
            onSaveAnyway = {},
            onEditForm = {},
            onAbandon = {},
            onConfirmPlaintext = {},
            onDismissPlaintext = {},
        )
    }
}
