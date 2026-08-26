package io.github.trevarj.motd.agentwire

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentwireDrawerModelTest {

    @Test
    fun `sections order bound live then recent and deduplicate by sid`() {
        val state = AgentwireUiState(
            activeSid = "sid-bound",
            cwd = "/work/motd",
            backend = "codex",
            liveSessions = listOf(
                session("sid-bound", "Bound session", "/work/motd"),
                session("sid-live", "Live session", "/work/other"),
            ),
            recentSessions = listOf(
                AgentwireRecentSession("sid-live", "Stale title", "/work/stale", "codex"),
                AgentwireRecentSession("sid-recent", "Recent session", "/work/archive", "codex"),
            ),
        )

        val rows = agentwireDrawerRows(state)

        assertEquals(listOf("sid-bound", "sid-live", "sid-recent"), rows.map(AgentwireDrawerRow::sid))
        assertEquals(
            listOf(
                AgentwireDrawerSection.BOUND,
                AgentwireDrawerSection.LIVE,
                AgentwireDrawerSection.RECENT,
            ),
            rows.map(AgentwireDrawerRow::section),
        )
        // The live page is fresher than the remembered copy of the same session.
        assertEquals("Live session", rows[1].title)
        assertTrue(rows[0].attached)
        assertFalse(rows[1].attached)
    }

    @Test
    fun `bound session is listed once even when absent from the live page`() {
        val state = AgentwireUiState(
            activeSid = "sid-bound",
            cwd = "/work/motd",
            recentSessions = listOf(AgentwireRecentSession("sid-bound", "Remembered", "/work/motd")),
        )

        val rows = agentwireDrawerRows(state)

        assertEquals(1, rows.size)
        assertEquals(AgentwireDrawerSection.BOUND, rows[0].section)
        assertEquals("Remembered", rows[0].title)
    }

    @Test
    fun `status registry overrides the session page snapshot`() {
        val state = AgentwireUiState(
            liveSessions = listOf(session("sid-live", "Live session", "/work/motd", busy = false)),
            sessionStatuses = mapOf(
                "sid-live" to AgentwireSessionStatus(
                    sid = "sid-live",
                    busy = true,
                    flags = emptyList(),
                    cwd = "/work/moved",
                    tuiAttached = true,
                    at = 5L,
                ),
            ),
        )

        val row = agentwireDrawerRows(state).single()

        assertEquals(AgentwireDrawerStatus.WORKING, row.status)
        assertEquals("/work/moved", row.cwd)
        assertTrue(row.tuiAttached)
    }

    @Test
    fun `session page status is used when the registry has no entry`() {
        val state = AgentwireUiState(
            liveSessions = listOf(
                session("sid-live", "Live session", "/work/motd", busy = true, tuiAttached = true),
            ),
        )

        val row = agentwireDrawerRows(state).single()

        assertEquals(AgentwireDrawerStatus.WORKING, row.status)
        assertTrue(row.tuiAttached)
    }

    @Test
    fun `waiting flags and open requests mark a session blocked`() {
        val state = AgentwireUiState(
            liveSessions = listOf(
                session("sid-flag", "Waiting flag", "/work/a", busy = true),
                session("sid-request", "Open request", "/work/b"),
                session("sid-idle", "Idle", "/work/c"),
            ),
            requests = listOf(
                AgentwireRequest(
                    rid = "rid-1", type = "approval", summary = null, redacted = false,
                    inactive = true, canSkip = false, sid = "sid-request",
                ),
            ),
            sessionStatuses = mapOf(
                "sid-flag" to AgentwireSessionStatus(
                    sid = "sid-flag", busy = true, flags = listOf("waitingForInput"),
                    cwd = null, tuiAttached = null, at = 1L,
                ),
            ),
        )

        val rows = agentwireDrawerRows(state).associateBy(AgentwireDrawerRow::sid)

        assertEquals(AgentwireDrawerStatus.BLOCKED, rows.getValue("sid-flag").status)
        assertEquals(AgentwireDrawerStatus.BLOCKED, rows.getValue("sid-request").status)
        assertEquals(AgentwireDrawerStatus.IDLE, rows.getValue("sid-idle").status)
    }

    @Test
    fun `directory shows the last path segment with the full path retained`() {
        val state = AgentwireUiState(
            liveSessions = listOf(
                session("sid-nested", "Nested", "/home/trev/Workspace/motd"),
                session("sid-root", "Root", "/"),
            ),
            recentSessions = listOf(AgentwireRecentSession("sid-unknown", "No workspace")),
        )

        val rows = agentwireDrawerRows(state).associateBy(AgentwireDrawerRow::sid)

        assertEquals("motd", rows.getValue("sid-nested").directory)
        assertEquals("/home/trev/Workspace/motd", rows.getValue("sid-nested").cwd)
        assertEquals("/", rows.getValue("sid-root").directory)
        assertEquals(null, rows.getValue("sid-unknown").directory)
    }

    @Test
    fun `untitled sessions fall back to a sid prefix`() {
        val state = AgentwireUiState(activeSid = "0123456789abcdefghij")

        assertEquals("0123456789ab", agentwireDrawerRows(state).single().title)
    }

    @Test
    fun `recents are newest first deduplicated and capped`() {
        var recents = emptyList<AgentwireRecentSession>()
        repeat(12) { index ->
            recents = agentwireRecentsWith(recents, AgentwireRecentSession("sid-$index", "Session $index"))
        }

        assertEquals(AGENTWIRE_RECENT_SESSION_LIMIT, recents.size)
        assertEquals("sid-11", recents.first().sid)
        assertEquals("sid-2", recents.last().sid)

        recents = agentwireRecentsWith(recents, AgentwireRecentSession("sid-5", "Renamed"))

        assertEquals(AGENTWIRE_RECENT_SESSION_LIMIT, recents.size)
        assertEquals("sid-5", recents.first().sid)
        assertEquals("Renamed", recents.first().title)
        assertEquals(1, recents.count { it.sid == "sid-5" })
    }

    @Test
    fun `recents survive a JSON round trip and tolerate corrupt storage`() {
        val recents = listOf(
            AgentwireRecentSession("sid-1", "One", "/work/one", "codex"),
            AgentwireRecentSession("sid-2", "Two"),
        )

        assertEquals(recents, decodeAgentwireRecents(encodeAgentwireRecents(recents)))
        assertEquals(emptyList<AgentwireRecentSession>(), decodeAgentwireRecents(null))
        assertEquals(emptyList<AgentwireRecentSession>(), decodeAgentwireRecents(""))
        assertEquals(emptyList<AgentwireRecentSession>(), decodeAgentwireRecents("not json"))
    }

    @Test
    fun `only the bound row counts non-terminal subagents`() {
        val state = AgentwireUiState(
            activeSid = "sid-bound",
            liveSessions = listOf(
                session("sid-bound", "Bound session", "/work/motd"),
                session("sid-live", "Live session", "/work/other"),
            ),
            subagents = listOf(
                AgentwireSubagent("a1", "Explore", "map", "running", true),
                AgentwireSubagent("a2", "Terra", "build", "queued", false),
                AgentwireSubagent("a3", "Terra", "done", "completed", false),
                AgentwireSubagent("a4", "Terra", "broke", "failed", false),
            ),
        )

        val rows = agentwireDrawerRows(state)

        assertEquals(2, rows[0].activeSubagents)
        // The registry describes the bound session only, so no other row may claim it.
        assertEquals(0, rows[1].activeSubagents)
        assertEquals(0, agentwireDrawerRows(state.copy(subagents = emptyList()))[0].activeSubagents)
    }

    private fun session(
        sid: String,
        title: String,
        cwd: String?,
        busy: Boolean = false,
        tuiAttached: Boolean = false,
        flags: List<String> = emptyList(),
    ) = AgentwireListItem(
        id = sid,
        title = title,
        subtitle = cwd,
        raw = buildJsonObject {
            put("sid", sid)
            put("busy", busy)
            put("tuiAttached", tuiAttached)
            put("flags", JsonArray(flags.map(::JsonPrimitive)))
        },
    )
}
