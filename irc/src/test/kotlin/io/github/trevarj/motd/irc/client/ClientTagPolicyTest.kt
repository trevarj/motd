package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.Prefix
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
    fun `channel context accepts only valid channel names and prefers the standard tag`() {
        val rules = IrcIdentityRules(advertisedChanTypes = "#&")

        fun context(vararg tags: Pair<String, String>): String? =
            validChannelContext(
                IrcEvent.ChatMessage(
                    ctx = MessageContext(null, 0, null, null, null, clientTags = tags.toMap()),
                    kind = IrcEvent.ChatKind.NOTICE,
                    source = Prefix("helperbot"),
                    target = "me",
                    text = "private help",
                    isSelf = false,
                    replyToMsgid = null,
                ),
                rules::isChannel,
            )

        val draft = "+draft/channel-context" to "#old"
        assertEquals("&help", context("+channel-context" to "&help", draft))
        assertEquals("#old", context(draft))
        for (invalid in listOf("", "#", "alice", "@#help", "+help", " #help", "#two rooms", "#a,#b", "#a\u0007b", "#a\tb", "#a\nb")) {
            assertNull(invalid, context("+channel-context" to invalid, draft))
        }
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
