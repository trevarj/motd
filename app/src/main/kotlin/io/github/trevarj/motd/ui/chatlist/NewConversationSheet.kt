package io.github.trevarj.motd.ui.chatlist

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.settings.PasswordField
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars

/** Local-only entry sheet; callbacks keep connection/protocol ownership outside UI. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewConversationSheet(
    networks: List<NetworkEntity>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onJoinChannel: (networkId: Long, channel: String, key: String?) -> Unit,
    onMessageUser: (networkId: Long, nick: String) -> Unit,
    preselectedNetworkId: Long? = null,
    onBrowseChannels: (networkId: Long) -> Unit = {},
    nickSuggestions: NickSuggestions = NickSuggestions(),
    onNickSuggestionQuery: (Long?, String) -> Unit = { _, _ -> },
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
            nickSuggestions = nickSuggestions,
            onNickSuggestionQuery = onNickSuggestionQuery,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewConversationSheetContent(
    networks: List<NetworkEntity>,
    onJoinChannel: (networkId: Long, channel: String, key: String?) -> Unit,
    onMessageUser: (networkId: Long, nick: String) -> Unit,
    preselectedNetworkId: Long? = null,
    onBrowseChannels: (networkId: Long) -> Unit = {},
    nickSuggestions: NickSuggestions = NickSuggestions(),
    onNickSuggestionQuery: (Long?, String) -> Unit = { _, _ -> },
) {
    val selectableNetworks = remember(networks) { networks.filter { it.role != NetworkRole.BOUNCER_ROOT } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var joinDraft by rememberSaveable { mutableStateOf("") }
    var nickDraft by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedNetworkId by rememberSaveable { mutableStateOf(preselectedNetworkId) }
    var networkExpanded by rememberSaveable { mutableStateOf(false) }
    var dismissedNickSuggestion by rememberSaveable { mutableStateOf<String?>(null) }
    var lastPreselectedNetworkId by remember { mutableStateOf(preselectedNetworkId) }
    val passwordFocusRequester = remember { FocusRequester() }

    LaunchedEffect(selectableNetworks.map(NetworkEntity::id), preselectedNetworkId) {
        val preselectionChanged = preselectedNetworkId != lastPreselectedNetworkId
        if (preselectionChanged) lastPreselectedNetworkId = preselectedNetworkId
        if (preselectionChanged || selectableNetworks.none { it.id == selectedNetworkId }) {
            selectedNetworkId =
                preselectedNetworkId?.takeIf { id -> selectableNetworks.any { it.id == id } }
                    ?: selectableNetworks.firstOrNull()?.id
        }
        if (selectableNetworks.size < 2) networkExpanded = false
    }

    val selectedNetwork =
        selectableNetworks.firstOrNull { it.id == selectedNetworkId }
            ?: selectableNetworks.firstOrNull()
    val activeDraft = if (tab == 0) joinDraft else nickDraft
    val canSubmit = selectedNetwork != null && activeDraft.isNotBlank()
    val nickPrefix = nickDraft.trim()
    val nickSuggestionIdentity = selectedNetwork?.id?.let { "$it\u0000$nickPrefix" }
    val currentQueryCallback by rememberUpdatedState(onNickSuggestionQuery)

    LaunchedEffect(tab, selectedNetwork?.id, selectedNetwork?.nick, nickPrefix) {
        if (tab == 1 && selectedNetwork != null && nickPrefix.isNotEmpty()) {
            currentQueryCallback(selectedNetwork.id, nickPrefix)
        } else {
            currentQueryCallback(null, "")
        }
    }
    DisposableEffect(Unit) {
        onDispose { currentQueryCallback(null, "") }
    }

    val passwordStateDescription =
        stringResource(if (passwordExpanded) R.string.new_sheet_expanded else R.string.new_sheet_collapsed)
    val visibleSuggestions =
        if (
            tab == 1 &&
            nickPrefix.isNotEmpty() &&
            nickSuggestions.networkId == selectedNetwork?.id &&
            nickSuggestions.prefix == nickPrefix &&
            dismissedNickSuggestion != nickSuggestionIdentity
        ) {
            nickSuggestions.candidates
        } else {
            emptyList()
        }

    fun submit() {
        val network = selectedNetwork ?: return
        val value = activeDraft.trim()
        if (value.isEmpty()) return
        if (tab == 0) {
            onJoinChannel(network.id, channelJoinTarget(value), password.takeIf(String::isNotBlank))
        } else {
            onMessageUser(network.id, value)
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .testTag("new_conversation_content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.new_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() }.testTag("new_conversation_header"),
            )
            Text(
                text = stringResource(R.string.new_sheet_supporting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        PrimaryTabRow(selectedTabIndex = tab) {
            Tab(
                selected = tab == 0,
                onClick = { tab = 0 },
                text = { Text(stringResource(R.string.new_sheet_join_channel)) },
                icon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                modifier = Modifier.testTag("new_conversation_join_tab"),
            )
            Tab(
                selected = tab == 1,
                onClick = { tab = 1 },
                text = { Text(stringResource(R.string.new_sheet_message_user)) },
                icon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                modifier = Modifier.testTag("new_conversation_message_tab"),
            )
        }

        Text(
            text =
                stringResource(
                    if (tab == 0) R.string.new_sheet_join_description else R.string.new_sheet_message_description,
                ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier =
                Modifier.testTag(
                    if (tab == 0) "new_conversation_join_description" else "new_conversation_message_description",
                ),
        )

        NetworkSelector(
            networks = selectableNetworks,
            selected = selectedNetwork,
            expanded = networkExpanded,
            onExpandedChange = { networkExpanded = it },
            onSelect = { selectedNetworkId = it.id },
        )

        if (selectableNetworks.isEmpty()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.new_sheet_no_networks),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp).testTag("new_conversation_no_networks"),
                )
            }
        }

        if (tab == 0) {
            OutlinedTextField(
                value = joinDraft,
                onValueChange = { joinDraft = it },
                enabled = selectedNetwork != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("new_conversation_input"),
                leadingIcon = { Icon(Icons.Outlined.Forum, contentDescription = null) },
                prefix = { Text(stringResource(R.string.new_sheet_channel_prefix)) },
                label = { Text(stringResource(R.string.new_sheet_channel_hint)) },
                supportingText = { Text(stringResource(R.string.new_sheet_channel_guidance)) },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = if (passwordExpanded) ImeAction.Next else ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onNext = { passwordFocusRequester.requestFocus() },
                        onDone = { if (canSubmit) submit() },
                    ),
            )

            Column {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(enabled = selectedNetwork != null) {
                                passwordExpanded = !passwordExpanded
                                if (!passwordExpanded) password = ""
                            }.semantics {
                                role = Role.Button
                                stateDescription = passwordStateDescription
                            }.padding(horizontal = 16.dp)
                            .testTag("new_conversation_password_toggle"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Lock, contentDescription = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.new_sheet_channel_password))
                        Text(
                            stringResource(R.string.new_sheet_channel_password_guidance),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = if (passwordExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                    )
                }
                AnimatedVisibility(visible = passwordExpanded) {
                    PasswordField(
                        value = password,
                        onValueChange = { password = it },
                        label = stringResource(R.string.new_sheet_channel_password),
                        imeAction = ImeAction.Done,
                        keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
                        enabled = selectedNetwork != null,
                        modifier =
                            Modifier
                                .padding(top = 8.dp)
                                .focusRequester(passwordFocusRequester)
                                .testTag("new_conversation_password"),
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nickDraft,
                    onValueChange = {
                        nickDraft = it
                        dismissedNickSuggestion = null
                    },
                    enabled = selectedNetwork != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("new_conversation_input"),
                    leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                    label = { Text(stringResource(R.string.new_sheet_nick_hint)) },
                    supportingText = { Text(stringResource(R.string.new_sheet_nick_guidance)) },
                    keyboardOptions =
                        KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
                )
                AutocompletePanel(
                    candidates = visibleSuggestions,
                    onPick = {
                        nickDraft = it
                        dismissedNickSuggestion = "${selectedNetwork?.id}\u0000${it.trim()}"
                    },
                    networkId = selectedNetwork?.id,
                    tagPrefix = "new_conversation_autocomplete",
                )
            }
        }

        Button(
            onClick = ::submit,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("new_conversation_submit"),
        ) {
            Text(stringResource(if (tab == 0) R.string.new_sheet_join else R.string.new_sheet_message))
        }

        if (tab == 0) {
            OutlinedButton(
                onClick = { selectedNetwork?.let { onBrowseChannels(it.id) } },
                enabled = selectedNetwork != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("new_conversation_browse"),
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null)
                Text(stringResource(R.string.new_sheet_browse), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

internal fun channelJoinTarget(channelName: String): String = "#${channelName.trim()}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NetworkSelector(
    networks: List<NetworkEntity>,
    selected: NetworkEntity?,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (NetworkEntity) -> Unit,
) {
    val current = selected ?: return
    val label = stringResource(R.string.new_sheet_network)
    val selectedDescription = stringResource(R.string.new_sheet_network_selected, current.name)
    if (networks.size == 1) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .semantics {
                        contentDescription = selectedDescription
                    }.testTag("new_conversation_network_value"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Public, contentDescription = null)
                Column {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(current.name, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        return
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = current.name,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        stateDescription = current.name
                    }.testTag("new_conversation_network_selector"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }) {
            networks.forEach { network ->
                val isSelected = network.id == current.id
                DropdownMenuItem(
                    text = { Text(network.name) },
                    trailingIcon = {
                        if (isSelected) Icon(Icons.Filled.Check, contentDescription = null)
                    },
                    onClick = {
                        onSelect(network)
                        onExpandedChange(false)
                    },
                    modifier =
                        Modifier
                            .semantics { this.selected = isSelected }
                            .testTag("new_conversation_network_option_${network.id}"),
                )
            }
        }
    }
}

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
