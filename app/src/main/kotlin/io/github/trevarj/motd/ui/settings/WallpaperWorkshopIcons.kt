package io.github.trevarj.motd.ui.settings

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withTranslation
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.ImportedWallpaperIcon
import io.github.trevarj.motd.data.prefs.MAX_WALLPAPER_ICONS
import io.github.trevarj.motd.data.prefs.MAX_WALLPAPER_SCALE
import io.github.trevarj.motd.data.prefs.MIN_WALLPAPER_SCALE
import io.github.trevarj.motd.data.prefs.WallpaperCatalogCategory
import io.github.trevarj.motd.data.prefs.WallpaperGlyph
import io.github.trevarj.motd.data.prefs.WallpaperPatternIcon
import io.github.trevarj.motd.ui.chat.loadWallpaperGlyph
import io.github.trevarj.motd.ui.chat.wallpaperColorHex
import java.io.IOException
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet as ModalBottomSheet
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

private val svgMimeTypes = arrayOf("image/svg+xml", "application/xml", "text/xml", "application/octet-stream")

@Composable
internal fun workshopIconName(
    state: WallpaperWorkshopUiState,
    id: String,
): String =
    state.entries.firstOrNull { it.id == id }?.name
        ?: state.imports.firstOrNull { it.id == id }?.name
        ?: stringResource(R.string.wallpaper_workshop_missing_icon)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkshopIconPicker(
    state: WallpaperWorkshopUiState,
    picker: WallpaperWorkshopIconPicker,
    viewModel: WallpaperWorkshopViewModel,
) {
    val importSvg =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importSvg(uri)
        }
    val catalog =
        remember(state.entries, picker.query, picker.category) {
            state.entries.filter { (picker.category == null || it.category == picker.category) && it.matches(picker.query) }
        }
    val imports =
        remember(state.imports, picker.query, picker.category) {
            if (picker.category == null || picker.category == WallpaperCatalogCategory.IMPORTS) {
                state.imports.filter { it.name.contains(picker.query.trim(), ignoreCase = true) }
            } else {
                emptyList()
            }
        }
    val full = picker.selectedIds.size >= MAX_WALLPAPER_ICONS
    ModalBottomSheet(
        onDismissRequest = viewModel::cancelPicker,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("wallpaper_icon_picker"),
    ) {
        val grid = rememberLazyGridState()
        LaunchedEffect(picker.category, picker.query) {
            // Keep the search controls reachable and restart the filtered results at their first item.
            if (grid.firstVisibleItemIndex > 0) grid.scrollToItem(0)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            state = grid,
            modifier = Modifier.weight(1f, fill = false).testTag("wallpaper_icon_grid"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("controls", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkshopHeading(stringResource(R.string.wallpaper_workshop_add_icons))
                    OutlinedTextField(
                        value = picker.query,
                        onValueChange = viewModel::setPickerQuery,
                        label = { Text(stringResource(R.string.wallpaper_workshop_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("wallpaper_icon_search"),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .testTag("wallpaper_icon_categories")
                            .horizontalScroll(rememberScrollState())
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WorkshopChoiceChip(picker.category == null, stringResource(R.string.wallpaper_workshop_all), Modifier.testTag("wallpaper_category_all")) {
                            viewModel.setPickerCategory(null)
                        }
                        for (category in WallpaperCatalogCategory.entries) {
                            WorkshopChoiceChip(picker.category == category, categoryLabel(category), Modifier.testTag("wallpaper_category_${category.name.lowercase()}")) {
                                viewModel.setPickerCategory(category)
                            }
                        }
                    }
                    OutlinedButton(
                        onClick = { importSvg.launch(svgMimeTypes) },
                        enabled = !picker.importing && !full,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("wallpaper_import_svg"),
                    ) { Text(stringResource(R.string.wallpaper_workshop_import_svg)) }
                    if (picker.importing) {
                        Text(stringResource(R.string.wallpaper_workshop_importing), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (full) Text(stringResource(R.string.wallpaper_workshop_icon_limit), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    state.error?.let { error ->
                        WorkshopWarning(error.label)
                        TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.action_dismiss)) }
                    }
                }
            }
            items(catalog, key = { it.id }) { entry ->
                WorkshopIconCell(entry.id, entry.name, null, entry.id in picker.selectedIds, !full, viewModel::togglePickerIcon)
            }
            items(imports, key = { it.id }) { imported ->
                WorkshopIconCell(imported.id, imported.name, imported, imported.id in picker.selectedIds, !full, viewModel::togglePickerIcon)
            }
            if (catalog.isEmpty() && imports.isEmpty()) {
                item("empty", span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.wallpaper_workshop_no_icons)) }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(
                pluralStringResource(
                    R.plurals.wallpaper_workshop_icon_count,
                    MAX_WALLPAPER_ICONS,
                    picker.selectedIds.size,
                    MAX_WALLPAPER_ICONS,
                ),
            )
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = viewModel::cancelPicker, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(onClick = viewModel::finishPicker, enabled = !picker.importing, modifier = Modifier.heightIn(min = 48.dp).testTag("wallpaper_icons_done")) {
                    Text(stringResource(R.string.wallpaper_workshop_done))
                }
            }
        }
    }
}

@Composable
private fun categoryLabel(category: WallpaperCatalogCategory): String =
    stringResource(
        when (category) {
            WallpaperCatalogCategory.MOTD -> R.string.wallpaper_workshop_category_motd
            WallpaperCatalogCategory.TECH -> R.string.wallpaper_workshop_category_tech
            WallpaperCatalogCategory.COMPUTER_GEAR -> R.string.wallpaper_workshop_category_computer
            WallpaperCatalogCategory.EMOJI_MEMES -> R.string.wallpaper_workshop_category_emoji
            WallpaperCatalogCategory.GAMING -> R.string.wallpaper_workshop_category_gaming
            WallpaperCatalogCategory.SPACE_RADIO -> R.string.wallpaper_workshop_category_space
            WallpaperCatalogCategory.DOODLES -> R.string.wallpaper_workshop_category_doodles
            WallpaperCatalogCategory.IMPORTS -> R.string.wallpaper_workshop_category_imports
        },
    )

@Composable
private fun WorkshopIconCell(
    id: String,
    name: String,
    imported: ImportedWallpaperIcon?,
    selected: Boolean,
    canAdd: Boolean,
    toggle: (String) -> Unit,
) {
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            .toggleable(selected, enabled = selected || canAdd, role = Role.Checkbox) { toggle(id) }
            .testTag("wallpaper_icon_${id.replace(':', '_')}"),
    ) {
        Box(Modifier.fillMaxWidth().padding(8.dp)) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                WorkshopGlyph(id, imported, Modifier.size(36.dp), null)
                Text(name, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
            }
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.align(Alignment.TopEnd).size(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun WorkshopIconDetails(
    state: WallpaperWorkshopUiState,
    icon: WallpaperPatternIcon,
    viewModel: WallpaperWorkshopViewModel,
    onColor: () -> Unit,
) {
    val id = icon.assetId
    val name = workshopIconName(state, id)
    val enabled = !state.busy
    val scale = icon.scale
    val rotation = icon.rotation
    ModalBottomSheet(onDismissRequest = viewModel::closeIcon, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WorkshopHeading(name)
            WorkshopGlyph(
                id,
                state.imports,
                Modifier.size(80.dp).align(Alignment.CenterHorizontally),
                icon.ink ?: state.draft?.pattern?.ink,
                scale = (scale ?: 100).toFloat() / MAX_WALLPAPER_SCALE,
                rotation = rotation ?: 0,
            )
            WorkshopHeading(stringResource(R.string.wallpaper_workshop_icon_role))
            FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkshopChoiceChip(!icon.filler, stringResource(R.string.wallpaper_workshop_lead), Modifier.testTag("wallpaper_icon_lead"), enabled) {
                    viewModel.updateIcon(id) { it.copy(filler = false) }
                }
                WorkshopChoiceChip(icon.filler, stringResource(R.string.wallpaper_workshop_filler), Modifier.testTag("wallpaper_icon_filler"), enabled) {
                    viewModel.updateIcon(id) { it.copy(filler = true) }
                }
            }
            WorkshopIconMode(
                labelId = R.string.wallpaper_workshop_size_mode,
                value = scale,
                defaultFixed = 100,
                tag = "wallpaper_icon_size",
                enabled = enabled,
            ) { value -> viewModel.updateIcon(id) { it.copy(scale = value) } }
            if (scale != null) {
                WorkshopSlider(R.string.wallpaper_workshop_size, scale, MIN_WALLPAPER_SCALE..MAX_WALLPAPER_SCALE, "wallpaper_icon_size", enabled) { value ->
                    viewModel.updateIcon(id) { it.copy(scale = value) }
                }
            }
            WorkshopIconMode(
                labelId = R.string.wallpaper_workshop_rotation_mode,
                value = rotation,
                defaultFixed = 0,
                tag = "wallpaper_icon_rotation",
                enabled = enabled,
            ) { value -> viewModel.updateIcon(id) { it.copy(rotation = value) } }
            if (rotation != null) {
                WorkshopSlider(R.string.wallpaper_workshop_rotation, rotation, -180..180, "wallpaper_icon_rotation", enabled, degrees = true) { value ->
                    viewModel.updateIcon(id) { it.copy(rotation = value) }
                }
            }
            WorkshopSlider(R.string.wallpaper_workshop_frequency, icon.weight, 1..5, "wallpaper_icon_frequency", enabled, step = 1, percent = false) { value ->
                viewModel.updateIcon(id) { it.copy(weight = value) }
            }
            SettingsNavigationRow(
                title = stringResource(R.string.wallpaper_workshop_icon_ink, name),
                value = icon.ink?.let(::wallpaperColorHex) ?: stringResource(R.string.wallpaper_workshop_use_design_ink),
                enabled = enabled,
                onClick = onColor,
            )
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { viewModel.removeIcon(id) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.wallpaper_workshop_remove_icon, name), color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = viewModel::closeIcon, modifier = Modifier.heightIn(min = 48.dp).testTag("wallpaper_icon_done")) {
                Text(stringResource(R.string.wallpaper_workshop_done))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkshopIconMode(
    labelId: Int,
    value: Int?,
    defaultFixed: Int,
    tag: String,
    enabled: Boolean,
    update: (Int?) -> Unit,
) {
    var lastFixed by rememberSaveable { mutableIntStateOf(value ?: defaultFixed) }
    WorkshopHeading(stringResource(labelId))
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WorkshopChoiceChip(value == null, stringResource(R.string.wallpaper_workshop_auto), Modifier.testTag("${tag}_auto"), enabled) {
            if (value != null) {
                lastFixed = value
                update(null)
            }
        }
        WorkshopChoiceChip(value != null, stringResource(R.string.wallpaper_workshop_fixed), Modifier.testTag("${tag}_fixed"), enabled) {
            if (value == null) update(lastFixed)
        }
    }
}

@Composable
internal fun WorkshopGlyph(
    id: String,
    imports: List<ImportedWallpaperIcon>,
    modifier: Modifier,
    ink: Int?,
    scale: Float = 2f / 3f,
    rotation: Int = 0,
) = WorkshopGlyph(id, imports.firstOrNull { it.id == id }, modifier, ink, scale, rotation)

@Composable
private fun WorkshopGlyph(
    id: String,
    imported: ImportedWallpaperIcon?,
    modifier: Modifier,
    ink: Int?,
    scale: Float = 2f / 3f,
    rotation: Int = 0,
) {
    val context = LocalContext.current.applicationContext
    val argb = ink ?: MaterialTheme.colorScheme.onSurface.toArgb()
    key(id, imported) {
        val glyph by produceState<WallpaperGlyph?>(null, context) {
            value =
                try {
                    loadWallpaperGlyph(context, id, imported)
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
        }
        val paint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
        Canvas(modifier) {
            glyph?.let { drawing ->
                val fit = size.minDimension / kotlin.math.hypot(drawing.width, drawing.height) * scale
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.withTranslation(center.x, center.y) {
                        rotate(rotation.toFloat())
                        scale(fit, fit)
                        translate(-drawing.width / 2f, -drawing.height / 2f)
                        drawing.draw(this, argb, paint)
                    }
                }
            }
        }
    }
}
