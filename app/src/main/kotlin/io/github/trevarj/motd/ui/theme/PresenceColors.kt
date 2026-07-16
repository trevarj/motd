package io.github.trevarj.motd.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Semantic online colors independent of each decorative theme's primary hue. The light variant is
 * dark enough for pale surfaces; the dark variant stays vivid on dark and AMOLED backgrounds.
 */
internal val PresenceOnlineLight = Color(0xFF147D3F)
internal val PresenceOnlineDark = Color(0xFF6DD58C)

internal fun presenceOnlineColor(dark: Boolean): Color =
    if (dark) PresenceOnlineDark else PresenceOnlineLight
