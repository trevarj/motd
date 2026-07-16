package io.github.trevarj.motd.ui.theme

import androidx.compose.ui.unit.dp
import io.github.trevarj.motd.data.prefs.LayoutDensity
import org.junit.Assert.assertEquals
import org.junit.Test

/** spacingFor token mapping (plans/13 table 2.1) + the avatar-column invariant. */
class SpacingTest {

    @Test
    fun compact_tokens() {
        val s = spacingFor(LayoutDensity.COMPACT)
        assertEquals(12.dp, s.messageOuterHPad)
        assertEquals(0.dp, s.bubbleRowVPad)
        assertEquals(4.dp, s.bubbleInnerVPad)
        assertEquals(8.dp, s.bubbleInnerHPad)
        assertEquals(14.dp, s.bubbleCorner)
        assertEquals(26.dp, s.bubbleAvatar)
        assertEquals(34.dp, s.bubbleAvatarColumn)
        assertEquals(2.dp, s.actionVPad)
        assertEquals(2.dp, s.systemPillVPad)
        assertEquals(6.dp, s.chatListVPad)
        assertEquals(36.dp, s.chatListAvatar)
        assertEquals(32.dp, s.memberAvatar)
        // COMPACT is the only mode that switches to the classic single-line IRC renderer.
        assertEquals(true, s.compact)
        assertEquals(1.dp, s.compactRowVPad)
    }

    @Test
    fun comfortable_tokens_match_current_literals() {
        val s = spacingFor(LayoutDensity.COMFORTABLE)
        assertEquals(12.dp, s.messageOuterHPad)
        assertEquals(1.dp, s.bubbleRowVPad)
        assertEquals(6.dp, s.bubbleInnerVPad)
        assertEquals(10.dp, s.bubbleInnerHPad)
        assertEquals(18.dp, s.bubbleCorner)
        assertEquals(32.dp, s.bubbleAvatar)
        assertEquals(40.dp, s.bubbleAvatarColumn)
        assertEquals(3.dp, s.actionVPad)
        assertEquals(4.dp, s.systemPillVPad)
        assertEquals(10.dp, s.chatListVPad)
        assertEquals(44.dp, s.chatListAvatar)
        assertEquals(36.dp, s.memberAvatar)
        // COMFORTABLE keeps the bubble renderer.
        assertEquals(false, s.compact)
    }

    @Test
    fun two_line_tokens() {
        val s = spacingFor(LayoutDensity.TWO_LINE)
        assertEquals(12.dp, s.messageOuterHPad)
        assertEquals(2.dp, s.bubbleRowVPad)
        assertEquals(4.dp, s.bubbleInnerVPad)
        assertEquals(12.dp, s.bubbleInnerHPad)
        assertEquals(18.dp, s.bubbleCorner)
        assertEquals(20.dp, s.bubbleAvatar)
        assertEquals(28.dp, s.bubbleAvatarColumn)
        assertEquals(3.dp, s.actionVPad)
        assertEquals(4.dp, s.systemPillVPad)
        assertEquals(10.dp, s.chatListVPad)
        assertEquals(44.dp, s.chatListAvatar)
        assertEquals(36.dp, s.memberAvatar)
        // TWO_LINE is the compact two-line renderer: not the single-line IRC row, not a bubble.
        assertEquals(false, s.compact)
        assertEquals(true, s.twoLine)
    }

    @Test
    fun compact_is_the_only_single_line_renderer() {
        assertEquals(true, spacingFor(LayoutDensity.COMPACT).compact)
        assertEquals(false, spacingFor(LayoutDensity.COMFORTABLE).compact)
        assertEquals(false, spacingFor(LayoutDensity.TWO_LINE).compact)
    }

    @Test
    fun avatarColumn_is_avatar_plus_8dp_for_all_densities() {
        for (d in LayoutDensity.entries) {
            val s = spacingFor(d)
            assertEquals("$d: column == avatar + 8.dp", s.bubbleAvatar + 8.dp, s.bubbleAvatarColumn)
        }
    }
}
