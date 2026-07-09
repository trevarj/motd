package io.github.trevarj.motd.ui.settings

import android.content.Intent
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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.core.net.toUri
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.ui.about.appVersion
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Stateful entry: wires the ViewModel and drives navigation. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onOpenNetwork: (Long) -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenFriends: () -> Unit = {},
    onOpenFools: () -> Unit = {},
    onOpenNickColors: () -> Unit = {},
    // Round 5 (plans/16): add-network entry. Body lands in WP-V2.
    onOpenAddNetwork: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    SettingsContent(
        state = state,
        onBack = onBack,
        onOpenNetwork = onOpenNetwork,
        onOpenAbout = onOpenAbout,
        onOpenFriends = onOpenFriends,
        onOpenFools = onOpenFools,
        onOpenNickColors = onOpenNickColors,
        onOpenAddNetwork = onOpenAddNetwork,
        onThemeMode = viewModel::setThemeMode,
        onDynamicColor = viewModel::setDynamicColor,
        onDeliveryMode = viewModel::setDeliveryMode,
        onLayoutDensity = viewModel::setLayoutDensity,
        onNickColorsEnabled = viewModel::setNickColorsEnabled,
        onNickColorPalette = viewModel::setNickColorPalette,
        onShowJoinPartQuit = viewModel::setShowJoinPartQuit,
        onFoolsMode = viewModel::setFoolsMode,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenNetwork: (Long) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenFools: () -> Unit,
    onOpenNickColors: () -> Unit,
    onOpenAddNetwork: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onDeliveryMode: (DeliveryMode) -> Unit,
    onLayoutDensity: (LayoutDensity) -> Unit,
    onNickColorsEnabled: (Boolean) -> Unit,
    onNickColorPalette: (NickColorPalette) -> Unit,
    onShowJoinPartQuit: (Boolean) -> Unit,
    onFoolsMode: (FoolsMode) -> Unit,
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
                switchTag = "settings_switch_dynamic_color",
            )
            SubLabel(stringResource(R.string.settings_density))
            DensityGroup(current = state.settings.layoutDensity, onSelect = onLayoutDensity)

            HorizontalDivider()

            // Chat -------------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_chat))
            SwitchRow(
                title = stringResource(R.string.settings_nick_colors),
                subtitle = stringResource(R.string.settings_nick_colors_desc),
                checked = state.settings.nickColorsEnabled,
                onCheckedChange = onNickColorsEnabled,
                switchTag = "settings_switch_nick_colors",
            )
            PaletteGroup(
                current = state.settings.nickColorPalette,
                enabled = state.settings.nickColorsEnabled,
                onSelect = onNickColorPalette,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_nick_color_overrides)) },
                supportingContent = countText(state.settings.nickColorOverrides.size),
                modifier = Modifier.clickable { onOpenNickColors() },
            )
            SwitchRow(
                title = stringResource(R.string.settings_show_jpq),
                subtitle = stringResource(R.string.settings_show_jpq_desc),
                checked = state.settings.showJoinPartQuit,
                onCheckedChange = onShowJoinPartQuit,
                switchTag = "settings_switch_show_jpq",
            )

            HorizontalDivider()

            // People -----------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_people))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_friends)) },
                supportingContent = countText(state.settings.friends.size),
                modifier = Modifier.clickable { onOpenFriends() },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_fools)) },
                supportingContent = countText(state.settings.fools.size),
                modifier = Modifier.clickable { onOpenFools() },
            )
            SubLabel(stringResource(R.string.settings_fools_mode))
            FoolsModeGroup(current = state.settings.foolsMode, onSelect = onFoolsMode)

            HorizontalDivider()

            // Delivery -----------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_delivery))
            val distributorUrl = stringResource(R.string.settings_delivery_push_distributor_url)
            DeliveryGroup(
                current = state.settings.deliveryMode,
                availability = state.pushAvailability,
                onSelect = onDeliveryMode,
                // No distributor installed: guide the user to install one (ntfy on F-Droid) via an
                // ACTION_VIEW web intent. Registration self-heals once a distributor appears.
                onInstallDistributor = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, distributorUrl.toUri()))
                },
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
                    supportingContent = { Text(networkSupporting(network, state.networks)) },
                    modifier = Modifier.clickable { onOpenNetwork(network.id) },
                )
            }
            // Round 5 (plans/16 §5.2): add-network entry after the per-network rows.
            ListItem(
                headlineContent = { Text(stringResource(R.string.add_network_title)) },
                leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
                modifier = Modifier.clickable { onOpenAddNetwork() },
            )

            HorizontalDivider()

            // About --------------------------------------------------------------------------
            SectionHeader(stringResource(R.string.settings_about))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_about)) },
                supportingContent = { Text(appVersion(context)) },
                modifier = Modifier.clickable { onOpenAbout() },
            )
        }
    }
}

/**
 * "host:port" plus a role suffix for soju rows (plans/16 §5.2): " · soju" for a BOUNCER_ROOT,
 * " · via <root name>" for a BOUNCER_CHILD (root name resolved from [all]).
 */
@Composable
private fun networkSupporting(network: NetworkEntity, all: List<NetworkEntity>): String {
    val base = "${network.host}:${network.port}"
    return when (network.role) {
        NetworkRole.BOUNCER_ROOT -> stringResource(R.string.settings_network_soju_suffix, base)
        NetworkRole.BOUNCER_CHILD -> {
            val rootName = all.firstOrNull { it.id == network.parentId }?.name
            if (rootName != null) stringResource(R.string.settings_network_via_suffix, base, rootName) else base
        }
        NetworkRole.DIRECT -> base
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
private fun DensityGroup(current: LayoutDensity, onSelect: (LayoutDensity) -> Unit) {
    // Density selects the message *render style*, not the font size: Compact is classic single-line
    // IRC, Comfortable/Cozy are chat bubbles (Cozy roomier). Subtitles spell that out.
    val options = listOf(
        Triple(LayoutDensity.COMPACT, R.string.settings_density_compact, R.string.settings_density_compact_desc),
        Triple(LayoutDensity.COMFORTABLE, R.string.settings_density_comfortable, R.string.settings_density_comfortable_desc),
        Triple(LayoutDensity.COZY, R.string.settings_density_cozy, R.string.settings_density_cozy_desc),
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (density, labelRes, descRes) ->
            RadioRow(
                label = stringResource(labelRes),
                subtitle = stringResource(descRes),
                selected = current == density,
                enabled = true,
                onClick = { onSelect(density) },
            )
        }
    }
}

@Composable
private fun PaletteGroup(
    current: NickColorPalette,
    enabled: Boolean,
    onSelect: (NickColorPalette) -> Unit,
) {
    val options = listOf(
        NickColorPalette.DEFAULT to R.string.settings_palette_default,
        NickColorPalette.VIVID to R.string.settings_palette_vivid,
        NickColorPalette.PASTEL to R.string.settings_palette_pastel,
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (palette, labelRes) ->
            RadioRow(
                label = stringResource(labelRes),
                selected = current == palette,
                enabled = enabled,
                onClick = { onSelect(palette) },
            )
        }
    }
}

@Composable
private fun FoolsModeGroup(current: FoolsMode, onSelect: (FoolsMode) -> Unit) {
    Column(Modifier.selectableGroup()) {
        RadioRow(
            label = stringResource(R.string.settings_fools_collapse),
            subtitle = stringResource(R.string.settings_fools_collapse_desc),
            selected = current == FoolsMode.COLLAPSE,
            enabled = true,
            onClick = { onSelect(FoolsMode.COLLAPSE) },
        )
        RadioRow(
            label = stringResource(R.string.settings_fools_hide),
            subtitle = stringResource(R.string.settings_fools_hide_desc),
            selected = current == FoolsMode.HIDE,
            enabled = true,
            onClick = { onSelect(FoolsMode.HIDE) },
        )
    }
}

/** Supporting text showing the pluralized nick count, or null when the list is empty. */
@Composable
private fun countText(count: Int): (@Composable () -> Unit)? =
    if (count > 0) {
        { Text(pluralStringResource(R.plurals.settings_nick_count, count, count)) }
    } else {
        null
    }

@Composable
private fun DeliveryGroup(
    current: DeliveryMode,
    availability: PushAvailability,
    onSelect: (DeliveryMode) -> Unit,
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
            selected = current == DeliveryMode.UNIFIED_PUSH,
            enabled = availability.selectable,
            onClick = { onSelect(DeliveryMode.UNIFIED_PUSH) },
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
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTag: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Stable handle so the harness reads/sets the checked state directly (plans/18 §4).
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (switchTag != null) Modifier.testTag(switchTag) else Modifier,
        )
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

/** Dimmed sub-section label above an inline radio group (density, fools mode). */
@Composable
private fun SubLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Preview
@Composable
private fun SettingsContentPreview() {
    MotdTheme {
        SettingsContent(
            state = SettingsUiState(
                settings = Settings(
                    themeMode = ThemeMode.DARK,
                    dynamicColor = true,
                    friends = setOf("alice"),
                    fools = setOf("bob", "carol"),
                    nickColorOverrides = mapOf("alice" to 210),
                ),
                networks = listOf(
                    NetworkEntity(
                        id = 1, name = "Libera", role = NetworkRole.DIRECT,
                        host = "irc.libera.chat", port = 6697,
                        nick = "me", username = "me", realname = "Me",
                    ),
                ),
                pushAvailability = PushAvailability(),
            ),
            onBack = {}, onOpenNetwork = {}, onOpenAbout = {},
            onOpenFriends = {}, onOpenFools = {}, onOpenNickColors = {}, onOpenAddNetwork = {},
            onThemeMode = {}, onDynamicColor = {}, onDeliveryMode = {},
            onLayoutDensity = {}, onNickColorsEnabled = {}, onNickColorPalette = {},
            onShowJoinPartQuit = {}, onFoolsMode = {},
        )
    }
}
