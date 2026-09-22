package io.github.trevarj.motd.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.DEFAULT_FONT_SCALE_PERCENT
import io.github.trevarj.motd.data.prefs.FONT_SCALE_STEP_PERCENT
import io.github.trevarj.motd.data.prefs.FolderDisplayMode
import io.github.trevarj.motd.data.prefs.FontChoice
import io.github.trevarj.motd.data.prefs.LauncherIcon
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.MAX_FONT_SCALE_PERCENT
import io.github.trevarj.motd.data.prefs.MIN_FONT_SCALE_PERCENT
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.TimeFormat
import io.github.trevarj.motd.data.prefs.isDark
import io.github.trevarj.motd.data.prefs.systemPartner
import io.github.trevarj.motd.ui.chat.ChatWallpaperPicker
import io.github.trevarj.motd.ui.nav.SettingsTarget
import io.github.trevarj.motd.ui.theme.MotdMotion
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.fontFamily
import io.github.trevarj.motd.ui.theme.rememberAppFontFamily
import java.io.File
import kotlin.math.roundToInt

/** Appearance category: theme, dynamic color, layout density, avatar style, nick colors, wallpaper. */
@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit = {},
    onOpenNickColors: () -> Unit = {},
    target: SettingsTarget? = null,
    viewModel: AppearanceSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val importFailedMessage = stringResource(R.string.settings_font_custom_invalid)
    // Success is already visible in the picker row (it selects and shows the file name); only the
    // failure case needs transient scaffold feedback; success is visible in selected font row.
    LaunchedEffect(viewModel, importFailedMessage) {
        viewModel.customFontImportEvents.collect { event ->
            if (event == CustomFontImportEvent.FAILED) snackbarHostState.showSnackbar(importFailedMessage)
        }
    }
    // Re-read the on-disk font whenever the display name changes or the revision bumps: a
    // same-name re-import changes no persisted state, so the name alone would miss it.
    val fontRevision by viewModel.fontRevision.collectAsStateWithLifecycle()
    val customFontFile =
        remember(state.appearance.customFontName, fontRevision) {
            viewModel.customFontFile
        }
    AppearanceSettingsContent(
        settings = state.settings,
        appearance = state.appearance,
        customFontFile = customFontFile,
        onBack = onBack,
        onOpenNickColors = onOpenNickColors,
        onThemePreset = viewModel::setThemePreset,
        onTrueBlack = viewModel::setTrueBlack,
        onFollowSystem = viewModel::setFollowSystem,
        onDynamicColor = viewModel::setDynamicColor,
        onLayoutDensity = viewModel::setLayoutDensity,
        onFolderDisplayMode = viewModel::setFolderDisplayMode,
        onShowFolderChatsInAll = viewModel::setShowFolderChatsInAll,
        onAvatarStyle = viewModel::setAvatarStyle,
        onNickColorsEnabled = viewModel::setNickColorsEnabled,
        onNickColorPalette = viewModel::setNickColorPalette,
        onWallpaper = viewModel::setWallpaper,
        onUiFontScale = viewModel::setUiFontScale,
        onConversationFontScale = viewModel::setConversationFontScale,
        onFontChoice = viewModel::setFontChoice,
        onImportCustomFont = viewModel::importCustomFont,
        onShowTimestamps = viewModel::setShowTimestamps,
        onTimeFormat = viewModel::setTimeFormat,
        onCustomTimeFormatPattern = viewModel::setCustomTimeFormatPattern,
        onMessageSpacing = viewModel::setMessageSpacing,
        onBubbleCornerStyle = viewModel::setBubbleCornerStyle,
        onLauncherIcon = viewModel::setLauncherIcon,
        target = target,
        snackbarHostState = snackbarHostState,
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
    onFollowSystem: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onLayoutDensity: (LayoutDensity) -> Unit,
    onFolderDisplayMode: (FolderDisplayMode) -> Unit,
    onShowFolderChatsInAll: (Boolean) -> Unit = {},
    onAvatarStyle: (AvatarStyle) -> Unit,
    onNickColorsEnabled: (Boolean) -> Unit,
    onNickColorPalette: (NickColorPalette) -> Unit,
    onWallpaper: (io.github.trevarj.motd.data.prefs.WallpaperSelection) -> Unit,
    onUiFontScale: (Int) -> Unit,
    onConversationFontScale: (Int) -> Unit,
    onFontChoice: (FontChoice) -> Unit,
    onShowTimestamps: (Boolean) -> Unit,
    onTimeFormat: (TimeFormat) -> Unit,
    onCustomTimeFormatPattern: (String) -> Unit,
    onMessageSpacing: (io.github.trevarj.motd.data.prefs.MessageSpacing) -> Unit,
    onBubbleCornerStyle: (io.github.trevarj.motd.data.prefs.BubbleCornerStyle) -> Unit,
    onLauncherIcon: (LauncherIcon) -> Unit,
    customFontFile: File? = null,
    onImportCustomFont: (Uri) -> Unit = {},
    target: SettingsTarget? = null,
    snackbarHostState: SnackbarHostState? = null,
) {
    var showThemeSheet by rememberSaveable { mutableStateOf(false) }
    var showFontSheet by rememberSaveable { mutableStateOf(false) }
    var choiceSheet by rememberSaveable { mutableStateOf<AppearanceChoice?>(null) }
    val followSystemAvailable = appearance.theme.systemPartner != null
    val trueBlackAvailable =
        appearance.theme == ColorThemePreset.SYSTEM ||
            appearance.theme.isDark || (appearance.followSystem && followSystemAvailable)
    val dynamicColorAvailable = appearance.theme == ColorThemePreset.SYSTEM
    SettingsScaffold(
        title = stringResource(R.string.settings_appearance),
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    ) {
        SettingsGroup(title = stringResource(R.string.settings_theme_section)) {
            SettingsTarget(
                if (target == SettingsTarget.APPEARANCE) SettingsTarget.THEME.name else target?.name,
                SettingsTarget.THEME.name,
            ) { targetModifier ->
                SettingsNavigationRow(
                    icon = Icons.Outlined.Palette,
                    title = stringResource(R.string.settings_theme),
                    value = themePresetLabel(appearance.theme),
                    onClick = { showThemeSheet = true },
                    modifier = targetModifier.testTag("settings_theme_picker"),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.FOLLOW_SYSTEM.name) { targetModifier ->
                SwitchRow(
                    title = stringResource(R.string.settings_follow_system),
                    subtitle =
                        stringResource(
                            when {
                                appearance.theme == ColorThemePreset.SYSTEM -> R.string.settings_follow_system_system_desc
                                followSystemAvailable -> R.string.settings_follow_system_desc
                                else -> R.string.settings_follow_system_unavailable_desc
                            },
                        ),
                    checked = appearance.followSystem,
                    onCheckedChange = onFollowSystem,
                    switchTag = "settings_switch_follow_system",
                    enabled = followSystemAvailable,
                    modifier = targetModifier,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.TRUE_BLACK.name) { targetModifier ->
                SwitchRow(
                    title = stringResource(R.string.settings_true_black),
                    subtitle =
                        stringResource(
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
                    modifier = targetModifier,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.DYNAMIC_COLOR.name) { targetModifier ->
                SwitchRow(
                    title = stringResource(R.string.settings_dynamic_color),
                    subtitle =
                        stringResource(
                            if (dynamicColorAvailable) {
                                R.string.settings_dynamic_color_desc
                            } else {
                                R.string.settings_dynamic_color_unavailable
                            },
                        ),
                    checked = settings.dynamicColor && dynamicColorAvailable,
                    onCheckedChange = onDynamicColor,
                    switchTag = "settings_switch_dynamic_color",
                    enabled = dynamicColorAvailable,
                    modifier = targetModifier,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.NICK_COLORS.name) { targetModifier ->
                SwitchRow(
                    title = stringResource(R.string.settings_nick_colors),
                    subtitle = stringResource(R.string.settings_nick_colors_desc),
                    checked = settings.nickColorsEnabled,
                    onCheckedChange = onNickColorsEnabled,
                    switchTag = "settings_switch_nick_colors",
                    modifier = targetModifier,
                )
            }
            SettingsTarget(target?.name, SettingsTarget.NICK_PALETTE.name) { targetModifier ->
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_nick_palette),
                    value = nickPaletteLabel(settings.nickColorPalette),
                    summary =
                        if (settings.nickColorsEnabled) {
                            null
                        } else {
                            stringResource(R.string.settings_nick_palette_disabled)
                        },
                    enabled = settings.nickColorsEnabled,
                    modifier = targetModifier.testTag("settings_palette_picker"),
                    onClick = { choiceSheet = AppearanceChoice.PALETTE },
                )
            }
            SettingsTarget(target?.name, SettingsTarget.NICK_OVERRIDES.name) { targetModifier ->
                SettingsNavigationRow(
                    icon = Icons.Outlined.ColorLens,
                    title = stringResource(R.string.settings_nick_color_overrides),
                    value =
                        pluralStringResource(
                            R.plurals.settings_nick_count,
                            settings.nickColorOverrides.size,
                            settings.nickColorOverrides.size,
                        ),
                    modifier = targetModifier.testTag("settings_nick_color_overrides"),
                    onClick = onOpenNickColors,
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_layout_section)) {
            SettingsTarget(target?.name, SettingsTarget.APP_FONT.name) { targetModifier ->
                SettingsNavigationRow(
                    icon = Icons.Outlined.TextFields,
                    title = stringResource(R.string.settings_app_font),
                    value = fontChoiceLabel(appearance.fontChoice),
                    onClick = { showFontSheet = true },
                    modifier = targetModifier.testTag("settings_font_picker"),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.UI_FONT_SIZE.name) { targetModifier ->
                FontScaleSlider(
                    title = stringResource(R.string.settings_ui_font_size),
                    description = stringResource(R.string.settings_ui_font_size_desc),
                    value = appearance.uiFontScalePercent,
                    tag = "settings_ui_font_scale",
                    onValue = onUiFontScale,
                    modifier = targetModifier,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.CONVERSATION_FONT_SIZE.name) { targetModifier ->
                FontScaleSlider(
                    title = stringResource(R.string.settings_conversation_font_size),
                    description = stringResource(R.string.settings_conversation_font_size_desc),
                    value = appearance.conversationFontScalePercent,
                    tag = "settings_conversation_font_scale",
                    onValue = onConversationFontScale,
                    modifier = targetModifier,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.FOLDER_LAYOUT.name) { targetModifier ->
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_folder_layout),
                    value = folderDisplayModeLabel(settings.folderDisplayMode),
                    modifier = targetModifier.testTag("settings_folder_layout_picker"),
                    onClick = { choiceSheet = AppearanceChoice.FOLDER_LAYOUT },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.SHOW_FOLDER_CHATS_IN_ALL.name) { targetModifier ->
                SwitchRow(
                    title = stringResource(R.string.settings_show_folder_chats_in_all),
                    subtitle = stringResource(R.string.settings_show_folder_chats_in_all_desc),
                    checked = settings.showFolderChatsInAll,
                    onCheckedChange = onShowFolderChatsInAll,
                    switchTag = "settings_switch_show_folder_chats_in_all",
                    enabled = settings.folderDisplayMode == FolderDisplayMode.TABS,
                    disabledExplanation = stringResource(R.string.settings_show_folder_chats_in_all_disabled_explanation),
                    modifier = targetModifier,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.MESSAGE_STYLE.name) { targetModifier ->
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_density),
                    value = densityLabel(settings.layoutDensity),
                    modifier = targetModifier.testTag("settings_density_picker"),
                    onClick = { choiceSheet = AppearanceChoice.DENSITY },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.AVATAR_STYLE.name) { targetModifier ->
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_avatar_style),
                    value = avatarStyleLabel(settings.avatarStyle),
                    modifier = targetModifier.testTag("settings_avatar_style_picker"),
                    onClick = { choiceSheet = AppearanceChoice.AVATAR },
                )
            }
        }
        SettingsGroup(title = stringResource(R.string.settings_appearance_messages_section)) {
            SettingsTarget(target?.name, SettingsTarget.TIMESTAMPS.name) { targetModifier ->
                SwitchRow(
                    title = stringResource(R.string.settings_show_timestamps),
                    subtitle = stringResource(R.string.settings_show_timestamps_desc),
                    checked = appearance.showTimestamps,
                    onCheckedChange = onShowTimestamps,
                    switchTag = "settings_switch_show_timestamps",
                    modifier = targetModifier,
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.TIME_FORMAT.name) { targetModifier ->
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_time_format),
                    value = timeFormatLabel(appearance.timeFormat),
                    modifier = targetModifier.testTag("settings_time_format_picker"),
                    onClick = { choiceSheet = AppearanceChoice.TIME },
                )
            }
            if (appearance.timeFormat == TimeFormat.CUSTOM) {
                OutlinedTextField(
                    value = appearance.customTimeFormatPattern,
                    onValueChange = onCustomTimeFormatPattern,
                    label = { Text(stringResource(R.string.settings_time_format)) },
                    supportingText = { Text(stringResource(R.string.settings_time_format_custom_help)) },
                    placeholder = { Text(stringResource(R.string.settings_time_format_custom_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("settings_time_format_custom_pattern"),
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.MESSAGE_SPACING.name) { targetModifier ->
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_message_spacing),
                    value = messageSpacingLabel(appearance.messageSpacing),
                    modifier = targetModifier.testTag("settings_message_spacing_picker"),
                    onClick = { choiceSheet = AppearanceChoice.SPACING },
                )
            }
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsTarget(target?.name, SettingsTarget.BUBBLE_CORNERS.name) { targetModifier ->
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_bubble_corners),
                    value = bubbleCornerLabel(appearance.bubbleCornerStyle),
                    modifier = targetModifier.testTag("settings_bubble_corner_picker"),
                    onClick = { choiceSheet = AppearanceChoice.BUBBLES },
                )
            }
        }
        SettingsTarget(target?.name, SettingsTarget.WALLPAPER.name) { targetModifier ->
            SettingsGroup(title = stringResource(R.string.settings_wallpaper), modifier = targetModifier) {
                ChatWallpaperPicker(current = appearance.wallpaper, onApply = onWallpaper)
            }
        }
        SettingsTarget(target?.name, SettingsTarget.LAUNCHER_ICON.name) { targetModifier ->
            SettingsGroup(title = stringResource(R.string.settings_app_icon_section), modifier = targetModifier) {
                SettingsNavigationRow(
                    title = stringResource(R.string.settings_app_icon_section),
                    value = stringResource(launcherIconLabelRes(appearance.launcherIcon)),
                    modifier = Modifier.testTag("settings_app_icon_picker"),
                    onClick = { choiceSheet = AppearanceChoice.LAUNCHER },
                )
            }
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
    choiceSheet?.let { choice ->
        AppearanceChoiceSheet(
            choice = choice,
            settings = settings,
            appearance = appearance,
            onPalette = onNickColorPalette,
            onDensity = onLayoutDensity,
            onFolderDisplayMode = onFolderDisplayMode,
            onAvatar = onAvatarStyle,
            onTime = onTimeFormat,
            onSpacing = onMessageSpacing,
            onBubbles = onBubbleCornerStyle,
            onLauncher = onLauncherIcon,
            onDismiss = { choiceSheet = null },
        )
    }
    if (showFontSheet) {
        FontPickerSheet(
            current = appearance.fontChoice,
            customFontName = appearance.customFontName,
            customFontFile = customFontFile,
            onSelect = onFontChoice,
            onImportCustomFont = onImportCustomFont,
            onDismiss = { showFontSheet = false },
        )
    }
}

private enum class AppearanceChoice { PALETTE, FOLDER_LAYOUT, DENSITY, AVATAR, TIME, SPACING, BUBBLES, LAUNCHER }

@Composable
private fun AppearanceChoiceSheet(
    choice: AppearanceChoice,
    settings: Settings,
    appearance: io.github.trevarj.motd.data.prefs.AppearanceConfig,
    onPalette: (NickColorPalette) -> Unit,
    onDensity: (LayoutDensity) -> Unit,
    onFolderDisplayMode: (FolderDisplayMode) -> Unit,
    onAvatar: (AvatarStyle) -> Unit,
    onTime: (TimeFormat) -> Unit,
    onSpacing: (io.github.trevarj.motd.data.prefs.MessageSpacing) -> Unit,
    onBubbles: (io.github.trevarj.motd.data.prefs.BubbleCornerStyle) -> Unit,
    onLauncher: (LauncherIcon) -> Unit,
    onDismiss: () -> Unit,
) {
    when (choice) {
        AppearanceChoice.PALETTE -> {
            SingleChoiceSheet(
                title = stringResource(R.string.settings_nick_palette),
                selected = settings.nickColorPalette,
                options = NickColorPalette.entries.map { ChoiceOption(it, nickPaletteLabel(it), tag = "settings_palette_${it.name.lowercase()}") },
                onSelect = onPalette,
                onDismiss = onDismiss,
                tag = "settings_palette_sheet",
            )
        }

        AppearanceChoice.FOLDER_LAYOUT -> {
            SingleChoiceSheet(
                title = stringResource(R.string.settings_folder_layout),
                selected = settings.folderDisplayMode,
                options =
                    FolderDisplayMode.entries.map {
                        ChoiceOption(it, folderDisplayModeLabel(it), folderDisplayModeDescription(it), "settings_folder_layout_${it.name.lowercase()}")
                    },
                onSelect = onFolderDisplayMode,
                onDismiss = onDismiss,
                tag = "settings_folder_layout_sheet",
            )
        }

        AppearanceChoice.DENSITY -> {
            SingleChoiceSheet(
                title = stringResource(R.string.settings_density),
                selected = settings.layoutDensity,
                options = LayoutDensity.entries.map { ChoiceOption(it, densityLabel(it), densityDescription(it), "settings_density_${it.name.lowercase()}") },
                onSelect = onDensity,
                onDismiss = onDismiss,
                tag = "settings_density_sheet",
            )
        }

        AppearanceChoice.AVATAR -> {
            SingleChoiceSheet(
                title = stringResource(R.string.settings_avatar_style),
                selected = settings.avatarStyle,
                options =
                    AvatarStyle.entries.map {
                        ChoiceOption(it, avatarStyleLabel(it), avatarStyleDescription(it), "settings_avatar_style_${it.name.lowercase()}")
                    },
                onSelect = onAvatar,
                onDismiss = onDismiss,
                tag = "settings_avatar_style_sheet",
            )
        }

        AppearanceChoice.TIME -> {
            SingleChoiceSheet(
                title = stringResource(R.string.settings_time_format),
                selected = appearance.timeFormat,
                options = TimeFormat.entries.map { ChoiceOption(it, timeFormatLabel(it), tag = "settings_time_format_${it.name.lowercase()}") },
                onSelect = onTime,
                onDismiss = onDismiss,
                tag = "settings_time_format_sheet",
            )
        }

        AppearanceChoice.SPACING -> {
            SingleChoiceSheet(
                title = stringResource(R.string.settings_message_spacing),
                selected = appearance.messageSpacing,
                options =
                    io.github.trevarj.motd.data.prefs.MessageSpacing.entries
                        .map { ChoiceOption(it, messageSpacingLabel(it), tag = "settings_message_spacing_${it.name.lowercase()}") },
                onSelect = onSpacing,
                onDismiss = onDismiss,
                tag = "settings_message_spacing_sheet",
            )
        }

        AppearanceChoice.BUBBLES -> {
            SingleChoiceSheet(
                title = stringResource(R.string.settings_bubble_corners),
                selected = appearance.bubbleCornerStyle,
                options =
                    io.github.trevarj.motd.data.prefs.BubbleCornerStyle.entries
                        .map { ChoiceOption(it, bubbleCornerLabel(it), tag = "settings_bubble_corner_${it.name.lowercase()}") },
                onSelect = onBubbles,
                onDismiss = onDismiss,
                tag = "settings_bubble_corner_sheet",
            )
        }

        AppearanceChoice.LAUNCHER -> {
            SingleChoiceSheet(
                title = stringResource(R.string.settings_app_icon_section),
                selected = appearance.launcherIcon,
                options = LauncherIcon.entries.map { ChoiceOption(it, stringResource(launcherIconLabelRes(it)), tag = "settings_app_icon_${it.name.lowercase()}") },
                onSelect = onLauncher,
                onDismiss = onDismiss,
                tag = "settings_app_icon_sheet",
            )
        }
    }
}

@Composable
private fun nickPaletteLabel(value: NickColorPalette): String =
    stringResource(
        when (value) {
            NickColorPalette.THEME -> R.string.settings_palette_theme
            NickColorPalette.CLASSIC -> R.string.settings_palette_classic
            NickColorPalette.VIVID -> R.string.settings_palette_vivid
        },
    )

@Composable
private fun folderDisplayModeLabel(value: FolderDisplayMode): String =
    stringResource(
        when (value) {
            FolderDisplayMode.INLINE -> R.string.settings_folder_layout_inline
            FolderDisplayMode.TABS -> R.string.settings_folder_layout_tabs
        },
    )

@Composable
private fun folderDisplayModeDescription(value: FolderDisplayMode): String =
    stringResource(
        when (value) {
            FolderDisplayMode.INLINE -> R.string.settings_folder_layout_inline_desc
            FolderDisplayMode.TABS -> R.string.settings_folder_layout_tabs_desc
        },
    )

@Composable
private fun densityLabel(value: LayoutDensity): String =
    stringResource(
        when (value) {
            LayoutDensity.COMPACT -> R.string.settings_density_compact
            LayoutDensity.COMFORTABLE -> R.string.settings_density_comfortable
            LayoutDensity.TWO_LINE -> R.string.settings_density_two_line
        },
    )

@Composable
private fun densityDescription(value: LayoutDensity): String =
    stringResource(
        when (value) {
            LayoutDensity.COMPACT -> R.string.settings_density_compact_desc
            LayoutDensity.COMFORTABLE -> R.string.settings_density_comfortable_desc
            LayoutDensity.TWO_LINE -> R.string.settings_density_two_line_desc
        },
    )

@Composable
private fun avatarStyleLabel(value: AvatarStyle): String =
    stringResource(
        when (value) {
            AvatarStyle.MONOGRAM -> R.string.settings_avatar_monogram
            AvatarStyle.INITIALS -> R.string.settings_avatar_initials
            AvatarStyle.IRC_SPRITE -> R.string.settings_avatar_irc_sprite
            AvatarStyle.NONE -> R.string.settings_avatar_none
        },
    )

@Composable
private fun avatarStyleDescription(value: AvatarStyle): String? =
    when (value) {
        AvatarStyle.IRC_SPRITE -> stringResource(R.string.settings_avatar_irc_sprite_desc)
        AvatarStyle.NONE -> stringResource(R.string.settings_avatar_none_desc)
        else -> null
    }

@Composable
private fun timeFormatLabel(value: TimeFormat): String =
    stringResource(
        when (value) {
            TimeFormat.AUTO -> R.string.settings_time_format_auto
            TimeFormat.H12 -> R.string.settings_time_format_h12
            TimeFormat.H24 -> R.string.settings_time_format_h24
            TimeFormat.CUSTOM -> R.string.settings_time_format_custom
        },
    )

@Composable
private fun messageSpacingLabel(value: io.github.trevarj.motd.data.prefs.MessageSpacing): String =
    stringResource(
        when (value) {
            io.github.trevarj.motd.data.prefs.MessageSpacing.COMPACT -> R.string.settings_message_spacing_compact
            io.github.trevarj.motd.data.prefs.MessageSpacing.DEFAULT -> R.string.settings_message_spacing_default
            io.github.trevarj.motd.data.prefs.MessageSpacing.RELAXED -> R.string.settings_message_spacing_relaxed
        },
    )

@Composable
private fun bubbleCornerLabel(value: io.github.trevarj.motd.data.prefs.BubbleCornerStyle): String =
    stringResource(
        when (value) {
            io.github.trevarj.motd.data.prefs.BubbleCornerStyle.ROUNDED -> R.string.settings_bubble_corner_rounded
            io.github.trevarj.motd.data.prefs.BubbleCornerStyle.SUBTLE -> R.string.settings_bubble_corner_subtle
            io.github.trevarj.motd.data.prefs.BubbleCornerStyle.SQUARE -> R.string.settings_bubble_corner_square
        },
    )

@Composable
private fun FontScaleSlider(
    title: String,
    description: String,
    value: Int,
    tag: String,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pending by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val displayed = pending.toInt()
    val percent = stringResource(R.string.settings_font_size_percent, displayed)
    Column(modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
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
            modifier =
                Modifier
                    .testTag(tag)
                    .semantics {
                        contentDescription = title
                        stateDescription = percent
                    },
        )
        // The threshold flips repeatedly while the slider is dragged; ease the reset button's row
        // in and out so the content below doesn't jump under the user's finger.
        AnimatedVisibility(
            visible = displayed != DEFAULT_FONT_SCALE_PERCENT,
            enter = fadeIn(MotdMotion.microFadeIn) + expandVertically(animationSpec = MotdMotion.contentSize),
            exit = fadeOut(MotdMotion.microFadeOut) + shrinkVertically(animationSpec = MotdMotion.contentSize),
            modifier = Modifier.align(androidx.compose.ui.Alignment.End),
        ) {
            TextButton(
                onClick = {
                    pending = DEFAULT_FONT_SCALE_PERCENT.toFloat()
                    onValue(DEFAULT_FONT_SCALE_PERCENT)
                },
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

    fun filtered(items: List<ColorThemePreset>) =
        items.filter {
            themePresetLabelText(it).lowercase().contains(normalized)
        }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_theme_sheet")) {
        SheetSystemBars()
        LazyColumn(
            Modifier
                .testTag("settings_theme_list")
                .selectableGroup()
                .heightIn(max = 680.dp)
                .padding(bottom = 24.dp),
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
            val groups =
                listOf(
                    R.string.settings_theme_system_group to filtered(listOf(ColorThemePreset.SYSTEM)),
                    R.string.settings_theme_light_group to filtered(LIGHT_THEME_PRESETS),
                    R.string.settings_theme_dark_group to filtered(DARK_THEME_PRESETS),
                )
            groups.forEach { (title, modes) ->
                if (modes.isNotEmpty()) {
                    item { SubLabel(stringResource(title)) }
                    items(modes.size) { index ->
                        val mode = modes[index]
                        ThemeRadioRow(
                            mode,
                            current == mode,
                            trueBlack,
                            dynamicColor,
                            onSelect,
                        )
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
                    shape = MotdShapes.tag,
                    border = BorderStroke(1.dp, scheme.outline),
                    modifier =
                        Modifier
                            .width(100.dp)
                            .height(42.dp)
                            .testTag("settings_theme_preview_${mode.name.lowercase()}"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("Aa", color = scheme.onBackground, style = MaterialTheme.typography.labelSmall)
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(0.72f)
                                    .height(7.dp)
                                    .background(scheme.surfaceContainerHigh, MotdShapes.pill),
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth(0.86f)
                                    .height(7.dp)
                                    .background(scheme.primaryContainer, MotdShapes.pill),
                            )
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .background(scheme.secondaryContainer, MotdShapes.pill),
                            )
                        }
                        Box(Modifier.width(5.dp).height(24.dp).background(scheme.tertiary, MotdShapes.pill))
                    }
                }
            }
        },
    )
}

/** Mime types accepted by the custom-font document picker; broad because OEM providers vary. */
private val CUSTOM_FONT_MIME_TYPES =
    arrayOf(
        "font/ttf",
        "font/otf",
        "font/*",
        "application/x-font-ttf",
        "application/octet-stream",
    )

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun FontPickerSheet(
    current: FontChoice,
    customFontName: String,
    customFontFile: File?,
    onSelect: (FontChoice) -> Unit,
    onImportCustomFont: (Uri) -> Unit,
    onDismiss: () -> Unit,
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(onImportCustomFont)
        }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_font_sheet")) {
        SheetSystemBars()
        Column(Modifier.selectableGroup().padding(bottom = 24.dp)) {
            Text(stringResource(R.string.settings_app_font), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            FontChoice.entries.forEach { choice ->
                if (choice == FontChoice.CUSTOM) {
                    CustomFontRow(
                        selected = current == choice,
                        customFontName = customFontName,
                        customFontFile = customFontFile,
                        onClick = {
                            if (customFontName.isEmpty()) {
                                launcher.launch(CUSTOM_FONT_MIME_TYPES)
                            } else {
                                onSelect(choice)
                            }
                        },
                        onChange = { launcher.launch(CUSTOM_FONT_MIME_TYPES) },
                    )
                } else {
                    RadioRow(
                        label = fontChoiceLabel(choice),
                        selected = current == choice,
                        enabled = true,
                        onClick = { onSelect(choice) },
                        modifier = Modifier.testTag("settings_font_${choice.name.lowercase()}"),
                        trailing = {
                            Text("Aa 0O1lI", fontFamily = choice.fontFamily())
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomFontRow(
    selected: Boolean,
    customFontName: String,
    customFontFile: File?,
    onClick: () -> Unit,
    onChange: () -> Unit,
) {
    val imported = customFontName.isNotEmpty()
    val previewFamily = rememberAppFontFamily(FontChoice.CUSTOM, customFontFile)
    RadioRow(
        label = stringResource(R.string.settings_font_custom),
        subtitle = if (imported) customFontName else stringResource(R.string.settings_font_custom_none),
        selected = selected,
        enabled = true,
        onClick = onClick,
        modifier = Modifier.testTag("settings_font_custom"),
        trailing = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Aa 0O1lI", fontFamily = previewFamily)
                if (imported) {
                    TextButton(onClick = onChange) {
                        Text(stringResource(R.string.settings_font_custom_change))
                    }
                }
            }
        },
    )
}

@Composable
private fun fontChoiceLabel(choice: FontChoice): String =
    stringResource(
        when (choice) {
            FontChoice.SYSTEM -> R.string.settings_font_system
            FontChoice.SANS -> R.string.settings_font_sans
            FontChoice.SERIF -> R.string.settings_font_serif
            FontChoice.MONOSPACE -> R.string.settings_font_mono
            FontChoice.JETBRAINS_MONO -> R.string.settings_font_jetbrains_mono
            FontChoice.CUSTOM -> R.string.settings_font_custom
        },
    )

@Composable
internal fun themePresetLabel(mode: ColorThemePreset): String = stringResource(themePresetLabelRes(mode))

internal fun themePresetLabelText(mode: ColorThemePreset): String =
    when (mode) {
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
        ColorThemePreset.MODUS_OPERANDI_TINTED -> "Modus Operandi Tinted"
        ColorThemePreset.MODUS_VIVENDI_TINTED -> "Modus Vivendi Tinted"
        ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA -> "Modus Operandi Deuteranopia"
        ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA -> "Modus Vivendi Deuteranopia"
        ColorThemePreset.MODUS_OPERANDI_TRITANOPIA -> "Modus Operandi Tritanopia"
        ColorThemePreset.MODUS_VIVENDI_TRITANOPIA -> "Modus Vivendi Tritanopia"
        ColorThemePreset.MONOKAI -> "Monokai"
        ColorThemePreset.NORD -> "Nord"
        ColorThemePreset.NORD_LIGHT -> "Nord Light"
        ColorThemePreset.ONE_DARK -> "One Dark"
        ColorThemePreset.ROSE_PINE -> "Rosé Pine"
        ColorThemePreset.ROSE_PINE_DAWN -> "Rosé Pine Dawn"
        ColorThemePreset.ROSE_PINE_MOON -> "Rosé Pine Moon"
        ColorThemePreset.SOLARIZED_DARK -> "Solarized Dark"
        ColorThemePreset.SOLARIZED_LIGHT -> "Solarized Light"
        ColorThemePreset.TOKYO_NIGHT -> "Tokyo Night"
        ColorThemePreset.ZENBURN -> "Zenburn"
    }

private fun themePresetLabelRes(mode: ColorThemePreset): Int =
    when (mode) {
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
        ColorThemePreset.MODUS_OPERANDI_TINTED -> R.string.settings_theme_modus_operandi_tinted
        ColorThemePreset.MODUS_VIVENDI_TINTED -> R.string.settings_theme_modus_vivendi_tinted
        ColorThemePreset.MODUS_OPERANDI_DEUTERANOPIA -> R.string.settings_theme_modus_operandi_deuteranopia
        ColorThemePreset.MODUS_VIVENDI_DEUTERANOPIA -> R.string.settings_theme_modus_vivendi_deuteranopia
        ColorThemePreset.MODUS_OPERANDI_TRITANOPIA -> R.string.settings_theme_modus_operandi_tritanopia
        ColorThemePreset.MODUS_VIVENDI_TRITANOPIA -> R.string.settings_theme_modus_vivendi_tritanopia
        ColorThemePreset.MONOKAI -> R.string.settings_theme_monokai
        ColorThemePreset.NORD -> R.string.settings_theme_nord
        ColorThemePreset.NORD_LIGHT -> R.string.settings_theme_nord_light
        ColorThemePreset.ONE_DARK -> R.string.settings_theme_one_dark
        ColorThemePreset.ROSE_PINE -> R.string.settings_theme_rose_pine
        ColorThemePreset.ROSE_PINE_DAWN -> R.string.settings_theme_rose_pine_dawn
        ColorThemePreset.ROSE_PINE_MOON -> R.string.settings_theme_rose_pine_moon
        ColorThemePreset.SOLARIZED_DARK -> R.string.settings_theme_solarized_dark
        ColorThemePreset.SOLARIZED_LIGHT -> R.string.settings_theme_solarized_light
        ColorThemePreset.TOKYO_NIGHT -> R.string.settings_theme_tokyo_night
        ColorThemePreset.ZENBURN -> R.string.settings_theme_zenburn
    }

internal val LIGHT_THEME_PRESETS =
    ColorThemePreset.entries
        .filter { !it.isDark && it != ColorThemePreset.SYSTEM }
        .sortedBy(::themePresetLabelText)
internal val DARK_THEME_PRESETS =
    ColorThemePreset.entries
        .filter { it.isDark && it != ColorThemePreset.AMOLED }
        .sortedBy(::themePresetLabelText)

private fun launcherIconLabelRes(icon: LauncherIcon): Int =
    when (icon) {
        LauncherIcon.DEFAULT -> R.string.settings_app_icon_default
        LauncherIcon.MONO -> R.string.settings_app_icon_mono
        LauncherIcon.TERMINAL -> R.string.settings_app_icon_terminal
        LauncherIcon.GRUVBOX -> R.string.settings_app_icon_gruvbox
        LauncherIcon.CATPPUCCIN -> R.string.settings_app_icon_catppuccin
        LauncherIcon.NORD -> R.string.settings_app_icon_nord
        LauncherIcon.LIGHT -> R.string.settings_app_icon_light
    }

@Preview
@Composable
private fun AppearanceSettingsPreview() {
    MotdTheme {
        AppearanceSettingsContent(
            settings = Settings(dynamicColor = true),
            appearance =
                io.github.trevarj.motd.data.prefs
                    .AppearanceConfig(theme = ColorThemePreset.DARK),
            onBack = {},
            onOpenNickColors = {},
            onThemePreset = {},
            onTrueBlack = {},
            onFollowSystem = {},
            onDynamicColor = {},
            onLayoutDensity = {},
            onFolderDisplayMode = {},
            onAvatarStyle = {},
            onNickColorsEnabled = {},
            onNickColorPalette = {},
            onWallpaper = {},
            onUiFontScale = {},
            onConversationFontScale = {},
            onFontChoice = {},
            onShowTimestamps = {},
            onTimeFormat = {},
            onCustomTimeFormatPattern = {},
            onMessageSpacing = {},
            onBubbleCornerStyle = {},
            onLauncherIcon = {},
        )
    }
}

@Preview(name = "Interface 80%", fontScale = 1f)
@Composable
private fun AppearanceSettingsMinTextPreview() {
    MotdTheme(uiFontScalePercent = 80) {
        AppearanceSettingsContent(
            settings = Settings(dynamicColor = true),
            appearance =
                io.github.trevarj.motd.data.prefs
                    .AppearanceConfig(uiFontScalePercent = 80),
            onBack = {},
            onOpenNickColors = {},
            onThemePreset = {},
            onTrueBlack = {},
            onFollowSystem = {},
            onDynamicColor = {},
            onLayoutDensity = {},
            onFolderDisplayMode = {},
            onAvatarStyle = {},
            onNickColorsEnabled = {},
            onNickColorPalette = {},
            onWallpaper = {},
            onUiFontScale = {},
            onConversationFontScale = {},
            onFontChoice = {},
            onShowTimestamps = {},
            onTimeFormat = {},
            onCustomTimeFormatPattern = {},
            onMessageSpacing = {},
            onBubbleCornerStyle = {},
            onLauncherIcon = {},
        )
    }
}

@Preview(name = "Interface 140% + large system font", fontScale = 1.5f)
@Composable
private fun AppearanceSettingsMaxTextPreview() {
    MotdTheme(uiFontScalePercent = 140) {
        AppearanceSettingsContent(
            settings = Settings(dynamicColor = true),
            appearance =
                io.github.trevarj.motd.data.prefs
                    .AppearanceConfig(uiFontScalePercent = 140),
            onBack = {},
            onOpenNickColors = {},
            onThemePreset = {},
            onTrueBlack = {},
            onFollowSystem = {},
            onDynamicColor = {},
            onLayoutDensity = {},
            onFolderDisplayMode = {},
            onAvatarStyle = {},
            onNickColorsEnabled = {},
            onNickColorPalette = {},
            onWallpaper = {},
            onUiFontScale = {},
            onConversationFontScale = {},
            onFontChoice = {},
            onShowTimestamps = {},
            onTimeFormat = {},
            onCustomTimeFormatPattern = {},
            onMessageSpacing = {},
            onBubbleCornerStyle = {},
            onLauncherIcon = {},
        )
    }
}
