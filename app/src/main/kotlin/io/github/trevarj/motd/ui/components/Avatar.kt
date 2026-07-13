package io.github.trevarj.motd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.avatar.AvatarRecord
import io.github.trevarj.motd.avatar.avatarIdentity
import io.github.trevarj.motd.avatar.canonicalAvatarNick
import io.github.trevarj.motd.avatar.expandAvatarUrl
import io.github.trevarj.motd.ui.theme.LocalAvatarStyle
import io.github.trevarj.motd.ui.theme.LocalNickColors
import io.github.trevarj.motd.ui.theme.MotdTheme
import coil.compose.AsyncImage

data class RemoteAvatarState(
    val enabled: Boolean = false,
    val records: List<AvatarRecord> = emptyList(),
) {
    private val byNetworkIdentity by lazy(LazyThreadSafetyMode.NONE) {
        records.associateBy { it.networkId to it.identity }
    }
    private val byNetworkNick by lazy(LazyThreadSafetyMode.NONE) {
        records.associateBy { it.networkId to it.nick }
    }
    private val unambiguousByIdentity by lazy(LazyThreadSafetyMode.NONE) {
        records.groupBy { it.identity }.mapValues { (_, matches) ->
            matches.distinctBy { it.url }.singleOrNull()
        }
    }
    private val unambiguousByNick by lazy(LazyThreadSafetyMode.NONE) {
        records.groupBy { it.nick }.mapValues { (_, matches) ->
            matches.distinctBy { it.url }.singleOrNull()
        }
    }

    fun url(networkId: Long?, name: String, account: String?, sizePx: Int): String? {
        if (!enabled) return null
        val identity = avatarIdentity(name, account)
        val normalizedNick = canonicalAvatarNick(name)
        val record = if (networkId != null) {
            byNetworkIdentity[networkId to identity] ?: byNetworkNick[networkId to normalizedNick]
        } else {
            // Global friends/fools management has no network context. Use a remote image only when
            // every matching network agrees on one URL; ambiguity falls back to the monogram.
            unambiguousByIdentity[identity] ?: unambiguousByNick[normalizedNick]
        }
        return record?.url?.let { expandAvatarUrl(it, sizePx) }
    }
}

val LocalRemoteAvatars = staticCompositionLocalOf { RemoteAvatarState() }

/**
 * Darkness of the *applied* theme, derived from the resolved background luminance rather than
 * [isSystemInDarkTheme] -- so forced DARK/AMOLED read as dark even when the OS is light (plans/15
 * #22). Used to pick nick-color palettes and on-color contrast across the chat components.
 */
@Composable
@ReadOnlyComposable
internal fun isAppliedThemeDark(): Boolean =
    MaterialTheme.colorScheme.background.luminance() < 0.5f

/** Pick a legible on-color (black/white) for text/initials over [bg] by its luminance. */
internal fun onColorFor(bg: Color): Color =
    if (bg.luminance() < 0.5f) Color.White else Color.Black

/**
 * Circular nick avatar. [AvatarStyle.MONOGRAM] (default) is a quiet theme-tinted disc with a single
 * initial; [AvatarStyle.INITIALS] is the bolder solid nick-color chip with two initials. Both take
 * their identity color from [name] via the current LocalNickColors scheme.
 *
 * [isChannel] uses the name as-is (channels keep the leading `#`); queries fall back to their nick.
 */
@Composable
fun Avatar(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    isChannel: Boolean = false,
    networkId: Long? = null,
    account: String? = null,
) {
    // Avatars keep their generated/override color even when nick coloring is disabled (an all-gray
    // avatar column would be unusable, plans/13 confirmed decision #5); avatar() ignores the flag.
    val nick = LocalNickColors.current.avatar(name)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        when (LocalAvatarStyle.current) {
            AvatarStyle.MONOGRAM -> MonogramAvatar(name, nick, size, isChannel, Modifier)
            AvatarStyle.INITIALS -> InitialsAvatar(name, nick, size, isChannel, Modifier)
        }
        LocalRemoteAvatars.current.url(networkId, name, account, size.value.toInt())?.let { url ->
            // The deterministic monogram stays underneath, so failed/cancelled loads fall back
            // without erasing valid metadata or flashing an empty avatar.
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
            )
        }
    }
}

/**
 * Tonal monogram: a circle filled with the theme's raised surface gently washed with the nick color
 * (a quiet top-to-bottom sheen), a hairline nick-tinted ring, and one initial in a theme-safe tint of
 * the nick color. Everything derives from [MaterialTheme.colorScheme] + [isAppliedThemeDark], so it
 * adapts to light/dark/AMOLED and the terminal palettes with no hardcoded colors. The letter carries
 * the color identity; the disc stays mostly neutral, so the avatar column reads calm rather than busy.
 */
@Composable
private fun MonogramAvatar(name: String, nick: Color, size: Dp, isChannel: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val dark = isAppliedThemeDark()
    val base = scheme.surfaceContainerHigh
    // compositeOver keeps the disc opaque: a raw low-alpha tint would let the row background bleed
    // through and make the circle muddy on textured/wallpapered backgrounds.
    val top = nick.copy(alpha = if (dark) 0.28f else 0.18f).compositeOver(base)
    val bottom = nick.copy(alpha = if (dark) 0.12f else 0.07f).compositeOver(base)
    // Pull the glyph toward the theme's guaranteed-contrast on-color while keeping the hue identity,
    // which fixes borderline pastel-on-light without any contrast-ratio math.
    val glyph = lerp(nick, scheme.onSurface, 0.30f)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            // The hairline ring is what defines the circle on AMOLED (disc barely above true black);
            // do not drop it.
            .border(1.dp, nick.copy(alpha = 0.40f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            // A single glyph stays crisp down to 24dp (autocomplete) where two chars would smear.
            text = initials(name, isChannel).take(1),
            color = glyph,
            fontWeight = FontWeight.Medium,
            fontSize = (size.value * 0.42f).sp,
        )
    }
}

/** The bolder alternate: two initials over the solid, saturated nick color (the original style). */
@Composable
private fun InitialsAvatar(name: String, bg: Color, size: Dp, isChannel: Boolean, modifier: Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(name, isChannel),
            // Contrast the initials against the (possibly light) nick-color background (plans/15 #23).
            color = onColorFor(bg),
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.4f).sp,
        )
    }
}

/** First two significant characters. Skips channel sigils and non-letter/digit leading chars. */
private fun initials(name: String, isChannel: Boolean): String {
    val stripped = name.trimStart('#', '&', '@', '+', '~', '%', '!').ifEmpty { name }
    val words = stripped.split(' ', '-', '_', '.').filter { it.isNotBlank() }
    val chars = when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        stripped.length >= 2 -> stripped.take(2)
        else -> stripped.take(1).ifEmpty { "?" }
    }
    return chars.uppercase()
}

@Preview
@Composable
private fun AvatarMonogramPreview() {
    MotdTheme {
        Avatar(name = "alice")
    }
}

@Preview
@Composable
private fun AvatarInitialsPreview() {
    MotdTheme(avatarStyle = AvatarStyle.INITIALS) {
        Avatar(name = "alice")
    }
}

@Preview
@Composable
private fun AvatarChannelPreview() {
    MotdTheme {
        Avatar(name = "#libera", isChannel = true)
    }
}
