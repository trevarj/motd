package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.isFixedPalette
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.data.prefs.DEFAULT_FONT_SCALE_PERCENT
import io.github.trevarj.motd.data.prefs.FONT_SCALE_STEP_PERCENT
import io.github.trevarj.motd.data.prefs.MAX_FONT_SCALE_PERCENT
import io.github.trevarj.motd.data.prefs.MIN_FONT_SCALE_PERCENT
import io.github.trevarj.motd.ui.chat.ChatWallpaperPicker
import io.github.trevarj.motd.ui.components.IrcChannelBadge
import io.github.trevarj.motd.ui.components.IrcSpriteAvatar
import io.github.trevarj.motd.ui.theme.MotdTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.roundToInt

/** Appearance category: theme, dynamic color, layout density, avatar style, nick colors, wallpaper. */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit = {},
    onOpenNickColors: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppearanceSettingsContent(
        settings = state.settings,
        appearance = state.appearance,
        onBack = onBack,
        onOpenNickColors = onOpenNickColors,
        onThemePreset = viewModel::setThemePreset,
        onTrueBlack = viewModel::setTrueBlack,
        onDynamicColor = viewModel::setDynamicColor,
        onLayoutDensity = viewModel::setLayoutDensity,
        onAvatarStyle = viewModel::setAvatarStyle,
        onNickColorsEnabled = viewModel::setNickColorsEnabled,
        onNickColorPalette = viewModel::setNickColorPalette,
        onWallpaper = viewModel::setWallpaper,
        onUiFontScale = viewModel::setUiFontScale,
        onConversationFontScale = viewModel::setConversationFontScale,
    )
}

@Composable
fun AppearanceSettingsContent(
    settings: Settings,
    appearance: io.github.trevarj.motd.data.prefs.AppearanceConfig,
    onBack: () -> Unit,
    onOpenNickColors: () -> Unit,
    onThemePreset: (ColorThemePreset) -> Unit,
    onTrueBlack: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onLayoutDensity: (LayoutDensity) -> Unit,
    onAvatarStyle: (AvatarStyle) -> Unit,
    onNickColorsEnabled: (Boolean) -> Unit,
    onNickColorPalette: (NickColorPalette) -> Unit,
    onWallpaper: (io.github.trevarj.motd.data.prefs.WallpaperSelection) -> Unit,
    onUiFontScale: (Int) -> Unit,
    onConversationFontScale: (Int) -> Unit,
) {
    var showThemeSheet by rememberSaveable { mutableStateOf(false) }
    val trueBlackAvailable = appearance.theme == ColorThemePreset.SYSTEM || appearance.theme.isDark
    SettingsScaffold(title = stringResource(R.string.settings_appearance), onBack = onBack) {
        SettingsGroup(title = stringResource(R.string.settings_theme_section)) {
            SettingsNavigationRow(
                icon = Icons.Outlined.Palette,
                title = stringResource(R.string.settings_theme),
                value = themePresetLabel(appearance.theme),
                onClick = { showThemeSheet = true },
                modifier = Modifier.testTag("settings_theme_picker"),
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_true_black),
                subtitle = stringResource(
                    when {
                        appearance.theme == ColorThemePreset.SYSTEM -> R.string.settings_true_black_system_desc
                        trueBlackAvailable -> R.string.settings_true_black_desc
                        appearance.trueBlack -> R.string.settings_true_black_saved_desc
                        else -> R.string.settings_true_black_unavailable_desc
                    },
                ),
                checked = appearance.trueBlack,
                onCheckedChange = onTrueBlack,
                switchTag = "settings_switch_true_black",
                enabled = trueBlackAvailable,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SwitchRow(
                title = stringResource(R.string.settings_dynamic_color),
                subtitle = stringResource(
                    if (appearance.theme.isFixedPalette) R.string.settings_dynamic_color_unavailable
                    else R.string.settings_dynamic_color_desc,
                ),
                checked = settings.dynamicColor && !appearance.theme.isFixedPalette,
                onCheckedChange = onDynamicColor,
                switchTag = "settings_switch_dynamic_color",
                enabled = !appearance.theme.isFixedPalette,
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
                modifier = Modifier.testTag("settings_nick_color_overrides"),
                onClick = onOpenNickColors,
            )
        }
        SettingsGroup(title = stringResource(R.string.settings_layout_section)) {
            FontScaleSlider(
                title = stringResource(R.string.settings_ui_font_size),
                description = stringResource(R.string.settings_ui_font_size_desc),
                value = appearance.uiFontScalePercent,
                tag = "settings_ui_font_scale",
                onValue = onUiFontScale,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            FontScaleSlider(
                title = stringResource(R.string.settings_conversation_font_size),
                description = stringResource(R.string.settings_conversation_font_size_desc),
                value = appearance.conversationFontScalePercent,
                tag = "settings_conversation_font_scale",
                onValue = onConversationFontScale,
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_density))
            DensityGroup(current = settings.layoutDensity, onSelect = onLayoutDensity)
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SubLabel(stringResource(R.string.settings_avatar_style))
            AvatarStyleGroup(current = settings.avatarStyle, onSelect = onAvatarStyle)
        }
        SettingsGroup(title = stringResource(R.string.settings_wallpaper)) {
            ChatWallpaperPicker(current = appearance.wallpaper, onApply = onWallpaper)
        }
    }
    if (showThemeSheet) {
        ThemePickerSheet(
            current = appearance.theme,
            trueBlack = appearance.trueBlack,
            dynamicColor = settings.dynamicColor,
            onSelect = onThemePreset,
            onDismiss = { showThemeSheet = false },
        )
    }
}

@Composable
private fun FontScaleSlider(
    title: String,
    description: String,
    value: Int,
    tag: String,
    onValue: (Int) -> Unit,
) {
    var pending by remember(value) { mutableStateOf(value.toFloat()) }
    val displayed = pending.toInt()
    val percent = stringResource(R.string.settings_font_size_percent, displayed)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(percent, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = pending,
            onValueChange = { raw ->
                pending = (raw / FONT_SCALE_STEP_PERCENT).roundToInt() * FONT_SCALE_STEP_PERCENT.toFloat()
            },
            onValueChangeFinished = { onValue(pending.toInt()) },
            valueRange = MIN_FONT_SCALE_PERCENT.toFloat()..MAX_FONT_SCALE_PERCENT.toFloat(),
            steps = (MAX_FONT_SCALE_PERCENT - MIN_FONT_SCALE_PERCENT) / FONT_SCALE_STEP_PERCENT - 1,
            modifier = Modifier
                .testTag(tag)
                .semantics {
                    contentDescription = title
                    stateDescription = percent
                },
        )
        if (displayed != DEFAULT_FONT_SCALE_PERCENT) {
            TextButton(
                onClick = {
                    pending = DEFAULT_FONT_SCALE_PERCENT.toFloat()
                    onValue(DEFAULT_FONT_SCALE_PERCENT)
                },
                modifier = Modifier.align(androidx.compose.ui.Alignment.End),
            ) {
                Text(stringResource(R.string.settings_font_size_reset))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ThemePickerSheet(
    current: ColorThemePreset,
    trueBlack: Boolean,
    dynamicColor: Boolean,
    onSelect: (ColorThemePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val normalized = query.trim().lowercase()
    fun filtered(items: List<ColorThemePreset>) = items.filter {
        themePresetLabelText(it).lowercase().contains(normalized)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_theme_sheet")) {
        LazyColumn(
            Modifier.testTag("settings_theme_list").selectableGroup().heightIn(max = 680.dp).padding(bottom = 24.dp),
        ) {
            item {
                Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    placeholder = { Text(stringResource(R.string.settings_theme_search)) },
                    modifier = Modifier.padding(horizontal = 16.dp).testTag("settings_theme_search"),
                )
            }
            val groups = listOf(
                R.string.settings_theme_system_group to filtered(listOf(ColorThemePreset.SYSTEM)),
                R.string.settings_theme_light_group to filtered(LIGHT_THEME_PRESETS),
                R.string.settings_theme_dark_group to filtered(DARK_THEME_PRESETS),
            )
            groups.forEach { (title, modes) ->
                if (modes.isNotEmpty()) {
                    item { SubLabel(stringResource(title)) }
                    items(modes.size) { index ->
                        val mode = modes[index]
                        ThemeRadioRow(mode, current == mode, trueBlack, dynamicColor, onSelect)
                    }
                }
            }
            if (groups.all { it.second.isEmpty() }) {
                item { Text(stringResource(R.string.settings_theme_no_results), modifier = Modifier.padding(24.dp)) }
            }
        }
    }
}

@Composable
private fun ThemeRadioRow(
    mode: ColorThemePreset,
    selected: Boolean,
    trueBlack: Boolean,
    dynamicColor: Boolean,
    onSelect: (ColorThemePreset) -> Unit,
) {
    RadioRow(
        label = themePresetLabel(mode),
        selected = selected,
        enabled = true,
        onClick = { onSelect(mode) },
        modifier = Modifier.testTag("settings_theme_${mode.name.lowercase()}"),
        trailing = {
            MotdTheme(themePreset = mode, trueBlack = trueBlack, dynamicColor = dynamicColor) {
                val scheme = MaterialTheme.colorScheme
                Surface(
                    color = scheme.background,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, scheme.outline),
                    modifier = Modifier
                        .width(88.dp)
                        .height(30.dp)
                        .testTag("settings_theme_preview_${mode.name.lowercase()}"),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("Aa", color = scheme.onBackground, style = MaterialTheme.typography.labelSmall)
                        listOf(scheme.primary, scheme.secondary, scheme.tertiary).forEach { color ->
                            Box(Modifier.size(11.dp).background(color, CircleShape))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun themePresetLabel(mode: ColorThemePreset): String = stringResource(themePresetLabelRes(mode))

internal fun themePresetLabelText(mode: ColorThemePreset): String = when (mode) {
    ColorThemePreset.SYSTEM -> "System default"
    ColorThemePreset.LIGHT -> "Light"
    ColorThemePreset.DARK -> "Dark"
    ColorThemePreset.AMOLED -> "AMOLED (true black)"
    ColorThemePreset.AYU_DARK -> "Ayu Dark"
    ColorThemePreset.AYU_LIGHT -> "Ayu Light"
    ColorThemePreset.AYU_MIRAGE -> "Ayu Mirage"
    ColorThemePreset.CATPPUCCIN_LATTE -> "Catppuccin Latte"
    ColorThemePreset.CATPPUCCIN_MOCHA -> "Catppuccin Mocha"
    ColorThemePreset.DRACULA -> "Dracula"
    ColorThemePreset.EVERFOREST_DARK -> "Everforest Dark"
    ColorThemePreset.EVERFOREST_LIGHT -> "Everforest Light"
    ColorThemePreset.GRUVBOX_DARK -> "Gruvbox Dark"
    ColorThemePreset.GRUVBOX_LIGHT -> "Gruvbox Light"
    ColorThemePreset.KANAGAWA_DRAGON -> "Kanagawa Dragon"
    ColorThemePreset.KANAGAWA_LOTUS -> "Kanagawa Lotus"
    ColorThemePreset.KANAGAWA_WAVE -> "Kanagawa Wave"
    ColorThemePreset.MODUS_OPERANDI -> "Modus Operandi"
    ColorThemePreset.MODUS_VIVENDI -> "Modus Vivendi"
    ColorThemePreset.MONOKAI -> "Monokai"
    ColorThemePreset.NORD -> "Nord"
    ColorThemePreset.ONE_DARK -> "One Dark"
    ColorThemePreset.ROSE_PINE -> "Rosé Pine"
    ColorThemePreset.ROSE_PINE_DAWN -> "Rosé Pine Dawn"
    ColorThemePreset.ROSE_PINE_MOON -> "Rosé Pine Moon"
    ColorThemePreset.SOLARIZED_DARK -> "Solarized Dark"
    ColorThemePreset.SOLARIZED_LIGHT -> "Solarized Light"
    ColorThemePreset.TOKYO_NIGHT -> "Tokyo Night"
    ColorThemePreset.ZENBURN -> "Zenburn"
}

private fun themePresetLabelRes(mode: ColorThemePreset): Int = when (mode) {
    ColorThemePreset.SYSTEM -> R.string.settings_theme_system
    ColorThemePreset.LIGHT -> R.string.settings_theme_light
    ColorThemePreset.DARK -> R.string.settings_theme_dark
    ColorThemePreset.AMOLED -> R.string.settings_theme_amoled
    ColorThemePreset.AYU_DARK -> R.string.settings_theme_ayu_dark
    ColorThemePreset.AYU_LIGHT -> R.string.settings_theme_ayu_light
    ColorThemePreset.AYU_MIRAGE -> R.string.settings_theme_ayu_mirage
    ColorThemePreset.CATPPUCCIN_LATTE -> R.string.settings_theme_catppuccin_latte
    ColorThemePreset.CATPPUCCIN_MOCHA -> R.string.settings_theme_catppuccin_mocha
    ColorThemePreset.DRACULA -> R.string.settings_theme_dracula
    ColorThemePreset.EVERFOREST_DARK -> R.string.settings_theme_everforest_dark
    ColorThemePreset.EVERFOREST_LIGHT -> R.string.settings_theme_everforest_light
    ColorThemePreset.GRUVBOX_DARK -> R.string.settings_theme_gruvbox_dark
    ColorThemePreset.GRUVBOX_LIGHT -> R.string.settings_theme_gruvbox_light
    ColorThemePreset.KANAGAWA_DRAGON -> R.string.settings_theme_kanagawa_dragon
    ColorThemePreset.KANAGAWA_LOTUS -> R.string.settings_theme_kanagawa_lotus
    ColorThemePreset.KANAGAWA_WAVE -> R.string.settings_theme_kanagawa_wave
    ColorThemePreset.MODUS_OPERANDI -> R.string.settings_theme_modus_operandi
    ColorThemePreset.MODUS_VIVENDI -> R.string.settings_theme_modus_vivendi
    ColorThemePreset.MONOKAI -> R.string.settings_theme_monokai
    ColorThemePreset.NORD -> R.string.settings_theme_nord
    ColorThemePreset.ONE_DARK -> R.string.settings_theme_one_dark
    ColorThemePreset.ROSE_PINE -> R.string.settings_theme_rose_pine
    ColorThemePreset.ROSE_PINE_DAWN -> R.string.settings_theme_rose_pine_dawn
    ColorThemePreset.ROSE_PINE_MOON -> R.string.settings_theme_rose_pine_moon
    ColorThemePreset.SOLARIZED_DARK -> R.string.settings_theme_solarized_dark
    ColorThemePreset.SOLARIZED_LIGHT -> R.string.settings_theme_solarized_light
    ColorThemePreset.TOKYO_NIGHT -> R.string.settings_theme_tokyo_night
    ColorThemePreset.ZENBURN -> R.string.settings_theme_zenburn
}

internal val LIGHT_THEME_PRESETS = ColorThemePreset.entries.filter { !it.isDark && it != ColorThemePreset.SYSTEM }
    .sortedBy(::themePresetLabelText)
internal val DARK_THEME_PRESETS = ColorThemePreset.entries
    .filter { it.isDark && it != ColorThemePreset.AMOLED }
    .sortedBy(::themePresetLabelText)

@Composable
private fun AvatarStyleGroup(current: AvatarStyle, onSelect: (AvatarStyle) -> Unit) {
    val options: List<Triple<AvatarStyle, Int, Int?>> = listOf(
        Triple(AvatarStyle.MONOGRAM, R.string.settings_avatar_monogram, null),
        Triple(AvatarStyle.INITIALS, R.string.settings_avatar_initials, null),
        Triple(
            AvatarStyle.IRC_SPRITE,
            R.string.settings_avatar_irc_sprite,
            R.string.settings_avatar_irc_sprite_desc,
        ),
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { (style, labelRes, subtitleRes) ->
            RadioRow(
                label = stringResource(labelRes),
                subtitle = subtitleRes?.let { stringResource(it) },
                selected = current == style,
                enabled = true,
                onClick = { onSelect(style) },
                modifier = Modifier.testTag("settings_avatar_style_${style.name.lowercase()}"),
            )
            if (style == AvatarStyle.IRC_SPRITE) IrcSpriteSampleStrip()
        }
    }
}

/** A static, data-free sample shows both person sprites and contextual channel marks. */
@Composable
private fun IrcSpriteSampleStrip() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 16.dp, bottom = 10.dp)
            .testTag("settings_avatar_sprite_preview"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IrcSpriteAvatar(name = "rustacean", size = 30.dp)
        IrcSpriteAvatar(name = "alice", size = 30.dp)
        IrcChannelBadge(name = "#guix", size = 30.dp)
        IrcChannelBadge(name = "#debian", size = 30.dp)
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
                modifier = Modifier.testTag("settings_density_${density.name.lowercase()}"),
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
                modifier = Modifier.testTag("settings_palette_${palette.name.lowercase()}"),
            )
        }
    }
}

@Preview
@Composable
private fun AppearanceSettingsPreview() {
    MotdTheme {
        AppearanceSettingsContent(
            settings = Settings(dynamicColor = true),
            appearance = io.github.trevarj.motd.data.prefs.AppearanceConfig(theme = ColorThemePreset.DARK),
            onBack = {}, onOpenNickColors = {}, onThemePreset = {}, onTrueBlack = {}, onDynamicColor = {},
            onLayoutDensity = {}, onAvatarStyle = {}, onNickColorsEnabled = {},
            onNickColorPalette = {}, onWallpaper = {}, onUiFontScale = {},
            onConversationFontScale = {},
        )
    }
}

@Preview(name = "Interface 80%", fontScale = 1f)
@Composable
private fun AppearanceSettingsMinTextPreview() {
    MotdTheme(uiFontScalePercent = 80) {
        AppearanceSettingsContent(
            settings = Settings(dynamicColor = true),
            appearance = io.github.trevarj.motd.data.prefs.AppearanceConfig(uiFontScalePercent = 80),
            onBack = {}, onOpenNickColors = {}, onThemePreset = {}, onTrueBlack = {}, onDynamicColor = {},
            onLayoutDensity = {}, onAvatarStyle = {}, onNickColorsEnabled = {},
            onNickColorPalette = {}, onWallpaper = {}, onUiFontScale = {},
            onConversationFontScale = {},
        )
    }
}

@Preview(name = "Interface 140% + large system font", fontScale = 1.5f)
@Composable
private fun AppearanceSettingsMaxTextPreview() {
    MotdTheme(uiFontScalePercent = 140) {
        AppearanceSettingsContent(
            settings = Settings(dynamicColor = true),
            appearance = io.github.trevarj.motd.data.prefs.AppearanceConfig(uiFontScalePercent = 140),
            onBack = {}, onOpenNickColors = {}, onThemePreset = {}, onTrueBlack = {}, onDynamicColor = {},
            onLayoutDensity = {}, onAvatarStyle = {}, onNickColorsEnabled = {},
            onNickColorPalette = {}, onWallpaper = {}, onUiFontScale = {},
            onConversationFontScale = {},
        )
    }
}
