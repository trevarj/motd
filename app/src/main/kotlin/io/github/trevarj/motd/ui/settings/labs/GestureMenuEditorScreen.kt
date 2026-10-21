package io.github.trevarj.motd.ui.settings.labs

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.outlined.FormatIndentIncrease
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DynamicFeed
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.gesture.GestureAction
import io.github.trevarj.motd.gesture.GestureIcon
import io.github.trevarj.motd.gesture.GestureMenuViolation
import io.github.trevarj.motd.gesture.GestureNode
import io.github.trevarj.motd.gesture.GestureProviderKind
import io.github.trevarj.motd.gesture.MAX_RING_SLICES
import io.github.trevarj.motd.gesture.defaultGestureMenu
import io.github.trevarj.motd.gesture.vector
import io.github.trevarj.motd.ui.settings.RadioRow
import io.github.trevarj.motd.ui.settings.SettingsScaffold
import io.github.trevarj.motd.ui.theme.MotdTheme

const val GESTURE_EDITOR_SCREEN_TAG = "screen_gesture_editor"
const val GESTURE_EDITOR_SAVE_TAG = "gesture_editor_save"
const val GESTURE_EDITOR_RESET_TAG = "gesture_editor_reset"
const val GESTURE_EDITOR_ACTION_SHEET_TAG = "gesture_editor_action_sheet"
const val GESTURE_EDITOR_PROVIDER_SHEET_TAG = "gesture_editor_provider_sheet"

fun gestureEditorRowTag(nodeId: String): String = "gesture_editor_row_$nodeId"

fun gestureEditorOverflowTag(nodeId: String): String = "gesture_editor_overflow_$nodeId"

fun gestureEditorViolationTag(nodeId: String): String = "gesture_editor_violation_$nodeId"

/** Everything the row list can ask the ViewModel to do. */
data class GestureEditorCallbacks(
    val onRename: (String, String) -> Unit = { _, _ -> },
    val onIcon: (String, GestureIcon) -> Unit = { _, _ -> },
    val onMoveUp: (String) -> Unit = {},
    val onMoveDown: (String) -> Unit = {},
    val onIndent: (String) -> Unit = {},
    val onOutdent: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onAddChild: (String, GestureNodeKind) -> Unit = { _, _ -> },
    val onBindAction: (String, GestureAction) -> Unit = { _, _ -> },
    val onSetProvider: (String, GestureProviderKind, Int) -> Unit = { _, _, _ -> },
    val onReset: () -> Unit = {},
    val onSave: () -> Unit = {},
)

/**
 * The gesture menu graph editor.
 *
 * The tree is shown flattened with depth indentation rather than as nested containers: a ring is a
 * ring wherever it sits, and a flat list keeps every slice reachable with one scroll, including the
 * ones nested two rings deep that a collapsing tree would hide.
 */
@Composable
fun GestureMenuEditorScreen(
    onBack: () -> Unit = {},
    viewModel: GestureMenuEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    GestureMenuEditorContent(
        state = state,
        onBack = onBack,
        callbacks =
            GestureEditorCallbacks(
                onRename = viewModel::rename,
                onIcon = viewModel::setIcon,
                onMoveUp = viewModel::moveUp,
                onMoveDown = viewModel::moveDown,
                onIndent = viewModel::indent,
                onOutdent = viewModel::outdent,
                onDelete = viewModel::delete,
                onAddChild = viewModel::addChild,
                onBindAction = viewModel::bindAction,
                onSetProvider = viewModel::setProvider,
                onReset = viewModel::resetToDefault,
                onSave = viewModel::save,
            ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureMenuEditorContent(
    state: GestureEditorUiState,
    onBack: () -> Unit,
    callbacks: GestureEditorCallbacks,
) {
    var renaming by remember { mutableStateOf<GestureNode?>(null) }
    var picking by remember { mutableStateOf<GestureNode?>(null) }
    var deleting by remember { mutableStateOf<GestureNode?>(null) }
    var binding by remember { mutableStateOf<GestureNode.Leaf?>(null) }
    var configuring by remember { mutableStateOf<GestureNode.Provider?>(null) }
    var resetting by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.gesture_editor_title),
        onBack = onBack,
        modifier = Modifier.testTag(GESTURE_EDITOR_SCREEN_TAG),
        scroll = false,
        pagePadding = false,
        topActions = {
            TextButton(
                onClick = { resetting = true },
                enabled = state.loaded,
                modifier = Modifier.testTag(GESTURE_EDITOR_RESET_TAG),
            ) {
                Text(stringResource(R.string.gesture_editor_reset))
            }
            IconButton(
                onClick = callbacks.onSave,
                enabled = state.canSave,
                modifier = Modifier.testTag(GESTURE_EDITOR_SAVE_TAG),
            ) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.action_save))
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            EditorBanner(state)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    TextButton(onClick = { previewing = true }, enabled = state.previewRoot != null, modifier = Modifier.testTag("gesture_editor_preview")) {
                        Text(stringResource(R.string.settings_preview))
                    }
                }
                items(state.rows, key = { it.node.id }) { row ->
                    GestureEditorRowItem(
                        row = row,
                        callbacks = callbacks,
                        onRename = { renaming = row.node },
                        onPickIcon = { picking = row.node },
                        onDelete = { deleting = row.node },
                        onOpenBinding = {
                            when (val node = row.node) {
                                is GestureNode.Leaf -> binding = node
                                is GestureNode.Provider -> configuring = node
                                else -> Unit
                            }
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    if (previewing) {
        state.previewRoot?.let { root ->
            io.github.trevarj.motd.gesture.radial
                .GestureMenuPreview(root, onDismiss = { previewing = false })
        }
    }

    renaming?.let { node ->
        TextFieldDialog(
            title = stringResource(R.string.gesture_editor_rename),
            label = stringResource(R.string.gesture_editor_rename_label),
            initial = node.label,
            onSave = {
                callbacks.onRename(node.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    picking?.let { node ->
        IconPickerDialog(
            current = node.icon,
            onPick = {
                callbacks.onIcon(node.id, it)
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
    deleting?.let { node ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.gesture_editor_delete_title)) },
            text = { Text(stringResource(R.string.gesture_editor_delete_message, node.label)) },
            confirmButton = {
                TextButton(onClick = {
                    callbacks.onDelete(node.id)
                    deleting = null
                }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) }
            },
            modifier = Modifier.testTag("gesture_editor_delete_dialog"),
        )
    }
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text(stringResource(R.string.gesture_editor_reset_title)) },
            text = { Text(stringResource(R.string.gesture_editor_reset_message)) },
            confirmButton = {
                TextButton(onClick = {
                    callbacks.onReset()
                    resetting = false
                }) {
                    Text(stringResource(R.string.gesture_editor_reset))
                }
            },
            dismissButton = {
                TextButton(onClick = { resetting = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            modifier = Modifier.testTag("gesture_editor_reset_dialog"),
        )
    }
    binding?.let { leaf ->
        ActionBindingSheet(
            leaf = leaf,
            chats = state.chats,
            networks = state.networks,
            onSave = {
                callbacks.onBindAction(leaf.id, it)
                binding = null
            },
            onDismiss = { binding = null },
        )
    }
    configuring?.let { provider ->
        ProviderSheet(
            provider = provider,
            onSave = { kind, limit ->
                callbacks.onSetProvider(provider.id, kind, limit)
                configuring = null
            },
            onDismiss = { configuring = null },
        )
    }
}

/** One line saying why saving is off, so the disabled tick is never a mystery. */
@Composable
private fun EditorBanner(state: GestureEditorUiState) {
    val message =
        when {
            state.violations.isNotEmpty() -> stringResource(R.string.gesture_editor_invalid)
            state.dirty -> stringResource(R.string.gesture_editor_unsaved)
            else -> null
        } ?: return
    Surface(
        color =
            if (state.violations.isNotEmpty()) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        modifier = Modifier.fillMaxWidth().testTag("gesture_editor_banner"),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun GestureEditorRowItem(
    row: GestureEditorRow,
    callbacks: GestureEditorCallbacks,
    onRename: () -> Unit,
    onPickIcon: () -> Unit,
    onDelete: () -> Unit,
    onOpenBinding: () -> Unit,
) {
    val node = row.node
    val bindable = node is GestureNode.Leaf || node is GestureNode.Provider
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(gestureEditorRowTag(node.id))
                .padding(start = (row.depth * 20).dp),
    ) {
        ListItem(
            leadingContent = { Icon(node.icon.vector, contentDescription = null) },
            headlineContent = { Text(nodeTitle(node)) },
            supportingContent = { Text(nodeSummary(node)) },
            trailingContent = { RowOverflow(row, callbacks, onRename, onPickIcon, onDelete) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(if (bindable) Modifier.clickable(onClick = onOpenBinding) else Modifier),
        )
        if (row.violations.isNotEmpty()) {
            ViolationChips(row.violations, node.id)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ViolationChips(
    violations: List<GestureMenuViolation>,
    nodeId: String,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .testTag(gestureEditorViolationTag(nodeId)),
    ) {
        violations.forEach { violation ->
            AssistChip(onClick = {}, label = { Text(violationText(violation)) })
        }
    }
}

@Composable
private fun RowOverflow(
    row: GestureEditorRow,
    callbacks: GestureEditorCallbacks,
    onRename: () -> Unit,
    onPickIcon: () -> Unit,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val id = row.node.id

    fun run(action: () -> Unit) {
        action()
        open = false
    }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(gestureEditorOverflowTag(id))) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (row.canRename) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gesture_editor_rename)) },
                    leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    onClick = { run(onRename) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gesture_editor_icon)) },
                    leadingIcon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                    onClick = { run(onPickIcon) },
                )
            }
            if (row.canMoveUp) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gesture_editor_move_up)) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowUpward, contentDescription = null) },
                    onClick = { run { callbacks.onMoveUp(id) } },
                )
            }
            if (row.canMoveDown) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gesture_editor_move_down)) },
                    leadingIcon = { Icon(Icons.Outlined.ArrowDownward, contentDescription = null) },
                    onClick = { run { callbacks.onMoveDown(id) } },
                )
            }
            if (row.canIndent) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gesture_editor_indent)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.FormatIndentIncrease, contentDescription = null) },
                    onClick = { run { callbacks.onIndent(id) } },
                )
            }
            if (row.canOutdent) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gesture_editor_outdent)) },
                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.FormatIndentDecrease, contentDescription = null) },
                    onClick = { run { callbacks.onOutdent(id) } },
                )
            }
            if (row.canAddChild) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gesture_editor_add_leaf)) },
                    leadingIcon = { Icon(Icons.Outlined.AddCircleOutline, contentDescription = null) },
                    onClick = { run { callbacks.onAddChild(id, GestureNodeKind.LEAF) } },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gesture_editor_add_submenu)) },
                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                    onClick = { run { callbacks.onAddChild(id, GestureNodeKind.SUBMENU) } },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.gesture_editor_add_provider)) },
                    leadingIcon = { Icon(Icons.Outlined.DynamicFeed, contentDescription = null) },
                    onClick = { run { callbacks.onAddChild(id, GestureNodeKind.PROVIDER) } },
                )
            }
            if (row.canDelete) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { run(onDelete) },
                )
            }
        }
    }
}

// -- sheets and dialogs ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionBindingSheet(
    leaf: GestureNode.Leaf,
    chats: List<GestureChatChoice>,
    networks: List<GestureNetworkChoice>,
    onSave: (GestureAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(leaf.id) { mutableStateOf(gestureActionDraft(leaf.action)) }
    val built = buildGestureAction(draft)
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(GESTURE_EDITOR_ACTION_SHEET_TAG)) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        ) {
            SheetTitle(stringResource(R.string.gesture_editor_action_title))
            Column(Modifier.selectableGroup()) {
                GestureActionFamily.entries.forEach { family ->
                    SheetOption(
                        label = stringResource(actionFamilyLabel(family)),
                        selected = draft.family == family,
                        tag = "gesture_editor_family_${family.name.lowercase()}",
                        onClick = { draft = draft.copy(family = family) },
                    )
                }
            }
            if (draft.family.params.isNotEmpty()) {
                HorizontalDivider()
            }
            draft.family.params.forEach { param ->
                when (param) {
                    GestureActionParam.CHAT -> {
                        ChatPicker(chats, draft.bufferId) {
                            draft = draft.copy(bufferId = it)
                        }
                    }

                    GestureActionParam.NETWORK -> {
                        NetworkPicker(networks, draft.networkId) {
                            draft = draft.copy(networkId = it)
                        }
                    }

                    GestureActionParam.NICK -> {
                        SheetField(
                            label = stringResource(R.string.gesture_editor_field_nick),
                            value = draft.text,
                            tag = "gesture_editor_field_nick",
                        ) { draft = draft.copy(text = it) }
                    }

                    GestureActionParam.TEXT -> {
                        SheetField(
                            label = stringResource(R.string.gesture_editor_field_text),
                            value = draft.text,
                            tag = "gesture_editor_field_text",
                        ) { draft = draft.copy(text = it) }
                    }

                    GestureActionParam.CHANNEL -> {
                        SheetField(
                            label = stringResource(R.string.gesture_editor_field_channel),
                            value = draft.text,
                            tag = "gesture_editor_field_channel",
                        ) { draft = draft.copy(text = it) }
                    }

                    GestureActionParam.KEY -> {
                        SheetField(
                            label = stringResource(R.string.gesture_editor_field_key),
                            value = draft.secondary,
                            tag = "gesture_editor_field_key",
                        ) { draft = draft.copy(secondary = it) }
                    }

                    GestureActionParam.AWAY_MESSAGE -> {
                        SheetField(
                            label = stringResource(R.string.gesture_editor_field_away),
                            value = draft.text,
                            tag = "gesture_editor_field_away",
                        ) { draft = draft.copy(text = it) }
                    }
                }
            }
            SheetActions(
                enabled = built != null,
                saveTag = "gesture_editor_action_save",
                onSave = { built?.let(onSave) },
                onDismiss = onDismiss,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderSheet(
    provider: GestureNode.Provider,
    onSave: (GestureProviderKind, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember(provider.id) { mutableStateOf(provider.kind) }
    var limit by remember(provider.id) { mutableIntStateOf(provider.clampedLimit) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(GESTURE_EDITOR_PROVIDER_SHEET_TAG)) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
        ) {
            SheetTitle(stringResource(R.string.gesture_editor_provider_title))
            Column(Modifier.selectableGroup()) {
                // An unknown source is not offered as a choice; it only exists to preserve a kind a
                // newer build wrote, and picking it deliberately would just make an empty ring.
                GestureProviderKind.entries.filter { it != GestureProviderKind.UNKNOWN }.forEach { option ->
                    SheetOption(
                        label = stringResource(providerKindLabel(option)),
                        selected = kind == option,
                        tag = "gesture_editor_provider_${option.name.lowercase()}",
                        onClick = { kind = option },
                    )
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.gesture_editor_provider_limit, limit),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { limit = (limit - 1).coerceAtLeast(1) },
                    enabled = limit > 1,
                    modifier = Modifier.testTag("gesture_editor_limit_down"),
                ) {
                    Icon(
                        Icons.Outlined.Remove,
                        contentDescription = stringResource(R.string.gesture_editor_provider_fewer),
                    )
                }
                IconButton(
                    onClick = { limit = (limit + 1).coerceAtMost(MAX_RING_SLICES) },
                    enabled = limit < MAX_RING_SLICES,
                    modifier = Modifier.testTag("gesture_editor_limit_up"),
                ) {
                    Icon(
                        Icons.Outlined.Add,
                        contentDescription = stringResource(R.string.gesture_editor_provider_more),
                    )
                }
            }
            SheetActions(
                enabled = true,
                saveTag = "gesture_editor_provider_save",
                onSave = { onSave(kind, limit) },
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun ChatPicker(
    chats: List<GestureChatChoice>,
    selected: Long?,
    onSelect: (Long) -> Unit,
) {
    SheetSubtitle(stringResource(R.string.gesture_editor_chat_picker))
    if (chats.isEmpty()) {
        SheetEmpty(stringResource(R.string.gesture_editor_no_chats))
        return
    }
    Column(Modifier.selectableGroup()) {
        chats.forEach { chat ->
            SheetOption(
                label = chat.label,
                supporting = chat.networkName,
                selected = selected == chat.bufferId,
                tag = "gesture_editor_chat_${chat.bufferId}",
                onClick = { onSelect(chat.bufferId) },
            )
        }
    }
}

@Composable
private fun NetworkPicker(
    networks: List<GestureNetworkChoice>,
    selected: Long?,
    onSelect: (Long) -> Unit,
) {
    SheetSubtitle(stringResource(R.string.gesture_editor_network_picker))
    if (networks.isEmpty()) {
        SheetEmpty(stringResource(R.string.gesture_editor_no_networks))
        return
    }
    Column(Modifier.selectableGroup()) {
        networks.forEach { network ->
            SheetOption(
                label = network.name,
                selected = selected == network.networkId,
                tag = "gesture_editor_network_${network.networkId}",
                onClick = { onSelect(network.networkId) },
            )
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SheetSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun SheetEmpty(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SheetOption(
    label: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    supporting: String? = null,
) {
    RadioRow(label, selected, true, onClick, Modifier.testTag(tag), subtitle = supporting)
}

@Composable
private fun SheetField(
    label: String,
    value: String,
    tag: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(tag),
    )
}

@Composable
private fun SheetActions(
    enabled: Boolean,
    saveTag: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        TextButton(onClick = onSave, enabled = enabled, modifier = Modifier.testTag(saveTag)) {
            Text(stringResource(R.string.action_save))
        }
    }
}

@Composable
private fun TextFieldDialog(
    title: String,
    label: String,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("gesture_editor_rename_field"),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
                modifier = Modifier.testTag("gesture_editor_rename_save"),
            ) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        modifier = Modifier.testTag("gesture_editor_rename_dialog"),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IconPickerDialog(
    current: GestureIcon,
    onPick: (GestureIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gesture_editor_icon)) },
        text = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
            ) {
                // UNKNOWN is a decode fallback, never something a user should be able to choose.
                GestureIcon.entries.filter { it != GestureIcon.UNKNOWN }.forEach { icon ->
                    val label = stringResource(gestureIconLabel(icon))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color =
                            if (icon == current) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                Color.Transparent
                            },
                        modifier =
                            Modifier
                                .testTag("gesture_editor_icon_${icon.name.lowercase()}")
                                .size(48.dp)
                                .selectable(icon == current, role = Role.RadioButton, onClick = { onPick(icon) })
                                .semantics { contentDescription = label },
                    ) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            Icon(icon.vector, contentDescription = null)
                            if (icon == current) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.align(Alignment.BottomEnd).size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        modifier = Modifier.testTag("gesture_editor_icon_dialog"),
    )
}

// -- labels ---------------------------------------------------------------------------------------

/** An unknown node has no label of its own worth showing: its text belongs to the build that wrote it. */
@Composable
private fun nodeTitle(node: GestureNode): String = if (node is GestureNode.Unknown) stringResource(R.string.gesture_editor_unknown) else node.label

@Composable
private fun nodeSummary(node: GestureNode): String =
    when (node) {
        is GestureNode.Submenu -> {
            pluralStringResource(
                R.plurals.gesture_editor_child_count,
                node.children.size,
                node.children.size,
            )
        }

        is GestureNode.Leaf -> {
            if (node.action is GestureAction.Unknown) {
                stringResource(R.string.gesture_editor_unknown_action)
            } else {
                stringResource(actionFamilyLabel(gestureActionDraft(node.action).family))
            }
        }

        is GestureNode.Provider -> {
            val source = stringResource(providerKindLabel(node.kind))
            val entries = stringResource(R.string.gesture_editor_provider_limit, node.clampedLimit)
            stringResource(R.string.gesture_editor_provider_summary, source, entries)
        }

        is GestureNode.Unknown -> {
            stringResource(R.string.gesture_editor_unknown_action)
        }
    }

@Composable
private fun violationText(violation: GestureMenuViolation): String =
    when (violation) {
        is GestureMenuViolation.RingOverflow -> {
            stringResource(R.string.gesture_editor_violation_ring, violation.slices, MAX_RING_SLICES)
        }

        is GestureMenuViolation.TooDeep -> {
            stringResource(R.string.gesture_editor_violation_depth, violation.ring)
        }

        is GestureMenuViolation.BlankLabel -> {
            stringResource(R.string.gesture_editor_violation_label)
        }

        is GestureMenuViolation.DuplicateId -> {
            stringResource(R.string.gesture_editor_violation_duplicate)
        }
    }

@StringRes
private fun gestureIconLabel(icon: GestureIcon): Int =
    when (icon) {
        GestureIcon.UNKNOWN -> R.string.gesture_icon_unknown
        GestureIcon.MENU -> R.string.gesture_icon_menu
        GestureIcon.FOLDER -> R.string.gesture_icon_folder
        GestureIcon.CHAT -> R.string.gesture_icon_chat
        GestureIcon.PIN -> R.string.gesture_icon_pin
        GestureIcon.STAR -> R.string.gesture_icon_star
        GestureIcon.UNREAD -> R.string.gesture_icon_unread
        GestureIcon.MARK_READ -> R.string.gesture_icon_mark_read
        GestureIcon.PEOPLE -> R.string.gesture_icon_people
        GestureIcon.PERSON -> R.string.gesture_icon_person
        GestureIcon.MENTION -> R.string.gesture_icon_mention
        GestureIcon.SEARCH -> R.string.gesture_icon_search
        GestureIcon.INFO -> R.string.gesture_icon_info
        GestureIcon.BOLT -> R.string.gesture_icon_bolt
        GestureIcon.AWAY -> R.string.gesture_icon_away
        GestureIcon.NETWORK -> R.string.gesture_icon_network
        GestureIcon.GLOBE -> R.string.gesture_icon_globe
        GestureIcon.ATTACH -> R.string.gesture_icon_attach
        GestureIcon.LIGHT_MODE -> R.string.gesture_icon_light_mode
        GestureIcon.DARK_MODE -> R.string.gesture_icon_dark_mode
        GestureIcon.REFRESH -> R.string.gesture_icon_refresh
        GestureIcon.POWER -> R.string.gesture_icon_power
        GestureIcon.LINK -> R.string.gesture_icon_link
        GestureIcon.MORE -> R.string.gesture_icon_more
    }

@StringRes
internal fun actionFamilyLabel(family: GestureActionFamily): Int =
    when (family) {
        GestureActionFamily.OPEN_CHAT -> R.string.gesture_action_open_chat
        GestureActionFamily.OPEN_CHAT_LIST -> R.string.gesture_action_open_chat_list
        GestureActionFamily.NEXT_UNREAD -> R.string.gesture_action_next_unread
        GestureActionFamily.MARK_ALL_READ -> R.string.gesture_action_mark_all_read
        GestureActionFamily.OPEN_SEARCH -> R.string.gesture_action_open_search
        GestureActionFamily.CHANNEL_INFO_CURRENT -> R.string.gesture_action_channel_info
        GestureActionFamily.ATTACH_CURRENT -> R.string.gesture_action_attach
        GestureActionFamily.INSERT_MENTION -> R.string.gesture_action_insert_mention
        GestureActionFamily.INSERT_SNIPPET -> R.string.gesture_action_insert_snippet
        GestureActionFamily.START_QUERY -> R.string.gesture_action_start_query
        GestureActionFamily.JOIN_CHANNEL -> R.string.gesture_action_join_channel
        GestureActionFamily.TOGGLE_AWAY -> R.string.gesture_action_toggle_away
        GestureActionFamily.TOGGLE_THEME -> R.string.gesture_action_toggle_theme
        GestureActionFamily.RECONNECT_NETWORK -> R.string.gesture_action_reconnect
        GestureActionFamily.DISCONNECT_NETWORK -> R.string.gesture_action_disconnect
    }

@StringRes
internal fun providerKindLabel(kind: GestureProviderKind): Int =
    when (kind) {
        GestureProviderKind.PINNED_CHATS -> R.string.gesture_provider_pinned
        GestureProviderKind.UNREAD_CHATS -> R.string.gesture_provider_unread
        GestureProviderKind.RECENT_DMS -> R.string.gesture_provider_recent_dms
        GestureProviderKind.FRIENDS -> R.string.gesture_provider_friends
        GestureProviderKind.NETWORKS -> R.string.gesture_provider_networks
        GestureProviderKind.UNKNOWN -> R.string.gesture_provider_unknown
    }

@Preview
@Composable
private fun GestureMenuEditorPreview() {
    MotdTheme {
        GestureMenuEditorContent(
            state =
                GestureEditorUiState(
                    loaded = true,
                    rows = gestureEditorRows(defaultGestureMenu()),
                ),
            onBack = {},
            callbacks = GestureEditorCallbacks(),
        )
    }
}
