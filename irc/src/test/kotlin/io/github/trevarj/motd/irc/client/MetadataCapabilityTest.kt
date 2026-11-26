package io.github.trevarj.motd.irc.client

import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.proto.IrcMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetadataCapabilityTest {
    @Test fun negotiationUsesOneMetadataVersionAndRequiresBatch() {
        val extras = setOf("metadata-notify")
        val versions = setOf("draft/metadata-3", "draft/metadata-2", "metadata-notify")
        assertEquals(setOf("batch", "draft/metadata-3"), CapNegotiator.requestSet(versions + "batch", extras))
        assertEquals(setOf("batch", "draft/metadata-2"), CapNegotiator.requestSet(versions - "draft/metadata-3" + "batch", extras))
        assertTrue(CapNegotiator.requestSet(versions, emptySet()).isEmpty())
        assertTrue(CapNegotiator.runtimeRequestSet(setOf("draft/metadata-3"), setOf("batch", "draft/metadata-2"), extras).isEmpty())
    }

    @Test fun registrationAndNestedMetadataBatchesPreserveTargetAndEmptySnapshots() =
        runTest {
            val transport = FakeTransport()
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val config = IrcClientConfig(host = "irc.example.org", port = 6697, tls = true, nick = "motd", username = "motd", realname = "MOTD")
            val client = IrcClient(config, transport.factory(), CoroutineScope(backgroundScope.coroutineContext + dispatcher))
            val events = mutableListOf<IrcEvent>()
            backgroundScope.launch(dispatcher) { client.broadcastEvents.toList(events) }
            client.start()
            val cap = "draft/metadata-3=max-subs=4,max-key-bytes=16"
            transport.feed(":srv CAP * LS :batch $cap draft/metadata-2")
            transport.feed(":srv CAP motd ACK :batch draft/metadata-3")
            transport.feed(":srv 001 motd :Welcome")
            transport.feed(":srv BATCH +register metadata motd")
            runCurrent()
            val openedBy = System.nanoTime()
            transport.feed("@batch=register :srv 761 motd motd avatar * :https://example.org/me.png")
            transport.feed(":srv 766 motd motd avatar :Key not set")
            transport.feed(":motd!u@h NICK :newmotd")
            transport.feed(":srv BATCH -register")
            transport.feed(":srv 376 newmotd :End of MOTD")
            transport.feed(":srv BATCH +wrapper example.org/wrapper")
            transport.feed("@batch=wrapper :srv BATCH +empty metadata #empty")
            transport.feed("@batch=wrapper :srv BATCH -empty")
            transport.feed(":srv BATCH -wrapper")
            transport.feed("@label=manual :srv BATCH +manual metadata alice")
            transport.feed("@batch=manual :srv 761 newmotd alice display-name * :Alice")
            transport.feed(":srv BATCH -manual")
            runCurrent()

            assertTrue(client.hasCap("draft/metadata-3"))
            assertFalse(client.hasCap("draft/metadata-2"))
            val snapshots = events.filterIsInstance<IrcEvent.MetadataSnapshot>()
            assertEquals(listOf("motd", "#empty"), snapshots.map { it.target })
            val interleaved = events.filterIsInstance<IrcEvent.Raw>().single { it.message.command == "766" }.message
            assertTrue(interleaved.wireSequence > snapshots[0].messages.single().wireSequence)
            assertTrue(snapshots[0].messages.single().wireSequence > snapshots[0].openingSequence)
            val registered = events.filterIsInstance<IrcEvent.Registered>().single()
            val rename = events.filterIsInstance<IrcEvent.NickChanged>().single()
            assertTrue(registered.wireSequence in 1 until snapshots[0].openingSequence)
            assertTrue(interleaved.wireSequence < rename.wireSequence)
            assertEquals("motd", interleaved.selfNickAtRead)
            assertEquals("motd", snapshots[0].selfNickAtOpen)
            assertEquals("motd", snapshots[0].messages.single().selfNickAtRead)
            assertEquals("newmotd", snapshots[1].selfNickAtOpen)
            assertTrue(snapshots[0].openedAtNanos != 0L && snapshots[0].openedAtNanos <= openedBy)
            assertEquals(
                "https://example.org/me.png",
                snapshots[0]
                    .messages
                    .single()
                    .params
                    .last(),
            )
            assertTrue(snapshots[1].messages.isEmpty())
            val manual = events.filterIsInstance<IrcEvent.Raw>().single { it.message.command == "761" }.message
            assertEquals("manual", manual.tags["label"])
            assertEquals("Alice", manual.params.last())
        }

    @Test fun runtimeMetadataAliasWaitsForBatchAndFallsBackAfterCapabilityRemoval() =
        runTest {
            val transport = FakeTransport()
            val config = IrcClientConfig(host = "irc.example.org", port = 6697, tls = true, nick = "motd", username = "motd", realname = "MOTD")
            val client = IrcClient(config, transport.factory(), backgroundScope)
            val events = mutableListOf<IrcEvent>()
            backgroundScope.launch { client.broadcastEvents.toList(events) }
            client.start()
            runCurrent()
            transport.feed(":srv CAP * LS :draft/metadata-3 draft/metadata-2")
            transport.feed(":srv 001 motd :Welcome")
            runCurrent()
            assertFalse(transport.sent.any { it.startsWith("CAP REQ") })

            transport.feed(":srv CAP motd NEW :batch")
            runCurrent()
            assertEquals("CAP REQ :batch", transport.sent.last())
            transport.feed(":srv CAP motd ACK :batch")
            runCurrent()
            assertEquals("CAP REQ :draft/metadata-3", transport.sent.last())
            transport.feed(":srv CAP motd ACK :draft/metadata-3")
            transport.feed(":srv CAP motd NEW :draft/metadata-3=max-key-bytes=3")
            runCurrent()
            assertTrue("draft/metadata-3=max-key-bytes=3" in client.caps)
            transport.feed(":srv CAP motd DEL :draft/metadata-2")
            runCurrent()
            assertTrue(events.filterIsInstance<IrcEvent.CapsChanged>().none { "draft/metadata-2" in it.removed })
            assertTrue(client.hasCap("draft/metadata-3"))
            transport.feed(":srv CAP motd NEW :draft/metadata-2")
            transport.feed(":srv CAP motd DEL :draft/metadata-3")
            runCurrent()
            assertEquals("CAP REQ :draft/metadata-2", transport.sent.last())
            transport.feed(":srv CAP motd ACK :draft/metadata-2")
            runCurrent()
            assertTrue(client.hasCap("draft/metadata-2"))
            assertFalse(client.hasCap("draft/metadata-3"))
        }

    @Test fun labeledJoinPreservesItsNestedAutomaticMetadataSnapshot() =
        runTest {
            val transport = FakeTransport()
            val config = IrcClientConfig(host = "irc.example.org", port = 6697, tls = true, nick = "motd", username = "motd", realname = "MOTD")
            val client = IrcClient(config, transport.factory(), backgroundScope)
            val events = mutableListOf<IrcEvent>()
            backgroundScope.launch { client.broadcastEvents.toList(events) }
            client.start()
            runCurrent()
            transport.feed(":srv CAP * LS :batch draft/metadata-3")
            transport.feed(":srv CAP motd ACK :batch draft/metadata-3")
            transport.feed(":srv 001 motd :Welcome")
            transport.feed(":srv CAP motd NEW :labeled-response")
            transport.feed(":srv CAP motd ACK :labeled-response")
            runCurrent()
            assertTrue(client.sendJoinForRejection("#chat", key = null, requestId = "join-label"))
            transport.feed("@label=join-label :srv BATCH +join labeled-response")
            transport.feed("@batch=join :motd!u@h JOIN #chat")
            transport.feed("@batch=join :srv 366 motd #chat :End of NAMES")
            transport.feed("@batch=join :srv BATCH +sync metadata #CHAT")
            transport.feed("@batch=sync :srv 761 motd #chat avatar * :https://example.org/chat.png")
            transport.feed("@batch=join :srv BATCH -sync")
            transport.feed(":srv BATCH -join")
            transport.feed(":srv 730 motd :alice!u@h")
            runCurrent()
            val joined = events.filterIsInstance<IrcEvent.Joined>().single()
            val names = events.filterIsInstance<IrcEvent.Names>().single()
            val snapshot = events.filterIsInstance<IrcEvent.MetadataSnapshot>().single()
            val monitored = events.filterIsInstance<IrcEvent.MonitorOnline>().single()
            assertTrue(joined.isSelf)
            assertEquals("join-label", joined.ctx.label)
            assertTrue(joined.wireSequence in 1 until names.wireSequence)
            assertTrue(names.wireSequence < snapshot.openingSequence)
            assertEquals("#CHAT", snapshot.target)
            assertEquals("join-label", snapshot.joinResponseLabel)
            assertEquals(
                "https://example.org/chat.png",
                snapshot.messages
                    .single()
                    .params
                    .last(),
            )
            assertTrue(snapshot.messages.single().wireSequence < monitored.wireSequence)
            assertTrue(events.indexOf(joined) < events.indexOf(snapshot))
        }

    @Test fun metadataWriteFenceRunsAfterEarlierOutboundWriteAndBeforeSync() =
        runTest {
            val transport = FakeTransport()
            val config = IrcClientConfig(host = "irc.example.org", port = 6697, tls = true, nick = "motd", username = "motd", realname = "MOTD")
            val client = IrcClient(config, transport.factory(), backgroundScope)
            client.start()
            runCurrent()
            transport.feed(":srv 001 motd :Welcome")
            runCurrent()

            val writeStarted = CompletableDeferred<Unit>()
            val allowWrite = CompletableDeferred<Unit>()
            var fenced = false
            transport.beforeSend = { line ->
                if (line.startsWith("NOTICE ")) {
                    writeStarted.complete(Unit)
                    allowWrite.await()
                } else if (line.startsWith("METADATA ")) {
                    assertTrue(fenced)
                }
            }
            launch { client.send(IrcMessage.parse("NOTICE #chat wait")) }
            writeStarted.await()
            val synced = async { client.sendIfConnected(IrcMessage.parse("METADATA #chat SYNC"), onWrite = { fenced = true }) }
            runCurrent()
            assertFalse(fenced)
            allowWrite.complete(Unit)
            assertTrue(synced.await())
        }

    @Test fun bouncerDefersOnlyNewestMetadataAliasAndDirectRegistrationReconcilesLateBatch() {
        val direct = IrcClientConfig(host = "irc.example.org", port = 6697, tls = true, nick = "motd", username = "motd", realname = "MOTD")
        val bouncer = RegistrationStateMachine(direct.copy(bouncerNetId = "1"))
        bouncer.start()
        bouncer.onMessage(IrcMessage.parse(":srv CAP * LS :batch draft/metadata-2"))
        bouncer.onMessage(IrcMessage.parse(":srv CAP motd ACK :batch"))
        val fallback = bouncer.onMessage(IrcMessage.parse(":srv CAP motd NEW :draft/metadata-3"))
        assertEquals(listOf("CAP REQ :draft/metadata-3"), fallback.filterIsInstance<RegistrationStateMachine.Action.SendDeferred>().map { it.line })

        val machine = RegistrationStateMachine(direct)
        machine.start()
        machine.onMessage(IrcMessage.parse(":srv CAP * LS :draft/metadata-3"))
        machine.onMessage(IrcMessage.parse(":srv CAP motd NEW :batch"))
        val welcome = machine.onMessage(IrcMessage.parse(":srv 001 motd :Welcome"))
        val ready = welcome.filterIsInstance<RegistrationStateMachine.Action.Complete>().single()
        assertEquals(setOf("batch"), ready.deferredCaps)
    }
}
