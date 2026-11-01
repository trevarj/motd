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
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.R
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
import io.github.trevarj.motd.ui.theme.MotdTheme
import io.github.trevarj.motd.ui.theme.SheetSystemBars
import io.github.trevarj.motd.ui.theme.resolveComfortableBubbleColor
import java.io.File
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

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

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("settings_bubble_color_sheet")) {
        SheetSystemBars()
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.settings_bubble_colors), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.settings_bubble_colors_desc), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.selectableGroup()) {
                Surface(
                    color = if (editSent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .selectable(editSent, role = Role.RadioButton, onClick = { editSent = true })
                            .testTag("settings_bubble_color_sent")
                            .padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.settings_bubble_color_sent))
                }
                Surface(
                    color = if (!editSent) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    modifier =
                        Modifier
                            .heightIn(min = 48.dp)
                            .selectable(!editSent, role = Role.RadioButton, onClick = { editSent = false })
                            .testTag("settings_bubble_color_received")
                            .padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.settings_bubble_color_received))
                }
            }
            AppearanceBubblePreview(settings, appearance.copy(sentBubbleColor = sent, receivedBubbleColor = received), customFontFile)
            AppearanceBubbleColorOptions(settings, appearance) {
                bubbleColorRoles.forEach { role ->
                    BubbleColorOption(role, selected.role == role) {
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
                    selected.customColor?.let(::Color),
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
        chatShadowsEnabled = appearance.chatShadowsEnabled,
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
    }
}

@Composable
private fun BubbleColorOption(
    role: ComfortableBubbleColorRole,
    selected: Boolean,
    color: Color? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val fill = color ?: resolveComfortableBubbleColor(ComfortableBubbleColor(role), scheme)
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.testTag("settings_bubble_color_" + role.name.lowercase()))
        Box(Modifier.size(24.dp).background(fill ?: scheme.secondaryContainer, CircleShape).border(1.dp, scheme.outline, CircleShape))
        Text(stringResource(bubbleColorRoleLabel(role)))
    }
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

private fun bubbleColorRoleLabel(role: ComfortableBubbleColorRole): Int =
    when (role) {
        ComfortableBubbleColorRole.DEFAULT -> R.string.settings_bubble_color_default
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
