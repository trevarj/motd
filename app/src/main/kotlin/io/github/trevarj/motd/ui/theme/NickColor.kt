package io.github.trevarj.motd.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

// Golden-ratio conjugate: spreads sequential hashes into well-separated hues.
private const val GOLDEN_RATIO_CONJUGATE = 0.618033988749895

/**
 * Deterministic per-nick color used for sender names and avatar backgrounds.
 *
 * The nick is hashed to a 0..1 seed, multiplied by the golden ratio conjugate and wrapped, which
 * scatters similar nicks across the hue wheel instead of clustering them. Saturation/lightness are
 * tuned per mode so text stays legible on the mode's surfaces.
 */
fun nickColor(nick: String, isDark: Boolean): Color {
    // Stable, case-insensitive hash so "Alice" and "alice" share a color.
    var hash = 0
    for (c in nick.lowercase()) {
        hash = hash * 31 + c.code
    }
    val seed = (abs(hash) % 1000) / 1000.0
    val hue = ((seed + GOLDEN_RATIO_CONJUGATE) % 1.0).toFloat() * 360f
    // Brighter, less-saturated on dark; deeper, more-saturated on light.
    val saturation = if (isDark) 0.55f else 0.65f
    val lightness = if (isDark) 0.68f else 0.42f
    return hslColor(hue, saturation, lightness)
}

/** HSL -> RGB Color (h in degrees, s/l in 0..1). */
private fun hslColor(h: Float, s: Float, l: Float): Color {
    val c = (1f - abs(2f * l - 1f)) * s
    val hp = h / 60f
    val x = c * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(c, x, 0f)
        hp < 2f -> Triple(x, c, 0f)
        hp < 3f -> Triple(0f, c, x)
        hp < 4f -> Triple(0f, x, c)
        hp < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val m = l - c / 2f
    return Color(r1 + m, g1 + m, b1 + m)
}
