package io.github.trevarj.motd.ui.channelinfo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R

/**
 * Channel operator controls.
 *
 * Every flag mode is a pair of action buttons, never a [androidx.compose.material3.Switch]: the app
 * has no 324 handling, so it cannot know which modes a channel currently has, and a switch resting
 * in the wrong position would assert something false. Sending `+x`/`-x` is cheap, reversible, and
 * the server's MODE echo lands in the timeline where the user can see what actually happened.
 *
 * Rows are gated on what the network advertises: a curated control appears only when its letter
 * sits in the CHANMODES group it belongs to, and the exception rows are absent (not disabled) when
 * EXCEPTS/INVEX are not advertised at all.
 */
@Composable
fun ChannelControlsSection(
    catalog: ModeCatalog?,
    members: List<String>,
    resolvedHost: String?,
    hostLoading: Boolean,
    onNickSelected: (String?) -> Unit,
    onFlagMode: (Char, Boolean) -> Unit,
    onInvite: () -> Unit,
    onSetKey: (String?) -> Unit,
    onSetLimit: (Int?) -> Unit,
    onSetListMask: (Char, String, Boolean) -> Unit,
    onBanWithMask: (String?, String, Boolean) -> Unit,
    onSetChannelMode: (String, String) -> Unit,
) {
    // Falling back to the RFC baseline keeps the curated rows present during the brief window
    // between roster load and the first 005 snapshot; the exception rows stay hidden because the
    // baseline advertises neither.
    val modes = catalog ?: ModeCatalog.DEFAULT
    var dialog by remember { mutableStateOf<ChannelControlDialog?>(null) }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .testTag("channelinfo_controls_section"),
    ) {
        SectionHeader(stringResource(R.string.channelinfo_controls_title))

        GroupHeader(stringResource(R.string.channelinfo_group_join))
        ActionRow(
            headline = stringResource(R.string.channelinfo_invite_row),
            supporting = stringResource(R.string.channelinfo_invite_row_desc),
            tag = "channelinfo_invite_row",
            onClick = onInvite,
        )
        if ('i' in modes.flagModes) {
            FlagModeRow(
                letter = 'i',
                headline = stringResource(R.string.channelinfo_mode_invite_only),
                supporting = stringResource(R.string.channelinfo_mode_invite_only_desc),
                onFlagMode = onFlagMode,
            )
        }
        if ('k' in modes.paramModes) {
            SetOrRemoveRow(
                headline = stringResource(R.string.channelinfo_key_row),
                supporting = stringResource(R.string.channelinfo_key_row_desc),
                tag = "channelinfo_key_row",
                setTag = "channelinfo_key_set",
                removeTag = "channelinfo_key_remove",
                onSet = { dialog = ChannelControlDialog.Key },
                onRemove = { onSetKey(null) },
            )
        }
        if ('l' in modes.setParamModes) {
            SetOrRemoveRow(
                headline = stringResource(R.string.channelinfo_limit_row),
                supporting = stringResource(R.string.channelinfo_limit_row_desc),
                tag = "channelinfo_limit_row",
                setTag = "channelinfo_limit_set",
                removeTag = "channelinfo_limit_remove",
                onSet = { dialog = ChannelControlDialog.Limit },
                onRemove = { onSetLimit(null) },
            )
        }

        GroupHeader(stringResource(R.string.channelinfo_group_conversation))
        if ('m' in modes.flagModes) {
            FlagModeRow(
                letter = 'm',
                headline = stringResource(R.string.channelinfo_mode_moderated),
                supporting = stringResource(R.string.channelinfo_mode_moderated_desc),
                onFlagMode = onFlagMode,
            )
        }
        if ('n' in modes.flagModes) {
            FlagModeRow(
                letter = 'n',
                headline = stringResource(R.string.channelinfo_mode_no_external),
                supporting = stringResource(R.string.channelinfo_mode_no_external_desc),
                onFlagMode = onFlagMode,
            )
        }
        if ('t' in modes.flagModes) {
            FlagModeRow(
                letter = 't',
                headline = stringResource(R.string.channelinfo_mode_topic_lock),
                supporting = stringResource(R.string.channelinfo_mode_topic_lock_desc),
                onFlagMode = onFlagMode,
            )
        }
        if ('s' in modes.flagModes) {
            FlagModeRow(
                letter = 's',
                headline = stringResource(R.string.channelinfo_mode_secret),
                supporting = stringResource(R.string.channelinfo_mode_secret_desc),
                onFlagMode = onFlagMode,
            )
        }

        GroupHeader(stringResource(R.string.channelinfo_group_bans))
        if ('b' in modes.listModes) {
            ActionRow(
                headline = stringResource(R.string.channelinfo_bans_row),
                supporting = stringResource(R.string.channelinfo_ban_persists),
                tag = "channelinfo_bans_row",
                onClick = { dialog = ChannelControlDialog.Ban },
            )
        }
        // Absent EXCEPTS/INVEX means the network has no such list at all, so the row does not
        // exist rather than sitting there disabled.
        modes.banExceptionChar?.let { letter ->
            ActionRow(
                headline = stringResource(R.string.channelinfo_excepts_row),
                supporting = stringResource(R.string.channelinfo_excepts_desc),
                tag = "channelinfo_excepts_row",
                onClick = { dialog = ChannelControlDialog.Except(letter, invite = false) },
            )
        }
        modes.inviteExceptionChar?.let { letter ->
            ActionRow(
                headline = stringResource(R.string.channelinfo_invex_row),
                supporting = stringResource(R.string.channelinfo_invex_desc),
                tag = "channelinfo_invex_row",
                onClick = { dialog = ChannelControlDialog.Except(letter, invite = true) },
            )
        }

        GroupHeader(stringResource(R.string.channelinfo_group_advanced))
        ActionRow(
            headline = stringResource(R.string.channelinfo_custom_mode_row),
            supporting = stringResource(R.string.channelinfo_custom_mode_desc),
            tag = "channelinfo_custom_mode_row",
            onClick = { dialog = ChannelControlDialog.CustomMode },
        )
    }

    when (val open = dialog) {
        null -> {}

        ChannelControlDialog.Key -> {
            KeyDialog(
                onDismiss = { dialog = null },
                onSet = {
                    onSetKey(it)
                    dialog = null
                },
            )
        }

        ChannelControlDialog.Limit -> {
            LimitDialog(
                onDismiss = { dialog = null },
                onSet = {
                    onSetLimit(it)
                    dialog = null
                },
            )
        }

        ChannelControlDialog.Ban -> {
            BanTargetDialog(
                dialogTag = "channelinfo_ban_dialog",
                title = stringResource(R.string.channelinfo_ban_dialog_title),
                members = members,
                resolvedHost = resolvedHost,
                hostLoading = hostLoading,
                onNickSelected = onNickSelected,
                onDismiss = {
                    dialog = null
                    onNickSelected(null)
                },
                onBan = { nick, mask, alsoKick ->
                    onBanWithMask(nick, mask, alsoKick)
                    dialog = null
                    onNickSelected(null)
                },
                onUnban = { mask ->
                    onSetListMask('b', mask, false)
                    dialog = null
                    onNickSelected(null)
                },
            )
        }

        is ChannelControlDialog.Except -> {
            ExceptionDialog(
                letter = open.letter,
                invite = open.invite,
                members = members,
                resolvedHost = resolvedHost,
                hostLoading = hostLoading,
                onNickSelected = onNickSelected,
                onDismiss = {
                    dialog = null
                    onNickSelected(null)
                },
                onAdd = { mask ->
                    onSetListMask(open.letter, mask, true)
                    dialog = null
                    onNickSelected(null)
                },
            )
        }

        ChannelControlDialog.CustomMode -> {
            CustomModeDialog(
                catalog = modes,
                onDismiss = { dialog = null },
                onSend = { letters, args ->
                    onSetChannelMode(letters, args)
                    dialog = null
                },
            )
        }
    }
}

/** Which control dialog is open. Sealed so an exception dialog can carry its advertised letter. */
private sealed interface ChannelControlDialog {
    data object Key : ChannelControlDialog

    data object Limit : ChannelControlDialog

    data object Ban : ChannelControlDialog

    data class Except(
        val letter: Char,
        val invite: Boolean,
    ) : ChannelControlDialog

    data object CustomMode : ChannelControlDialog
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

/** A row whose whole surface opens a dialog. */
@Composable
private fun ActionRow(
    headline: String,
    supporting: String,
    tag: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(tag)
                .clickable(onClick = onClick),
    )
}

/** On/Off actions for an argument-less mode. Deliberately not a Switch; see the file header. */
@Composable
private fun FlagModeRow(
    letter: Char,
    headline: String,
    supporting: String,
    onFlagMode: (Char, Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = { onFlagMode(letter, true) },
                    modifier = Modifier.testTag("channelinfo_mode_on_$letter"),
                ) { Text(stringResource(R.string.channelinfo_mode_on)) }
                OutlinedButton(
                    onClick = { onFlagMode(letter, false) },
                    modifier = Modifier.testTag("channelinfo_mode_off_$letter"),
                ) { Text(stringResource(R.string.channelinfo_mode_off)) }
            }
        },
        modifier = Modifier.fillMaxWidth().testTag("channelinfo_mode_row_$letter"),
    )
}

/** A row for a mode that takes a value: one action opens an editor, the other clears it. */
@Composable
private fun SetOrRemoveRow(
    headline: String,
    supporting: String,
    tag: String,
    setTag: String,
    removeTag: String,
    onSet: () -> Unit,
    onRemove: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = { Text(supporting) },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onSet, modifier = Modifier.testTag(setTag)) {
                    Text(stringResource(R.string.channelinfo_key_set))
                }
                OutlinedButton(onClick = onRemove, modifier = Modifier.testTag(removeTag)) {
                    Text(stringResource(R.string.channelinfo_key_remove))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun KeyDialog(
    onDismiss: () -> Unit,
    onSet: (String) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    // A key with a space or comma cannot survive the MODE line, so it is rejected up front.
    val valid = key.isNotBlank() && key.none { it == ' ' || it == ',' }
    ControlDialog(
        tag = "channelinfo_key_dialog",
        title = stringResource(R.string.channelinfo_key_dialog_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.channelinfo_key_confirm),
        confirmTag = "channelinfo_key_confirm",
        confirmEnabled = valid,
        onConfirm = { onSet(key) },
    ) {
        OutlinedTextField(
            value = key,
            onValueChange = { key = it },
            label = { Text(stringResource(R.string.channelinfo_key_hint)) },
            supportingText = { Text(stringResource(R.string.channelinfo_key_rule)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("channelinfo_key_input"),
        )
    }
}

internal val LIMIT_PRESETS = listOf(25, 50, 100, 500)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalLayoutApi::class)
@Composable
private fun LimitDialog(
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit,
) {
    var limit by remember { mutableStateOf("") }
    val parsed = limit.toIntOrNull()?.takeIf { it >= 1 }
    ControlDialog(
        tag = "channelinfo_limit_dialog",
        title = stringResource(R.string.channelinfo_limit_dialog_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.channelinfo_limit_confirm),
        confirmTag = "channelinfo_limit_confirm",
        confirmEnabled = parsed != null,
        onConfirm = { parsed?.let(onSet) },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LIMIT_PRESETS.forEach { preset ->
                // A chip fills the field rather than replacing it, so the value stays editable.
                FilterChip(
                    selected = parsed == preset,
                    onClick = { limit = preset.toString() },
                    label = { Text(preset.toString()) },
                    modifier = Modifier.testTag("channelinfo_limit_chip_$preset"),
                )
            }
        }
        OutlinedTextField(
            value = limit,
            onValueChange = { value -> limit = value.filter(Char::isDigit) },
            label = { Text(stringResource(R.string.channelinfo_limit_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("channelinfo_limit_input"),
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ExceptionDialog(
    letter: Char,
    invite: Boolean,
    members: List<String>,
    resolvedHost: String?,
    hostLoading: Boolean,
    onNickSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    val prefix = if (invite) "channelinfo_invex" else "channelinfo_excepts"
    val target = rememberBanTargetState(null)
    val mask = target.mask(resolvedHost)
    ControlDialog(
        tag = "${prefix}_dialog",
        title =
            stringResource(
                if (invite) R.string.channelinfo_invex_dialog_title else R.string.channelinfo_excepts_dialog_title,
            ),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.action_add),
        confirmTag = "${prefix}_confirm",
        confirmEnabled = mask.isNotBlank(),
        onConfirm = { onAdd(mask) },
    ) {
        Text(
            text =
                stringResource(
                    if (invite) R.string.channelinfo_invex_desc else R.string.channelinfo_excepts_desc,
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        BanTargetPicker(
            state = target,
            members = members,
            resolvedHost = resolvedHost,
            hostLoading = hostLoading,
            onNickSelected = onNickSelected,
            tagPrefix = prefix,
        )
        // The advertised letter, not a hardcoded e/I, is what actually goes on the wire.
        Text(
            text = "+$letter $mask",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Free-text escape hatch for everything the curated rows deliberately do not cover. Hints are
 * advisory only: the catalog can be stale and the server is the authority, so send is never blocked.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CustomModeDialog(
    catalog: ModeCatalog,
    onDismiss: () -> Unit,
    onSend: (String, String) -> Unit,
) {
    var letters by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("") }
    val hints = catalog.hintsFor(letters)
    ControlDialog(
        tag = "channelinfo_custom_mode_dialog",
        title = stringResource(R.string.channelinfo_custom_mode_row),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.network_tools_send_mode),
        confirmTag = "channelinfo_custom_mode_send",
        confirmEnabled = letters.isNotBlank(),
        onConfirm = { onSend(letters, args) },
    ) {
        OutlinedTextField(
            value = letters,
            onValueChange = { letters = it },
            label = { Text(stringResource(R.string.network_tools_modes)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("channelinfo_custom_mode_letters"),
        )
        OutlinedTextField(
            value = args,
            onValueChange = { args = it },
            label = { Text(stringResource(R.string.network_tools_mode_args)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("channelinfo_custom_mode_args"),
        )
        if (hints.isNotEmpty()) {
            Text(
                // map is inline, so stringResource stays inside the composable context.
                text =
                    hints
                        .map { hint ->
                            when (hint) {
                                is ModeHint.NeedsValue -> {
                                    stringResource(R.string.channelinfo_custom_mode_needs_value, hint.letter.toString())
                                }

                                is ModeHint.Unknown -> {
                                    stringResource(R.string.channelinfo_custom_mode_unknown, hint.letter.toString())
                                }
                            }
                        }.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("channelinfo_custom_mode_hint"),
            )
        }
        Text(
            text = stringResource(R.string.channelinfo_custom_mode_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Shared dialog shell. Each dialog is its own Compose window, so it needs its own
 * [testTagsAsResourceId] opt-in or none of its tags reach uiautomator.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ControlDialog(
    tag: String,
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    confirmTag: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    body: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier =
            Modifier
                .semantics { testTagsAsResourceId = true }
                .testTag(tag),
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                modifier = Modifier.testTag(confirmTag),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
