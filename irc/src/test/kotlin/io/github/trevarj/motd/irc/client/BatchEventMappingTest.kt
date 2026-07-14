package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.ext.BatchChild
import io.github.trevarj.motd.irc.ext.BatchTree
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchEventMappingTest {
    private val client = IrcClient(
        IrcClientConfig("example", 6697, true, "me", "me", "Me"),
        FakeTransport().factory(),
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    @Test fun `typed netsplit survives inside chathistory`() {
        val split = BatchTree(
            "split",
            "netsplit",
            listOf("a.example", "b.example"),
            listOf(
                BatchChild.Message(msg("@time=2026-01-01T00:00:00Z :Alice!u@h QUIT :split")),
                BatchChild.Message(msg("@time=2026-01-01T00:00:01Z :Bob!u@h QUIT :split")),
            ),
        )
        val history = BatchTree(
            "history",
            "chathistory",
            listOf("#room"),
            listOf(BatchChild.Nested(split)),
        )

        val outer = client.mapBatchTree(history).single() as IrcEvent.HistoryBatch
        val event = outer.events.single() as IrcEvent.NetworkBatch
        assertEquals(IrcEvent.NetworkBatchKind.NETSPLIT, event.kind)
        assertEquals("#room", event.target)
        assertEquals(listOf("Alice", "Bob"), event.events.map { (it as IrcEvent.Quit).nick })
    }

    @Test fun `malformed known batch recursively degrades while typed child survives unknown parent`() {
        val malformed = BatchTree(
            "bad",
            "netsplit",
            listOf("only-one-server"),
            listOf(BatchChild.Message(msg(":Alice!u@h QUIT :split"))),
        )
        assertTrue(client.mapBatchTree(malformed).single() is IrcEvent.Quit)

        val typed = BatchTree(
            "join",
            "netjoin",
            listOf("a", "b"),
            listOf(BatchChild.Message(msg(":Alice!u@h JOIN #room"))),
        )
        val unknown = BatchTree("outer", "vendor/unknown", emptyList(), listOf(BatchChild.Nested(typed)))
        assertTrue(client.mapBatchTree(unknown).single() is IrcEvent.NetworkBatch)
    }

    private fun msg(line: String) = IrcMessage.parse(line)
}
