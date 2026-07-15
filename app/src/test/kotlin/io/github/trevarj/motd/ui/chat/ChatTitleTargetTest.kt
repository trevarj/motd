package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.BufferType
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTitleTargetTest {
    @Test
    fun queryOpensNickDetailsInsteadOfChannelInfo() {
        assertEquals(ChatTitleTarget.NICK_DETAILS, chatTitleTarget(BufferType.QUERY))
    }

    @Test
    fun channelStillOpensChannelInfo() {
        assertEquals(ChatTitleTarget.CHANNEL_INFO, chatTitleTarget(BufferType.CHANNEL))
    }

    @Test
    fun serverAndUnloadedBuffersRemainInert() {
        assertEquals(ChatTitleTarget.NONE, chatTitleTarget(BufferType.SERVER))
        assertEquals(ChatTitleTarget.NONE, chatTitleTarget(null))
    }
}
