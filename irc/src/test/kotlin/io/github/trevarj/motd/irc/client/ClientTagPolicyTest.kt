package io.github.trevarj.motd.irc.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientTagPolicyTest {
    @Test
    fun `missing CLIENTTAGDENY allows client tags`() {
        assertTrue(clientTagAllowed(emptyMap(), "+reply"))
    }

    @Test
    fun `explicit entries deny only the named tags`() {
        val isupport = mapOf("CLIENTTAGDENY" to "draft/react,reply")
        assertFalse(clientTagAllowed(isupport, "+draft/react"))
        assertFalse(clientTagAllowed(isupport, "+reply"))
        assertTrue(clientTagAllowed(isupport, "+draft/unreact"))
    }

    @Test
    fun `wildcard honors negated exemptions`() {
        val isupport = mapOf("CLIENTTAGDENY" to "*,-reply,-draft/react")
        assertTrue(clientTagAllowed(isupport, "+reply"))
        assertTrue(clientTagAllowed(isupport, "+draft/react"))
        assertFalse(clientTagAllowed(isupport, "+draft/unreact"))
    }

    @Test
    fun `channel context accepts private standard and draft tags only for channels`() {
        val isChannel: (String) -> Boolean = { it.startsWith('#') }

        assertEquals("#help", validChannelContext(mapOf("+channel-context" to "#help"), "me", isChannel))
        assertEquals("#old", validChannelContext(mapOf("+draft/channel-context" to "#old"), "me", isChannel))
        assertNull(validChannelContext(mapOf("+channel-context" to "alice"), "me", isChannel))
        assertNull(validChannelContext(mapOf("+channel-context" to "#help"), "#public", isChannel))
    }

    @Test
    fun `reaction support requires message tags reply and the mutation tag`() {
        val caps = setOf("message-tags")
        assertTrue(canSendReactionTags(caps, emptyMap(), remove = false))
        assertFalse(canSendReactionTags(emptySet(), emptyMap(), remove = false))
        assertEquals("+reply", preferredReplyTag(caps, emptyMap()))
        assertNull(preferredReplyTag(emptySet(), emptyMap()))
        val draftOnly = mapOf("CLIENTTAGDENY" to "*,-draft/react,-draft/reply")
        assertEquals("+draft/reply", preferredReplyTag(caps, draftOnly))
        assertTrue(canSendReactionTags(caps, draftOnly, remove = false))
        assertFalse(canSendReactionTags(caps, draftOnly, remove = true))
        assertFalse(canSendReactionTags(emptySet(), draftOnly, remove = false))
        val both = mapOf("CLIENTTAGDENY" to "*,-reply,-draft/reply,-draft/react,-draft/unreact")
        assertEquals("+reply", preferredReplyTag(caps, both))
        assertTrue(canSendReactionTags(caps, both, remove = true))
        val neither = mapOf("CLIENTTAGDENY" to "*,-draft/react,-draft/unreact")
        assertNull(preferredReplyTag(caps, neither))
        assertFalse(canSendReactionTags(caps, neither, remove = false))
        assertFalse(canSendReactionTags(caps, neither, remove = true))
        val removeOnly = mapOf("CLIENTTAGDENY" to "*,-draft/reply,-draft/unreact")
        assertFalse(canSendReactionTags(caps, removeOnly, remove = false))
        assertTrue(canSendReactionTags(caps, removeOnly, remove = true))
        assertFalse(
            canSendReactionTags(
                caps,
                mapOf("CLIENTTAGDENY" to "draft/unreact"),
                remove = true,
            ),
        )
    }
}
