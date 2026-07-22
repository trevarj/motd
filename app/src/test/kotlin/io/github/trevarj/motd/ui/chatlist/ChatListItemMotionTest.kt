package io.github.trevarj.motd.ui.chatlist

import io.github.trevarj.motd.ui.theme.MotdMotion
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChatListItemMotionTest {
    @Test
    fun chat_rows_snap_on_reorder_but_retain_shared_micro_fades() {
        assertNull(ChatListItemMotion.placementSpec)
        assertSame(MotdMotion.microFadeIn, ChatListItemMotion.fadeInSpec)
        assertSame(MotdMotion.microFadeOut, ChatListItemMotion.fadeOutSpec)
    }
}
