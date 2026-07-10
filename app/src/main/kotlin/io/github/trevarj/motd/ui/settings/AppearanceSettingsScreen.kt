package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    SettingsScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack) {
        ThemeModeGroup(current = settings.themeMode, onSelect = onThemeMode)
        SwitchRow(
            title = stringResource(R.string.settings_dynamic_color),
            subtitle = stringResource(R.string.settings_dynamic_color_desc),
            // Dynamic color only applies to the base modes; disable the toggle for terminal themes.
            checked = settings.dynamicColor && !settings.themeMode.isTerminalTheme,
            onCheckedChange = onDynamicColor,
            switchTag = "settings_switch_dynamic_color",
        )
        SubLabel(stringResource(R.string.settings_density))
        DensityGroup(current = settings.layoutDensity, onSelect = onLayoutDensity)
        SubLabel(stringResource(R.string.settings_avatar_style))
        AvatarStyleGroup(current = settings.avatarStyle, onSelect = onAvatarStyle)

        HorizontalDivider()

        SwitchRow(
            title = stringResource(R.string.settings_nick_colors),
            subtitle = stringResource(R.string.settings_nick_colors_desc),
            checked = settings.nickColorsEnabled,
            onCheckedChange = onNickColorsEnabled,
            switchTag = "settings_switch_nick_colors",
        )
        PaletteGroup(
            current = settings.nickColorPalette,
            enabled = settings.nickColorsEnabled,
            onSelect = onNickColorPalette,
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_nick_color_overrides)) },
            supportingContent = countText(settings.nickColorOverrides.size),
            modifier = Modifier.clickable { onOpenNickColors() },
        )

        HorizontalDivider()

        SubLabel(stringResource(R.string.settings_wallpaper))
        ChatWallpaperPicker(current = settings.chatWallpaper, onSelect = onChatWallpaper)
    }
}

@Composable
private fun ThemeModeGroup(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    // Base modes first, then terminal color schemes in a clearly labeled sub-block.
    val baseOptions = listOf(
        ThemeMode.SYSTEM to R.string.settings_theme_system,
        ThemeMode.LIGHT to R.string.settings_theme_light,
        ThemeMode.DARK to R.string.settings_theme_dark,
        ThemeMode.AMOLED to R.string.settings_theme_amoled,
    )
    val terminalOptions = listOf(
        ThemeMode.GRUVBOX_DARK to R.string.settings_theme_gruvbox_dark,
        ThemeMode.GRUVBOX_LIGHT to R.string.settings_theme_gruvbox_light,
        ThemeMode.SOLARIZED_DARK to R.string.settings_theme_solarized_dark,
        ThemeMode.SOLARIZED_LIGHT to R.string.settings_theme_solarized_light,
        ThemeMode.DRACULA to R.string.settings_theme_dracula,
        ThemeMode.NORD to R.string.settings_theme_nord,
        ThemeMode.CATPPUCCIN_LATTE to R.string.settings_theme_catppuccin_latte,
        ThemeMode.CATPPUCCIN_MOCHA to R.string.settings_theme_catppuccin_mocha,
        ThemeMode.TOKYO_NIGHT to R.string.settings_theme_tokyo_night,
    )
    Column(Modifier.selectableGroup()) {
        baseOptions.forEach { (mode, labelRes) ->
            RadioRow(
                label = stringResource(labelRes),
                selected = current == mode,
                enabled = true,
                onClick = { onSelect(mode) },
            )
        }
        // Sub-label separating the terminal schemes from the base OS modes.
        Text(
            text = stringResource(R.string.settings_theme_terminal_schemes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 52.dp, top = 6.dp, bottom = 2.dp),
        )
        terminalOptions.forEach { (mode, labelRes) ->
            RadioRow(
                label = stringResource(labelRes),
                selected = current == mode,
                enabled = true,
                onClick = { onSelect(mode) },
            )
        }
    }
}

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
