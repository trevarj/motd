package io.github.trevarj.motd.ui.settings.bouncer

import io.github.trevarj.motd.bouncer.BouncerServResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BouncerControlModelsTest {
    @Test fun console_suggestions_are_prefix_ranked_and_bounded() {
        val paths = setOf(
            "network create",
            "network delete",
            "network status",
            "server status",
        )
        assertEquals(
            listOf("network create", "network delete", "network status"),
            bouncerCommandSuggestions(paths, "  net"),
        )
        assertTrue(bouncerCommandSuggestions(paths, "network create").isEmpty())
    }

    @Test fun channel_status_parser_accepts_known_rows_and_ignores_drift() {
        assertEquals(
            listOf(
                BouncerChannelRow("#attached", "joined", detached = false),
                BouncerChannelRow("#quiet", "parted", detached = true),
            ),
            parseChannelStatus(
                listOf("#attached [joined]", "future output", "#quiet [parted, detached]"),
            ),
        )
    }

    @Test fun deletion_token_is_ephemeral_and_requires_exact_username() {
        val reply = listOf("To confirm user deletion, send \"user delete bob b4f2aa\"")
        assertEquals("b4f2aa", extractUserDeletionToken("bob", reply))
        assertNull(extractUserDeletionToken("alice", reply))
    }

    @Test fun result_summary_never_surfaces_sensitive_failure_details() {
        val result = BouncerServResult.Failed("user update -password <redacted>", "secret leaked")
        assertEquals("BouncerServ command failed", result.safeSummary())
    }
}
