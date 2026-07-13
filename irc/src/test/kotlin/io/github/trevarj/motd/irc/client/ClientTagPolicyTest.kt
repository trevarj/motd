package io.github.trevarj.motd.irc.client

import org.junit.Assert.assertFalse
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
    fun `reaction support requires message tags reply and the mutation tag`() {
        val caps = setOf("message-tags")
        assertTrue(canSendReactionTags(caps, emptyMap(), remove = false))
        assertFalse(canSendReactionTags(emptySet(), emptyMap(), remove = false))
        assertFalse(
            canSendReactionTags(
                caps,
                mapOf("CLIENTTAGDENY" to "draft/unreact"),
                remove = true,
            ),
        )
    }
}
