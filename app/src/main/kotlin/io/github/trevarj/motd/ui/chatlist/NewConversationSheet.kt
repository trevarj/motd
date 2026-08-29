package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.ConnectionTransport
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.settings.PasswordField
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars

/**
 * Bottom sheet with two actions: join a channel or start a query. Network selection is a dropdown
 * (auto-selected when there is a single network). An optional password is sent only for channel
 * joins; the caller routes to [ConnectionManager.joinChannel] / [ConnectionManager.ensureQueryBuffer].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationSheet(
    networks: List<NetworkEntity>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onJoinChannel: (networkId: Long, channel: String, key: String?) -> Unit,
    onMessageUser: (networkId: Long, nick: String) -> Unit,
    // Round 5: seed the network from the active scope + browse entry.
    preselectedNetworkId: Long? = null,
    onBrowseChannels: (networkId: Long) -> Unit = {},
    onChooseProviderTarget: (networkId: Long) -> Unit = {},
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("new_conversation_sheet"),
    ) {
        SheetSystemBars()
        NewConversationSheetContent(
            networks = networks,
            preselectedNetworkId = preselectedNetworkId,
            onJoinChannel = onJoinChannel,
            onMessageUser = onMessageUser,
            onBrowseChannels = onBrowseChannels,
            onChooseProviderTarget = onChooseProviderTarget,
        )
    }
}

@Composable
internal fun NewConversationSheetContent(
    networks: List<NetworkEntity>,
    onJoinChannel: (networkId: Long, channel: String, key: String?) -> Unit,
    onMessageUser: (networkId: Long, nick: String) -> Unit,
    preselectedNetworkId: Long? = null,
    onBrowseChannels: (networkId: Long) -> Unit = {},
    onChooseProviderTarget: (networkId: Long) -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    // Joining channels / messaging users only works on bound child networks or direct networks;
    // the soju BOUNCER_ROOT is just the control connection, so exclude it from selection (a JOIN
    // there silently does nothing). Browse was already gated out for the same reason.
    val selectableNetworks =
        remember(networks) {
            networks.filter { it.role != NetworkRole.BOUNCER_ROOT }
        }
    var selectedNetwork by remember(selectableNetworks) {
        mutableStateOf(
            selectableNetworks.firstOrNull { it.id == preselectedNetworkId }
                ?: selectableNetworks.firstOrNull(),
        )
    }
    var input by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordExpanded by remember { mutableStateOf(false) }
    val sidecarSelected = selectedNetwork?.connectionTransport == ConnectionTransport.SIDECAR
    val canSubmit = selectedNetwork != null && !sidecarSelected && input.isNotBlank()

    fun submit() {
        val net = selectedNetwork ?: return
        val value = input.trim()
        if (value.isEmpty()) return
        if (tab == 0) {
            onJoinChannel(net.id, channelJoinTarget(value), password.takeIf(String::isNotBlank))
        } else {
            onMessageUser(net.id, value)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .testTag("new_conversation_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = {
                    tab = 0
                    input = ""
                    password = ""
                    passwordExpanded = false
                },
                text = { Text(stringResource(R.string.new_sheet_join_channel)) },
                modifier = Modifier.testTag("new_conversation_join_tab"),
            )
            Tab(
                selected = tab == 1,
                onClick = {
                    tab = 1
                    input = ""
                    password = ""
                    passwordExpanded = false
                },
                text = { Text(stringResource(R.string.new_sheet_message_user)) },
                modifier = Modifier.testTag("new_conversation_message_tab"),
            )
        }

        NetworkDropdown(
            networks = selectableNetworks,
            selected = selectedNetwork,
            onSelect = { selectedNetwork = it },
        )

        if (sidecarSelected) {
            Button(
                onClick = { selectedNetwork?.let { onChooseProviderTarget(it.id) } },
                modifier = Modifier.fillMaxWidth().testTag("new_conversation_provider_picker"),
            ) {
                Text(stringResource(R.string.new_sheet_choose_provider_target))
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            enabled = !sidecarSelected,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("new_conversation_input"),
            prefix =
                if (tab == 0) {
                    { Text(stringResource(R.string.new_sheet_channel_prefix)) }
                } else {
                    null
                },
            label = {
                Text(
                    stringResource(
                        if (tab == 0) {
                            R.string.new_sheet_channel_hint
                        } else {
                            R.string.new_sheet_nick_hint
                        },
                    ),
                )
            },
            keyboardOptions =
                KeyboardOptions(
                    imeAction = if (tab == 0 && passwordExpanded) ImeAction.Next else ImeAction.Done,
                ),
            keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
        )

        if (tab == 0 && !sidecarSelected) {
            Column {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .clickable {
                                passwordExpanded = !passwordExpanded
                                if (!passwordExpanded) password = ""
                            }.padding(horizontal = 16.dp)
                            .testTag("new_conversation_password_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.new_sheet_channel_password),
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (passwordExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription =
                            stringResource(
                                if (passwordExpanded) {
                                    R.string.new_sheet_channel_password_collapse
                                } else {
                                    R.string.new_sheet_channel_password_expand
                                },
                            ),
                    )
                }
                if (passwordExpanded) {
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.new_sheet_channel_password),
                        modifier = Modifier.testTag("new_conversation_password"),
                    )
                }
            }
        } else {
            // Match collapsed Join-tab height so switching tabs does not move sheet chrome.
            Spacer(Modifier.height(56.dp))
        }

        Button(
            onClick = ::submit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().testTag("new_conversation_submit"),
        ) {
            Text(
                stringResource(if (tab == 0) R.string.new_sheet_join else R.string.new_sheet_message),
            )
        }

        // Keep this action slot on both tabs. Without it, hiding Browse for direct messages also
        // removes the Column spacing before it and makes the bottom sheet visibly jump in height.
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("new_conversation_action_slot"),
            contentAlignment = Alignment.Center,
        ) {
            // Browse: LIST is meaningless on the unbound soju root, so gate BOUNCER_ROOT out.
            if (tab == 0 && !sidecarSelected) {
                val net = selectedNetwork
                TextButton(
                    onClick = { net?.let { onBrowseChannels(it.id) } },
                    enabled = net != null && net.role != NetworkRole.BOUNCER_ROOT,
                    modifier = Modifier.fillMaxWidth().testTag("new_conversation_browse"),
                ) {
                    Text(stringResource(R.string.new_sheet_browse))
                }
            }
        }
    }
}

internal fun channelJoinTarget(channelName: String): String = "#${channelName.trim()}"

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
            onJoinChannel = { _, _, _ -> },
            onMessageUser = { _, _ -> },
        )
    }
}
