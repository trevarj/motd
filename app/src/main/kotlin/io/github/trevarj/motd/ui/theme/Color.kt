package io.github.trevarj.motd.ui.theme

import androidx.compose.ui.graphics.Color

// Brand seeds. Static light, dark, and AMOLED schemes use a role-coded teal/orchid/coral trio.
// System dynamic color deliberately remains fully controlled by the platform palette.
val MotdTeal = Color(0xFF006C70)
val MotdOrchid = Color(0xFF8B4FA5)
val MotdCoral = Color(0xFFB44C46)

// Source roles: Noctalia built-ins c1a7283a50b6ce8d0459b8864d8713289597bae9;
// Solarized: community-palettes 1c43e2c66c2cd07a58ae07007a88cbdd0705cda2.
// Accessibility and semantic error roles are derived separately by ThemeSchemes.
val GruvboxDarkScheme =
    darkEditorScheme(
        background = 0xFF282828,
        surface = 0xFF282828,
        foreground = 0xFFFBF1C7,
        muted = 0xFFEBDBB2,
        primary = 0xFFB8BB26,
        secondary = 0xFFFABD2F,
        tertiary = 0xFF83A598,
        surfaceContainer = 0xFF3C3836,
        outlineVariant = 0xFF57514E,
    )

val GruvboxLightScheme =
    lightEditorScheme(
        background = 0xFFFBF1C7,
        surface = 0xFFFBF1C7,
        foreground = 0xFF3C3836,
        muted = 0xFF7C6F64,
        primary = 0xFF98971A,
        secondary = 0xFFD79921,
        tertiary = 0xFF458588,
        surfaceContainer = 0xFFEBDBB2,
        outlineVariant = 0xFFBDAE93,
    )

val SolarizedDarkScheme =
    darkEditorScheme(
        background = 0xFF002B36,
        surface = 0xFF002B36,
        foreground = 0xFF839496,
        muted = 0xFF657B83,
        primary = 0xFFB58900,
        secondary = 0xFFD33682,
        tertiary = 0xFFCB4B16,
        surfaceContainer = 0xFF06313C,
        outlineVariant = 0xFF0C5C70,
    )

val SolarizedLightScheme =
    lightEditorScheme(
        background = 0xFFFDF6E3,
        surface = 0xFFFDF6E3,
        foreground = 0xFF657B83,
        muted = 0xFF738587,
        primary = 0xFFA37A00,
        secondary = 0xFFD33682,
        tertiary = 0xFFCB4B16,
        surfaceContainer = 0xFFEEE8D5,
        outlineVariant = 0xFFDFD4B1,
    )

val DraculaScheme =
    darkEditorScheme(
        background = 0xFF282A36,
        surface = 0xFF282A36,
        foreground = 0xFFF8F8F2,
        muted = 0xFFD6D8E0,
        primary = 0xFFBD93F9,
        secondary = 0xFFFF79C6,
        tertiary = 0xFF8BE9FD,
        surfaceContainer = 0xFF44475A,
        outlineVariant = 0xFF5A5E77,
    )

val DraculaLightScheme =
    lightEditorScheme(
        background = 0xFFF8F8F2,
        surface = 0xFFF8F8F2,
        foreground = 0xFF282A36,
        muted = 0xFF44475A,
        primary = 0xFF8332F4,
        secondary = 0xFFFF1399,
        tertiary = 0xFF0398B9,
        surfaceContainer = 0xFFE6E6EA,
        outlineVariant = 0xFFCACAD3,
    )

val NordScheme =
    darkEditorScheme(
        background = 0xFF2E3440,
        surface = 0xFF2E3440,
        foreground = 0xFFECEFF4,
        muted = 0xFFD8DEE9,
        primary = 0xFF8FBCBB,
        secondary = 0xFF88C0D0,
        tertiary = 0xFF5E81AC,
        surfaceContainer = 0xFF3B4252,
        outlineVariant = 0xFF505A70,
    )

val NordLightScheme =
    lightEditorScheme(
        background = 0xFFECEFF4,
        surface = 0xFFECEFF4,
        foreground = 0xFF2E3440,
        muted = 0xFF4C566A,
        primary = 0xFF5E81AC,
        secondary = 0xFF64ADC2,
        tertiary = 0xFF6FA9A8,
        surfaceContainer = 0xFFE5E9F0,
        outlineVariant = 0xFFC5CEDD,
    )

val CatppuccinLatteScheme =
    lightEditorScheme(
        background = 0xFFEFF1F5,
        surface = 0xFFEFF1F5,
        foreground = 0xFF4C4F69,
        muted = 0xFF6C6F85,
        primary = 0xFF8839EF,
        secondary = 0xFFFE640B,
        tertiary = 0xFF40A02B,
        surfaceContainer = 0xFFCCD0DA,
        outlineVariant = 0xFFA5ADCB,
    )

val CatppuccinMochaScheme =
    darkEditorScheme(
        background = 0xFF1E1E2E,
        surface = 0xFF1E1E2E,
        foreground = 0xFFCDD6F4,
        muted = 0xFFA3B4EB,
        primary = 0xFFCBA6F7,
        secondary = 0xFFFAB387,
        tertiary = 0xFF94E2D5,
        surfaceContainer = 0xFF313244,
        outlineVariant = 0xFF4C4F69,
    )

val TokyoNightScheme =
    darkEditorScheme(
        background = 0xFF1A1B26,
        surface = 0xFF1A1B26,
        foreground = 0xFFC0CAF5,
        muted = 0xFF9AA5CE,
        primary = 0xFF7AA2F7,
        secondary = 0xFFBB9AF7,
        tertiary = 0xFF9ECE6A,
        surfaceContainer = 0xFF24283B,
        outlineVariant = 0xFF353D57,
    )

val TokyoNightLightScheme =
    lightEditorScheme(
        background = 0xFFE1E2E7,
        surface = 0xFFE1E2E7,
        foreground = 0xFF3760BF,
        muted = 0xFF6172B0,
        primary = 0xFF2E7DE9,
        secondary = 0xFF9854F1,
        tertiary = 0xFF587539,
        surfaceContainer = 0xFFD0D5E3,
        outlineVariant = 0xFFB4B5B9,
    )
