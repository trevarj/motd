package io.github.trevarj.motd.ui.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.trevarj.motd.profile.IrcProfileRepository
import io.github.trevarj.motd.profile.ProfileKey
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal val LocalIrcProfileRepository = staticCompositionLocalOf<IrcProfileRepository?> { null }
internal val LocalProfileDisplayNameFirst = compositionLocalOf { false }

@Composable
internal fun rememberIrcProfile(
    networkId: Long?,
    target: String,
): Map<ProfileKey, String> {
    if (networkId == null || target.isBlank()) return emptyMap()
    val repository = LocalIrcProfileRepository.current ?: return emptyMap()
    // A different identity must not inherit the previous collector's last value.
    return key(repository, networkId, target) {
        val profile = remember { repository.observe(networkId, target) }
        profile.collectAsStateWithLifecycle(initialValue = emptyMap()).value
    }
}

@Composable
internal fun ircIdentityLabels(
    identity: String,
    displayName: String?,
): Pair<String, String?> = orderedIrcIdentityLabels(identity, displayName, LocalProfileDisplayNameFirst.current)

internal fun orderedIrcIdentityLabels(
    identity: String,
    displayName: String?,
    displayNameFirst: Boolean,
): Pair<String, String?> {
    val name = displayName?.trim()?.takeIf { it.isNotEmpty() && it != identity } ?: return identity to null
    return if (displayNameFirst) name to identity else identity to name
}

@Composable
internal fun ircIdentityLabel(
    identity: String,
    displayName: String?,
): String {
    val name = displayName?.trim()?.takeIf { it.isNotEmpty() && it != identity } ?: return identity
    val shortened = compactIrcDisplayName(name)
    return if (LocalProfileDisplayNameFirst.current) "$shortened ($identity)" else "$identity ($shortened)"
}

@Composable
internal fun ircProfileIdentityLabel(
    networkId: Long?,
    identity: String,
): String = ircIdentityLabel(identity, rememberIrcProfile(networkId, identity)[ProfileKey.DISPLAY_NAME])

// Single-line consumers reserve most of the label for the IRC identity, even with long metadata.
internal fun compactIrcDisplayName(name: String): String {
    val limit = 16
    if (name.length <= limit) return name
    if (name.codePointCount(0, name.length) <= limit) return name
    return name.substring(0, name.offsetByCodePoints(0, limit - 1)) + '…'
}

internal fun safeProfileHomepage(value: String): String? {
    if (value.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return null
    val parsed = value.toHttpUrlOrNull() ?: return null
    val authority = value.substringAfter("://", "").takeWhile { it != '/' && it != '?' && it != '#' }
    return parsed.toString().takeIf { authority.isNotEmpty() && '@' !in authority }
}
