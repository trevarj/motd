package io.github.trevarj.motd.ui.settings

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.data.prefs.PushProvider
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Notifications & delivery category: delivery mode (persistent/push) and battery optimization. */
@Composable
fun DeliverySettingsScreen(
    onBack: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    DeliverySettingsContent(
        deliveryMode = state.settings.deliveryMode,
        pushAvailability = state.pushAvailability,
        pushProvider = state.pushProvider,
        onBack = onBack,
        onDeliveryMode = viewModel::setDeliveryMode,
        onPushProvider = viewModel::setPushProvider,
        onSelectDistributor = viewModel::selectPushDistributor,
        onRetryPush = viewModel::retryPushSetup,
    )
}

@Composable
fun DeliverySettingsContent(
    deliveryMode: DeliveryMode,
    pushAvailability: PushAvailability,
    pushProvider: PushProvider,
    onBack: () -> Unit,
    onDeliveryMode: (DeliveryMode) -> Unit,
    onPushProvider: (PushProvider) -> Unit,
    onSelectDistributor: (String) -> Unit = {},
    onRetryPush: () -> Unit = {},
) {
    val context = LocalContext.current
    var showDistributorChooser by remember { mutableStateOf(false) }
    SettingsScaffold(title = stringResource(R.string.settings_delivery), onBack = onBack) {
        val distributorUrl = stringResource(R.string.settings_delivery_push_distributor_url)
        SettingsGroup(title = stringResource(R.string.settings_delivery_method)) {
            DeliveryGroup(
                current = deliveryMode,
                availability = pushAvailability,
                provider = pushProvider,
                onSelect = onDeliveryMode,
                onSelectProvider = onPushProvider,
                onInstallDistributor = { context.startActivity(Intent(Intent.ACTION_VIEW, distributorUrl.toUri())) },
                onChooseDistributor = { showDistributorChooser = true },
                onRetryPush = onRetryPush,
                onFixNotifications = {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                },
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_background_reliability)) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_battery)) },
                supportingContent = {
                    Text(
                        stringResource(
                            when {
                                deliveryMode != DeliveryMode.UNIFIED_PUSH -> R.string.settings_battery_desc
                                pushAvailability.protectedNetworks in 1 until pushAvailability.eligibleNetworks ->
                                    R.string.settings_battery_hybrid_desc
                                else -> R.string.settings_battery_push_desc
                            },
                        ),
                    )
                },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                modifier = Modifier.clickable {
                    val pm = context.getSystemService(PowerManager::class.java)
                    val targetPackage = if (deliveryMode == DeliveryMode.UNIFIED_PUSH) {
                        pushAvailability.selectedDistributor ?: context.packageName
                    } else context.packageName
                    if (pm?.isIgnoringBatteryOptimizations(targetPackage) == true) {
                        context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } else {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData("package:$targetPackage".toUri()),
                        )
                    }
                },
            )
        }
    }
    if (showDistributorChooser) {
        AlertDialog(
            onDismissRequest = { showDistributorChooser = false },
            title = { Text(stringResource(R.string.settings_delivery_push_choose)) },
            text = {
                Column {
                    pushAvailability.distributors.forEach { distributor ->
                        TextButton(
                            modifier = Modifier.testTag("settings_push_distributor_${distributor.packageName}"),
                            onClick = {
                                showDistributorChooser = false
                                onSelectDistributor(distributor.packageName)
                            },
                        ) { Text(distributor.label) }
                    }
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun DeliveryGroup(
    current: DeliveryMode,
    availability: PushAvailability,
    provider: PushProvider,
    onSelect: (DeliveryMode) -> Unit,
    onSelectProvider: (PushProvider) -> Unit,
    onInstallDistributor: () -> Unit,
    onChooseDistributor: () -> Unit,
    onRetryPush: () -> Unit,
    onFixNotifications: () -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        RadioRow(
            label = stringResource(R.string.settings_delivery_socket),
            subtitle = stringResource(R.string.settings_delivery_socket_desc),
            selected = current == DeliveryMode.PERSISTENT_SOCKET,
            enabled = true,
            onClick = { onSelect(DeliveryMode.PERSISTENT_SOCKET) },
        )
        // Selectable once the bouncer advertises webpush; a missing distributor is surfaced as
        // actionable guidance rather than a silently-disabled control (registration self-heals when
        // a distributor is installed). Only the missing-webpush case disables the control.
        val subtitle = when {
            availability.needsDistributor -> stringResource(R.string.settings_delivery_push_needs_distributor)
            availability.selectable -> stringResource(R.string.settings_delivery_push_desc)
            else -> stringResource(R.string.settings_delivery_push_unavailable)
        }
        RadioRow(
            label = stringResource(R.string.settings_delivery_push),
            subtitle = subtitle,
            selected = current == DeliveryMode.UNIFIED_PUSH && provider == PushProvider.UNIFIED_PUSH,
            enabled = availability.selectable,
            onClick = {
                onSelectProvider(PushProvider.UNIFIED_PUSH)
                if (availability.distributors.size > 1 && availability.selectedDistributor == null) {
                    onChooseDistributor()
                }
            },
            modifier = Modifier.testTag("settings_unified_push_row"),
        )
        // Install-a-distributor action, shown only when push is selectable but no distributor exists.
        // Opens ntfy's F-Droid listing so the user can fix the missing-distributor gap in one tap.
        if (availability.needsDistributor) {
            TextButton(
                onClick = onInstallDistributor,
                modifier = Modifier
                    .padding(start = 52.dp)
                    .testTag("settings_install_distributor"),
            ) {
                Text(stringResource(R.string.settings_delivery_push_install_distributor))
            }
        }
        if (availability.fcmAvailable) {
            RadioRow(
                label = stringResource(R.string.settings_delivery_fcm),
                subtitle = if (availability.selectable) {
                    stringResource(R.string.settings_delivery_fcm_desc)
                } else {
                    stringResource(R.string.settings_delivery_push_unavailable)
                },
                selected = current == DeliveryMode.UNIFIED_PUSH && provider == PushProvider.FCM,
                enabled = availability.selectable,
                onClick = { onSelectProvider(PushProvider.FCM) },
            )
        }
        if (current == DeliveryMode.UNIFIED_PUSH && provider == PushProvider.UNIFIED_PUSH) {
            PushStatusCard(
                availability = availability,
                onChooseDistributor = onChooseDistributor,
                onRetry = onRetryPush,
                onFixNotifications = onFixNotifications,
            )
        }
    }
}

@Composable
private fun PushStatusCard(
    availability: PushAvailability,
    onChooseDistributor: () -> Unit,
    onRetry: () -> Unit,
    onFixNotifications: () -> Unit,
) {
    val title = stringResource(
        when (availability.setupStatus) {
            PushSetupStatus.CHOOSE_DISTRIBUTOR -> R.string.settings_delivery_push_status_choose
            PushSetupStatus.REQUESTING_ENDPOINT -> R.string.settings_delivery_push_status_endpoint
            PushSetupStatus.VERIFYING -> R.string.settings_delivery_push_status_verifying
            PushSetupStatus.ACTIVE -> R.string.settings_delivery_push_status_active
            PushSetupStatus.PARTIAL_FALLBACK -> R.string.settings_delivery_push_status_partial
            PushSetupStatus.NEEDS_ATTENTION -> R.string.settings_delivery_push_status_attention
        },
    )
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("settings_push_status_card"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            availability.distributors.firstOrNull { it.packageName == availability.selectedDistributor }
                ?.let { Text(it.label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium) }
            if (availability.eligibleNetworks > 0) {
                Text(pluralStringResource(
                    R.plurals.settings_delivery_push_coverage,
                    availability.eligibleNetworks,
                    availability.protectedNetworks,
                    availability.eligibleNetworks,
                ))
            }
            availability.lastSuccessAt?.let {
                Text(stringResource(
                    R.string.settings_delivery_push_last_success,
                    DateUtils.getRelativeTimeSpanString(it).toString(),
                ))
            }
            availability.errorCode?.let {
                Text(stringResource(R.string.settings_delivery_push_error, it.replace('_', ' ').lowercase()))
            }
            Text(stringResource(R.string.settings_delivery_push_scope))
            androidx.compose.foundation.layout.Row {
                if (availability.distributors.isNotEmpty()) {
                    TextButton(
                        onClick = onChooseDistributor,
                        modifier = Modifier.testTag("settings_push_choose_distributor"),
                    ) {
                        Text(stringResource(
                            if (availability.selectedDistributor == null) R.string.settings_delivery_push_choose
                            else R.string.settings_delivery_push_change,
                        ))
                    }
                }
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("settings_push_retry"),
                ) { Text(stringResource(R.string.settings_delivery_push_retry)) }
            }
            if (!availability.notificationsGranted) {
                TextButton(
                    onClick = onFixNotifications,
                    modifier = Modifier.testTag("settings_push_fix_notifications"),
                ) { Text(stringResource(R.string.settings_delivery_push_fix_notifications)) }
            }
        }
    }
}

@Preview
@Composable
private fun DeliverySettingsPreview() {
    MotdTheme {
        DeliverySettingsContent(
            deliveryMode = DeliveryMode.PERSISTENT_SOCKET,
            pushAvailability = PushAvailability(),
            pushProvider = PushProvider.UNIFIED_PUSH,
            onBack = {}, onDeliveryMode = {},
            onPushProvider = {},
        )
    }
}
