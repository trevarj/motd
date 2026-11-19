@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.trevarj.motd.ui.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.ChatWallpaperPreset
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.WallpaperChatChoice
import io.github.trevarj.motd.data.prefs.WallpaperChatChoiceKind
import io.github.trevarj.motd.data.prefs.WallpaperPatternRef
import io.github.trevarj.motd.data.prefs.WallpaperPatternRefKind
import io.github.trevarj.motd.data.prefs.WallpaperStarters
import io.github.trevarj.motd.data.prefs.decodeWallpaperChatChoice
import io.github.trevarj.motd.data.prefs.isValidIn
import io.github.trevarj.motd.data.prefs.resolveWallpaper
import io.github.trevarj.motd.ui.chat.WALLPAPER_THUMBNAIL_TILE_PERIOD_DP
import io.github.trevarj.motd.ui.chat.WallpaperPreview
import io.github.trevarj.motd.ui.chat.wallpaperLabel
import io.github.trevarj.motd.ui.chat.wallpaperPatternLabel
import java.io.File
import io.github.trevarj.motd.ui.components.EmojiText as Text
import io.github.trevarj.motd.ui.theme.UiIcon as Icon

@Composable
internal fun WallpaperWorkshopScreen(
    viewModel: WallpaperWorkshopViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val back: () -> Unit = { if (viewModel.back()) onBack() }
    BackHandler(onBack = back)
    LaunchedEffect(state.applied) { if (state.applied) onBack() }
    val pages = rememberSaveableStateHolder()
    SettingsScaffold(
        title = stringResource(R.string.wallpaper_workshop_title),
        onBack = back,
        modifier = Modifier.testTag("wallpaper_workshop"),
        scroll = false,
        pagePadding = false,
        maxPageWidth = 1200.dp,
    ) {
        if (!state.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            if (state.busy) {
                val writing = stringResource(R.string.wallpaper_workshop_writing)
                LinearProgressIndicator(Modifier.fillMaxWidth().semantics { stateDescription = writing })
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 800.dp
                val draft = state.draft
                when (state.page) {
                    WallpaperWorkshopPage.HUB -> {
                        pages.SaveableStateProvider("hub") {
                            WorkshopHub(state, viewModel, columns = if (wide) 3 else 2)
                        }
                    }

                    WallpaperWorkshopPage.DETAIL -> {
                        WorkshopDetail(state, viewModel)
                    }

                    WallpaperWorkshopPage.EDITOR -> {
                        if (draft != null) {
                            pages.SaveableStateProvider("editor") {
                                key(draft.id) {
                                    WorkshopEditor(state, draft, viewModel, wide, short = maxHeight < 550.dp)
                                }
                            }
                        }
                    }

                    WallpaperWorkshopPage.PREVIEW -> {
                        WorkshopExpandedPreview(state, viewModel)
                    }
                }
            }
        }
    }
    WorkshopDialogs(state, viewModel)
}

@Composable
private fun WorkshopHub(
    state: WallpaperWorkshopUiState,
    viewModel: WallpaperWorkshopViewModel,
    columns: Int,
) {
    val appearance = state.appearance
    val library = appearance.wallpaperLibrary.takeIf { appearance.wallpaperLibraryReadError == null }
    val encoded = state.room?.wallpaperOverrideEncoded
    val decoded = decodeWallpaperChatChoice(encoded)
    val choice = decoded.takeUnless { it?.pattern?.isValidIn(library) == false }
    val spec = resolveWallpaper(appearance, choice)
    val current = spec.selection
    val isChat = state.roomId != null
    val appliedRef =
        if (isChat) {
            choice?.pattern
        } else if (current.preset == ChatWallpaperPreset.PATTERN && current.localImageName == null) {
            current.patternRef ?: WallpaperPatternRef(WallpaperPatternRefKind.STARTER, "motd").takeIf {
                spec.pattern == WallpaperStarters.find(it.id)?.pattern
            }
        } else {
            null
        }
    val name =
        when {
            current.localImageName != null -> stringResource(R.string.settings_wallpaper_custom)
            current.patternRef != null -> wallpaperPatternLabel(current.patternRef, appearance) ?: wallpaperLabel(current.preset)
            else -> wallpaperLabel(current.preset)
        }
    val missing =
        (isChat && encoded != null && choice == null) ||
            (choice == null && appearance.wallpaper.patternRef?.isValidIn(library) == false)
    val heading = workshopTarget(state)
    val none = WallpaperChatChoice(WallpaperChatChoiceKind.NONE)
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("wallpaper_workshop_hub"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("target") {
            WorkshopHeading(heading)
            if (isChat) Text(stringResource(R.string.wallpaper_workshop_conversation))
            if (isChat && state.room == null) WorkshopWarning(R.string.wallpaper_workshop_target_unavailable)
            Spacer(Modifier.height(6.dp))
            SettingsGroup(title = stringResource(R.string.wallpaper_workshop_current)) {
                SettingsValueRow(
                    title = name,
                    value = stringResource(if (isChat && choice == null) R.string.wallpaper_workshop_inherit_global else R.string.wallpaper_workshop_applied),
                )
            }
            if (missing) WorkshopWarning(R.string.wallpaper_workshop_missing_design)
            if (appearance.wallpaperLibraryReadError != null) WorkshopWarning(R.string.wallpaper_workshop_library_invalid)
        }
        if (isChat) {
            item("choices") {
                SettingsGroup {
                    Column(Modifier.selectableGroup()) {
                        RadioRow(
                            label = stringResource(R.string.wallpaper_workshop_inherit),
                            selected = choice == null,
                            enabled = !state.busy,
                            onClick = { viewModel.detail(null) },
                            modifier = Modifier.testTag("wallpaper_choice_inherit"),
                            subtitle = stringResource(R.string.wallpaper_workshop_inherit_global),
                        )
                        RadioRow(
                            label = stringResource(R.string.settings_wallpaper_none),
                            selected = choice == none,
                            enabled = !state.busy,
                            onClick = { viewModel.detail(none) },
                            modifier = Modifier.testTag("wallpaper_choice_none"),
                        )
                    }
                }
            }
        }
        item("new") {
            OutlinedCard(
                onClick = viewModel::newDesign,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).testTag("wallpaper_workshop_new"),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.wallpaper_workshop_new), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.wallpaper_workshop_add_to_start))
                }
            }
        }
        item("designs") { WorkshopHeading(stringResource(R.string.wallpaper_workshop_designs)) }
        if (library?.designs.isNullOrEmpty() && appearance.wallpaperLibraryReadError == null) {
            item("no-designs") { Text(stringResource(R.string.wallpaper_workshop_no_designs)) }
        }
        items(library?.designs.orEmpty().chunked(columns), key = { "saved-row-${it.first().id}" }) { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                group.forEach { design ->
                    val ref = WallpaperPatternRef(WallpaperPatternRefKind.SAVED, design.id)
                    WorkshopDesignCard(design.name, ref, state, appliedRef == ref, viewModel, Modifier.weight(1f))
                }
                repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        item("starters") { WorkshopHeading(stringResource(R.string.wallpaper_workshop_starters)) }
        items(WallpaperStarters.entries.chunked(columns), key = { "starter-row-${it.first().id}" }) { group ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                group.forEach { starter ->
                    val ref = WallpaperPatternRef(WallpaperPatternRefKind.STARTER, starter.id)
                    WorkshopDesignCard(stringResource(starter.label), ref, state, appliedRef == ref, viewModel, Modifier.weight(1f))
                }
                repeat(columns - group.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun WorkshopDesignCard(
    name: String,
    ref: WallpaperPatternRef,
    state: WallpaperWorkshopUiState,
    applied: Boolean,
    viewModel: WallpaperWorkshopViewModel,
    modifier: Modifier,
) {
    val choice = remember(ref) { WallpaperChatChoice(WallpaperChatChoiceKind.PATTERN, ref) }
    val spec = remember(state.appearance, choice) { resolveWallpaper(state.appearance, choice) }
    val appliedLabel = stringResource(R.string.wallpaper_workshop_applied)
    var menu by remember { mutableStateOf(false) }
    val group = if (ref.kind == WallpaperPatternRefKind.SAVED) "saved" else "starter"
    OutlinedCard(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .selectable(applied, enabled = !state.busy, role = Role.RadioButton, onClick = { viewModel.detail(choice) })
                .testTag("wallpaper_${group}_${ref.id}"),
        ) {
            WallpaperPreview(
                spec,
                Modifier.fillMaxWidth().height(96.dp),
                artworkOnly = true,
                tilePeriodDp = if (ref.kind == WallpaperPatternRefKind.STARTER) 256f else WALLPAPER_THUMBNAIL_TILE_PERIOD_DP,
            )
            Text(name, Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp), style = MaterialTheme.typography.titleSmall)
            if (applied) Text(appliedLabel, Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium)
        }
        Box(Modifier.align(Alignment.End)) {
            IconButton(onClick = { menu = true }, enabled = !state.busy) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.wallpaper_workshop_options, name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.wallpaper_workshop_duplicate)) }, onClick = {
                    menu = false
                    viewModel.duplicate(ref)
                })
                if (ref.kind == WallpaperPatternRefKind.SAVED) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                        menu = false
                        viewModel.requestDelete(ref)
                    })
                }
            }
        }
    }
}

@Composable
private fun WorkshopDetail(
    state: WallpaperWorkshopUiState,
    viewModel: WallpaperWorkshopViewModel,
) {
    val ref = state.detailChoice?.pattern
    val name =
        when {
            ref != null -> wallpaperPatternLabel(ref, state.appearance)
            state.detailChoice?.kind == WallpaperChatChoiceKind.NONE -> stringResource(R.string.settings_wallpaper_none)
            else -> stringResource(R.string.wallpaper_workshop_inherit)
        }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WorkshopHeading(name ?: stringResource(R.string.wallpaper_workshop_current))
        Text(workshopTarget(state))
        if (state.roomId != null && state.room == null) WorkshopWarning(R.string.wallpaper_workshop_target_unavailable)
        if (name == null) WorkshopWarning(R.string.wallpaper_workshop_missing_design)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = viewModel::preview, modifier = Modifier.heightIn(min = 48.dp).testTag("wallpaper_detail_preview")) {
                Text(stringResource(R.string.wallpaper_workshop_preview))
            }
            if (ref != null) {
                OutlinedButton(
                    onClick = viewModel::editDetail,
                    enabled = name != null && !state.busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("wallpaper_detail_edit"),
                ) { Text(stringResource(R.string.action_edit)) }
            }
            WorkshopApply(state, "wallpaper_detail_apply", viewModel::apply)
        }
        WorkshopConversationPreview(state, viewModel.customFontFile, Modifier.fillMaxWidth())
    }
}

@Composable
private fun WorkshopExpandedPreview(
    state: WallpaperWorkshopUiState,
    viewModel: WallpaperWorkshopViewModel,
) {
    BoxWithConstraints {
        val artworkHeight = (maxHeight - 180.dp).coerceAtLeast(220.dp)
        WorkshopPreviewControls(state, viewModel, artworkHeight)
    }
}

@Composable
private fun WorkshopPreviewControls(
    state: WallpaperWorkshopUiState,
    viewModel: WallpaperWorkshopViewModel,
    artworkHeight: Dp,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkshopHeading(stringResource(R.string.wallpaper_workshop_preview_mode))
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((mode, label) in listOf(
                WallpaperWorkshopPreviewMode.CHAT to R.string.wallpaper_workshop_preview_chat,
                WallpaperWorkshopPreviewMode.WALLPAPER to R.string.wallpaper_workshop_preview_wallpaper,
            )) {
                WorkshopChoiceChip(state.previewMode == mode, stringResource(label), Modifier.testTag("wallpaper_preview_${mode.name.lowercase()}")) {
                    viewModel.setPreviewMode(mode)
                }
            }
        }
        WorkshopHeading(stringResource(R.string.wallpaper_workshop_preview_theme))
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((theme, label) in listOf(
                WallpaperWorkshopPreviewTheme.CURRENT to R.string.wallpaper_workshop_preview_current,
                WallpaperWorkshopPreviewTheme.LIGHT to R.string.wallpaper_workshop_preview_light,
                WallpaperWorkshopPreviewTheme.DARK to R.string.wallpaper_workshop_preview_dark,
            )) {
                WorkshopChoiceChip(state.previewTheme == theme, stringResource(label), Modifier.testTag("wallpaper_preview_${theme.name.lowercase()}")) {
                    viewModel.setPreviewTheme(theme)
                }
            }
        }
        val preview =
            Modifier.fillMaxWidth().then(
                if (state.previewMode == WallpaperWorkshopPreviewMode.WALLPAPER) Modifier.height(artworkHeight) else Modifier,
            )
        WorkshopConversationPreview(state, viewModel.customFontFile, preview, expanded = true)
    }
}

@Composable
private fun WorkshopConversationPreview(
    state: WallpaperWorkshopUiState,
    font: File?,
    modifier: Modifier,
    expanded: Boolean = false,
) {
    val appearance = state.previewAppearance
    val settings = state.previewSettings
    AppearanceChatLayoutPreview(
        settings = settings,
        appearance = appearance,
        customFontFile = font,
        density = settings.layoutDensity,
        messageSpacing = appearance.messageSpacing,
        bubbleCorners = appearance.bubbleCornerStyle,
        chatShadowsEnabled = appearance.chatShadowsEnabled,
        tagPrefix = if (expanded) "wallpaper_preview_sample" else "wallpaper_detail_sample",
        modifier = modifier,
        wallpaper = state.renderSpec,
        previewPaletteMode =
            when (state.previewTheme) {
                WallpaperWorkshopPreviewTheme.CURRENT -> null
                WallpaperWorkshopPreviewTheme.LIGHT -> PaletteMode.LIGHT
                WallpaperWorkshopPreviewTheme.DARK -> PaletteMode.DARK
            }.takeIf { expanded },
        wallpaperOnly = expanded && state.previewMode == WallpaperWorkshopPreviewMode.WALLPAPER,
    )
}

@Composable
internal fun WorkshopApply(
    state: WallpaperWorkshopUiState,
    tag: String,
    apply: () -> Unit,
) {
    val targetLabel =
        if (state.roomId == null) {
            stringResource(R.string.wallpaper_workshop_apply_global)
        } else {
            stringResource(R.string.wallpaper_workshop_apply_to, workshopTarget(state))
        }
    Button(
        onClick = apply,
        enabled = state.canApply,
        modifier = Modifier.heightIn(min = 48.dp).testTag(tag),
    ) {
        Text(targetLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun WorkshopUsage(state: WallpaperWorkshopUiState) {
    if (state.usedByGlobal) Text(stringResource(R.string.wallpaper_workshop_used_globally))
    if (state.chatUses > 0) Text(pluralStringResource(R.plurals.wallpaper_workshop_chat_uses, state.chatUses, state.chatUses))
}

@Composable
internal fun WorkshopChoiceChip(
    selected: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        leadingIcon =
            if (selected) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
            } else {
                null
            },
    )
}

@Composable
internal fun WorkshopHeading(title: String) {
    Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun WorkshopWarning(
    @StringRes resource: Int,
) {
    Text(stringResource(resource), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun workshopTarget(state: WallpaperWorkshopUiState): String =
    if (state.roomId == null) {
        stringResource(R.string.wallpaper_workshop_global)
    } else {
        state.room?.displayName ?: stringResource(R.string.wallpaper_workshop_conversation)
    }

@Composable
private fun WorkshopDialogs(
    state: WallpaperWorkshopUiState,
    viewModel: WallpaperWorkshopViewModel,
) {
    when {
        state.discardConfirm -> {
            AlertDialog(
                onDismissRequest = viewModel::cancelDiscard,
                title = { Text(stringResource(R.string.wallpaper_workshop_discard_title)) },
                dismissButton = { TextButton(onClick = viewModel::cancelDiscard) { Text(stringResource(R.string.wallpaper_workshop_keep_editing)) } },
                confirmButton = {
                    Button(onClick = viewModel::confirmDiscard, modifier = Modifier.testTag("wallpaper_discard")) {
                        Text(stringResource(R.string.wallpaper_workshop_discard))
                    }
                },
            )
        }

        state.deleteConfirm != null -> {
            AlertDialog(
                onDismissRequest = viewModel::cancelDelete,
                title = { Text(stringResource(R.string.wallpaper_workshop_delete_title, state.deleteConfirm.name)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.wallpaper_workshop_delete_message))
                        WorkshopUsage(state)
                    }
                },
                dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.action_cancel)) } },
                confirmButton = { Button(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.action_delete)) } },
            )
        }

        state.error != null && state.picker == null -> {
            AlertDialog(
                onDismissRequest = viewModel::dismissError,
                text = { Text(stringResource(state.error.label)) },
                confirmButton = {
                    TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.action_dismiss)) }
                },
            )
        }
    }
}

internal val WallpaperWorkshopError.label: Int
    @StringRes get() =
        when (this) {
            WallpaperWorkshopError.INVALID -> R.string.wallpaper_workshop_invalid
            WallpaperWorkshopError.LIBRARY_INVALID -> R.string.wallpaper_workshop_library_invalid
            WallpaperWorkshopError.TOO_LARGE -> R.string.wallpaper_workshop_too_large
            WallpaperWorkshopError.CONFLICT -> R.string.wallpaper_workshop_conflict
            WallpaperWorkshopError.TARGET_UNAVAILABLE -> R.string.wallpaper_workshop_target_unavailable
            WallpaperWorkshopError.SAVED_NOT_APPLIED -> R.string.wallpaper_workshop_saved_not_applied
            WallpaperWorkshopError.SVG_UNREADABLE -> R.string.wallpaper_workshop_svg_unreadable
            WallpaperWorkshopError.SVG_TOO_LARGE -> R.string.wallpaper_workshop_svg_too_large
            WallpaperWorkshopError.SVG_UNSUPPORTED -> R.string.wallpaper_workshop_svg_unsupported
            WallpaperWorkshopError.SVG_NOT_OUTLINE -> R.string.wallpaper_workshop_svg_not_outline
        }
