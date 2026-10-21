package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.FoolPreferences
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.ui.components.Avatar
import io.github.trevarj.motd.ui.components.avatarsHidden
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars

/**
 * Sanitize raw add-nick input. Returns the trimmed nick, or null when unusable: blank, contains
 * whitespace or a comma, or starts with a channel sigil (`#`/`&`). Pure — unit-tested.
 */
fun sanitizeNickInput(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it.isWhitespace() } || trimmed.contains(',')) return null
    if (trimmed.startsWith('#') || trimmed.startsWith('&')) return null
    return trimmed
}

/** Stateful entry: one screen serves FRIENDS / FOOLS / COLORS routes. */
@Composable
fun ManageNicksScreen(
    kind: NickListKind,
    onBack: () -> Unit = {},
    viewModel: ManageNicksViewModel = hiltViewModel(),
) {
    LaunchedEffect(kind) { viewModel.init(kind) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ManageNicksContent(
        state = state,
        onBack = onBack,
        onAdd = viewModel::add,
        onRemove = viewModel::remove,
        onSetHue = viewModel::setHue,
        onUpdateFoolPreferences = viewModel::updateFoolPreferences,
    )
}

@Composable
fun ManageNicksContent(
    state: ManageNicksUiState,
    onBack: () -> Unit,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSetHue: (String, Int) -> Unit,
    onUpdateFoolPreferences: (String, (FoolPreferences) -> FoolPreferences) -> Unit,
) {
    val scheme = LocalNickColors.current
    val titleRes =
        when (state.kind) {
            NickListKind.FRIENDS -> R.string.friends_title
            NickListKind.FOOLS -> R.string.fools_title
            NickListKind.COLORS -> R.string.nick_colors_title
        }

    var input by remember { mutableStateOf("") }
    // Nick whose hue is being edited (COLORS): existing row tap or a freshly-added nick.
    var editingNick by remember { mutableStateOf<String?>(null) }
    var editingFool by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(editingFool, state.nicks, state.kind) {
        if (state.kind != NickListKind.FOOLS || editingFool !in state.nicks) editingFool = null
    }
    editingFool?.takeIf { it in state.nicks }?.let { nick ->
        FoolSettingsSheet(
            nick = nick,
            globalMode = state.foolsMode,
            preferences = state.foolPreferences[nick] ?: FoolPreferences(),
            onChange = { onUpdateFoolPreferences(nick, it) },
            onDismiss = { editingFool = null },
        )
    }

    editingNick?.let { nick ->
        NickHuePickerDialog(
            nick = nick,
            currentHue = state.overrides[nick],
            onPick = { hue ->
                if (hue != null) onSetHue(nick, hue) else onRemove(nick)
                editingNick = null
            },
            onDismiss = { editingNick = null },
        )
    }

    SettingsScaffold(
        title = stringResource(titleRes),
        onBack = onBack,
        scroll = false,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Add row -----------------------------------------------------------------------
            val sanitized = sanitizeNickInput(input)
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.manage_add_nick_hint)) },
                        modifier = Modifier.weight(1f).testTag("manage_nick_input"),
                    )
                    TextButton(
                        enabled = sanitized != null,
                        onClick = {
                            val nick = sanitized ?: return@TextButton
                            if (state.kind == NickListKind.COLORS) {
                                // Persist only once a swatch is picked in the dialog.
                                editingNick = nick
                            } else {
                                onAdd(nick)
                            }
                            input = ""
                        },
                        modifier = Modifier.testTag("manage_nick_add"),
                    ) {
                        Text(stringResource(R.string.manage_add))
                    }
                }
            }

            // List --------------------------------------------------------------------------
            if (state.nicks.isEmpty()) {
                val emptyRes =
                    when (state.kind) {
                        NickListKind.FRIENDS -> R.string.manage_empty_friends
                        NickListKind.FOOLS -> R.string.manage_empty_fools
                        NickListKind.COLORS -> R.string.manage_empty_colors
                    }
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(emptyRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.nicks, key = { it }) { nick ->
                            // Nick keys are stable, so add/remove edits fade and the surviving rows
                            // settle into place instead of snapping.
                            val animatedModifier =
                                Modifier.animateItem(
                                    fadeInSpec = MotdMotion.microFadeIn,
                                    placementSpec = MotdMotion.rowPlacement,
                                    fadeOutSpec = MotdMotion.microFadeOut,
                                )
                            val rowModifier =
                                when (state.kind) {
                                    NickListKind.COLORS -> animatedModifier.clickable { editingNick = nick }
                                    NickListKind.FOOLS -> animatedModifier.clickable { editingFool = nick }
                                    NickListKind.FRIENDS -> animatedModifier
                                }.testTag("manage_nick_$nick")
                            ListItem(
                                headlineContent = { Text(nick) },
                                supportingContent =
                                    if (state.kind == NickListKind.FOOLS) {
                                        {
                                            val preferences = state.foolPreferences[nick] ?: FoolPreferences()
                                            val mode = stringResource(foolVisibilityLabel(preferences.visibility ?: state.foolsMode))
                                            val visibility =
                                                if (preferences.visibility == null) stringResource(R.string.fool_visibility_inherit, mode) else mode
                                            val notifications =
                                                when {
                                                    preferences.notifyMentions && preferences.notifyDirectMessages -> R.string.fool_notifications_both
                                                    preferences.notifyMentions -> R.string.fool_notifications_mentions
                                                    preferences.notifyDirectMessages -> R.string.fool_notifications_direct
                                                    else -> R.string.fool_notifications_none
                                                }
                                            Text("$visibility\n${stringResource(notifications)}")
                                        }
                                    } else {
                                        null
                                    },
                                // The color list keeps its chip (it is the point of the row); the friends
                                // list drops the slot entirely when avatars are hidden.
                                leadingContent =
                                    if (state.kind != NickListKind.COLORS && avatarsHidden()) {
                                        null
                                    } else {
                                        {
                                            if (state.kind == NickListKind.COLORS) {
                                                // Color chip previewing the resolved override color.
                                                Box(
                                                    modifier =
                                                        Modifier
                                                            .size(20.dp)
                                                            .clip(CircleShape)
                                                            .background(scheme.hue(state.overrides[nick] ?: 0)),
                                                )
                                            } else {
                                                Avatar(name = nick, size = 36.dp)
                                            }
                                        }
                                    },
                                trailingContent = {
                                    IconButton(onClick = { onRemove(nick) }, modifier = Modifier.testTag("manage_remove_$nick")) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(R.string.manage_remove),
                                        )
                                    }
                                },
                                modifier = rowModifier,
                                colors =
                                    androidx.compose.material3.ListItemDefaults
                                        .colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun foolVisibilityLabel(mode: FoolsMode): Int =
    when (mode) {
        FoolsMode.DIMMED -> R.string.settings_fools_dimmed
        FoolsMode.COLLAPSE -> R.string.settings_fools_collapse
        FoolsMode.HIDE -> R.string.settings_fools_hide
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoolSettingsSheet(
    nick: String,
    globalMode: FoolsMode,
    preferences: FoolPreferences,
    onChange: ((FoolPreferences) -> FoolPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("fool_settings_sheet"),
    ) {
        SheetSystemBars()
        LazyColumn(Modifier.fillMaxWidth().testTag("fool_settings_options")) {
            item { Box(Modifier.padding(horizontal = 12.dp)) { SectionHeader(stringResource(R.string.fool_settings_title, nick)) } }
            item { Box(Modifier.padding(horizontal = 12.dp)) { SectionHeader(stringResource(R.string.fool_visibility)) } }
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.settings_fool_person_scope))
                    io.github.trevarj.motd.ui.components
                        .FoolVisibilityExample(preferences.visibility ?: globalMode)
                }
            }
            item {
                Column(Modifier.selectableGroup()) {
                    RadioRow(
                        label = stringResource(R.string.fool_visibility_inherit, stringResource(foolVisibilityLabel(globalMode))),
                        selected = preferences.visibility == null,
                        enabled = true,
                        onClick = { onChange { it.copy(visibility = null) } },
                        modifier = Modifier.testTag("fool_visibility_inherit"),
                    )
                    for (mode in FoolsMode.entries) {
                        RadioRow(
                            label = stringResource(foolVisibilityLabel(mode)),
                            selected = preferences.visibility == mode,
                            enabled = true,
                            onClick = { onChange { it.copy(visibility = mode) } },
                            modifier = Modifier.testTag("fool_visibility_${mode.name.lowercase()}"),
                        )
                    }
                }
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.fool_notify_mentions),
                    subtitle = "",
                    checked = preferences.notifyMentions,
                    onCheckedChange = { checked -> onChange { it.copy(notifyMentions = checked) } },
                    modifier = Modifier.testTag("fool_notify_mentions"),
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.fool_notify_direct_messages),
                    subtitle = "",
                    checked = preferences.notifyDirectMessages,
                    onCheckedChange = { checked -> onChange { it.copy(notifyDirectMessages = checked) } },
                    modifier = Modifier.testTag("fool_notify_direct_messages"),
                )
            }
            item {
                Text(
                    stringResource(R.string.fool_settings_help),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Preview
@Composable
private fun ManageNicksFriendsPreview() {
    MotdTheme {
        ManageNicksContent(
            state =
                ManageNicksUiState(
                    kind = NickListKind.FRIENDS,
                    nicks = listOf("alice", "bob"),
                ),
            onBack = {},
            onAdd = {},
            onRemove = {},
            onSetHue = { _, _ -> },
            onUpdateFoolPreferences = { _, _ -> },
        )
    }
}

@Preview
@Composable
private fun ManageNicksColorsPreview() {
    MotdTheme {
        ManageNicksContent(
            state =
                ManageNicksUiState(
                    kind = NickListKind.COLORS,
                    nicks = listOf("alice", "carol"),
                    overrides = mapOf("alice" to 210, "carol" to 90),
                ),
            onBack = {},
            onAdd = {},
            onRemove = {},
            onSetHue = { _, _ -> },
            onUpdateFoolPreferences = { _, _ -> },
        )
    }
}

@Preview
@Composable
private fun ManageNicksEmptyPreview() {
    MotdTheme {
        ManageNicksContent(
            state = ManageNicksUiState(kind = NickListKind.FOOLS),
            onBack = {},
            onAdd = {},
            onRemove = {},
            onSetHue = { _, _ -> },
            onUpdateFoolPreferences = { _, _ -> },
        )
    }
}
