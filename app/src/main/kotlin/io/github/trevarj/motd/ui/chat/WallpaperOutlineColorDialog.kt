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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.trevarj.motd.R
import io.github.trevarj.motd.data.prefs.WallpaperSelection
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Color as AndroidColor

internal fun wallpaperColorHex(color: Int): String = String.format(Locale.ROOT, "#%06X", color and 0xffffff)

private fun parseWallpaperColorHex(value: String): Int? {
    val digits = value.trim().removePrefix("#")
    return digits
        .takeIf { it.length == 6 && it.all { digit -> digit in '0'..'9' || digit in 'a'..'f' || digit in 'A'..'F' } }
        ?.toIntOrNull(16)
        ?.or(0xff000000.toInt())
}

@Composable
internal fun WallpaperOutlineColorDialog(
    selection: WallpaperSelection,
    onApply: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val themeColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val previewLabel = stringResource(R.string.settings_wallpaper_color_preview)
    val initial = remember { FloatArray(3).also { AndroidColor.colorToHSV(selection.outlineColor ?: themeColor, it) } }
    var hue by rememberSaveable { mutableStateOf(initial[0]) }
    var saturation by rememberSaveable { mutableStateOf(initial[1]) }
    var brightness by rememberSaveable { mutableStateOf(initial[2]) }
    var useDefault by rememberSaveable { mutableStateOf(selection.outlineColor == null) }
    var hex by rememberSaveable { mutableStateOf(wallpaperColorHex(selection.outlineColor ?: themeColor)) }
    val parsed = parseWallpaperColorHex(hex)
    val color = remember(hue, saturation, brightness) { AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)) }
    val draft = if (useDefault) null else parsed ?: color

    fun updateHex() {
        useDefault = false
        hex = wallpaperColorHex(AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.padding(horizontal = 16.dp).testTag("settings_wallpaper_color_dialog"),
        title = { Text(stringResource(R.string.settings_wallpaper_outline_color)) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("settings_wallpaper_color_list"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(32.dp).background(Color(draft ?: themeColor), CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape))
                    Text(if (useDefault) stringResource(R.string.settings_wallpaper_color_default) else wallpaperColorHex(draft ?: color))
                }
                WallpaperPreview(
                    selection.copy(outlineColor = draft),
                    Modifier.fillMaxWidth().height(140.dp).testTag("settings_wallpaper_color_preview").semantics {
                        contentDescription = previewLabel
                    },
                )
                OutlineColorWheel(hue, saturation, brightness) { nextHue, nextSaturation ->
                    hue = nextHue
                    saturation = nextSaturation
                    updateHex()
                }
                ColorComponentSlider(
                    label = stringResource(R.string.settings_wallpaper_color_hue),
                    value = hue,
                    range = 0f..360f,
                    state = stringResource(R.string.settings_wallpaper_color_degrees, hue.roundToInt()),
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
                    onValueChange = { value ->
                        hex = value
                        useDefault = false
                        parseWallpaperColorHex(value)?.let { parsedColor ->
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
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth().testTag("settings_wallpaper_color_hex"),
                )
                TextButton(
                    onClick = {
                        val hsv = FloatArray(3)
                        AndroidColor.colorToHSV(themeColor, hsv)
                        hue = hsv[0]
                        saturation = hsv[1]
                        brightness = hsv[2]
                        hex = wallpaperColorHex(themeColor)
                        useDefault = true
                    },
                    modifier = Modifier.testTag("settings_wallpaper_color_default"),
                ) { Text(stringResource(R.string.settings_wallpaper_color_default)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("settings_wallpaper_color_cancel")) {
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
private fun ColorComponentSlider(
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
private fun OutlineColorWheel(
    hue: Float,
    saturation: Float,
    brightness: Float,
    onChange: (Float, Float) -> Unit,
) {
    val change by rememberUpdatedState(onChange)
    val wheelLabel = stringResource(R.string.settings_wallpaper_color_wheel)
    val hueState = stringResource(R.string.settings_wallpaper_color_degrees, hue.roundToInt())
    val saturationState = stringResource(R.string.settings_wallpaper_color_percent, (saturation * 100).roundToInt())
    val rainbow = remember { Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)) }
    val saturationBrush = remember { Brush.radialGradient(listOf(Color.White, Color.Transparent)) }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp)
            .padding(8.dp)
            .testTag("settings_wallpaper_color_wheel")
            .semantics {
                contentDescription = wheelLabel
                stateDescription = "$hueState, $saturationState"
            }.pointerInput(Unit) {
                fun select(position: Offset) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val offset = position - center
                    val radius = (minOf(size.width, size.height) / 2f).coerceAtLeast(1f)
                    val degrees = ((atan2(offset.y, offset.x) * 180f / PI.toFloat()) + 360f) % 360f
                    change(degrees, (offset.getDistance() / radius).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    select(down.position)
                    down.consume()
                    drag(down.id) {
                        select(it.position)
                        it.consume()
                    }
                }
            },
    ) {
        val radius = size.minDimension / 2f
        drawCircle(rainbow, radius)
        drawCircle(saturationBrush, radius)
        drawCircle(Color.Black.copy(alpha = 1f - brightness), radius)
        val angle = hue * PI.toFloat() / 180f
        val marker = center + Offset(cos(angle), sin(angle)) * (saturation * radius)
        drawCircle(Color.Black, 7.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
        drawCircle(Color.White, 7.dp.toPx(), marker, style = Stroke(1.5.dp.toPx()))
    }
}
