package io.github.trevarj.motd.ui.settings.bouncer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.bouncer.ChannelCommandFields

@Composable
fun ChannelsPanel(
    state: BouncerNetworksUiState,
    connected: Boolean,
    callbacks: BouncerControlCallbacks,
) {
    var network by remember(state.rows) { mutableStateOf(state.rows.firstOrNull()?.name.orEmpty()) }
    var channel by remember { mutableStateOf("") }
    var detached by remember { mutableStateOf<Boolean?>(null) }
    var relayDetached by remember { mutableStateOf("") }
    var reattachOn by remember { mutableStateOf("") }
    var detachAfter by remember { mutableStateOf("") }
    var detachOn by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val canMutate = connected && state.capabilities.verified && !state.commandBusy
    val fields = {
        ChannelCommandFields(
            detached = detached,
            relayDetached = relayDetached.trim().takeIf(String::isNotBlank),
            reattachOn = reattachOn.trim().takeIf(String::isNotBlank),
            detachAfter = detachAfter.trim().takeIf(String::isNotBlank),
            detachOn = detachOn.trim().takeIf(String::isNotBlank),
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("bouncer_channels_panel"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PanelCard(stringResource(R.string.bouncer_channels_configured)) {
                Text(
                    stringResource(R.string.bouncer_channels_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionWrap {
                    OutlinedButton(
                        onClick = { callbacks.onChannelStatus(network.trim()) },
                        enabled = connected && network.isNotBlank() &&
                            state.capabilities.supports("channel status") && !state.commandBusy,
                    ) { Text(stringResource(R.string.action_refresh)) }
                }
                if (state.channels.isEmpty()) {
                    Text(stringResource(R.string.bouncer_channels_empty))
                } else {
                    state.channels.forEach { row ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(row.name, fontWeight = FontWeight.Medium)
                                Text(
                                    if (row.detached) "${row.status} · ${stringResource(R.string.bouncer_channel_detached)}"
                                    else row.status,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            PanelCard(stringResource(R.string.bouncer_channel_editor)) {
                OutlinedTextField(
                    value = network,
                    onValueChange = { network = it },
                    label = { Text(stringResource(R.string.bouncer_tab_networks)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = channel,
                    onValueChange = { channel = it },
                    label = { Text(stringResource(R.string.new_sheet_channel_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TriStateField(
                    label = stringResource(R.string.bouncer_channel_detached),
                    value = detached,
                    onValueChange = { detached = it },
                )
                FilterField(
                    value = relayDetached,
                    onValueChange = { relayDetached = it },
                    label = stringResource(R.string.bouncer_channel_relay_detached),
                )
                FilterField(
                    value = reattachOn,
                    onValueChange = { reattachOn = it },
                    label = stringResource(R.string.bouncer_channel_reattach_on),
                )
                OutlinedTextField(
                    value = detachAfter,
                    onValueChange = { detachAfter = it },
                    label = { Text(stringResource(R.string.bouncer_channel_detach_after)) },
                    supportingText = { Text(stringResource(R.string.bouncer_channel_duration_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FilterField(
                    value = detachOn,
                    onValueChange = { detachOn = it },
                    label = stringResource(R.string.bouncer_channel_detach_on),
                )
                ActionWrap {
                    Button(
                        onClick = { callbacks.onCreateChannel(channel.trim(), network.trim(), fields()) },
                        enabled = canMutate && channel.isNotBlank() && network.isNotBlank() &&
                            state.capabilities.supports("channel create"),
                    ) { Text(stringResource(R.string.bouncer_add)) }
                    OutlinedButton(
                        onClick = { callbacks.onUpdateChannel(channel.trim(), network.trim(), fields()) },
                        enabled = canMutate && channel.isNotBlank() && network.isNotBlank() &&
                            state.capabilities.supports("channel update"),
                    ) { Text(stringResource(R.string.action_save)) }
                    TextButton(
                        onClick = { confirmDelete = true },
                        enabled = canMutate && channel.isNotBlank() && network.isNotBlank() &&
                            state.capabilities.supports("channel delete"),
                    ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.bouncer_channel_delete_title)) },
            text = { Text(stringResource(R.string.bouncer_channel_delete_message, channel, network)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    callbacks.onDeleteChannel(channel.trim(), network.trim())
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
fun AccountPanel(
    state: BouncerNetworksUiState,
    connected: Boolean,
    callbacks: BouncerControlCallbacks,
) {
    var nick by remember { mutableStateOf("") }
    var realName by remember { mutableStateOf("") }
    var network by remember(state.rows) { mutableStateOf(state.rows.firstOrNull()?.name.orEmpty()) }
    var keyType by remember { mutableStateOf("ed25519") }
    var showSasl by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmCert by remember { mutableStateOf(false) }
    val enabled = connected && state.capabilities.verified && !state.commandBusy
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("bouncer_account_panel"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PanelCard(stringResource(R.string.bouncer_account_identity)) {
                Text(
                    stringResource(R.string.bouncer_account_identity_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = nick,
                    onValueChange = { nick = it },
                    label = { Text(stringResource(R.string.bouncer_field_nick)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = realName,
                    onValueChange = { realName = it },
                    label = { Text(stringResource(R.string.onboarding_field_realname)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        callbacks.onUpdateAccount(
                            nick.trim().takeIf(String::isNotBlank),
                            realName.trim().takeIf(String::isNotBlank),
                        )
                    },
                    enabled = enabled && (nick.isNotBlank() || realName.isNotBlank()) &&
                        state.capabilities.supports("user update"),
                ) { Text(stringResource(R.string.action_save)) }
            }
        }
        item {
            PanelCard(stringResource(R.string.bouncer_account_upstream_auth)) {
                OutlinedTextField(
                    value = network,
                    onValueChange = { network = it },
                    label = { Text(stringResource(R.string.bouncer_tab_networks)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionWrap {
                    OutlinedButton(
                        onClick = { callbacks.onSaslStatus(network.trim()) },
                        enabled = enabled && network.isNotBlank() && state.capabilities.supports("sasl status"),
                    ) { Text(stringResource(R.string.bouncer_status)) }
                    Button(
                        onClick = { showSasl = true },
                        enabled = enabled && network.isNotBlank() && state.capabilities.supports("sasl set-plain"),
                    ) { Text(stringResource(R.string.bouncer_sasl_set)) }
                    TextButton(
                        onClick = { confirmReset = true },
                        enabled = enabled && network.isNotBlank() && state.capabilities.supports("sasl reset"),
                    ) { Text(stringResource(R.string.bouncer_reset)) }
                }
                Text(
                    stringResource(R.string.bouncer_certfp_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionWrap {
                    listOf("ed25519", "ecdsa", "rsa").forEach { type ->
                        FilterChip(selected = keyType == type, onClick = { keyType = type }, label = { Text(type) })
                    }
                }
                ActionWrap {
                    Button(
                        onClick = { confirmCert = true },
                        enabled = enabled && network.isNotBlank() && state.capabilities.supports("certfp generate"),
                    ) { Text(stringResource(R.string.bouncer_certfp_generate)) }
                    OutlinedButton(
                        onClick = { callbacks.onShowCertFp(network.trim()) },
                        enabled = enabled && network.isNotBlank() && state.capabilities.supports("certfp fingerprint"),
                    ) { Text(stringResource(R.string.bouncer_certfp_show)) }
                }
            }
        }
    }
    if (showSasl) {
        SaslPlainDialog(
            network = network,
            onDismiss = { showSasl = false },
            onSubmit = { username, password ->
                showSasl = false
                callbacks.onSetSaslPlain(network.trim(), username, password)
            },
        )
    }
    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.bouncer_sasl_reset_title),
            message = stringResource(R.string.bouncer_sasl_reset_message, network),
            destructive = true,
            onDismiss = { confirmReset = false },
            onConfirm = { confirmReset = false; callbacks.onResetSasl(network.trim()) },
        )
    }
    if (confirmCert) {
        ConfirmDialog(
            title = stringResource(R.string.bouncer_certfp_generate_title),
            message = stringResource(R.string.bouncer_certfp_generate_message, network),
            destructive = false,
            onDismiss = { confirmCert = false },
            onConfirm = {
                confirmCert = false
                callbacks.onGenerateCertFp(network.trim(), keyType)
            },
        )
    }
}

@Composable
fun AdminPanel(
    state: BouncerNetworksUiState,
    connected: Boolean,
    callbacks: BouncerControlCallbacks,
) {
    var dialog by remember { mutableStateOf<AdminDialog?>(null) }
    var debugEnableWarning by remember { mutableStateOf(false) }
    val enabled = connected && state.capabilities.administrator && !state.commandBusy
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("bouncer_admin_panel"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PanelCard(stringResource(R.string.bouncer_admin_users)) {
                Text(
                    stringResource(R.string.bouncer_admin_users_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionWrap {
                    OutlinedButton(
                        onClick = { callbacks.onUserStatus(null) },
                        enabled = enabled && state.capabilities.supports("user status"),
                    ) { Text(stringResource(R.string.bouncer_status)) }
                    Button(
                        onClick = { dialog = AdminDialog.CREATE_USER },
                        enabled = enabled && state.capabilities.supports("user create"),
                    ) { Text(stringResource(R.string.bouncer_user_create)) }
                    OutlinedButton(
                        onClick = { dialog = AdminDialog.UPDATE_USER },
                        enabled = enabled && state.capabilities.supports("user update"),
                    ) { Text(stringResource(R.string.action_edit)) }
                    TextButton(
                        onClick = { dialog = AdminDialog.DELETE_USER },
                        enabled = enabled && state.capabilities.supports("user delete"),
                    ) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(
                        onClick = { dialog = AdminDialog.RUN_AS_USER },
                        enabled = enabled && state.capabilities.supports("user run"),
                    ) { Text(stringResource(R.string.bouncer_user_run)) }
                }
            }
        }
        item {
            PanelCard(stringResource(R.string.bouncer_admin_server)) {
                Text(
                    stringResource(R.string.bouncer_admin_server_help),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ActionWrap {
                    OutlinedButton(
                        onClick = callbacks.onServerStatus,
                        enabled = enabled && state.capabilities.supports("server status"),
                    ) { Text(stringResource(R.string.bouncer_status)) }
                    Button(
                        onClick = { dialog = AdminDialog.SERVER_NOTICE },
                        enabled = enabled && state.capabilities.supports("server notice"),
                    ) { Text(stringResource(R.string.bouncer_server_notice)) }
                    Button(
                        onClick = { debugEnableWarning = true },
                        enabled = enabled && state.capabilities.supports("server debug"),
                    ) { Text(stringResource(R.string.bouncer_debug_enable)) }
                    OutlinedButton(
                        onClick = { callbacks.onServerDebug(false) },
                        enabled = enabled && state.capabilities.supports("server debug"),
                    ) { Text(stringResource(R.string.bouncer_debug_disable)) }
                }
            }
        }
    }
    when (dialog) {
        AdminDialog.CREATE_USER -> UserCreateDialog(
            onDismiss = { dialog = null },
            onSubmit = { username, password, admin, userEnabled ->
                dialog = null
                callbacks.onCreateUser(username, password, admin, userEnabled)
            },
        )
        AdminDialog.UPDATE_USER -> UserUpdateDialog(
            currentUsername = state.root?.username.orEmpty(),
            administrator = true,
            onDismiss = { dialog = null },
            onSubmit = { username, fields -> dialog = null; callbacks.onUpdateUser(username, fields) },
        )
        AdminDialog.DELETE_USER -> TypedUserDeleteDialog(
            onDismiss = { dialog = null },
            onSubmit = { username -> dialog = null; callbacks.onRequestUserDeletion(username) },
        )
        AdminDialog.RUN_AS_USER -> TextCommandDialog(
            title = stringResource(R.string.bouncer_user_run_title),
            firstLabel = stringResource(R.string.onboarding_field_username),
            secondLabel = stringResource(R.string.bouncer_console_command),
            onDismiss = { dialog = null },
            onSubmit = { username, command -> dialog = null; callbacks.onRunAsUser(username, command) },
        )
        AdminDialog.SERVER_NOTICE -> TextCommandDialog(
            title = stringResource(R.string.bouncer_server_notice_title),
            firstLabel = stringResource(R.string.bouncer_server_notice_message),
            warning = stringResource(R.string.bouncer_server_notice_warning),
            onDismiss = { dialog = null },
            onSubmit = { message, _ -> dialog = null; callbacks.onServerNotice(message) },
        )
        null -> Unit
    }
    if (debugEnableWarning) {
        ConfirmDialog(
            title = stringResource(R.string.bouncer_debug_enable_title),
            message = stringResource(R.string.bouncer_debug_enable_warning),
            destructive = true,
            onDismiss = { debugEnableWarning = false },
            onConfirm = { debugEnableWarning = false; callbacks.onServerDebug(true) },
        )
    }
}

@Composable
fun ConsolePanel(
    state: BouncerNetworksUiState,
    connected: Boolean,
    callbacks: BouncerControlCallbacks,
) {
    var command by remember { mutableStateOf("") }
    val suggestions = remember(state.capabilities.commandPaths, command) {
        bouncerCommandSuggestions(state.capabilities.commandPaths, command)
    }
    Column(Modifier.fillMaxSize().testTag("bouncer_console_panel")) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.transcript.isEmpty()) {
                item { Text(stringResource(R.string.bouncer_console_empty)) }
            }
            items(state.transcript, key = { "${it.serverTime}:${it.sender}:${it.text}" }) { entry ->
                Surface(
                    color = if (entry.isSelf) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            entry.sender,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(entry.text, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        if (suggestions.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.take(3).forEach { suggestion ->
                    FilterChip(
                        selected = false,
                        onClick = { command = suggestion },
                        label = { Text(suggestion, maxLines = 1) },
                    )
                }
            }
        }
        OutlinedTextField(
            value = command,
            onValueChange = { if ('\r' !in it && '\n' !in it) command = it },
            label = { Text(stringResource(R.string.bouncer_console_command)) },
            supportingText = {
                Text(
                    if (state.capabilities.verified) stringResource(R.string.bouncer_console_help)
                    else stringResource(R.string.bouncer_console_unverified),
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = { callbacks.onSubmitConsole(command); command = "" },
                    enabled = connected && command.isNotBlank() && !state.commandBusy,
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_send))
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = {
                if (connected && command.isNotBlank() && !state.commandBusy) {
                    callbacks.onSubmitConsole(command)
                    command = ""
                }
            }),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("bouncer_console_input"),
        )
    }
}

@Composable
private fun PanelCard(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun FilterField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(stringResource(R.string.bouncer_channel_filter_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ActionWrap(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    destructive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.action_continue),
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private enum class AdminDialog { CREATE_USER, UPDATE_USER, DELETE_USER, RUN_AS_USER, SERVER_NOTICE }
