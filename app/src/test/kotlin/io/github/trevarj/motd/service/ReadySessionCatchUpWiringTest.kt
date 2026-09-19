package io.github.trevarj.motd.service

import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Ready session's history wiring, driven against a REAL client.
 *
 * [HistoryCatchUpSessionTest] covers the two decisions in isolation, with every wait injected. What
 * that cannot see is which connection property each branch is actually attached to, and pointing
 * either at something that never converges is silent: the session simply never catches up. These
 * tests therefore drive `draft/chathistory` through a real registration and CAP NEW instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadySessionCatchUpWiringTest {
    private class Recorder {
        var catchUps = 0
        var backfills = 0
        var gateReleases = 0
        var proactiveStarts = 0
    }

    private fun TestScope.session(
        client: IrcClient,
        recorder: Recorder,
        isCurrent: () -> Boolean = { true },
        liveClient: () -> IrcClient? = { client },
    ) = launch {
        runHistoryCatchUpSession(
            client = client,
            isCurrent = isCurrent,
            liveClient = liveClient,
            releaseGate = { recorder.gateReleases++ },
            catchUp = { recorder.catchUps++ },
            backfill = { recorder.backfills++ },
            proactive = { recorder.proactiveStarts++ },
        )
    }

    @Test
    fun `a bouncer that advertises chathistory only by cap new still catches up`() =
        runTest {
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch message-tags server-time")
            val recorder = Recorder()

            val session = session(client, recorder)
            runCurrent()

            // Nothing pending and no chathistory: the entry decision settles NEGATIVE on this client's
            // own pending-cap set instead of blocking, releases the entry gate, and runs no pass. That
            // is the ZNC/no-history shape, and holding the gate here is what used to make chat entry
            // unreachable for the whole Ready session.
            assertEquals(1, recorder.gateReleases)
            assertEquals(0, recorder.catchUps)
            assertEquals(0, recorder.proactiveStarts)

            // chathistory is in the PRE-bind CAP REQ set, so it never appears in the post-welcome
            // deferred set the entry decision settles on. Only the re-arm can still catch this.
            transport.feed(":srv CAP me NEW :draft/chathistory")
            runCurrent()
            transport.feed(":srv CAP me ACK :draft/chathistory")
            runCurrent()

            assertEquals(1, recorder.catchUps)
            assertEquals(1, recorder.backfills)
            assertEquals(1, recorder.proactiveStarts)
            // The re-arm stands in for the entry catch-up; it does not release the gate a second time.
            assertEquals(1, recorder.gateReleases)
            session.join()
        }

    @Test
    fun `an outstanding chathistory cap req is waited out, not read as unsupported`() =
        runTest {
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch message-tags server-time")
            // The client REQs the newly advertised cap, so it is genuinely outstanding: availability is
            // NegotiatingOrOffline and the answer lives in pendingFeatureCaps.
            transport.feed(":srv CAP me NEW :draft/chathistory")
            runCurrent()
            val recorder = Recorder()

            val session = session(client, recorder)
            runCurrent()

            // Attached to anything that cannot shed the cap, this would sit here until the decision
            // timeout expired and then decide "unsupported".
            assertEquals(0, recorder.gateReleases)
            assertEquals(0, recorder.catchUps)

            transport.feed(":srv CAP me ACK :draft/chathistory")
            runCurrent()

            // The ENTRY branch owns this one: it claimed the catch-up and released the gate behind it,
            // which is what the chat screen's entry waits block on.
            assertEquals(1, recorder.catchUps)
            assertEquals(1, recorder.gateReleases)
            assertEquals(1, recorder.backfills)
            assertEquals(1, recorder.proactiveStarts)
            session.join()
        }

    @Test
    fun `a session that already has chathistory runs exactly one catch-up`() =
        runTest {
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch message-tags server-time draft/chathistory")
            val recorder = Recorder()

            val session = session(client, recorder)
            session.join()

            // The entry decision claimed it, so the re-arm must stand down rather than double-issue.
            assertEquals(1, recorder.catchUps)
            assertEquals(1, recorder.backfills)
            assertEquals(1, recorder.gateReleases)
            assertEquals(1, recorder.proactiveStarts)
        }

    @Test
    fun `a client the actor already replaced verifies nothing`() =
        runTest {
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch message-tags server-time")
            val replacement = readyClient(FakeTransport(), caps = "batch message-tags server-time")
            val recorder = Recorder()

            // Same network, live generation, but the actor swapped the socket underneath this session.
            // A pass pinned to the replaced client can no longer verify anything.
            val session = session(client, recorder, liveClient = { replacement })
            runCurrent()
            transport.feed(":srv CAP me NEW :draft/chathistory")
            runCurrent()
            transport.feed(":srv CAP me ACK :draft/chathistory")
            runCurrent()

            assertEquals(0, recorder.catchUps)
            assertEquals(0, recorder.backfills)
            assertEquals(0, recorder.proactiveStarts)
            // Nor may a superseded session release the gate the live one owns.
            assertEquals(0, recorder.gateReleases)
            session.join()
        }

    @Test
    fun `proactive work starts after overflow without holding entry and dies with its session`() =
        runTest {
            val transport = FakeTransport()
            val client = readyClient(transport, caps = "batch message-tags server-time draft/chathistory")
            val overflow = CompletableDeferred<Unit>()
            val entryReleased = CompletableDeferred<Unit>()
            val observerStopped = CompletableDeferred<Unit>()
            var proactiveStarts = 0
            var backfills = 0
            val session =
                launch {
                    runHistoryCatchUpSession(
                        client = client,
                        isCurrent = { true },
                        liveClient = { client },
                        releaseGate = { entryReleased.complete(Unit) },
                        catchUp = {
                            // Visible-wave convergence releases entry before paced overflow settles.
                            entryReleased.complete(Unit)
                            overflow.await()
                        },
                        backfill = { backfills++ },
                        proactive = {
                            try {
                                proactiveStarts++
                                awaitCancellation()
                            } finally {
                                observerStopped.complete(Unit)
                            }
                        },
                    )
                }
            try {
                runCurrent()
                assertTrue(entryReleased.isCompleted)
                assertEquals(0, proactiveStarts)
                assertEquals(0, backfills)

                overflow.complete(Unit)
                runCurrent()
                assertEquals(1, proactiveStarts)
                assertEquals(1, backfills)

                transport.feed(":srv CAP me NEW :draft/chathistory")
                transport.feed(":srv CAP me ACK :draft/chathistory")
                runCurrent()
                assertEquals(1, proactiveStarts)
            } finally {
                session.cancelAndJoin()
            }
            assertTrue(observerStopped.isCompleted)
        }

    private suspend fun TestScope.readyClient(
        transport: FakeTransport,
        caps: String,
    ): IrcClient {
        val client =
            IrcClient(
                IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
                TransportFactory { _, _, _, _, _ -> transport },
                CoroutineScope(SupervisorJob() + coroutineContext),
            )
        client.start()
        runCurrent()
        transport.feed(":srv CAP * LS :$caps")
        runCurrent()
        transport.feed(":srv CAP me ACK :$caps")
        transport.feed(":srv 005 me CHANTYPES=# :supported")
        transport.feed(":srv 001 me :Welcome")
        runCurrent()
        check(client.state.value is IrcClientState.Ready)
        return client
    }

    private class FakeTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)

        override suspend fun connect() = Unit

        override val incoming = inbound.consumeAsFlow()

        override suspend fun send(line: String) = Unit

        override suspend fun close() {
            inbound.close()
        }

        suspend fun feed(line: String) {
            inbound.send(line)
        }
    }
}
