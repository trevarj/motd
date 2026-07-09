package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhoisParserTest {
    private fun numeric(code: String, vararg params: String) =
        IrcMessage(command = code, params = params.toList())

    @Test fun folds_full_whois() {
        val info = parseWhois(
            listOf(
                numeric("311", "me", "alice", "aliceuser", "alice.host", "*", "Alice Real"),
                numeric("312", "me", "alice", "irc.libera.chat", "Libera server"),
                numeric("319", "me", "alice", "#kotlin @#android +#compose"),
                numeric("330", "me", "alice", "aliceacct", "is logged in as"),
                numeric("317", "me", "alice", "42", "1700000000"),
                numeric("318", "me", "alice", "End of WHOIS"),
            ),
        )
        requireNotNull(info)
        assertEquals("alice", info.nick)
        assertEquals("aliceuser", info.username)
        assertEquals("alice.host", info.host)
        assertEquals("Alice Real", info.realname)
        assertEquals("irc.libera.chat", info.server)
        assertEquals("aliceacct", info.account)
        assertEquals(listOf("#kotlin", "@#android", "+#compose"), info.channels)
        assertEquals(42L, info.idleSecs)
    }

    @Test fun folds_away_message() {
        val info = parseWhois(
            listOf(
                numeric("311", "me", "bob", "bobuser", "bob.host", "*", "Bob"),
                numeric("301", "me", "bob", "gone fishing"),
                numeric("318", "me", "bob", "End of WHOIS"),
            ),
        )
        assertEquals("gone fishing", info?.awayMessage)
    }

    @Test fun null_without_311_or_318() {
        // A stray 319 alone does not describe a WHOIS.
        assertNull(parseWhois(listOf(numeric("319", "me", "carol", "#x"))))
        assertNull(parseWhois(emptyList()))
    }

    @Test fun end_only_still_yields_nick() {
        // 318 alone (no 311) is enough to know it was a WHOIS for that nick.
        val info = parseWhois(listOf(numeric("318", "me", "dave", "End of WHOIS")))
        assertEquals("dave", info?.nick)
        assertNull(info?.username)
    }
}
