package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R

/**
 * Selection behind a ban or exception dialog. The dialog owns one of these; [BanTargetPicker]
 * renders it. Kept separate so the Channel-controls dialog and the nick sheet compose the exact
 * same mask from the exact same rules.
 */
@Stable
class BanTargetState(
    preselectedNick: String?,
) {
    var nick by mutableStateOf(preselectedNick)
    var scope by mutableStateOf(BanScope.NICK)
    var customMask by mutableStateOf("")

    /** The mask that will actually be sent, given the address resolved for [nick] so far. */
    fun mask(resolvedHost: String?): String = composeBanMask(scope, nick, resolvedHost, customMask)
}

@Composable
fun rememberBanTargetState(preselectedNick: String? = null): BanTargetState = remember(preselectedNick) { BanTargetState(preselectedNick) }

/**
 * Who to ban and how wide the ban reaches. Stateless apart from the dropdown's expansion: the
 * selection lives in [state] and the composed mask is always shown verbatim, because a mask is the
 * one part of banning a user cannot check afterwards (the app never fetches the ban list).
 *
 * [locked] preselects a member and blocks changing them, for the nick sheet's entry point.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BanTargetPicker(
    state: BanTargetState,
    members: List<String>,
    resolvedHost: String?,
    hostLoading: Boolean,
    onNickSelected: (String?) -> Unit,
    tagPrefix: String,
    modifier: Modifier = Modifier,
    locked: Boolean = false,
) {
    var membersExpanded by remember { mutableStateOf(false) }
    // One place drives the address lookup, so a preselected nick and a dropdown pick behave alike.
    LaunchedEffect(state.nick) { onNickSelected(state.nick) }

    Column(modifier = modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(
            expanded = membersExpanded && !locked,
            onExpandedChange = { if (!locked) membersExpanded = it },
        ) {
            OutlinedTextField(
                value = state.nick.orEmpty(),
                onValueChange = {},
                readOnly = true,
                enabled = !locked,
                label = { Text(stringResource(R.string.channelinfo_ban_who)) },
                trailingIcon = {
                    if (!locked) ExposedDropdownMenuDefaults.TrailingIcon(membersExpanded)
                },
                modifier =
                    Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                        .testTag("${tagPrefix}_member_dropdown"),
            )
            ExposedDropdownMenu(
                expanded = membersExpanded && !locked,
                onDismissRequest = { membersExpanded = false },
            ) {
                members.forEach { member ->
                    DropdownMenuItem(
                        text = { Text(member) },
                        onClick = {
                            state.nick = member
                            if (state.scope == BanScope.CUSTOM) state.scope = BanScope.NICK
                            membersExpanded = false
                        },
                    )
                }
            }
        }

        ScopeOption(
            selected = state.scope == BanScope.NICK,
            onSelect = { state.scope = BanScope.NICK },
            title = stringResource(R.string.channelinfo_ban_scope_nick),
            description = stringResource(R.string.channelinfo_ban_scope_nick_desc),
            tag = "${tagPrefix}_scope_nick",
        )
        ScopeOption(
            selected = state.scope == BanScope.HOST,
            onSelect = { state.scope = BanScope.HOST },
            title = stringResource(R.string.channelinfo_ban_scope_host),
            // Never offer an address ban we cannot spell out; say why it is unavailable instead.
            description =
                when {
                    resolvedHost != null -> stringResource(R.string.channelinfo_ban_scope_host_desc)
                    hostLoading -> stringResource(R.string.channelinfo_ban_scope_host_loading)
                    else -> stringResource(R.string.channelinfo_ban_scope_host_unknown)
                },
            enabled = resolvedHost != null,
            tag = "${tagPrefix}_scope_host",
        )
        ScopeOption(
            selected = state.scope == BanScope.CUSTOM,
            onSelect = { state.scope = BanScope.CUSTOM },
            title = stringResource(R.string.channelinfo_ban_scope_custom),
            description = stringResource(R.string.channelinfo_ban_mask_examples),
            tag = "${tagPrefix}_scope_custom",
        )
        if (state.scope == BanScope.CUSTOM) {
            OutlinedTextField(
                value = state.customMask,
                onValueChange = { state.customMask = it },
                label = { Text(stringResource(R.string.channelinfo_ban_mask_hint)) },
                supportingText = { Text(stringResource(R.string.channelinfo_ban_mask_help)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("${tagPrefix}_custom_input"),
            )
        }

        Text(
            text = state.mask(resolvedHost),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("${tagPrefix}_preview"),
        )
    }
}

/**
 * Ban builder dialog, shared by Channel controls and the nick sheet. This dialog *is* the
 * confirmation: the exact mask is on screen and the action is in the error colour, so a second
 * "are you sure" would only add a tap without adding information.
 *
 * The nick sheet passes a [preselectedNick] and no [onUnban]; Channel controls passes neither.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BanTargetDialog(
    dialogTag: String,
    title: String,
    members: List<String>,
    resolvedHost: String?,
    hostLoading: Boolean,
    onNickSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
    onBan: (nick: String?, mask: String, alsoKick: Boolean) -> Unit,
    preselectedNick: String? = null,
    onUnban: ((String) -> Unit)? = null,
) {
    val target = rememberBanTargetState(preselectedNick)
    var alsoKick by remember { mutableStateOf(true) }
    val mask = target.mask(resolvedHost)
    val hasMember = !target.nick.isNullOrBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        // A dialog is its own Compose window and needs its own testTagsAsResourceId opt-in.
        modifier =
            Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag(dialogTag),
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BanTargetPicker(
                    state = target,
                    members = members,
                    resolvedHost = resolvedHost,
                    hostLoading = hostLoading,
                    onNickSelected = onNickSelected,
                    tagPrefix = "channelinfo_ban",
                    locked = preselectedNick != null,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.testTag("channelinfo_ban_also_kick"),
                ) {
                    // A ban keeps someone out; only a kick removes them now. Meaningless without a
                    // member, so a bare custom mask leaves it off and disabled.
                    Checkbox(
                        checked = alsoKick && hasMember,
                        onCheckedChange = { alsoKick = it },
                        enabled = hasMember,
                    )
                    Text(stringResource(R.string.channelinfo_ban_also_kick))
                }
                Text(
                    text = stringResource(R.string.channelinfo_ban_persists),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onBan(target.nick, mask, alsoKick && hasMember) },
                enabled = mask.isNotBlank(),
                modifier = Modifier.testTag("channelinfo_ban_confirm"),
            ) {
                Text(stringResource(R.string.nick_sheet_ban), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onUnban?.let { unban ->
                    TextButton(
                        onClick = { unban(mask) },
                        enabled = mask.isNotBlank(),
                        modifier = Modifier.testTag("channelinfo_ban_remove"),
                    ) { Text(stringResource(R.string.channelinfo_remove_ban)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun ScopeOption(
    selected: Boolean,
    onSelect: () -> Unit,
    title: String,
    description: String,
    tag: String,
    enabled: Boolean = true,
) {
    io.github.trevarj.motd.ui.settings.RadioRow(
        label = title,
        subtitle = description,
        selected = selected,
        enabled = enabled,
        onClick = onSelect,
        modifier = Modifier.testTag(tag),
    )
}
