package io.github.trevarj.motd.data.prefs

import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.flow.Flow

enum class ThemeMode {
    SYSTEM, LIGHT, DARK, AMOLED,
    // Terminal color schemes (light + dark where the scheme has both).
    GRUVBOX_DARK, GRUVBOX_LIGHT,
    SOLARIZED_DARK, SOLARIZED_LIGHT,
    DRACULA,
    NORD,
    CATPPUCCIN_LATTE, CATPPUCCIN_MOCHA,
    TOKYO_NIGHT,
}

// Round 4 (plans/13): user-customizable UI settings.
enum class LayoutDensity { COMPACT, COMFORTABLE, TWO_LINE }
enum class NickColorPalette { DEFAULT, VIVID, PASTEL }
enum class FoolsMode { COLLAPSE, HIDE }

/** Which visual style to use for nick avatars. */
enum class AvatarStyle { MONOGRAM, INITIALS }

/** Subtle IRC-themed chat background rendered behind the message list. NONE keeps the plain
 *  theme background (opt-in; default for existing users). */
enum class ChatWallpaper { NONE, CLASSIC, NETWORK, PIXEL }

/** True for terminal color-scheme variants (dynamic color does not apply to these). */
val ThemeMode.isTerminalTheme: Boolean
    get() = when (this) {
        ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED -> false
        else -> true
    }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val deliveryMode: DeliveryMode = DeliveryMode.PERSISTENT_SOCKET,
    // Round 4 (plans/13)
    val layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    val nickColorsEnabled: Boolean = true,
    val nickColorPalette: NickColorPalette = NickColorPalette.DEFAULT,
    /** Normalized nick -> hue 0..359. Rendered with the active palette's S/L. */
    val nickColorOverrides: Map<String, Int> = emptyMap(),
    /** Normalized nicks. friends and fools are kept disjoint by the repository. */
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
    val foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    val showJoinPartQuit: Boolean = true,
    /** Avatar rendering style; defaults to the tonal monogram disc. */
    val avatarStyle: AvatarStyle = AvatarStyle.MONOGRAM,
    /** Opt-in patterned chat background; NONE preserves the existing plain theme background. */
    val chatWallpaper: ChatWallpaper = ChatWallpaper.NONE,
    /** Show the emoji (Mood) button in the composer; users who never use it can hide it. */
    val showComposerEmoji: Boolean = true,
)

/** Canonical key for friends/fools/override lookups: trimmed + lowercased.
 *  Deliberate simplification of RFC 1459 casemapping (see plans/13 Risks). */
fun normalizeNick(nick: String): String = nick.trim().lowercase()

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun setThemeMode(m: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setDeliveryMode(m: DeliveryMode)
    // Round 4
    suspend fun setLayoutDensity(d: LayoutDensity)
    suspend fun setNickColorsEnabled(enabled: Boolean)
    suspend fun setNickColorPalette(p: NickColorPalette)
    /** hue 0..359 (coerced); null removes. [nick] is normalized internally. */
    suspend fun setNickColorOverride(nick: String, hue: Int?)
    /** Adding a friend removes the nick from fools, and vice versa. */
    suspend fun setFriend(nick: String, isFriend: Boolean)
    suspend fun setFool(nick: String, isFool: Boolean)
    suspend fun setFoolsMode(m: FoolsMode)
    suspend fun setShowJoinPartQuit(show: Boolean)
    suspend fun setAvatarStyle(style: AvatarStyle)
    suspend fun setChatWallpaper(w: ChatWallpaper)
    suspend fun setShowComposerEmoji(show: Boolean)
}

/** Webpush endpoint + client keypair persistence (DataStore). Implemented by WP4 alongside
 *  SettingsRepository; consumed by WP9. All values base64url. */
data class PushKeys(val privateKey: String, val publicUncompressed: String, val auth: String)

interface PushPrefs {
    // Per-network endpoints (keyed by network row id).
    suspend fun endpoints(): Map<Long, String>
    suspend fun endpointFor(networkId: Long): String?
    suspend fun setEndpointFor(networkId: Long, endpoint: String?)  // null removes
    suspend fun clearEndpoints()

    suspend fun keys(): PushKeys?                      // one shared keypair
    suspend fun setKeys(keys: PushKeys)
}

/**
 * TOFU cert pins for self-signed / bare-IP TLS bouncers (plans/12). Persists the accepted leaf
 * SHA-256 (lowercase hex) per host:port. A pinned host skips CA/hostname validation; a pin mismatch
 * later triggers a change warning. DataStore key `cert_pins` = JSON `{"host:port":"<sha256 hex>"}`.
 */
interface CertTrustStore {
    /** Pinned lowercase-hex SHA-256 for [host]:[port], or null when unpinned. */
    suspend fun pinnedFor(host: String, port: Int): String?

    /** True when [sha256] (case-insensitive) matches the pin for [host]:[port]. */
    suspend fun isPinned(host: String, port: Int, sha256: String): Boolean

    /** Pin (or re-pin) [sha256] for [host]:[port]; stored lowercase. */
    suspend fun pin(host: String, port: Int, sha256: String)

    /** Remove any pin for [host]:[port]. */
    suspend fun unpin(host: String, port: Int)
}
