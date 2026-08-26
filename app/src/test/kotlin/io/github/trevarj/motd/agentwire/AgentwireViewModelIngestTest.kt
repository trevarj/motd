package io.github.trevarj.motd.agentwire

import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.encodeAgentwireEnvelope
import io.github.trevarj.motd.irc.client.EventMapper
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Isupport
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentwireViewModelIngestTest {
    @Test
    fun `only topic provisioned backend can establish or retain Agentwire state`() {
        val ingestor = AgentwireEventIngestor()
        val syncId = "11111111-1111-4111-8111-111111111111"
        var state = AgentwireUiState(
            channel = "#codex",
            controllerAccount = "controller",
            backendAccount = "agent-a",
            syncing = true,
        )

        val forgedHello = hello(syncId, "evil-epoch", setOf("turn.prompt"))
        state = rejected(ingestor, state, inbound("agent-b", forgedHello), syncId)
        assertEquals("Rejected Agentwire event from untrusted account: agent-b", state.error)
        assertNull(state.botAccount)
        assertNull(state.epoch)
        assertTrue(state.actions.isEmpty())
        assertNull(state.activeSid)
        assertTrue(state.requests.isEmpty())

        state = applied(ingestor, state, inbound("agent-a", hello(syncId, "epoch-a", setOf("turn.prompt"))), syncId)
        state = applied(ingestor, state, inbound("agent-a", snapshot(syncId, "session-a")), syncId)
        assertEquals("agent-a", state.botAccount)
        assertEquals("epoch-a", state.epoch)
        assertEquals(setOf("turn.prompt"), state.actions)
        assertEquals("session-a", state.activeSid)
        assertTrue(!state.syncing)

        val pinned = state
        state = rejected(ingestor, state, inbound("agent-b", hello(syncId, "evil-epoch", setOf("request.respond"))), syncId)
        assertEquals(pinned.botAccount, state.botAccount)
        assertEquals(pinned.epoch, state.epoch)
        assertEquals(pinned.actions, state.actions)
        assertEquals(pinned.activeSid, state.activeSid)
        assertEquals("Rejected Agentwire event from untrusted account: agent-b", state.error)

        // Topic metadata may deliberately rotate the backend account. A reconnect must reject
        // the former identity and only establish state from the newly provisioned one.
        ingestor.reset()
        val reconnect = state.copy(
            backendAccount = "agent-c",
            syncing = true,
            epoch = null,
            botAccount = null,
            actions = emptySet(),
            activeSid = null,
            requests = emptyList(),
            error = null,
        )
        state = rejected(ingestor, reconnect, inbound("agent-a", hello("22222222-2222-4222-8222-222222222222", "old-epoch", setOf("turn.prompt"))), "22222222-2222-4222-8222-222222222222")
        assertNull(state.botAccount)
        assertNull(state.epoch)
        assertTrue(state.actions.isEmpty())
        assertNull(state.activeSid)
        assertTrue(state.requests.isEmpty())
        state = applied(ingestor, state.copy(error = null), inbound("agent-c", hello("22222222-2222-4222-8222-222222222222", "epoch-c", setOf("request.respond"))), "22222222-2222-4222-8222-222222222222")
        assertEquals("agent-c", state.botAccount)
        assertEquals("epoch-c", state.epoch)
        assertEquals(setOf("request.respond"), state.actions)

        // Controller authorization and event-source provisioning remain distinct fields even
        // when a deployment intentionally assigns them the same IRC account.
        ingestor.reset()
        state = applied(
            ingestor,
            AgentwireUiState(channel = "#codex", controllerAccount = "shared", backendAccount = "shared", syncing = true),
            inbound("shared", hello(syncId, "shared-epoch", setOf("turn.prompt"))),
            syncId,
        )
        assertEquals("shared", state.botAccount)
        assertEquals("shared-epoch", state.epoch)
    }

    @Test
    fun `applied envelopes feed the session log until a resync clears it`() {
        val ingestor = AgentwireEventIngestor()
        val log = AgentwireLogStore()
        val syncId = "33333333-3333-4333-8333-333333333333"
        var state = AgentwireUiState(
            channel = "#codex",
            controllerAccount = "controller",
            backendAccount = "agent-a",
            syncing = true,
        )
        state = applied(ingestor, state, inbound("agent-a", hello(syncId, "epoch-a", setOf("turn.prompt"))), syncId)
        state = applied(ingestor, state, inbound("agent-a", snapshot(syncId, "session-a")), syncId)

        // The ViewModel captures from the envelope, so the log keeps what the timeline strips.
        listOf(
            tool("tool.started", at = 10) { put("id", "i1"); put("kind", "shell"); put("input", "rg needle") },
            tool("tool.completed", at = 11) {
                put("id", "i1"); put("kind", "shell"); put("input", "rg needle")
                put("output", "src/found.kt"); put("status", "completed"); put("exitCode", 0)
            },
            AgentwireEnvelope(
                kind = "assistant.completed", type = "event",
                id = "cccccccc-cccc-4ccc-8ccc-cccccccccccc", at = 12, instance = "agent",
                epoch = "epoch-a", sid = "session-a", tid = "t1", iid = "a1", history = true,
                data = buildJsonObject { put("content", "backfilled answer") },
            ),
        ).forEach { envelope ->
            val result = ingestor.ingest(state, inbound("agent-a", envelope), syncId)
            state = (result as AgentwireEventIngestor.Result.Applied).state
            log.capture(result.envelope)
        }

        val entries = log.entries()
        assertEquals(listOf("assistant.completed", "tool.completed"), entries.map(AgentwireLogEntry::kind))
        assertEquals("src/found.kt", entries.last().output)
        assertEquals("rg needle", entries.last().input)
        assertEquals("backfilled answer", entries.first().body)
        // The stripped timeline no longer carries either payload.
        assertNull(state.timeline.first { it.kind == "tool.completed" }.data.string("output"))
        assertEquals(listOf("src/found.kt"), agentwireLogQuery(entries, text = "FOUND").map { it.output })

        log.clear()
        assertTrue(log.entries().isEmpty())
    }

    @Test
    fun `grouped timeline entries are derived once per timeline replacement`() {
        val previous = AgentwireUiState(
            timeline = listOf(AgentwireTimelineItem("a", "tool.started", 1, "s1", "t1", "$ ls", null, running = true)),
        )
        val derived = previous.withDerived(AgentwireUiState())
        val group = derived.timelineEntries.single() as AgentwireTimelineEntry.ToolGroup
        assertEquals(listOf("a"), group.items.map(AgentwireTimelineItem::id))
        assertTrue(group.running)

        // An envelope that leaves the timeline alone must not pay for regrouping.
        val unrelated = derived.copy(busy = true)
        assertTrue(unrelated.withDerived(derived) === unrelated)
    }

    private fun tool(kind: String, at: Long, data: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        AgentwireEnvelope(
            kind = kind, type = "event", id = java.util.UUID.randomUUID().toString(), at = at,
            instance = "agent", epoch = "epoch-a", sid = "session-a", tid = "t1", iid = "i1",
            data = buildJsonObject(data),
        )

    private fun applied(
        ingestor: AgentwireEventIngestor,
        state: AgentwireUiState,
        event: IrcEvent,
        syncId: String,
    ): AgentwireUiState = (ingestor.ingest(state, event, syncId) as AgentwireEventIngestor.Result.Applied).state

    private fun rejected(
        ingestor: AgentwireEventIngestor,
        state: AgentwireUiState,
        event: IrcEvent,
        syncId: String,
    ): AgentwireUiState = (ingestor.ingest(state, event, syncId) as AgentwireEventIngestor.Result.Rejected).state

    private fun inbound(account: String, envelope: AgentwireEnvelope): IrcEvent = checkNotNull(
        EventMapper({ "me" }, { Isupport() }).map(
            IrcMessage.parse(
                "@account=$account;$AGENTWIRE_TAG=${escapeTagValue(encodeAgentwireEnvelope(envelope))}" +
                    " :$account!u@h TAGMSG #codex",
            ),
        ),
    )

    /** Payload JSON carries spaces and semicolons, which are only legal in a tag when escaped. */
    private fun escapeTagValue(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\:")
        .replace(" ", "\\s")
        .replace("\r", "\\r")
        .replace("\n", "\\n")

    private fun hello(reply: String, epoch: String, actions: Set<String>) = AgentwireEnvelope(
        kind = "agent.hello",
        type = "event",
        id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
        at = 1,
        instance = "agent",
        epoch = epoch,
        reply = reply,
        data = buildJsonObject {
            put("epoch", epoch)
            put("actions", buildJsonArray { actions.forEach { add(JsonPrimitive(it)) } })
        },
    )

    private fun snapshot(reply: String, sid: String) = AgentwireEnvelope(
        kind = "channel.snapshot",
        type = "event",
        id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
        at = 2,
        instance = "agent",
        epoch = "epoch-a",
        reply = reply,
        data = buildJsonObject {
            put("binding", buildJsonObject { put("sid", sid) })
        },
    )
}
