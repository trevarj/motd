package io.github.trevarj.motd.agentwire

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentwireTimelineGroupsTest {

    @Test
    fun `consecutive tools of one turn collapse into a counted group`() {
        val timeline = listOf(
            tool("a", "t1", kind = "read"),
            tool("b", "t1", kind = "read"),
            tool("c", "t1", kind = "read"),
            tool("d", "t1", kind = "bash"),
            tool("e", "t1", kind = "bash"),
        )

        val group = agentwireTimelineGroups(timeline).single() as AgentwireTimelineEntry.ToolGroup
        assertEquals("tools:a", group.id)
        assertEquals("t1", group.tid)
        assertEquals(listOf("a", "b", "c", "d", "e"), group.items.map(AgentwireTimelineItem::id))
        assertEquals("5 tools · read ×3, bash ×2", group.summary)
        assertFalse(group.running)
        assertFalse(group.anyFailed)
    }

    @Test
    fun `non-tool items and a new turn split the run`() {
        val timeline = listOf(
            item("prompt", "user.prompt", "t1"),
            tool("a", "t1", kind = "read"),
            tool("b", "t1", kind = "read"),
            item("answer", "assistant.completed", "t1"),
            tool("c", "t1", kind = "bash"),
            tool("d", "t2", kind = "bash"),
        )

        val entries = agentwireTimelineGroups(timeline)

        assertEquals(
            listOf("prompt", "tools:a", "answer", "tools:c", "tools:d"),
            entries.map(AgentwireTimelineEntry::id),
        )
        assertTrue(entries[0] is AgentwireTimelineEntry.Single)
        assertEquals("2 tools · read ×2", (entries[1] as AgentwireTimelineEntry.ToolGroup).summary)
        assertEquals("1 tool · bash", (entries[3] as AgentwireTimelineEntry.ToolGroup).summary)
        assertEquals("t2", (entries[4] as AgentwireTimelineEntry.ToolGroup).tid)
    }

    @Test
    fun `a group reports running and failed members`() {
        val running = agentwireTimelineGroups(
            listOf(tool("a", "t1", kind = "bash", success = true), tool("b", "t1", kind = "bash", running = true)),
        ).single() as AgentwireTimelineEntry.ToolGroup
        assertTrue(running.running)
        assertFalse(running.anyFailed)

        val failed = agentwireTimelineGroups(
            listOf(tool("a", "t1", kind = "bash", success = true), tool("b", "t1", kind = "bash", success = false)),
        ).single() as AgentwireTimelineEntry.ToolGroup
        assertFalse(failed.running)
        assertTrue(failed.anyFailed)
    }

    @Test
    fun `a label supplies the name when the backend omits a machine kind`() {
        val group = agentwireTimelineGroups(
            listOf(
                AgentwireTimelineItem("a", "tool.completed", 1, "s1", "t1", "apply_patch src/main.kt", null),
                AgentwireTimelineItem("b", "tool.completed", 2, "s1", "t1", "apply_patch src/other.kt", null),
            ),
        ).single() as AgentwireTimelineEntry.ToolGroup

        assertEquals("2 tools · apply_patch ×2", group.summary)
    }

    private fun tool(
        id: String,
        tid: String,
        kind: String,
        running: Boolean = false,
        success: Boolean? = null,
    ) = AgentwireTimelineItem(
        id = id,
        kind = if (running) "tool.started" else "tool.completed",
        at = 1,
        sid = "s1",
        tid = tid,
        title = "$ $kind something",
        body = null,
        running = running,
        success = success,
        data = buildJsonObject { put("kind", kind) },
    )

    private fun item(id: String, kind: String, tid: String) =
        AgentwireTimelineItem(id, kind, 1, "s1", tid, "Title", "body")
}
