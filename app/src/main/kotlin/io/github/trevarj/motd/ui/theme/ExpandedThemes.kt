package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import io.github.trevarj.motd.data.prefs.PaletteColors

private fun mix(
    a: Color,
    b: Color,
    amount: Float,
): Color =
    Color(
        red = a.red + (b.red - a.red) * amount,
        green = a.green + (b.green - a.green) * amount,
        blue = a.blue + (b.blue - a.blue) * amount,
        alpha = 1f,
    )

internal fun darkEditorScheme(
    background: Long,
    surface: Long,
    foreground: Long,
    muted: Long,
    primary: Long,
    secondary: Long,
    tertiary: Long,
    surfaceContainer: Long? = null,
    onBackground: Long = foreground,
    outlineVariant: Long? = null,
): ColorScheme {
    val bg = Color(background)
    val sf = Color(surface)
    val container = surfaceContainer?.let { Color(it) } ?: sf
    val fg = Color(foreground)
    val mt = Color(muted)
    val p = Color(primary)
    val s = Color(secondary)
    val t = Color(tertiary)
    return darkColorScheme(
        primary = p,
        onPrimary = bg,
        primaryContainer = mix(bg, p, .18f),
        onPrimaryContainer = fg,
        secondary = s,
        onSecondary = bg,
        secondaryContainer = mix(bg, s, .16f),
        onSecondaryContainer = fg,
        tertiary = t,
        onTertiary = bg,
        tertiaryContainer = mix(bg, t, .16f),
        onTertiaryContainer = fg,
        background = bg,
        onBackground = Color(onBackground),
        surface = sf,
        onSurface = fg,
        surfaceVariant = mix(sf, fg, .12f),
        onSurfaceVariant = mt,
        outline = outlineVariant?.let { Color(it) } ?: mix(bg, mt, .65f),
        outlineVariant = outlineVariant?.let { Color(it) } ?: mix(bg, mt, .35f),
        surfaceContainerLowest = mix(if (surfaceContainer == null) bg else container, Color.Black, .18f),
        surfaceContainerLow = mix(bg, container, .45f),
        surfaceContainer = container,
        surfaceContainerHigh = mix(container, fg, .07f),
        surfaceContainerHighest = mix(container, fg, .13f),
    )
}

internal fun lightEditorScheme(
    background: Long,
    surface: Long,
    foreground: Long,
    muted: Long,
    primary: Long,
    secondary: Long,
    tertiary: Long,
    surfaceContainer: Long? = null,
    onBackground: Long = foreground,
    outlineVariant: Long? = null,
): ColorScheme {
    val bg = Color(background)
    val sf = Color(surface)
    val container = surfaceContainer?.let { Color(it) } ?: sf
    val fg = Color(foreground)
    val mt = Color(muted)
    val p = Color(primary)
    val s = Color(secondary)
    val t = Color(tertiary)
    return lightColorScheme(
        primary = p,
        onPrimary = Color.White,
        primaryContainer = mix(bg, p, .17f),
        onPrimaryContainer = fg,
        secondary = s,
        onSecondary = Color.White,
        secondaryContainer = mix(bg, s, .15f),
        onSecondaryContainer = fg,
        tertiary = t,
        onTertiary = Color.White,
        tertiaryContainer = mix(bg, t, .15f),
        onTertiaryContainer = fg,
        background = bg,
        onBackground = Color(onBackground),
        surface = sf,
        onSurface = fg,
        surfaceVariant = mix(sf, fg, .09f),
        onSurfaceVariant = mt,
        outline = outlineVariant?.let { Color(it) } ?: mix(bg, mt, .65f),
        outlineVariant = outlineVariant?.let { Color(it) } ?: mix(bg, mt, .30f),
        surfaceContainerLowest = mix(if (surfaceContainer == null) bg else container, Color.White, .65f),
        surfaceContainerLow = mix(if (surfaceContainer == null) bg else container, fg, .025f),
        surfaceContainer = container,
        surfaceContainerHigh = mix(container, fg, .06f),
        surfaceContainerHighest = mix(container, fg, .11f),
    )
}

/** Authored roles only; callers apply accessibility and optional true black once. */
internal fun PaletteColors.toRawColorScheme(dark: Boolean): ColorScheme {
    fun String.argb(): Long = 0xFF000000L or substring(1).toLong(16)
    return if (dark) {
        darkEditorScheme(
            background = background.argb(),
            surface = surface.argb(),
            foreground = onSurface.argb(),
            muted = onSurfaceVariant.argb(),
            primary = primary.argb(),
            secondary = secondary.argb(),
            tertiary = tertiary.argb(),
            surfaceContainer = surfaceContainer.argb(),
            onBackground = onBackground.argb(),
            outlineVariant = outlineVariant.argb(),
        )
    } else {
        lightEditorScheme(
            background = background.argb(),
            surface = surface.argb(),
            foreground = onSurface.argb(),
            muted = onSurfaceVariant.argb(),
            primary = primary.argb(),
            secondary = secondary.argb(),
            tertiary = tertiary.argb(),
            surfaceContainer = surfaceContainer.argb(),
            onBackground = onBackground.argb(),
            outlineVariant = outlineVariant.argb(),
        )
    }
}

// Aligned source roles: Noctalia c1a7283a50b6ce8d0459b8864d8713289597bae9;
// Everforest Alt, Kanagawa Dragon and Rose Pine Moon use community-palettes
// 1c43e2c66c2cd07a58ae07007a88cbdd0705cda2. Unmatched variants retain MOTD's roles.
val AyuDarkScheme =
    darkEditorScheme(
        background = 0xFF0B0E14,
        surface = 0xFF0B0E14,
        foreground = 0xFFD1D1C7,
        muted = 0xFF8E959E,
        primary = 0xFFE6B450,
        secondary = 0xFFAAD94C,
        tertiary = 0xFF39BAE6,
        surfaceContainer = 0xFF1E222A,
        outlineVariant = 0xFF565B66,
    )
val AyuLightScheme =
    lightEditorScheme(
        background = 0xFFF8F9FA,
        surface = 0xFFF8F9FA,
        foreground = 0xFF42474C,
        muted = 0xFF6E757C,
        primary = 0xFFFF8F40,
        secondary = 0xFF86B300,
        tertiary = 0xFF55B4D4,
        surfaceContainer = 0xFFE4E6E9,
        outlineVariant = 0xFF8A9199,
    )
val AyuMirageScheme =
    darkEditorScheme(
        0xFF1F2430,
        0xFF242936,
        0xFFCBCCC6,
        0xFF8A9199,
        0xFF73D0FF,
        0xFFBAE67E,
        0xFFFFD580,
    )

val EverforestDarkScheme =
    darkEditorScheme(
        background = 0xFF2D353B,
        surface = 0xFF2D353B,
        foreground = 0xFFD3C6AA,
        muted = 0xFFD3C6AA,
        primary = 0xFFA7C080,
        secondary = 0xFF7FBBB3,
        tertiary = 0xFFE69875,
        surfaceContainer = 0xFF3D484D,
        outlineVariant = 0xFF7A8478,
    )
val EverforestLightScheme =
    lightEditorScheme(
        background = 0xFFFFFBEF,
        surface = 0xFFFFFBEF,
        foreground = 0xFF5C6A72,
        muted = 0xFF5C6A72,
        primary = 0xFF8DA101,
        secondary = 0xFF3A94C5,
        tertiary = 0xFFF57D26,
        surfaceContainer = 0xFFF2EFDB,
        outlineVariant = 0xFFA6B0A0,
    )

val KanagawaWaveScheme =
    darkEditorScheme(
        background = 0xFF1F1F28,
        surface = 0xFF1F1F28,
        foreground = 0xFFC8C093,
        muted = 0xFF717C7C,
        primary = 0xFF76946A,
        secondary = 0xFFC0A36E,
        tertiary = 0xFF7E9CD8,
        surfaceContainer = 0xFF2A2A37,
        outlineVariant = 0xFF363646,
    )
val KanagawaDragonScheme =
    darkEditorScheme(
        background = 0xFF181616,
        surface = 0xFF181616,
        foreground = 0xFFC5C9C5,
        muted = 0xFFC8C093,
        primary = 0xFF8A9A7B,
        secondary = 0xFF8EA4A2,
        tertiary = 0xFFC4746E,
        surfaceContainer = 0xFF282727,
        outlineVariant = 0xFF625E5A,
    )
val KanagawaLotusScheme =
    lightEditorScheme(
        background = 0xFFF2ECBC,
        surface = 0xFFF2ECBC,
        foreground = 0xFF545464,
        muted = 0xFF8A8980,
        primary = 0xFF6F894E,
        secondary = 0xFF77713F,
        tertiary = 0xFF4D699B,
        surfaceContainer = 0xFFE5DDB0,
        outlineVariant = 0xFFCFC49C,
    )

val ModusOperandiScheme =
    lightEditorScheme(
        0xFFFFFFFF,
        0xFFF2F2F2,
        0xFF000000,
        0xFF595959,
        0xFF0031A9,
        0xFF006800,
        0xFF721045,
    )
val ModusVivendiScheme =
    darkEditorScheme(
        0xFF000000,
        0xFF1E1E1E,
        0xFFFFFFFF,
        0xFFBFC0C4,
        0xFF2Fafff,
        0xFF44BC44,
        0xFFFEACD0,
    )
val ModusOperandiTintedScheme =
    lightEditorScheme(
        0xFFFBF7F0,
        0xFFEFE9DD,
        0xFF000000,
        0xFF595959,
        0xFF0031A9,
        0xFF006300,
        0xFF721045,
    )
val ModusVivendiTintedScheme =
    darkEditorScheme(
        0xFF0D0E1C,
        0xFF1D2235,
        0xFFFFFFFF,
        0xFF989898,
        0xFF2FAFFF,
        0xFF44BC44,
        0xFFFEACD0,
    )

// The color-deficiency variants preserve the upstream semantic pairings: yellow/blue for
// deuteranopia and red/cyan for tritanopia.
val ModusOperandiDeuteranopiaScheme =
    lightEditorScheme(
        0xFFFFFFFF,
        0xFFF2F2F2,
        0xFF000000,
        0xFF595959,
        0xFF0031A9,
        0xFF695500,
        0xFF973300,
    )
val ModusVivendiDeuteranopiaScheme =
    darkEditorScheme(
        0xFF000000,
        0xFF1E1E1E,
        0xFFFFFFFF,
        0xFF989898,
        0xFF2FAFFF,
        0xFFCABF00,
        0xFFFFA00F,
    )
val ModusOperandiTritanopiaScheme =
    lightEditorScheme(
        0xFFFFFFFF,
        0xFFF2F2F2,
        0xFF000000,
        0xFF595959,
        0xFF005E8B,
        0xFFA60000,
        0xFFA0132F,
    )
val ModusVivendiTritanopiaScheme =
    darkEditorScheme(
        0xFF000000,
        0xFF1E1E1E,
        0xFFFFFFFF,
        0xFF989898,
        0xFF00D3D0,
        0xFFFF5F59,
        0xFFFF7F86,
    )

val MonokaiScheme =
    darkEditorScheme(
        0xFF272A30,
        0xFF2E323C,
        0xFFF8F8F0,
        0xFFB1B1B1,
        0xFF66D9EF,
        0xFFA6E22E,
        0xFFF92672,
    )
val OneDarkScheme =
    darkEditorScheme(
        0xFF282C34,
        0xFF21252B,
        0xFFABB2BF,
        0xFF8B93A1,
        0xFF61AFEF,
        0xFF98C379,
        0xFFC678DD,
    )

val RosePineScheme =
    darkEditorScheme(
        background = 0xFF191724,
        surface = 0xFF191724,
        foreground = 0xFFE0DEF4,
        muted = 0xFF908CAA,
        primary = 0xFFEBBCBA,
        secondary = 0xFF9CCFD8,
        tertiary = 0xFF31748F,
        surfaceContainer = 0xFF26233A,
        outlineVariant = 0xFF403D52,
    )
val RosePineMoonScheme =
    darkEditorScheme(
        background = 0xFF232136,
        surface = 0xFF232136,
        foreground = 0xFFE0DEF4,
        muted = 0xFF908CAA,
        primary = 0xFFEA9A97,
        secondary = 0xFF9CCFD8,
        tertiary = 0xFF32758F,
        surfaceContainer = 0xFF393552,
        outlineVariant = 0xFF44415A,
    )
val RosePineDawnScheme =
    lightEditorScheme(
        background = 0xFFFFFAF3,
        surface = 0xFFFFFAF3,
        foreground = 0xFF575279,
        muted = 0xFF797593,
        primary = 0xFFD7827E,
        secondary = 0xFF56949F,
        tertiary = 0xFF286983,
        surfaceContainer = 0xFFF2E9E1,
        outlineVariant = 0xFFDFDAD9,
    )

val ZenburnScheme =
    darkEditorScheme(
        0xFF3F3F3F,
        0xFF4F4F4F,
        0xFFDCDCCC,
        0xFFBDBDBD,
        0xFF8CD0D3,
        0xFF7F9F7F,
        0xFFDC8CC3,
    )
