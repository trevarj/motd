package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.about.appVersion

/**
 * Top-level Settings screen: a short list of category rows that each open a focused sub-screen. The
 * long flat list was split into Appearance / Chat / Notifications & delivery / Networks / About for
 * discoverability; every individual setting still lives under exactly one category and keeps its
 * original wiring (see the per-category screens in this package).
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    onOpenAppearance: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    onOpenDelivery: () -> Unit = {},
    onOpenNetworks: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    SettingsContent(
        onBack = onBack,
        onOpenAppearance = onOpenAppearance,
        onOpenChat = onOpenChat,
        onOpenDelivery = onOpenDelivery,
        onOpenNetworks = onOpenNetworks,
        onOpenAbout = onOpenAbout,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenDelivery: () -> Unit,
    onOpenNetworks: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    SettingsScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        CategoryRow(
            title = stringResource(R.string.settings_appearance),
            summary = stringResource(R.string.settings_appearance_summary),
            onClick = onOpenAppearance,
        )
        CategoryRow(
            title = stringResource(R.string.settings_chat),
            summary = stringResource(R.string.settings_chat_summary),
            onClick = onOpenChat,
        )
        CategoryRow(
            title = stringResource(R.string.settings_delivery),
            summary = stringResource(R.string.settings_delivery_summary),
            onClick = onOpenDelivery,
        )
        CategoryRow(
            title = stringResource(R.string.settings_networks),
            summary = stringResource(R.string.settings_networks_summary),
            onClick = onOpenNetworks,
        )
        CategoryRow(
            title = stringResource(R.string.settings_about),
            summary = appVersion(context),
            onClick = onOpenAbout,
        )
    }
}

/** A tappable category row in the top-level Settings list. */
@Composable
private fun CategoryRow(title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        modifier = Modifier.clickable { onClick() },
    )
}

// -- Shared building blocks (used by the category sub-screens in this package) --------------------

/** Standard settings sub-screen scaffold: back-navigable top bar + vertically scrolling column. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.onboarding_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()),
        ) {
            content()
        }
    }
}

/**
 * "host:port" plus a role suffix for soju rows (plans/16 §5.2): " · soju" for a BOUNCER_ROOT,
 * " · via <root name>" for a BOUNCER_CHILD (root name resolved from [all]).
 */
@Composable
internal fun networkSupporting(network: NetworkEntity, all: List<NetworkEntity>): String {
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

/** Supporting text showing the pluralized nick count, or null when the list is empty. */
@Composable
internal fun countText(count: Int): (@Composable () -> Unit)? =
    if (count > 0) {
        { Text(pluralStringResource(R.plurals.settings_nick_count, count, count)) }
    } else {
        null
    }

@Composable
internal fun RadioRow(
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
internal fun SwitchRow(
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
internal fun SectionHeader(text: String) {
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
internal fun SubLabel(text: String) {
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
    io.github.trevarj.motd.ui.theme.MotdTheme {
        SettingsContent(
            onBack = {}, onOpenAppearance = {}, onOpenChat = {},
            onOpenDelivery = {}, onOpenNetworks = {}, onOpenAbout = {},
        )
    }
}
