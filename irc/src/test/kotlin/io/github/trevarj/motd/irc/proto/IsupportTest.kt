package io.github.trevarj.motd.irc.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsupportTest {

    @Test
    fun `defaults when nothing advertised`() {
        val isup = Isupport()
        assertEquals("rfc1459", isup.caseMapping)
        assertEquals("#&", isup.chanTypes)
        assertEquals(listOf('o' to '@', 'v' to '+'), isup.prefixModes)
    }

    @Test
    fun `parses key value tokens`() {
        val isup = Isupport()
        isup.update(listOf("CHATHISTORY=1000", "AWAYLEN=200", "NETWORK=Libera.Chat"))
        assertEquals("1000", isup["CHATHISTORY"])
        assertEquals("200", isup["AWAYLEN"])
        assertEquals("Libera.Chat", isup["NETWORK"])
    }

    @Test
    fun `get is case-insensitive on key`() {
        val isup = Isupport()
        isup.update(listOf("CHATHISTORY=1000"))
        assertEquals("1000", isup["chathistory"])
    }

    @Test
    fun `flag token without value`() {
        val isup = Isupport()
        isup.update(listOf("WHOX", "SAFELIST"))
        assertEquals("", isup["WHOX"])
        assertEquals("", isup["SAFELIST"])
    }

    @Test
    fun `negation removes token`() {
        val isup = Isupport()
        isup.update(listOf("WHOX"))
        assertEquals("", isup["WHOX"])
        isup.update(listOf("-WHOX"))
        assertNull(isup["WHOX"])
    }

    @Test
    fun `accumulates across multiple updates`() {
        val isup = Isupport()
        isup.update(listOf("CHATHISTORY=1000"))
        isup.update(listOf("NETWORK=Test"))
        assertEquals("1000", isup["CHATHISTORY"])
        assertEquals("Test", isup["NETWORK"])
    }

    // -- PREFIX parsing --

    @Test
    fun `parses PREFIX token`() {
        val isup = Isupport()
        isup.update(listOf("PREFIX=(ov)@+"))
        assertEquals(listOf('o' to '@', 'v' to '+'), isup.prefixModes)
    }

    @Test
    fun `parses extended PREFIX token`() {
        val isup = Isupport()
        isup.update(listOf("PREFIX=(qaohv)~&@%+"))
        assertEquals(
            listOf('q' to '~', 'a' to '&', 'o' to '@', 'h' to '%', 'v' to '+'),
            isup.prefixModes,
        )
    }

    @Test
    fun `empty PREFIX yields empty list`() {
        val isup = Isupport()
        isup.update(listOf("PREFIX="))
        assertEquals(emptyList<Pair<Char, Char>>(), isup.prefixModes)
    }

    // -- CHATHISTORY --

    @Test
    fun `parses CHATHISTORY limit`() {
        val isup = Isupport()
        isup.update(listOf("CHATHISTORY=1000"))
        assertEquals("1000", isup["CHATHISTORY"])
    }

    // -- CASEMAPPING --

    @Test
    fun `parses CASEMAPPING ascii`() {
        val isup = Isupport()
        isup.update(listOf("CASEMAPPING=ascii"))
        assertEquals("ascii", isup.caseMapping)
    }

    @Test
    fun `parses CASEMAPPING rfc1459`() {
        val isup = Isupport()
        isup.update(listOf("CASEMAPPING=rfc1459"))
        assertEquals("rfc1459", isup.caseMapping)
    }

    @Test
    fun `CHANTYPES override`() {
        val isup = Isupport()
        isup.update(listOf("CHANTYPES=#"))
        assertEquals("#", isup.chanTypes)
    }

    // -- normalize --

    @Test
    fun `normalize ascii lowers only A-Z`() {
        val isup = Isupport()
        isup.update(listOf("CASEMAPPING=ascii"))
        assertEquals("foobar", isup.normalize("FooBar"))
        // Bracket chars untouched under ascii.
        assertEquals("nick[]\\~", isup.normalize("NICK[]\\~"))
    }

    @Test
    fun `normalize rfc1459 lowers A-Z plus bracket set`() {
        val isup = Isupport() // default rfc1459
        assertEquals("foobar", isup.normalize("FooBar"))
        assertEquals("{}|^", isup.normalize("[]\\~"))
        assertEquals("nick{|}^", isup.normalize("Nick[\\]~"))
    }

    @Test
    fun `normalize channel name`() {
        val isup = Isupport()
        assertEquals("#libera", isup.normalize("#Libera"))
    }

    @Test
    fun `normalize leaves already-normal untouched`() {
        val isup = Isupport()
        assertEquals("plainnick", isup.normalize("plainnick"))
    }
}
