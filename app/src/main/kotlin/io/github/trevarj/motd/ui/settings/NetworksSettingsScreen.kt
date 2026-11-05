package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

/** Networks category: the per-network list plus the add-network entry. */
@Composable
fun NetworksSettingsScreen(
    onBack: () -> Unit = {},
    onOpenNetwork: (Long) -> Unit = {},
    onOpenAddNetwork: () -> Unit = {},
    onScanInvite: () -> Unit = {},
    target: SettingsTarget? = null,
    viewModel: NetworksSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NetworksSettingsContent(
        networks = state.networks,
        zncNetworkIds = state.zncNetworkIds,
        connectionStates = state.connectionStates,
        target = target,
        onBack = onBack,
        onOpenNetwork = onOpenNetwork,
        onOpenAddNetwork = onOpenAddNetwork,
        onScanInvite = onScanInvite,
    )
}

@Composable
fun NetworksSettingsContent(
    networks: List<NetworkEntity>,
    zncNetworkIds: Set<Long> = emptySet(),
    connectionStates: Map<Long, IrcClientState> = emptyMap(),
    target: SettingsTarget? = null,
    onBack: () -> Unit,
    onOpenNetwork: (Long) -> Unit,
    onOpenAddNetwork: () -> Unit,
    onScanInvite: () -> Unit = {},
) {
    SettingsScaffold(title = stringResource(R.string.settings_networks), onBack = onBack) {
        SettingsTarget(target?.name, SettingsTarget.NETWORKS.name) { targetModifier ->
            FilledTonalButton(
                onClick = onOpenAddNetwork,
                modifier = targetModifier.fillMaxWidth().testTag("settings_add_network"),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.add_network_title))
            }
        }
        FilledTonalButton(
            onClick = onScanInvite,
            modifier = Modifier.fillMaxWidth().testTag("settings_scan_invite"),
        ) {
            Icon(Icons.Filled.QrCodeScanner, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.invite_scan_title))
        }
        if (networks.isEmpty()) {
            SettingsGroup {
                ListItem(
                    colors =
                        androidx.compose.material3.ListItemDefaults
                            .colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.settings_networks_empty_title)) },
                    supportingContent = { Text(stringResource(R.string.settings_networks_empty_message)) },
                    modifier = Modifier.testTag("settings_networks_empty"),
                )
            }
        }
        val organized = organizeNetworks(networks, zncNetworkIds)
        if (organized.bouncerRoots.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.settings_bouncers_section)) {
                organized.bouncerRoots.forEach { root ->
                    NetworkRow(root, networks, zncNetworkIds, connectionStates[root.id], onOpenNetwork)
                    organized.childrenByRoot[root.id].orEmpty().forEach { child ->
                        NetworkRow(child, networks, zncNetworkIds, connectionStates[child.id], onOpenNetwork, child = true)
                    }
                }
            }
        }
        if (organized.direct.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.settings_direct_networks_section)) {
                organized.direct.forEach { NetworkRow(it, networks, zncNetworkIds, connectionStates[it.id], onOpenNetwork) }
            }
        }
        if (organized.orphanChildren.isNotEmpty()) {
            SettingsGroup(title = stringResource(R.string.settings_managed_networks_section)) {
                organized.orphanChildren.forEach { NetworkRow(it, networks, zncNetworkIds, connectionStates[it.id], onOpenNetwork, child = true) }
            }
        }
    }
}

@Composable
private fun NetworkRow(
    network: NetworkEntity,
    all: List<NetworkEntity>,
    zncNetworkIds: Set<Long>,
    connectionState: IrcClientState?,
    onOpenNetwork: (Long) -> Unit,
    child: Boolean = false,
) {
    SettingsNavigationRow(
        title = network.name,
        summary =
            stringResource(
                R.string.settings_network_supporting_status,
                networkSupporting(network, all, zncNetworkIds),
                networkConnectionLabel(connectionState),
            ),
        modifier =
            Modifier
                .testTag("settings_network_row_${network.id}")
                .padding(start = if (child) 20.dp else 0.dp),
        onClick = { onOpenNetwork(network.id) },
    )
}

@Composable
private fun networkConnectionLabel(state: IrcClientState?): String =
    stringResource(
        when (state) {
            is IrcClientState.Ready -> R.string.network_settings_status_connected
            IrcClientState.Connecting, IrcClientState.Registering -> R.string.network_settings_status_connecting
            is IrcClientState.Failed -> R.string.network_settings_status_attention
            IrcClientState.Disconnected, null -> R.string.network_settings_status_disconnected
        },
    )

internal data class OrganizedNetworks(
    val bouncerRoots: List<NetworkEntity>,
    val direct: List<NetworkEntity>,
    val childrenByRoot: Map<Long, List<NetworkEntity>>,
    val orphanChildren: List<NetworkEntity>,
)

internal fun organizeNetworks(
    networks: List<NetworkEntity>,
    zncNetworkIds: Set<Long> = emptySet(),
): OrganizedNetworks {
    val byName = compareBy<NetworkEntity> { it.name.lowercase() }
    val sojuRoots = networks.filter { it.role == NetworkRole.BOUNCER_ROOT }
    val roots =
        (sojuRoots + networks.filter { it.role == NetworkRole.DIRECT && it.id in zncNetworkIds })
            .sortedWith(byName)
    val rootIds = sojuRoots.mapTo(mutableSetOf()) { it.id }
    val children = networks.filter { it.role == NetworkRole.BOUNCER_CHILD }
    return OrganizedNetworks(
        bouncerRoots = roots,
        direct = networks.filter { it.role == NetworkRole.DIRECT && it.id !in zncNetworkIds }.sortedWith(byName),
        childrenByRoot =
            children
                .filter { it.parentId in rootIds }
                .groupBy { it.parentId!! }
                .mapValues { (_, rows) -> rows.sortedWith(byName) },
        orphanChildren = children.filter { it.parentId !in rootIds }.sortedWith(byName),
    )
}

@Preview
@Composable
private fun NetworksSettingsPreview() {
    MotdTheme {
        NetworksSettingsContent(
            networks =
                listOf(
                    NetworkEntity(
                        id = 1,
                        name = "Libera",
                        role = NetworkRole.DIRECT,
                        host = "irc.libera.chat",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                ),
            onBack = {},
            onOpenNetwork = {},
            onOpenAddNetwork = {},
        )
    }
}
