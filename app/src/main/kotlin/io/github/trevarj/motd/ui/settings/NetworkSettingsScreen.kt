package io.github.trevarj.motd.ui.settings

import android.content.Context
import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.avatar.SelfAvatarSetting
import io.github.trevarj.motd.avatar.validateAvatarUrl
import io.github.trevarj.motd.bouncer.BouncerKind
import io.github.trevarj.motd.bouncer.SojuLoginForm
import io.github.trevarj.motd.bouncer.ZncLoginForm
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.prefs.HistorySyncDepth
import io.github.trevarj.motd.data.sync.HistorySyncOutcome
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.components.ExplicitAvatarUrlPreview
import io.github.trevarj.motd.ui.nav.NetworkSettingsTarget
import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.profile.rememberProfileMetadataSupported
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.readableForeground
import io.github.trevarj.motd.ui.theme.readableOutlinedTextFieldColors
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import java.text.NumberFormat
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

/** Stateful entry: wires the ViewModel, seeds the edit form from the network id. */
@Composable
fun NetworkSettingsScreen(
    networkId: Long,
    target: NetworkSettingsTarget? = null,
    onBack: () -> Unit = {},
    // Round 5: soju bouncer manager + server-messages buffer.
    onOpenBouncerNetworks: (Long) -> Unit = {},
    onOpenBuffer: (Long) -> Unit = {},
    onOpenNetworkTools: (Long) -> Unit = {},
    onOpenAccountSetup: (Long) -> Unit = {},
    onOpenUserProfile: (Long) -> Unit = {},
    viewModel: NetworkSettingsViewModel = hiltViewModel(),
) {
    LaunchedEffect(networkId) { viewModel.init(networkId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    // Resources rather than Context: a LocalContext read inside the effect would not follow a
    // configuration change (Compose lint LocalContextGetResourceValueCall).
    val resources = LocalResources.current
    LaunchedEffect(viewModel, resources) {
        viewModel.historySyncResults.collect { result -> snackbarHostState.showSnackbar(historySyncResultMessage(resources, result)) }
    }
    NetworkSettingsContent(
        state = state,
        target = target,
        onBack = onBack,
        snackbarHostState = snackbarHostState,
        onResyncHistory = viewModel::resyncHistory,
        onCancelResyncHistory = viewModel::cancelResyncHistory,
        onDisplayNameChange = viewModel::editDisplayName,
        onWsUrlChange = viewModel::editWsUrl,
        onTrustedFileHostChange = viewModel::editTrustedFileHost,
        onOnConnectCommandsChange = viewModel::editOnConnectCommands,
        onInitialAwayMessageChange = viewModel::editInitialAwayMessage,
        onObfsModeChange = viewModel::editObfsMode,
        onProxyHostChange = viewModel::editProxyHost,
        onProxyPortChange = viewModel::editProxyPort,
        onObfsLinkChange = viewModel::editObfsLink,
        onServerChange = viewModel::editServer,
        onAuthChange = viewModel::editAuth,
        onSetAutoRegainPreferredNick = viewModel::setAutoRegainPreferredNick,
        onSetRoutePrivateNoticesToServer = viewModel::setRoutePrivateNoticesToServer,
        onZncLoginChange = viewModel::editZncLogin,
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
        onOpenNetworkTools = { onOpenNetworkTools(networkId) },
        onOpenAccountSetup = { onOpenAccountSetup(networkId) },
        onOpenUserProfile = { if (state.entity?.id == networkId) onOpenUserProfile(networkId) },
        onSaveAvatar = viewModel::saveAvatar,
        onStopManagingAvatar = viewModel::stopManagingAvatar,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSettingsContent(
    state: NetworkSettingsUiState,
    target: NetworkSettingsTarget? = null,
    onBack: () -> Unit,
    onDisplayNameChange: (String) -> Unit = {},
    onWsUrlChange: (String) -> Unit = {},
    onTrustedFileHostChange: (String) -> Unit = {},
    onOnConnectCommandsChange: (String) -> Unit = {},
    onInitialAwayMessageChange: (String) -> Unit = {},
    onObfsModeChange: (ObfsMode) -> Unit = {},
    onProxyHostChange: (String) -> Unit = {},
    onProxyPortChange: (String) -> Unit = {},
    onObfsLinkChange: (String) -> Unit = {},
    onServerChange: (ServerForm) -> Unit,
    onAuthChange: (AuthForm) -> Unit,
    onSetAutoRegainPreferredNick: (Boolean) -> Unit = {},
    onSetRoutePrivateNoticesToServer: (Boolean) -> Unit = {},
    onZncLoginChange: (ZncLoginForm) -> Unit = {},
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
    onOpenNetworkTools: () -> Unit = {},
    onOpenAccountSetup: () -> Unit = {},
    onOpenUserProfile: () -> Unit = {},
    onSaveAvatar: (String?) -> Unit = {},
    onStopManagingAvatar: () -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    onResyncHistory: (HistorySyncDepth) -> Unit = {},
    onCancelResyncHistory: () -> Unit = {},
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardConfirm by remember { mutableStateOf(false) }
    var resyncSheetOpen by remember { mutableStateOf(false) }
    var avatarEditorOpen by rememberSaveable { mutableStateOf(false) }
    val requestBack = { if (state.hasUnsavedChanges) showDiscardConfirm = true else onBack() }
    BackHandler(enabled = state.hasUnsavedChanges) { showDiscardConfirm = true }

    SettingsScaffold(
        title = state.entity?.name ?: stringResource(R.string.network_settings_title),
        onBack = requestBack,
        modifier = Modifier.testTag("screen_network_settings"),
        snackbarHostState = snackbarHostState,
        topActions = {
            TextButton(
                colors = readableTextButtonColors(),
                onClick = onSave,
                enabled = state.canSave,
                modifier = Modifier.testTag("network_settings_save"),
            ) { Text(stringResource(R.string.network_settings_save)) }
        },
    ) {
        SettingsGroup { StatusCard(state.connState, onConnect, onDisconnect) }
        if (state.entity?.role == NetworkRole.DIRECT && state.server.tls && state.auth.mode == AuthMode.NONE &&
            state.auth.nickServPassword.isBlank()
        ) {
            FilledTonalButton(
                onClick = onOpenAccountSetup,
                modifier = Modifier.fillMaxWidth().testTag("network_account_setup"),
            ) { Text(stringResource(R.string.account_setup_title)) }
        }
        SettingsGroup(title = stringResource(R.string.network_settings_general_section)) {
            OutlinedTextField(
                colors = readableOutlinedTextFieldColors(),
                value = state.displayName,
                onValueChange = onDisplayNameChange,
                label = { Text(stringResource(R.string.network_settings_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("network_display_name"),
            )
            AutoConnectRow(checked = state.autoConnect, onCheckedChange = onSetAutoConnect)
            if (state.entity != null && state.entity.role != NetworkRole.BOUNCER_ROOT) {
                SwitchRow(
                    title = stringResource(R.string.network_route_private_notices_to_server),
                    subtitle = stringResource(R.string.network_route_private_notices_to_server_desc),
                    checked = state.routePrivateNoticesToServer,
                    onCheckedChange = onSetRoutePrivateNoticesToServer,
                    switchTag = "network_route_private_notices_to_server",
                    icon = Icons.Outlined.Notifications,
                )
            }
        }
        val profileNetwork = state.entity?.takeUnless { it.role == NetworkRole.BOUNCER_ROOT }
        if (profileNetwork != null) {
            val profileEnabled = rememberProfileMetadataSupported(profileNetwork.id)
            SettingsGroup {
                SettingsNavigationRow(
                    title = stringResource(R.string.profile_user_title),
                    summary = stringResource(if (profileEnabled) R.string.profile_network_entry_summary else R.string.profile_entry_unavailable),
                    modifier = Modifier.testTag("network_user_profile"),
                    enabled = profileEnabled,
                    onClick = onOpenUserProfile,
                )
            }
        }
        if (state.entity?.role == NetworkRole.BOUNCER_CHILD) {
            SettingsTarget(target?.name, NetworkSettingsTarget.CONNECTION.name) { targetModifier ->
                SettingsGroup(
                    title = stringResource(R.string.network_settings_connection_section),
                    modifier = targetModifier,
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.network_settings_managed_by, state.parentName.orEmpty())) },
                        supportingContent = { Text(stringResource(R.string.network_settings_managed_by_desc)) },
                        colors =
                            androidx.compose.material3.ListItemDefaults
                                .colors(containerColor = Color.Transparent),
                    )
                    TrustedFileHostField(state, onTrustedFileHostChange)
                    InitialAwayField(state, onInitialAwayMessageChange)
                    OnConnectCommandsField(state, onOnConnectCommandsChange)
                }
            }
        } else {
            SettingsTarget(target?.name, NetworkSettingsTarget.CONNECTION.name) { targetModifier ->
                SettingsGroup(
                    title = stringResource(R.string.network_settings_connection_section),
                    modifier = targetModifier,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        NetworkEndpointFields(state.server, onServerChange)
                    }
                    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    TransportSection(wsUrl = state.wsUrl, onWsUrlChange = onWsUrlChange)
                    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    TrustedFileHostField(state, onTrustedFileHostChange)
                    androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    ObfuscationSection(
                        target = target,
                        mode = state.obfsMode,
                        proxyHost = state.proxyHost,
                        proxyPort = state.proxyPort,
                        obfsLink = state.obfsLink,
                        onModeChange = onObfsModeChange,
                        onProxyHostChange = onProxyHostChange,
                        onProxyPortChange = onProxyPortChange,
                        onObfsLinkChange = onObfsLinkChange,
                    )
                    OnConnectCommandsField(state, onOnConnectCommandsChange)
                }
            }
            SettingsTarget(target?.name, NetworkSettingsTarget.AUTHENTICATION.name) { targetModifier ->
                SettingsGroup(
                    title = stringResource(R.string.network_settings_identity_section),
                    modifier = targetModifier,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (state.isZnc) {
                            BouncerLoginFields(
                                kind = BouncerKind.ZNC,
                                server = state.server,
                                sojuLogin = SojuLoginForm(),
                                zncLogin = state.zncLogin,
                                onServerChange = onServerChange,
                                onSojuLoginChange = {},
                                onZncLoginChange = onZncLoginChange,
                                showEndpoint = false,
                            )
                        } else {
                            NetworkIdentityFields(
                                server = state.server,
                                auth = state.auth,
                                onServerChange = onServerChange,
                                onAuthChange = onAuthChange,
                                soju = state.entity?.role == NetworkRole.BOUNCER_ROOT,
                            )
                        }
                    }
                    if (state.entity?.role == NetworkRole.DIRECT && !state.isZnc) {
                        SwitchRow(
                            title = stringResource(R.string.network_auto_regain_preferred_nick),
                            subtitle = stringResource(R.string.network_auto_regain_preferred_nick_desc),
                            checked = state.autoRegainPreferredNick,
                            onCheckedChange = onSetAutoRegainPreferredNick,
                            switchTag = "network_auto_regain_preferred_nick",
                            icon = Icons.Outlined.Person,
                        )
                    }
                }
            }
            if (state.entity?.role != NetworkRole.BOUNCER_ROOT) {
                SettingsGroup(title = stringResource(R.string.network_settings_presence_section)) {
                    InitialAwayField(state, onInitialAwayMessageChange)
                }
            }
        }
        if (state.entity != null) {
            SettingsTarget(target?.name, NetworkSettingsTarget.AVATAR.name) { targetModifier ->
                SettingsGroup(modifier = targetModifier) {
                    SettingsNavigationRow(
                        title = stringResource(R.string.network_settings_avatar_section),
                        summary = stringResource(networkAvatarStatus(state.selfAvatar)),
                        enabled = state.selfAvatar != null,
                        modifier = Modifier.testTag("network_avatar_edit"),
                        onClick = { avatarEditorOpen = true },
                    )
                }
            }
        }
        SettingsTarget(target?.name, NetworkSettingsTarget.TOOLS.name) { targetModifier ->
            SettingsGroup(
                title = stringResource(R.string.network_settings_tools_section),
                modifier = targetModifier,
            ) {
                if (state.entity?.role == NetworkRole.BOUNCER_ROOT) {
                    SettingsNavigationRow(
                        title = stringResource(R.string.network_settings_bouncer_networks),
                        summary = stringResource(R.string.network_settings_bouncer_networks_desc),
                        modifier = Modifier.testTag("network_settings_bouncer_networks"),
                        onClick = onOpenBouncerNetworks,
                    )
                }
                SettingsNavigationRow(
                    title = stringResource(R.string.network_settings_server_messages),
                    modifier = Modifier.testTag("network_settings_server_messages"),
                    onClick = onOpenServerMessages,
                )
                if (state.entity?.role != NetworkRole.BOUNCER_ROOT) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.network_settings_stored_messages)) },
                        supportingContent = { Text(stringResource(R.string.network_settings_stored_messages_desc)) },
                        trailingContent = {
                            io.github.trevarj.motd.ui.components
                                .MetadataPill(state.storedMessages?.let { NumberFormat.getIntegerInstance().format(it) } ?: "…")
                        },
                        modifier = Modifier.testTag("network_settings_stored_messages"),
                        colors =
                            androidx.compose.material3.ListItemDefaults
                                .colors(containerColor = Color.Transparent),
                    )
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(
                                    if (state.historySyncRunning) R.string.network_settings_resync_stop else R.string.network_settings_resync_history,
                                ),
                            )
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (state.historySyncRunning) R.string.network_settings_resync_running else R.string.network_settings_resync_history_desc,
                                ),
                            )
                        },
                        modifier =
                            Modifier
                                .testTag("network_settings_resync_history")
                                .clickable { if (state.historySyncRunning) onCancelResyncHistory() else resyncSheetOpen = true },
                        colors =
                            androidx.compose.material3.ListItemDefaults
                                .colors(containerColor = Color.Transparent),
                    )
                }
                SettingsNavigationRow(
                    title = stringResource(R.string.network_settings_network_tools),
                    summary = stringResource(R.string.network_settings_network_tools_desc),
                    modifier = Modifier.testTag("network_settings_network_tools"),
                    onClick = onOpenNetworkTools,
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.network_settings_danger_section)) {
            OutlinedButton(
                onClick = { showDeleteConfirm = true },
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(R.string.network_settings_delete), color = MaterialTheme.colorScheme.error) }
        }
    }

    if (avatarEditorOpen && state.entity != null && state.selfAvatar != null) {
        NetworkAvatarEditor(
            state = state,
            networkId = state.entity.id,
            onSave = onSaveAvatar,
            onStopManaging = onStopManagingAvatar,
            onDismiss = { avatarEditorOpen = false },
        )
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.network_settings_unsaved_title)) },
            text = { Text(stringResource(R.string.network_settings_unsaved_message)) },
            confirmButton = {
                TextButton(colors = readableTextButtonColors(), onClick = {
                    showDiscardConfirm = false
                    onSave()
                }, enabled = state.canSave) {
                    Text(stringResource(R.string.network_settings_save))
                }
            },
            dismissButton = {
                Row {
                    TextButton(colors = readableTextButtonColors(), onClick = { showDiscardConfirm = false }) { Text(stringResource(R.string.network_settings_keep_editing)) }
                    TextButton(colors = readableTextButtonColors(), onClick = {
                        showDiscardConfirm = false
                        onBack()
                    }) {
                        Text(stringResource(R.string.network_settings_discard), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
        )
    }

    if (resyncSheetOpen) {
        SingleChoiceSheet(
            title = stringResource(R.string.network_settings_resync_history_window),
            selected = HistorySyncDepth.MONTH,
            options =
                HistorySyncDepth.entries.map { depth ->
                    ChoiceOption(
                        depth,
                        historySyncDepthLabel(depth),
                        summary = syncEstimateLabel(state.syncEstimates[depth]),
                        tag = "network_settings_resync_${depth.name.lowercase()}",
                    )
                },
            onSelect = onResyncHistory,
            onDismiss = { resyncSheetOpen = false },
            tag = "network_settings_resync_sheet",
        )
    }
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.network_settings_delete_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.entity?.role == NetworkRole.BOUNCER_ROOT) {
                            R.string.network_settings_delete_bouncer_confirm_message
                        } else {
                            R.string.network_settings_delete_confirm_message
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(colors = readableTextButtonColors(), onClick = {
                    showDeleteConfirm = false
                    onDelete()
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(colors = readableTextButtonColors(), onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.pendingBouncerIdentityChange?.let { change ->
        AlertDialog(
            onDismissRequest = onCancelBouncerIdentityChange,
            title = { Text(stringResource(R.string.network_settings_bouncer_identity_title)) },
            text = {
                Text(
                    androidx.compose.ui.res.pluralStringResource(
                        R.plurals.network_settings_bouncer_identity_message,
                        change.localMirrorCount,
                        change.localMirrorCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(colors = readableTextButtonColors(), onClick = onRemoveLocalMirrors) {
                    Text(stringResource(R.string.network_settings_remove_local_mirrors))
                }
            },
            dismissButton = {
                Row {
                    TextButton(colors = readableTextButtonColors(), onClick = onCancelBouncerIdentityChange) {
                        Text(stringResource(R.string.action_cancel))
                    }
                    TextButton(colors = readableTextButtonColors(), onClick = onKeepLocalMirrors) {
                        Text(stringResource(R.string.network_settings_keep_local_mirrors))
                    }
                }
            },
        )
    }
}

private fun networkAvatarStatus(setting: SelfAvatarSetting?): Int =
    when (setting) {
        null -> R.string.network_settings_avatar_loading
        SelfAvatarSetting.Unmanaged -> R.string.network_settings_avatar_unsaved
        SelfAvatarSetting.ExplicitlyCleared -> R.string.network_settings_avatar_removal_saved
        is SelfAvatarSetting.Set -> R.string.network_settings_avatar_saved
    }

@Composable
private fun NetworkAvatarEditor(
    state: NetworkSettingsUiState,
    networkId: Long,
    onSave: (String?) -> Unit,
    onStopManaging: () -> Unit,
    onDismiss: () -> Unit,
) {
    val savedUrl = (state.selfAvatar as? SelfAvatarSetting.Set)?.url.orEmpty()
    var url by rememberSaveable(networkId, savedUrl) { mutableStateOf(savedUrl) }
    val validUrl = remember(url) { validateAvatarUrl(url) }
    val dirty = url.trim() != savedUrl
    val invalid = dirty && url.isNotBlank() && validUrl == null
    val focus = LocalFocusManager.current
    AlertDialog(
        onDismissRequest = { if (!state.avatarSaving) onDismiss() },
        title = { Text(stringResource(R.string.network_settings_avatar_section)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text =
                        stringResource(
                            when {
                                state.avatarSaving -> R.string.network_settings_avatar_saving
                                state.avatarSaveFailed -> R.string.avatar_result_failed
                                else -> networkAvatarStatus(state.selfAvatar)
                            },
                        ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = if (state.avatarSaveFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    stringResource(
                        when {
                            state.avatarPublishingAvailable -> R.string.network_settings_avatar_sharing_available
                            state.connState is IrcClientState.Ready -> R.string.network_settings_avatar_unavailable
                            else -> R.string.network_settings_avatar_offline
                        },
                    ),
                )
                OutlinedTextField(
                    colors = readableOutlinedTextFieldColors(),
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.network_settings_avatar_url)) },
                    supportingText = { Text(stringResource(if (invalid) R.string.avatar_url_invalid else R.string.network_settings_avatar_url_desc)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    isError = invalid,
                    enabled = !state.avatarSaving,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("network_avatar_url"),
                )
                if (validUrl != null) {
                    ExplicitAvatarUrlPreview(url, networkId, "network:$networkId:${state.entity?.nick}", isChannel = false)
                }
                if (!dirty && state.selfAvatar != SelfAvatarSetting.ExplicitlyCleared) {
                    TextButton(
                        colors = readableTextButtonColors(),
                        enabled = !state.avatarSaving,
                        onClick = { onSave(null) },
                        modifier = Modifier.testTag("network_avatar_clear"),
                    ) { Text(stringResource(R.string.network_settings_avatar_clear)) }
                }
                if (!dirty && state.selfAvatar != SelfAvatarSetting.Unmanaged) {
                    Column {
                        TextButton(
                            colors = readableTextButtonColors(),
                            enabled = !state.avatarSaving,
                            onClick = onStopManaging,
                        ) { Text(stringResource(R.string.network_settings_avatar_unmanage)) }
                        Text(stringResource(R.string.network_settings_avatar_unmanage_desc), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = validUrl ?: return@Button
                    focus.clearFocus()
                    onSave(value)
                },
                enabled = dirty && validUrl != null && !state.avatarSaving,
                modifier = Modifier.testTag("network_avatar_save"),
            ) { Text(stringResource(R.string.network_settings_avatar_save)) }
        },
        dismissButton = {
            TextButton(
                colors = readableTextButtonColors(),
                enabled = !state.avatarSaving,
                onClick = onDismiss,
            ) { Text(stringResource(if (dirty) R.string.action_cancel else R.string.network_settings_avatar_done)) }
        },
    )
}

/** Status header: a dot + state label with a state-appropriate action button. */
@Composable
private fun StatusCard(
    connState: IrcClientState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    ListItem(
        colors =
            androidx.compose.material3.ListItemDefaults
                .colors(containerColor = Color.Transparent),
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
                is IrcClientState.Disconnected -> {
                    FilledTonalButton(onClick = onConnect, modifier = connButton) {
                        Text(stringResource(R.string.network_settings_status_connect))
                    }
                }

                is IrcClientState.Failed -> {
                    FilledTonalButton(onClick = onConnect, modifier = connButton) {
                        Text(stringResource(R.string.network_settings_status_reconnect))
                    }
                }

                else -> {
                    FilledTonalButton(onClick = onDisconnect, modifier = connButton) {
                        Text(stringResource(R.string.network_settings_status_disconnect))
                    }
                }
            }
        },
    )
}

@Composable
private fun statusLabel(connState: IrcClientState): String =
    when (connState) {
        is IrcClientState.Ready -> stringResource(R.string.network_settings_status_ready, connState.nick)
        IrcClientState.Connecting -> stringResource(R.string.network_settings_status_connecting)
        IrcClientState.Registering -> stringResource(R.string.network_settings_status_registering)
        IrcClientState.Disconnected -> stringResource(R.string.network_settings_status_disconnected)
        is IrcClientState.Failed -> stringResource(R.string.network_settings_status_failed, connState.reason)
    }

@Composable
private fun statusColor(connState: IrcClientState): Color {
    val semanticColors = LocalMotdSemanticColors.current
    return when (connState) {
        is IrcClientState.Ready -> semanticColors.success
        IrcClientState.Connecting, IrcClientState.Registering -> semanticColors.warning
        is IrcClientState.Failed -> MaterialTheme.colorScheme.error
        IrcClientState.Disconnected -> MaterialTheme.colorScheme.outlineVariant
    }
}

@Composable
private fun AutoConnectRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SwitchRow(
        title = stringResource(R.string.network_settings_autoconnect),
        subtitle = stringResource(R.string.network_settings_autoconnect_desc),
        checked = checked,
        onCheckedChange = onCheckedChange,
        switchTag = "network_settings_autoconnect",
    )
}

@Composable
private fun InitialAwayField(
    state: NetworkSettingsUiState,
    onInitialAwayMessageChange: (String) -> Unit,
) {
    val error = initialAwayValidationError(state.initialAwayMessage)
    OutlinedTextField(
        colors = readableOutlinedTextFieldColors(),
        value = state.initialAwayMessage,
        onValueChange = onInitialAwayMessageChange,
        label = { Text(stringResource(R.string.network_settings_initial_away)) },
        supportingText = {
            Text(
                if (error == null) {
                    stringResource(R.string.network_settings_initial_away_desc)
                } else {
                    stringResource(R.string.network_settings_initial_away_invalid)
                },
            )
        },
        isError = error != null,
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("network_initial_away"),
    )
}

@Composable
private fun TrustedFileHostField(
    state: NetworkSettingsUiState,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        colors = readableOutlinedTextFieldColors(),
        value = state.trustedFileHost,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.network_settings_trusted_filehost)) },
        supportingText = {
            Text(
                stringResource(
                    if (state.trustedFileHostError == null) {
                        R.string.network_settings_trusted_filehost_desc
                    } else {
                        R.string.network_settings_trusted_filehost_error
                    },
                ),
            )
        },
        isError = state.trustedFileHostError != null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("network_trusted_filehost"),
    )
}

@Composable
private fun OnConnectCommandsField(
    state: NetworkSettingsUiState,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        colors = readableOutlinedTextFieldColors(),
        value = state.onConnectCommands,
        onValueChange = onValueChange,
        label = { Text(stringResource(R.string.network_settings_on_connect_commands)) },
        placeholder = { Text(stringResource(R.string.network_settings_on_connect_commands_placeholder)) },
        supportingText = {
            val scope =
                when (state.entity?.role) {
                    NetworkRole.BOUNCER_ROOT -> stringResource(R.string.network_settings_on_connect_commands_root)
                    NetworkRole.BOUNCER_CHILD -> stringResource(R.string.network_settings_on_connect_commands_child)
                    else -> null
                }
            Text(stringResource(R.string.network_settings_on_connect_commands_desc) + (scope?.let { " $it" } ?: ""))
        },
        minLines = 4,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("network_on_connect_commands"),
    )
}

/**
 * Collapsible "Connection / Obfuscation" section. Off by default: a header row
 * with an expand toggle reveals the mode selector; SOCKS5 reveals host/port and embedded REALITY
 * accepts only a share link. The header always exposes the current value and expansion affordance.
 */
@Composable
private fun ObfuscationSection(
    target: NetworkSettingsTarget? = null,
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
    SettingsTarget(target?.name, NetworkSettingsTarget.OBFUSCATION.name) { targetModifier ->
        Column(modifier = targetModifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                        .testTag("network_obfs_header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.network_settings_routing), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary))
                    Text(
                        stringResource(R.string.network_settings_current_value, obfsModeLabel(mode)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!expanded) {
                        Text(
                            stringResource(R.string.network_settings_tap_configure),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription =
                        stringResource(
                            if (expanded) R.string.network_settings_collapse else R.string.network_settings_expand,
                        ),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
                exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
            ) {
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
                    // Mode picks swap in sub-forms of very different heights; ease the swap so the
                    // expanded section doesn't jolt.
                    Column(Modifier.animateContentSize(animationSpec = MotdMotion.contentSize)) {
                        when (mode) {
                            ObfsMode.TOR -> {
                                Text(
                                    stringResource(R.string.network_settings_obfs_tor_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            ObfsMode.SOCKS5 -> {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    OutlinedTextField(
                                        colors = readableOutlinedTextFieldColors(),
                                        value = proxyHost,
                                        onValueChange = onProxyHostChange,
                                        label = { Text(stringResource(R.string.network_settings_obfs_proxy_host)) },
                                        placeholder = { Text(stringResource(R.string.network_settings_proxy_host_example)) },
                                        singleLine = true,
                                        keyboardOptions =
                                            KeyboardOptions(
                                                keyboardType = KeyboardType.Uri,
                                                autoCorrectEnabled = false,
                                                imeAction = ImeAction.Next,
                                            ),
                                        modifier = Modifier.fillMaxWidth().testTag("network_obfs_host"),
                                    )
                                    OutlinedTextField(
                                        colors = readableOutlinedTextFieldColors(),
                                        value = proxyPort,
                                        onValueChange = onProxyPortChange,
                                        label = { Text(stringResource(R.string.network_settings_obfs_proxy_port)) },
                                        placeholder = { Text(stringResource(R.string.network_settings_proxy_port_example)) },
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
                            }

                            ObfsMode.EMBEDDED_REALITY -> {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        stringResource(R.string.network_settings_reality_ingress_help),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        stringResource(R.string.network_settings_obfs_reality_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    OutlinedTextField(
                                        colors = readableOutlinedTextFieldColors(),
                                        value = obfsLink,
                                        onValueChange = onObfsLinkChange,
                                        label = { Text(stringResource(R.string.network_settings_obfs_reality_link)) },
                                        placeholder = { Text(stringResource(R.string.network_settings_vless_example)) },
                                        singleLine = false,
                                        isError = vlessLinkValidationError(obfsLink) != null,
                                        supportingText = {
                                            vlessLinkValidationError(obfsLink)?.let { Text(it) }
                                        },
                                        keyboardOptions =
                                            KeyboardOptions(
                                                keyboardType = KeyboardType.Uri,
                                                capitalization = KeyboardCapitalization.None,
                                                autoCorrectEnabled = false,
                                            ),
                                        modifier = Modifier.fillMaxWidth().testTag("network_obfs_link"),
                                    )
                                }
                            }

                            ObfsMode.NONE -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransportSection(
    wsUrl: String,
    onWsUrlChange: (String) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var websocketSelected by rememberSaveable { mutableStateOf(wsUrl.isNotBlank()) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("network_transport_header"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.network_settings_transport), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary))
                Text(
                    stringResource(
                        R.string.network_settings_current_value,
                        stringResource(if (websocketSelected) R.string.network_settings_transport_websocket else R.string.network_settings_transport_tcp),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!expanded) {
                    Text(
                        stringResource(R.string.network_settings_tap_configure),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(if (expanded) R.string.network_settings_collapse else R.string.network_settings_expand),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
            exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
        ) {
            Column(Modifier.padding(start = 8.dp, end = 16.dp, bottom = 16.dp).selectableGroup()) {
                RadioRow(
                    label = stringResource(R.string.network_settings_transport_tcp),
                    subtitle = stringResource(R.string.network_settings_transport_tcp_desc),
                    selected = !websocketSelected,
                    enabled = true,
                    onClick = {
                        websocketSelected = false
                        onWsUrlChange("")
                    },
                )
                RadioRow(
                    label = stringResource(R.string.network_settings_transport_websocket),
                    subtitle = stringResource(R.string.network_settings_transport_websocket_desc),
                    selected = websocketSelected,
                    enabled = true,
                    onClick = { websocketSelected = true },
                )
                // The URL field appears the moment the WebSocket radio is picked; ease it in
                // instead of popping the row below the radios.
                AnimatedVisibility(
                    visible = websocketSelected,
                    enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
                    exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
                ) {
                    OutlinedTextField(
                        colors = readableOutlinedTextFieldColors(),
                        value = wsUrl,
                        onValueChange = onWsUrlChange,
                        label = { Text(stringResource(R.string.network_settings_ws_url)) },
                        placeholder = { Text(stringResource(R.string.network_settings_wss_example)) },
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
private fun obfsModeLabel(mode: ObfsMode): String =
    stringResource(
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
    RadioRow(label, mode == selected, true, { onSelect(mode) })
}

@Preview
@Composable
private fun NetworkSettingsContentPreview() {
    MotdTheme {
        NetworkSettingsContent(
            state =
                NetworkSettingsUiState(
                    loaded = true,
                    server = ServerForm(host = "irc.libera.chat", port = "6697", nick = "me"),
                    auth = AuthForm(mode = AuthMode.PLAIN, saslUser = "me", saslPassword = "secret"),
                ),
            onBack = {},
            onServerChange = {},
            onAuthChange = {},
            onSave = {},
            onDelete = {},
        )
    }
}

@Composable
private fun historySyncDepthLabel(depth: HistorySyncDepth): String =
    stringResource(
        when (depth) {
            HistorySyncDepth.WEEK -> R.string.onboarding_history_depth_week
            HistorySyncDepth.MONTH -> R.string.onboarding_history_depth_month
            HistorySyncDepth.QUARTER -> R.string.onboarding_history_depth_quarter
            HistorySyncDepth.EVERYTHING -> R.string.onboarding_history_depth_everything
        },
    )

@Composable
private fun syncEstimateLabel(estimate: Long?): String? =
    when {
        estimate == null -> null
        estimate == 0L -> stringResource(R.string.network_settings_resync_estimate_none)
        else -> stringResource(R.string.network_settings_resync_estimate, NumberFormat.getIntegerInstance().format(estimate))
    }

private fun historySyncResultMessage(
    resources: Resources,
    result: HistorySyncOutcome,
): String =
    when (result) {
        is HistorySyncOutcome.Fetched -> {
            if (result.messages == 0) {
                resources.getString(R.string.network_settings_resync_up_to_date)
            } else {
                resources.getString(
                    R.string.network_settings_resync_fetched,
                    resources.getQuantityString(R.plurals.network_settings_resync_fetched_messages, result.messages, result.messages),
                    resources.getQuantityString(R.plurals.network_settings_resync_fetched_chats, result.chats, result.chats),
                )
            }
        }

        is HistorySyncOutcome.Partial -> {
            resources.getString(
                R.string.network_settings_resync_partial,
                resources.getQuantityString(R.plurals.network_settings_resync_fetched_messages, result.messages, result.messages),
            )
        }

        HistorySyncOutcome.Unsupported -> {
            resources.getString(R.string.network_settings_resync_unsupported)
        }

        HistorySyncOutcome.Failed -> {
            resources.getString(R.string.network_settings_resync_failed)
        }

        HistorySyncOutcome.Cancelled -> {
            resources.getString(R.string.network_settings_resync_cancelled)
        }
    }
