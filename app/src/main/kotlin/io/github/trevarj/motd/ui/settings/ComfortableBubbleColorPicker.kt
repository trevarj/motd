package io.github.trevarj.motd.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.prefs.AppearanceConfig
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColor
import io.github.trevarj.motd.data.prefs.ComfortableBubbleColorRole
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.ui.chat.ColorComponentSlider
import io.github.trevarj.motd.ui.chat.ColorWheel
import io.github.trevarj.motd.ui.chat.parseColorHex
import io.github.trevarj.motd.ui.chat.wallpaperColorHex
import io.github.trevarj.motd.ui.components.ChatLayoutPreview
import io.github.trevarj.motd.ui.components.Composer
import io.github.trevarj.motd.ui.components.messageBubbleRoleColors
import io.github.trevarj.motd.ui.theme.ConversationTypography
import io.github.trevarj.motd.ui.theme.LocalMotdSemanticColors
import io.github.trevarj.motd.ui.theme.MotdShapes
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.resolveComfortableBubbleColor
import java.io.File
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor
import io.github.trevarj.motd.ui.components.EffectModalBottomSheet as ModalBottomSheet

private val bubbleColorRoles =
    listOf(
        ComfortableBubbleColorRole.DEFAULT,
        ComfortableBubbleColorRole.SURFACE_CONTAINER,
        ComfortableBubbleColorRole.SURFACE_CONTAINER_HIGH,
        ComfortableBubbleColorRole.PRIMARY,
        ComfortableBubbleColorRole.SECONDARY,
        ComfortableBubbleColorRole.TERTIARY,
        ComfortableBubbleColorRole.PRIMARY_CONTAINER,
        ComfortableBubbleColorRole.SECONDARY_CONTAINER,
        ComfortableBubbleColorRole.TERTIARY_CONTAINER,
    )

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun ComfortableBubbleColorPickerSheet(
    settings: Settings,
    appearance: AppearanceConfig,
    customFontFile: File?,
    onSentColor: (ComfortableBubbleColor) -> Unit,
    onReceivedColor: (ComfortableBubbleColor) -> Unit,
    onDismiss: () -> Unit,
) {
    var sentRole by rememberSaveable { mutableStateOf(appearance.sentBubbleColor.role.name) }
    var sentCustom by rememberSaveable { mutableStateOf(appearance.sentBubbleColor.customColor) }
    var receivedRole by rememberSaveable { mutableStateOf(appearance.receivedBubbleColor.role.name) }
    var receivedCustom by rememberSaveable { mutableStateOf(appearance.receivedBubbleColor.customColor) }
    var editSent by rememberSaveable { mutableStateOf(true) }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    val sent = ComfortableBubbleColor(ComfortableBubbleColorRole.valueOf(sentRole), sentCustom)
    val received = ComfortableBubbleColor(ComfortableBubbleColorRole.valueOf(receivedRole), receivedCustom)
    val selected = if (editSent) sent else received

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.testTag("settings_bubble_color_sheet")) {
        SheetSystemBars()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_bubble_colors), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.settings_bubble_colors_desc), style = MaterialTheme.typography.bodyMedium)
            AppearanceBubbleColorOptions(settings, appearance) {
                Row(
                    modifier = Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BubbleColorTarget(
                        label = stringResource(R.string.settings_bubble_color_sent),
                        color = bubbleColorSample(sent, isSent = true),
                        selected = editSent,
                        tag = "settings_bubble_color_sent",
                        modifier = Modifier.weight(1f),
                        onClick = { editSent = true },
                    )
                    BubbleColorTarget(
                        label = stringResource(R.string.settings_bubble_color_received),
                        color = bubbleColorSample(received, isSent = false),
                        selected = !editSent,
                        tag = "settings_bubble_color_received",
                        modifier = Modifier.weight(1f),
                        onClick = { editSent = false },
                    )
                }
            }
            AppearanceBubblePreview(settings, appearance.copy(sentBubbleColor = sent, receivedBubbleColor = received), customFontFile)
            AppearanceBubbleColorOptions(settings, appearance) {
                bubbleColorRoles.forEach { role ->
                    BubbleColorOption(role, selected.role == role, editSent) {
                        val next = ComfortableBubbleColor(role)
                        if (editSent) {
                            sentRole = next.role.name
                            sentCustom = null
                            onSentColor(next)
                        } else {
                            receivedRole = next.role.name
                            receivedCustom = null
                            onReceivedColor(next)
                        }
                    }
                }
                BubbleColorOption(
                    ComfortableBubbleColorRole.CUSTOM,
                    selected.role == ComfortableBubbleColorRole.CUSTOM,
                    editSent,
                    selected.customColor,
                ) { showCustom = true }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings_bubble_color_done")) {
                Text(stringResource(R.string.settings_wallpaper_done))
            }
        }
    }
    if (showCustom) {
        ComfortableBubbleCustomColorDialog(
            initial = selected.customColor ?: MaterialTheme.colorScheme.primary.toArgb(),
            onApply = { color ->
                val next = ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, color)
                if (editSent) {
                    sentRole = next.role.name
                    sentCustom = next.customColor
                    onSentColor(next)
                } else {
                    receivedRole = next.role.name
                    receivedCustom = next.customColor
                    onReceivedColor(next)
                }
                showCustom = false
            },
            previewContent = { draft ->
                val next = ComfortableBubbleColor(ComfortableBubbleColorRole.CUSTOM, draft)
                AppearanceBubblePreview(
                    settings,
                    if (editSent) {
                        appearance.copy(sentBubbleColor = next, receivedBubbleColor = received)
                    } else {
                        appearance.copy(sentBubbleColor = sent, receivedBubbleColor = next)
                    },
                    customFontFile,
                )
            },
            onDismiss = { showCustom = false },
        )
    }
}

@Composable
private fun AppearanceBubbleColorOptions(
    settings: Settings,
    appearance: AppearanceConfig,
    content: @Composable () -> Unit,
) {
    MotdTheme(
        themePreset = appearance.theme,
        trueBlack = appearance.trueBlack,
        dynamicColor = settings.dynamicColor,
        followSystem = appearance.followSystem,
        useThemeTextColors = appearance.useThemeTextColors,
        syncSystemBars = false,
    ) { content() }
}

@Composable
private fun AppearanceBubblePreview(
    settings: Settings,
    appearance: AppearanceConfig,
    customFontFile: File?,
) {
    MotdTheme(
        themePreset = appearance.theme,
        trueBlack = appearance.trueBlack,
        dynamicColor = settings.dynamicColor,
        followSystem = appearance.followSystem,
        useThemeTextColors = appearance.useThemeTextColors,
        layoutDensity = LayoutDensity.COMFORTABLE,
        nickColorsEnabled = settings.nickColorsEnabled,
        nickColorPalette = settings.nickColorPalette,
        nickColorOverrides = settings.nickColorOverrides,
        avatarStyle = settings.avatarStyle,
        uiFontScalePercent = appearance.uiFontScalePercent,
        fontChoice = appearance.fontChoice,
        customFontFile = customFontFile,
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
        ChatLayoutPreview(
            density = LayoutDensity.COMFORTABLE,
            messageSpacing = appearance.messageSpacing,
            bubbleCorners = appearance.bubbleCornerStyle,
            wallpaper = appearance.wallpaper,
            conversationFontScalePercent = appearance.conversationFontScalePercent,
            tagPrefix = "settings_bubble_color_preview",
        )
        ConversationTypography(appearance.conversationFontScalePercent) {
            Composer(
                value = TextFieldValue(),
                onValueChange = {},
                onSend = {},
                enabled = true,
                sendEnabled = false,
                placeholder = stringResource(R.string.settings_bubble_color_composer_preview),
                composerStyle = appearance.composerStyle,
                modifier = Modifier.testTag("settings_bubble_color_composer_preview"),
            )
        }
    }
}

@Composable
private fun BubbleColorOption(
    role: ComfortableBubbleColorRole,
    selected: Boolean,
    isSent: Boolean,
    customColor: Int? = null,
    onClick: () -> Unit,
) {
    val fill = bubbleColorSample(ComfortableBubbleColor(role, customColor), isSent)
    val ink = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("settings_bubble_color_" + role.name.lowercase())
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors =
                androidx.compose.material3.RadioButtonDefaults
                    .colors(selectedColor = ink),
        )
        BubbleColorSwatch(fill, "settings_bubble_color_${role.name.lowercase()}_swatch")
        Column(Modifier.weight(1f)) {
            Text(stringResource(bubbleColorRoleLabel(role, isSent)), color = ink)
            if (role == ComfortableBubbleColorRole.DEFAULT) {
                Text(
                    stringResource(if (isSent) R.string.settings_bubble_color_baseline_sent_desc else R.string.settings_bubble_color_baseline_received_desc),
                    color = if (selected) ink else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun BubbleColorTarget(
    label: String,
    color: Color?,
    selected: Boolean,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        shape = MotdShapes.pill,
        modifier =
            modifier
                .heightIn(min = 48.dp)
                .clip(MotdShapes.pill)
                .selectable(selected, role = Role.RadioButton, onClick = onClick)
                .testTag(tag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BubbleColorSwatch(color, "${tag}_swatch")
            Text(label)
        }
    }
}

@Composable
private fun bubbleColorSample(
    selection: ComfortableBubbleColor,
    isSent: Boolean,
): Color? {
    if (selection.role == ComfortableBubbleColorRole.CUSTOM && selection.customColor == null) return null
    val scheme = MaterialTheme.colorScheme
    val selected = resolveComfortableBubbleColor(selection, scheme)
    return messageBubbleRoleColors(
        scheme = scheme,
        isSelf = isSent,
        mentionHighlighted = false,
        kind = MessageKind.PRIVMSG,
        semantic = LocalMotdSemanticColors.current,
        sentBubbleColor = if (isSent) selected else null,
        receivedBubbleColor = if (isSent) null else selected,
    ).container
}

@Composable
private fun BubbleColorSwatch(
    color: Color?,
    tag: String,
) {
    val scheme = MaterialTheme.colorScheme
    val customWheel =
        remember {
            Brush.sweepGradient(
                listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
            )
        }
    Box(
        Modifier
            .size(32.dp)
            .then(
                if (color == null) {
                    Modifier.drawBehind { drawCircle(customWheel) }
                } else {
                    Modifier.background(color, CircleShape)
                },
            ).border(1.dp, scheme.outline, CircleShape)
            .testTag(tag),
    )
}

@Composable
private fun ComfortableBubbleCustomColorDialog(
    initial: Int,
    onApply: (Int) -> Unit,
    previewContent: @Composable (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialHsv = remember(initial) { FloatArray(3).also { AndroidColor.colorToHSV(initial, it) } }
    var hue by rememberSaveable { mutableStateOf(initialHsv[0]) }
    var saturation by rememberSaveable { mutableStateOf(initialHsv[1]) }
    var brightness by rememberSaveable { mutableStateOf(initialHsv[2]) }
    var hex by rememberSaveable { mutableStateOf(wallpaperColorHex(initial)) }
    val parsed = parseColorHex(hex)
    val draft = parsed ?: AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))

    fun updateHex() {
        hex = wallpaperColorHex(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("settings_bubble_color_dialog"),
        title = { Text(stringResource(R.string.settings_bubble_color_custom)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                previewContent(draft)
                ColorWheel(hue, saturation, { nextHue, nextSaturation ->
                    hue = nextHue
                    saturation = nextSaturation
                    updateHex()
                }, "settings_bubble_color_wheel", stringResource(R.string.settings_bubble_color_custom))
                ColorComponentSlider(stringResource(R.string.settings_wallpaper_color_hue), hue, 0f..360f, pluralStringResource(R.plurals.settings_wallpaper_color_degrees, hue.roundToInt(), hue.roundToInt()), "settings_bubble_color_hue") {
                    hue = it
                    updateHex()
                }
                ColorComponentSlider(stringResource(R.string.settings_wallpaper_color_saturation), saturation, 0f..1f, stringResource(R.string.settings_wallpaper_color_percent, (saturation * 100).roundToInt()), "settings_bubble_color_saturation") {
                    saturation = it
                    updateHex()
                }
                ColorComponentSlider(stringResource(R.string.settings_wallpaper_color_brightness), brightness, 0f..1f, stringResource(R.string.settings_wallpaper_color_percent, (brightness * 100).roundToInt()), "settings_bubble_color_brightness") {
                    brightness = it
                    updateHex()
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { value ->
                        hex = value
                        parseColorHex(value)?.let { parsedColor ->
                            val hsv = FloatArray(3)
                            AndroidColor.colorToHSV(parsedColor, hsv)
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    label = { Text(stringResource(R.string.settings_wallpaper_color_hex)) },
                    isError = parsed == null,
                    supportingText = { if (parsed == null) Text(stringResource(R.string.settings_wallpaper_color_invalid)) },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text
                            .KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().testTag("settings_bubble_color_hex"),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings_bubble_color_cancel")) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            Button(onClick = { onApply(requireNotNull(parsed)) }, enabled = parsed != null, modifier = Modifier.testTag("settings_bubble_color_apply")) {
                Text(stringResource(R.string.settings_wallpaper_color_apply))
            }
        },
    )
}

private fun bubbleColorRoleLabel(
    role: ComfortableBubbleColorRole,
    isSent: Boolean,
): Int =
    when (role) {
        ComfortableBubbleColorRole.DEFAULT -> if (isSent) R.string.settings_bubble_color_baseline_sent else R.string.settings_bubble_color_baseline_received
        ComfortableBubbleColorRole.SURFACE_CONTAINER -> R.string.settings_bubble_color_surface_container
        ComfortableBubbleColorRole.SURFACE_CONTAINER_HIGH -> R.string.settings_bubble_color_surface_container_high
        ComfortableBubbleColorRole.PRIMARY -> R.string.settings_bubble_color_primary
        ComfortableBubbleColorRole.SECONDARY -> R.string.settings_bubble_color_secondary
        ComfortableBubbleColorRole.TERTIARY -> R.string.settings_bubble_color_tertiary
        ComfortableBubbleColorRole.PRIMARY_CONTAINER -> R.string.settings_bubble_color_primary_container
        ComfortableBubbleColorRole.SECONDARY_CONTAINER -> R.string.settings_bubble_color_secondary_container
        ComfortableBubbleColorRole.TERTIARY_CONTAINER -> R.string.settings_bubble_color_tertiary_container
        ComfortableBubbleColorRole.CUSTOM -> R.string.settings_bubble_color_custom
    }
