package io.github.trevarj.motd.agentwire

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workspace browser is a pure projection of the cached pages plus the hoisted expansion set,
 * so its tree shape, session counts, and search filtering are all testable without Compose.
 */
class AgentwireWorkspaceBrowserTest {

    @Test
    fun `only expanded directories contribute descendants`() {
        val children = mapOf(
            "" to listOf(directory("/work")),
            "/work" to listOf(directory("/work/motd"), directory("/work/agentwire")),
        )

        val collapsed = workspaceRows(children, expanded = emptySet(), sessions = emptyMap())
        assertEquals(listOf("/work"), collapsed.map { it.item.id })
        assertFalse(collapsed.single().expanded)

        val expanded = workspaceRows(children, expanded = setOf("/work"), sessions = emptyMap())
        assertEquals(listOf("/work", "/work/motd", "/work/agentwire"), expanded.map { it.item.id })
        assertEquals(listOf(0, 1, 1), expanded.map(AgentwireWorkspaceRow::depth))
    }

    @Test
    fun `a leaf directory never unfolds children`() {
        val children = mapOf(
            "" to listOf(directory("/work/motd", hasChildren = false)),
            // A stale page for a directory the bridge now calls a leaf must not be rendered.
            "/work/motd" to listOf(directory("/work/motd/app")),
        )

        val rows = workspaceRows(children, expanded = setOf("/work/motd"), sessions = emptyMap())

        assertEquals(listOf("/work/motd"), rows.map { it.item.id })
        assertFalse(rows.single().hasChildren)
        assertTrue(rows.single().expanded)
    }

    @Test
    fun `session counts prefer loaded sessions over the bridge hint`() {
        val children = mapOf(
            "" to listOf(
                directory("/work/motd", sessionCount = 7),
                directory("/work/agentwire", sessionCount = 2),
                directory("/work/unknown"),
            ),
        )
        val sessions = mapOf("/work/motd" to listOf(session("sid-a", "/work/motd")))

        val rows = workspaceRows(
            children = children,
            expanded = setOf("/work/motd"),
            sessions = sessions,
            loadedSessionDirectories = setOf("/work/motd"),
        )

        assertEquals(1, rows[0].sessionCount)
        assertEquals(2, rows[1].sessionCount)
        assertNull(rows[2].sessionCount)
    }

    @Test
    fun `search keeps directories that match by name path session or descendant`() {
        val children = mapOf(
            "" to listOf(directory("/work"), directory("/spare")),
            "/work" to listOf(directory("/work/motd"), directory("/work/notes")),
            "/spare" to listOf(directory("/spare/scratch")),
        )
        val sessions = mapOf("/spare/scratch" to listOf(session("sid-a", "/spare/scratch", "Fix motd lint")))

        val rows = workspaceRows(children, emptySet(), sessions, emptySet(), query = "motd")

        // A search reaches into collapsed branches: the matching directory and the directory
        // holding a matching session both survive, along with their ancestors.
        assertEquals(listOf("/work", "/work/motd", "/spare", "/spare/scratch"), rows.map { it.item.id })
        assertTrue(rows.all(AgentwireWorkspaceRow::expanded))

        val empty = workspaceRows(children, emptySet(), sessions, emptySet(), query = "nothing-here")
        assertTrue(empty.isEmpty())
    }

    @Test
    fun `a directory cycle cannot recurse forever`() {
        val children = mapOf(
            "" to listOf(directory("/work")),
            "/work" to listOf(directory("/work")),
        )

        val rows = workspaceRows(children, expanded = setOf("/work"), sessions = emptyMap())

        assertEquals(listOf("/work", "/work"), rows.map { it.item.id })
    }

    @Test
    fun `runtime status prefers the pushed registry over page data`() {
        val idle = session("sid-a", "/work/motd")
        val busyPage = session("sid-b", "/work/motd", busy = true)

        assertNull(agentwireSessionRuntimeStatus(idle))
        assertEquals("Running", agentwireSessionRuntimeStatus(busyPage))
        // The registry is newer than the page it came with, so it decides both fields.
        assertEquals("Waiting", agentwireSessionRuntimeStatus(busyPage, status("sid-b", true, listOf("waiting_approval"))))
        assertNull(agentwireSessionRuntimeStatus(busyPage, status("sid-b", false, emptyList())))
        assertEquals("Running", agentwireSessionRuntimeStatus(idle, status("sid-a", true, emptyList())))
    }

    @Test
    fun `a workspace page reopens what the user had expanded`() {
        val state = AgentwireUiState(
            workspaceChildren = mapOf(
                "" to listOf(directory("/work")),
                "/work" to listOf(directory("/work/motd"), directory("/work/agentwire")),
            ),
            expandedDirectories = setOf("/work", "/work/motd"),
            loadedSessionDirectories = setOf("/work"),
        )

        // The root page reopens nothing new: /work already has its sessions.
        assertTrue(agentwireDirectoriesToReopen(state, parent = null).isEmpty())
        assertEquals(
            listOf("/work/motd"),
            agentwireDirectoriesToReopen(state, parent = "/work").map { it.id },
        )
    }

    @Test
    fun `roots open by default only until the user has expanded something`() {
        val roots = mapOf("" to listOf(directory("/work"), directory("/spare")))
        val fresh = AgentwireUiState(workspaceChildren = roots)

        assertEquals(
            listOf("/work", "/spare"),
            agentwireDirectoriesToReopen(fresh, parent = null).map { it.id },
        )

        val collapsedRoot = fresh.copy(expandedDirectories = setOf("/spare"))
        assertEquals(
            listOf("/spare"),
            agentwireDirectoriesToReopen(collapsedRoot, parent = null).map { it.id },
        )
    }

    @Test
    fun `expanding a directory loads it once`() {
        val state = AgentwireUiState(
            workspaceChildren = mapOf("/work" to listOf(directory("/work/motd"))),
            loadedSessionDirectories = setOf("/work"),
        )

        assertFalse(agentwireExpansionNeedsLoad(state, "/work"))
        // Children cached but sessions never fetched: expanding still has to ask.
        assertTrue(agentwireExpansionNeedsLoad(state.copy(loadedSessionDirectories = emptySet()), "/work"))
        assertTrue(agentwireExpansionNeedsLoad(state, "/work/motd"))
    }

    private fun directory(
        path: String,
        hasChildren: Boolean = true,
        sessionCount: Int? = null,
    ) = AgentwireListItem(
        id = path,
        title = path.trimEnd('/').substringAfterLast('/'),
        subtitle = path,
        raw = buildJsonObject {
            put("path", path)
            put("hasChildren", hasChildren)
            sessionCount?.let { put("sessionCount", it) }
        },
    )

    private fun session(
        sid: String,
        cwd: String,
        title: String = "Session $sid",
        busy: Boolean = false,
    ) = AgentwireListItem(
        id = sid,
        title = title,
        subtitle = cwd,
        raw = buildJsonObject {
            put("busy", busy)
            put("flags", buildJsonArray { })
        },
    )

    private fun status(sid: String, busy: Boolean, flags: List<String>) = AgentwireSessionStatus(
        sid = sid,
        busy = busy,
        flags = flags,
        cwd = null,
        tuiAttached = null,
        at = 0L,
    )
}
