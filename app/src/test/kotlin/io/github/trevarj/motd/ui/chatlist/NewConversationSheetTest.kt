package io.github.trevarj.motd.ui.chatlist

import org.junit.Assert.assertEquals
import org.junit.Test

class NewConversationSheetTest {
    @Test
    fun channelJoinTarget_addsChannelPrefix() {
        assertEquals("#motd", channelJoinTarget("motd"))
    }

    @Test
    fun channelJoinTarget_preservesAdditionalPrefixForDoubleHashChannels() {
        assertEquals("##motd", channelJoinTarget("#motd"))
    }

    @Test
    fun channelJoinTarget_trimsSurroundingWhitespace() {
        assertEquals("#motd", channelJoinTarget("  motd  "))
    }
}
