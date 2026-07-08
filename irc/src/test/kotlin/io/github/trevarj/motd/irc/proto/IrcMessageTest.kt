package io.github.trevarj.motd.irc.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IrcMessageTest {

    // -- basic parsing --

    @Test
    fun `parses command only`() {
        val m = IrcMessage.parse("PING")
        assertEquals("PING", m.command)
        assertTrue(m.params.isEmpty())
        assertNull(m.source)
        assertTrue(m.tags.isEmpty())
    }

    @Test
    fun `command is uppercased on parse`() {
        assertEquals("PRIVMSG", IrcMessage.parse("privmsg #a :hi").command)
    }

    @Test
    fun `numeric commands stay three-digit strings`() {
        val m = IrcMessage.parse(":srv 001 nick :Welcome")
        assertEquals("001", m.command)
        assertEquals(listOf("nick", "Welcome"), m.params)
    }

    @Test
    fun `missing source is fine`() {
        val m = IrcMessage.parse("NOTICE #a :hello")
        assertNull(m.source)
        assertEquals("NOTICE", m.command)
    }

    @Test
    fun `parses full prefix nick user host`() {
        val m = IrcMessage.parse(":nick!user@host PRIVMSG #chan :yo")
        assertEquals(Prefix("nick", "user", "host"), m.source)
    }

    @Test
    fun `parses servername prefix into nick`() {
        val m = IrcMessage.parse(":irc.example.net 001 me :hi")
        assertEquals(Prefix("irc.example.net"), m.source)
    }

    @Test
    fun `parses prefix with only host`() {
        val m = IrcMessage.parse(":nick@host QUIT")
        assertEquals(Prefix("nick", null, "host"), m.source)
    }

    // -- trailing param edge cases --

    @Test
    fun `trailing param may contain spaces and colons`() {
        val m = IrcMessage.parse("PRIVMSG #a :hello: there world")
        assertEquals(listOf("#a", "hello: there world"), m.params)
    }

    @Test
    fun `empty trailing param preserved`() {
        val m = IrcMessage.parse("PRIVMSG #a :")
        assertEquals(listOf("#a", ""), m.params)
    }

    @Test
    fun `colon inside trailing not treated as new param`() {
        val m = IrcMessage.parse("PRIVMSG #a :a:b:c")
        assertEquals(listOf("#a", "a:b:c"), m.params)
    }

    @Test
    fun `multiple middle params then trailing`() {
        val m = IrcMessage.parse("MODE #a +o nick :ignored trailing")
        assertEquals(listOf("#a", "+o", "nick", "ignored trailing"), m.params)
    }

    @Test
    fun `collapses runs of spaces between middle params`() {
        val m = IrcMessage.parse("MODE   #a    +o")
        assertEquals(listOf("#a", "+o"), m.params)
    }

    // -- tag parsing / keys --

    @Test
    fun `tag without equals has empty value`() {
        val m = IrcMessage.parse("@foo PRIVMSG #a :x")
        assertEquals("", m.tags["foo"])
    }

    @Test
    fun `tag with trailing equals has empty value`() {
        val m = IrcMessage.parse("@foo= PRIVMSG #a :x")
        assertEquals("", m.tags["foo"])
    }

    @Test
    fun `vendored and client tag keys parsed verbatim`() {
        val m = IrcMessage.parse("@+example.com/foo=bar;draft/reply=abc;+typing=active TAGMSG #a")
        assertEquals("bar", m.tags["+example.com/foo"])
        assertEquals("abc", m.tags["draft/reply"])
        assertEquals("active", m.tags["+typing"])
    }

    @Test
    fun `parses tags source command params together`() {
        val m = IrcMessage.parse("@time=2026-07-08T12:34:56.789Z :n!u@h PRIVMSG #a :hi")
        assertEquals("2026-07-08T12:34:56.789Z", m.tags["time"])
        assertEquals(Prefix("n", "u", "h"), m.source)
        assertEquals("PRIVMSG", m.command)
        assertEquals(listOf("#a", "hi"), m.params)
    }

    // -- garbage --

    @Test
    fun `tags with no command throws`() {
        assertThrows(IrcParseException::class.java) { IrcMessage.parse("@foo=bar") }
    }

    @Test
    fun `source with no command throws`() {
        assertThrows(IrcParseException::class.java) { IrcMessage.parse(":nick!u@h") }
    }

    @Test
    fun `empty line throws`() {
        assertThrows(IrcParseException::class.java) { IrcMessage.parse("") }
    }

    // -- tag escape table round-trips (every row) --

    @Test
    fun `tag escape semicolon`() {
        assertEquals("\\:", IrcMessage.escapeTagValueForTest(";"))
        assertEquals(";", IrcMessage.unescapeTagValueForTest("\\:"))
    }

    @Test
    fun `tag escape space`() {
        assertEquals("\\s", IrcMessage.escapeTagValueForTest(" "))
        assertEquals(" ", IrcMessage.unescapeTagValueForTest("\\s"))
    }

    @Test
    fun `tag escape backslash`() {
        assertEquals("\\\\", IrcMessage.escapeTagValueForTest("\\"))
        assertEquals("\\", IrcMessage.unescapeTagValueForTest("\\\\"))
    }

    @Test
    fun `tag escape CR`() {
        assertEquals("\\r", IrcMessage.escapeTagValueForTest("\r"))
        assertEquals("\r", IrcMessage.unescapeTagValueForTest("\\r"))
    }

    @Test
    fun `tag escape LF`() {
        assertEquals("\\n", IrcMessage.escapeTagValueForTest("\n"))
        assertEquals("\n", IrcMessage.unescapeTagValueForTest("\\n"))
    }

    @Test
    fun `tag unescape unknown drops backslash`() {
        assertEquals("q", IrcMessage.unescapeTagValueForTest("\\q"))
        assertEquals("hello", IrcMessage.unescapeTagValueForTest("\\h\\e\\l\\l\\o"))
    }

    @Test
    fun `tag unescape trailing lone backslash dropped`() {
        assertEquals("abc", IrcMessage.unescapeTagValueForTest("abc\\"))
        assertEquals("", IrcMessage.unescapeTagValueForTest("\\"))
    }

    @Test
    fun `tag value round-trip through parse and serialize`() {
        // A value hitting every escapable character.
        val raw = "a;b c\\d\re\nf"
        val m = IrcMessage(tags = mapOf("k" to raw), command = "PING")
        val wire = m.serialize()
        assertTrue(wire.startsWith("@k="))
        val back = IrcMessage.parse(wire)
        assertEquals(raw, back.tags["k"])
    }

    // -- serialize --

    @Test
    fun `serialize command only`() {
        assertEquals("PING", IrcMessage(command = "PING").serialize())
    }

    @Test
    fun `serialize with trailing when contains space`() {
        val m = IrcMessage(command = "PRIVMSG", params = listOf("#a", "hi there"))
        assertEquals("PRIVMSG #a :hi there", m.serialize())
    }

    @Test
    fun `serialize with trailing when empty`() {
        val m = IrcMessage(command = "PRIVMSG", params = listOf("#a", ""))
        assertEquals("PRIVMSG #a :", m.serialize())
    }

    @Test
    fun `serialize with trailing when starts with colon`() {
        val m = IrcMessage(command = "PRIVMSG", params = listOf("#a", ":wave"))
        assertEquals("PRIVMSG #a ::wave", m.serialize())
    }

    @Test
    fun `serialize plain middle params without colon`() {
        val m = IrcMessage(command = "MODE", params = listOf("#a", "+o", "nick"))
        assertEquals("MODE #a +o nick", m.serialize())
    }

    @Test
    fun `serialize with source`() {
        val m = IrcMessage(source = Prefix("n", "u", "h"), command = "QUIT", params = listOf("bye now"))
        assertEquals(":n!u@h QUIT :bye now", m.serialize())
    }

    @Test
    fun `serialize round-trip preserves parse`() {
        val line = "@time=2026-07-08T12:34:56.789Z :n!u@h PRIVMSG #a :hi there"
        assertEquals(line, IrcMessage.parse(line).serialize())
    }

    // -- oversize serialize throws --

    @Test
    fun `oversize message serialize throws`() {
        val big = "x".repeat(600)
        val m = IrcMessage(command = "PRIVMSG", params = listOf("#a", big))
        assertThrows(IllegalArgumentException::class.java) { m.serialize() }
    }

    @Test
    fun `oversize tag section serialize throws`() {
        val bigVal = "y".repeat(9000)
        val m = IrcMessage(tags = mapOf("k" to bigVal), command = "PING")
        assertThrows(IllegalArgumentException::class.java) { m.serialize() }
    }

    @Test
    fun `at-limit message serialize does not throw`() {
        // 512 total incl CRLF => 510 wire bytes max. Build a wire of exactly 510 bytes.
        // "PRIVMSG " (8) + "#a " (3) + ":" (1) + text; a non-empty single-word text still
        // renders with the leading ':' since it is the last (trailing) param here it does
        // not contain spaces, so it serializes without ':'. Use a spaced text to force ':'.
        val prefix = "PRIVMSG #a :" // 12 bytes when the trailing form is used
        val text = "a " + "z".repeat(510 - prefix.length - 2) // force spaces so ':' is emitted
        val m = IrcMessage(command = "PRIVMSG", params = listOf("#a", text))
        val wire = m.serialize() // must not throw
        assertEquals(510, wire.toByteArray(Charsets.UTF_8).size)
    }
}
