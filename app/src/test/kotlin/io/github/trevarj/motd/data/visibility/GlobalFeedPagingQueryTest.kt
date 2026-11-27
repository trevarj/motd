package io.github.trevarj.motd.data.visibility

import io.github.trevarj.motd.data.prefs.FoolPreferences
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

    @Test
    fun onlyReactionScopesBuildAReactionSeekAndAllCapsBothBranchesBeforeMerging() {
        for (filter in listOf(null, ActivityFilter.MENTIONS, ActivityFilter.KEYWORDS)) {
            val query = globalFeedPagingQuery(MessageVisibilitySpec(), limit = 50, activityFilter = filter)
            assertFalse(query.sql, "reactions r" in query.sql)
            assertFalse(query.sql, "UNION ALL" in query.sql)
            assertEquals(1, query.argCount)
        }

        val mixed = globalFeedPagingQuery(MessageVisibilitySpec(), limit = 50, activityFilter = ActivityFilter.ALL)
        assertTrue(mixed.sql, "ORDER BY m.serverTime DESC, m.id DESC LIMIT ?) UNION ALL" in mixed.sql)
        assertTrue(mixed.sql, "ORDER BY r.activityAt DESC, r.id DESC LIMIT ?) ORDER BY feedTime DESC, feedKind DESC, feedId DESC LIMIT ?" in mixed.sql)
        assertEquals(3, mixed.argCount)

        val reactions = globalFeedPagingQuery(MessageVisibilitySpec(), limit = 50, activityFilter = ActivityFilter.REACTIONS)
        assertTrue(reactions.sql, "r.emoji AS activity_reaction_emoji" in reactions.sql)
        assertTrue(reactions.sql, "r.activityAt IS NOT NULL" in reactions.sql)
        assertFalse(reactions.sql, "m.hasMention" in reactions.sql)
        assertFalse(reactions.sql, "UNION ALL" in reactions.sql)
        assertEquals(1, reactions.argCount)
    }

    @Test
    fun actorFoldingIsOmittedWhenNoFoolRuleCanExcludeAReaction() {
        val permitted = FoolPreferences(FoolsMode.DIMMED, notifyMentions = true, notifyDirectMessages = true)
        val permittedSpec = MessageVisibilitySpec(fools = setOf("fool"), foolPreferences = mapOf("fool" to permitted))
        for (spec in listOf(MessageVisibilitySpec(), permittedSpec)) {
            val reactions = globalFeedPagingQuery(spec, limit = 50, activityFilter = ActivityFilter.REACTIONS)
            assertFalse(reactions.sql, "ascii_fold" in reactions.sql)
        }
    }

    @Test
    fun aDifferentSourceSeeksOnTimestampWithoutComparingIds() {
        val messageKey = GlobalFeedKey(400, 9)
        val reactionKey = GlobalFeedKey(400, 9, GlobalFeedKind.REACTION)
        for (seek in GlobalFeedSeek.entries) {
            val newer = seek == GlobalFeedSeek.NEWER
            val fromMessage = globalFeedPagingQuery(MessageVisibilitySpec(), messageKey, seek, 50, ActivityFilter.ALL)
            assertTrue(fromMessage.sql, "AND r.activityAt ${if (newer) ">=" else "<"} ? ORDER BY" in fromMessage.sql)
            assertEquals(7, fromMessage.argCount)

            val fromReaction = globalFeedPagingQuery(MessageVisibilitySpec(), reactionKey, seek, 50, ActivityFilter.ALL)
            assertTrue(fromReaction.sql, "AND m.serverTime ${if (newer) ">" else "<="} ? ORDER BY" in fromReaction.sql)
            assertEquals(8, fromReaction.argCount)
            val reactionComparison =
                when (seek) {
                    GlobalFeedSeek.OLDER -> "<"
                    GlobalFeedSeek.OLDER_OR_AT -> "<="
                    GlobalFeedSeek.NEWER -> ">"
                }
            assertTrue(fromReaction.sql, "AND r.activityAt = ? AND r.id $reactionComparison ? ORDER BY" in fromReaction.sql)
            assertTrue(fromReaction.sql, "AND r.activityAt ${if (newer) ">" else "<"} ? ORDER BY" in fromReaction.sql)
        }
    }
}
