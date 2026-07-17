package io.github.trevarj.motd.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
    var localPage by rememberSaveable { mutableStateOf(SettingsLocalPage.ROOT) }
    BackHandler(enabled = localPage != SettingsLocalPage.ROOT) { localPage = SettingsLocalPage.ROOT }
    if (localPage == SettingsLocalPage.UPLOADS) {
        SettingsScaffold(title = stringResource(R.string.settings_uploads), onBack = { localPage = SettingsLocalPage.ROOT }) {
            UploadsSettingsContent()
        }
    } else {
        SettingsContent(
            onBack = onBack,
            onOpenAppearance = onOpenAppearance,
            onOpenChat = onOpenChat,
            onOpenDelivery = onOpenDelivery,
            onOpenNetworks = onOpenNetworks,
            onOpenUploads = { localPage = SettingsLocalPage.UPLOADS },
            onOpenAbout = onOpenAbout,
        )
    }
}

private enum class SettingsLocalPage { ROOT, UPLOADS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    onBack: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenDelivery: () -> Unit,
    onOpenNetworks: () -> Unit,
    onOpenUploads: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    SettingsScaffold(title = stringResource(R.string.settings_title), onBack = onBack) {
        CategoryRow(
            icon = Icons.Outlined.Language,
            title = stringResource(R.string.settings_networks),
            summary = stringResource(R.string.settings_networks_summary),
            modifier = Modifier.testTag("settings_category_networks"),
            onClick = onOpenNetworks,
        )
        CategoryRow(
            icon = Icons.Outlined.Palette,
            title = stringResource(R.string.settings_appearance),
            summary = stringResource(R.string.settings_appearance_summary),
            modifier = Modifier.testTag("settings_category_appearance"),
            onClick = onOpenAppearance,
        )
        CategoryRow(
            icon = Icons.AutoMirrored.Outlined.Chat,
            title = stringResource(R.string.settings_chat),
            summary = stringResource(R.string.settings_chat_summary),
            modifier = Modifier.testTag("settings_category_chat"),
            onClick = onOpenChat,
        )
        CategoryRow(
            icon = Icons.Outlined.Notifications,
            title = stringResource(R.string.settings_delivery),
            summary = stringResource(R.string.settings_delivery_summary),
            modifier = Modifier.testTag("settings_category_delivery"),
            onClick = onOpenDelivery,
        )
        CategoryRow(
            icon = Icons.Outlined.CloudUpload,
            title = stringResource(R.string.settings_uploads),
            summary = stringResource(R.string.settings_uploads_summary),
            modifier = Modifier.testTag("settings_category_uploads"),
            onClick = onOpenUploads,
        )
        CategoryRow(
            icon = Icons.Outlined.Info,
            title = stringResource(R.string.settings_about),
            summary = stringResource(R.string.settings_about_summary, appVersion(context)),
            modifier = Modifier.testTag("settings_category_about"),
            onClick = onOpenAbout,
        )
    }
}

/** A tappable category row in the top-level Settings list. */
@Composable
private fun CategoryRow(
    icon: ImageVector,
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
    ) {
        SettingsNavigationRow(icon = icon, title = title, summary = summary, onClick = onClick)
    }
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
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
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
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = 720.dp).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
internal fun SettingsGroup(
    title: String? = null,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        title?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Column { content() }
        }
    }
}

@Composable
internal fun SettingsNavigationRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    value: String? = null,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(icon, contentDescription = null, modifier = Modifier.padding(10.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                value?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Icon(Icons.Outlined.ChevronRight, contentDescription = null)
            }
        },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).semantics { role = Role.Button },
    )
}

/**
 * "host:port" plus a role suffix for soju rows (plans/16 §5.2): " · soju" for a BOUNCER_ROOT,
 * " · via <root name>" for a BOUNCER_CHILD (root name resolved from [all]).
 */
@Composable
internal fun networkSupporting(
    network: NetworkEntity,
    all: List<NetworkEntity>,
    zncNetworkIds: Set<Long> = emptySet(),
): String {
    val base = "${network.host}:${network.port}"
    return when (network.role) {
        NetworkRole.BOUNCER_ROOT -> stringResource(R.string.settings_network_soju_suffix, base)
        NetworkRole.BOUNCER_CHILD -> {
            val rootName = all.firstOrNull { it.id == network.parentId }?.name
            if (rootName != null) stringResource(R.string.settings_network_via_suffix, base, rootName) else base
        }
        NetworkRole.DIRECT -> if (network.id in zncNetworkIds) {
            stringResource(R.string.settings_network_znc_suffix, base)
        } else {
            base
        }
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
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                label,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    switchTag: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Stable handle so the harness reads/sets the checked state directly.
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
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
            onOpenDelivery = {}, onOpenNetworks = {}, onOpenUploads = {}, onOpenAbout = {},
        )
    }
}
