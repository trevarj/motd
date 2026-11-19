package io.github.trevarj.motd.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.WallpaperPatternRef
import io.github.trevarj.motd.data.prefs.WallpaperPatternRefKind
import io.github.trevarj.motd.data.prefs.WallpaperRenderSpec
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import io.github.trevarj.motd.data.prefs.WallpaperStarters
import io.github.trevarj.motd.data.prefs.resolveWallpaper
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.readableForeground
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet as ModalBottomSheet
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

@Composable
fun ChatWallpaperPicker(
    appearance: AppearanceConfig,
    onChange: (WallpaperSelection) -> Unit,
    onOpenWallpaperWorkshop: () -> Unit,
    modifier: Modifier = Modifier,
    onImportImage: (Uri) -> Unit = {},
    importedSelection: WallpaperSelection? = null,
    importing: Boolean = false,
) {
    val wallpaper = resolveWallpaper(appearance)
    val current = wallpaper.selection
    var showEditor by rememberSaveable { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let(onImportImage)
        }
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_wallpaper)) },
        supportingContent = {
            Text(wallpaperSummary(wallpaper, appearance))
        },
        leadingContent = { Icon(Icons.Outlined.Image, contentDescription = null) },
        trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null) },
        modifier = modifier.fillMaxWidth().clickable { showEditor = true }.testTag("settings_wallpaper_picker"),
    )
    if (showEditor) {
        WallpaperEditorSheet(
            appearance = appearance,
            onDismiss = { showEditor = false },
            onOpenWallpaperWorkshop = {
                showEditor = false
                onOpenWallpaperWorkshop()
            },
            onChange = onChange,
            onImport = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            importedSelection = importedSelection,
            importing = importing,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun WallpaperEditorSheet(
    appearance: AppearanceConfig,
    onDismiss: () -> Unit,
    onOpenWallpaperWorkshop: () -> Unit,
    onChange: (WallpaperSelection) -> Unit,
    onImport: () -> Unit,
    importedSelection: WallpaperSelection?,
    importing: Boolean,
) {
    // Keep the active edit local so delayed preference emissions cannot rewind a slider drag.
    var selection by rememberSaveable(
        stateSaver =
            listSaver<WallpaperSelection, Any>(
                save = {
                    listOf(
                        it.preset.name,
                        it.intensity,
                        it.localImageName.orEmpty(),
                        it.outlineColor?.toString().orEmpty(),
                        it.scenicFollowSystem,
                        it.scenicAnimationEnabled,
                        it.patternRef
                            ?.kind
                            ?.name
                            .orEmpty(),
                        it.patternRef?.id.orEmpty(),
                    )
                },
                restore = {
                    WallpaperSelection(
                        ChatWallpaperPreset.valueOf(it[0] as String),
                        it[1] as Int,
                        (it[2] as String).ifEmpty { null },
                        (it[3] as String).toIntOrNull(),
                        it.getOrNull(4) as? Boolean ?: true,
                        it.getOrNull(5) as? Boolean ?: true,
                        (it.getOrNull(7) as? String)?.takeIf(String::isNotEmpty)?.let { id ->
                            WallpaperPatternRef(WallpaperPatternRefKind.valueOf(it[6] as String), id)
                        },
                    )
                },
            ),
    ) { mutableStateOf(appearance.wallpaper.normalized()) }
    val importAtOpen = remember { importedSelection }
    LaunchedEffect(importedSelection) {
        // Only an explicit successful import may replace the active edit, never stale preferences.
        importedSelection?.takeIf { it != importAtOpen }?.let { selection = it.normalized() }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val previewAppearance = appearance.copy(wallpaper = selection)
    val wallpaper = resolveWallpaper(previewAppearance)
    val hasPhoto = selection.localImageName != null
    val showIntensity = hasPhoto || selection.preset.isScenic

    fun updateSelection(next: WallpaperSelection) {
        selection = next.normalized()
        onChange(selection)
    }

    ModalBottomSheet(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag("settings_wallpaper_sheet"),
    ) {
        SheetSystemBars()
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .selectableGroup()
                    .testTag("settings_wallpaper_list"),
        ) {
            item(key = "title") {
                Text(stringResource(R.string.settings_wallpaper), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.readableForeground(MaterialTheme.colorScheme.primary))
                Text(wallpaperSummary(wallpaper, previewAppearance), style = MaterialTheme.typography.bodyMedium)
            }
            item(key = "preview") {
                WallpaperPreview(
                    wallpaper,
                    Modifier
                        .fillMaxWidth()
                        .height(if (!hasPhoto && selection.preset.isScenic) 315.dp else 190.dp)
                        .testTag("settings_wallpaper_preview"),
                )
            }
            item(key = "workshop") {
                Button(
                    onClick = {
                        scope.launch {
                            sheetState.hide()
                            if (!sheetState.isVisible) onOpenWallpaperWorkshop()
                        }
                    },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("settings_wallpaper_workshop"),
                ) {
                    Text(stringResource(R.string.wallpaper_workshop_title))
                }
            }
            item(key = "photo") {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hasPhoto) {
                        WallpaperOptionThumbnail(
                            wallpaper,
                            "settings_wallpaper_photo_thumbnail",
                            Modifier.padding(start = 16.dp, end = 8.dp),
                        )
                    }
                    TextButton(colors = readableTextButtonColors(), onClick = onImport, enabled = !importing, modifier = Modifier.weight(1f).testTag("settings_wallpaper_custom")) {
                        Text(
                            stringResource(
                                when {
                                    importing -> R.string.settings_wallpaper_importing
                                    hasPhoto -> R.string.settings_wallpaper_change
                                    else -> R.string.settings_wallpaper_import
                                },
                            ),
                        )
                    }
                    TextButton(
                        colors = readableTextButtonColors(),
                        onClick = { updateSelection(selection.copy(localImageName = null)) },
                        enabled = !importing && hasPhoto,
                        modifier = Modifier.weight(1f).testTag("settings_wallpaper_remove"),
                    ) {
                        Text(stringResource(R.string.settings_wallpaper_remove))
                    }
                }
            }
            items(wallpaperSheetPresets, key = { it.name }) { preset ->
                val selected = !hasPhoto && selection.preset == preset
                val description = wallpaperDescription(preset)
                val tag = "settings_wallpaper_preset_${preset.name.lowercase()}"
                ListItem(
                    headlineContent = { Text(wallpaperLabel(preset)) },
                    supportingContent = description?.let { { Text(it) } },
                    leadingContent = {
                        WallpaperOptionThumbnail(
                            WallpaperRenderSpec(
                                WallpaperSelection(preset, intensity = 100, scenicFollowSystem = selection.scenicFollowSystem),
                                pattern = null,
                            ),
                            "${tag}_thumbnail",
                        )
                    },
                    trailingContent = { RadioButton(selected = selected, onClick = null, enabled = !importing) },
                    modifier =
                        Modifier
                            .selectable(selected = selected, enabled = !importing, role = Role.RadioButton) {
                                updateSelection(selection.copy(preset = preset, localImageName = null, patternRef = null))
                            }.testTag(tag),
                )
            }
            if (showIntensity) {
                item(key = "intensity") {
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.settings_wallpaper_intensity), modifier = Modifier.weight(1f))
                            Text("${selection.intensity}%", fontWeight = FontWeight.SemiBold)
                        }
                        Slider(
                            value = selection.intensity.toFloat(),
                            onValueChange = { updateSelection(selection.copy(intensity = it.roundToInt())) },
                            valueRange = 0f..100f,
                            steps = 19,
                            enabled = !importing,
                            modifier = Modifier.testTag("settings_wallpaper_intensity"),
                        )
                    }
                }
            }
            if (!hasPhoto && selection.preset.isScenic) {
                item(key = "scenic-system") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_wallpaper_scenic_follow_system)) },
                        supportingContent = { Text(stringResource(R.string.settings_wallpaper_scenic_follow_system_desc)) },
                        trailingContent = {
                            Switch(
                                checked = selection.scenicFollowSystem,
                                onCheckedChange = { updateSelection(selection.copy(scenicFollowSystem = it)) },
                                enabled = !importing,
                                modifier = Modifier.testTag("settings_wallpaper_scenic_follow_system"),
                            )
                        },
                    )
                }
                item(key = "scenic-animation") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_wallpaper_scenic_animation)) },
                        supportingContent = { Text(stringResource(R.string.settings_wallpaper_scenic_animation_desc)) },
                        trailingContent = {
                            Switch(
                                checked = selection.scenicAnimationEnabled,
                                onCheckedChange = { updateSelection(selection.copy(scenicAnimationEnabled = it)) },
                                enabled = !importing,
                                modifier = Modifier.testTag("settings_wallpaper_scenic_animation"),
                            )
                        },
                    )
                }
            }
            item(key = "done") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(onClick = onDismiss, modifier = Modifier.testTag("settings_wallpaper_done")) {
                        Text(stringResource(R.string.settings_wallpaper_done))
                    }
                }
            }
        }
    }
}

private val wallpaperSheetPresets = ChatWallpaperPreset.entries.filter { it == ChatWallpaperPreset.NONE || it.isScenic }

@Composable
private fun WallpaperOptionThumbnail(
    wallpaper: WallpaperRenderSpec,
    tag: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(36.dp, 60.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.background)
            .testTag(tag),
    ) {
        ChatWallpaperBackground(wallpaper, Modifier.matchParentSize(), tilePeriodDp = WALLPAPER_THUMBNAIL_TILE_PERIOD_DP, animate = false)
    }
}

@Composable
internal fun WallpaperPreview(
    wallpaper: WallpaperRenderSpec,
    modifier: Modifier = Modifier,
    artworkOnly: Boolean = false,
    tilePeriodDp: Float = if (artworkOnly) WALLPAPER_THUMBNAIL_TILE_PERIOD_DP else WALLPAPER_TILE_PERIOD_DP,
) {
    val selection = wallpaper.selection
    val shape = RoundedCornerShape(22.dp)
    Box(modifier.clip(shape).background(MaterialTheme.colorScheme.background)) {
        val previewModifier =
            if (!artworkOnly && selection.localImageName == null && selection.preset.isScenic) {
                Modifier
                    .align(Alignment.Center)
                    .width(180.dp)
                    .height(315.dp)
                    .clip(shape)
            } else {
                Modifier.matchParentSize()
            }
        Box(previewModifier) {
            ChatWallpaperBackground(
                wallpaper,
                Modifier.matchParentSize(),
                tilePeriodDp = tilePeriodDp,
                animate = !artworkOnly,
            )
            if (!artworkOnly) {
                Column(
                    Modifier.align(Alignment.Center).fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(stringResource(R.string.settings_wallpaper_preview_incoming), Modifier.padding(12.dp))
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.settings_wallpaper_preview_outgoing), Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun wallpaperSummary(
    wallpaper: WallpaperRenderSpec,
    appearance: AppearanceConfig,
): String {
    val selection = wallpaper.selection
    if (selection.localImageName != null) {
        return "${stringResource(R.string.settings_wallpaper_custom)} · ${selection.intensity}%"
    }
    val label = selection.patternRef?.let { wallpaperPatternLabel(it, appearance) } ?: wallpaperLabel(selection.preset)
    return if (selection.preset == ChatWallpaperPreset.NONE) label else "$label · ${wallpaper.pattern?.intensity ?: selection.intensity}%"
}

@Composable
internal fun wallpaperPatternLabel(
    ref: WallpaperPatternRef,
    appearance: AppearanceConfig,
): String? =
    when (ref.kind) {
        WallpaperPatternRefKind.STARTER -> {
            WallpaperStarters.find(ref.id)?.let { stringResource(it.label) }
        }

        WallpaperPatternRefKind.SAVED -> {
            appearance.wallpaperLibrary
                .takeIf { appearance.wallpaperLibraryReadError == null }
                ?.designs
                ?.firstOrNull { it.id == ref.id }
                ?.name
        }
    }

@Composable
internal fun wallpaperLabel(preset: ChatWallpaperPreset): String =
    stringResource(
        when (preset) {
            ChatWallpaperPreset.NONE -> R.string.settings_wallpaper_none
            ChatWallpaperPreset.PATTERN -> R.string.settings_wallpaper_motd
            ChatWallpaperPreset.JAPANESE_COUNTRYSIDE -> R.string.settings_wallpaper_japanese_countryside
            ChatWallpaperPreset.RAINY_CAFE -> R.string.settings_wallpaper_rainy_cafe
            ChatWallpaperPreset.MOTD_ARCADE -> R.string.settings_wallpaper_motd_arcade
            ChatWallpaperPreset.MIDNIGHT_TERMINAL -> R.string.settings_wallpaper_midnight_terminal
            ChatWallpaperPreset.CYBERPUNK_CANAL -> R.string.settings_wallpaper_cyberpunk_canal
        },
    )

@Composable
private fun wallpaperDescription(preset: ChatWallpaperPreset): String? =
    when (preset) {
        ChatWallpaperPreset.NONE, ChatWallpaperPreset.PATTERN -> null
        ChatWallpaperPreset.JAPANESE_COUNTRYSIDE -> stringResource(R.string.settings_wallpaper_japanese_countryside_desc)
        ChatWallpaperPreset.RAINY_CAFE -> stringResource(R.string.settings_wallpaper_rainy_cafe_desc)
        ChatWallpaperPreset.MOTD_ARCADE -> stringResource(R.string.settings_wallpaper_motd_arcade_desc)
        ChatWallpaperPreset.MIDNIGHT_TERMINAL -> stringResource(R.string.settings_wallpaper_midnight_terminal_desc)
        ChatWallpaperPreset.CYBERPUNK_CANAL -> stringResource(R.string.settings_wallpaper_cyberpunk_canal_desc)
    }
