package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.service.ChannelWatchDuration
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.NotificationMode
import io.github.trevarj.motd.ui.components.ChannelNotificationSheet
import io.github.trevarj.motd.ui.components.label
import io.github.trevarj.motd.ui.components.summary
import io.github.trevarj.motd.ui.nav.SettingsTarget

@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit = {},
    target: SettingsTarget? = null,
    viewModel: NotificationSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NotificationSettingsContent(
        state = state,
        onBack = onBack,
        onGlobal = viewModel::setGlobal,
        onServer = viewModel::setServer,
        onChannel = viewModel::setChannel,
        onStartWatch = { bufferId, duration -> viewModel.startWatch(bufferId, duration.millis) },
        onStopWatch = viewModel::stopWatch,
        onRetry = viewModel::retryLoad,
        onDismissError = viewModel::dismissError,
        target = target,
    )
}

@Composable
fun NotificationSettingsContent(
    state: NotificationSettingsUiState,
    onBack: () -> Unit,
    onGlobal: (NotificationMode) -> Unit,
    onServer: (Long, NotificationMode?) -> Unit,
    onChannel: (Long, NotificationMode?) -> Unit,
    onStartWatch: (Long, ChannelWatchDuration) -> Unit,
    onStopWatch: (Long) -> Unit,
    onRetry: () -> Unit,
    onDismissError: () -> Unit,
    target: SettingsTarget? = null,
) {
    var expandedNetwork by rememberSaveable { mutableStateOf<Long?>(null) }
    var globalPicker by remember { mutableStateOf(false) }
    var serverPicker by remember { mutableStateOf<Long?>(null) }
    var channelPicker by remember { mutableStateOf<Long?>(null) }
    val available = !state.loading && !state.unavailable
    val snackbarHostState = remember { SnackbarHostState() }
    val saveFailure = stringResource(R.string.notification_settings_save_failed)
    LaunchedEffect(state.saveError, saveFailure) {
        if (state.saveError) {
            snackbarHostState.showSnackbar(saveFailure, withDismissAction = true, duration = SnackbarDuration.Long)
            onDismissError()
        }
    }
    SettingsScaffold(
        title = stringResource(R.string.settings_notifications),
        onBack = onBack,
        modifier = Modifier.testTag("screen_notification_settings"),
        snackbarHostState = snackbarHostState,
        scroll = false,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("notification_settings_list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.loading) {
                item(key = "loading") {
                    CircularProgressIndicator(Modifier.testTag("notification_settings_loading"))
                }
            }
            if (state.unavailable) {
                item(key = "unavailable") {
                    PersistentStatusNotice(
                        text = stringResource(R.string.notification_settings_unavailable),
                        error = true,
                        actionLabel = stringResource(R.string.notification_settings_retry),
                        onAction = onRetry,
                    )
                }
            }
            item(key = "global") {
                SettingsTarget(target?.name, SettingsTarget.NOTIFICATIONS.name) { targetModifier ->
                    Box(targetModifier) {
                        SettingsNavigationRow(
                            title = stringResource(R.string.notification_global_default),
                            icon = if (state.global == NotificationMode.OFF) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                            summary =
                                when {
                                    state.loading -> stringResource(R.string.notification_settings_loading)
                                    state.unavailable -> stringResource(R.string.notification_settings_unavailable_short)
                                    else -> state.global.label()
                                },
                            enabled = available,
                            modifier = Modifier.testTag("notification_global"),
                            onClick = { globalPicker = true },
                        )
                    }
                }
            }
            if (state.deliveryMode == DeliveryMode.UNIFIED_PUSH) {
                item(key = "push_disclosure") {
                    PersistentStatusNotice(text = stringResource(R.string.notification_unified_push_disclosure))
                }
            }
            state.networks.forEach { network ->
                item(key = "server_${network.networkId}") {
                    val expanded = expandedNetwork == network.networkId
                    val expansion = stringResource(if (expanded) R.string.system_event_expanded else R.string.system_event_collapsed)
                    SettingsNavigationRow(
                        title = network.name,
                        icon = if (network.activeWatchCount > 0) Icons.Outlined.NotificationsActive else Icons.Outlined.Dns,
                        summary =
                            stringResource(
                                R.string.notification_server_summary,
                                network.effectiveMode.label(),
                                pluralStringResource(R.plurals.notification_channel_overrides, network.channelOverrideCount, network.channelOverrideCount),
                                pluralStringResource(R.plurals.notification_active_watches, network.activeWatchCount, network.activeWatchCount),
                            ),
                        enabled = available,
                        modifier = Modifier.testTag("notification_server_${network.networkId}").semantics { stateDescription = expansion },
                        onClick = { expandedNetwork = if (expanded) null else network.networkId },
                    )
                }
                if (expandedNetwork == network.networkId) {
                    item(key = "server_default_${network.networkId}") {
                        SettingsNavigationRow(
                            title = stringResource(R.string.notification_server_default),
                            icon = if (network.effectiveMode == NotificationMode.OFF) Icons.Outlined.NotificationsOff else Icons.Outlined.Notifications,
                            summary = network.serverOverride?.label() ?: stringResource(R.string.notification_use_global, state.global.label()),
                            enabled = available,
                            modifier = Modifier.padding(start = 16.dp).testTag("notification_server_default_${network.networkId}"),
                            onClick = { serverPicker = network.networkId },
                        )
                    }
                    if (network.channels.isEmpty()) {
                        item(key = "empty_${network.networkId}") {
                            Text(stringResource(R.string.notification_no_channels), modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp))
                        }
                    }
                    items(network.channels, key = { "channel_${it.bufferId}" }) { channel ->
                        SettingsNavigationRow(
                            title = channel.name,
                            icon =
                                when {
                                    channel.notification.watch != null -> Icons.Outlined.NotificationsActive
                                    channel.notification.mode == NotificationMode.OFF -> Icons.Outlined.NotificationsOff
                                    else -> Icons.Outlined.Tag
                                },
                            summary = channel.notification.summary(),
                            enabled = available,
                            modifier = Modifier.padding(start = 32.dp).testTag("notification_channel_${channel.bufferId}"),
                            onClick = { channelPicker = channel.bufferId },
                        )
                    }
                }
            }
        }
    }
    if (available && globalPicker) {
        SingleChoiceSheet(
            title = stringResource(R.string.notification_global_default),
            selected = state.global,
            options = NotificationMode.entries.map { ChoiceOption(it, it.label(), tag = "notification_mode_${it.name.lowercase()}") },
            onSelect = onGlobal,
            onDismiss = { globalPicker = false },
            tag = "notification_global_sheet",
        )
    }
    val selectedServer = serverPicker?.let { networkId -> state.networks.firstOrNull { it.networkId == networkId } }
    if (available && selectedServer != null) {
        SingleChoiceSheet<NotificationMode?>(
            title = stringResource(R.string.notification_server_default),
            selected = selectedServer.serverOverride,
            options =
                listOf(ChoiceOption<NotificationMode?>(null, stringResource(R.string.notification_use_global, state.global.label()), tag = "notification_mode_inherit")) +
                    NotificationMode.entries.map { ChoiceOption<NotificationMode?>(it, it.label(), tag = "notification_mode_${it.name.lowercase()}") },
            onSelect = { onServer(selectedServer.networkId, it) },
            onDismiss = { serverPicker = null },
            tag = "notification_server_sheet",
        )
    }
    val selectedChannel =
        channelPicker?.let { bufferId ->
            state.networks.firstNotNullOfOrNull { network -> network.channels.firstOrNull { it.bufferId == bufferId } }
        }
    if (selectedChannel != null) {
        ChannelNotificationSheet(
            presentation = selectedChannel.notification,
            onMode = { onChannel(selectedChannel.bufferId, it) },
            onStart = { onStartWatch(selectedChannel.bufferId, it) },
            onStop = { onStopWatch(selectedChannel.bufferId) },
            onDismiss = { channelPicker = null },
            tagPrefix = "notification",
        )
    }
}
