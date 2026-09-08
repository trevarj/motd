package io.github.trevarj.motd.data.prefs

import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.service.DeliveryMode
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED,

    // Terminal color schemes (light + dark where the scheme has both).
    GRUVBOX_DARK,
    GRUVBOX_LIGHT,
    SOLARIZED_DARK,
    SOLARIZED_LIGHT,
    DRACULA,
    NORD,
    CATPPUCCIN_LATTE,
    CATPPUCCIN_MOCHA,
    TOKYO_NIGHT,
}

// Round 4: user-customizable UI settings.
enum class LayoutDensity { COMPACT, COMFORTABLE, TWO_LINE }

enum class NickColorPalette { THEME, CLASSIC, VIVID }

enum class FoolsMode { COLLAPSE, HIDE }

enum class FolderDisplayMode { INLINE, TABS }

/** Decode folder presentation without letting unknown future values break startup. */
internal fun folderDisplayModeFromPreference(saved: String?): FolderDisplayMode = saved?.let { runCatching { FolderDisplayMode.valueOf(it) }.getOrNull() } ?: FolderDisplayMode.INLINE

/**
 * How presence events (join/part/quit and nick changes) are presented in a conversation.
 *
 * [SMART] is the default: a presence row is shown only when that user actually took part in the
 * conversation, defined as having sent a message in the same room within [SMART_PRESENCE_WINDOW_MS]
 * before the event. Your own presence rows always survive, so "you joined" still anchors a fresh
 * buffer. This mirrors Halloy's `server_messages.smart` and removes the bulk of large-channel noise
 * without losing the events that carry meaning.
 *
 * Netsplit/netjoin rows are aggregates covering many users at once, so they are already condensed to
 * a single row and are not subject to the smart test; only [HIDDEN] removes them.
 */
enum class PresenceMode { ALL, SMART, HIDDEN }

/** Window a user's last message keeps their presence events visible under [PresenceMode.SMART]. */
const val SMART_PRESENCE_WINDOW_MS: Long = 5 * 60 * 1000

/**
 * Decode the stored presence preference. Installations predating this setting carry the former
 * `show_join_part_quit` boolean instead: an explicit `false` stays a full hide, an explicit `true`
 * stays "show everything", and no stored choice at all adopts the new smart default.
 */
internal fun presenceModeFromPreference(
    saved: String?,
    legacyShowJoinPartQuit: String?,
): PresenceMode =
    saved?.let { runCatching { PresenceMode.valueOf(it) }.getOrNull() }
        ?: when (legacyShowJoinPartQuit?.toBooleanStrictOrNull()) {
            false -> PresenceMode.HIDDEN
            true -> PresenceMode.ALL
            null -> PresenceMode.SMART
        }

/** Decode saved palettes, migrating both former defaults to the theme-derived default. */
internal fun nickColorPaletteFromPreference(saved: String?): NickColorPalette =
    when (saved) {
        "CLASSIC" -> NickColorPalette.CLASSIC
        "VIVID" -> NickColorPalette.VIVID
        "THEME", "DEFAULT", "PASTEL", null -> NickColorPalette.THEME
        else -> NickColorPalette.THEME
    }

/**
 * How far back the first history sync of a network enumerates. A bounded window keeps onboarding
 * responsive on a large bouncer account; EVERYTHING enumerates from epoch in one pass, so nothing
 * is left for the paced backfill.
 */
enum class HistorySyncDepth(
    val lookbackMs: Long?,
) {
    WEEK(7L * 24 * 60 * 60 * 1_000),
    MONTH(30L * 24 * 60 * 60 * 1_000),
    QUARTER(90L * 24 * 60 * 60 * 1_000),
    EVERYTHING(null),
}

/**
 * Background delays offered for auto-away, in minutes. The stored value is coerced onto this list so
 * a hand-edited or future-build preference can never arm an unexpected timer.
 */
val AUTO_AWAY_MINUTE_CHOICES: List<Int> = listOf(1, 5, 10, 15, 30, 60)

/** Default auto-away delay: the choice used until the user picks another one. */
const val DEFAULT_AUTO_AWAY_MINUTES: Int = 10

/** Snap a stored/incoming delay onto [AUTO_AWAY_MINUTE_CHOICES]; anything unknown takes the default. */
internal fun autoAwayMinutesFromPreference(saved: Int?): Int = saved?.takeIf { it in AUTO_AWAY_MINUTE_CHOICES } ?: DEFAULT_AUTO_AWAY_MINUTES

/**
 * Which visual style to use for nick avatars. IRC sprites are the default for new users; [NONE]
 * hides avatars in the UI entirely (notifications still need an icon, so they fall back to
 * initials).
 */
enum class AvatarStyle { MONOGRAM, INITIALS, IRC_SPRITE, NONE, IRC_SPRITE_V2 }

/** Decode a saved choice while defaulting installations without one to IRC sprites. */
internal fun avatarStyleFromPreference(saved: String?): AvatarStyle = saved?.let { runCatching { AvatarStyle.valueOf(it) }.getOrNull() } ?: AvatarStyle.IRC_SPRITE

/** Subtle IRC-themed chat background rendered behind the message list. NONE keeps the plain
 *  theme background (opt-in; default for existing users). */
enum class ChatWallpaper { NONE, CLASSIC, NETWORK, PIXEL }

/** True for terminal color-scheme variants (dynamic color does not apply to these). */
val ThemeMode.isTerminalTheme: Boolean
    get() =
        when (this) {
            ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.AMOLED -> false
            else -> true
        }

@Serializable
data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val deliveryMode: DeliveryMode = DeliveryMode.PERSISTENT_SOCKET,
    // Round 4
    val layoutDensity: LayoutDensity = LayoutDensity.COMFORTABLE,
    val nickColorsEnabled: Boolean = true,
    val nickColorPalette: NickColorPalette = NickColorPalette.THEME,
    /** Normalized nick -> hue/position 0..359, rendered through the active palette. */
    val nickColorOverrides: Map<String, Int> = emptyMap(),
    /** Normalized nicks. friends and fools are kept disjoint by the repository. */
    val friends: Set<String> = emptySet(),
    val fools: Set<String> = emptySet(),
    val foolsMode: FoolsMode = FoolsMode.COLLAPSE,
    /** Global presence-event presentation; a conversation may override it (RoomEntity). */
    val presenceMode: PresenceMode = PresenceMode.SMART,
    /** Keep visible accountability tombstones for messages deleted through IRCv3 redaction. */
    val showRedactedMessages: Boolean = true,
    /**
     * Backup compatibility only, never populated at runtime. Archives written before presence modes
     * existed carry the former boolean here; restore maps it onto [presenceMode] (see
     * ConfigurationBackup). Newer archives omit it entirely.
     */
    val showJoinPartQuit: Boolean? = null,
    /** Avatar rendering style; explicit saved choices override the IRC-sprite default. */
    val avatarStyle: AvatarStyle = AvatarStyle.IRC_SPRITE,
    /** Opt-in patterned chat background; NONE preserves the existing plain theme background. */
    val chatWallpaper: ChatWallpaper = ChatWallpaper.NONE,
    /** Show Emoji inside the shared composer tools strip. */
    val showComposerEmoji: Boolean = true,
    /** Show IRC formatting actions in the compact composer tools strip. */
    val showComposerFormattingTools: Boolean = true,
    /** Play subtle send/receive sounds for the currently open foreground chat. */
    val chatSoundsEnabled: Boolean = true,
    /** Window the first history sync of a network enumerates; chosen during soju onboarding. */
    val historySyncDepth: HistorySyncDepth = HistorySyncDepth.MONTH,
    /** Mark yourself away on every connected network while the app stays in the background. */
    val autoAwayEnabled: Boolean = false,
    /** How long the app must stay backgrounded before auto-away fires (minutes). */
    val autoAwayMinutes: Int = DEFAULT_AUTO_AWAY_MINUTES,
    /** Away text written by auto-away; blank means "use the localized default". */
    val autoAwayMessage: String = "",
    /** Present chat folders as expandable inline sections or a tab strip. */
    val folderDisplayMode: FolderDisplayMode = FolderDisplayMode.INLINE,
    /** Include chats assigned to folders in the All tab. */
    val showFolderChatsInAll: Boolean = true,
)

/** Canonical key for friends/fools/override lookups: trimmed + lowercased.
 *  Deliberate simplification of RFC 1459 casemapping. */
fun normalizeNick(nick: String): String = nick.trim().lowercase()

/** Compare stored preference entries using one network's advertised IRC identity rules. */
fun IrcIdentityRules.matchesConfiguredNick(
    nick: String,
    configuredNicks: Set<String>,
): Boolean {
    if (configuredNicks.isEmpty()) return false
    val normalizedNick = normalize(nick.trim())
    return configuredNicks.any { normalize(it.trim()) == normalizedNick }
}

interface SettingsRepository {
    val settings: Flow<Settings>

    suspend fun setThemeMode(m: ThemeMode)

    suspend fun setDynamicColor(enabled: Boolean)

    suspend fun setDeliveryMode(m: DeliveryMode)

    // Round 4
    suspend fun setLayoutDensity(d: LayoutDensity)

    suspend fun setFolderDisplayMode(mode: FolderDisplayMode) {}

    suspend fun setShowFolderChatsInAll(enabled: Boolean) {}

    suspend fun setNickColorsEnabled(enabled: Boolean)

    suspend fun setNickColorPalette(p: NickColorPalette)

    /** hue 0..359 (coerced); null removes. [nick] is normalized internally. */
    suspend fun setNickColorOverride(
        nick: String,
        hue: Int?,
    )

    /** Adding a friend removes the nick from fools, and vice versa. */
    suspend fun setFriend(
        nick: String,
        isFriend: Boolean,
    )

    suspend fun setFool(
        nick: String,
        isFool: Boolean,
    )

    /** Rules-aware variants remove the actual equivalent stored entries in one transaction. */
    suspend fun setFriend(
        nick: String,
        isFriend: Boolean,
        identityRules: IrcIdentityRules,
    ) {
        setFriend(nick, isFriend)
    }

    suspend fun setFool(
        nick: String,
        isFool: Boolean,
        identityRules: IrcIdentityRules,
    ) {
        setFool(nick, isFool)
    }

    suspend fun setFoolsMode(m: FoolsMode)

    suspend fun setPresenceMode(m: PresenceMode)

    suspend fun setShowRedactedMessages(show: Boolean) {}

    suspend fun setAvatarStyle(style: AvatarStyle)

    suspend fun setChatWallpaper(w: ChatWallpaper)

    suspend fun setShowComposerEmoji(show: Boolean)

    suspend fun setShowComposerFormattingTools(show: Boolean)

    suspend fun setChatSoundsEnabled(enabled: Boolean)

    suspend fun setHistorySyncDepth(d: HistorySyncDepth)

    suspend fun setAutoAwayEnabled(enabled: Boolean)

    /** [minutes] is coerced onto [AUTO_AWAY_MINUTE_CHOICES]. */
    suspend fun setAutoAwayMinutes(minutes: Int)

    /** Blank keeps the localized default message. */
    suspend fun setAutoAwayMessage(message: String)
}

/** Webpush endpoint + client keypair persistence (DataStore). Implemented by WP4 alongside
 *  SettingsRepository; consumed by WP9. All values base64url. */
data class PushKeys(
    val privateKey: String,
    val publicUncompressed: String,
    val auth: String,
)

interface PushPrefs {
    // Per-network endpoints (keyed by network row id).
    suspend fun endpoints(): Map<Long, String>

    suspend fun endpointFor(networkId: Long): String?

    suspend fun setEndpointFor(networkId: Long, endpoint: String?) // null removes

    suspend fun clearEndpoints()

    suspend fun keys(): PushKeys? // one shared keypair

    suspend fun setKeys(keys: PushKeys)
}

/**
 * TOFU cert pins for self-signed / bare-IP TLS bouncers. Persists the accepted leaf
 * SHA-256 (lowercase hex) per host:port. A pinned host skips CA/hostname validation; a pin mismatch
 * later triggers a change warning. DataStore key `cert_pins` = JSON `{"host:port":"<sha256 hex>"}`.
 */
interface CertTrustStore {
    /** Pinned lowercase-hex SHA-256 for [host]:[port], or null when unpinned. */
    suspend fun pinnedFor(
        host: String,
        port: Int,
    ): String?

    /** True when [sha256] (case-insensitive) matches the pin for [host]:[port]. */
    suspend fun isPinned(
        host: String,
        port: Int,
        sha256: String,
    ): Boolean

    /** Pin (or re-pin) [sha256] for [host]:[port]; stored lowercase. */
    suspend fun pin(
        host: String,
        port: Int,
        sha256: String,
    )

    /** Remove any pin for [host]:[port]. */
    suspend fun unpin(
        host: String,
        port: Int,
    )
}
