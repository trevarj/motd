package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private fun mix(a: Color, b: Color, amount: Float): Color = Color(
    red = a.red + (b.red - a.red) * amount,
    green = a.green + (b.green - a.green) * amount,
    blue = a.blue + (b.blue - a.blue) * amount,
    alpha = 1f,
)

private fun darkEditorScheme(
    background: Long,
    surface: Long,
    foreground: Long,
    muted: Long,
    primary: Long,
    secondary: Long,
    tertiary: Long,
): ColorScheme {
    val bg = Color(background)
    val sf = Color(surface)
    val fg = Color(foreground)
    val mt = Color(muted)
    val p = Color(primary)
    val s = Color(secondary)
    val t = Color(tertiary)
    return darkColorScheme(
        primary = p, onPrimary = bg,
        primaryContainer = mix(bg, p, .18f), onPrimaryContainer = fg,
        secondary = s, onSecondary = bg,
        secondaryContainer = mix(bg, s, .16f), onSecondaryContainer = fg,
        tertiary = t, onTertiary = bg,
        tertiaryContainer = mix(bg, t, .16f), onTertiaryContainer = fg,
        background = bg, onBackground = fg,
        surface = sf, onSurface = fg,
        surfaceVariant = mix(sf, fg, .12f), onSurfaceVariant = mt,
        outline = mix(bg, mt, .65f), outlineVariant = mix(bg, mt, .35f),
        surfaceContainerLowest = mix(bg, Color.Black, .18f),
        surfaceContainerLow = mix(bg, sf, .45f),
        surfaceContainer = sf,
        surfaceContainerHigh = mix(sf, fg, .07f),
        surfaceContainerHighest = mix(sf, fg, .13f),
    )
}

private fun lightEditorScheme(
    background: Long,
    surface: Long,
    foreground: Long,
    muted: Long,
    primary: Long,
    secondary: Long,
    tertiary: Long,
): ColorScheme {
    val bg = Color(background)
    val sf = Color(surface)
    val fg = Color(foreground)
    val mt = Color(muted)
    val p = Color(primary)
    val s = Color(secondary)
    val t = Color(tertiary)
    return lightColorScheme(
        primary = p, onPrimary = Color.White,
        primaryContainer = mix(bg, p, .17f), onPrimaryContainer = fg,
        secondary = s, onSecondary = Color.White,
        secondaryContainer = mix(bg, s, .15f), onSecondaryContainer = fg,
        tertiary = t, onTertiary = Color.White,
        tertiaryContainer = mix(bg, t, .15f), onTertiaryContainer = fg,
        background = bg, onBackground = fg,
        surface = sf, onSurface = fg,
        surfaceVariant = mix(sf, fg, .09f), onSurfaceVariant = mt,
        outline = mix(bg, mt, .65f), outlineVariant = mix(bg, mt, .30f),
        surfaceContainerLowest = mix(bg, Color.White, .65f),
        surfaceContainerLow = mix(bg, fg, .025f),
        surfaceContainer = sf,
        surfaceContainerHigh = mix(sf, fg, .06f),
        surfaceContainerHighest = mix(sf, fg, .11f),
    )
}

val AyuDarkScheme = darkEditorScheme(
    0xFF0A0E14, 0xFF11151C, 0xFFB3B1AD, 0xFF8A9199, 0xFF59C2FF, 0xFFAAD94C, 0xFFFFB454,
)
val AyuLightScheme = lightEditorScheme(
    0xFFFAFAFA, 0xFFF3F4F5, 0xFF4C5761, 0xFF667581, 0xFF399EE6, 0xFF86B300, 0xFFF2AE49,
)
val AyuMirageScheme = darkEditorScheme(
    0xFF1F2430, 0xFF242936, 0xFFCBCCC6, 0xFF8A9199, 0xFF73D0FF, 0xFFBAE67E, 0xFFFFD580,
)

val EverforestDarkScheme = darkEditorScheme(
    0xFF2D353B, 0xFF343F44, 0xFFD3C6AA, 0xFF9DA9A0, 0xFF7FBBB3, 0xFFA7C080, 0xFFE69875,
)
val EverforestLightScheme = lightEditorScheme(
    0xFFFDF6E3, 0xFFF4F0D9, 0xFF5C6A72, 0xFF829181, 0xFF3A94C5, 0xFF8DA101, 0xFFF57D26,
)

val KanagawaWaveScheme = darkEditorScheme(
    0xFF1F1F28, 0xFF2A2A37, 0xFFDCD7BA, 0xFF9CABCA, 0xFF7E9CD8, 0xFF98BB6C, 0xFFE6C384,
)
val KanagawaDragonScheme = darkEditorScheme(
    0xFF181616, 0xFF282727, 0xFFC5C9C5, 0xFF9E9B93, 0xFF8BA4B0, 0xFF87A987, 0xFFC4B28A,
)
val KanagawaLotusScheme = lightEditorScheme(
    0xFFF2ECBC, 0xFFE7DDB5, 0xFF545464, 0xFF716E61, 0xFF4D699B, 0xFF6F894E, 0xFFB35B79,
)

val ModusOperandiScheme = lightEditorScheme(
    0xFFFFFFFF, 0xFFF2F2F2, 0xFF000000, 0xFF595959, 0xFF0031A9, 0xFF006800, 0xFF721045,
)
val ModusVivendiScheme = darkEditorScheme(
    0xFF000000, 0xFF1E1E1E, 0xFFFFFFFF, 0xFFBFC0C4, 0xFF2Fafff, 0xFF44BC44, 0xFFFEACD0,
)

val MonokaiScheme = darkEditorScheme(
    0xFF272A30, 0xFF2E323C, 0xFFF8F8F0, 0xFFB1B1B1, 0xFF66D9EF, 0xFFA6E22E, 0xFFF92672,
)
val OneDarkScheme = darkEditorScheme(
    0xFF282C34, 0xFF21252B, 0xFFABB2BF, 0xFF8B93A1, 0xFF61AFEF, 0xFF98C379, 0xFFC678DD,
)

val RosePineScheme = darkEditorScheme(
    0xFF191724, 0xFF1F1D2E, 0xFFE0DEF4, 0xFF908CAA, 0xFF9CCFD8, 0xFF31748F, 0xFFC4A7E7,
)
val RosePineMoonScheme = darkEditorScheme(
    0xFF232136, 0xFF2A273F, 0xFFE0DEF4, 0xFF908CAA, 0xFF9CCFD8, 0xFF3E8FB0, 0xFFC4A7E7,
)
val RosePineDawnScheme = lightEditorScheme(
    0xFFFAF4ED, 0xFFFFFAF3, 0xFF575279, 0xFF797593, 0xFF286983, 0xFF56949F, 0xFF907AA9,
)

val ZenburnScheme = darkEditorScheme(
    0xFF3F3F3F, 0xFF4F4F4F, 0xFFDCDCCC, 0xFFBDBDBD, 0xFF8CD0D3, 0xFF7F9F7F, 0xFFDC8CC3,
)
