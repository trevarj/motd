package io.github.trevarj.motd.ui.channelinfo

/**
 * Pure moderation helpers (plans/16 §5.8). No Android/IRC deps, so op-gating and mask building are
 * unit-testable in isolation.
 */

/** Prefix glyphs at or above operator (owner '~', admin '&', op '@'). Halfop '%' is excluded. */
private const val OP_GLYPHS = "~&@"

/**
 * True when [ownPrefixes] contains a mode at or above op per [prefixOrder] (most privileged first).
 * Halfop is intentionally excluded (Confirmed decision #7): only '~', '&', '@' grant moderation.
 * A glyph not present in [prefixOrder] never qualifies.
 */
fun canModerate(ownPrefixes: String, prefixOrder: String): Boolean {
    val order = prefixOrder.ifEmpty { DEFAULT_PREFIX_ORDER }
    return ownPrefixes.any { it in OP_GLYPHS && order.indexOf(it) >= 0 }
}

/** Ban mask for [nick]: the simple `nick!*@*` form used by the /ban command and ban action. */
fun banMask(nick: String): String = "$nick!*@*"
