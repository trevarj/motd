package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyDeliveryTest {
    @Test
    fun `channel reply uses only semantic tag by default`() {
        assertEquals(
            ReplyDelivery("hello", "m1"),
            prepareReplyDelivery("hello", "m1", "alice", BufferType.CHANNEL, false, true),
        )
    }

    @Test
    fun `visible preference prefixes channels but not queries while tags work`() {
        assertEquals(
            ReplyDelivery("alice: hello", "m1"),
            prepareReplyDelivery("hello", "m1", "alice", BufferType.CHANNEL, true, true),
        )
        assertEquals(
            ReplyDelivery("hello", "m1"),
            prepareReplyDelivery("hello", "m1", "alice", BufferType.QUERY, true, true),
        )
    }

    @Test
    fun `blocked tags fall back visibly in channels and queries`() {
        for (type in listOf(BufferType.CHANNEL, BufferType.QUERY)) {
            assertEquals(
                ReplyDelivery("alice: hello", null),
                prepareReplyDelivery("hello", "m1", "alice", type, false, false),
            )
        }
    }

    @Test
    fun `fallback prefix is added at most once and missing parent stays plain`() {
        assertEquals(
            ReplyDelivery("alice: hello", null),
            prepareReplyDelivery("alice: hello", "m1", "alice", BufferType.CHANNEL, false, false),
        )
        assertEquals(
            ReplyDelivery("hello", null),
            prepareReplyDelivery("hello", "m1", null, BufferType.CHANNEL, false, false),
        )
    }
}
