package io.github.trevarj.motd.ui.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.trevarj.motd.R
import io.github.trevarj.motd.ui.components.ThemeColorSwatches
import io.github.trevarj.motd.ui.theme.readableTextButtonColors
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Color as AndroidColor
import io.github.trevarj.motd.ui.components.EmojiOutlinedTextField as OutlinedTextField
import io.github.trevarj.motd.ui.components.EmojiText as Text

internal fun wallpaperColorHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xffffff)

internal fun parseColorHex(value: String): Int? {
    val digits = value.trim().removePrefix("#")
    return digits
        .takeIf { it.length == 6 && it.all { digit -> digit in '0'..'9' || digit in 'a'..'f' || digit in 'A'..'F' } }
        ?.toIntOrNull(16)
        ?.or(0xff000000.toInt())
}

@Composable
internal fun WallpaperColorDialog(
    label: String,
    defaultLabel: String,
    defaultColor: Int,
    value: Int?,
    onApply: (Int?) -> Unit,
    onDismiss: () -> Unit,
    preview: @Composable (Int?, Modifier) -> Unit,
) {
    val previewLabel = stringResource(R.string.settings_wallpaper_color_preview)
    val initial = remember { FloatArray(3).also { AndroidColor.colorToHSV(value ?: defaultColor, it) } }
    var hue by rememberSaveable { mutableStateOf(initial[0]) }
    var saturation by rememberSaveable { mutableStateOf(initial[1]) }
    var brightness by rememberSaveable { mutableStateOf(initial[2]) }
    var useDefault by rememberSaveable { mutableStateOf(value == null) }
    var hex by rememberSaveable { mutableStateOf(wallpaperColorHex(value ?: defaultColor)) }
    val parsed = parseColorHex(hex)
    val color = remember(hue, saturation, brightness) { AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)) }
    val draft = if (useDefault) null else parsed ?: color

    fun updateHex() {
        useDefault = false
        hex = wallpaperColorHex(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    }

    fun setHex(value: String) {
        hex = value
        useDefault = false
        parseColorHex(value)?.let { parsedColor ->
            val hsv = FloatArray(3)
            AndroidColor.colorToHSV(parsedColor, hsv)
            hue = hsv[0]
            saturation = hsv[1]
            brightness = hsv[2]
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 16.dp).testTag("settings_wallpaper_color_dialog"),
        title = { Text(label) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        text = {
            ColorEditorScrollColumn(Modifier.testTag("settings_wallpaper_color_list")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(32.dp).background(Color(draft ?: defaultColor), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    Text(if (useDefault) defaultLabel else wallpaperColorHex(draft ?: color))
                }
                preview(
                    draft,
                    Modifier.fillMaxWidth().height(140.dp).testTag("settings_wallpaper_color_preview").semantics {
                        contentDescription = previewLabel
                    },
                )
                ThemeColorSwatches(selected = parsed.takeUnless { useDefault }, onSelect = { setHex(wallpaperColorHex(it)) })
                ColorWheel(hue, saturation, onChange = { nextHue, nextSaturation ->
                    hue = nextHue
                    saturation = nextSaturation
                    updateHex()
                })
                ColorComponentSlider(
                    label = stringResource(R.string.settings_wallpaper_color_hue),
                    value = hue,
                    range = 0f..360f,
                    state = pluralStringResource(R.plurals.settings_wallpaper_color_degrees, hue.roundToInt(), hue.roundToInt()),
                    tag = "settings_wallpaper_color_hue",
                ) {
                    hue = it
                    updateHex()
                }
                ColorComponentSlider(
                    label = stringResource(R.string.settings_wallpaper_color_saturation),
                    value = saturation,
                    range = 0f..1f,
                    state = stringResource(R.string.settings_wallpaper_color_percent, (saturation * 100).roundToInt()),
                    tag = "settings_wallpaper_color_saturation",
                ) {
                    saturation = it
                    updateHex()
                }
                ColorComponentSlider(
                    label = stringResource(R.string.settings_wallpaper_color_brightness),
                    value = brightness,
                    range = 0f..1f,
                    state = stringResource(R.string.settings_wallpaper_color_percent, (brightness * 100).roundToInt()),
                    tag = "settings_wallpaper_color_brightness",
                ) {
                    brightness = it
                    updateHex()
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = ::setHex,
                    label = { Text(stringResource(R.string.settings_wallpaper_color_hex)) },
                    isError = parsed == null,
                    supportingText = { if (parsed == null) Text(stringResource(R.string.settings_wallpaper_color_invalid)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().testTag("settings_wallpaper_color_hex"),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((labelId, presetBrightness) in listOf(R.string.wallpaper_workshop_black to 0f, R.string.wallpaper_workshop_white to 1f)) {
                        TextButton(
                            colors = readableTextButtonColors(),
                            onClick = {
                                saturation = 0f
                                brightness = presetBrightness
                                updateHex()
                            },
                        ) { Text(stringResource(labelId)) }
                    }
                }
                TextButton(
                    colors = readableTextButtonColors(),
                    onClick = {
                        val hsv = FloatArray(3)
                        AndroidColor.colorToHSV(defaultColor, hsv)
                        hue = hsv[0]
                        saturation = hsv[1]
                        brightness = hsv[2]
                        hex = wallpaperColorHex(defaultColor)
                        useDefault = true
                    },
                    modifier = Modifier.testTag("settings_wallpaper_color_default"),
                ) { Text(defaultLabel) }
            }
        },
        dismissButton = {
            TextButton(colors = readableTextButtonColors(), onClick = onDismiss, modifier = Modifier.testTag("settings_wallpaper_color_cancel")) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        confirmButton = {
            Button(onClick = { onApply(draft) }, enabled = parsed != null, modifier = Modifier.testTag("settings_wallpaper_color_apply")) {
                Text(stringResource(R.string.settings_wallpaper_color_apply))
            }
        },
    )
}

@Composable
internal fun ColorEditorScrollColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
internal fun ColorComponentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    state: String,
    tag: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Text("$label · $state")
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier =
                Modifier.testTag(tag).semantics {
                    contentDescription = label
                    stateDescription = state
                },
        )
    }
}

@Composable
internal fun ColorWheel(
    hue: Float,
    saturation: Float,
    onChange: (Float, Float) -> Unit,
    tag: String = "settings_wallpaper_color_wheel",
    label: String? = null,
) {
    val change by rememberUpdatedState(onChange)
    val wheelLabel = label ?: stringResource(R.string.settings_wallpaper_color_wheel)
    val hueState = pluralStringResource(R.plurals.settings_wallpaper_color_degrees, hue.roundToInt(), hue.roundToInt())
    val saturationState = stringResource(R.string.settings_wallpaper_color_percent, (saturation * 100).roundToInt())
    val rainbow = remember { Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)) }
    val saturationBrush = remember { Brush.radialGradient(listOf(Color.White, Color.Transparent)) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(8.dp)
            .testTag(tag)
            .semantics {
                contentDescription = wheelLabel
                stateDescription = "$hueState, $saturationState"
            }.pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = (minOf(size.width, size.height) / 2f).coerceAtLeast(1f)
                    if ((down.position - center).getDistance() > radius) return@awaitEachGesture

                    fun select(position: Offset) {
                        val offset = position - center
                        val degrees = ((atan2(offset.y, offset.x) * 180f / PI.toFloat()) + 360f) % 360f
                        change(degrees, (offset.getDistance() / radius).coerceIn(0f, 1f))
                    }

                    down.consume()
                    select(down.position)
                    drag(down.id) {
                        it.consume()
                        select(it.position)
                    }
                }
            },
    ) {
        val radius = size.minDimension / 2f
        drawCircle(rainbow, radius)
        drawCircle(saturationBrush, radius)
        val angle = hue * PI.toFloat() / 180f
        val marker = center + Offset(cos(angle), sin(angle)) * (saturation * radius)
        drawCircle(Color.Black, 7.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), marker, style = Stroke(1.5.dp.toPx()))
    }
}
