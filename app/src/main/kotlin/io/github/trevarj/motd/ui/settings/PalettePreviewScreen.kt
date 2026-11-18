package io.github.trevarj.motd.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.ui.theme.UiIcon
import io.github.trevarj.motd.ui.components.EmojiText as Text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PalettePreviewScreen(
    onBack: () -> Unit,
    onInstalled: () -> Unit,
    viewModel: PalettePreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.completed) {
        if (state.completed) onInstalled()
    }
    BackHandler(enabled = state.saving) { }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Palette preview") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.saving, modifier = Modifier.testTag("palette_preview_cancel")) {
                        UiIcon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.document != null) {
                    if (state.targetInstalledId != null) {
                        Button(
                            onClick = viewModel::saveWithoutApplying,
                            enabled = !state.saving && !state.completed && state.error == null,
                            modifier = Modifier.fillMaxWidth().testTag("palette_preview_update"),
                        ) { Text("Update") }
                    } else {
                        Button(
                            onClick = viewModel::apply,
                            enabled = !state.saving && !state.completed,
                            modifier = Modifier.fillMaxWidth().testTag("palette_preview_apply"),
                        ) { Text("Apply") }
                        TextButton(
                            onClick = viewModel::saveWithoutApplying,
                            enabled = !state.saving && !state.completed,
                            modifier = Modifier.fillMaxWidth().testTag("palette_preview_save"),
                        ) { Text("Save without applying") }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).testTag("palette_preview_list"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { error ->
                item {
                    Text(error, Modifier.padding(horizontal = 16.dp).testTag("palette_preview_error"), color = MaterialTheme.colorScheme.error)
                }
            }
            state.previousDocument?.let { previous ->
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("OLD · ${previous.name} · revision ${previous.revision}", Modifier.testTag("palette_preview_old"), style = MaterialTheme.typography.titleLarge)
                    }
                }
                listOf(PaletteMode.LIGHT to previous.light, PaletteMode.DARK to previous.dark).forEach { (mode, colors) ->
                    if (colors != null) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (mode == PaletteMode.LIGHT) "Old light preview" else "Old dark preview")
                                PaletteSamples(previous, mode, state.settings, state.appearance)
                            }
                        }
                    }
                }
            }
            state.document?.let { document ->
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.targetInstalledId != null) Text("NEW", Modifier.testTag("palette_preview_new"), style = MaterialTheme.typography.titleLarge)
                        Text(document.name, style = MaterialTheme.typography.headlineSmall)
                        Text("Revision ${document.revision}")
                        Text(
                            when {
                                document.light == null -> "Dark only"
                                document.dark == null -> "Light only"
                                else -> "Light and dark"
                            },
                        )
                        state.sourceHostname?.let { Text("Source host: $it") }
                        if (state.targetInstalledId != null) Text("Update this installed palette without changing your selected palette or color mode.")
                        Text(if (state.appearance.adjustPaletteReadability) "Readability adjustments and your saved text preference apply. True black and nickname preferences remain independent." else "Authored palette colors are shown. True black and nickname preferences remain independent.")
                    }
                }
                if (state.targetInstalledId == null) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                            Text(stringResource(R.string.palette_assignment))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                                listOf(PaletteMode.LIGHT, PaletteMode.DARK, PaletteMode.SYSTEM).forEach { target ->
                                    val supported =
                                        when (target) {
                                            PaletteMode.LIGHT -> document.light != null
                                            PaletteMode.DARK -> document.dark != null
                                            PaletteMode.SYSTEM -> document.light != null && document.dark != null
                                        }
                                    FilterChip(
                                        selected = state.assignmentTarget == target,
                                        onClick = { viewModel.selectAssignmentTarget(target) },
                                        enabled = supported && !state.saving,
                                        label = {
                                            Text(
                                                stringResource(
                                                    when (target) {
                                                        PaletteMode.LIGHT -> R.string.palette_mode_light
                                                        PaletteMode.DARK -> R.string.palette_mode_dark
                                                        PaletteMode.SYSTEM -> R.string.palette_both
                                                    },
                                                ),
                                            )
                                        },
                                        modifier = Modifier.testTag("palette_assign_${target.name.lowercase()}"),
                                    )
                                }
                            }
                        }
                    }
                }
                item { Text(stringResource(R.string.palette_preview_mode), Modifier.padding(horizontal = 16.dp)) }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PaletteMode.entries.forEach { mode ->
                            val supported =
                                when (mode) {
                                    PaletteMode.SYSTEM -> document.light != null && document.dark != null
                                    PaletteMode.LIGHT -> document.light != null
                                    PaletteMode.DARK -> document.dark != null
                                }
                            FilterChip(
                                selected = state.mode == mode,
                                onClick = { viewModel.selectMode(mode) },
                                enabled = supported && !state.saving,
                                label = {
                                    Text(
                                        stringResource(
                                            when (mode) {
                                                PaletteMode.LIGHT -> R.string.palette_mode_light
                                                PaletteMode.DARK -> R.string.palette_mode_dark
                                                PaletteMode.SYSTEM -> R.string.palette_both
                                            },
                                        ),
                                    )
                                },
                                modifier = Modifier.testTag("palette_preview_mode_${mode.name.lowercase()}"),
                            )
                        }
                    }
                }
                listOf(PaletteMode.LIGHT to document.light, PaletteMode.DARK to document.dark).forEach { (mode, colors) ->
                    if (colors != null && (state.mode == PaletteMode.SYSTEM || state.mode == mode)) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp).testTag("palette_preview_sample_${mode.name.lowercase()}"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (mode == PaletteMode.LIGHT) "Light preview" else "Dark preview", style = MaterialTheme.typography.titleMedium)
                                PaletteSamples(document, mode, state.settings, state.appearance)
                                Text("Authored nickname colors", style = MaterialTheme.typography.labelLarge)
                                if (colors.nicknameColors.isEmpty()) {
                                    Text("Automatic accent-derived nickname colors")
                                } else {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(colors.nicknameColors) { hex ->
                                            Box(
                                                Modifier
                                                    .size(40.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(hex.toColorInt()))
                                                    .semantics { contentDescription = "Nickname color $hex" },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.conflict) {
        AlertDialog(
            onDismissRequest = viewModel::cancelConflict,
            modifier = Modifier.testTag("palette_preview_conflict"),
            title = { Text("Palette already installed") },
            text = {
                Column(Modifier.selectableGroup()) {
                    Text("This ID is installed with different content.")
                    PaletteConflictChoice.entries.forEach { choice ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = state.conflictChoice == choice,
                                    enabled = !state.saving,
                                    role = Role.RadioButton,
                                    onClick = { viewModel.selectConflictChoice(choice) },
                                ).testTag(if (choice == PaletteConflictChoice.KEEP_BOTH) "palette_preview_keep_both" else "palette_preview_replace")
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            RadioButton(selected = state.conflictChoice == choice, onClick = null)
                            Text(if (choice == PaletteConflictChoice.KEEP_BOTH) "Keep both" else "Replace")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::confirmConflict,
                    enabled = !state.saving,
                    modifier = Modifier.testTag("palette_preview_confirm_conflict"),
                ) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelConflict, enabled = !state.saving) { Text("Cancel") }
            },
        )
    }
}
