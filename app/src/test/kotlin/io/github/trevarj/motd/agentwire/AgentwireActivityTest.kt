package io.github.trevarj.motd.agentwire

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentwireActivityTest {
    @Test
    fun `turn summaries use observed identity and explicit failures without guessing missing turns`() {
        val command = tool("reused", "shell", success = false)
        val other = tool("other", "MCP tool").copy(title = "shell", body = "tests passed")
        val edit = tool("reused", "file edit").copy(tid = "t2")
        val missingTurn = tool("unattributed", "shell", success = false).copy(tid = null)
        val summaries = agentwireTurnActivity(listOf(command, command, other, edit, missingTurn))
        assertEquals(2, summaries.size)
        assertEquals(2, summaries.getValue("s" to "t").total)
        assertEquals(1, summaries.getValue("s" to "t").failed)
        assertEquals(1, summaries.getValue("s" to "t").categories["commands"])
        assertEquals(1, summaries.getValue("s" to "t").categories["other"])
        assertEquals(1, summaries.getValue("s" to "t2").categories["edits"])
        assertEquals(1, agentwireToolActivity(listOf(missingTurn)).failed)
        assertEquals(1, agentwireTurnActivity(listOf(command)).getValue("s" to "t").total)
    }

    @Test
    fun `runs stop at narration questions running tools and turn changes`() {
        val a = tool("a", "shell")
        val b = tool("b", "file read")
        val narration = a.copy(id = "reply", kind = "assistant.completed", backendItemId = "reply")
        val question = narration.copy(id = "question", kind = "request.opened")
        val running = tool("running", "web search").copy(kind = "tool.started", running = true)
        val rows = agentwireDisplayRows(listOf(a, b, narration, a.copy(id = "c", backendItemId = "c"), question, b, running, a.copy(tid = "t2")))
        assertEquals(6, rows.size)
        assertTrue(rows[0] is AgentwireDisplayRow.ToolRun)
        assertTrue(rows[1] is AgentwireDisplayRow.Card)
        assertTrue(rows.drop(2).all { it is AgentwireDisplayRow.Tool })
        assertEquals(running, (rows[4] as AgentwireDisplayRow.Tool).item)
        val differentTurns = agentwireDisplayRows(listOf(a, b.copy(tid = "t2")))
        assertTrue(differentTurns.all { it is AgentwireDisplayRow.Tool })
    }

    @Test
    fun `growing runs keep expansion identity and preserve tool detail`() {
        val first =
            tool("a", "file edit").copy(
                data =
                    buildJsonObject {
                        put("kind", "file edit")
                        put("input", "input")
                        put("output", "output")
                        put("diff", "+code")
                    },
            )
        val initial = AgentwireDisplayRow.ToolRun(listOf(first, tool("b", "shell")))
        val grown = AgentwireDisplayRow.ToolRun(initial.tools + tool("c", "agent"))
        assertEquals(initial.key, grown.key)
        assertEquals(first.data, grown.tools.first().data)
        assertEquals(1, grown.activity.categories["agents"])
    }

    @Test
    fun `summary labels describe observed calls and omit empty categories`() {
        val activity = agentwireToolActivity(listOf(tool("a", "shell"), tool("b", "file read", success = false)))
        assertEquals("Observed activity: 2 tools · 1 failed", activity.summary())
        assertEquals("Commands 1 · Reads 1", activity.categorySummary())
        assertEquals("Observed activity: 1 tool", agentwireToolActivity(listOf(tool("a", "shell"))).summary())
    }

    @Test
    fun `unidentified tools stay visible without inflating canonical counts`() {
        val first = tool("first", "shell")
        val unidentified = tool("event-only", "file read").copy(backendItemId = null)
        val timeline = listOf(first, unidentified, tool("last", "agent"))
        assertTrue(agentwireTurnActivity(listOf(unidentified)).isEmpty())
        assertEquals(2, agentwireToolActivity(timeline).total)
        assertEquals(2, agentwireTurnActivity(timeline).getValue("s" to "t").total)
        assertTrue(agentwireDisplayRows(timeline).all { it is AgentwireDisplayRow.Tool })
    }

    private fun tool(
        id: String,
        kind: String,
        success: Boolean? = null,
    ) = AgentwireTimelineItem(
        id = id,
        kind = "tool.completed",
        at = 1,
        sid = "s",
        tid = "t",
        title = kind,
        body = null,
        success = success,
        data = buildJsonObject { put("kind", kind) },
        backendItemId = id,
    )
}
