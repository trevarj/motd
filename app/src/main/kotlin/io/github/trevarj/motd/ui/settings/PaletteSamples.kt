package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.ColorThemePreset
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PaletteColors
import io.github.trevarj.motd.data.prefs.PaletteDocument
import io.github.trevarj.motd.data.prefs.PaletteMode
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.chatlist.SettingSampleChatRow
import io.github.trevarj.motd.ui.chatlist.settingSampleChats
import io.github.trevarj.motd.ui.components.ChatLayoutPreview
import io.github.trevarj.motd.ui.components.LocalAutomaticRemoteMedia
import io.github.trevarj.motd.ui.components.LocalRemoteAvatars
import io.github.trevarj.motd.ui.components.RemoteAvatarState
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.TimestampConfig

@Composable
internal fun PaletteSamples(
    palette: PaletteDocument,
    mode: PaletteMode,
    settings: Settings,
    appearance: AppearanceConfig,
    modifier: Modifier = Modifier,
    layout: LayoutDensity = settings.layoutDensity,
) {
    var showTimestamps by rememberSaveable(appearance.showTimestamps) { mutableStateOf(appearance.showTimestamps) }
    val sampleText = stringResource(R.string.settings_chat_preview_message)
    var draft by rememberSaveable { mutableStateOf(sampleText) }
    val avatars = remember { RemoteAvatarState() }
    MotdTheme(
        themePreset = ColorThemePreset.SYSTEM,
        customPalette = palette,
        paletteMode = mode,
        dynamicColor = false,
        trueBlack = appearance.trueBlack,
        useThemeTextColors = appearance.useThemeTextColors,
        layoutDensity = layout,
        compactNickBrackets = settings.compactNickBrackets,
        nickColorsEnabled = settings.nickColorsEnabled,
        nickMentionStyle = settings.nickMentionStyle,
        nickColorPalette = settings.nickColorPalette,
        nickColorOverrides = settings.nickColorOverrides,
        avatarStyle = settings.avatarStyle,
        uiFontScalePercent = appearance.uiFontScalePercent,
        fontChoice = appearance.fontChoice,
        timestampConfig = TimestampConfig(showTimestamps, appearance.timeFormat, appearance.customTimeFormatPattern),
        messageSpacing = appearance.messageSpacing,
        bubbleCornerStyle = appearance.bubbleCornerStyle,
        inputCornerStyle = appearance.inputCornerStyle,
        avatarShape = appearance.avatarShape,
        chatCardCornerStyle = appearance.chatCardCornerStyle,
        mediaCornerStyle = appearance.mediaCornerStyle,
        uiIconSet = appearance.uiIconSet,
        chatShadowsEnabled = appearance.chatShadowsEnabled,
        nicknameBackgroundsEnabled = appearance.nicknameBackgroundsEnabled,
        messageBackgroundOpacityPercent = appearance.messageBackgroundOpacityPercent,
        messageBackgroundBlur = appearance.messageBackgroundBlur,
        surfaceEffects = appearance.surfaceEffects,
        sentBubbleColor = appearance.sentBubbleColor,
        receivedBubbleColor = appearance.receivedBubbleColor,
        syncSystemBars = false,
    ) {
        CompositionLocalProvider(LocalAutomaticRemoteMedia provides false, LocalRemoteAvatars provides avatars) {
            Surface(
                modifier = modifier.fillMaxWidth().testTag("palette_samples"),
                color = MaterialTheme.colorScheme.background,
                shape = MotdShapes.card,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChatLayoutPreview(
                        density = layout,
                        messageSpacing = appearance.messageSpacing,
                        bubbleCorners = appearance.bubbleCornerStyle,
                        wallpaper = appearance.wallpaper,
                        conversationFontScalePercent = appearance.conversationFontScalePercent,
                        tagPrefix = "palette_sample_chat",
                    )
                    SettingSampleChatRow(settingSampleChats.first(), Modifier.testTag("palette_sample_chat_list"))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text(stringResource(R.string.settings_preview)) },
                        modifier = Modifier.fillMaxWidth().testTag("palette_sample_input"),
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = showTimestamps,
                                role = Role.Switch,
                                onValueChange = { showTimestamps = it },
                            ).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.settings_show_timestamps), Modifier.weight(1f))
                        Switch(checked = showTimestamps, onCheckedChange = null)
                    }
                }
            }
        }
    }
}

@Composable
internal fun PaletteSwatches(
    colors: PaletteColors,
    modifier: Modifier = Modifier,
) {
    val roles =
        remember(colors) {
            listOf(
                "primary" to colors.primary,
                "secondary" to colors.secondary,
                "tertiary" to colors.tertiary,
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceContainer" to colors.surfaceContainer,
                "onBackground" to colors.onBackground,
                "onSurface" to colors.onSurface,
                "onSurfaceVariant" to colors.onSurfaceVariant,
                "outlineVariant" to colors.outlineVariant,
            )
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            roles.forEach { (role, hex) ->
                Surface(
                    modifier = Modifier.weight(1f).height(20.dp).semantics { contentDescription = "$role $hex" },
                    color = Color(hex.toColorInt()),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {}
            }
        }
        if (colors.nicknameColors.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                colors.nicknameColors.forEachIndexed { index, hex ->
                    Surface(
                        modifier = Modifier.weight(1f).height(16.dp).semantics { contentDescription = "nicknameColors ${index + 1} $hex" },
                        color = Color(hex.toColorInt()),
                        shape = CircleShape,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {}
                }
            }
        }
    }
}
