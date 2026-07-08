package io.github.trevarj.motd.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel and drives navigation. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onOpenNetwork: (Long) -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    SettingsContent(
        state = state,
        onBack = onBack,
        onOpenNetwork = onOpenNetwork,
        onThemeMode = viewModel::setThemeMode,
        onDynamicColor = viewModel::setDynamicColor,
        onDeliveryMode = viewModel::setDeliveryMode,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenNetwork: (Long) -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onDeliveryMode: (DeliveryMode) -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.onboarding_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            // Appearance ---------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_appearance))
            ThemeModeGroup(current = state.settings.themeMode, onSelect = onThemeMode)
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(R.string.settings_dynamic_color_desc),
                checked = state.settings.dynamicColor,
                onCheckedChange = onDynamicColor,
            )

            HorizontalDivider()

            // Delivery -----------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_delivery))
            DeliveryGroup(
                current = state.settings.deliveryMode,
                pushAvailable = state.pushAvailable,
                onSelect = onDeliveryMode,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_battery)) },
                supportingContent = { Text(stringResource(R.string.settings_battery_desc)) },
                modifier = Modifier.clickable {
                    // Ask the OS to exempt MOTD from battery optimizations.
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                    )
                },
            )

            HorizontalDivider()

            // Networks -----------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_networks))
            state.networks.forEach { network ->
                ListItem(
                    headlineContent = { Text(network.name) },
                    supportingContent = { Text("${network.host}:${network.port}") },
                    modifier = Modifier.clickable { onOpenNetwork(network.id) },
                )
            }

            HorizontalDivider()

            // About --------------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_version)) },
                supportingContent = { Text(appVersion(context)) },
            )
            val githubUrl = stringResource(R.string.settings_github_url)
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_github)) },
                supportingContent = { Text(githubUrl) },
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                },
            )
        }
    }
}

@Composable
private fun ThemeModeGroup(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(
        ThemeMode.SYSTEM to R.string.settings_theme_system,
        ThemeMode.LIGHT to R.string.settings_theme_light,
        ThemeMode.DARK to R.string.settings_theme_dark,
        ThemeMode.AMOLED to R.string.settings_theme_amoled,
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (mode, labelRes) ->
            RadioRow(
                label = stringResource(labelRes),
                selected = current == mode,
                enabled = true,
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun DeliveryGroup(
    current: DeliveryMode,
    pushAvailable: Boolean,
    onSelect: (DeliveryMode) -> Unit,
) {
    Column(Modifier.selectableGroup()) {
        RadioRow(
            label = stringResource(R.string.settings_delivery_socket),
            subtitle = stringResource(R.string.settings_delivery_socket_desc),
            selected = current == DeliveryMode.PERSISTENT_SOCKET,
            enabled = true,
            onClick = { onSelect(DeliveryMode.PERSISTENT_SOCKET) },
        )
        RadioRow(
            label = stringResource(R.string.settings_delivery_push),
            subtitle = if (pushAvailable) stringResource(R.string.settings_delivery_push_desc)
            else stringResource(R.string.settings_delivery_push_unavailable),
            selected = current == DeliveryMode.UNIFIED_PUSH,
            enabled = pushAvailable,
            onClick = { onSelect(DeliveryMode.UNIFIED_PUSH) },
        )
    }
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                label,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun appVersion(context: android.content.Context): String =
    runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

@Preview
@Composable
private fun SettingsContentPreview() {
    MotdTheme {
        SettingsContent(
            state = SettingsUiState(
                settings = Settings(themeMode = ThemeMode.DARK, dynamicColor = true),
                networks = listOf(
                    NetworkEntity(
                        id = 1, name = "Libera", role = NetworkRole.DIRECT,
                        host = "irc.libera.chat", port = 6697,
                        nick = "me", username = "me", realname = "Me",
                    ),
                ),
                pushAvailable = false,
            ),
            onBack = {}, onOpenNetwork = {}, onThemeMode = {}, onDynamicColor = {}, onDeliveryMode = {},
        )
    }
}
