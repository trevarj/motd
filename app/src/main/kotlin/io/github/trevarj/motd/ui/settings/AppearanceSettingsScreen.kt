package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.prefs.isTerminalTheme
import io.github.trevarj.motd.ui.chat.ChatWallpaperPicker
import io.github.trevarj.motd.ui.theme.MotdTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ColorLens

/** Appearance category: theme, dynamic color, layout density, avatar style, nick colors, wallpaper. */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit = {},
    onOpenNickColors: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    AppearanceSettingsContent(
        settings = state.settings,
        onBack = onBack,
        onOpenNickColors = onOpenNickColors,
        onThemeMode = viewModel::setThemeMode,
        onDynamicColor = viewModel::setDynamicColor,
        onLayoutDensity = viewModel::setLayoutDensity,
        onAvatarStyle = viewModel::setAvatarStyle,
        onNickColorsEnabled = viewModel::setNickColorsEnabled,
        onNickColorPalette = viewModel::setNickColorPalette,
        onChatWallpaper = viewModel::setChatWallpaper,
    )
}

@Composable
fun AppearanceSettingsContent(
    settings: Settings,
    onBack: () -> Unit,
    onOpenNickColors: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onLayoutDensity: (LayoutDensity) -> Unit,
    onAvatarStyle: (AvatarStyle) -> Unit,
    onNickColorsEnabled: (Boolean) -> Unit,
    onNickColorPalette: (NickColorPalette) -> Unit,
    onChatWallpaper: (io.github.trevarj.motd.data.prefs.ChatWallpaper) -> Unit,
) {
    var showThemeSheet by remember { mutableStateOf(false) }
    SettingsScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack) {
        SettingsGroup(title = stringResource(R.string.settings_theme_section)) {
            SettingsNavigationRow(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_theme),
                value = themeModeLabel(settings.themeMode),
                onClick = { showThemeSheet = true },
                modifier = Modifier.testTag("settings_theme_picker"),
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(
                    if (settings.themeMode.isTerminalTheme) R.string.settings_dynamic_color_unavailable
                    else R.string.settings_dynamic_color_desc,
                ),
                checked = settings.dynamicColor && !settings.themeMode.isTerminalTheme,
                onCheckedChange = onDynamicColor,
                switchTag = "settings_switch_dynamic_color",
                enabled = !settings.themeMode.isTerminalTheme,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_nick_colors),
                subtitle = stringResource(R.string.settings_nick_colors_desc),
                checked = settings.nickColorsEnabled,
                onCheckedChange = onNickColorsEnabled,
                switchTag = "settings_switch_nick_colors",
            )
            PaletteGroup(current = settings.nickColorPalette, enabled = settings.nickColorsEnabled, onSelect = onNickColorPalette)
            SettingsNavigationRow(
                icon = Icons.Outlined.ColorLens,
                title = stringResource(R.string.settings_nick_color_overrides),
                value = pluralStringResource(
                    R.plurals.settings_nick_count,
                    settings.nickColorOverrides.size,
                    settings.nickColorOverrides.size,
                ),
                onClick = onOpenNickColors,
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_layout_section)) {
            SubLabel(stringResource(R.string.settings_density))
            DensityGroup(current = settings.layoutDensity, onSelect = onLayoutDensity)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_avatar_style))
            AvatarStyleGroup(current = settings.avatarStyle, onSelect = onAvatarStyle)
        }
        SettingsGroup(title = stringResource(R.string.settings_wallpaper)) {
            ChatWallpaperPicker(current = settings.chatWallpaper, onSelect = onChatWallpaper)
        }
    }
    if (showThemeSheet) {
        ThemePickerSheet(
            current = settings.themeMode,
            onSelect = { mode -> onThemeMode(mode); showThemeSheet = false },
            onDismiss = { showThemeSheet = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ThemePickerSheet(current: ThemeMode, onSelect: (ThemeMode) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_theme_sheet")) {
        Column(Modifier.selectableGroup().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            ThemeSheetSection(stringResource(R.string.settings_theme_system_group), listOf(ThemeMode.SYSTEM), current, onSelect)
            ThemeSheetSection(stringResource(R.string.settings_theme_light_group), LIGHT_THEME_MODES, current, onSelect)
            ThemeSheetSection(stringResource(R.string.settings_theme_dark_group), DARK_THEME_MODES, current, onSelect)
        }
    }
}

@Composable
private fun ThemeSheetSection(title: String, modes: List<ThemeMode>, current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    SubLabel(title)
    modes.forEach { mode -> RadioRow(themeModeLabel(mode), current == mode, true, { onSelect(mode) }) }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.settings_theme_system
        ThemeMode.LIGHT -> R.string.settings_theme_light
        ThemeMode.DARK -> R.string.settings_theme_dark
        ThemeMode.AMOLED -> R.string.settings_theme_amoled
        ThemeMode.GRUVBOX_DARK -> R.string.settings_theme_gruvbox_dark
        ThemeMode.GRUVBOX_LIGHT -> R.string.settings_theme_gruvbox_light
        ThemeMode.SOLARIZED_DARK -> R.string.settings_theme_solarized_dark
        ThemeMode.SOLARIZED_LIGHT -> R.string.settings_theme_solarized_light
        ThemeMode.DRACULA -> R.string.settings_theme_dracula
        ThemeMode.NORD -> R.string.settings_theme_nord
        ThemeMode.CATPPUCCIN_LATTE -> R.string.settings_theme_catppuccin_latte
        ThemeMode.CATPPUCCIN_MOCHA -> R.string.settings_theme_catppuccin_mocha
        ThemeMode.TOKYO_NIGHT -> R.string.settings_theme_tokyo_night
    },
)

internal val LIGHT_THEME_MODES = listOf(
    ThemeMode.CATPPUCCIN_LATTE, ThemeMode.GRUVBOX_LIGHT, ThemeMode.LIGHT, ThemeMode.SOLARIZED_LIGHT,
)
internal val DARK_THEME_MODES = listOf(
    ThemeMode.AMOLED, ThemeMode.CATPPUCCIN_MOCHA, ThemeMode.DARK, ThemeMode.DRACULA,
    ThemeMode.GRUVBOX_DARK, ThemeMode.NORD, ThemeMode.SOLARIZED_DARK, ThemeMode.TOKYO_NIGHT,
)

@Composable
private fun AvatarStyleGroup(current: AvatarStyle, onSelect: (AvatarStyle) -> Unit) {
    val options = listOf(
        AvatarStyle.MONOGRAM to R.string.settings_avatar_monogram,
        AvatarStyle.INITIALS to R.string.settings_avatar_initials,
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (style, labelRes) ->
            RadioRow(
                label = stringResource(labelRes),
                selected = current == style,
                enabled = true,
                onClick = { onSelect(style) },
            )
        }
    }
}

@Composable
private fun DensityGroup(current: LayoutDensity, onSelect: (LayoutDensity) -> Unit) {
    // Density selects the message *render style*, not the font size: Compact is classic single-line
    // IRC, Comfortable is chat bubbles, Two-line is a compact avatar+nick+time header over the body.
    // Subtitles spell that out.
    val options = listOf(
        Triple(LayoutDensity.COMPACT, R.string.settings_density_compact, R.string.settings_density_compact_desc),
        Triple(LayoutDensity.COMFORTABLE, R.string.settings_density_comfortable, R.string.settings_density_comfortable_desc),
        Triple(LayoutDensity.TWO_LINE, R.string.settings_density_two_line, R.string.settings_density_two_line_desc),
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (density, labelRes, descRes) ->
            RadioRow(
                label = stringResource(labelRes),
                subtitle = stringResource(descRes),
                selected = current == density,
                enabled = true,
                onClick = { onSelect(density) },
            )
        }
    }
}

@Composable
private fun PaletteGroup(
    current: NickColorPalette,
    enabled: Boolean,
    onSelect: (NickColorPalette) -> Unit,
) {
    val options = listOf(
        NickColorPalette.DEFAULT to R.string.settings_palette_default,
        NickColorPalette.VIVID to R.string.settings_palette_vivid,
        NickColorPalette.PASTEL to R.string.settings_palette_pastel,
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (palette, labelRes) ->
            RadioRow(
                label = stringResource(labelRes),
                selected = current == palette,
                enabled = enabled,
                onClick = { onSelect(palette) },
            )
        }
    }
}

@Preview
@Composable
private fun AppearanceSettingsPreview() {
    MotdTheme {
        AppearanceSettingsContent(
            settings = Settings(themeMode = ThemeMode.DARK, dynamicColor = true),
            onBack = {}, onOpenNickColors = {}, onThemeMode = {}, onDynamicColor = {},
            onLayoutDensity = {}, onAvatarStyle = {}, onNickColorsEnabled = {},
            onNickColorPalette = {}, onChatWallpaper = {},
        )
    }
}
