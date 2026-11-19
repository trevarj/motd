@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.MAX_WALLPAPER_SCALE
import io.github.trevarj.motd.data.prefs.MIN_WALLPAPER_SCALE
import io.github.trevarj.motd.data.prefs.WallpaperPattern
import io.github.trevarj.motd.data.prefs.WallpaperPatternLayout
import io.github.trevarj.motd.data.prefs.WallpaperRenderSpec
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.prefs.WallpaperStarters
import io.github.trevarj.motd.data.prefs.WallpaperTexture
import io.github.trevarj.motd.data.prefs.effective
import io.github.trevarj.motd.data.prefs.isWallpaperName
import io.github.trevarj.motd.ui.chat.ChatWallpaperBackground
import io.github.trevarj.motd.ui.chat.WallpaperColorDialog
import io.github.trevarj.motd.ui.chat.WallpaperPreview
import io.github.trevarj.motd.ui.chat.wallpaperColorHex
import kotlin.math.roundToInt
import kotlin.random.Random
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

private const val BACKGROUND = "background"
private const val DEFAULT_INK = "ink"
private val workshopLayouts =
    listOf(
        WallpaperPatternLayout.SCATTERED to R.string.wallpaper_workshop_layout_scattered,
        WallpaperPatternLayout.ROWS to R.string.wallpaper_workshop_layout_rows,
        WallpaperPatternLayout.DIAGONAL to R.string.wallpaper_workshop_layout_diagonal,
        WallpaperPatternLayout.WAVES to R.string.wallpaper_workshop_layout_waves,
        WallpaperPatternLayout.KALEIDOSCOPE to R.string.wallpaper_workshop_layout_kaleidoscope,
    ).chunked(2)

@Composable
internal fun WorkshopEditor(
    state: WallpaperWorkshopUiState,
    draft: WallpaperWorkshopDraft,
    viewModel: WallpaperWorkshopViewModel,
    wide: Boolean,
    short: Boolean,
) {
    val spec = state.renderSpec
    val scrollHero = short || LocalDensity.current.fontScale >= 1.35f
    val tabs = rememberSaveableStateHolder()
    var colorTarget by rememberSaveable(draft.id) { mutableStateOf<String?>(null) }
    val nameAndHero: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.name,
                onValueChange = viewModel::setName,
                label = { Text(stringResource(R.string.wallpaper_workshop_name)) },
                isError = draft.name.any(Char::isISOControl) || !isWallpaperName(draft.name.trim()),
                enabled = !state.busy,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("wallpaper_editor_name"),
            )
            WorkshopHero(spec, draft.pattern.icons.isEmpty(), short, viewModel::preview)
        }
    }
    val controls: @Composable (Modifier, Boolean) -> Unit = { modifier, includeHero ->
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!scrollHero) WorkshopTabs(state.tab, viewModel::setTab)
            tabs.SaveableStateProvider(state.tab.name) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("wallpaper_editor_controls")
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (includeHero) nameAndHero()
                    if (scrollHero) {
                        WorkshopTabs(state.tab, viewModel::setTab)
                        WorkshopUsage(state)
                    }
                    if (state.missingAssets.isNotEmpty()) WorkshopWarning(R.string.wallpaper_workshop_missing_assets)
                    if (state.roomId != null && state.room == null) WorkshopWarning(R.string.wallpaper_workshop_target_unavailable)
                    if (state.conflicted) {
                        WorkshopWarning(R.string.wallpaper_workshop_conflict)
                        OutlinedButton(onClick = viewModel::duplicateDraft, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.wallpaper_workshop_duplicate))
                        }
                    }
                    when (state.tab) {
                        WallpaperWorkshopTab.ICONS -> WorkshopIcons(state, draft.pattern, viewModel)
                        WallpaperWorkshopTab.PATTERN -> WorkshopPatternControls(draft.pattern, !state.busy, viewModel::updatePattern)
                        WallpaperWorkshopTab.COLOR -> WorkshopColorControls(state, draft.pattern, viewModel) { colorTarget = it }
                    }
                }
            }
        }
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (wide) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { nameAndHero() }
                controls(Modifier.weight(1f).fillMaxHeight(), false)
            }
        } else if (scrollHero) {
            controls(Modifier.weight(1f).fillMaxWidth(), true)
        } else {
            nameAndHero()
            controls(Modifier.weight(1f).fillMaxWidth(), false)
        }
        if (!scrollHero) WorkshopUsage(state)
        FlowRow(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = viewModel::save,
                enabled = state.canSave,
                modifier = Modifier.heightIn(min = 48.dp).testTag("wallpaper_editor_save"),
            ) { Text(stringResource(R.string.action_save)) }
            WorkshopApply(state, "wallpaper_editor_apply", viewModel::apply)
        }
    }
    if (state.picker != null) WorkshopIconPicker(state, state.picker, viewModel)
    state.iconDetail?.let { id ->
        draft.pattern.icons.firstOrNull { it.assetId == id }?.let { icon ->
            WorkshopIconDetails(state, icon, viewModel, onColor = { colorTarget = id })
        }
    }
    colorTarget?.let { target ->
        WorkshopDraftColorDialog(state, draft.pattern, target, viewModel, onDismiss = { colorTarget = null })
    }
}

@Composable
private fun WorkshopHero(
    spec: WallpaperRenderSpec,
    empty: Boolean,
    short: Boolean,
    onPreview: () -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val preview = stringResource(R.string.wallpaper_workshop_preview)
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (short) 112.dp else 180.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClickLabel = preview, role = Role.Button, onClick = onPreview)
            .testTag("wallpaper_editor_preview"),
    ) {
        ChatWallpaperBackground(spec, Modifier.matchParentSize(), animate = false)
        if (empty) {
            Text(stringResource(R.string.wallpaper_workshop_add_to_start), Modifier.align(Alignment.Center).padding(12.dp))
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) { Text(preview, Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkshopTabs(
    tab: WallpaperWorkshopTab,
    select: (WallpaperWorkshopTab) -> Unit,
) {
    PrimaryTabRow(selectedTabIndex = tab.ordinal) {
        for ((value, label) in listOf(
            WallpaperWorkshopTab.ICONS to R.string.wallpaper_workshop_icons,
            WallpaperWorkshopTab.PATTERN to R.string.wallpaper_workshop_pattern,
            WallpaperWorkshopTab.COLOR to R.string.wallpaper_workshop_color,
        )) {
            Tab(
                selected = value == tab,
                onClick = { select(value) },
                text = { Text(stringResource(label)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag("wallpaper_tab_${value.name.lowercase()}"),
            )
        }
    }
}

@Composable
private fun WorkshopIcons(
    state: WallpaperWorkshopUiState,
    pattern: WallpaperPattern,
    viewModel: WallpaperWorkshopViewModel,
) {
    Button(onClick = viewModel::openPicker, enabled = !state.busy, modifier = Modifier.heightIn(min = 48.dp).testTag("wallpaper_add_icons")) {
        Text(stringResource(R.string.wallpaper_workshop_add_icons))
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (icon in pattern.icons) {
            val id = icon.assetId
            val name = workshopIconName(state, id)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = { viewModel.editIcon(id) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp).testTag("wallpaper_mix_${id.replace(':', '_')}"),
                    label = {
                        Column {
                            Text(name)
                            Text(
                                stringResource(if (icon.filler) R.string.wallpaper_workshop_filler else R.string.wallpaper_workshop_lead),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                    leadingIcon = { WorkshopGlyph(id, state.imports, Modifier.size(22.dp), icon.ink ?: pattern.ink) },
                )
                IconButton(onClick = { viewModel.removeIcon(id) }, enabled = !state.busy) {
                    Icon(Icons.Filled.Close, stringResource(R.string.wallpaper_workshop_remove_icon, name))
                }
            }
        }
    }
}

@Composable
private fun WorkshopPatternControls(
    pattern: WallpaperPattern,
    enabled: Boolean,
    update: ((WallpaperPattern) -> WallpaperPattern) -> Unit,
) {
    WorkshopSlider(R.string.wallpaper_workshop_density, pattern.density, 0..100, "wallpaper_density", enabled) { value -> update { it.copy(density = value) } }
    Text(stringResource(R.string.wallpaper_workshop_density_hint), style = MaterialTheme.typography.bodySmall)
    WorkshopHeading(stringResource(R.string.wallpaper_workshop_layout))
    val selectedLayout = pattern.layout.effective
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val sample =
            remember {
                WallpaperRenderSpec(WallpaperSelection(ChatWallpaperPreset.PATTERN), checkNotNull(WallpaperStarters.find("motd")).pattern)
            }
        for (layouts in workshopLayouts) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((layout, label) in layouts) {
                    OutlinedCard(
                        Modifier
                            .weight(1f)
                            .selectable(selectedLayout == layout, enabled, Role.RadioButton) { update { it.copy(layout = layout) } }
                            .testTag("wallpaper_layout_${layout.name.lowercase()}"),
                    ) {
                        WallpaperPreview(sample.copy(pattern = sample.pattern?.copy(layout = layout)), Modifier.fillMaxWidth().height(56.dp), artworkOnly = true)
                        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (selectedLayout == layout) Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                            Text(stringResource(label))
                        }
                    }
                }
            }
        }
    }
    val shuffleLabel = stringResource(R.string.wallpaper_workshop_shuffle_description)
    OutlinedButton(
        enabled = enabled,
        onClick = { update { it.copy(seed = it.seed xor Random.nextInt(1, Int.MAX_VALUE)) } },
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = shuffleLabel },
    ) { Text(stringResource(R.string.wallpaper_workshop_shuffle)) }
    WorkshopSlider(R.string.wallpaper_workshop_size, pattern.size, MIN_WALLPAPER_SCALE..MAX_WALLPAPER_SCALE, "wallpaper_size", enabled) { value ->
        update { it.copy(size = value) }
    }
    WorkshopSlider(R.string.wallpaper_workshop_rotation, pattern.rotation, -180..180, "wallpaper_rotation", enabled, degrees = true) { value -> update { it.copy(rotation = value) } }
    WorkshopSlider(R.string.wallpaper_workshop_variation, pattern.variation, 0..180, "wallpaper_variation", enabled, degrees = true) { value -> update { it.copy(variation = value) } }
}

@Composable
private fun WorkshopColorControls(
    state: WallpaperWorkshopUiState,
    pattern: WallpaperPattern,
    viewModel: WallpaperWorkshopViewModel,
    edit: (String) -> Unit,
) {
    val enabled = !state.busy
    WorkshopHeading(stringResource(R.string.wallpaper_workshop_background))
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkshopChoiceChip(pattern.background == null, stringResource(R.string.wallpaper_workshop_theme), enabled = enabled) {
            viewModel.updatePattern { it.copy(background = null) }
        }
        WorkshopChoiceChip(
            selected = pattern.background != null,
            label = stringResource(R.string.wallpaper_workshop_custom),
            modifier = Modifier.testTag("wallpaper_background_custom"),
            enabled = enabled,
        ) { edit(BACKGROUND) }
    }
    pattern.background?.let { Text(wallpaperColorHex(it)) }
    WorkshopHeading(stringResource(R.string.wallpaper_workshop_texture))
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (texture in WallpaperTexture.entries) {
            val label =
                when (texture) {
                    WallpaperTexture.NONE -> R.string.settings_wallpaper_none
                    WallpaperTexture.SPECKS -> R.string.wallpaper_workshop_texture_specks
                    WallpaperTexture.COMIC_PAPER -> R.string.wallpaper_workshop_texture_comic_paper
                    WallpaperTexture.GRAIN -> R.string.wallpaper_workshop_texture_grain
                    WallpaperTexture.LINEN -> R.string.wallpaper_workshop_texture_linen
                    WallpaperTexture.SCANLINE -> R.string.wallpaper_workshop_texture_scanline
                    WallpaperTexture.STARS -> R.string.wallpaper_workshop_texture_stars
                }
            WorkshopChoiceChip(
                selected = pattern.texture == texture,
                label = stringResource(label),
                modifier = Modifier.testTag("wallpaper_texture_${texture.name.lowercase()}"),
                enabled = enabled,
            ) { viewModel.updatePattern { it.copy(texture = texture) } }
        }
    }
    val theme = stringResource(R.string.wallpaper_workshop_theme)
    SettingsGroup {
        SettingsNavigationRow(
            title = stringResource(R.string.wallpaper_workshop_default_ink),
            value = pattern.ink?.let(::wallpaperColorHex) ?: theme,
            enabled = enabled,
            modifier = Modifier.testTag("wallpaper_default_ink"),
        ) { edit(DEFAULT_INK) }
    }
    WorkshopSlider(R.string.settings_wallpaper_intensity, pattern.intensity, 0..100, "wallpaper_intensity", enabled) { value ->
        viewModel.updatePattern { it.copy(intensity = value) }
    }
    for (icon in pattern.icons) {
        SettingsNavigationRow(
            title = stringResource(R.string.wallpaper_workshop_icon_ink, workshopIconName(state, icon.assetId)),
            value = icon.ink?.let(::wallpaperColorHex) ?: stringResource(R.string.wallpaper_workshop_use_design_ink),
            enabled = enabled,
        ) { edit(icon.assetId) }
    }
}

@Composable
private fun WorkshopDraftColorDialog(
    state: WallpaperWorkshopUiState,
    pattern: WallpaperPattern,
    target: String,
    viewModel: WallpaperWorkshopViewModel,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val icon = pattern.icons.firstOrNull { it.assetId == target }
    if (target != BACKGROUND && target != DEFAULT_INK && icon == null) return
    val label =
        when (target) {
            BACKGROUND -> stringResource(R.string.wallpaper_workshop_background)
            DEFAULT_INK -> stringResource(R.string.wallpaper_workshop_default_ink)
            else -> stringResource(R.string.wallpaper_workshop_icon_ink, workshopIconName(state, target))
        }
    val defaultColor =
        when (target) {
            BACKGROUND -> colors.background.toArgb()
            DEFAULT_INK -> colors.onSurfaceVariant.toArgb()
            else -> pattern.ink ?: colors.onSurfaceVariant.toArgb()
        }
    val value =
        when (target) {
            BACKGROUND -> pattern.background
            DEFAULT_INK -> pattern.ink
            else -> icon?.ink
        }

    fun WallpaperPattern.withColor(color: Int?) =
        when (target) {
            BACKGROUND -> copy(background = color)
            DEFAULT_INK -> copy(ink = color)
            else -> copy(icons = icons.map { if (it.assetId == target) it.copy(ink = color) else it })
        }
    WallpaperColorDialog(
        label = label,
        defaultLabel = stringResource(if (icon == null) R.string.wallpaper_workshop_theme else R.string.wallpaper_workshop_use_design_ink),
        defaultColor = defaultColor,
        value = value,
        onApply = { color ->
            viewModel.updatePattern { it.withColor(color) }
            onDismiss()
        },
        onDismiss = onDismiss,
    ) { color, modifier ->
        Box(modifier.clip(MaterialTheme.shapes.large).background(colors.background)) {
            ChatWallpaperBackground(state.renderSpec.copy(pattern = pattern.withColor(color)), Modifier.matchParentSize(), animate = false)
        }
    }
}

@Composable
internal fun WorkshopSlider(
    labelId: Int,
    value: Int,
    range: IntRange,
    tag: String,
    enabled: Boolean,
    degrees: Boolean = false,
    step: Int = 5,
    percent: Boolean = !degrees,
    update: (Int) -> Unit,
) {
    val label = stringResource(labelId)
    val formatted =
        when {
            degrees -> pluralStringResource(R.plurals.settings_wallpaper_color_degrees, kotlin.math.abs(value), value)
            percent -> stringResource(R.string.settings_wallpaper_color_percent, value)
            else -> value.toString()
        }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, Modifier.weight(1f))
            Text(formatted)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { update(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / step - 1,
            enabled = enabled,
            modifier =
                Modifier.heightIn(min = 48.dp).testTag(tag).semantics {
                    contentDescription = label
                    stateDescription = formatted
                },
        )
    }
}
