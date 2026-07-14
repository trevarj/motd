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
}
