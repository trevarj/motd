package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OutgoingMessagePlanTest {
    @Test
    fun `utf8 chunks respect byte limit and never split a code point`() {
        val chunks = splitUtf8("hello 😀 world café", maxBytes = 10)

        assertTrue(chunks.all { it.toByteArray(Charsets.UTF_8).size <= 10 })
        assertEquals("hello😀worldcafé", chunks.joinToString(""))
        assertTrue(chunks.none(::hasUnpairedSurrogate))
    }

    @Test
    fun `multiline action only wraps the first physical line`() {
        val chunks = prepareOutgoingMessageChunks("/me waves\nplain text", isBouncerServ = false)

        assertEquals(
            listOf(
                OutgoingMessageChunk("\u0001ACTION waves\u0001", "waves", MessageKind.ACTION),
                OutgoingMessageChunk("plain text", "plain text", MessageKind.PRIVMSG),
            ),
            chunks,
        )
    }

    @Test
    fun `long action chunks are individually valid actions and keep display text clean`() {
        val text = "/me " + "😀".repeat(80)
        val chunks = prepareOutgoingMessageChunks(text, isBouncerServ = false, maxBytes = 40)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.kind == MessageKind.ACTION })
        assertTrue(chunks.all { it.wireText.startsWith("\u0001ACTION ") && it.wireText.endsWith("\u0001") })
        assertTrue(chunks.all { it.wireText.toByteArray(Charsets.UTF_8).size <= 40 })
        assertEquals("😀".repeat(80), chunks.joinToString("") { it.displayText })
    }

    @Test
    fun `bouncer service rejects multiline and oversized commands before planning`() {
        assertTrue(prepareOutgoingMessageChunks("help\nserver status", isBouncerServ = true).isEmpty())
        assertTrue(prepareOutgoingMessageChunks("x".repeat(401), isBouncerServ = true).isEmpty())
    }

    @Test
    fun `bouncer service transcript redacts secrets while wire text stays intact`() {
        val chunks = prepareOutgoingMessageChunks(
            "network create -addr irc.example -pass hunter2",
            isBouncerServ = true,
        )

        assertEquals("network create -addr irc.example -pass hunter2", chunks.single().wireText)
        assertFalse(chunks.single().displayText.contains("hunter2"))
        assertTrue(chunks.single().displayText.contains("<redacted>"))
    }

    @Test
    fun `missing transport fails every already durable event without writes`() = runTest {
        val writes = mutableListOf<Int>()
        val failed = mutableListOf<Long>()

        val result = transmitDurableOutgoingPlan(
            eventIds = listOf(10L, 11L, 12L),
            write = { index -> writes += index; ImmediateWireAcceptance.DISCONNECTED },
            onWritten = {},
            failRemaining = { ids -> failed.addAll(ids) },
        )

        assertEquals(ImmediateWireAcceptance.DISCONNECTED, result)
        assertEquals(listOf(0), writes)
        assertEquals(listOf(10L, 11L, 12L), failed)
    }

    @Test
    fun `mid plan failure leaves written prefix and fails current plus tail`() = runTest {
        val written = mutableListOf<Int>()
        val failed = mutableListOf<Long>()

        val result = transmitDurableOutgoingPlan(
            eventIds = listOf(20L, 21L, 22L),
            write = { index ->
                if (index < 1) ImmediateWireAcceptance.ACCEPTED else ImmediateWireAcceptance.FAILED
            },
            onWritten = { index -> written.add(index) },
            failRemaining = { ids -> failed.addAll(ids) },
        )

        assertEquals(ImmediateWireAcceptance.FAILED, result)
        assertEquals(listOf(0), written)
        assertEquals(listOf(21L, 22L), failed)
    }

    @Test
    fun `every durable id exists before the first write`() = runTest {
        val eventIds = listOf(25L, 26L, 27L)
        val durableRows = eventIds.toMutableSet()
        var checked = false

        val result = transmitDurableOutgoingPlan(
            eventIds = eventIds,
            write = {
                assertTrue(eventIds.all(durableRows::contains))
                checked = true
                ImmediateWireAcceptance.ACCEPTED
            },
            onWritten = {},
            failRemaining = {},
        )

        assertTrue(checked)
        assertEquals(ImmediateWireAcceptance.ACCEPTED, result)
    }

    @Test
    fun `cancellation during post-write transition fails current and unattempted tail`() = runTest {
        val written = mutableListOf<Int>()
        val failed = mutableListOf<Long>()

        val result = transmitDurableOutgoingPlan(
            eventIds = listOf(30L, 31L, 32L),
            write = { ImmediateWireAcceptance.ACCEPTED },
            onWritten = { index ->
                if (index == 1) throw CancellationException("cancel after write")
                written += index
            },
            failRemaining = { failed += it },
        )

        assertEquals(ImmediateWireAcceptance.FAILED, result)
        assertEquals(listOf(0), written)
        assertEquals(listOf(31L, 32L), failed)
    }

    @Test
    fun `cancellation before a write fails the complete unattempted plan`() = runTest {
        val failed = mutableListOf<Long>()

        val result = transmitDurableOutgoingPlan(
            eventIds = listOf(35L, 36L),
            write = { throw CancellationException("cancel before write") },
            onWritten = { error("write was not accepted") },
            failRemaining = { failed += it },
        )

        assertEquals(ImmediateWireAcceptance.FAILED, result)
        assertEquals(listOf(35L, 36L), failed)
    }

    @Test
    fun `caller cancellation after commit still returns acceptance and ignores sound cancellation`() = runTest {
        val lifecycle = DurableSendLifecycle()
        val transitionEntered = CompletableDeferred<Unit>()
        val releaseTransition = CompletableDeferred<Unit>()
        val accepted = CompletableDeferred<SendAcceptance.Accepted>()
        val job = launch {
            val result = lifecycle.sending {
                completeDurableAcceptance(
                    eventIds = listOf(40L, 41L),
                    transition = {
                        transitionEntered.complete(Unit)
                        releaseTransition.await()
                        ImmediateWireAcceptance.ACCEPTED
                    },
                    secondaryEffect = { throw CancellationException("sound cancelled") },
                )
            }
            accepted.complete(result)
        }
        transitionEntered.await()

        job.cancel()
        releaseTransition.complete(Unit)
        job.join()

        assertEquals(
            SendAcceptance.Accepted(listOf(40L, 41L), ImmediateWireAcceptance.ACCEPTED),
            accepted.await(),
        )
    }

    @Test
    fun `lifecycle stop drains active sends runs recovery and gates the next send`() = runTest {
        val lifecycle = DurableSendLifecycle()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch {
            lifecycle.sending {
                order += "first-start"
                firstEntered.complete(Unit)
                releaseFirst.await()
                order += "first-end"
            }
        }
        firstEntered.await()
        val stop = launch {
            lifecycle.quiesce(
                onBlocked = { order += "timeouts-cancelled" },
                block = { order += "pending-recovered" },
            )
        }
        runCurrent()
        val next = launch { lifecycle.sending { order += "next-send" } }
        runCurrent()
        assertEquals(listOf("first-start", "timeouts-cancelled"), order)

        releaseFirst.complete(Unit)
        first.join()
        stop.join()
        next.join()

        assertEquals(
            listOf("first-start", "timeouts-cancelled", "first-end", "pending-recovered", "next-send"),
            order,
        )
        lifecycle.quiesce { order += "pending-recovered-again" }
        assertEquals("pending-recovered-again", order.last())
    }

    @Test
    fun `generic retry rejects bouncer service and redacted durable rows`() {
        val room = BufferEntity(
            networkId = 1,
            name = "#room",
            displayName = "#room",
            type = BufferType.CHANNEL,
        )
        val bouncer = BufferEntity(
            networkId = 1,
            name = "bouncerserv",
            displayName = "BouncerServ",
            type = BufferType.QUERY,
        )
        val failed = MessageEntity(
            bufferId = 1,
            serverTime = 1,
            sender = "me",
            kind = MessageKind.PRIVMSG,
            text = "safe retry",
            isSelf = true,
            failed = true,
            dedupKey = "retry",
        )

        assertTrue(isGenericRetryEligible(room, failed))
        assertFalse(isGenericRetryEligible(bouncer, failed))
        assertFalse(isGenericRetryEligible(room, failed.copy(text = "password <redacted>")))
    }

    private fun hasUnpairedSurrogate(text: String): Boolean {
        for (index in text.indices) {
            when {
                text[index].isHighSurrogate() &&
                    (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) -> return true
                text[index].isLowSurrogate() &&
                    (index == 0 || !text[index - 1].isHighSurrogate()) -> return true
            }
        }
        return false
    }
}
