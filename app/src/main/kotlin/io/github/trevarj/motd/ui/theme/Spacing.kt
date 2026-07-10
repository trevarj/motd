package io.github.trevarj.motd.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.LayoutDensity

/**
 * Density-scaled spacing tokens (plans/13 §2.1) provided through [LocalSpacing]. Components read
 * `LocalSpacing.current` inside the composable, so density needs no signature changes anywhere.
 * COMFORTABLE reproduces the current hardcoded literals exactly (pixel-identical to today).
 *
 * The density setting selects the *render style*, not the font size: COMPACT renders each message as
 * a classic single-line IRC row (`nick: text`), COMFORTABLE renders Telegram-style bubbles, and
 * TWO_LINE renders a compact two-line row (avatar+nick+time header over the body). Message body text
 * size is constant across all modes (always `bodyLarge`); only spacing/paddings and the render
 * paradigm change. [compact] routes [io.github.trevarj.motd.ui.components.MessageBubble] into the
 * inline IRC row; [twoLine] routes it into the two-line row; otherwise the bubble renders.
 * [compactRowVPad] is the inline row's vertical padding.
 */
@Immutable
data class MotdSpacing(
    val compact: Boolean, // true -> classic single-line IRC row; false -> chat bubbles / two-line
    val twoLine: Boolean, // true -> compact two-line row (avatar+nick+time header over the body)
    val compactRowVPad: Dp, // COMPACT inline row vertical padding (tight IRC rows)
    val bubbleRowVPad: Dp, // MessageBubble outer Row vertical padding
    val bubbleInnerVPad: Dp, // bubble Column inner vertical padding
    val bubbleInnerHPad: Dp, // bubble Column inner horizontal padding
    val bubbleCorner: Dp, // base bubble corner radius (grouped inner corner stays 4.dp)
    val bubbleAvatar: Dp, // in-bubble sender avatar size
    val bubbleAvatarColumn: Dp, // reserved avatar column width (= bubbleAvatar + 8.dp)
    val actionVPad: Dp, // ACTION line vertical padding
    val systemPillVPad: Dp, // SystemEventPill row vertical padding
    val chatListVPad: Dp, // ChatListRowItem vertical padding
    val chatListAvatar: Dp, // chat-list avatar size
    val memberAvatar: Dp, // channel-info member-row avatar size
)

/** Pure token mapping; unit-tested. */
fun spacingFor(density: LayoutDensity): MotdSpacing = when (density) {
    LayoutDensity.COMPACT -> MotdSpacing(
        compact = true,
        twoLine = false,
        compactRowVPad = 1.dp,
        bubbleRowVPad = 0.dp,
        bubbleInnerVPad = 4.dp,
        bubbleInnerHPad = 8.dp,
        bubbleCorner = 14.dp,
        bubbleAvatar = 26.dp,
        bubbleAvatarColumn = 34.dp,
        actionVPad = 2.dp,
        systemPillVPad = 2.dp,
        chatListVPad = 6.dp,
        chatListAvatar = 36.dp,
        memberAvatar = 32.dp,
    )
    LayoutDensity.COMFORTABLE -> MotdSpacing(
        compact = false,
        twoLine = false,
        compactRowVPad = 1.dp,
        bubbleRowVPad = 1.dp,
        bubbleInnerVPad = 6.dp,
        bubbleInnerHPad = 10.dp,
        bubbleCorner = 18.dp,
        bubbleAvatar = 32.dp,
        bubbleAvatarColumn = 40.dp,
        actionVPad = 3.dp,
        systemPillVPad = 4.dp,
        chatListVPad = 10.dp,
        chatListAvatar = 44.dp,
        memberAvatar = 36.dp,
    )
    // TWO_LINE = a compact two-line row: small avatar + nick + time header over the body. Not a
    // bubble and not the single-line IRC row; [twoLine] routes MessageBubble to that renderer.
    LayoutDensity.TWO_LINE -> MotdSpacing(
        compact = false,
        twoLine = true,
        compactRowVPad = 2.dp,
        // Tight outer padding for the two-line row; the inner header/body spacing lives in the renderer.
        bubbleRowVPad = 2.dp,
        bubbleInnerVPad = 4.dp,
        bubbleInnerHPad = 12.dp,
        bubbleCorner = 18.dp,
        // Small header avatar (line 1) — smaller than the bubble avatar to keep the row compact.
        bubbleAvatar = 20.dp,
        bubbleAvatarColumn = 28.dp,
        actionVPad = 3.dp,
        systemPillVPad = 4.dp,
        chatListVPad = 10.dp,
        chatListAvatar = 44.dp,
        memberAvatar = 36.dp,
    )
}

/** COMFORTABLE default so previews and un-provided contexts render as today. */
val LocalSpacing: ProvidableCompositionLocal<MotdSpacing> =
    staticCompositionLocalOf { spacingFor(LayoutDensity.COMFORTABLE) }
