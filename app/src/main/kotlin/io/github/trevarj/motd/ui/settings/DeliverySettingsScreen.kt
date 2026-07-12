package io.github.trevarj.motd.ui.settings

import android.content.Intent
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
) {
    val context = LocalContext.current
    SettingsScaffold(title = stringResource(R.string.settings_delivery), onBack = onBack) {
        val distributorUrl = stringResource(R.string.settings_delivery_push_distributor_url)
        DeliveryGroup(
            current = deliveryMode,
            availability = pushAvailability,
            provider = pushProvider,
            onSelect = onDeliveryMode,
            onSelectProvider = onPushProvider,
            // No distributor installed: guide the user to install one (ntfy on F-Droid) via an
            // ACTION_VIEW web intent. Registration self-heals once a distributor appears.
            onInstallDistributor = {
                context.startActivity(Intent(Intent.ACTION_VIEW, distributorUrl.toUri()))
            },
        )

        HorizontalDivider()

        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_battery)) },
            supportingContent = { Text(stringResource(R.string.settings_battery_desc)) },
            modifier = Modifier.clickable {
                // Directly request the Doze exemption for this app (keeps the persistent socket
                // alive in the background). If already exempt, open the OS list so the user can
                // review/revoke it.
                val pm = context.getSystemService(PowerManager::class.java)
                if (pm?.isIgnoringBatteryOptimizations(context.packageName) == true) {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                } else {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData("package:${context.packageName}".toUri()),
                    )
                }
            },
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
            onClick = { onSelectProvider(PushProvider.UNIFIED_PUSH) },
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
