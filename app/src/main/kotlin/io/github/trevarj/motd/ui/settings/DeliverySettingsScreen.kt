package io.github.trevarj.motd.ui.settings

import android.content.Intent
import android.os.PowerManager
import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.isBootReceiverEnabled
import io.github.trevarj.motd.service.setBootReceiverEnabled
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import android.provider.Settings as AndroidSettings

/** Notifications & delivery category: delivery mode (persistent/push) and battery optimization. */
@Composable
fun DeliverySettingsScreen(
    onBack: () -> Unit = {},
    target: SettingsTarget? = null,
    viewModel: DeliverySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var startOnBoot by remember(context) { mutableStateOf(isBootReceiverEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel) { viewModel.refreshNotificationPermission() }
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer =
            object : DefaultLifecycleObserver {
                override fun onResume(owner: LifecycleOwner) {
                    viewModel.refreshNotificationPermission()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DeliverySettingsContent(
        deliveryMode = state.deliveryMode,
        pushAvailability = state.pushAvailability,
        onBack = onBack,
        onDeliveryMode = viewModel::setDeliveryMode,
        startOnBoot = startOnBoot,
        onStartOnBoot = { enabled ->
            setBootReceiverEnabled(context, enabled)
            startOnBoot = enabled
        },
        onSelectDistributor = viewModel::selectPushDistributor,
        onRetryPush = viewModel::retryPushSetup,
        target = target,
    )
}

@Composable
fun DeliverySettingsContent(
    deliveryMode: DeliveryMode,
    pushAvailability: PushAvailability,
    onBack: () -> Unit,
    onDeliveryMode: (DeliveryMode) -> Unit,
    startOnBoot: Boolean = true,
    onStartOnBoot: (Boolean) -> Unit = {},
    onSelectDistributor: (String) -> Unit = {},
    onRetryPush: () -> Unit = {},
    target: SettingsTarget? = null,
) {
    val context = LocalContext.current
    var showDistributorChooser by remember { mutableStateOf(false) }
    SettingsScaffold(title = stringResource(R.string.settings_delivery), onBack = onBack) {
        val distributorUrl = stringResource(R.string.settings_delivery_push_distributor_url)
        SettingsGroup(title = stringResource(R.string.settings_delivery_method)) {
            DeliveryGroup(
                current = deliveryMode,
                availability = pushAvailability,
                onSelect = onDeliveryMode,
                onInstallDistributor = { context.startActivity(Intent(Intent.ACTION_VIEW, distributorUrl.toUri())) },
                onChooseDistributor = { showDistributorChooser = true },
                onRetryPush = onRetryPush,
                target = target,
            )
            // Permission is re-checked in onResume, so the warning flips while the user watches the
            // screen re-appear; ease the height change instead of jumping.
            AnimatedVisibility(
                visible = !pushAvailability.notificationsGranted,
                enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
                exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
            ) {
                NotificationPermissionWarning(
                    onFixNotifications = {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    },
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_background_reliability)) {
            SettingsTarget(target?.name, SettingsTarget.START_ON_BOOT.name) { targetModifier ->
                SwitchRow(
                    title = stringResource(R.string.settings_start_on_boot),
                    subtitle = stringResource(R.string.settings_start_on_boot_desc),
                    checked = startOnBoot,
                    onCheckedChange = onStartOnBoot,
                    switchTag = "settings_start_on_boot",
                    modifier = targetModifier,
                )
            }
            SettingsTarget(target?.name, SettingsTarget.BATTERY.name) { targetModifier ->
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_battery),
                    summary =
                        stringResource(
                            when {
                                deliveryMode != DeliveryMode.UNIFIED_PUSH -> R.string.settings_battery_desc
                                pushAvailability.protectedNetworks in 1 until pushAvailability.eligibleNetworks -> R.string.settings_battery_hybrid_desc
                                else -> R.string.settings_battery_push_desc
                            },
                        ),
                    modifier = targetModifier.testTag("settings_battery_optimization"),
                    onClick = {
                        val pm = context.getSystemService(PowerManager::class.java)
                        val targetPackage =
                            if (deliveryMode == DeliveryMode.UNIFIED_PUSH) {
                                pushAvailability.selectedDistributor ?: context.packageName
                            } else {
                                context.packageName
                            }
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
    }
    if (showDistributorChooser) {
        AlertDialog(
            onDismissRequest = { showDistributorChooser = false },
            title = { Text(stringResource(R.string.settings_delivery_push_choose)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                    pushAvailability.distributors.forEach { distributor ->
                        RadioRow(
                            label = distributor.label,
                            selected = pushAvailability.selectedDistributor == distributor.packageName,
                            enabled = true,
                            modifier = Modifier.testTag("settings_push_distributor_${distributor.packageName}"),
                            onClick = {
                                showDistributorChooser = false
                                onSelectDistributor(distributor.packageName)
                            },
                        )
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
    onSelect: (DeliveryMode) -> Unit,
    onInstallDistributor: () -> Unit,
    onChooseDistributor: () -> Unit,
    onRetryPush: () -> Unit,
    target: SettingsTarget? = null,
) {
    Column(Modifier.selectableGroup()) {
        SettingsTarget(
            if (target == SettingsTarget.DELIVERY) SettingsTarget.PERSISTENT_DELIVERY.name else target?.name,
            SettingsTarget.PERSISTENT_DELIVERY.name,
        ) { targetModifier ->
            RadioRow(
                label = stringResource(R.string.settings_delivery_socket),
                subtitle = stringResource(R.string.settings_delivery_socket_desc),
                selected = current == DeliveryMode.PERSISTENT_SOCKET,
                enabled = true,
                onClick = { onSelect(DeliveryMode.PERSISTENT_SOCKET) },
                modifier = targetModifier,
            )
        }
        // Selectable once the bouncer advertises webpush; a missing distributor is surfaced as
        // actionable guidance rather than a silently-disabled control (registration self-heals when
        // a distributor is installed). Only the missing-webpush case disables the control.
        val subtitle =
            when {
                availability.needsDistributor -> stringResource(R.string.settings_delivery_push_needs_distributor)
                availability.selectable -> stringResource(R.string.settings_delivery_push_desc)
                else -> stringResource(R.string.settings_delivery_push_unavailable)
            }
        SettingsTarget(target?.name, SettingsTarget.UNIFIED_PUSH.name) { targetModifier ->
            RadioRow(
                label = stringResource(R.string.settings_delivery_push),
                subtitle = subtitle,
                selected = current == DeliveryMode.UNIFIED_PUSH,
                enabled = availability.selectable,
                onClick = {
                    onSelect(DeliveryMode.UNIFIED_PUSH)
                    if (availability.distributors.size > 1 && availability.selectedDistributor == null) {
                        onChooseDistributor()
                    }
                },
                modifier = targetModifier.testTag("settings_unified_push_row"),
            )
        }
        // Install-a-distributor action, shown only when push is selectable but no distributor exists.
        // Opens ntfy's F-Droid listing so the user can fix the missing-distributor gap in one tap.
        AnimatedVisibility(
            visible = availability.needsDistributor,
            enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
            exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
        ) {
            TextButton(
                onClick = onInstallDistributor,
                modifier =
                    Modifier
                        .padding(start = 52.dp)
                        .testTag("settings_install_distributor"),
            ) {
                Text(stringResource(R.string.settings_delivery_push_install_distributor))
            }
        }
        // The status card appears the moment the user selects the push radio; ease it in so the
        // radio group below doesn't jump under their finger.
        AnimatedVisibility(
            visible = current == DeliveryMode.UNIFIED_PUSH,
            enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
            exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
        ) {
            PushStatusCard(
                availability = availability,
                onChooseDistributor = onChooseDistributor,
                onRetry = onRetryPush,
            )
        }
    }
}

@Composable
private fun PushStatusCard(
    availability: PushAvailability,
    onChooseDistributor: () -> Unit,
    onRetry: () -> Unit,
) {
    val title =
        stringResource(
            when (availability.setupStatus) {
                PushSetupStatus.CHOOSE_DISTRIBUTOR -> R.string.settings_delivery_push_status_choose
                PushSetupStatus.REQUESTING_ENDPOINT -> R.string.settings_delivery_push_status_endpoint
                PushSetupStatus.WAITING_FOR_SERVER -> R.string.settings_delivery_push_status_server
                PushSetupStatus.VERIFYING -> R.string.settings_delivery_push_status_verifying
                PushSetupStatus.ACTIVE -> R.string.settings_delivery_push_status_active
                PushSetupStatus.PARTIAL_FALLBACK -> R.string.settings_delivery_push_status_partial
                PushSetupStatus.NEEDS_ATTENTION -> R.string.settings_delivery_push_status_attention
            },
        )
    Card(
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("settings_push_status_card"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            availability.distributors
                .firstOrNull { it.packageName == availability.selectedDistributor }
                ?.let { Text(it.label, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium) }
            if (availability.eligibleNetworks > 0) {
                Text(
                    pluralStringResource(
                        R.plurals.settings_delivery_push_coverage,
                        availability.eligibleNetworks,
                        availability.protectedNetworks,
                        availability.eligibleNetworks,
                    ),
                )
            }
            availability.lastSuccessAt?.let {
                Text(
                    stringResource(
                        R.string.settings_delivery_push_last_success,
                        DateUtils.getRelativeTimeSpanString(it).toString(),
                    ),
                )
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
                        Text(
                            stringResource(
                                if (availability.selectedDistributor == null) {
                                    R.string.settings_delivery_push_choose
                                } else {
                                    R.string.settings_delivery_push_change
                                },
                            ),
                        )
                    }
                }
                TextButton(
                    onClick = onRetry,
                    modifier = Modifier.testTag("settings_push_retry"),
                ) { Text(stringResource(R.string.settings_delivery_push_retry)) }
            }
        }
    }
}

@Composable
private fun NotificationPermissionWarning(onFixNotifications: () -> Unit) {
    Card(
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag("settings_notification_permission_warning"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.settings_delivery_notifications_blocked),
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.settings_delivery_notifications_blocked_desc))
            TextButton(
                onClick = onFixNotifications,
                modifier = Modifier.testTag("settings_fix_notifications"),
            ) { Text(stringResource(R.string.settings_delivery_push_fix_notifications)) }
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
            onBack = {},
            onDeliveryMode = {},
        )
    }
}
