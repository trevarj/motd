package io.github.trevarj.motd.ui.channelinfo

import io.github.trevarj.motd.data.db.MemberEntity

/**
 * Pure member-list sectioning by highest channel prefix. Fully unit-testable; no Android deps.
 *
 * A member's [MemberEntity.prefixes] holds the prefix glyphs they hold (e.g. "@+"). Members are
 * grouped by their *highest* prefix using [prefixOrder] (most privileged first), with unprefixed
 * members last. Within a section, members sort case-insensitively by nick.
 */

/** Sensible fallback prefix ordering when ISUPPORT PREFIX is unavailable. */
const val DEFAULT_PREFIX_ORDER: String = "~&@%+"

data class MemberSection(
    /** Highest prefix glyph for this section, or null for the unprefixed (regular) section. */
    val prefix: Char?,
    val members: List<MemberEntity>,
)

/**
 * Build sections. [prefixOrder] is the ordered prefix glyphs, most privileged first (from
 * `client.isupport.prefixModes` prefixes, mapped to glyph order). Falls back to
 * [DEFAULT_PREFIX_ORDER] when empty.
 */
fun sectionMembers(
    members: List<MemberEntity>,
    prefixOrder: String = DEFAULT_PREFIX_ORDER,
): List<MemberSection> {
    val order = prefixOrder.ifEmpty { DEFAULT_PREFIX_ORDER }
    val rank: (Char) -> Int = { c -> order.indexOf(c).let { if (it < 0) Int.MAX_VALUE else it } }

    // Highest prefix = the one with the smallest rank among the member's held prefixes.
    fun highest(m: MemberEntity): Char? =
        m.prefixes.minByOrNull(rank).takeIf { it != null && rank(it) != Int.MAX_VALUE }

    val grouped = members.groupBy { highest(it) }

    // Section order: known prefixes by [order], then the null (regular) bucket last.
    val prefixSections = order
        .mapNotNull { glyph ->
            grouped[glyph]?.let { list ->
                MemberSection(glyph, list.sortedBy { it.nick.lowercase() })
            }
        }
    val regular = grouped[null]?.let { list ->
        MemberSection(null, list.sortedBy { it.nick.lowercase() })
    }

    return prefixSections + listOfNotNull(regular)
}

/**
 * Derive the prefix-glyph order (most privileged first) from ISUPPORT prefixModes
 * (mode->prefix pairs, already in privilege order). Empty input yields the default order.
 */
fun prefixOrderFrom(prefixModes: List<Pair<Char, Char>>): String =
    if (prefixModes.isEmpty()) DEFAULT_PREFIX_ORDER
    else prefixModes.joinToString("") { it.second.toString() }
