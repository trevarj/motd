package io.github.trevarj.motd.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Brand seed. Static color schemes derive from this indigo when dynamic color is unavailable.
val Indigo = Color(0xFF5B6EE1)

// Near-black surfaces for the AMOLED variant (dark scheme with true-black background/surface).
val AmoledBackground = Color.Black
val AmoledSurface = Color.Black
val AmoledSurfaceContainerLowest = Color(0xFF000000)
val AmoledSurfaceContainerLow = Color(0xFF0A0A0A)
val AmoledSurfaceContainer = Color(0xFF101010)
val AmoledSurfaceContainerHigh = Color(0xFF161616)
val AmoledSurfaceContainerHighest = Color(0xFF1E1E1E)

// ---------------------------------------------------------------------------
// Terminal color schemes -- each gives a proper Material 3 ColorScheme.
// Colors taken from the canonical palette specs for each scheme.
// ---------------------------------------------------------------------------

// -- Gruvbox (Morhetz) -------------------------------------------------------
// Dark: bg/fg from gruvbox-dark, accents from orange/yellow primaries.
val GruvboxDarkScheme = darkColorScheme(
    primary = Color(0xFFD79921),      // gruvbox yellow
    onPrimary = Color(0xFF1D2021),
    primaryContainer = Color(0xFF3C3836),
    onPrimaryContainer = Color(0xFFEBDBB2),
    secondary = Color(0xFF689D6A),    // gruvbox aqua
    onSecondary = Color(0xFF1D2021),
    secondaryContainer = Color(0xFF282828),
    onSecondaryContainer = Color(0xFF8EC07C),
    tertiary = Color(0xFFD65D0E),     // gruvbox orange
    onTertiary = Color(0xFF1D2021),
    background = Color(0xFF1D2021),
    onBackground = Color(0xFFEBDBB2),
    surface = Color(0xFF282828),
    onSurface = Color(0xFFEBDBB2),
    surfaceVariant = Color(0xFF3C3836),
    onSurfaceVariant = Color(0xFFBDBDB1),
    outline = Color(0xFF665C54),
    surfaceContainerLowest = Color(0xFF1D2021),
    surfaceContainerLow = Color(0xFF282828),
    surfaceContainer = Color(0xFF32302F),
    surfaceContainerHigh = Color(0xFF3C3836),
    surfaceContainerHighest = Color(0xFF504945),
)

// Light: gruvbox-light variant
val GruvboxLightScheme = lightColorScheme(
    primary = Color(0xFF79740E),      // gruvbox-light yellow (darker for contrast)
    onPrimary = Color(0xFFFBF1C7),
    primaryContainer = Color(0xFFD5C4A1),
    onPrimaryContainer = Color(0xFF3C3836),
    secondary = Color(0xFF427B58),    // gruvbox-light aqua
    onSecondary = Color(0xFFFBF1C7),
    secondaryContainer = Color(0xFFD5C4A1),
    onSecondaryContainer = Color(0xFF3C3836),
    tertiary = Color(0xFFAF3A03),     // gruvbox-light orange
    onTertiary = Color(0xFFFBF1C7),
    background = Color(0xFFFBF1C7),
    onBackground = Color(0xFF3C3836),
    surface = Color(0xFFF2E5BC),
    onSurface = Color(0xFF3C3836),
    surfaceVariant = Color(0xFFEBDBB2),
    onSurfaceVariant = Color(0xFF665C54),
    outline = Color(0xFFBDAE93),
    surfaceContainerLowest = Color(0xFFFBF1C7),
    surfaceContainerLow = Color(0xFFF2E5BC),
    surfaceContainer = Color(0xFFEBDBB2),
    surfaceContainerHigh = Color(0xFFD5C4A1),
    surfaceContainerHighest = Color(0xFFBDAE93),
)

// -- Solarized (Ethan Schoonover) --------------------------------------------
// Dark: Solarized base0x as backgrounds, cyan/blue as primaries.
val SolarizedDarkScheme = darkColorScheme(
    primary = Color(0xFF268BD2),      // solarized blue
    onPrimary = Color(0xFF002B36),
    primaryContainer = Color(0xFF073642),
    onPrimaryContainer = Color(0xFF93A1A1),
    secondary = Color(0xFF2AA198),    // solarized cyan
    onSecondary = Color(0xFF002B36),
    secondaryContainer = Color(0xFF073642),
    onSecondaryContainer = Color(0xFF93A1A1),
    tertiary = Color(0xFF6C71C4),     // solarized violet
    onTertiary = Color(0xFF002B36),
    background = Color(0xFF002B36),
    onBackground = Color(0xFF839496),
    surface = Color(0xFF073642),
    onSurface = Color(0xFF839496),
    surfaceVariant = Color(0xFF073642),
    onSurfaceVariant = Color(0xFF657B83),
    outline = Color(0xFF586E75),
    surfaceContainerLowest = Color(0xFF002B36),
    surfaceContainerLow = Color(0xFF073642),
    surfaceContainer = Color(0xFF0D3E4C),
    surfaceContainerHigh = Color(0xFF1A4A57),
    surfaceContainerHighest = Color(0xFF2A5560),
)

// Light: Solarized uses the same palette with inverted base.
val SolarizedLightScheme = lightColorScheme(
    primary = Color(0xFF268BD2),
    onPrimary = Color(0xFFFDF6E3),
    primaryContainer = Color(0xFFEEE8D5),
    onPrimaryContainer = Color(0xFF073642),
    secondary = Color(0xFF2AA198),
    onSecondary = Color(0xFFFDF6E3),
    secondaryContainer = Color(0xFFEEE8D5),
    onSecondaryContainer = Color(0xFF073642),
    tertiary = Color(0xFF6C71C4),
    onTertiary = Color(0xFFFDF6E3),
    background = Color(0xFFFDF6E3),
    onBackground = Color(0xFF657B83),
    surface = Color(0xFFEEE8D5),
    onSurface = Color(0xFF586E75),
    surfaceVariant = Color(0xFFE9E2CF),
    onSurfaceVariant = Color(0xFF657B83),
    outline = Color(0xFF93A1A1),
    surfaceContainerLowest = Color(0xFFFDF6E3),
    surfaceContainerLow = Color(0xFFF5EFDA),
    surfaceContainer = Color(0xFFEEE8D5),
    surfaceContainerHigh = Color(0xFFE3DCC8),
    surfaceContainerHighest = Color(0xFFD8D1BB),
)

// -- Dracula -----------------------------------------------------------------
val DraculaScheme = darkColorScheme(
    primary = Color(0xFFBD93F9),      // dracula purple
    onPrimary = Color(0xFF282A36),
    primaryContainer = Color(0xFF44475A),
    onPrimaryContainer = Color(0xFFF8F8F2),
    secondary = Color(0xFF50FA7B),    // dracula green
    onSecondary = Color(0xFF282A36),
    secondaryContainer = Color(0xFF44475A),
    onSecondaryContainer = Color(0xFF8BE9FD),
    tertiary = Color(0xFFFF79C6),     // dracula pink
    onTertiary = Color(0xFF282A36),
    background = Color(0xFF282A36),
    onBackground = Color(0xFFF8F8F2),
    surface = Color(0xFF21222C),
    onSurface = Color(0xFFF8F8F2),
    surfaceVariant = Color(0xFF44475A),
    onSurfaceVariant = Color(0xFF6272A4),
    outline = Color(0xFF6272A4),
    surfaceContainerLowest = Color(0xFF191A21),
    surfaceContainerLow = Color(0xFF21222C),
    surfaceContainer = Color(0xFF282A36),
    surfaceContainerHigh = Color(0xFF343746),
    surfaceContainerHighest = Color(0xFF44475A),
)

// -- Nord (Arctic Ice Studio) ------------------------------------------------
val NordScheme = darkColorScheme(
    primary = Color(0xFF88C0D0),      // nord frost 1
    onPrimary = Color(0xFF2E3440),
    primaryContainer = Color(0xFF3B4252),
    onPrimaryContainer = Color(0xFFECEFF4),
    secondary = Color(0xFF81A1C1),    // nord frost 2
    onSecondary = Color(0xFF2E3440),
    secondaryContainer = Color(0xFF3B4252),
    onSecondaryContainer = Color(0xFFD8DEE9),
    tertiary = Color(0xFFB48EAD),     // nord aurora 5 (purple)
    onTertiary = Color(0xFF2E3440),
    background = Color(0xFF2E3440),
    onBackground = Color(0xFFECEFF4),
    surface = Color(0xFF3B4252),
    onSurface = Color(0xFFECEFF4),
    surfaceVariant = Color(0xFF434C5E),
    onSurfaceVariant = Color(0xFFD8DEE9),
    outline = Color(0xFF4C566A),
    surfaceContainerLowest = Color(0xFF242933),
    surfaceContainerLow = Color(0xFF2E3440),
    surfaceContainer = Color(0xFF3B4252),
    surfaceContainerHigh = Color(0xFF434C5E),
    surfaceContainerHighest = Color(0xFF4C566A),
)

// -- Catppuccin (Latte = light, Mocha = dark) --------------------------------
val CatppuccinLatteScheme = lightColorScheme(
    primary = Color(0xFF1E66F5),      // latte blue
    onPrimary = Color(0xFFEFF1F5),
    primaryContainer = Color(0xFFBCC0CC),
    onPrimaryContainer = Color(0xFF4C4F69),
    secondary = Color(0xFF04A5E5),    // latte sky
    onSecondary = Color(0xFFEFF1F5),
    secondaryContainer = Color(0xFFCDD0DA),
    onSecondaryContainer = Color(0xFF5C5F77),
    tertiary = Color(0xFFEA76CB),     // latte pink
    onTertiary = Color(0xFFEFF1F5),
    background = Color(0xFFEFF1F5),
    onBackground = Color(0xFF4C4F69),
    surface = Color(0xFFE6E9EF),
    onSurface = Color(0xFF4C4F69),
    surfaceVariant = Color(0xFFDCE0E8),
    onSurfaceVariant = Color(0xFF5C5F77),
    outline = Color(0xFF9CA0B0),
    surfaceContainerLowest = Color(0xFFEFF1F5),
    surfaceContainerLow = Color(0xFFE6E9EF),
    surfaceContainer = Color(0xFFDCE0E8),
    surfaceContainerHigh = Color(0xFFCDD0DA),
    surfaceContainerHighest = Color(0xFFBCC0CC),
)

val CatppuccinMochaScheme = darkColorScheme(
    primary = Color(0xFF89B4FA),      // mocha blue
    onPrimary = Color(0xFF1E1E2E),
    primaryContainer = Color(0xFF313244),
    onPrimaryContainer = Color(0xFFCDD6F4),
    secondary = Color(0xFF89DCEB),    // mocha sky
    onSecondary = Color(0xFF1E1E2E),
    secondaryContainer = Color(0xFF313244),
    onSecondaryContainer = Color(0xFFBAC2DE),
    tertiary = Color(0xFFF38BA8),     // mocha red/pink
    onTertiary = Color(0xFF1E1E2E),
    background = Color(0xFF1E1E2E),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF181825),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244),
    onSurfaceVariant = Color(0xFFBAC2DE),
    outline = Color(0xFF585B70),
    surfaceContainerLowest = Color(0xFF11111B),
    surfaceContainerLow = Color(0xFF181825),
    surfaceContainer = Color(0xFF1E1E2E),
    surfaceContainerHigh = Color(0xFF313244),
    surfaceContainerHighest = Color(0xFF45475A),
)

// -- Tokyo Night (enkia) -----------------------------------------------------
val TokyoNightScheme = darkColorScheme(
    primary = Color(0xFF7AA2F7),      // tokyo night blue
    onPrimary = Color(0xFF1A1B26),
    primaryContainer = Color(0xFF24283B),
    onPrimaryContainer = Color(0xFFC0CAF5),
    secondary = Color(0xFF7DCFFF),    // tokyo night cyan
    onSecondary = Color(0xFF1A1B26),
    secondaryContainer = Color(0xFF24283B),
    onSecondaryContainer = Color(0xFFBB9AF7),
    tertiary = Color(0xFFBB9AF7),     // tokyo night purple
    onTertiary = Color(0xFF1A1B26),
    background = Color(0xFF1A1B26),
    onBackground = Color(0xFFC0CAF5),
    surface = Color(0xFF16161E),
    onSurface = Color(0xFFC0CAF5),
    surfaceVariant = Color(0xFF24283B),
    onSurfaceVariant = Color(0xFFA9B1D6),
    outline = Color(0xFF414868),
    surfaceContainerLowest = Color(0xFF13131A),
    surfaceContainerLow = Color(0xFF16161E),
    surfaceContainer = Color(0xFF1A1B26),
    surfaceContainerHigh = Color(0xFF24283B),
    surfaceContainerHighest = Color(0xFF2F334D),
)
