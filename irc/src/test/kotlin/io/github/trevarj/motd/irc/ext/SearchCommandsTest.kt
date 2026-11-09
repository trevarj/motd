package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `SEARCH` is a single `key=value;key=value` parameter in soju's `irc.ParseTags` form, so every
 * value must survive the IRCv3 tag escape table and the attribute order must stay stable.
 */
class SearchCommandsTest {
    private fun attributes(req: SearchRequest): String = SearchCommands.search(req).params.single()

    @Test
    fun rendersMandatoryAndOptionalAttributesInFixedOrder() {
        val message =
            SearchCommands.search(
                SearchRequest(
                    target = "#chan",
                    text = "hello",
                    from = "alice",
                    after = 1_000,
                    before = 2_000,
                    limit = 25,
                ),
            )

        assertEquals("SEARCH", message.command)
        assertEquals(
            "in=#chan;text=hello;from=alice;" +
                "after=1970-01-01T00:00:01.000Z;before=1970-01-01T00:00:02.000Z;limit=25",
            message.params.single(),
        )
        // Escaping keeps the attribute list one whitespace-free parameter on the wire.
        assertTrue(message.serialize().startsWith("SEARCH in=#chan;"))
    }

    @Test
    fun escapesTagSyntaxInValues() {
        val rendered = attributes(SearchRequest(target = "#chan", text = "a b;c\\d", from = "n\r\nick"))

        assertTrue(rendered, rendered.contains("text=a\\sb\\:c\\\\d"))
        assertTrue(rendered, rendered.contains("from=n\\r\\nick"))
        assertTrue("no raw separator may leak into a value", '\n' !in rendered && '\r' !in rendered)
        assertTrue("values must not introduce whitespace", ' ' !in rendered)
    }

    @Test
    fun omitsAbsentAttributes() {
        val rendered = attributes(SearchRequest(target = "#chan", text = null, from = "   "))

        // `in` and `limit` are always present; blank optional values are dropped, not sent empty.
        assertEquals("in=#chan;limit=$SOJU_SEARCH_MAX_LIMIT", rendered)
    }

    @Test
    fun clampsLimitIntoSojuRange() {
        assertTrue(attributes(SearchRequest(target = "#chan", limit = 0)).endsWith("limit=1"))
        assertTrue(attributes(SearchRequest(target = "#chan", limit = -5)).endsWith("limit=1"))
        assertTrue(
            attributes(SearchRequest(target = "#chan", limit = 500))
                .endsWith("limit=$SOJU_SEARCH_MAX_LIMIT"),
        )
    }

    @Test
    fun rejectsBlankTarget() {
        // soju has no cross-buffer search; a SEARCH without `in` is rejected server-side.
        val error = runCatching { SearchCommands.search(SearchRequest(target = "  ")) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun rendersMillisecondIsoBounds() {
        val rendered = attributes(SearchRequest(target = "#chan", after = 1_704_164_645_678L))

        // soju rejects a dropped zero fractional part, so the formatter always emits milliseconds.
        assertTrue(rendered, rendered.contains("after=2024-01-02T03:04:05.678Z"))
        assertTrue(
            attributes(SearchRequest(target = "#chan", before = 0L)).contains("before=1970-01-01T00:00:00.000Z"),
        )
    }

    @Test
    fun parsesPrivmsgNoticeAndCtcpAction() {
        val privmsg = parseSearchResult(IrcMessage.parse(":alice!u@h PRIVMSG #chan :plain hit"))!!
        assertEquals(SearchResultKind.PRIVMSG, privmsg.kind)
        assertEquals("alice", privmsg.sender)
        assertEquals("#chan", privmsg.target)
        assertEquals("plain hit", privmsg.text)

        val notice = parseSearchResult(IrcMessage.parse(":srv!u@h NOTICE #chan :heads up"))!!
        assertEquals(SearchResultKind.NOTICE, notice.kind)

        val action = parseSearchResult(IrcMessage.parse(":alice!u@h PRIVMSG #chan :\u0001ACTION waves\u0001"))!!
        assertEquals(SearchResultKind.ACTION, action.kind)
        assertEquals("waves", action.text)

        // The trailing delimiter is optional in the wild.
        val unterminated = parseSearchResult(IrcMessage.parse(":alice!u@h PRIVMSG #chan :\u0001ACTION waves"))!!
        assertEquals(SearchResultKind.ACTION, unterminated.kind)
        assertEquals("waves", unterminated.text)
    }

    @Test
    fun parsesTimeAndMsgidTags() {
        val hit =
            parseSearchResult(
                IrcMessage.parse(
                    "@time=2024-01-02T03:04:05.678Z;msgid=abc :alice!u@h PRIVMSG #chan :hit",
                ),
            )!!

        assertEquals(1_704_164_645_678L, hit.serverTime)
        assertEquals("abc", hit.msgid)
    }

    @Test
    fun preservesAccountIdentityAcrossNickChangesAndTreatsLoggedOutTagsAsAbsent() {
        val hit = parseSearchResult(IrcMessage.parse("@account=stable-account :different-nick!u@h PRIVMSG #chan :hit"))!!
        assertEquals("different-nick", hit.sender)
        assertEquals("stable-account", hit.senderAccount)
        for (tags in listOf("", "@account= ", "@account=* ")) {
            assertNull(parseSearchResult(IrcMessage.parse("$tags:nick!u@h PRIVMSG #chan :hit"))!!.senderAccount)
        }
    }

    @Test
    fun toleratesMissingTags() {
        val untagged = parseSearchResult(IrcMessage.parse(":alice!u@h PRIVMSG #chan :hit"))!!
        assertNull(untagged.serverTime)
        assertNull(untagged.msgid)

        val unparsable =
            parseSearchResult(
                IrcMessage.parse("@time=not-a-timestamp;msgid= :alice!u@h PRIVMSG #chan :hit"),
            )!!
        assertNull("an unparsable time tag is absent, not an error", unparsable.serverTime)
        assertNull("an empty msgid tag is absent", unparsable.msgid)
    }

    @Test
    fun ignoresNonMessageLines() {
        assertNull(parseSearchResult(IrcMessage.parse("BATCH +s soju.im/search")))
        assertNull(parseSearchResult(IrcMessage.parse(":srv 001 motd :Welcome")))
        // A result with no source has no sender to attribute the hit to.
        assertNull(parseSearchResult(IrcMessage.parse("PRIVMSG #chan :orphan")))
    }
}
