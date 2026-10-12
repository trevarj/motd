package io.github.trevarj.motd.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.ui.components.MuteBacklogUndoEffect
import io.github.trevarj.motd.ui.components.ReasonPresetChips
import io.github.trevarj.motd.ui.theme.MotdMotion

@Composable
fun NetworkToolsScreen(
    networkId: Long,
    onBack: () -> Unit = {},
    viewModel: NetworkToolsViewModel = hiltViewModel(),
) {
    LaunchedEffect(networkId) { viewModel.init(networkId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    MuteBacklogUndoEffect(
        suppressions = viewModel.muteBacklogSuppressions,
        hostState = snackbarHostState,
        onUndo = viewModel::undoMuteBacklogSuppression,
    )
    NetworkToolsContent(
        state = state,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        onAddIgnore = viewModel::addIgnore,
        onSetIgnoreEnabled = viewModel::setIgnoreEnabled,
        onDeleteIgnore = viewModel::deleteIgnore,
        onSetMuted = viewModel::setMuted,
        onSendCommand = { viewModel.send(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkToolsContent(
    state: NetworkToolsUiState,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onAddIgnore: (String) -> Unit = {},
    onSetIgnoreEnabled: (Long, Boolean) -> Unit = { _, _ -> },
    onDeleteIgnore: (Long) -> Unit = {},
    onSetMuted: (Long, Boolean) -> Unit = { _, _ -> },
    onSendCommand: (IrcMessage) -> Unit = {},
) {
    SettingsScaffold(
        title = stringResource(R.string.network_tools_title),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        modifier = Modifier.testTag("screen_network_tools"),
    ) {
        state.status?.let { status ->
            val text = networkToolsStatusText(status)
            PersistentStatusNotice(
                text = stringResource(R.string.network_tools_status, stringResource(text.resId, *text.args)),
                error = status is NetworkToolsStatus.IgnoreFailed || status is NetworkToolsStatus.CommandFailed,
                modifier = Modifier.testTag("network_tools_status"),
            )
        }
        IgnoreSection(state, onAddIgnore, onSetIgnoreEnabled, onDeleteIgnore)
        MuteSection(state, onSetMuted)
        OperatorSection(state, onSendCommand)
    }
}

@Composable
private fun IgnoreSection(
    state: NetworkToolsUiState,
    onAddIgnore: (String) -> Unit,
    onSetIgnoreEnabled: (Long, Boolean) -> Unit,
    onDeleteIgnore: (Long) -> Unit,
) {
    var pattern by remember { mutableStateOf("") }
    SettingsGroup(title = stringResource(R.string.network_tools_privacy_section)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = pattern,
                onValueChange = { pattern = it },
                label = { Text(stringResource(R.string.network_tools_ignore_hint)) },
                supportingText = { Text(stringResource(R.string.network_tools_ignore_help)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().testTag("network_tools_ignore_input"),
            )
            Button(
                onClick = {
                    onAddIgnore(pattern)
                    pattern = ""
                },
                enabled = pattern.isNotBlank(),
                modifier = Modifier.testTag("network_tools_add_ignore"),
            ) { Text(stringResource(R.string.network_tools_add_ignore)) }
        }
        if (state.ignores.isEmpty()) {
            Text(
                text = stringResource(R.string.network_tools_ignores_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            state.ignores.forEach { ignore ->
                ListItem(
                    headlineContent = { Text(ignore.pattern) },
                    supportingContent = {
                        Text(stringResource(if (ignore.enabled) R.string.network_tools_enabled else R.string.network_tools_disabled))
                    },
                    trailingContent = {
                        Row {
                            Switch(
                                checked = ignore.enabled,
                                onCheckedChange = { onSetIgnoreEnabled(ignore.id, it) },
                            )
                            IconButton(onClick = { onDeleteIgnore(ignore.id) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun MuteSection(
    state: NetworkToolsUiState,
    onSetMuted: (Long, Boolean) -> Unit,
) {
    SettingsGroup(title = stringResource(R.string.network_tools_mutes_section)) {
        if (state.buffers.isEmpty()) {
            Text(
                text = stringResource(R.string.network_tools_mutes_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            state.buffers.forEach { row ->
                ListItem(
                    headlineContent = { Text(row.displayName) },
                    supportingContent = {
                        Text(
                            when (row.type) {
                                BufferType.CHANNEL -> stringResource(R.string.channelinfo_title)
                                BufferType.QUERY -> stringResource(R.string.nick_sheet_message)
                                BufferType.SERVER -> stringResource(R.string.network_settings_server_messages)
                            },
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = row.muted,
                            onCheckedChange = { onSetMuted(row.bufferId, it) },
                            modifier = Modifier.testTag("network_tools_mute_${row.bufferId}"),
                        )
                    },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * IRCop commands, collapsed by default. RPL_YOUREOPER (381) is not tracked anywhere, so the app
 * cannot tell whether the viewer actually holds server-operator privileges; hiding the controls
 * behind one deliberate tap is the honest substitute for a privilege gate we cannot implement.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun OperatorSection(
    state: NetworkToolsUiState,
    onSendCommand: (IrcMessage) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var operUser by remember { mutableStateOf("") }
    var operPassword by remember { mutableStateOf("") }
    var modeTarget by remember { mutableStateOf("") }
    var modeTargetExpanded by remember { mutableStateOf(false) }
    var modes by remember { mutableStateOf("") }
    var modeArgs by remember { mutableStateOf("") }
    var killNick by remember { mutableStateOf("") }
    // KILL and SQUIT own separate reasons: sharing one made SQUIT's enablement depend on text
    // typed for an unrelated kill.
    var killReason by remember { mutableStateOf("") }
    var squitReason by remember { mutableStateOf("") }
    var rehashServer by remember { mutableStateOf("") }
    var connectServer by remember { mutableStateOf("") }
    var connectPort by remember { mutableStateOf("") }
    var connectRemote by remember { mutableStateOf("") }
    var squitServer by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<PendingOperatorCommand?>(null) }
    val enabled = state.connected

    SettingsGroup(title = stringResource(R.string.network_tools_operator_section)) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.network_tools_ircop_header)) },
            supportingContent = { Text(stringResource(R.string.network_tools_ircop_desc)) },
            trailingContent = {
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            },
            modifier =
                Modifier
                    .clickable { expanded = !expanded }
                    .testTag("network_tools_ircop_expand"),
        )
        // Eased expand/collapse instead of the previous hard conditional; the content composes as
        // the expansion starts, so scroll-to targets inside it resolve immediately.
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
            exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text =
                        if (enabled) {
                            stringResource(R.string.network_tools_operator_help)
                        } else {
                            stringResource(R.string.network_tools_disconnected)
                        },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = operUser,
                    onValueChange = { operUser = it },
                    label = { Text(stringResource(R.string.network_tools_oper_user)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("network_tools_oper_user"),
                )
                OutlinedTextField(
                    value = operPassword,
                    onValueChange = { operPassword = it },
                    label = { Text(stringResource(R.string.network_tools_oper_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().testTag("network_tools_oper_password"),
                )
                Button(
                    onClick = {
                        onSendCommand(operMessage(operUser, operPassword))
                        operPassword = ""
                    },
                    enabled = enabled && operUser.isNotBlank() && operPassword.isNotBlank(),
                    modifier = Modifier.testTag("network_tools_oper_send"),
                ) { Text(stringResource(R.string.network_tools_send_oper)) }

                HorizontalDivider()
                // Editable suggestions: own nick first, then this network's channels. MODE targets that
                // are neither (a service, another user) stay typable.
                ExposedDropdownMenuBox(
                    expanded = modeTargetExpanded,
                    onExpandedChange = { modeTargetExpanded = it },
                ) {
                    OutlinedTextField(
                        value = modeTarget,
                        onValueChange = { modeTarget = it },
                        label = { Text(stringResource(R.string.network_tools_target)) },
                        singleLine = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modeTargetExpanded) },
                        modifier =
                            Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                                .fillMaxWidth()
                                .testTag("network_tools_mode_target"),
                    )
                    ExposedDropdownMenu(
                        expanded = modeTargetExpanded,
                        onDismissRequest = { modeTargetExpanded = false },
                    ) {
                        state.selfNick?.let { nick ->
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.network_tools_target_self, nick)) },
                                onClick = {
                                    modeTarget = nick
                                    modeTargetExpanded = false
                                },
                            )
                        }
                        state.buffers.filter { it.type == BufferType.CHANNEL }.forEach { row ->
                            DropdownMenuItem(
                                text = { Text(row.displayName) },
                                onClick = {
                                    modeTarget = row.displayName
                                    modeTargetExpanded = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = modes,
                    onValueChange = { modes = it },
                    label = { Text(stringResource(R.string.network_tools_modes)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("network_tools_mode_letters"),
                )
                OutlinedTextField(
                    value = modeArgs,
                    onValueChange = { modeArgs = it },
                    label = { Text(stringResource(R.string.network_tools_mode_args)) },
                    supportingText = { Text(stringResource(R.string.network_tools_mode_help)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("network_tools_mode_args"),
                )
                OutlinedButton(
                    onClick = { onSendCommand(modeMessage(modeTarget, modes, modeArgs)) },
                    enabled = enabled && modeTarget.isNotBlank() && modes.isNotBlank(),
                    modifier = Modifier.testTag("network_tools_mode_send"),
                ) { Text(stringResource(R.string.network_tools_send_mode)) }

                HorizontalDivider()
                OutlinedTextField(
                    value = killNick,
                    onValueChange = { killNick = it },
                    label = { Text(stringResource(R.string.network_tools_kill_nick)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("network_tools_kill_nick"),
                )
                ReasonPresetChips(
                    current = killReason,
                    onSelect = { killReason = it },
                    tagPrefix = "network_tools_kill_chip",
                )
                ReasonField(
                    value = killReason,
                    onValueChange = { killReason = it },
                    label = stringResource(R.string.network_tools_kill_reason),
                    tag = "network_tools_kill_reason",
                )
                DestructiveButton(
                    label = stringResource(R.string.network_tools_send_kill),
                    enabled = enabled && killNick.isNotBlank() && killReason.isNotBlank(),
                    tag = "network_tools_kill_send",
                    command = {
                        PendingOperatorCommand(
                            kind = OperatorCommandKind.KILL,
                            target = killNick.trim(),
                            message = killMessage(killNick, killReason),
                        )
                    },
                    onConfirm = { pending = it },
                )

                HorizontalDivider()
                OutlinedTextField(
                    value = rehashServer,
                    onValueChange = { rehashServer = it },
                    label = { Text(stringResource(R.string.network_tools_rehash_server)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DestructiveButton(
                    label = stringResource(R.string.network_tools_send_rehash),
                    enabled = enabled,
                    tag = "network_tools_rehash_send",
                    command = {
                        PendingOperatorCommand(
                            kind = OperatorCommandKind.REHASH,
                            target = rehashServer.trim().ifBlank { state.network?.name.orEmpty() },
                            message = rehashMessage(rehashServer),
                        )
                    },
                    onConfirm = { pending = it },
                )

                HorizontalDivider()
                OutlinedTextField(
                    value = connectServer,
                    onValueChange = { connectServer = it },
                    label = { Text(stringResource(R.string.network_tools_connect_server)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = connectPort,
                    onValueChange = { connectPort = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.network_tools_connect_port)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = connectRemote,
                    onValueChange = { connectRemote = it },
                    label = { Text(stringResource(R.string.network_tools_connect_remote)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DestructiveButton(
                    label = stringResource(R.string.network_tools_send_connect),
                    enabled = enabled && connectServer.isNotBlank(),
                    tag = "network_tools_connect_send",
                    command = {
                        PendingOperatorCommand(
                            kind = OperatorCommandKind.CONNECT,
                            target = connectServer.trim(),
                            message = connectMessage(connectServer, connectPort, connectRemote),
                        )
                    },
                    onConfirm = { pending = it },
                )

                HorizontalDivider()
                OutlinedTextField(
                    value = squitServer,
                    onValueChange = { squitServer = it },
                    label = { Text(stringResource(R.string.network_tools_squit_server)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("network_tools_squit_server"),
                )
                ReasonField(
                    value = squitReason,
                    onValueChange = { squitReason = it },
                    label = stringResource(R.string.network_tools_squit_reason),
                    tag = "network_tools_squit_reason",
                )
                DestructiveButton(
                    label = stringResource(R.string.network_tools_send_squit),
                    enabled = enabled && squitServer.isNotBlank() && squitReason.isNotBlank(),
                    tag = "network_tools_squit_send",
                    command = {
                        PendingOperatorCommand(
                            kind = OperatorCommandKind.SQUIT,
                            target = squitServer.trim(),
                            message = squitMessage(squitServer, squitReason),
                        )
                    },
                    onConfirm = { pending = it },
                )
            }
        }
    }

    pending?.let { command ->
        AlertDialog(
            onDismissRequest = { pending = null },
            // An AlertDialog is its own Compose window, so the Activity root's testTagsAsResourceId
            // does not reach it; opt this window in the same way.
            modifier =
                Modifier
                    .semantics { testTagsAsResourceId = true }
                    .testTag("network_tools_confirm_dialog"),
            title = {
                Text(
                    when (command.kind) {
                        OperatorCommandKind.KILL -> {
                            stringResource(R.string.network_tools_confirm_kill_title, command.target)
                        }

                        OperatorCommandKind.REHASH -> {
                            stringResource(R.string.network_tools_confirm_rehash_title)
                        }

                        OperatorCommandKind.CONNECT -> {
                            stringResource(R.string.network_tools_confirm_connect_title, command.target)
                        }

                        OperatorCommandKind.SQUIT -> {
                            stringResource(R.string.network_tools_confirm_squit_title, command.target)
                        }
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        when (command.kind) {
                            OperatorCommandKind.KILL -> {
                                stringResource(R.string.network_tools_confirm_kill_body, command.target)
                            }

                            OperatorCommandKind.REHASH -> {
                                stringResource(R.string.network_tools_confirm_rehash_body)
                            }

                            OperatorCommandKind.CONNECT -> {
                                stringResource(R.string.network_tools_confirm_connect_body, command.target)
                            }

                            OperatorCommandKind.SQUIT -> {
                                stringResource(R.string.network_tools_confirm_squit_body, command.target)
                            }
                        },
                    )
                    // The exact wire line, from the same message instance the confirm button sends.
                    command.message.previewLine()?.let { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("network_tools_confirm_preview"),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        onSendCommand(command.message)
                    },
                    modifier = Modifier.testTag("network_tools_confirm_accept"),
                ) { Text(command.kind.commandName, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ReasonField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    tag: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@Composable
private fun DestructiveButton(
    label: String,
    enabled: Boolean,
    tag: String,
    command: () -> PendingOperatorCommand,
    onConfirm: (PendingOperatorCommand) -> Unit,
) {
    TextButton(
        onClick = { onConfirm(command()) },
        enabled = enabled,
        modifier = Modifier.testTag(tag),
    ) {
        Text(label, color = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
    }
}

/** A confirmation in flight. Holds the built message so the preview and the send are one object. */
private data class PendingOperatorCommand(
    val kind: OperatorCommandKind,
    val target: String,
    val message: IrcMessage,
)

/** A status string resource plus its format arguments; pure, so the mapping is unit-testable. */
internal data class NetworkToolsStatusText(
    val resId: Int,
    val args: Array<Any>,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is NetworkToolsStatusText && resId == other.resId && args.contentEquals(other.args))

    override fun hashCode(): Int = 31 * resId + args.contentHashCode()
}

/** Map a [NetworkToolsStatus] to user-facing copy. Lives in the screen layer, never the ViewModel. */
internal fun networkToolsStatusText(status: NetworkToolsStatus): NetworkToolsStatusText =
    when (status) {
        NetworkToolsStatus.IgnoreAdded -> {
            NetworkToolsStatusText(R.string.network_tools_status_ignore_added, emptyArray())
        }

        is NetworkToolsStatus.IgnoreFailed -> {
            NetworkToolsStatusText(R.string.network_tools_status_ignore_failed, arrayOf(status.message))
        }

        NetworkToolsStatus.NotConnected -> {
            NetworkToolsStatusText(R.string.network_tools_status_not_connected, emptyArray())
        }

        is NetworkToolsStatus.CommandSent -> {
            NetworkToolsStatusText(R.string.network_tools_status_sent, arrayOf(status.command))
        }

        is NetworkToolsStatus.CommandFailed -> {
            NetworkToolsStatusText(
                R.string.network_tools_status_failed,
                arrayOf(status.command, status.message),
            )
        }

        NetworkToolsStatus.MissingFields -> {
            NetworkToolsStatusText(R.string.network_tools_status_missing_fields, emptyArray())
        }
    }
