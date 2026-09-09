package io.github.trevarj.motd.ui.theme

import android.graphics.Color

private const val LUMA_RED = 0.2126f
private const val LUMA_GREEN = 0.7152f
private const val LUMA_BLUE = 0.0722f
private const val CERAMIC_SHADE_STRENGTH = 0.8f

/**
 * Maps the grayscale matte-ceramic logo to [targetArgb] without flattening its baked-in shading.
 *
 * The source's darkest ceramic ink is the target color. The lighter source tones either move toward
 * white for dark targets or toward black for light targets, matching the selected artwork's
 * opposite-polarity treatments. Alpha passes through unchanged, including the anti-aliased logo
 * edge and slash cutout.
 */
internal fun ceramicLogoColorMatrix(targetArgb: Int): FloatArray {
    val targetChannels =
        floatArrayOf(
            Color.red(targetArgb).toFloat(),
            Color.green(targetArgb).toFloat(),
            Color.blue(targetArgb).toFloat(),
        )
    val targetLuminance =
        (targetChannels[0] * LUMA_RED + targetChannels[1] * LUMA_GREEN + targetChannels[2] * LUMA_BLUE) / 255f
    val lightTarget = targetLuminance >= 0.5f
    val rows = FloatArray(20)
    for (channel in targetChannels.indices) {
        val target = targetChannels[channel]
        val range =
            if (lightTarget) {
                -target * CERAMIC_SHADE_STRENGTH
            } else {
                (255f - target) * CERAMIC_SHADE_STRENGTH
            }
        val offset = channel * 5
        rows[offset] = range * LUMA_RED / 255f
        rows[offset + 1] = range * LUMA_GREEN / 255f
        rows[offset + 2] = range * LUMA_BLUE / 255f
        rows[offset + 4] = target
    }
    rows[18] = 1f
    return rows
}
