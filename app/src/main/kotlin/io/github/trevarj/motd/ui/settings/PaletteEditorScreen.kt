package io.github.trevarj.motd.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.*
import io.github.trevarj.motd.ui.chat.ColorComponentSlider
import io.github.trevarj.motd.ui.chat.ColorWheel
import io.github.trevarj.motd.ui.chat.parseColorHex
import io.github.trevarj.motd.ui.chat.wallpaperColorHex
import io.github.trevarj.motd.ui.theme.accessibleColorScheme
import io.github.trevarj.motd.ui.theme.toRawColorScheme
import io.github.trevarj.motd.ui.theme.withTrueBlackSurfaces
import android.graphics.Color as AndroidColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteEditorScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: PaletteEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var previewDark by rememberSaveable { mutableStateOf(false) }
    var editingDark by rememberSaveable { mutableStateOf(false) }
    var layoutName by rememberSaveable { mutableStateOf(LayoutDensity.COMFORTABLE.name) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var removeMode by rememberSaveable { mutableStateOf<String?>(null) }
    var colorRole by rememberSaveable { mutableStateOf<String?>(null) }
    var colorMode by rememberSaveable { mutableStateOf(PaletteMode.LIGHT.name) }

    fun back() {
        if (state.dirty) discard = true else onBack()
    }
    BackHandler { back() }
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    val draft = state.draft
    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.palette_editor_title)) }, navigationIcon = {
                TextButton(onClick = { back() }, modifier = Modifier.testTag("palette_editor_back")) { Text(stringResource(R.string.palette_editor_back)) }
            })
        },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .horizontalScroll(rememberScrollState())
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = { back() }) { Text(stringResource(R.string.palette_editor_cancel)) }
                Button(onClick = { viewModel.save(false) }, enabled = draft != null && !state.saving && !state.invalidFields, modifier = Modifier.testTag("palette_editor_save")) { Text(stringResource(R.string.palette_editor_save)) }
                Button(onClick = { viewModel.save(true) }, enabled = draft != null && !state.saving && !state.invalidFields, modifier = Modifier.testTag("palette_editor_save_apply")) { Text(stringResource(R.string.palette_editor_save_apply)) }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (draft != null) {
                val sampleMode =
                    if (draft.light == null) {
                        PaletteMode.DARK
                    } else if (draft.dark == null) {
                        PaletteMode.LIGHT
                    } else if (previewDark) {
                        PaletteMode.DARK
                    } else {
                        PaletteMode.LIGHT
                    }
                val editMode =
                    if (draft.light == null) {
                        PaletteMode.DARK
                    } else if (draft.dark == null) {
                        PaletteMode.LIGHT
                    } else if (editingDark) {
                        PaletteMode.DARK
                    } else {
                        PaletteMode.LIGHT
                    }
                val colors = if (editMode == PaletteMode.DARK) draft.dark!! else draft.light!!
                val dark = editMode == PaletteMode.DARK
                val fitted =
                    remember(colors, dark, state.appearance.useThemeTextColors) {
                        accessibleColorScheme(colors.toRawColorScheme(dark), dark, state.appearance.useThemeTextColors)
                    }
                val applied =
                    remember(fitted, dark, state.appearance.trueBlack) {
                        if (dark && state.appearance.trueBlack) fitted.withTrueBlackSurfaces() else fitted
                    }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                ) {
                    Text(stringResource(R.string.palette_editor_contained_preview), style = MaterialTheme.typography.labelLarge)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (draft.light != null) FilterChip(selected = sampleMode == PaletteMode.LIGHT, onClick = { previewDark = false }, label = { Text(stringResource(R.string.palette_editor_light)) }, modifier = Modifier.testTag("palette_editor_preview_light"))
                        if (draft.dark != null) FilterChip(selected = sampleMode == PaletteMode.DARK, onClick = { previewDark = true }, label = { Text(stringResource(R.string.palette_editor_dark)) }, modifier = Modifier.testTag("palette_editor_preview_dark"))
                        LayoutDensity.entries.forEach { layout ->
                            FilterChip(selected = layoutName == layout.name, onClick = { layoutName = layout.name }, label = {
                                Text(
                                    stringResource(
                                        when (layout) {
                                            LayoutDensity.COMPACT -> R.string.palette_editor_compact
                                            LayoutDensity.COMFORTABLE -> R.string.palette_editor_comfortable
                                            LayoutDensity.TWO_LINE -> R.string.palette_editor_two_line
                                        },
                                    ),
                                )
                            }, modifier = Modifier.testTag("palette_editor_layout_${layout.name}"))
                        }
                    }
                    PaletteSamples(draft, sampleMode, state.settings, state.appearance, Modifier.testTag("palette_editor_sample"), LayoutDensity.valueOf(layoutName))
                }
                HorizontalDivider()
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("palette_editor_error")) }
                    if (state.revisionLimit) Button(onClick = { viewModel.save(false, duplicate = true) }, modifier = Modifier.testTag("palette_editor_duplicate")) { Text(stringResource(R.string.palette_editor_duplicate)) }
                    val name = state.fields["name"] ?: draft.name
                    val nameInvalid = "name" in state.rejectedFields || name.trim().isBlank() || name.trim().codePointCount(0, name.trim().length) > 64 || name.any(Char::isISOControl)
                    OutlinedTextField(value = name, onValueChange = { if (it != name) viewModel.setName(it) }, label = { Text(stringResource(R.string.palette_editor_name)) }, isError = nameInvalid, supportingText = { if (nameInvalid) Text(stringResource(R.string.palette_editor_name_error)) }, modifier = Modifier.fillMaxWidth().testTag("palette_editor_name"))
                    Text(stringResource(R.string.palette_editor_edit_mode), style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (draft.light != null) FilterChip(selected = editMode == PaletteMode.LIGHT, onClick = { editingDark = false }, label = { Text(stringResource(R.string.palette_editor_light)) }, modifier = Modifier.testTag("palette_editor_edit_light"))
                        if (draft.dark != null) FilterChip(selected = editMode == PaletteMode.DARK, onClick = { editingDark = true }, label = { Text(stringResource(R.string.palette_editor_dark)) }, modifier = Modifier.testTag("palette_editor_edit_dark"))
                        if (draft.light == null) {
                            TextButton(onClick = {
                                viewModel.addMode(PaletteMode.LIGHT)
                                editingDark = false
                            }, modifier = Modifier.testTag("palette_editor_add_light")) { Text(stringResource(R.string.palette_editor_add_light)) }
                        }
                        if (draft.dark == null) {
                            TextButton(onClick = {
                                viewModel.addMode(PaletteMode.DARK)
                                editingDark = true
                            }, modifier = Modifier.testTag("palette_editor_add_dark")) { Text(stringResource(R.string.palette_editor_add_dark)) }
                        }
                        if (draft.light != null && draft.dark != null) TextButton(onClick = { removeMode = editMode.name }, modifier = Modifier.testTag("palette_editor_remove_${editMode.name.lowercase()}")) { Text(stringResource(R.string.palette_editor_remove_mode)) }
                    }
                    Text(stringResource(R.string.palette_editor_mode_seed), style = MaterialTheme.typography.bodySmall)
                    if (!state.appearance.useThemeTextColors) Text(stringResource(R.string.palette_editor_neutral_mask), style = MaterialTheme.typography.bodySmall)
                    if (state.appearance.trueBlack) Text(stringResource(R.string.palette_editor_black_mask), style = MaterialTheme.typography.bodySmall)
                    if (!state.settings.nickColorsEnabled || state.settings.nickColorPalette != NickColorPalette.THEME) Text(stringResource(R.string.palette_editor_nick_mask), style = MaterialTheme.typography.bodySmall)
                    val roles = listOf("primary" to R.string.palette_editor_primary, "secondary" to R.string.palette_editor_secondary, "tertiary" to R.string.palette_editor_tertiary, "background" to R.string.palette_editor_background, "surface" to R.string.palette_editor_surface)
                    val extra = listOf("surfaceContainer" to R.string.palette_editor_container, "onBackground" to R.string.palette_editor_on_background, "onSurface" to R.string.palette_editor_on_surface, "onSurfaceVariant" to R.string.palette_editor_supporting, "outlineVariant" to R.string.palette_editor_outline)
                    (roles + if (advanced) extra else emptyList()).forEach { (role, label) ->
                        EditorRoleRow(colors, editMode, role, stringResource(label), state, fitted, applied) {
                            colorMode = editMode.name
                            colorRole = role
                        }
                    }
                    TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("palette_editor_advanced")) { Text(stringResource(if (advanced) R.string.palette_editor_hide_advanced else R.string.palette_editor_advanced)) }
                    Text(stringResource(R.string.palette_editor_nicknames), style = MaterialTheme.typography.titleMedium)
                    if (colors.nicknameColors.isEmpty()) Text(stringResource(R.string.palette_editor_automatic))
                    colors.nicknameColors.forEachIndexed { index, color ->
                        Column {
                            OutlinedButton(onClick = {
                                colorMode = editMode.name
                                colorRole = "nick_$index"
                            }, modifier = Modifier.fillMaxWidth().testTag("palette_editor_nick_$index")) {
                                Column {
                                    Text(stringResource(R.string.palette_editor_nickname_number, index + 1) + " · " + (state.fields["${editMode.name}:nick_$index"] ?: color))
                                    EditorNicknameSwatches(applied, editMode, color, state)
                                    if ("${editMode.name}:nick_$index" in state.rejectedFields || parseColorHex(state.fields["${editMode.name}:nick_$index"] ?: color) == null) Text(stringResource(R.string.palette_editor_hex_error), color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Row(Modifier.horizontalScroll(rememberScrollState())) {
                                TextButton(onClick = { viewModel.moveNickname(editMode, index, -1) }, enabled = index > 0) { Text(stringResource(R.string.palette_editor_move_up)) }
                                TextButton(onClick = { viewModel.moveNickname(editMode, index, 1) }, enabled = index < colors.nicknameColors.lastIndex) { Text(stringResource(R.string.palette_editor_move_down)) }
                                TextButton(onClick = { viewModel.removeNickname(editMode, index) }) { Text(stringResource(R.string.palette_editor_remove)) }
                            }
                        }
                    }
                    TextButton(onClick = { viewModel.addNickname(editMode) }, enabled = colors.nicknameColors.size < 24, modifier = Modifier.testTag("palette_editor_nick_add")) { Text(stringResource(R.string.palette_editor_nick_add)) }
                }
            } else {
                Text(state.error ?: stringResource(R.string.palette_editor_loading), Modifier.padding(16.dp))
            }
        }
    }
    if (discard) {
        AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.palette_editor_discard_title)) }, text = { Text(stringResource(R.string.palette_editor_discard_message)) }, confirmButton = {
            TextButton(onClick = onBack, modifier = Modifier.testTag("palette_editor_discard")) { Text(stringResource(R.string.palette_editor_discard)) }
        }, dismissButton = { TextButton(onClick = { discard = false }, modifier = Modifier.testTag("palette_editor_keep_editing")) { Text(stringResource(R.string.palette_editor_keep_editing)) } })
    }
    removeMode?.let { mode ->
        AlertDialog(onDismissRequest = { removeMode = null }, title = { Text(stringResource(R.string.palette_editor_remove_mode)) }, text = { Text(stringResource(R.string.palette_editor_remove_mode_message)) }, confirmButton = {
            TextButton(onClick = {
                viewModel.removeMode(PaletteMode.valueOf(mode))
                removeMode = null
            }, modifier = Modifier.testTag("palette_editor_confirm_remove")) { Text(stringResource(R.string.palette_editor_remove)) }
        }, dismissButton = { TextButton(onClick = { removeMode = null }) { Text(stringResource(R.string.palette_editor_cancel)) } })
    }
    colorRole?.let { role ->
        val mode = PaletteMode.valueOf(colorMode)
        val colors = if (mode == PaletteMode.DARK) draft?.dark else draft?.light
        if (colors != null) {
            val authored = colors.role(role)
            val value = state.fields["${mode.name}:$role"] ?: authored
            EditorColorDialog(value, authored, "${mode.name}:$role" in state.rejectedFields, { viewModel.setColor(mode, role, it) }, { colorRole = null })
        }
    }
}

@Composable
private fun EditorRoleRow(
    colors: PaletteColors,
    mode: PaletteMode,
    role: String,
    label: String,
    state: PaletteEditorState,
    fitted: ColorScheme,
    applied: ColorScheme,
    onClick: () -> Unit,
) {
    val authored = Color(parseColorHex(colors.role(role))!!)
    val field = state.fields["${mode.name}:$role"] ?: colors.role(role)
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().testTag("palette_editor_$role")) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label + " · " + field)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(24.dp).background(authored, CircleShape))
                Text(stringResource(R.string.palette_editor_authored))
                val appliedColor = applied.paletteRole(role)
                if (authored.toArgb() != appliedColor.toArgb()) {
                    Box(Modifier.size(24.dp).background(appliedColor, CircleShape))
                    Text(stringResource(R.string.palette_editor_applied))
                }
            }
            if ("${mode.name}:$role" in state.rejectedFields || parseColorHex(field) == null) Text(stringResource(R.string.palette_editor_hex_error), color = MaterialTheme.colorScheme.error)
            if (fitted.paletteRole(role).toArgb() != authored.toArgb()) Text(stringResource(R.string.palette_editor_adjusted), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun ColorScheme.paletteRole(role: String): Color =
    when (role) {
        "primary" -> primary
        "secondary" -> secondary
        "tertiary" -> tertiary
        "background" -> background
        "surface" -> surface
        "surfaceContainer" -> surfaceContainer
        "onBackground" -> onBackground
        "onSurface" -> onSurface
        "onSurfaceVariant" -> onSurfaceVariant
        else -> outlineVariant
    }

@Composable
private fun EditorColorDialog(
    value: String,
    authored: String,
    rejected: Boolean,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val parsed = parseColorHex(value)
    val invalid = rejected || parsed == null
    val hsv = remember(parsed, authored) { FloatArray(3).also { AndroidColor.colorToHSV(parsed ?: parseColorHex(authored)!!, it) } }

    fun update(
        hue: Float = hsv[0],
        saturation: Float = hsv[1],
        brightness: Float = hsv[2],
    ) {
        onChange(wallpaperColorHex(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 16.dp).testTag("palette_editor_color_dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.palette_editor_color)) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = value, onValueChange = { if (it != value) onChange(it) }, singleLine = true, label = { Text(stringResource(R.string.palette_editor_hex)) }, isError = invalid, supportingText = { if (invalid) Text(stringResource(R.string.palette_editor_hex_error)) }, modifier = Modifier.fillMaxWidth().testTag("palette_editor_hex"))
                ColorWheel(hsv[0], hsv[1], { h, s -> update(hue = h, saturation = s) }, tag = "palette_editor_wheel", label = stringResource(R.string.palette_editor_wheel))
                ColorComponentSlider(stringResource(R.string.palette_editor_hue), hsv[0], 0f..360f, hsv[0].toInt().toString(), "palette_editor_hue") { update(hue = it) }
                ColorComponentSlider(stringResource(R.string.palette_editor_saturation), hsv[1], 0f..1f, "${(hsv[1] * 100).toInt()}%", "palette_editor_saturation") { update(saturation = it) }
                ColorComponentSlider(stringResource(R.string.palette_editor_brightness), hsv[2], 0f..1f, "${(hsv[2] * 100).toInt()}%", "palette_editor_brightness") { update(brightness = it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !invalid, modifier = Modifier.testTag("palette_editor_color_done")) { Text(stringResource(R.string.palette_editor_done)) } },
    )
}

@Composable
private fun EditorNicknameSwatches(
    scheme: ColorScheme,
    mode: PaletteMode,
    value: String,
    state: PaletteEditorState,
) {
    val authored = Color(parseColorHex(value)!!)
    val applied =
        remember(authored, scheme, mode, state.settings.nickColorPalette, state.settings.nickColorsEnabled) {
            if (!state.settings.nickColorsEnabled) {
                scheme.onSurface
            } else {
                io.github.trevarj.motd.ui.theme.paletteNickColor(
                    "Palette sample",
                    mode == PaletteMode.DARK,
                    state.settings.nickColorPalette,
                    listOf(scheme.primary, scheme.tertiary, scheme.secondary),
                    listOf(scheme.background, scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest, scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer).distinct(),
                    listOf(authored),
                )
            }
        }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(24.dp).background(authored, CircleShape))
            Text(stringResource(R.string.palette_editor_authored))
        }
        if (applied.toArgb() != authored.toArgb()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(24.dp).background(applied, CircleShape))
                Text(stringResource(R.string.palette_editor_applied))
            }
            if (state.settings.nickColorsEnabled && state.settings.nickColorPalette == NickColorPalette.THEME) Text(stringResource(R.string.palette_editor_adjusted), style = MaterialTheme.typography.bodySmall)
        }
    }
}
