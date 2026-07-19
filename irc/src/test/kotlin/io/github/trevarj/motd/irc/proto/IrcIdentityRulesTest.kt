package io.github.trevarj.motd.irc.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IrcIdentityRulesTest {
    @Test
    fun `ascii folds only ASCII uppercase letters`() {
        assertEquals(
            "az09[]\\~^\u00c4",
            IrcCaseMapping.Ascii.normalize("AZ09[]\\~^\u00c4"),
        )
    }

    @Test
    fun `rfc1459 folds the complete bracket and tilde set`() {
        assertEquals("az{}|^^", IrcCaseMapping.Rfc1459.normalize("AZ[]\\~^"))
    }

    @Test
    fun `rfc1459 strict keeps tilde and caret distinct`() {
        val mapping = IrcCaseMapping.Rfc1459Strict

        assertEquals("az{}|~^", mapping.normalize("AZ[]\\~^"))
        assertFalse(mapping.normalize("~") == mapping.normalize("^"))
    }

    @Test
    fun `known mapping names are parsed case insensitively`() {
        assertEquals(IrcCaseMapping.Ascii, IrcCaseMapping.from("ASCII"))
        assertEquals(IrcCaseMapping.Rfc1459, IrcCaseMapping.from("RFC1459"))
        assertEquals(IrcCaseMapping.Rfc1459Strict, IrcCaseMapping.from("RFC1459-STRICT"))
        assertNull(IrcCaseMapping.Ascii.diagnostic)
    }

    @Test
    fun `unknown mapping exposes raw diagnostic and falls back to ascii`() {
        val mapping = IrcCaseMapping.from("Vendor-Unicode")

        assertEquals(IrcCaseMapping.Unknown("Vendor-Unicode"), mapping)
        assertEquals("Vendor-Unicode", mapping.rawName)
        assertEquals(
            "Unsupported IRC CASEMAPPING 'Vendor-Unicode'; using conservative ASCII folding",
            mapping.diagnostic,
        )
        assertEquals("nick[]\\~", mapping.normalize("NICK[]\\~"))
    }

    @Test
    fun `missing chantypes uses legacy defaults`() {
        val rules = IrcIdentityRules.from(rawCaseMapping = null, advertisedChanTypes = null)

        assertEquals(IrcCaseMapping.Rfc1459, rules.caseMapping)
        assertNull(rules.advertisedChanTypes)
        assertEquals("#&", rules.chanTypes)
        assertTrue(rules.isChannel("#room"))
        assertTrue(rules.isChannel("&local"))
        assertFalse(rules.isChannel("+modeless"))
        assertFalse(rules.isChannel(""))
    }

    @Test
    fun `custom chantypes are authoritative`() {
        val rules = IrcIdentityRules.from(rawCaseMapping = null, advertisedChanTypes = "+!")

        assertEquals("+!", rules.advertisedChanTypes)
        assertEquals("+!", rules.chanTypes)
        assertTrue(rules.isChannel("+modeless"))
        assertTrue(rules.isChannel("!safe"))
        assertFalse(rules.isChannel("#room"))
    }

    @Test
    fun `explicitly empty chantypes disable channel classification`() {
        val rules = IrcIdentityRules.from(rawCaseMapping = null, advertisedChanTypes = "")

        assertEquals("", rules.advertisedChanTypes)
        assertEquals("", rules.chanTypes)
        assertFalse(rules.isChannel("#room"))
        assertFalse(rules.isChannel("&local"))
        assertFalse(rules.isChannel(""))
    }

    @Test
    fun `mention matching follows the advertised casemap`() {
        val rfc = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459)
        val strict = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459Strict)

        assertTrue(rfc.containsMention("hello Nick[]", "nick{}"))
        assertTrue(rfc.containsMention("hello nick~", "nick^"))
        assertFalse(strict.containsMention("hello nick~", "nick^"))
        assertFalse(rfc.containsMention("prefixnick{}suffix", "nick[]"))
    }

    @Test
    fun `reaction actor key prefers account and namespaces nick fallback`() {
        val rules = IrcIdentityRules(caseMapping = IrcCaseMapping.Rfc1459Strict)

        assertEquals("account:Alice", rules.actorKey("Nick~", "Alice"))
        assertEquals("nick:nick~", rules.actorKey("Nick~", null))
        assertEquals("nick:nick~", rules.actorKey("Nick~", ""))
    }
}
