package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import java.util.UUID
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentwireLogTest {

    @Test
    fun `the log is newest first and evicts the oldest entries past its bound`() {
        val store = AgentwireLogStore(capacity = 3)
        (1..5).forEach { store.append(entry("e$it", at = it.toLong())) }

        assertEquals(listOf("e5", "e4", "e3"), store.entries().map(AgentwireLogEntry::id))

        store.clear()
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `an updated entry replaces its predecessor in place instead of adding a row`() {
        val store = AgentwireLogStore(capacity = 3)
        store.append(entry("tool:s1:t1:i1", at = 1, output = null, status = "running"))
        store.append(entry("later", at = 2))
        store.append(entry("tool:s1:t1:i1", at = 3, output = "done", status = "completed"))

        val entries = store.entries()
        assertEquals(listOf("later", "tool:s1:t1:i1"), entries.map(AgentwireLogEntry::id))
        assertEquals("completed", entries.last().status)
        assertEquals("done", entries.last().output)
    }

    @Test
    fun `a tool lifecycle collapses onto one entry that keeps the full payload`() {
        val store = AgentwireLogStore()
        val envelopes = listOf(
            envelope("tool.started", sid = "s1", tid = "t1", iid = "i1", at = 1) {
                put("id", "i1"); put("kind", "shell"); put("label", "$ git status")
                put("input", "git status")
            },
            envelope("tool.completed", sid = "s1", tid = "t1", iid = "i1", at = 2) {
                put("id", "i1"); put("kind", "shell"); put("label", "$ git status")
                put("input", "git status"); put("output", "M bridge.py")
                put("status", "completed"); put("exitCode", 0); put("success", true)
            },
        )
        envelopes.forEach { assertTrue(store.capture(it)) }

        val captured = store.entries().single()
        assertEquals("tool:s1:t1:i1", captured.id)
        assertEquals("$ git status", captured.title)
        assertEquals("git status", captured.input)
        assertEquals("M bridge.py", captured.output)
        assertEquals(0, captured.exitCode)
        assertEquals(true, captured.success)
        // Tool text is addressed by its own fields; body stays reserved for non-tool content.
        assertEquals(null, captured.body)
    }

    @Test
    fun `only interesting kinds are captured and non-tool content lands in the body`() {
        val store = AgentwireLogStore()

        assertTrue(store.capture(envelope("assistant.completed", sid = "s1", tid = "t1", at = 1) {
            put("content", "Here is the patch")
        }))
        assertTrue(store.capture(envelope("plan.updated", sid = "s1", tid = "t1", at = 2) {
            put("summary", "Ship it"); put("completedSteps", 1); put("totalSteps", 2)
        }))
        assertTrue(!store.capture(envelope("usage.updated", sid = "s1", tid = "t1", at = 3) {
            put("tokens", 12)
        }))
        assertTrue(!store.capture(envelope("session.status", sid = "s1", at = 4) { put("busy", true) }))

        assertEquals(listOf("Plan", "Assistant"), store.entries().map(AgentwireLogEntry::title))
        assertEquals("Here is the patch", store.entries().last().body)
        assertEquals("Ship it\n\n1 of 2 steps complete", store.entries().first().body)
    }

    @Test
    fun `queries filter by text kind prefix and session`() {
        val entries = listOf(
            entry("a", at = 3, kind = "tool.completed", sid = "s1", title = "$ rg needle", output = "found"),
            entry("b", at = 2, kind = "assistant.completed", sid = "s1", title = "Assistant", output = null, body = "no match here"),
            entry("c", at = 1, kind = "tool.completed", sid = "s2", title = "$ ls", output = "NEEDLE.txt"),
        )

        assertEquals(listOf("a", "c"), agentwireLogQuery(entries, kinds = setOf("tool")).map(AgentwireLogEntry::id))
        assertEquals(listOf("b"), agentwireLogQuery(entries, kinds = setOf("assistant")).map(AgentwireLogEntry::id))
        // Case-insensitive across every captured field, title and output alike.
        assertEquals(listOf("a", "c"), agentwireLogQuery(entries, text = "needle").map(AgentwireLogEntry::id))
        assertEquals(listOf("a"), agentwireLogQuery(entries, text = "needle", sid = "s1").map(AgentwireLogEntry::id))
        assertEquals(listOf("b"), agentwireLogQuery(entries, text = "MATCH").map(AgentwireLogEntry::id))
        assertEquals(entries, agentwireLogQuery(entries))
    }

    private fun envelope(
        kind: String,
        sid: String? = null,
        tid: String? = null,
        iid: String? = null,
        at: Long = 1,
        data: JsonObjectBuilder.() -> Unit = {},
    ) = AgentwireEnvelope(
        kind, "event", UUID.randomUUID().toString(), at, "bridge", "epoch",
        sid = sid, tid = tid, iid = iid, data = buildJsonObject(data),
    )

    private fun entry(
        id: String,
        at: Long,
        kind: String = "tool.completed",
        sid: String? = "s1",
        title: String = "Tool",
        output: String? = "out",
        status: String? = "completed",
        body: String? = null,
    ) = AgentwireLogEntry(
        id = id, at = at, kind = kind, title = title, sid = sid, tid = "t1",
        output = output, status = status, body = body,
    )
}
