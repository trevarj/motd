package io.github.trevarj.motd.ui.chatlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.ui.components.AutocompletePanel
import io.github.trevarj.motd.ui.components.popupEffectSurface
import io.github.trevarj.motd.ui.settings.PasswordField
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.UiDropdownTrailingIcon
import io.github.trevarj.motd.ui.theme.readableForeground
import io.github.trevarj.motd.ui.theme.readableOutlinedTextFieldColors
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet as ModalBottomSheet
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

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
    val drafts = rememberNewConversationDrafts(preselectedNetworkId)
    ModalBottomSheet(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
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
            drafts = drafts,
        )
    }
}

@Stable
internal class NewConversationDrafts(
    networkId: Long?,
    joining: Boolean = true,
    channel: String = "",
    nickname: String = "",
) {
    var networkId by mutableStateOf(networkId)
    var joining by mutableStateOf(joining)
    var channel by mutableStateOf(channel)
    var nickname by mutableStateOf(nickname)

    companion object {
        val Saver =
            listSaver<NewConversationDrafts, Any?>(
                save = { listOf(it.networkId, it.joining, it.channel, it.nickname) },
                restore = { NewConversationDrafts(it[0] as Long?, it[1] as Boolean, it[2] as String, it[3] as String) },
            )
    }
}

@Composable
private fun rememberNewConversationDrafts(networkId: Long?): NewConversationDrafts = rememberSaveable(saver = NewConversationDrafts.Saver) { NewConversationDrafts(networkId) }

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
    drafts: NewConversationDrafts = rememberNewConversationDrafts(preselectedNetworkId),
) {
    val selectableNetworks = remember(networks) { networks.filter { it.role != NetworkRole.BOUNCER_ROOT } }
    var networkExpanded by remember { mutableStateOf(false) }
    var dismissedNickSuggestion by remember { mutableStateOf<String?>(null) }
    var lastPreselectedNetworkId by remember { mutableStateOf(preselectedNetworkId) }
    val passwordFocusRequester = remember { FocusRequester() }

    LaunchedEffect(selectableNetworks.map(NetworkEntity::id), preselectedNetworkId) {
        val preselectionChanged = preselectedNetworkId != lastPreselectedNetworkId
        if (preselectionChanged) lastPreselectedNetworkId = preselectedNetworkId
        if (preselectionChanged || selectableNetworks.none { it.id == drafts.networkId }) {
            drafts.networkId =
                preselectedNetworkId?.takeIf { id -> selectableNetworks.any { it.id == id } }
                    ?: selectableNetworks.firstOrNull()?.id
        }
        if (selectableNetworks.size < 2) networkExpanded = false
    }

    val selectedNetwork =
        selectableNetworks.firstOrNull { it.id == drafts.networkId }
            ?: selectableNetworks.firstOrNull()
    var password by remember(selectedNetwork?.id) { mutableStateOf("") }
    var passwordExpanded by remember(selectedNetwork?.id) { mutableStateOf(false) }
    val joining = drafts.joining
    val activeDraft = if (joining) drafts.channel else drafts.nickname
    val canSubmit = selectedNetwork != null && activeDraft.isNotBlank()
    val nickPrefix = drafts.nickname.trim()
    val nickSuggestionIdentity = selectedNetwork?.id?.let { "$it\u0000$nickPrefix" }
    val currentQueryCallback by rememberUpdatedState(onNickSuggestionQuery)

    LaunchedEffect(joining, selectedNetwork?.id, selectedNetwork?.nick, nickPrefix) {
        if (!joining && selectedNetwork != null && nickPrefix.isNotEmpty()) {
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
            !joining &&
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
        if (joining) {
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
                .height(440.dp)
                .padding(start = 24.dp, end = 24.dp, bottom = 12.dp)
                .testTag("new_conversation_content"),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.new_sheet_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surfaceContainerLow),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() }.testTag("new_conversation_header"),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedNetwork != null) {
                    Box(Modifier.widthIn(min = 176.dp).weight(1f)) {
                        NetworkSelector(
                            networks = selectableNetworks,
                            selected = selectedNetwork,
                            expanded = networkExpanded,
                            onExpandedChange = { networkExpanded = it },
                            onSelect = { drafts.networkId = it.id },
                        )
                    }
                }
                TextButton(
                    onClick = { selectedNetwork?.let { onBrowseChannels(it.id) } },
                    enabled = selectedNetwork != null,
                    colors = readableTextButtonColors(),
                    modifier = Modifier.heightIn(min = 48.dp).testTag("new_conversation_browse"),
                ) {
                    Text(stringResource(R.string.new_sheet_browse))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConversationChoice(R.string.new_sheet_join_channel, "new_conversation_join_tab", joining, Modifier.weight(1f).fillMaxHeight()) {
                    drafts.joining = true
                }
                ConversationChoice(R.string.new_sheet_message_user, "new_conversation_message_tab", !joining, Modifier.weight(1f).fillMaxHeight()) {
                    drafts.joining = false
                }
            }
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

            OutlinedTextField(
                colors = readableOutlinedTextFieldColors(),
                shape = MaterialTheme.shapes.small,
                value = activeDraft,
                onValueChange = {
                    if (joining) {
                        drafts.channel = it
                    } else {
                        drafts.nickname = it
                        dismissedNickSuggestion = null
                    }
                },
                enabled = selectedNetwork != null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("new_conversation_input"),
                leadingIcon = {
                    Icon(if (joining) Icons.Outlined.Forum else Icons.Outlined.Person, contentDescription = null)
                },
                prefix = if (joining) ({ Text(stringResource(R.string.new_sheet_channel_prefix)) }) else null,
                label = { Text(stringResource(if (joining) R.string.new_sheet_channel_hint else R.string.new_sheet_nick_hint)) },
                supportingText = {
                    Text(stringResource(if (joining) R.string.new_sheet_channel_guidance else R.string.new_sheet_nick_guidance))
                },
                keyboardOptions =
                    KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        imeAction = if (joining && passwordExpanded) ImeAction.Next else ImeAction.Done,
                    ),
                keyboardActions =
                    KeyboardActions(
                        onNext = { if (joining && passwordExpanded) passwordFocusRequester.requestFocus() },
                        onDone = { if (canSubmit) submit() },
                    ),
            )
            if (joining) {
                val scheme = MaterialTheme.colorScheme
                val fill = scheme.surfaceContainer
                val muted = scheme.readableForeground(lerp(scheme.onSurfaceVariant, fill, 0.30f), fill)
                val shape = MaterialTheme.shapes.small
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(fill),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (passwordExpanded) {
                        PasswordField(
                            value = password,
                            onValueChange = { password = it },
                            label = stringResource(R.string.new_sheet_channel_key),
                            imeAction = ImeAction.Done,
                            keyboardActions = KeyboardActions(onDone = { if (canSubmit) submit() }),
                            enabled = selectedNetwork != null,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(start = 8.dp, top = 4.dp, bottom = 8.dp)
                                    .focusRequester(passwordFocusRequester)
                                    .testTag("new_conversation_password"),
                        )
                    } else {
                        Row(
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .padding(start = 12.dp)
                                    .clearAndSetSemantics {},
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Outlined.Lock, contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
                            Text(
                                stringResource(R.string.new_sheet_channel_key_optional),
                                color = muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    IconButton(
                        enabled = selectedNetwork != null,
                        onClick = {
                            passwordExpanded = !passwordExpanded
                            if (!passwordExpanded) password = ""
                        },
                        modifier =
                            Modifier
                                .semantics { stateDescription = passwordStateDescription }
                                .testTag("new_conversation_password_toggle"),
                    ) {
                        Icon(
                            if (passwordExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            stringResource(if (passwordExpanded) R.string.new_sheet_hide_channel_key else R.string.new_sheet_channel_key_optional),
                            tint = muted,
                        )
                    }
                }
            } else {
                AutocompletePanel(
                    candidates = visibleSuggestions,
                    onPick = {
                        drafts.nickname = it
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
            Text(stringResource(if (joining) R.string.new_sheet_join_channel else R.string.new_sheet_message_user))
        }
    }
}

@Composable
private fun ConversationChoice(
    label: Int,
    tag: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        shape = MotdShapes.pill,
        colors =
            if (selected) {
                readableTextButtonColors(colors = ButtonDefaults.outlinedButtonColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer))
            } else {
                readableTextButtonColors(colors = ButtonDefaults.outlinedButtonColors())
            },
        contentPadding = ButtonDefaults.TextButtonContentPadding,
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .semantics { this.selected = selected }
                .testTag(tag),
    ) {
        Text(stringResource(label), textAlign = TextAlign.Center)
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
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
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
            colors = readableOutlinedTextFieldColors(),
            shape = MaterialTheme.shapes.small,
            value = current.name,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Outlined.Public, contentDescription = null) },
            trailingIcon = { UiDropdownTrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
                    .semantics {
                        role = Role.Button
                        stateDescription = current.name
                    }.testTag("new_conversation_network_selector"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { onExpandedChange(false) }, containerColor = androidx.compose.ui.graphics.Color.Transparent, modifier = Modifier.popupEffectSurface(MaterialTheme.colorScheme.surfaceContainerLow)) {
            networks.forEach { network ->
                val isSelected = network.id == current.id
                DropdownMenuItem(
                    text = { Text(network.name, color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface) },
                    trailingIcon = {
                        if (isSelected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    },
                    onClick = {
                        onSelect(network)
                        onExpandedChange(false)
                    },
                    modifier =
                        Modifier
                            .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
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
