package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.TabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.theme.MotdTheme

/**
 * Bottom sheet with two actions: join a channel or start a query. Network selection is a dropdown
 * (auto-selected when there is a single network). Emits the chosen network id + input; the caller
 * routes to [ConnectionManager.joinChannel] / [ConnectionManager.ensureQueryBuffer].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationSheet(
    networks: List<NetworkEntity>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onJoinChannel: (networkId: Long, channel: String) -> Unit,
    onMessageUser: (networkId: Long, nick: String) -> Unit,
    // Round 5 (plans/16 §3.5): seed the network from the active scope + browse entry.
    preselectedNetworkId: Long? = null,
    onBrowseChannels: (networkId: Long) -> Unit = {},
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        NewConversationSheetContent(
            networks = networks,
            preselectedNetworkId = preselectedNetworkId,
            onJoinChannel = onJoinChannel,
            onMessageUser = onMessageUser,
            onBrowseChannels = onBrowseChannels,
        )
    }
}

@Composable
private fun NewConversationSheetContent(
    networks: List<NetworkEntity>,
    onJoinChannel: (networkId: Long, channel: String) -> Unit,
    onMessageUser: (networkId: Long, nick: String) -> Unit,
    preselectedNetworkId: Long? = null,
    onBrowseChannels: (networkId: Long) -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var selectedNetwork by remember {
        mutableStateOf(
            networks.firstOrNull { it.id == preselectedNetworkId } ?: networks.firstOrNull(),
        )
    }
    var input by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0; input = "" },
                text = { Text(stringResource(R.string.new_sheet_join_channel)) },
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1; input = "" },
                text = { Text(stringResource(R.string.new_sheet_message_user)) },
            )
        }

        NetworkDropdown(
            networks = networks,
            selected = selectedNetwork,
            onSelect = { selectedNetwork = it },
        )

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    stringResource(
                        if (tab == 0) R.string.new_sheet_channel_hint
                        else R.string.new_sheet_nick_hint,
                    ),
                )
            },
        )

        Button(
            onClick = {
                val net = selectedNetwork ?: return@Button
                val value = input.trim()
                if (value.isEmpty()) return@Button
                if (tab == 0) onJoinChannel(net.id, value) else onMessageUser(net.id, value)
            },
            enabled = selectedNetwork != null && input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(if (tab == 0) R.string.new_sheet_join else R.string.new_sheet_message),
            )
        }

        // Browse: LIST is meaningless on the unbound soju root, so gate BOUNCER_ROOT out.
        if (tab == 0) {
            val net = selectedNetwork
            TextButton(
                onClick = { net?.let { onBrowseChannels(it.id) } },
                enabled = net != null && net.role != NetworkRole.BOUNCER_ROOT,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.channel_list_title))
            }
        }
    }
}

@Composable
private fun NetworkDropdown(
    networks: List<NetworkEntity>,
    selected: NetworkEntity?,
    onSelect: (NetworkEntity) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = networks.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = selected?.name ?: stringResource(R.string.new_sheet_network))
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            networks.forEach { net ->
                DropdownMenuItem(
                    text = { Text(net.name) },
                    onClick = {
                        onSelect(net)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
private fun NewConversationSheetPreview() {
    MotdTheme {
        NewConversationSheetContent(
            networks = listOf(
                NetworkEntity(
                    id = 1, name = "Libera", role = NetworkRole.DIRECT,
                    host = "irc.libera.chat", port = 6697,
                    nick = "me", username = "me", realname = "Me",
                ),
            ),
            onJoinChannel = { _, _ -> },
            onMessageUser = { _, _ -> },
        )
    }
}
