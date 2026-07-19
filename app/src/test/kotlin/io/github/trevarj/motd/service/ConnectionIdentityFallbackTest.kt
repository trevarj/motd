package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionIdentityFallbackTest {
    @Test
    fun `persisted strict rules are used while no client exists`() {
        val rules = identityRulesFallback(
            live = null,
            liveReady = false,
            persisted = NetworkIdentityEntity(
                networkId = 1,
                caseMapping = "rfc1459-strict",
                chanTypes = "+",
            ),
        )

        assertEquals("nick~", rules.normalize("NICK~"))
        assertFalse(rules.normalize("nick~") == rules.normalize("nick^"))
        assertTrue(rules.isChannel("+room"))
        assertFalse(rules.isChannel("#room"))
    }

    @Test
    fun `unknown persisted mapping stays ascii and live rules require ready state`() {
        val persisted = NetworkIdentityEntity(1, caseMapping = "vendor-unicode", chanTypes = "")
        val fallback = identityRulesFallback(live = null, liveReady = false, persisted = persisted)
        assertFalse(fallback.normalize("[nick]") == fallback.normalize("{nick}"))
        assertFalse(fallback.isChannel("#room"))

        val live = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459, advertisedChanTypes = "#")
        assertEquals(live, identityRulesFallback(live, liveReady = true, persisted = persisted))

        assertEquals(
            fallback,
            identityRulesFallback(live, liveReady = false, persisted = persisted),
        )
    }

    @Test
    fun `missing persisted snapshot keeps protocol defaults`() {
        val rules = identityRulesFallback(live = null, liveReady = false, persisted = null)
        assertEquals("nick^", rules.normalize("NICK~"))
        assertTrue(rules.isChannel("#room"))
    }
}
