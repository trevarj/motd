package io.github.trevarj.motd.data.visibility

import io.github.trevarj.motd.data.prefs.FoolsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure string assertions on the generated global feed SQL; no database involved. */
class GlobalFeedPagingQueryTest {
    @Test
    fun keepsConversationKindsAndProjectsTheSearchHitColumns() {
        val sql = globalFeedPagingQuery(MessageVisibilitySpec()).sql

        assertTrue(sql.contains("'PRIVMSG'"))
        assertTrue(sql.contains("'NOTICE'"))
        assertTrue(sql.contains("'ACTION'"))
        assertFalse(sql.contains("'JOIN'"))
        assertFalse(sql.contains("'QUIT'"))
        // Byte-identical to MessageDao.search's projection, which is why both return SearchHit.
        assertTrue(sql.contains("b.displayName AS bufferDisplayName"))
        assertTrue(sql.contains("n.name AS networkName"))
        // SearchHit's bufferType/networkId are non-null; a raw query that omits them maps to null.
        assertTrue(sql.contains("b.type AS bufferType"))
        assertTrue(sql.contains("b.networkId AS networkId"))
        assertTrue(sql.contains("b.avatarOverrideModel AS avatarOverrideModel"))
        assertTrue(sql.contains("ni.caseMapping AS caseMapping"))
        assertTrue(sql.contains("ni.chanTypes AS chanTypes"))
        // (serverTime, id) only: timelineOrder is per-buffer and not comparable across buffers.
        assertTrue(sql.contains("ORDER BY m.serverTime DESC, m.id DESC"))
        assertFalse(sql.contains("timelineOrder"))
    }

    /** A null key reads the newest page: no keyset predicate and no LIMIT clause or arguments. */
    @Test
    fun anUnkeyedQueryCarriesNoSeekAndNoArguments() {
        val query = globalFeedPagingQuery(MessageVisibilitySpec())

        assertFalse(query.sql.contains("LIMIT"))
        assertEquals(0, query.argCount)
    }

    /**
     * Spelled as a leading-column range plus a tie-break, not a row value: SQLite offers no index
     * guarantee for `(a, b) < (?, ?)`, and the range is what the (serverTime, id) index seeks on.
     */
    @Test
    fun seekingOlderRowsBoundsServerTimeAndBreaksTiesOnId() {
        val query =
            globalFeedPagingQuery(
                MessageVisibilitySpec(),
                key = GlobalFeedKey(serverTime = 400, id = 9),
                seek = GlobalFeedSeek.OLDER,
                limit = 50,
            )

        assertTrue(query.sql.contains("AND m.serverTime <= ? AND (m.serverTime < ? OR m.id < ?) "))
        assertTrue(query.sql.contains("ORDER BY m.serverTime DESC, m.id DESC LIMIT ?"))
        assertFalse(query.sql.contains("OFFSET"))
        // serverTime twice, then the tie-break id, then the page size.
        assertEquals(4, query.argCount)
    }

    /** Refresh re-seeks from the anchor row itself, so the viewport keeps the line it was on. */
    @Test
    fun anchoredRefreshIncludesTheAnchorRow() {
        val sql =
            globalFeedPagingQuery(
                MessageVisibilitySpec(),
                key = GlobalFeedKey(serverTime = 400, id = 9),
                seek = GlobalFeedSeek.OLDER_OR_AT,
                limit = 50,
            ).sql

        assertTrue(sql.contains("AND m.serverTime <= ? AND (m.serverTime < ? OR m.id <= ?) "))
    }

    /** Prepend reads ascending from the key; the source flips the page back to newest-first. */
    @Test
    fun seekingNewerRowsReadsAscendingFromTheKey() {
        val sql =
            globalFeedPagingQuery(
                MessageVisibilitySpec(),
                key = GlobalFeedKey(serverTime = 400, id = 9),
                seek = GlobalFeedSeek.NEWER,
                limit = 50,
            ).sql

        assertTrue(sql.contains("AND m.serverTime >= ? AND (m.serverTime > ? OR m.id > ?) "))
        assertTrue(sql.contains("ORDER BY m.serverTime ASC, m.id ASC LIMIT ?"))
    }

    /** Join order is load-bearing: see the CROSS JOIN note in globalFeedPagingQuery. */
    @Test
    fun pinsMessagesAsTheOuterLoopSoTheOrderingStaysAnIndexWalk() {
        val sql = globalFeedPagingQuery(MessageVisibilitySpec()).sql

        assertTrue(sql.contains("FROM messages m CROSS JOIN buffers b ON b.id = m.bufferId"))
    }

    @Test
    fun excludesRoomsThatAreNotPartOfTheStream() {
        val sql = globalFeedPagingQuery(MessageVisibilitySpec()).sql

        assertTrue(sql.contains("b.type IN ('CHANNEL','QUERY')"))
        // The soju console is excluded by its type alone; no name guard belongs in this query.
        assertFalse(sql.contains("bouncerserv"))
        assertTrue(sql.contains("b.dismissed = 0"))
        assertTrue(sql.contains("b.archived = 0"))
        assertTrue(sql.contains("b.pendingCloseAt IS NULL"))
        assertTrue(sql.contains("b.redirectToRoomId IS NULL"))
    }

    @Test
    fun antiJoinsRowsThatLostCanonicalEventMerging() {
        val sql = globalFeedPagingQuery(MessageVisibilitySpec()).sql

        assertTrue(sql.contains("LEFT JOIN event_redirects redirect ON redirect.losingEventId = m.id"))
        assertTrue(sql.contains("redirect.losingEventId IS NULL"))
    }

    /** Collapse has no compact rendering here, so the global feed mutes fools in either mode. */
    @Test
    fun foolsAreExcludedInCollapseModeToo() {
        val sql =
            globalFeedPagingQuery(
                MessageVisibilitySpec(fools = setOf("troll"), foolsMode = FoolsMode.COLLAPSE),
            ).sql

        assertTrue(sql.contains("m.normalizedActor"))
        assertTrue(sql.contains("LOWER(COALESCE(ni.caseMapping,'rfc1459'))"))
    }

    /** Three foldings, so three fixed terms cover every network. */
    @Test
    fun foolTermsAreKeyedOnCasemapRatherThanOnNetworkIdentity() {
        val sql =
            globalFeedPagingQuery(
                MessageVisibilitySpec(fools = setOf("Ann[ie]"), foolsMode = FoolsMode.HIDE),
            ).sql

        val casemap = "LOWER(COALESCE(ni.caseMapping,'rfc1459'))"
        assertTrue(sql.contains("($casemap = 'rfc1459' AND NOT ("))
        assertTrue(sql.contains("($casemap = 'rfc1459-strict' AND NOT ("))
        // ASCII plus every unknown advertisement, which IrcCaseMapping.Unknown folds as ASCII.
        assertTrue(sql.contains("($casemap NOT IN ('rfc1459','rfc1459-strict') AND NOT ("))
        // RFC1459 folds brackets to braces; ASCII leaves them alone, so the terms really differ.
        assertTrue("rfc1459 term missing folded actor", sql.contains("X'616e6e7b69657d'"))
        assertTrue("ascii term missing unfolded actor", sql.contains("X'616e6e5b69655d'"))

        // The only network id left in the query is the join itself.
        assertEquals(1, Regex("n\\.id").findAll(sql).count())
        assertFalse(sql.contains("n.id NOT IN"))
    }
}
