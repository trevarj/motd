package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.EffectSurface
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.chat.ChatWallpaperBackground
import io.github.trevarj.motd.ui.components.captureSurfaceBackdrop
import io.github.trevarj.motd.ui.components.rememberSurfaceBackdropSource
import io.github.trevarj.motd.ui.components.surfaceBackdrop
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.LocalSurfaceEffects

internal fun surfaceEffectTitle(surface: EffectSurface): Int =
    when (surface) {
        EffectSurface.CHAT_HEADER -> R.string.settings_surface_chat_header
        EffectSurface.INPUT_BAR -> R.string.settings_surface_input_bar
        EffectSurface.CHAT_LIST -> R.string.settings_surface_chat_list
        EffectSurface.MENU -> R.string.settings_surface_menu
        EffectSurface.SHEET -> R.string.settings_surface_sheet
        EffectSurface.DRAWER -> R.string.settings_surface_drawer
        EffectSurface.MINI_PLAYER -> R.string.settings_surface_mini_player
        EffectSurface.FLOATING_BUTTON -> R.string.settings_surface_floating_button
    }

@Composable
fun SurfaceEffectsSettingsScreen(
    onBack: () -> Unit,
    target: SettingsTarget? = null,
    viewModel: AppearanceSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SurfaceEffectsSettingsContent(
        state.appearance,
        state.settings,
        onBack,
        viewModel::setSurfaceEffectsEnabled,
        viewModel::setLinkGlobalOpacity,
        viewModel::setLinkGlobalBlur,
        viewModel::setMessageBackgroundOpacity,
        viewModel::setMessageBackgroundBlur,
        viewModel::setSurfaceOpacity,
        viewModel::setSurfaceBlur,
        target,
        customFontFile = viewModel.customFontFile,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SurfaceEffectsSettingsContent(
    appearance: AppearanceConfig,
    settings: Settings,
    onBack: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onLinkOpacity: (Boolean) -> Unit,
    onLinkBlur: (Boolean) -> Unit,
    onMessageOpacity: (Int) -> Unit,
    onMessageBlur: (Int) -> Unit,
    onSurfaceOpacity: (EffectSurface, Int) -> Unit,
    onSurfaceBlur: (EffectSurface, Int) -> Unit,
    target: SettingsTarget? = null,
    customFontFile: java.io.File? = null,
) {
    var selected by rememberSaveable { mutableStateOf(EffectSurface.CHAT_HEADER) }
    var draft by remember { mutableStateOf(appearance) }
    LaunchedEffect(appearance) { draft = appearance }
    val targetIndex =
        when (target) {
            SettingsTarget.MESSAGE_BACKGROUND_OPACITY -> 4
            SettingsTarget.MESSAGE_BACKGROUND_BLUR -> 5
            else -> 0
        }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = targetIndex)

    fun editOpacity(
        surface: EffectSurface?,
        percent: Int,
    ) {
        val effects = draft.surfaceEffects
        val targets = if (draft.linkGlobalOpacity) EffectSurface.entries else listOfNotNull(surface)
        draft =
            draft.copy(
                messageBackgroundOpacityPercent = if (surface == null || draft.linkGlobalOpacity) percent else draft.messageBackgroundOpacityPercent,
                surfaceEffects = if (targets.isEmpty()) effects else effects.copy(surfaces = effects.surfaces + targets.associateWith { effects[it].copy(opacityPercent = percent) }),
            )
        if (surface == null) onMessageOpacity(percent) else onSurfaceOpacity(surface, percent)
    }

    fun editBlur(
        surface: EffectSurface?,
        percent: Int,
    ) {
        val effects = draft.surfaceEffects
        val targets = if (draft.linkGlobalBlur) EffectSurface.entries else listOfNotNull(surface)
        draft =
            draft.copy(
                messageBackgroundBlur = if (surface == null || draft.linkGlobalBlur) percent else draft.messageBackgroundBlur,
                surfaceEffects = if (targets.isEmpty()) effects else effects.copy(surfaces = effects.surfaces + targets.associateWith { effects[it].copy(blurPercent = percent) }),
            )
        if (surface == null) onMessageBlur(percent) else onSurfaceBlur(surface, percent)
    }
    CompositionLocalProvider(LocalSurfaceEffects provides draft.surfaceEffects) {
        SettingsScaffold(stringResource(R.string.settings_surface_effects), onBack, scroll = false) {
            LazyColumn(Modifier.fillMaxWidth().testTag("surface_effects_page"), state = listState) {
                item {
                    SettingsTarget(target?.name, SettingsTarget.SURFACE_EFFECTS.name) { modifier ->
                        SwitchRow(stringResource(R.string.settings_surface_effects_master), stringResource(R.string.settings_surface_effects_desc), draft.surfaceEffects.enabled, {
                            draft = draft.copy(surfaceEffects = draft.surfaceEffects.copy(enabled = it))
                            onEnabled(it)
                        }, modifier = modifier, switchTag = "surface_effects_master")
                    }
                }
                item {
                    SwitchRow(stringResource(R.string.settings_link_global_opacity), stringResource(R.string.settings_link_global_effects_desc), draft.linkGlobalOpacity, {
                        draft = draft.copy(linkGlobalOpacity = it)
                        onLinkOpacity(it)
                    }, switchTag = "surface_effects_link_opacity")
                }
                item {
                    SwitchRow(stringResource(R.string.settings_link_global_blur), stringResource(R.string.settings_link_global_effects_desc), draft.linkGlobalBlur, {
                        draft = draft.copy(linkGlobalBlur = it)
                        onLinkBlur(it)
                    }, switchTag = "surface_effects_link_blur")
                }
                item {
                    Text(stringResource(R.string.settings_appearance_messages_section), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp))
                    AppearanceChatLayoutPreview(settings, draft, customFontFile, settings.layoutDensity, draft.messageSpacing, draft.bubbleCornerStyle, draft.chatShadowsEnabled, "settings_effects_message_preview")
                }
                item {
                    SettingsTarget(target?.name, SettingsTarget.MESSAGE_BACKGROUND_OPACITY.name) { modifier ->
                        MessageBackgroundOpacitySlider(draft.messageBackgroundOpacityPercent, { editOpacity(null, it) }, modifier)
                    }
                }
                item {
                    SettingsTarget(target?.name, SettingsTarget.MESSAGE_BACKGROUND_BLUR.name) { modifier ->
                        MessageBackgroundBlurSlider(draft.messageBackgroundBlur, { editBlur(null, it) }, modifier)
                    }
                }
                stickyHeader {
                    Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                        SurfaceEffectPreview(draft, selected)
                    }
                }
                items(EffectSurface.entries, key = { it.name }) { surface ->
                    val title = stringResource(surfaceEffectTitle(surface))
                    val value = draft.surfaceEffects[surface]
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { selected = surface }
                                .padding(16.dp)
                                .testTag("surface_${surface.name.lowercase()}_select"),
                    )
                    BackingSlider(value.opacityPercent, {
                        selected = surface
                        editOpacity(surface, it)
                    }, stringResource(R.string.settings_surface_opacity), stringResource(R.string.settings_surface_effects_off), Modifier.onFocusChanged { if (it.hasFocus) selected = surface }, "surface_${surface.name.lowercase()}_opacity", accessibilityLabel = "$title: ${stringResource(R.string.settings_surface_opacity)}")
                    BackingSlider(value.blurPercent, {
                        selected = surface
                        editBlur(surface, it)
                    }, stringResource(R.string.settings_surface_blur), stringResource(R.string.settings_surface_effects_off), Modifier.onFocusChanged { if (it.hasFocus) selected = surface }, "surface_${surface.name.lowercase()}_blur", accessibilityLabel = "$title: ${stringResource(R.string.settings_surface_blur)}")
                }
            }
        }
    }
}

@Composable
private fun SurfaceEffectPreview(
    appearance: AppearanceConfig,
    surface: EffectSurface,
) {
    val source = rememberSurfaceBackdropSource()
    val settings = appearance.surfaceEffects[surface]
    Box(
        Modifier
            .fillMaxWidth()
            .height(100.dp)
            .padding(horizontal = 16.dp)
            .testTag("surface_effects_preview"),
    ) {
        Box(Modifier.matchParentSize().captureSurfaceBackdrop(source).background(MaterialTheme.colorScheme.surface)) {
            ChatWallpaperBackground(appearance.wallpaper, Modifier.matchParentSize(), animate = false)
            Text(stringResource(R.string.settings_surface_preview_backdrop), modifier = Modifier.padding(8.dp))
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxWidth(0.7f)
                .height(72.dp)
                .surfaceBackdrop(source, MaterialTheme.colorScheme.surfaceContainerLow, settings.opacityPercent, settings.blurPercent, appearance.surfaceEffects.enabled, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(surfaceEffectTitle(surface))) }
    }
}
