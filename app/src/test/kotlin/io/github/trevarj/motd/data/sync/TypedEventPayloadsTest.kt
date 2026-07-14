package io.github.trevarj.motd.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TypedEventPayloadsTest {
    @Test fun `invite payload round trips escaped content`() {
        val payload = InvitePayloadV1("a\\\"lice", "me", "#room")
        assertEquals(payload, InvitePayloadV1.decode(payload.encode()))
    }

    @Test fun `invite payload rejects unknown and malformed versions`() {
        assertNull(InvitePayloadV1.decode(null))
        assertNull(InvitePayloadV1.decode("not json"))
        assertNull(InvitePayloadV1.decode("invite-v2:YQ:bWU:I2M"))
        assertNull(InvitePayloadV1.decode("invite-v1:YQ:bWU"))
    }

    @Test fun `network batch payload round trips ordered nicks and rejects malformed versions`() {
        val payload = NetworkBatchPayloadV1("a.example", "b.example", listOf("Alice", "B:ob", "c.d"))
        assertEquals(payload, NetworkBatchPayloadV1.decode(payload.encode()))
        assertNull(NetworkBatchPayloadV1.decode(null))
        assertNull(NetworkBatchPayloadV1.decode("network-v2:YQ:Yg:QWxpY2U"))
        assertNull(NetworkBatchPayloadV1.decode("network-v1:YQ"))
    }
}
