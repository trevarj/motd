package io.github.trevarj.motd.ui.channelinfo

/**
 * Pure ISUPPORT-derived channel mode catalog. No Android/IRC deps (like [Moderation.kt]), so the
 * server-derived half of the operator tools is unit-testable in isolation.
 *
 * `Isupport` only types CASEMAPPING/CHANTYPES/PREFIX; CHANMODES, EXCEPTS, INVEX, MAXLIST, MODES,
 * KICKLEN and TOPICLEN are reachable only as raw 005 tokens, which is what this parses.
 *
 * The catalog says which modes a network *advertises*, not which are set in a channel.
 * Active bans are queried separately.
 */

/** A PREFIX entry: the mode letter and the glyph it puts in front of a nick (e.g. `o` and `@`). */
data class PrefixRole(
    val mode: Char,
    val glyph: Char,
)

data class ModeCatalog(
    /** PREFIX roles, most privileged first. */
    val prefixRoles: List<PrefixRole>,
    /** CHANMODES group A: list modes (`b`, `e`, `I`). Argument on both set and unset. */
    val listModes: Set<Char>,
    /** CHANMODES group B: argument on both set and unset (`k`). */
    val paramModes: Set<Char>,
    /** CHANMODES group C: argument on set only (`l`). */
    val setParamModes: Set<Char>,
    /** CHANMODES group D: no argument (`imnpst`). */
    val flagModes: Set<Char>,
    /** EXCEPTS letter, or null when the network does not advertise ban exceptions at all. */
    val banExceptionChar: Char?,
    /** INVEX letter, or null when the network does not advertise invite exceptions at all. */
    val inviteExceptionChar: Char?,
    /** MAXLIST expanded per letter, so `beI:60` and `b:60,e:30` read the same way. */
    val maxList: Map<Char, Int>,
    val kickLen: Int?,
    val topicLen: Int?,
    /** MODES: how many mode changes the server accepts on one line. */
    val maxModesPerLine: Int?,
) {
    /** True when [letter] takes an argument when *set* (groups A, B and C, plus prefix roles). */
    fun needsValueOnSet(letter: Char): Boolean =
        letter in listModes || letter in paramModes || letter in setParamModes ||
            prefixRoles.any { it.mode == letter }

    /** True when [letter] appears in no advertised group at all (so we cannot say anything useful). */
    fun isUnknown(letter: Char): Boolean = !needsValueOnSet(letter) && letter !in flagModes

    companion object {
        /** RFC 1459/2812 groups, used when a server omits or mangles CHANMODES. */
        private const val DEFAULT_LIST_MODES = "b"
        private const val DEFAULT_PARAM_MODES = "k"
        private const val DEFAULT_SET_PARAM_MODES = "l"
        private const val DEFAULT_FLAG_MODES = "imnpst"

        /** PREFIX fallback when a server omits or mangles the token. */
        private val DEFAULT_PREFIX_ROLES = listOf(PrefixRole('o', '@'), PrefixRole('v', '+'))

        /** What a client knows before (or without) any 005: the RFC baseline, no EXCEPTS/INVEX. */
        val DEFAULT: ModeCatalog = from(emptyMap())

        fun from(isupport: Map<String, String>): ModeCatalog {
            // 005 keys are case-insensitive on the wire; normalize so callers can pass either.
            val tokens = isupport.entries.associate { (key, value) -> key.uppercase() to value }
            val groups = chanModeGroups(tokens["CHANMODES"])
            return ModeCatalog(
                prefixRoles = prefixRoles(tokens["PREFIX"]),
                listModes = groups[0],
                paramModes = groups[1],
                setParamModes = groups[2],
                flagModes = groups[3],
                // An absent token means the network has no such list; the row must be absent, not
                // disabled. An advertised-but-empty value means "the usual letter".
                banExceptionChar = tokens["EXCEPTS"]?.let { it.firstOrNull() ?: 'e' },
                inviteExceptionChar = tokens["INVEX"]?.let { it.firstOrNull() ?: 'I' },
                maxList = maxList(tokens["MAXLIST"]),
                kickLen = tokens["KICKLEN"]?.toIntOrNull()?.takeIf { it > 0 },
                topicLen = tokens["TOPICLEN"]?.toIntOrNull()?.takeIf { it > 0 },
                maxModesPerLine = tokens["MODES"]?.toIntOrNull()?.takeIf { it > 0 },
            )
        }

        /**
         * CHANMODES is four comma-separated groups. Groups past the fourth are a server extension
         * this app has no meaning for, so they are ignored rather than guessed at. Anything that is
         * not four groups is malformed and falls back to the RFC defaults, because a partial split
         * would silently mislabel which modes take an argument.
         */
        private fun chanModeGroups(raw: String?): List<Set<Char>> {
            val parts = raw?.takeIf { it.isNotBlank() }?.split(',')
            if (parts == null || parts.size < 4) {
                return listOf(
                    DEFAULT_LIST_MODES.toSet(),
                    DEFAULT_PARAM_MODES.toSet(),
                    DEFAULT_SET_PARAM_MODES.toSet(),
                    DEFAULT_FLAG_MODES.toSet(),
                )
            }
            return parts.take(4).map { group -> group.filter(Char::isLetter).toSet() }
        }

        /** PREFIX=(ov)@+ -> [o/@, v/+]. Absent or malformed falls back to the o/@ + v/+ baseline. */
        private fun prefixRoles(raw: String?): List<PrefixRole> {
            if (raw.isNullOrBlank() || !raw.startsWith("(")) return DEFAULT_PREFIX_ROLES
            val close = raw.indexOf(')')
            if (close < 0) return DEFAULT_PREFIX_ROLES
            val modes = raw.substring(1, close)
            val glyphs = raw.substring(close + 1)
            val n = minOf(modes.length, glyphs.length)
            if (n == 0) return DEFAULT_PREFIX_ROLES
            return (0 until n).map { PrefixRole(modes[it], glyphs[it]) }
        }

        /** MAXLIST=beI:60 and MAXLIST=b:60,e:30 both expand to a per-letter limit. */
        private fun maxList(raw: String?): Map<Char, Int> {
            if (raw.isNullOrBlank()) return emptyMap()
            val limits = LinkedHashMap<Char, Int>()
            for (entry in raw.split(',')) {
                val colon = entry.lastIndexOf(':')
                if (colon <= 0) continue
                val limit = entry.substring(colon + 1).toIntOrNull() ?: continue
                entry.substring(0, colon).filter(Char::isLetter).forEach { limits[it] = limit }
            }
            return limits
        }
    }
}

/** A single advisory about a letter typed into the free-text "Custom mode" dialog. */
sealed interface ModeHint {
    /** The network advertises [letter] in a group that takes an argument when set. */
    data class NeedsValue(
        val letter: Char,
    ) : ModeHint

    /** The network's CHANMODES/PREFIX never mention [letter]. */
    data class Unknown(
        val letter: Char,
    ) : ModeHint
}

/**
 * Advisory hints for a free-text mode string such as `+bk` or `-l`. Purely informational: the
 * catalog can be stale and the server is always the authority, so a hint must never block a send.
 */
fun ModeCatalog.hintsFor(letters: String): List<ModeHint> =
    letters
        .asSequence()
        .filter { it != '+' && it != '-' && !it.isWhitespace() }
        .distinct()
        .mapNotNull { letter ->
            when {
                isUnknown(letter) -> ModeHint.Unknown(letter)
                needsValueOnSet(letter) -> ModeHint.NeedsValue(letter)
                else -> null
            }
        }.toList()
