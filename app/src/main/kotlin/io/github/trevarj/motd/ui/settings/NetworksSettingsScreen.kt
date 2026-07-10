package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.theme.MotdTheme

/** Networks category: the per-network list plus the add-network entry (plans/16 §5.2). */
@Composable
fun NetworksSettingsScreen(
    onBack: () -> Unit = {},
    onOpenNetwork: (Long) -> Unit = {},
    onOpenAddNetwork: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    NetworksSettingsContent(
        networks = state.networks,
        onBack = onBack,
        onOpenNetwork = onOpenNetwork,
        onOpenAddNetwork = onOpenAddNetwork,
    )
}

@Composable
fun NetworksSettingsContent(
    networks: List<NetworkEntity>,
    onBack: () -> Unit,
    onOpenNetwork: (Long) -> Unit,
    onOpenAddNetwork: () -> Unit,
) {
    SettingsScaffold(title = stringResource(R.string.settings_networks), onBack = onBack) {
        networks.forEach { network ->
            ListItem(
                headlineContent = { Text(network.name) },
                supportingContent = { Text(networkSupporting(network, networks)) },
                modifier = Modifier.clickable { onOpenNetwork(network.id) },
            )
        }
        // Round 5 (plans/16 §5.2): add-network entry after the per-network rows.
        ListItem(
            headlineContent = { Text(stringResource(R.string.add_network_title)) },
            leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
            modifier = Modifier.clickable { onOpenAddNetwork() },
        )
    }
}

@Preview
@Composable
private fun NetworksSettingsPreview() {
    MotdTheme {
        NetworksSettingsContent(
            networks = listOf(
                NetworkEntity(
                    id = 1, name = "Libera", role = NetworkRole.DIRECT,
                    host = "irc.libera.chat", port = 6697,
                    nick = "me", username = "me", realname = "Me",
                ),
            ),
            onBack = {}, onOpenNetwork = {}, onOpenAddNetwork = {},
        )
    }
}
