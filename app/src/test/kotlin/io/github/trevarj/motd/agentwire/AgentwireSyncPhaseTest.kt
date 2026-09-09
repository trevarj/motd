package io.github.trevarj.motd.agentwire

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_REQUIRED_CAPS
import io.github.trevarj.motd.irc.agentwire.AGENTWIRE_TAG
import io.github.trevarj.motd.irc.agentwire.AgentwireEnvelope
import io.github.trevarj.motd.irc.agentwire.AgentwireReassembler
import io.github.trevarj.motd.irc.agentwire.AgentwireTopicDefect
import io.github.trevarj.motd.irc.agentwire.AgentwireValue
import io.github.trevarj.motd.irc.agentwire.decodeAgentwireValue
import io.github.trevarj.motd.irc.agentwire.encodeAgentwireEnvelope
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.SendAcceptance
import io.github.trevarj.motd.testing.NoopConnectionManager
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.share.PendingShareStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.OutputStream
import java.util.UUID

private const val NETWORK_ID = 7L
private const val BUFFER_ID = 11L
private const val CHANNEL = "#claude"
private const val BACKEND_ACCOUNT = "agent"

/**
 * The negative space no existing agentwire test covered: when correlated replies never arrive, or
 * the bridge answers with a definitive refusal, the UI must leave `syncing` within a bound and say
 * which of the distinguishable failures happened.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AgentwireSyncPhaseTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `unanswered handshake ends as a named timeout and retry re-arms the budget`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val viewModel = viewModel(client)
            advanceTimeBy(10)
            runCurrent()
            assertTrue(viewModel.state.value.sync is AgentwireSyncState.Syncing)
            assertTrue(syncRequests(transport).isNotEmpty())

            advanceTimeBy(AGENTWIRE_SYNC_BUDGET_MS - 1_000)
            runCurrent()
            assertTrue(
                "the spinner must still be running one second before the budget",
                viewModel.state.value.sync is AgentwireSyncState.Syncing,
            )

            advanceTimeBy(2_000)
            runCurrent()
            val failed = viewModel.state.value.sync as AgentwireSyncState.Failed
            val timeout = failed.failure as AgentwireSyncFailure.Timeout
            assertEquals(syncRequests(transport).size, timeout.attempts)

            // The loop is terminal: waiting longer must not produce more requests.
            val issued = syncRequests(transport).size
            advanceTimeBy(AGENTWIRE_SYNC_BUDGET_MS)
            runCurrent()
            assertEquals(issued, syncRequests(transport).size)

            viewModel.retrySync()
            runCurrent()
            assertTrue(viewModel.state.value.sync is AgentwireSyncState.Syncing)
            advanceTimeBy(1_000)
            runCurrent()
            assertTrue("retry must re-arm and send again", syncRequests(transport).size > issued)
        }

    @Test
    fun `sync completion refreshes live sessions without loading workspace tree`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val viewModel = viewModel(client)
            advanceTimeBy(10)
            runCurrent()
            val syncId = syncRequests(transport).last()

            transport.feed(
                tagMessage(
                    BACKEND_ACCOUNT,
                    hello(syncId, setOf("session.list.request", "workspace.list.request")),
                ),
            )
            transport.feed(tagMessage(BACKEND_ACCOUNT, snapshot(syncId)))
            runCurrent()

            assertEquals(AgentwireSyncState.Ready, viewModel.state.value.sync)
            val requests = outboundEnvelopes(transport)
            val sessionRequests = requests.filter { it.kind == "session.list.request" }
            assertEquals(1, sessionRequests.size)
            assertEquals("live", sessionRequests.single().data?.string("scope"))
            assertTrue(requests.none { it.kind == "workspace.list.request" })
        }

    @Test
    fun `trusted sync recovers an unresolved receipt through a correlated status query`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val scope = actionScope()
            val prefs =
                FakeAgentwirePrefs(
                    initialReceipts =
                        listOf(
                            AgentwireActionReceipt(
                                id = "00000000-0000-4000-8000-00000000cafe",
                                kind = "turn.prompt",
                                channel = CHANNEL,
                                sentAt = 1,
                                outcome = "unknown",
                                scope = scope,
                            ),
                            AgentwireActionReceipt(
                                id = "00000000-0000-4000-8000-00000000beef",
                                kind = "turn.prompt",
                                channel = CHANNEL,
                                sentAt = 0,
                                outcome = "unknown",
                                scope = scope,
                            ),
                        ),
                )
            val viewModel = viewModel(FakeConnections(client), FakeBufferRepository(buffer(true)), prefs = prefs)

            completeSync(transport, setOf("action.status.request"))
            runCurrent()

            val query = outboundEnvelopes(transport).last { it.kind == "action.status.request" }
            assertEquals("00000000-0000-4000-8000-00000000cafe", query.data?.string("actionId"))
            transport.feed(
                tagMessage(
                    BACKEND_ACCOUNT,
                    actionStatus(query.id, "00000000-0000-4000-8000-00000000dead", "succeeded"),
                ),
            )
            runCurrent()
            assertEquals(
                "unknown",
                prefs.receipts.value
                    .first { it.id.endsWith("cafe") }
                    .outcome,
            )
            assertTrue(
                viewModel.state.value.actionStatus
                    .isEmpty(),
            )

            transport.feed(
                tagMessage(
                    BACKEND_ACCOUNT,
                    actionStatus(query.id, "00000000-0000-4000-8000-00000000cafe", "succeeded"),
                ),
            )
            runCurrent()

            assertEquals(
                "succeeded",
                prefs.receipts.value
                    .first { it.id.endsWith("cafe") }
                    .outcome,
            )
            assertEquals("succeeded", viewModel.state.value.actionStatus["00000000-0000-4000-8000-00000000cafe"])
            advanceTimeBy(999)
            runCurrent()
            val queries = outboundEnvelopes(transport).filter { it.kind == "action.status.request" }
            assertEquals(1, queries.size)
            advanceTimeBy(101)
            runCurrent()
            val nextQueries = outboundEnvelopes(transport).filter { it.kind == "action.status.request" }
            assertEquals(2, nextQueries.size)
            assertEquals("00000000-0000-4000-8000-00000000beef", nextQueries.last().data?.string("actionId"))
            assertTrue(outboundEnvelopes(transport).none { it.kind == "turn.prompt" })
        }

    @Test
    fun `status query failure releases recovery slot without changing its mutation`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val scope = actionScope()
            val prefs =
                FakeAgentwirePrefs(
                    initialReceipts =
                        listOf(
                            AgentwireActionReceipt(
                                id = "00000000-0000-4000-8000-0000000000a1",
                                kind = "turn.prompt",
                                channel = CHANNEL,
                                sentAt = 1,
                                outcome = "unknown",
                                scope = scope,
                            ),
                            AgentwireActionReceipt(
                                id = "00000000-0000-4000-8000-0000000000b2",
                                kind = "turn.prompt",
                                channel = CHANNEL,
                                sentAt = 0,
                                outcome = "unknown",
                                scope = scope,
                            ),
                            AgentwireActionReceipt(
                                id = "00000000-0000-4000-8000-0000000000c3",
                                kind = "turn.prompt",
                                channel = CHANNEL,
                                sentAt = -1,
                                outcome = "unknown",
                                scope = scope,
                            ),
                        ),
                )
            val viewModel = viewModel(FakeConnections(readyClient(transport)), FakeBufferRepository(buffer(true)), prefs = prefs)

            completeSync(transport, setOf("action.status.request"))
            runCurrent()

            val first = outboundEnvelopes(transport).last { it.kind == "action.status.request" }
            assertEquals("00000000-0000-4000-8000-0000000000a1", first.data?.string("actionId"))
            transport.feed(
                tagMessage(
                    BACKEND_ACCOUNT,
                    actionFailed(first.id, "Status service unavailable").copy(epoch = "epoch-1"),
                ),
            )
            runCurrent()

            assertEquals(
                "unknown",
                prefs.receipts.value
                    .first { it.id.endsWith("a1") }
                    .outcome,
            )
            assertFalse(first.id in viewModel.state.value.actionStatus)
            advanceTimeBy(999)
            runCurrent()
            assertEquals(1, outboundEnvelopes(transport).count { it.kind == "action.status.request" })
            advanceTimeBy(101)
            runCurrent()
            val second = outboundEnvelopes(transport).last { it.kind == "action.status.request" }
            assertEquals("00000000-0000-4000-8000-0000000000b2", second.data?.string("actionId"))
            transport.feed(
                tagMessage(
                    BACKEND_ACCOUNT,
                    AgentwireEnvelope(
                        kind = "action.uncertain",
                        type = "event",
                        id = UUID.randomUUID().toString(),
                        at = 3,
                        instance = "bridge",
                        epoch = "epoch-1",
                        reply = second.id,
                    ),
                ),
            )
            runCurrent()

            assertEquals(
                "unknown",
                prefs.receipts.value
                    .first { it.id.endsWith("b2") }
                    .outcome,
            )
            assertFalse(second.id in viewModel.state.value.actionStatus)
            advanceTimeBy(999)
            runCurrent()
            assertEquals(2, outboundEnvelopes(transport).count { it.kind == "action.status.request" })
            advanceTimeBy(101)
            runCurrent()
            val third = outboundEnvelopes(transport).last { it.kind == "action.status.request" }
            assertEquals("00000000-0000-4000-8000-0000000000c3", third.data?.string("actionId"))
            assertTrue(outboundEnvelopes(transport).none { it.kind == "turn.prompt" })
        }

    @Test
    fun `diagnostic refresh coalesces and only accepts its trusted live reply`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val viewModel = viewModel(FakeConnections(readyClient(transport)), FakeBufferRepository(buffer(true)))
            completeSync(
                transport,
                actions = setOf("diagnostics.request"),
                capabilities = setOf("diagnostics"),
            )

            viewModel.refreshDiagnostics()
            viewModel.refreshDiagnostics()
            runCurrent()
            val request = outboundEnvelopes(transport).single { it.kind == "diagnostics.request" }
            assertTrue(viewModel.state.value.diagnosticsLoading)

            transport.feed(tagMessage(BACKEND_ACCOUNT, diagnosticsSnapshot(UUID.randomUUID().toString())))
            transport.feed(tagMessage("impostor", diagnosticsSnapshot(request.id)))
            transport.feed(tagMessage(BACKEND_ACCOUNT, diagnosticsSnapshot(request.id, history = true)))
            runCurrent()
            assertNull(viewModel.state.value.diagnosticReport)
            assertTrue(viewModel.state.value.diagnosticsLoading)

            transport.feed(tagMessage(BACKEND_ACCOUNT, diagnosticsSnapshot(request.id)))
            runCurrent()
            val report = requireNotNull(viewModel.state.value.diagnosticReport)
            assertEquals(1_785_400_000_000, report.generatedAt)
            assertEquals("backend.ready", report.checks.single().code)
            assertFalse(viewModel.state.value.diagnosticsLoading)
            assertFalse(viewModel.state.value.diagnosticsStale)

            viewModel.refreshDiagnostics()
            runCurrent()
            val refreshed = outboundEnvelopes(transport).filter { it.kind == "diagnostics.request" }
            assertEquals(2, refreshed.size)
            assertTrue(request.id != refreshed.last().id)
        }

    @Test
    fun `diagnostic refresh failures retain only ephemeral stale reports`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val connections = FakeConnections(readyClient(transport))
            val buffers = FakeBufferRepository(buffer(true))
            val prefs = FakeAgentwirePrefs()
            val viewModel = viewModel(connections, buffers, prefs = prefs)
            completeSync(
                transport,
                actions = setOf("diagnostics.request"),
                capabilities = setOf("diagnostics"),
            )

            viewModel.refreshDiagnostics()
            runCurrent()
            val rejected = outboundEnvelopes(transport).single { it.kind == "diagnostics.request" }
            transport.feed(tagMessage(BACKEND_ACCOUNT, actionFailed(rejected.id, "Diagnostics unavailable").copy(epoch = "epoch-1")))
            runCurrent()
            assertFalse(viewModel.state.value.diagnosticsLoading)
            assertEquals("Bridge could not provide diagnostics", viewModel.state.value.diagnosticsError)
            assertTrue(prefs.receipts.value.isEmpty())
            assertTrue(
                viewModel.state.value.actionStatus
                    .isEmpty(),
            )
            assertNull(viewModel.state.value.error)

            viewModel.refreshDiagnostics()
            runCurrent()
            val timedOut = outboundEnvelopes(transport).last { it.kind == "diagnostics.request" }
            advanceTimeBy(10_000)
            runCurrent()
            assertFalse(viewModel.state.value.diagnosticsLoading)
            assertEquals("Diagnostics request timed out", viewModel.state.value.diagnosticsError)
            assertTrue(prefs.receipts.value.isEmpty())

            viewModel.refreshDiagnostics()
            runCurrent()
            val live = outboundEnvelopes(transport).last { it.kind == "diagnostics.request" }
            assertTrue(live.id != timedOut.id)
            transport.feed(tagMessage(BACKEND_ACCOUNT, diagnosticsSnapshot(live.id)))
            runCurrent()
            assertNotNull(viewModel.state.value.diagnosticReport)

            connections.connectionStates.value = mapOf(NETWORK_ID to IrcClientState.Disconnected)
            runCurrent()
            assertNotNull(viewModel.state.value.diagnosticReport)
            assertTrue(viewModel.state.value.diagnosticsStale)

            buffers.buffers.value =
                buffer(
                    true,
                    topic = "agentwire:v1;account=controller;agent=$BACKEND_ACCOUNT;backend=codex | Codex",
                )
            runCurrent()
            assertNull(viewModel.state.value.diagnosticReport)
            assertFalse(viewModel.state.value.diagnosticsStale)
        }

    @Test
    fun `epoch resync retains a stale diagnostic report and rejects the old query reply`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val viewModel = viewModel(FakeConnections(readyClient(transport)), FakeBufferRepository(buffer(true)))
            completeSync(
                transport,
                actions = setOf("diagnostics.request"),
                capabilities = setOf("diagnostics"),
            )

            viewModel.refreshDiagnostics()
            runCurrent()
            val initial = outboundEnvelopes(transport).single { it.kind == "diagnostics.request" }
            transport.feed(tagMessage(BACKEND_ACCOUNT, diagnosticsSnapshot(initial.id)))
            runCurrent()
            assertNotNull(viewModel.state.value.diagnosticReport)

            viewModel.refreshDiagnostics()
            runCurrent()
            val pending = outboundEnvelopes(transport).last { it.kind == "diagnostics.request" }
            transport.feed(
                tagMessage(
                    BACKEND_ACCOUNT,
                    actionFailed(pending.id, "stale or missing live epoch").copy(epoch = "epoch-2"),
                ),
            )
            runCurrent()
            assertTrue(viewModel.state.value.sync is AgentwireSyncState.Syncing)
            assertNotNull(viewModel.state.value.diagnosticReport)
            assertTrue(viewModel.state.value.diagnosticsStale)
            assertFalse(viewModel.state.value.diagnosticsLoading)
            assertTrue(
                viewModel.state.value.capabilities
                    .isEmpty(),
            )

            transport.feed(tagMessage(BACKEND_ACCOUNT, diagnosticsSnapshot(pending.id)))
            runCurrent()
            assertEquals(
                1_785_400_000_000,
                viewModel.state.value.diagnosticReport
                    ?.generatedAt,
            )
            assertTrue(viewModel.state.value.diagnosticsStale)
        }

    @Test
    fun `transport failure after a mutation write leaves a recoverable unknown receipt`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val prefs = FakeAgentwirePrefs()
            val viewModel = viewModel(FakeConnections(readyClient(transport)), FakeBufferRepository(buffer(true)), prefs = prefs)
            completeSync(transport, setOf("turn.prompt"))
            transport.throwAfterWrite = true

            viewModel.submit("Keep this receipt")
            runCurrent()

            assertEquals(1, prefs.receipts.value.size)
            assertEquals(
                "unknown",
                prefs.receipts.value
                    .single()
                    .outcome,
            )
        }

    @Test
    fun `disconnect marks an accepted mutation unknown then reconnect recovers it without resubmitting`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val connections = FakeConnections(client)
            val prefs = FakeAgentwirePrefs()
            val viewModel = viewModel(connections, FakeBufferRepository(buffer(true)), prefs = prefs)
            completeSync(transport, setOf("turn.prompt", "action.status.request"))

            viewModel.submit("Keep this once")
            runCurrent()
            val prompt = outboundEnvelopes(transport).single { it.kind == "turn.prompt" }
            transport.feed(
                tagMessage(
                    BACKEND_ACCOUNT,
                    AgentwireEnvelope(
                        kind = "action.accepted",
                        type = "event",
                        id = UUID.randomUUID().toString(),
                        at = 3,
                        instance = "bridge",
                        epoch = "epoch-1",
                        reply = prompt.id,
                    ),
                ),
            )
            runCurrent()
            assertEquals(
                "accepted",
                prefs.receipts.value
                    .single()
                    .outcome,
            )

            connections.connectionStates.value = mapOf(NETWORK_ID to IrcClientState.Disconnected)
            runCurrent()
            assertEquals(
                "unknown",
                prefs.receipts.value
                    .single()
                    .outcome,
            )

            val reconnectTransport = RecordingTransport()
            connections.replaceClient(readyClient(reconnectTransport))
            connections.connectionStates.value =
                mapOf(NETWORK_ID to IrcClientState.Ready("me", AGENTWIRE_REQUIRED_CAPS, emptyMap()))
            runCurrent()
            assertTrue("Reconnect phase: ${viewModel.state.value.sync}; connected=${viewModel.state.value.connected}", viewModel.state.value.sync is AgentwireSyncState.Syncing)
            // The existing handshake rate floor persists across reconnects.
            advanceTimeBy(AGENTWIRE_SYNC_MIN_INTERVAL_MS)
            completeSync(reconnectTransport, setOf("turn.prompt", "action.status.request"))
            val reconnected = viewModel.state.value
            assertEquals(AgentwireSyncState.Ready, reconnected.sync)
            assertTrue("Status recovery capability was not retained", "action.status.request" in reconnected.actions)
            assertEquals(CHANNEL, reconnected.channel)
            assertEquals("epoch-1", reconnected.epoch)
            assertEquals(listOf(prompt.id), reconnected.recentActions.map(AgentwireActionReceipt::id))
            assertTrue(
                "Reconnect did not write sync.request: ${outboundEnvelopes(reconnectTransport).map(AgentwireEnvelope::kind)}",
                outboundEnvelopes(reconnectTransport).any { it.kind == "sync.request" },
            )
            val query = outboundEnvelopes(reconnectTransport).last { it.kind == "action.status.request" }
            assertEquals(prompt.id, query.data?.string("actionId"))
            reconnectTransport.feed(tagMessage(BACKEND_ACCOUNT, actionStatus(query.id, prompt.id, "succeeded")))
            runCurrent()

            assertEquals(
                "succeeded",
                prefs.receipts.value
                    .single()
                    .outcome,
            )
            assertEquals(1, outboundEnvelopes(transport).count { it.kind == "turn.prompt" })
            assertTrue(outboundEnvelopes(reconnectTransport).none { it.kind == "turn.prompt" })
        }

    @Test
    fun `topic change during receipt write sends nothing and terminalizes only that receipt`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val prefs =
                FakeAgentwirePrefs(
                    initialReceipts =
                        listOf(
                            AgentwireActionReceipt(
                                id = "foreign-receipt",
                                kind = "turn.prompt",
                                channel = CHANNEL,
                                sentAt = 1,
                                outcome = "accepted",
                                scope = "foreign-scope",
                            ),
                        ),
                )
            val buffers = FakeBufferRepository(buffer(true))
            val viewModel = viewModel(FakeConnections(client), buffers, prefs = prefs)
            completeSync(transport, setOf("turn.prompt"))
            val gate = CompletableDeferred<Unit>()
            prefs.recordGate = gate

            viewModel.submit("Do not send after the topic changes")
            runCurrent()
            val own = prefs.receipts.value.single { it.kind == "turn.prompt" && it.scope == actionScope() }
            buffers.buffers.value =
                buffer(
                    true,
                    topic = "agentwire:v1;account=controller;agent=$BACKEND_ACCOUNT;backend=codex | Codex",
                )
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertTrue(outboundEnvelopes(transport).none { it.kind == "turn.prompt" })
            assertEquals(
                "failed",
                prefs.receipts.value
                    .single { it.id == own.id }
                    .outcome,
            )
            assertEquals(
                "accepted",
                prefs.receipts.value
                    .single { it.id == "foreign-receipt" }
                    .outcome,
            )
        }

    @Test
    fun `receipt rows follow trust scope even when the receipt store does not emit`() =
        runTest(dispatcher) {
            val claudeScope = actionScope()
            val codexScope = actionScope(backend = "codex")
            val prefs =
                FakeAgentwirePrefs(
                    initialReceipts =
                        listOf(
                            AgentwireActionReceipt("claude", "turn.prompt", CHANNEL, sentAt = 1, outcome = "unknown", scope = claudeScope),
                            AgentwireActionReceipt("codex", "turn.prompt", CHANNEL, sentAt = 2, outcome = "unknown", scope = codexScope),
                        ),
                )
            val buffers = FakeBufferRepository(buffer(true))
            val viewModel = viewModel(FakeConnections(readyClient(RecordingTransport())), buffers, prefs = prefs)
            runCurrent()
            assertEquals(
                listOf("claude"),
                viewModel.state.value.recentActions
                    .map(AgentwireActionReceipt::id),
            )

            buffers.buffers.value =
                buffer(
                    true,
                    topic = "agentwire:v1;account=controller;agent=$BACKEND_ACCOUNT;backend=codex | Codex",
                )
            runCurrent()

            assertEquals(
                listOf("codex"),
                viewModel.state.value.recentActions
                    .map(AgentwireActionReceipt::id),
            )
        }

    @Test
    fun `close session sends the active managed session id`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val viewModel = viewModel(client)
            advanceTimeBy(10)
            runCurrent()
            val syncId = syncRequests(transport).last()

            transport.feed(tagMessage(BACKEND_ACCOUNT, hello(syncId, setOf("session.close"))))
            transport.feed(tagMessage(BACKEND_ACCOUNT, snapshot(syncId)))
            runCurrent()
            transport.sent.clear()

            viewModel.closeSession()
            runCurrent()

            val close = outboundEnvelopes(transport).single()
            assertEquals("session.close", close.kind)
            assertEquals("session-1", close.sid)
        }

    @Test
    fun `action failed replying to the live sync id becomes a rejection and stops retrying`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val viewModel = viewModel(client)
            advanceTimeBy(10)
            runCurrent()
            val syncId = syncRequests(transport).last()

            transport.feed(tagMessage(BACKEND_ACCOUNT, actionFailed(syncId, "topic agent= does not match")))
            runCurrent()

            val failed = viewModel.state.value.sync as AgentwireSyncState.Failed
            val rejected = failed.failure as AgentwireSyncFailure.Rejected
            assertEquals("topic agent= does not match", rejected.detail)

            val issued = syncRequests(transport).size
            advanceTimeBy(2 * AGENTWIRE_SYNC_BUDGET_MS)
            runCurrent()
            assertEquals("a definitive refusal must end the retry loop", issued, syncRequests(transport).size)
            assertTrue(viewModel.state.value.sync is AgentwireSyncState.Failed)
        }

    @Test
    fun `an unjoined channel starts no session and sends nothing until the join lands`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val buffers = FakeBufferRepository(buffer(joined = false))
            val connections = FakeConnections(client)
            val viewModel = viewModel(connections, buffers)
            advanceTimeBy(AGENTWIRE_SYNC_BUDGET_MS)
            runCurrent()

            assertEquals(AgentwireSyncState.NotJoined, viewModel.state.value.sync)
            assertEquals(emptyList<String>(), syncRequests(transport))

            viewModel.joinAgentwireChannel()
            runCurrent()
            assertEquals(listOf(NETWORK_ID to CHANNEL), connections.joins)

            // EventProcessor confirms the self-JOIN; the gate collector must take it from here.
            buffers.buffers.value = buffer(joined = true)
            advanceTimeBy(10)
            runCurrent()
            assertTrue(viewModel.state.value.sync is AgentwireSyncState.Syncing)
            assertTrue("the handshake starts only once the channel is joined", syncRequests(transport).isNotEmpty())
        }

    @Test
    fun `untrusted events during a handshake are counted and named in the timeout`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val diagnostics = RecordingDiagnostics()
            val viewModel =
                viewModel(
                    FakeConnections(client),
                    FakeBufferRepository(buffer(joined = true)),
                    diagnostics,
                )
            advanceTimeBy(10)
            runCurrent()
            val syncId = syncRequests(transport).last()

            repeat(2) { transport.feed(tagMessage("impostor", hello(syncId))) }
            runCurrent()
            assertEquals(
                "Ignoring agent events from account impostor. The channel topic trusts only agent=agent.",
                viewModel.state.value.error,
            )

            advanceTimeBy(AGENTWIRE_SYNC_BUDGET_MS)
            runCurrent()
            val failed = viewModel.state.value.sync as AgentwireSyncState.Failed
            val timeout = failed.failure as AgentwireSyncFailure.Timeout
            assertEquals(2, timeout.counters.counts[IgnoreReason.UNTRUSTED_ACCOUNT])

            val untrusted = diagnostics.records.single { it.event == "untrusted_events" }
            assertEquals(AGENTWIRE_DIAGNOSTIC_COMPONENT, untrusted.component)
            assertEquals(diagnostics.fingerprint("impostor"), untrusted.fields["account_fp"])
            val syncFailed = diagnostics.records.last { it.event == "sync_failed" }
            assertEquals("timeout", syncFailed.fields["end_reason"])
            assertEquals(2, syncFailed.fields["ignored_untrusted_account"])
            assertTrue(
                "an account name must never reach the journal raw",
                diagnostics.records.none { record ->
                    record.fields.values.any { it?.toString()?.contains("impostor") == true }
                },
            )
        }

    @Test
    fun `a marked topic missing agent names the defect instead of rendering ordinary chat`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val diagnostics = RecordingDiagnostics()
            // Exactly the shape of a topic written before `agent=` became required.
            val buffers =
                FakeBufferRepository(
                    buffer(joined = true, topic = "agentwire:v1;account=controller;backend=claude | Claude"),
                )
            val viewModel = viewModel(FakeConnections(client), buffers, diagnostics)
            advanceTimeBy(AGENTWIRE_SYNC_BUDGET_MS)
            runCurrent()

            val state = viewModel.state.value
            assertEquals(AgentwireGate.INVALID_TOPIC, state.gate)
            assertEquals(AgentwireTopicDefect.MISSING_FIELD, state.topicDefect?.defect)
            assertEquals(listOf("agent"), state.topicDefect?.fields)
            assertEquals(
                "a channel that cannot activate must not open a handshake",
                emptyList<String>(),
                syncRequests(transport),
            )
            val gate = diagnostics.records.last { it.event == "gate" }
            assertEquals("invalid_topic", gate.fields["gate"])
            assertEquals("missing_field", gate.fields["topic_defect"])
            assertEquals("agent", gate.fields["topic_fields"])
        }

    @Test
    fun `an unmarked channel stays ordinary and reports no defect`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val diagnostics = RecordingDiagnostics()
            val buffers = FakeBufferRepository(buffer(joined = true, topic = "Welcome to the channel"))
            val viewModel = viewModel(FakeConnections(client), buffers, diagnostics)
            advanceTimeBy(AGENTWIRE_SYNC_BUDGET_MS)
            runCurrent()

            assertEquals(AgentwireGate.ORDINARY, viewModel.state.value.gate)
            assertEquals(null, viewModel.state.value.topicDefect)
            assertEquals(emptyList<String>(), syncRequests(transport))
            // An ordinary channel is not a failure and must never be reported as one.
            val gate = diagnostics.records.last { it.event == "gate" }
            assertEquals(null, gate.fields["topic_defect"])
            assertEquals(null, gate.fields["lab_disabled"])
        }

    @Test
    fun `a usable marker with the lab disabled records why nothing activated`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val diagnostics = RecordingDiagnostics()
            val viewModel =
                viewModel(
                    FakeConnections(client),
                    FakeBufferRepository(buffer(joined = true)),
                    diagnostics,
                    lab = false,
                )
            advanceTimeBy(AGENTWIRE_SYNC_BUDGET_MS)
            runCurrent()

            // The lab being off is a choice, so the channel still renders as ordinary chat; the
            // journal is what makes "I set everything up and nothing happened" answerable.
            assertEquals(AgentwireGate.ORDINARY, viewModel.state.value.gate)
            assertEquals(emptyList<String>(), syncRequests(transport))
            val gate = diagnostics.records.last { it.event == "gate" }
            assertEquals(true, gate.fields["lab_disabled"])
        }

    @Test
    fun `context waits for review and retains UTF8 overflow until explicit prompt acceptance`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val shares = PendingShareStore()
            val prefs = FakeAgentwirePrefs()
            shares.assignAgentContext(BUFFER_ID, PendingShare.AgentContext(99, "#source", "Draft context", "Two local messages"))
            val viewModel = viewModel(FakeConnections(client), FakeBufferRepository(buffer(true)), pendingShares = shares, prefs = prefs)
            viewModel.reviewContext()
            assertNull(viewModel.contextReview.value?.destination)
            completeSync(transport, setOf("turn.prompt", "turn.steer"), busy = true)
            viewModel.submitContext()
            runCurrent()
            assertTrue(outboundEnvelopes(transport).none { it.kind.startsWith("turn.") })

            viewModel.reviewContext()
            val limit = "é".repeat(AGENTWIRE_MAX_PROMPT_BYTES / 2)
            val oversized = limit + "a"
            assertTrue(viewModel.editContext(oversized))
            viewModel.submitContext()
            runCurrent()
            assertEquals(oversized, shares.agentContext(BUFFER_ID).value?.prompt)
            assertNotNull(viewModel.state.value.error)
            assertTrue(outboundEnvelopes(transport).none { it.kind.startsWith("turn.") })

            assertTrue(viewModel.editContext(limit))
            val gate = CompletableDeferred<Unit>()
            prefs.deviceGate = gate
            viewModel.submitContext()
            runCurrent()
            assertEquals(limit, shares.agentContext(BUFFER_ID).value?.prompt)
            assertTrue(
                viewModel.state.value.timeline
                    .none { it.kind == "user.prompt" },
            )
            gate.complete(Unit)
            runCurrent()

            val prompt = outboundEnvelopes(transport).single { it.kind.startsWith("turn.") }
            assertEquals("turn.prompt", prompt.kind)
            assertEquals("session-1", prompt.sid)
            assertEquals(limit, prompt.data?.string("content"))
            assertNull(shares.agentContext(BUFFER_ID).value)
            assertEquals(
                limit,
                viewModel.state.value.timeline
                    .single { it.kind == "user.prompt" }
                    .body,
            )
        }

    @Test
    fun `topic change during suspended context send retains edits and revokes review`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val shares = PendingShareStore()
            val prefs = FakeAgentwirePrefs()
            val buffers = FakeBufferRepository(buffer(true))
            shares.assignAgentContext(BUFFER_ID, PendingShare.AgentContext(99, "#source", "Original context", "Two local messages"))
            val viewModel = viewModel(FakeConnections(client), buffers, pendingShares = shares, prefs = prefs)
            completeSync(transport, setOf("turn.prompt"))
            viewModel.reviewContext()
            assertTrue(viewModel.editContext("Edited context to retain"))
            viewModel.keepContextForLater()
            assertEquals("Edited context to retain", shares.agentContext(BUFFER_ID).value?.prompt)
            viewModel.reviewContext()

            val gate = CompletableDeferred<Unit>()
            prefs.deviceGate = gate
            viewModel.submitContext()
            runCurrent()
            buffers.buffers.value = buffer(true, topic = "Ordinary IRC channel")
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertEquals(AgentwireGate.ORDINARY, viewModel.state.value.gate)
            assertNull(viewModel.contextReview.value?.destination)
            assertEquals("Edited context to retain", shares.agentContext(BUFFER_ID).value?.prompt)
            assertTrue(outboundEnvelopes(transport).none { it.kind == "turn.prompt" })
            buffers.buffers.value = buffer(true)
            completeSync(transport, setOf("turn.prompt"))
            viewModel.submitContext()
            runCurrent()
            assertNull(viewModel.contextReview.value?.destination)
            assertTrue(outboundEnvelopes(transport).none { it.kind == "turn.prompt" })
        }

    @Test
    fun `session change away and back and transcript override require another context review`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val shares = PendingShareStore()
            val share = PendingShare.AgentContext(99, "#source", "Context", "Two local messages")
            val viewModel = viewModel(FakeConnections(client), FakeBufferRepository(buffer(true)), pendingShares = shares)
            completeSync(transport, setOf("turn.prompt"), sid = null)
            viewModel.viewTranscript()
            shares.assignAgentContext(BUFFER_ID, share)
            runCurrent()
            assertFalse(viewModel.state.value.transcriptOverride)
            viewModel.reviewContext()
            assertFalse(viewModel.canReviewContext())
            assertNull(viewModel.contextReview.value?.destination)
            transport.feed(tagMessage(BACKEND_ACCOUNT, snapshot("live", sid = "session-1")))
            runCurrent()
            viewModel.reviewContext()
            assertNotNull(viewModel.contextReview.value?.destination)
            transport.feed(tagMessage(BACKEND_ACCOUNT, snapshot("live", sid = "session-2")))
            transport.feed(tagMessage(BACKEND_ACCOUNT, snapshot("live", sid = "session-1")))
            runCurrent()
            assertEquals("session-1", viewModel.state.value.activeSid)
            assertNull(viewModel.contextReview.value?.destination)
            viewModel.submitContext()
            runCurrent()
            assertTrue(outboundEnvelopes(transport).none { it.kind == "turn.prompt" })

            viewModel.reviewContext()
            viewModel.viewTranscript()
            assertTrue(viewModel.editContext("Context edited"))
            runCurrent()
            assertTrue(viewModel.state.value.transcriptOverride)
            viewModel.reviewContext()
            viewModel.submitContext()
            runCurrent()
            assertNull(viewModel.contextReview.value?.destination)
            assertEquals(share.copy(prompt = "Context edited"), shares.agentContext(BUFFER_ID).value)
            assertTrue(outboundEnvelopes(transport).none { it.kind == "turn.prompt" })
            viewModel.returnToHarness()
            viewModel.submitContext()
            runCurrent()
            assertTrue(outboundEnvelopes(transport).none { it.kind == "turn.prompt" })
            viewModel.reviewContext()
            viewModel.submitContext()
            runCurrent()
            assertEquals("session-1", outboundEnvelopes(transport).single { it.kind == "turn.prompt" }.sid)
            assertNull(shares.agentContext(BUFFER_ID).value)
        }

    private suspend fun TestScope.completeSync(
        transport: RecordingTransport,
        actions: Set<String>,
        capabilities: Set<String> = emptySet(),
        sid: String? = "session-1",
        busy: Boolean = false,
    ) {
        advanceTimeBy(10)
        runCurrent()
        val syncId = syncRequests(transport).last()
        transport.feed(tagMessage(BACKEND_ACCOUNT, hello(syncId, actions, capabilities)))
        transport.feed(tagMessage(BACKEND_ACCOUNT, snapshot(syncId, sid, busy)))
        runCurrent()
    }

    @Test
    fun `revoking context review cancels a send waiting behind another transport write`() =
        runTest(dispatcher) {
            val transport = RecordingTransport()
            val client = readyClient(transport)
            val shares = PendingShareStore()
            val share = PendingShare.AgentContext(99, "#source", "Private context", "Two local messages")
            shares.assignAgentContext(BUFFER_ID, share)
            val viewModel = viewModel(FakeConnections(client), FakeBufferRepository(buffer(true)), pendingShares = shares)
            completeSync(transport, setOf("turn.prompt"))
            viewModel.reviewContext()
            val gate = CompletableDeferred<Unit>()
            transport.sendGate = gate
            val occupying =
                launch {
                    client.sendAtomicallyIfConnected(listOf(IrcMessage(command = "TAGMSG", params = listOf("#other"))))
                }
            runCurrent()
            assertFalse(occupying.isCompleted)
            viewModel.submitContext()
            runCurrent()
            assertTrue(viewModel.contextReview.value?.sending == true)
            viewModel.viewTranscript()
            gate.complete(Unit)
            runCurrent()
            occupying.join()

            assertTrue(outboundEnvelopes(transport).none { it.kind == "turn.prompt" })
            assertEquals(share, shares.agentContext(BUFFER_ID).value)
            assertNull(viewModel.contextReview.value?.destination)
            assertFalse(viewModel.contextReview.value?.sending == true)
        }

    private fun TestScope.viewModel(
        connections: FakeConnections,
        buffers: FakeBufferRepository,
        diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
        lab: Boolean = true,
        pendingShares: PendingShareStore = PendingShareStore(),
        prefs: AgentwirePrefs = FakeAgentwirePrefs(lab),
    ): AgentwireViewModel =
        AgentwireViewModel(
            savedStateHandle = SavedStateHandle(mapOf("bufferId" to BUFFER_ID)),
            prefs = prefs,
            buffers = buffers,
            connections = connections,
            diagnostics = diagnostics,
            pendingShares = pendingShares,
            clock = AppClock { testScheduler.currentTime },
        )

    private fun TestScope.viewModel(client: IrcClient): AgentwireViewModel = viewModel(FakeConnections(client), FakeBufferRepository(buffer(joined = true)))

    private fun actionScope(
        networkId: Long = NETWORK_ID,
        backend: String = "claude",
    ): String {
        val material = listOf(networkId.toString(), "controller", BACKEND_ACCOUNT, backend).joinToString("") { "${it.length}:$it" }
        return java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(material.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun buffer(
        joined: Boolean,
        topic: String = "agentwire:v1;account=controller;agent=$BACKEND_ACCOUNT;backend=claude | Claude",
    ) = BufferEntity(
        id = BUFFER_ID,
        networkId = NETWORK_ID,
        name = CHANNEL,
        displayName = CHANNEL,
        type = BufferType.CHANNEL,
        topic = topic,
        joined = joined,
    )

    private suspend fun TestScope.readyClient(transport: RecordingTransport): IrcClient {
        val client =
            IrcClient(
                IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
                TransportFactory { _, _, _, _, _ -> transport },
                CoroutineScope(SupervisorJob() + coroutineContext),
            )
        client.start()
        runCurrent()
        val caps = AGENTWIRE_REQUIRED_CAPS.joinToString(" ")
        transport.feed(":srv CAP * LS :$caps")
        runCurrent()
        transport.feed(":srv CAP me ACK :$caps")
        transport.feed(":srv 005 me CHANTYPES=# :supported")
        transport.feed(":srv 001 me :Welcome")
        runCurrent()
        check(client.state.value is IrcClientState.Ready) { "client is ${client.state.value}" }
        transport.sent.clear()
        return client
    }

    private fun outboundEnvelopes(transport: RecordingTransport): List<AgentwireEnvelope> {
        val reassembler = AgentwireReassembler()
        return transport.sent.mapNotNull { line ->
            val tag = runCatching { IrcMessage.parse(line) }.getOrNull()?.tags?.get(AGENTWIRE_TAG) ?: return@mapNotNull null
            when (val value = decodeAgentwireValue(tag).getOrNull()) {
                is AgentwireValue.Envelope -> value.value
                is AgentwireValue.Fragment -> reassembler.accept(value.value).getOrThrow()
                null -> null
            }
        }
    }

    /** Ids of the `sync.request` envelopes this device actually wrote to the wire. */
    private fun syncRequests(transport: RecordingTransport) = outboundEnvelopes(transport).filter { it.kind == "sync.request" }.map { it.id }

    /** Serialized so tag values are escaped: envelope JSON contains spaces and semicolons. */
    private fun tagMessage(
        account: String,
        envelope: AgentwireEnvelope,
    ): String =
        IrcMessage(
            tags = mapOf("account" to account, AGENTWIRE_TAG to encodeAgentwireEnvelope(envelope)),
            source = Prefix(account, "u", "h"),
            command = "TAGMSG",
            params = listOf(CHANNEL),
        ).serialize()

    private fun hello(
        reply: String,
        actions: Set<String> = emptySet(),
        capabilities: Set<String> = emptySet(),
    ) = AgentwireEnvelope(
        kind = "agent.hello",
        type = "event",
        id = UUID.randomUUID().toString(),
        at = 1,
        instance = "bridge",
        epoch = "epoch-1",
        reply = reply,
        data =
            buildJsonObject {
                put("epoch", "epoch-1")
                put("actions", buildJsonArray { actions.forEach { add(JsonPrimitive(it)) } })
                put("capabilities", buildJsonArray { capabilities.forEach { add(JsonPrimitive(it)) } })
            },
    )

    private fun snapshot(
        reply: String,
        sid: String? = "session-1",
        busy: Boolean = false,
    ) = AgentwireEnvelope(
        kind = "channel.snapshot",
        type = "event",
        id = UUID.randomUUID().toString(),
        at = 2,
        instance = "bridge",
        epoch = "epoch-1",
        reply = reply,
        data =
            buildJsonObject {
                sid?.let { put("binding", buildJsonObject { put("sid", it) }) }
                put("busy", busy)
                if (busy) put("settings", buildJsonObject { put("delivery", "steer") })
            },
    )

    private fun actionFailed(
        reply: String,
        message: String,
    ) = AgentwireEnvelope(
        kind = "action.failed",
        type = "event",
        id = UUID.randomUUID().toString(),
        at = 1,
        instance = "bridge",
        reply = reply,
        data = buildJsonObject { put("message", message) },
    )

    private fun actionStatus(
        reply: String,
        actionId: String,
        status: String,
    ) = AgentwireEnvelope(
        kind = "action.status",
        type = "event",
        id = UUID.randomUUID().toString(),
        at = 3,
        instance = "bridge",
        epoch = "epoch-1",
        reply = reply,
        data =
            buildJsonObject {
                put("actionId", actionId)
                put("status", status)
                put("kind", "turn.prompt")
                put("channel", CHANNEL)
                put("receivedAt", 2)
            },
    )

    private fun diagnosticsSnapshot(
        reply: String,
        history: Boolean = false,
    ) = AgentwireEnvelope(
        kind = "diagnostics.snapshot",
        type = "event",
        id = UUID.randomUUID().toString(),
        at = 3,
        instance = "bridge",
        epoch = "epoch-1",
        reply = reply,
        history = history,
        data =
            buildJsonObject {
                put("schemaVersion", 1)
                put("generatedAt", 1_785_400_000_000)
                put("source", "bridge")
                put(
                    "checks",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("code", "backend.ready")
                                put("status", "ok")
                                put("explanation", "Backend is ready.")
                                put("facts", buildJsonObject { put("ready", true) })
                            },
                        )
                    },
                )
            },
    )

    private class RecordingTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var sendGate: CompletableDeferred<Unit>? = null
        var throwAfterWrite = false

        override suspend fun connect() = Unit

        override val incoming = inbound.consumeAsFlow()

        override suspend fun send(line: String) {
            sendGate?.await()
            sent += line
            if (throwAfterWrite) {
                throwAfterWrite = false
                error("transport write outcome unknown")
            }
        }

        override suspend fun close() = inbound.close().let { }

        suspend fun feed(line: String) = inbound.send(line)
    }

    private class RecordingDiagnostics : DiagnosticLogger {
        data class Record(
            val component: String,
            val event: String,
            val fields: Map<String, Any?>,
        )

        val records = mutableListOf<Record>()
        override val enabled = MutableStateFlow(true)

        override fun setEnabled(enabled: Boolean) = Unit

        override fun record(
            component: String,
            event: String,
            fields: () -> Map<String, Any?>,
        ) {
            records += Record(component, event, fields())
        }

        // Deliberately not value-embedding, so the "no raw identity" assertion is meaningful.
        override fun fingerprint(value: String?): String? = value?.let { "fp-${it.hashCode()}" }

        override suspend fun exportTo(output: OutputStream) = Unit
    }

    private class FakeAgentwirePrefs(
        lab: Boolean = true,
        initialReceipts: List<AgentwireActionReceipt> = emptyList(),
    ) : AgentwirePrefs(ApplicationProvider.getApplicationContext<Context>()) {
        override val enabled: Flow<Boolean> = flowOf(lab)
        val receipts = MutableStateFlow(initialReceipts)
        var deviceGate: CompletableDeferred<Unit>? = null
        var recordGate: CompletableDeferred<Unit>? = null

        override suspend fun setEnabled(enabled: Boolean) = Unit

        // Kept off the real DataStore so the handshake tests never touch preference file IO.
        override fun recentSessions(channel: String): Flow<List<AgentwireRecentSession>> = flowOf(emptyList())

        override suspend fun addRecentSession(
            channel: String,
            sid: String,
            title: String,
            cwd: String?,
            backend: String?,
        ) = Unit

        override fun actionReceipts(): Flow<List<AgentwireActionReceipt>> = receipts

        override suspend fun recordAction(receipt: AgentwireActionReceipt) {
            receipts.value = receipts.value.filterNot { it.id == receipt.id } + receipt
            recordGate?.await()
        }

        override suspend fun updateAction(
            id: String,
            scope: String,
            outcome: String,
        ) {
            receipts.value = receipts.value.map { if (it.id == id && it.scope == scope) it.copy(outcome = outcome) else it }
        }

        override suspend fun markUnresolvedActionsUnknown(scope: String) {
            receipts.value = receipts.value.map { if (it.scope == scope && it.outcome in setOf("sent", "accepted")) it.copy(outcome = "unknown") else it }
        }

        override suspend fun deviceId(): String {
            deviceGate?.await()
            return "device-under-test"
        }
    }

    private class FakeBufferRepository(
        buffer: BufferEntity,
    ) : BufferRepository {
        val buffers = MutableStateFlow<BufferEntity?>(buffer)

        override fun observeChatList(): Flow<List<ChatListRow>> = flowOf(emptyList())

        override fun observeBuffer(id: Long): Flow<BufferEntity?> = buffers

        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())

        override suspend fun setPinned(
            id: Long,
            pinned: Boolean,
        ) = Unit

        override suspend fun setMuted(
            id: Long,
            muted: Boolean,
        ): MuteBacklogSuppression? = null

        override suspend fun setLayoutDensityOverride(
            id: Long,
            layout: LayoutDensity?,
        ): Boolean = true

        override suspend fun setPresenceModeOverride(
            id: Long,
            mode: PresenceMode?,
        ): Boolean = true

        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeConnections(
        private var client: IrcClient?,
    ) : NoopConnectionManager() {
        val joins = mutableListOf<Pair<Long, String>>()
        override val connectionStates =
            MutableStateFlow(
                mapOf<Long, IrcClientState>(NETWORK_ID to IrcClientState.Ready("me", AGENTWIRE_REQUIRED_CAPS, emptyMap())),
            )

        override fun clientFor(networkId: Long): IrcClient? = client.takeIf { networkId == NETWORK_ID }

        fun replaceClient(next: IrcClient?) {
            client = next
        }

        override suspend fun joinChannel(
            networkId: Long,
            channel: String,
            key: String?,
        ): Boolean {
            joins += networkId to channel
            return true
        }

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ) = 0L

        override suspend fun ensureServerBuffer(networkId: Long) = 0L
    }
}
