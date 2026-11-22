package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.Prefix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkIgnorePolicyTest {
    @Test fun `bare nick becomes nick hostmask`() {
        assertEquals("alice!*@*", normalizeIgnorePattern(" alice ").getOrThrow())
    }

    @Test fun `user host mask gets wildcard nick`() {
        assertEquals("*!user@example.net", normalizeIgnorePattern("user@example.net").getOrThrow())
    }

    @Test fun `line breaks are rejected`() {
        assertTrue(normalizeIgnorePattern("alice\nOPER bad").isFailure)
    }

    @Test fun `enabled ignore matches source hostmask`() {
        val ignores = listOf(ignore("a?ice!*@*.example.net"))
        assertTrue(
            ignoredBy(
                ignores,
                Prefix("alice", "ident", "chat.example.net"),
                IrcIdentityRules(),
            ),
        )
    }

    @Test fun `disabled ignore does not match`() {
        val ignores = listOf(ignore("alice!*@*", enabled = false))
        assertFalse(ignoredBy(ignores, Prefix("alice", "u", "h"), IrcIdentityRules()))
    }

    @Test fun `nick matching follows irc case mapping`() {
        val ignores = listOf(ignore("[lice!*@*"))
        assertTrue(
            ignoredBy(
                ignores,
                Prefix("{lice", "u", "h"),
                IrcIdentityRules(IrcCaseMapping.Rfc1459),
            ),
        )
        assertFalse(
            ignoredBy(
                ignores,
                Prefix("{lice", "u", "h"),
                IrcIdentityRules(IrcCaseMapping.Ascii),
            ),
        )
    }

    @Test fun `exact ignore requires a single IRC nickname outside current channel types`() {
        val rules = IrcIdentityRules(advertisedChanTypes = "#[")
        for (nick in listOf("Alice", "_alice-9", "{lice", "\\lice", "`lice")) {
            assertTrue(nick, isExactIgnoreNick(nick, rules))
        }
        for (nick in listOf("", "[lice", "#chat", "irc.example.net", "9alice", "-alice", "a b", " a", "a\u0000", "a\u0085", "a\n")) {
            assertFalse(nick, isExactIgnoreNick(nick, rules))
        }
        assertTrue(isExactIgnoreNick("[lice", IrcIdentityRules()))
    }

    @Test fun `exact ignore state counts only enabled unrestricted nick masks under the network casemap`() {
        val rules = IrcIdentityRules(IrcCaseMapping.Rfc1459)
        val nonExact =
            listOf(
                ignore("[lice!*@*", enabled = false),
                ignore("?lice!*@*"),
                ignore("[li*!*@*"),
                ignore("[lice!user@*"),
                ignore("[lice!*@*.example.net"),
                ignore("[lice!**@*"),
                ignore("[lice"),
            )
        assertFalse(hasExactNickIgnore(nonExact, "{lice", rules))
        val exact = nonExact + ignore("[LICE!*@*")
        assertTrue(hasExactNickIgnore(exact, "{lice", rules))
        assertFalse(hasExactNickIgnore(exact, "{lice", IrcIdentityRules(IrcCaseMapping.Ascii)))
    }

    private fun ignore(
        pattern: String,
        enabled: Boolean = true,
    ) = NetworkIgnoreEntity(id = 1, networkId = 1, pattern = pattern, enabled = enabled, createdAt = 1)
}
