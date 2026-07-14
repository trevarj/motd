package io.github.trevarj.motd.irc.ext

import io.github.trevarj.motd.irc.proto.IrcMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAssemblerTest {
    @Test fun `nested batches retain immutable tree position and wire order`() {
        val assembler = BatchAssembler()
        assertEquals(BatchAssembler.Outcome.Buffered, assembler.route(msg("BATCH +history chathistory #room")))
        assertEquals(
            BatchAssembler.Outcome.Buffered,
            assembler.route(msg("@batch=history BATCH +split netsplit a.example b.example")),
        )
        assembler.route(msg("@batch=split :Alice!u@h QUIT :a.example b.example"))
        assembler.route(msg("@batch=split :Bob!u@h QUIT :a.example b.example"))
        assembler.route(msg("BATCH -split"))
        assembler.route(msg("@batch=history :Carol!u@h PRIVMSG #room :after"))
        val closed = assembler.route(msg("BATCH -history")) as BatchAssembler.Outcome.Closed

        assertEquals("history", closed.tree.ref)
        assertEquals("chathistory", closed.tree.type)
        assertEquals(2, closed.tree.children.size)
        val nested = (closed.tree.children[0] as BatchChild.Nested).batch
        assertEquals("netsplit", nested.type)
        assertEquals(listOf("a.example", "b.example"), nested.params)
        assertEquals(
            listOf("Alice", "Bob"),
            nested.children.map { ((it as BatchChild.Message).message.source!!.nick) },
        )
        assertEquals("Carol", (closed.tree.children[1] as BatchChild.Message).message.source!!.nick)
        assertFalse(assembler.hasOpenBatch)
    }

    @Test fun `reset drops incomplete generations`() {
        val assembler = BatchAssembler()
        assembler.route(msg("BATCH +split netsplit a b"))
        assertTrue(assembler.hasOpenBatch)
        assembler.reset()
        assertFalse(assembler.hasOpenBatch)
        assertEquals(BatchAssembler.Outcome.PassThrough, assembler.route(msg("BATCH -split")))
    }

    private fun msg(line: String) = IrcMessage.parse(line)
}
