package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhoisParserTest {
    @Test fun cached_whox_fills_missing_whois_fields_without_overriding_newer_values() {
        val cached =
            UserEntity(
                networkId = 1,
                nick = "alice",
                username = "cached-user",
                account = "cached-account",
                away = true,
                hostmask = "cached-user@cached.host",
                realname = "Cached Real",
            )
        val merged =
            mergeUserDetails(
                "Alice",
                cached,
                WhoisInfo(nick = "Alice", username = "fresh-user", host = "fresh.host"),
            )!!
        assertEquals("fresh-user", merged.username)
        assertEquals("fresh.host", merged.host)
        assertEquals("cached-account", merged.account)
        assertEquals("Cached Real", merged.realname)
        assertEquals(true, merged.away)

        val cachedOnly = mergeUserDetails("Alice", cached, null)!!
        assertEquals("cached-user", cachedOnly.username)
        assertEquals("cached.host", cachedOnly.host)
    }

    @Test fun whois_bot_badge_needs_fresh_numeric_not_cached_roster_state() {
        val end = numeric("318", "me", "alice", "End of WHOIS")
        val info = parseWhois(listOf(numeric("335", "me", "alice", "arbitrary text"), end))
        assertEquals(true, info?.isBot)
        assertNull(parseWhois(listOf(numeric("335", "me", "alice"), end))?.isBot)
        assertNull(parseWhois(listOf(end))?.isBot)

        val cached = UserEntity(networkId = 1, nick = "alice", username = "aliceuser", isBot = true)
        val sheet = NickSheetState("Alice", cached = cached)
        assertEquals("aliceuser", sheet.details?.username)
        assertNull(sheet.details?.isBot)
        assertNull(sheet.copy(whois = parseWhois(listOf(end))).details?.isBot)
        assertEquals(true, sheet.copy(whois = info).details?.isBot)
    }

    private fun numeric(
        code: String,
        vararg params: String,
    ) = IrcMessage(command = code, params = params.toList())

    @Test fun folds_full_whois() {
        val info =
            parseWhois(
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
        val info =
            parseWhois(
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
