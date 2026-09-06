package io.github.trevarj.motd.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.paging.PagingData
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.agentwire.AgentwirePrefs
import io.github.trevarj.motd.audio.AudioAttachment
import io.github.trevarj.motd.audio.AudioMetadata
import io.github.trevarj.motd.audio.AudioMetadataRepository
import io.github.trevarj.motd.audio.AudioPlaybackController
import io.github.trevarj.motd.audio.AudioPlaybackState
import io.github.trevarj.motd.avatar.LocalAvatarStore
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.ChatListRow
import io.github.trevarj.motd.data.db.DccTransferEntity
import io.github.trevarj.motd.data.db.EventRedirectEntity
import io.github.trevarj.motd.data.db.HistoryGapEntity
import io.github.trevarj.motd.data.db.JoinedChannelRow
import io.github.trevarj.motd.data.db.MemberEntity
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.MuteBacklogSuppression
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ReactionEntity
import io.github.trevarj.motd.data.db.TimelineAnchor
import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.AvatarStyle
import io.github.trevarj.motd.data.prefs.ChatWallpaper
import io.github.trevarj.motd.data.prefs.ContentPreviewConfig
import io.github.trevarj.motd.data.prefs.ContentPreviewPrefs
import io.github.trevarj.motd.data.prefs.FoolsMode
import io.github.trevarj.motd.data.prefs.LayoutDensity
import io.github.trevarj.motd.data.prefs.NickColorPalette
import io.github.trevarj.motd.data.prefs.PresenceMode
import io.github.trevarj.motd.data.prefs.ReplyConfig
import io.github.trevarj.motd.data.prefs.ReplyPrefs
import io.github.trevarj.motd.data.prefs.Settings
import io.github.trevarj.motd.data.prefs.SettingsRepository
import io.github.trevarj.motd.data.prefs.ThemeMode
import io.github.trevarj.motd.data.repo.BufferRepository
import io.github.trevarj.motd.data.repo.BufferRepositoryImpl
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.data.repo.LinkPreview
import io.github.trevarj.motd.data.repo.LinkPreviewRepository
import io.github.trevarj.motd.data.repo.MessageRepository
import io.github.trevarj.motd.data.repo.MessageRepositoryImpl
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.GapFillProgress
import io.github.trevarj.motd.data.sync.HistoryGapFiller
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.data.sync.NoopHistoryGapFiller
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilityReader
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.data.visibility.messagePagingQuery
import io.github.trevarj.motd.dcc.DccTransferController
import io.github.trevarj.motd.di.AppClock
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcClientConfig
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.irc.transport.IrcTransport
import io.github.trevarj.motd.irc.transport.TransportFactory
import io.github.trevarj.motd.service.CertPrompt
import io.github.trevarj.motd.service.ChannelWatch
import io.github.trevarj.motd.service.ChannelWatchDuration
import io.github.trevarj.motd.service.ChannelWatchImpl
import io.github.trevarj.motd.service.ConnectionManager
import io.github.trevarj.motd.service.DeliveryMode
import io.github.trevarj.motd.service.ForegroundBufferTracker
import io.github.trevarj.motd.service.HistoryResyncController
import io.github.trevarj.motd.service.HistoryResyncCoordinator
import io.github.trevarj.motd.service.HistoryResyncState
import io.github.trevarj.motd.service.HistorySyncStatus
import io.github.trevarj.motd.service.PresenceKey
import io.github.trevarj.motd.service.PresenceState
import io.github.trevarj.motd.service.RosterLoadState
import io.github.trevarj.motd.service.TypingTracker
import io.github.trevarj.motd.testing.NoopConnectionManager
import io.github.trevarj.motd.ui.components.ReplyPreviewData
import io.github.trevarj.motd.ui.share.PendingShare
import io.github.trevarj.motd.ui.share.PendingShareStore
import io.github.trevarj.motd.ui.share.SharePickerViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MotdDatabase
    private lateinit var network: NetworkEntity
    private lateinit var channel: BufferEntity
    private lateinit var query: BufferEntity
    private lateinit var processor: EventProcessor

    @Before
    fun setUp() =
        runTest {
            Dispatchers.setMain(dispatcher)
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    // Room work runs on the calling thread, never a pool thread. A DAO call from a runTest
                    // body must not really suspend: while the body waits on a pool thread, the scheduler
                    // is idle and advances virtual time, which fires pending timers such as the 8s entry
                    // catch-up bound mid-arrangement — a machine-speed race that only lost on CI runners.
                    .setQueryExecutor { it.run() }
                    .setTransactionExecutor { it.run() }
                    .build()
            network =
                NetworkEntity(
                    name = "test",
                    role = NetworkRole.DIRECT,
                    host = "irc.example",
                    port = 6697,
                    nick = "me",
                    username = "me",
                    realname = "Me",
                ).let { it.copy(id = db.networkDao().insert(it)) }
            channel =
                BufferEntity(
                    networkId = network.id,
                    name = "#room",
                    displayName = "#room",
                    type = BufferType.CHANNEL,
                ).let { it.copy(id = db.bufferDao().insert(it)) }
            query =
                BufferEntity(
                    networkId = network.id,
                    name = "alice",
                    displayName = "alice",
                    type = BufferType.QUERY,
                ).let { it.copy(id = db.bufferDao().insert(it)) }
            processor = EventProcessor(db, TypingTrackerImpl(), io.github.trevarj.motd.data.sync.MessageNotifier.Noop)
        }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `message submission sends reply metadata and stops typing`() =
        runTest {
            val manager = FakeConnectionManager(network.id, IrcClientState.Ready("me", emptySet(), emptyMap()))
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            val parent = message(channel.id, "parent", msgid = "parent-1", sender = "alice", id = 88)
            vm.setReply(parent)
            vm.state.first { it.replyTo?.msgid == "parent-1" }
            vm.saveDraft("answer")

            val revisionBeforeSubmit = vm.composerDraft.value.revision
            val submission = vm.submit("answer", {}, {})
            val (clearedState, clearedDraft) =
                combine(vm.state, vm.composerDraft) { state, draft ->
                    state to draft
                }.first { (state, draft) ->
                    state.replyTo == null && draft.text.isEmpty() && draft.revision > revisionBeforeSubmit
                }
            submission.join()

            assertEquals(listOf(SentMessage(channel.id, "answer", parent.id)), manager.messages)
            assertEquals(listOf(channel.id to "done"), manager.typing)
            assertNull(clearedState.replyTo)
            assertNull(db.composerDraftDao().byRoom(channel.id))
            assertEquals("", clearedDraft.text)
        }

    @Test
    fun `an accepted send flies a ghost at the row it produced`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            vm.saveDraft("answer")

            vm.submit("answer", {}, {}).join()

            val flight = vm.outgoingFlight.value
            assertNotNull(flight)
            assertEquals("answer", flight!!.text)
            assertEquals(setOf(1L), flight.eventIds)

            // Only the screen ends a flight, once the ghost has reached the row or given up on it.
            vm.onFlightSettled(flight.token)
            assertNull(vm.outgoingFlight.value)
        }

    @Test
    fun `a rewritten send drops its ghost instead of claiming a row it does not match`() =
        runTest {
            // The pipeline stores a reply as "alice: answer" when the client tag is unavailable. A
            // ghost showing the bare text must not seize that row -- it is already animating in.
            val manager = FakeConnectionManager(network.id, storedTexts = { listOf("alice: $it") })
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            vm.saveDraft("answer")

            vm.submit("answer", {}, {}).join()

            assertEquals(listOf(SentMessage(channel.id, "answer", null)), manager.messages)
            assertNull(vm.outgoingFlight.value)
            assertEquals("", vm.composerDraft.value.text)
        }

    @Test
    fun `a send split across rows drops its ghost`() =
        runTest {
            val manager = FakeConnectionManager(network.id, storedTexts = { it.split(" ") })
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            vm.saveDraft("two lines")

            vm.submit("two lines", {}, {}).join()

            assertNull(vm.outgoingFlight.value)
            assertEquals("", vm.composerDraft.value.text)
        }

    @Test
    fun `an emote never launches a ghost`() =
        runTest {
            // The manager rewrites it into an ACTION row with the prefix stripped, so the ghost would
            // match no row and land on a bubble it does not resemble.
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            vm.saveDraft("/me waves")

            vm.submit("/me waves", {}, {}).join()

            assertEquals(listOf(SentMessage(channel.id, "/me waves", null)), manager.messages)
            assertNull(vm.outgoingFlight.value)
        }

    @Test
    fun `a rejected send drops its ghost and republishes the retained draft`() =
        runTest {
            val manager = FakeConnectionManager(network.id, sendAccepted = false)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            vm.saveDraft("answer")
            val revisionBefore = vm.composerDraft.value.revision

            vm.submit("answer", {}, {}).join()

            // The composer emptied on the tap, so a rejection has to hand the text back explicitly.
            assertNull(vm.outgoingFlight.value)
            assertEquals("answer", vm.composerDraft.value.text)
            assertTrue(vm.composerDraft.value.revision > revisionBefore)
        }

    @Test
    fun `a submission the draft no longer matches is reported instead of dropped`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            vm.saveDraft("answer")
            val revisionBefore = vm.composerDraft.value.revision

            // A stale callback: the text offered no longer matches the draft this ViewModel holds.
            vm.submit("something else", {}, {}).join()

            assertTrue(manager.messages.isEmpty())
            assertNull(vm.outgoingFlight.value)
            assertEquals("answer", vm.composerDraft.value.text)
            assertTrue(vm.composerDraft.value.revision > revisionBefore)
            assertEquals(
                ChatUiEvent.SendDropped,
                vm.uiEvents.value
                    .single()
                    .value,
            )
        }

    @Test
    fun `send rejection retains draft text and reply`() =
        runTest {
            val manager = FakeConnectionManager(network.id, sendAccepted = false)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            val parent = message(channel.id, "parent", msgid = "parent-1", sender = "alice", id = 88)
            vm.setReply(parent)
            vm.saveDraft("answer")

            val submission = vm.submit("answer", {}, {})
            submission.join()

            assertEquals("answer", db.composerDraftDao().byRoom(channel.id)?.text)
            assertEquals(88L, db.composerDraftDao().byRoom(channel.id)?.replyToEventId)
            assertEquals(parent, vm.state.first { it.replyTo?.id == parent.id }.replyTo)
            assertEquals("answer", vm.composerDraft.value.text)
        }

    @Test
    fun `parted channel exposes parted state and surfaces not-in-channel rejection`() =
        runTest {
            val parted =
                BufferEntity(
                    networkId = network.id,
                    name = "#left",
                    displayName = "#left",
                    type = BufferType.CHANNEL,
                    joined = false,
                ).let { it.copy(id = db.bufferDao().insert(it)) }
            val manager =
                FakeConnectionManager(
                    network.id,
                    sendRejection = io.github.trevarj.motd.service.SendRejectionReason.NOT_IN_CHANNEL,
                )
            val vm = viewModel(parted, manager)
            vm.state.first { it.buffer != null }

            assertTrue(vm.state.value.parted)

            vm.saveDraft("hello?")
            vm.submit("hello?", {}, {}).join()

            assertTrue(manager.messages.isEmpty())
            assertEquals(
                ChatUiEvent.NotInChannel,
                vm.uiEvents.value
                    .single()
                    .value,
            )
        }

    @Test
    fun `retry while not in channel surfaces not-in-channel rejection`() =
        runTest {
            val parted =
                BufferEntity(
                    networkId = network.id,
                    name = "#left",
                    displayName = "#left",
                    type = BufferType.CHANNEL,
                    joined = false,
                ).let { it.copy(id = db.bufferDao().insert(it)) }
            val manager =
                FakeConnectionManager(
                    network.id,
                    retryRejection = io.github.trevarj.motd.service.SendRejectionReason.NOT_IN_CHANNEL,
                )
            val messages = FakeMessageRepository()
            val vm = viewModel(parted, manager, messages = messages)
            vm.state.first { it.buffer != null }
            val failed = message(parted.id, "try again", null, "me", id = 77).copy(failed = true)

            vm.retry(failed)
            advanceUntilIdle()

            assertEquals(
                ChatUiEvent.NotInChannel,
                vm.uiEvents.value
                    .single()
                    .value,
            )
        }

    @Test
    fun `rapid duplicate submits of an unchanged draft send only once`() =
        runTest {
            val sendGate = CompletableDeferred<Unit>()
            val manager = FakeConnectionManager(network.id, sendGate = sendGate)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            val link = "https://crafterbin.example/paste"
            vm.saveDraft(link)

            val first = vm.submit(link, {}, {})
            runCurrent()
            manager.messageStarted.await()

            val second = vm.submit(link, {}, {})
            val third = vm.submit(link, {}, {})
            runCurrent()

            assertEquals(listOf(SentMessage(channel.id, link, null)), manager.messages)

            sendGate.complete(Unit)
            advanceUntilIdle()
            first.join()
            second.join()
            third.join()

            // A callback held by Compose until after the accepted draft clear is stale, not a new edit.
            val staleCallback = vm.submit(link, {}, {})
            advanceUntilIdle()
            staleCallback.join()

            assertEquals(listOf(SentMessage(channel.id, link, null)), manager.messages)
            assertEquals("", vm.composerDraft.value.text)
        }

    @Test
    fun `a prefill pushed at the open chat arrives as composer prefill text`() =
        runTest {
            // The gesture orb inserts into the visible conversation without navigating: delivery rides
            // composerPrefills alone, so this chain must work with the screen already collecting.
            val drafts = ComposerDraftStore(db)
            val vm = viewModel(channel, FakeConnectionManager(network.id), drafts = drafts)
            advanceUntilIdle()

            val received = mutableListOf<String>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    vm.composerPrefills.collect { received += it }
                }
            drafts.push(channel.id, "on my way ")
            runCurrent()
            collector.cancel()

            assertEquals(listOf("on my way "), received)
            // Delivered means consumed: entry hydration later must not append it a second time.
            assertEquals(null, drafts.consume(channel.id))
        }

    @Test
    fun `late hydration keeps fresh text and restores persisted reply`() =
        runTest {
            val parent = message(channel.id, "parent", msgid = "parent-1", sender = "alice", id = 88)
            ComposerDraftStore(db).saveDraft(channel.id, "old text", parent.id)
            val messages = FakeMessageRepository(listOf(parent))
            val vm = viewModel(channel, FakeConnectionManager(network.id), messages = messages)

            vm.saveDraft("fresh text")
            advanceUntilIdle()

            assertEquals("fresh text", vm.composerDraft.value.text)
            assertEquals(parent, vm.state.first { it.replyTo != null }.replyTo)
            assertEquals("fresh text", db.composerDraftDao().byRoom(channel.id)?.text)
            assertEquals(parent.id, db.composerDraftDao().byRoom(channel.id)?.replyToEventId)

            val recreated =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    messages = messages,
                )
            assertEquals("fresh text", recreated.composerDraft.first { it.hydrated }.text)
            assertEquals(parent, recreated.state.first { it.replyTo != null }.replyTo)
        }

    @Test
    fun `same text retyped after submit is not cleared and survives recreation`() =
        runTest {
            val sendGate = CompletableDeferred<Unit>()
            val manager = FakeConnectionManager(network.id, sendGate = sendGate)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            vm.saveDraft("answer")

            vm.submit("answer", {}, {})
            manager.messageStarted.await()
            vm.saveDraft("answer")
            sendGate.complete(Unit)
            manager.typingSent.await()

            assertEquals("answer", vm.composerDraft.value.text)
            assertEquals("answer", db.composerDraftDao().byRoom(channel.id)?.text)

            val recreated = viewModel(channel, FakeConnectionManager(network.id))
            val restored = recreated.composerDraft.first { it.hydrated }
            assertEquals("answer", restored.text)
        }

    @Test
    fun `mention nicks resolve from the cached roster without an authoritative roster load`() =
        runTest {
            db.memberDao().insertAll(
                listOf(
                    MemberEntity(channel.id, "Alice"),
                    MemberEntity(channel.id, "bob", prefixes = "@"),
                ),
            )
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    buffers =
                        BufferRepositoryImpl(
                            bufferDao = db.bufferDao(),
                            memberDao = db.memberDao(),
                            messageDao = db.messageDao(),
                            settings = FakeSettingsRepository(),
                            visibilityReader = MessageVisibilityReader(db),
                            localAvatarStore = LocalAvatarStore(ApplicationProvider.getApplicationContext()),
                        ),
                )

            // Mention coloring must not wait for ensureMembersObserved() or a LOADED roster: neither has
            // happened here, yet the locally cached nicks are already resolvable.
            assertEquals(setOf("alice", "bob"), vm.knownNicks.first { it.isNotEmpty() })
            assertTrue(vm.memberNicks.value.isEmpty())
        }

    @Test
    fun `conversation layout inherits global then persists and clears an override`() =
        runTest {
            val settings = FakeSettingsRepository()
            val buffers = FakeBufferRepository(channel)
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    buffers = buffers,
                    settings = settings,
                )
            vm.state.first { it.buffer != null }

            settings.settings.value = Settings(layoutDensity = LayoutDensity.COMPACT)
            assertEquals(
                LayoutDensity.COMPACT,
                vm.state
                    .first { it.conversationLayout.global == LayoutDensity.COMPACT }
                    .conversationLayout.effective,
            )

            vm.setConversationLayoutOverride(LayoutDensity.TWO_LINE)
            advanceUntilIdle()
            assertEquals(listOf(channel.id to LayoutDensity.TWO_LINE), buffers.layoutWrites)
            assertEquals(
                LayoutDensity.TWO_LINE,
                vm.state
                    .first { it.conversationLayout.override == LayoutDensity.TWO_LINE }
                    .conversationLayout.effective,
            )

            settings.settings.value = Settings(layoutDensity = LayoutDensity.COMFORTABLE)
            assertEquals(
                LayoutDensity.TWO_LINE,
                vm.state
                    .first { it.conversationLayout.global == LayoutDensity.COMFORTABLE }
                    .conversationLayout.effective,
            )

            vm.setConversationLayoutOverride(null)
            advanceUntilIdle()
            assertEquals(
                LayoutDensity.COMFORTABLE,
                vm.state
                    .first { it.conversationLayout.override == null }
                    .conversationLayout.effective,
            )
        }

    @Test
    fun `server layout defaults to two-line until explicitly overridden`() =
        runTest {
            val server = channel.copy(name = "*", displayName = "test", type = BufferType.SERVER)
            val settings =
                FakeSettingsRepository().apply {
                    this.settings.value = Settings(layoutDensity = LayoutDensity.COMPACT)
                }
            val buffers = FakeBufferRepository(server)
            val vm =
                viewModel(
                    server,
                    FakeConnectionManager(network.id),
                    buffers = buffers,
                    settings = settings,
                )

            assertEquals(
                LayoutDensity.TWO_LINE,
                vm.state
                    .first {
                        it.buffer?.type == BufferType.SERVER &&
                            it.conversationLayout.bufferDefault == LayoutDensity.TWO_LINE
                    }.conversationLayout.effective,
            )

            vm.setConversationLayoutOverride(LayoutDensity.COMFORTABLE)
            assertEquals(
                LayoutDensity.COMFORTABLE,
                vm.state
                    .first { it.conversationLayout.override == LayoutDensity.COMFORTABLE }
                    .conversationLayout.effective,
            )

            vm.setConversationLayoutOverride(null)
            assertEquals(
                LayoutDensity.TWO_LINE,
                vm.state
                    .first {
                        it.buffer?.type == BufferType.SERVER &&
                            it.conversationLayout.override == null &&
                            it.conversationLayout.bufferDefault == LayoutDensity.TWO_LINE
                    }.conversationLayout.effective,
            )
        }

    @Test
    fun `conversation layout write failure is surfaced without optimistic state`() =
        runTest {
            val buffers = FakeBufferRepository(channel).apply { layoutWriteResult = false }
            val vm = viewModel(channel, FakeConnectionManager(network.id), buffers = buffers)
            vm.state.first { it.buffer != null }

            vm.setConversationLayoutOverride(LayoutDensity.COMPACT)
            advanceUntilIdle()

            assertEquals(listOf(channel.id to LayoutDensity.COMPACT), buffers.layoutWrites)
            assertNull(vm.state.value.conversationLayout.override)
            assertEquals(
                ChatUiEvent.ConversationLayoutWriteFailed,
                vm.uiEvents
                    .first()
                    .single()
                    .value,
            )
        }

    @Test
    fun `conversation layouts write to canonical buffers independently`() =
        runTest {
            val redirectedChannel = FakeBufferRepository(channel, routeId = query.id)
            val queryBuffers = FakeBufferRepository(query)
            val channelVm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    routeBufferId = query.id,
                    buffers = redirectedChannel,
                )
            val queryVm = viewModel(query, FakeConnectionManager(network.id), buffers = queryBuffers)
            channelVm.state.first { it.buffer?.id == channel.id }
            queryVm.state.first { it.buffer?.id == query.id }

            channelVm.setConversationLayoutOverride(LayoutDensity.COMPACT)
            queryVm.setConversationLayoutOverride(LayoutDensity.TWO_LINE)
            advanceUntilIdle()

            assertEquals(listOf(channel.id to LayoutDensity.COMPACT), redirectedChannel.layoutWrites)
            assertEquals(listOf(query.id to LayoutDensity.TWO_LINE), queryBuffers.layoutWrites)
            assertEquals(LayoutDensity.COMPACT, channelVm.state.value.conversationLayout.effective)
            assertEquals(LayoutDensity.TWO_LINE, queryVm.state.value.conversationLayout.effective)
        }

    @Test
    fun `presence mode inherits global then persists and clears an override`() =
        runTest {
            val settings = FakeSettingsRepository()
            val buffers = FakeBufferRepository(channel)
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    buffers = buffers,
                    settings = settings,
                )
            vm.state.first { it.buffer != null }

            settings.settings.value = Settings(presenceMode = PresenceMode.SMART)
            assertEquals(
                PresenceMode.SMART,
                vm.state
                    .first { it.conversationPresence.global == PresenceMode.SMART }
                    .conversationPresence.effective,
            )

            vm.setPresenceModeOverride(PresenceMode.ALL)
            advanceUntilIdle()
            assertEquals(listOf(channel.id to PresenceMode.ALL), buffers.presenceWrites)
            assertEquals(
                PresenceMode.ALL,
                vm.state
                    .first { it.conversationPresence.override == PresenceMode.ALL }
                    .conversationPresence.effective,
            )

            // A global change must not disturb a conversation that has made its own choice.
            settings.settings.value = Settings(presenceMode = PresenceMode.HIDDEN)
            assertEquals(
                PresenceMode.ALL,
                vm.state
                    .first { it.conversationPresence.global == PresenceMode.HIDDEN }
                    .conversationPresence.effective,
            )

            vm.setPresenceModeOverride(null)
            advanceUntilIdle()
            assertEquals(
                PresenceMode.HIDDEN,
                vm.state
                    .first { it.conversationPresence.override == null }
                    .conversationPresence.effective,
            )
        }

    @Test
    fun `presence mode write failure is surfaced without optimistic state`() =
        runTest {
            val buffers = FakeBufferRepository(channel).apply { presenceWriteResult = false }
            val vm = viewModel(channel, FakeConnectionManager(network.id), buffers = buffers)
            vm.state.first { it.buffer != null }

            vm.setPresenceModeOverride(PresenceMode.HIDDEN)
            advanceUntilIdle()

            assertEquals(listOf(channel.id to PresenceMode.HIDDEN), buffers.presenceWrites)
            assertNull(vm.state.value.conversationPresence.override)
            assertEquals(
                ChatUiEvent.PresenceModeWriteFailed,
                vm.uiEvents
                    .first()
                    .single()
                    .value,
            )
        }

    @Test
    fun `selecting reply primes its timeline preview before repository collection`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            val parent = message(channel.id, "original text", msgid = "parent-1", sender = "alice")
            assertTrue(vm.replyPreview("parent-1").value == null)

            vm.setReply(parent)

            assertEquals(ReplyPreviewData("alice", "original text"), vm.replyPreview("parent-1").value)
        }

    @Test
    fun `reply warns when loaded channel roster excludes sender without clearing selection`() =
        runTest {
            val manager = FakeConnectionManager(network.id).apply { rosterStates.value = mapOf(channel.id to RosterLoadState.LOADED) }
            val buffers = FakeBufferRepository(channel)
            val prefs = FakeReplyPrefs(ReplyConfig(visibleChannelPrefix = true))
            val vm = viewModel(channel, manager, buffers = buffers, replyPrefs = prefs)
            vm.state.first { it.buffer != null }
            vm.replyConfig.first { it.visibleChannelPrefix }
            val parent = message(channel.id, "parent", msgid = "parent-1", sender = "alice", id = 88)

            vm.setReply(parent)
            runCurrent()

            val state = vm.state.first { it.replyTo?.id == parent.id && it.replySenderNotInChannel }
            assertEquals(parent, state.replyTo)

            buffers.memberNicks.value = listOf("ALICE")
            vm.setReply(parent)
            runCurrent()

            assertFalse(vm.state.value.replySenderNotInChannel)
            assertTrue(manager.memberRequests.isEmpty())
        }

    @Test
    fun `reply does not warn for unknown or non-loaded roster or later roster load`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm =
                viewModel(
                    channel,
                    manager,
                    replyPrefs = FakeReplyPrefs(ReplyConfig(visibleChannelPrefix = true)),
                )
            vm.state.first { it.buffer != null }
            vm.replyConfig.first { it.visibleChannelPrefix }

            val parent = message(channel.id, "parent", msgid = "parent-1", sender = "alice", id = 88)
            vm.setReply(parent)
            runCurrent()
            manager.rosterStates.value = mapOf(channel.id to RosterLoadState.NOT_LOADED)
            vm.setReply(parent)
            runCurrent()
            manager.rosterStates.value = mapOf(channel.id to RosterLoadState.LOADED)
            runCurrent()

            assertFalse(vm.state.value.replySenderNotInChannel)
            assertTrue(manager.memberRequests.isEmpty())
        }

    @Test
    fun `reply does not warn when prefix disabled or buffer is query or message is self`() =
        runTest {
            val channelManager = FakeConnectionManager(network.id).apply { rosterStates.value = mapOf(channel.id to RosterLoadState.LOADED) }
            val disabled = viewModel(channel, channelManager)
            disabled.state.first { it.buffer != null }
            disabled.setReply(message(channel.id, "parent", msgid = "parent-1", sender = "alice", id = 88))

            val queryManager = FakeConnectionManager(network.id).apply { rosterStates.value = mapOf(query.id to RosterLoadState.LOADED) }
            val queryVm =
                viewModel(
                    query,
                    queryManager,
                    replyPrefs = FakeReplyPrefs(ReplyConfig(visibleChannelPrefix = true)),
                )
            queryVm.state.first { it.buffer != null }
            queryVm.replyConfig.first { it.visibleChannelPrefix }
            queryVm.setReply(message(query.id, "parent", msgid = "parent-2", sender = "alice", id = 89))

            val selfVm =
                viewModel(
                    channel,
                    channelManager,
                    replyPrefs = FakeReplyPrefs(ReplyConfig(visibleChannelPrefix = true)),
                )
            selfVm.state.first { it.buffer != null }
            selfVm.replyConfig.first { it.visibleChannelPrefix }
            selfVm.setReply(
                message(channel.id, "parent", msgid = "parent-3", sender = "me", id = 90).copy(isSelf = true),
            )
            runCurrent()

            assertFalse(disabled.state.value.replySenderNotInChannel)
            assertFalse(queryVm.state.value.replySenderNotInChannel)
            assertFalse(selfVm.state.value.replySenderNotInChannel)
        }

    @Test
    fun `cancel and replacement clear and suppress stale reply warning`() =
        runTest {
            val manager = FakeConnectionManager(network.id).apply { rosterStates.value = mapOf(channel.id to RosterLoadState.LOADED) }
            val buffers = FakeBufferRepository(channel).apply { memberNicks.value = listOf("bob") }
            val vm =
                viewModel(
                    channel,
                    manager,
                    buffers = buffers,
                    replyPrefs = FakeReplyPrefs(ReplyConfig(visibleChannelPrefix = true)),
                )
            vm.state.first { it.buffer != null }
            vm.replyConfig.first { it.visibleChannelPrefix }
            val absent = message(channel.id, "old", msgid = "parent-1", sender = "alice", id = 88)
            val present = message(channel.id, "new", msgid = "parent-2", sender = "bob", id = 89)

            vm.setReply(absent)
            runCurrent()
            assertTrue(vm.state.value.replySenderNotInChannel)
            vm.setReply(null)
            runCurrent()
            assertFalse(vm.state.value.replySenderNotInChannel)

            vm.setReply(absent)
            vm.setReply(null)
            runCurrent()
            assertFalse(vm.state.value.replySenderNotInChannel)

            vm.setReply(absent)
            vm.setReply(present)
            runCurrent()

            val state = vm.state.first { it.replyTo?.id == present.id }
            assertEquals(present, state.replyTo)
            assertFalse(state.replySenderNotInChannel)
        }

    @Test
    fun `msg submission creates query target and opens it`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            val opened = CompletableDeferred<Long>()
            vm.saveDraft("/msg alice hello there")

            vm.submit("/msg alice hello there", { opened.complete(it) })
            val openedBuffer = opened.await()

            assertEquals(listOf(SentMessage(query.id, "hello there", null, channel.ircTarget)), manager.messages)
            assertEquals(query.id, openedBuffer)
        }

    @Test
    fun `moderation commands are ignored outside channel buffers`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(query, manager)
            vm.state.first { it.buffer != null }

            vm.submit("/kick alice", {}, {})
            vm.submit("/ban alice", {}, {})
            advanceUntilIdle()

            assertTrue(manager.sentLines.isEmpty())
        }

    @Test
    fun `channel commands use wire target instead of collision-safe internal name`() =
        runTest {
            val transport = RecordingTransport()
            val client = testClient(transport)
            client.start()
            // Advancing virtual time here would fire the client's watchdog and close the transport.
            runCurrent()
            transport.sent.clear()
            val collisionRoom =
                channel.copy(
                    name = "#room\u0000account:stable",
                    displayName = "!WireRoom",
                )
            val vm =
                viewModel(
                    collisionRoom,
                    FakeConnectionManager(network.id, client = client),
                )
            vm.state.first { it.buffer != null }

            vm.submit("/topic reviewed topic", {}, {})
            vm.setMemberMode("alice", 'o', grant = true)
            vm.kick("bob", "reason")
            vm.ban("carol")
            runCurrent()

            val commands = transport.sent.map { IrcMessage.parse(it) }
            assertEquals(listOf("TOPIC", "MODE", "KICK", "MODE"), commands.map { it.command })
            assertTrue(commands.all { it.params.firstOrNull() == "!WireRoom" })
            assertTrue(transport.sent.none { '\u0000' in it })
            client.stop()
            runCurrent()
        }

    @Test
    fun `join opens its first channel after the buffer appears`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val buffers = FakeBufferRepository(channel)
            val opened = mutableListOf<Long>()
            val vm = viewModel(channel, manager, buffers = buffers)
            vm.state.first { it.buffer != null }

            vm.submit("/join #new,#other", opened::add)
            runCurrent()
            assertTrue(opened.isEmpty())
            buffers.chatList.value =
                listOf(
                    ChatListRow(
                        bufferId = 42,
                        networkId = network.id,
                        networkName = network.name,
                        displayName = "#new",
                        type = BufferType.CHANNEL,
                        pinned = false,
                        muted = false,
                        lastMessageText = null,
                        lastMessageSender = null,
                        lastMessageTime = null,
                        unreadCount = 0,
                        mentionCount = 0,
                    ),
                )
            runCurrent()

            assertEquals(listOf(Triple(network.id, "#new,#other", null)), manager.joins)
            assertEquals(listOf(42L), opened)
        }

    /** #51: the composer's new first-class commands, checked on the wire rather than at the parser. */
    @Test
    fun `new slash commands reach the wire with buffer-derived targets`() =
        runTest {
            val transport = RecordingTransport()
            val client = testClient(transport)
            client.start()
            runCurrent()
            transport.sent.clear()
            val manager = FakeConnectionManager(network.id, client = client)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }

            vm.submit("/mode +m", {}, {})
            vm.submit("/mode #other +o alice", {}, {})
            vm.submit("/notice #chan heads up", {}, {})
            vm.submit("/invite bob", {}, {})
            vm.submit("/knock #secret let me in", {}, {})
            vm.submit("/setname Ada Lovelace", {}, {})
            vm.submit("/motd", {}, {})
            vm.submit("/raw LINKS", {}, {})
            runCurrent()

            val sent = transport.sent.map { IrcMessage.parse(it) }
            assertEquals(
                listOf("MODE", "MODE", "NOTICE", "INVITE", "KNOCK", "SETNAME", "MOTD", "LINKS"),
                sent.map { it.command },
            )
            // An omitted target resolves to this conversation; an explicit one is respected.
            assertEquals(listOf(channel.ircTarget, "+m"), sent[0].params)
            assertEquals(listOf("#other", "+o", "alice"), sent[1].params)
            assertEquals(listOf("#chan", "heads up"), sent[2].params)
            assertEquals(listOf("bob", channel.ircTarget), sent[3].params)
            assertEquals(listOf("#secret", "let me in"), sent[4].params)
            assertEquals(listOf("Ada Lovelace"), sent[5].params)
            assertEquals(emptyList<String>(), sent[6].params)
            assertTrue(manager.commandOrigins.all { it.first == channel.id })
            assertEquals(sent.map { it.command }, manager.commandOrigins.map { it.second.command })

            client.stop()
            runCurrent()
        }

    @Test
    fun `outgoing invite reports replay-safe chat feedback`() =
        runTest {
            val manager = FakeConnectionManager(network.id, inviteAccepted = true)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            var accepted: Boolean? = null
            val destination = JoinedChannelRow(channel.id, network.id, channel.displayName)

            vm.inviteToChannel(destination, "alice") { accepted = it }.join()

            assertEquals(listOf(channel.id to "alice"), manager.invites)
            assertEquals(true, accepted)
            assertEquals(
                ChatUiEvent.InviteRequestSent("alice", channel.displayName),
                vm.uiEvents.value
                    .single()
                    .value,
            )
        }

    @Test
    fun `whois remains sheet only instead of using command feedback path`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }

            vm.submit("/whois alice", {}, {})
            advanceUntilIdle()

            assertEquals("alice", vm.nickSheet.value?.nick)
            assertTrue(manager.commandOrigins.isEmpty())
        }

    @Test
    fun `ctcp wraps its request in the delimiter`() =
        runTest {
            val transport = RecordingTransport()
            val client = testClient(transport)
            client.start()
            runCurrent()
            transport.sent.clear()
            val vm = viewModel(channel, FakeConnectionManager(network.id, client = client))
            vm.state.first { it.buffer != null }

            vm.submit("/ctcp alice PING 12345", {}, {})
            runCurrent()

            val sent = IrcMessage.parse(transport.sent.single())
            assertEquals("PRIVMSG", sent.command)
            assertEquals(listOf("alice", "PING 12345"), sent.params)

            client.stop()
            runCurrent()
        }

    /** Regression for the dropped-key bug: `/join #chan key` must carry the key to JOIN. */
    @Test
    fun `join forwards channel keys to the connection manager`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }

            vm.submit("/join #keyed hunter2", {}, {})
            vm.submit("/join #a,#b key-a,key-b", {}, {})
            vm.submit("/join #plain", {}, {})
            advanceUntilIdle()

            assertEquals(
                listOf(
                    Triple(network.id, "#keyed", "hunter2"),
                    Triple(network.id, "#a,#b", "key-a,key-b"),
                    Triple(network.id, "#plain", null),
                ),
                manager.joins,
            )
        }

    @Test
    fun `hop parts the current channel then rejoins it`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }

            vm.submit("/hop brb", {}, {})
            advanceUntilIdle()

            assertEquals(listOf(channel.id to "brb"), manager.parts)
            assertEquals(listOf(Triple(network.id, channel.ircTarget, null)), manager.joins)
        }

    @Test
    fun `hop outside a channel does nothing`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(query, manager)
            vm.state.first { it.buffer != null }

            vm.submit("/hop", {}, {})
            advanceUntilIdle()

            assertTrue(manager.parts.isEmpty())
            assertTrue(manager.joins.isEmpty())
        }

    @Test
    fun `server buffer invalid raw command surfaces snackbar without sending`() =
        runTest {
            val server =
                BufferEntity(
                    networkId = network.id,
                    name = "*",
                    displayName = "test",
                    type = BufferType.SERVER,
                ).let { it.copy(id = db.bufferDao().insert(it)) }
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(server, manager)
            vm.state.first { it.buffer != null }

            vm.submit("/", {}, {})
            advanceUntilIdle()

            assertEquals(
                ChatUiEvent.InvalidCommand,
                vm.uiEvents.value
                    .single()
                    .value,
            )
            assertTrue(manager.sentLines.isEmpty())
        }

    /**
     * Search can open the console, and the SERVER raw-send branch put its text on the wire as an IRC
     * command — `sasl set-plain ... <secret>` unredacted. It is a PRIVMSG surface like any chat.
     */
    @Test
    fun `bouncer console composer sends a message instead of a raw wire line`() =
        runTest {
            db.networkDao().update(network.copy(role = NetworkRole.BOUNCER_ROOT))
            val console =
                BufferEntity(
                    networkId = network.id,
                    name = "bouncerserv",
                    displayName = "BouncerServ",
                    type = BufferType.SERVER,
                ).let { it.copy(id = db.bufferDao().insert(it)) }
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(console, manager)
            vm.state.first { it.buffer != null }
            val command = "sasl set-plain -network libera alice hunter2"
            vm.saveDraft(command)

            vm.submit(command, {}, {}).join()

            assertTrue(manager.sentLines.isEmpty())
            assertEquals(listOf(SentMessage(console.id, command, null)), manager.messages)
        }

    /** soju answers CHATHISTORY for the console, so its transcript is fetchable after a reinstall. */
    @Test
    fun `bouncer console reports history as available`() =
        runTest {
            val console =
                BufferEntity(
                    networkId = network.id,
                    name = "bouncerserv",
                    displayName = "BouncerServ",
                    type = BufferType.SERVER,
                ).let { it.copy(id = db.bufferDao().insert(it)) }
            val ready =
                HistoryAvailability.Ready(
                    setOf(HistoryReferenceType.TIMESTAMP),
                    50,
                )
            val vm =
                viewModel(
                    console,
                    FakeConnectionManager(network.id, client = testClient(), historyAvailability = ready),
                )
            vm.state.first { it.buffer != null }

            assertEquals(ready, vm.historyAvailability.first { it != HistoryAvailability.NegotiatingOrOffline })
        }

    @Test
    fun `visible ready chat does not launch redundant history reconciliation`() =
        runTest {
            val history = FakeHistoryResyncController()
            val manager = FakeConnectionManager(network.id, client = testClient())
            val vm = viewModel(channel, manager, history)
            vm.state.first { it.buffer != null }

            vm.onResume()
            vm.onResume()
            advanceUntilIdle()

            assertTrue(history.reconciledBuffers.isEmpty())
        }

    @Test
    fun `entry readiness distinguishes active catchup from settled or offline startup`() {
        val ready =
            io.github.trevarj.motd.service.ConnectionActivitySnapshot(
                states = mapOf(network.id to IrcClientState.Ready("me", emptySet(), emptyMap())),
            )
        val catchingUp = ready.copy(historyCatchUpPending = setOf(network.id))
        val connecting =
            io.github.trevarj.motd.service.ConnectionActivitySnapshot(
                states = mapOf(network.id to IrcClientState.Connecting),
            )
        val retrying =
            io.github.trevarj.motd.service.ConnectionActivitySnapshot(
                states = mapOf(network.id to IrcClientState.Failed("retry", fatal = false)),
                progressing = mapOf(network.id to true),
            )
        val terminal =
            io.github.trevarj.motd.service.ConnectionActivitySnapshot(
                states = mapOf(network.id to IrcClientState.Failed("fatal", fatal = true)),
            )
        val offline =
            io.github.trevarj.motd.service.ConnectionActivitySnapshot(
                initializationComplete = true,
            )

        assertFalse(entryHistoryReady(catchingUp, network.id))
        assertTrue(entryHistoryReady(ready, network.id))
        assertFalse(entryHistoryReady(connecting, network.id))
        assertFalse(entryHistoryReady(retrying, network.id))
        assertTrue(entryHistoryReady(terminal, network.id))
        assertTrue(entryHistoryReady(offline, network.id))
    }

    @Test
    fun `viewport read marker advances only while chat destination is resumed`() =
        runTest {
            val manager = FakeConnectionManager(network.id)
            val vm = viewModel(channel, manager)
            vm.state.first { it.buffer != null }
            val anchor = TimelineAnchor(serverTime = 500, eventId = 5)

            vm.markRead(anchor)
            advanceUntilIdle()
            vm.onResume()
            vm.markRead(anchor)
            advanceUntilIdle()
            vm.onPause()
            vm.markRead(TimelineAnchor(serverTime = 600, eventId = 6))
            advanceUntilIdle()

            assertEquals(listOf(channel.id to anchor), manager.readMarkers)
        }

    @Test
    fun `timeline history status follows the current buffer`() =
        runTest {
            val history = FakeHistoryResyncController()
            val vm = viewModel(channel, FakeConnectionManager(network.id), history)
            val collector =
                backgroundScope.launch(StandardTestDispatcher(testScheduler)) {
                    vm.historySyncStatus.collect()
                }
            runCurrent()

            history.setSyncStatus(HistorySyncStatus.Partial("fixture"))
            runCurrent()

            assertEquals(HistorySyncStatus.Partial("fixture"), vm.historySyncStatus.value)
            collector.cancel()
        }

    @Test
    fun `manual history retry reconciles the current buffer through the coordinator`() =
        runTest {
            val history = FakeHistoryResyncController()
            val manager = FakeConnectionManager(network.id, client = testClient())
            val vm = viewModel(channel, manager, history)
            vm.state.first { it.buffer != null }

            vm.retryHistorySync()
            advanceUntilIdle()

            // The ordinary reconciliation entry point, so a tap collapses onto any in-flight pass
            // instead of opening a bespoke fetch beside it.
            assertEquals(listOf(channel.id), history.reconciledBuffers)
            assertTrue(history.pendingReconciledBuffers.isEmpty())
        }

    @Test
    fun `manual history retry no-ops without a resolved buffer or a live client`() =
        runTest {
            val history = FakeHistoryResyncController()
            // Route points at a buffer the repository does not know: nothing to reconcile.
            val unresolved =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id, client = testClient()),
                    history,
                    routeBufferId = channel.id + 999,
                    buffers = FakeBufferRepository(channel),
                )
            unresolved.retryHistorySync()
            advanceUntilIdle()

            assertTrue(history.reconciledBuffers.isEmpty())

            // Buffer resolved but the network has no socket: clientFor returns null.
            val offline = viewModel(channel, FakeConnectionManager(network.id), history)
            offline.state.first { it.buffer != null }
            offline.retryHistorySync()
            advanceUntilIdle()

            assertTrue(history.reconciledBuffers.isEmpty())
        }

    @Test
    fun `server buffer never performs automatic history reconciliation`() =
        runTest {
            val server =
                BufferEntity(
                    networkId = network.id,
                    name = "*",
                    displayName = "test",
                    type = BufferType.SERVER,
                ).let { it.copy(id = db.bufferDao().insert(it)) }
            val history = FakeHistoryResyncController()
            val manager = FakeConnectionManager(network.id, client = testClient())
            val vm = viewModel(server, manager, history)
            vm.state.first { it.buffer != null }

            vm.onResume()
            advanceUntilIdle()

            assertTrue(history.reconciledBuffers.isEmpty())
        }

    @Test
    fun `ready server buffer with a read anchor does not wait for ineligible history preparation`() =
        runTest {
            val server =
                BufferEntity(
                    networkId = network.id,
                    name = "*",
                    displayName = "test",
                    type = BufferType.SERVER,
                    localReadAnchorTime = 100,
                    localReadAnchorEventId = 10,
                ).let { it.copy(id = db.bufferDao().insert(it)) }
            val history = FakeHistoryResyncController()
            val vm =
                viewModel(
                    server,
                    FakeConnectionManager(network.id, client = testClient()),
                    history,
                )
            vm.state.first { it.buffer != null }
            vm.onResume()

            val target = checkNotNull(vm.initialTarget.first { it != null })

            assertEquals(100L, target.serverTime)
            assertTrue(history.reconciledBuffers.isEmpty())
        }

    @Test
    fun `stale redirect route uses canonical foreground buffer id`() =
        runTest {
            val canonical = channel.copy(id = 42)
            val foreground = FakeForegroundBufferTracker()
            val manager = FakeConnectionManager(network.id)
            val vm =
                viewModel(
                    buffer = canonical,
                    manager = manager,
                    routeBufferId = channel.id,
                    foreground = foreground,
                )
            vm.state.first { it.buffer != null }

            vm.onResume()
            advanceUntilIdle()

            assertEquals(canonical.id, foreground.foregroundBufferId.value)

            vm.onPause()
            advanceUntilIdle()

            assertEquals(null, foreground.foregroundBufferId.value)
        }

    @Test
    fun `stale redirect route starts and stops a canonical channel watch`() =
        runTest {
            val canonical = channel.copy(id = 42)
            val watch =
                ChannelWatchImpl(
                    scope = backgroundScope,
                    clock = AppClock { testScheduler.currentTime },
                    onExpired = {},
                )
            val vm =
                viewModel(
                    buffer = canonical,
                    manager = FakeConnectionManager(network.id),
                    routeBufferId = channel.id,
                    channelWatch = watch,
                )
            vm.state.first { it.buffer != null }

            vm.startChannelWatch(ChannelWatchDuration.FOREVER)
            runCurrent()

            assertTrue(watch.isActive(canonical.id))
            assertEquals(canonical.id, vm.activeWatch.value?.bufferId)

            vm.stopChannelWatch()
            runCurrent()

            assertNull(vm.activeWatch.value)
        }

    @Test
    fun `reaction uses urgent history reconciliation to promote the msgid`() =
        runTest {
            val messages = FakeMessageRepository()
            val history =
                FakeHistoryResyncController { attempt ->
                    if (attempt == 1) messages.msgid.value = "server-parent"
                }
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", setOf("message-tags"), emptyMap()),
                    client = testClient(),
                )
            val vm = viewModel(channel, manager, history, messages)
            vm.state.first { it.buffer != null }
            val pending =
                message(
                    bufferId = channel.id,
                    text = "pending parent",
                    msgid = null,
                    sender = "me",
                    id = 42,
                )

            vm.react(pending, "👍")
            advanceUntilIdle()

            assertEquals(listOf(channel.id), history.pendingReconciledBuffers)
            assertTrue(history.reconciledBuffers.isEmpty())
            assertEquals(listOf(SentReaction(channel.id, "server-parent", "👍")), manager.reactions)
        }

    @Test
    fun `reaction allows urgent history to finish a serialized wire wait`() =
        runTest {
            val messages = FakeMessageRepository()
            val history =
                FakeHistoryResyncController { attempt ->
                    if (attempt == 1) {
                        delay(35_000)
                        messages.msgid.value = "delayed-server-parent"
                    }
                }
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", setOf("message-tags"), emptyMap()),
                    client = testClient(),
                )
            val vm = viewModel(channel, manager, history, messages)
            vm.state.first { it.buffer != null }

            vm.react(
                message(
                    bufferId = channel.id,
                    text = "pending behind history",
                    msgid = null,
                    sender = "me",
                    id = 44,
                ),
                "👍",
            )
            advanceUntilIdle()

            assertEquals(listOf(channel.id), history.pendingReconciledBuffers)
            assertEquals(
                listOf(SentReaction(channel.id, "delayed-server-parent", "👍")),
                manager.reactions,
            )
        }

    @Test
    fun `reaction uses fast msgid without waiting for slow history`() =
        runTest {
            val messages =
                FakeMessageRepository().apply {
                    msgid.value = "fast-server-parent"
                }
            val history =
                FakeHistoryResyncController {
                    awaitCancellation()
                }
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", setOf("message-tags"), emptyMap()),
                    client = testClient(),
                )
            val vm = viewModel(channel, manager, history, messages)
            vm.state.first { it.buffer != null }

            vm.react(
                message(
                    bufferId = channel.id,
                    text = "fast parent",
                    msgid = null,
                    sender = "me",
                    id = 43,
                ),
                "👍",
            )
            advanceUntilIdle()

            assertEquals(
                listOf(SentReaction(channel.id, "fast-server-parent", "👍")),
                manager.reactions,
            )
        }

    @Test
    fun `reaction failures enqueue typed replay-safe events`() =
        runTest {
            val blocked = viewModel(channel, FakeConnectionManager(network.id))
            blocked.state.first { it.buffer != null }
            blocked.react(message(channel.id, "confirmed", "m1", "alice"), "👍")
            advanceUntilIdle()
            assertEquals(
                ChatUiEvent.ReactionBlocked,
                blocked.uiEvents.value
                    .single()
                    .value,
            )

            val unconfirmed =
                viewModel(
                    channel,
                    FakeConnectionManager(
                        networkId = network.id,
                        state = IrcClientState.Ready("me", setOf("message-tags"), emptyMap()),
                    ),
                )
            unconfirmed.state.first { it.buffer != null }
            unconfirmed.react(message(channel.id, "pending", null, "me", id = 91), "👍")
            advanceUntilIdle()
            assertEquals(
                ChatUiEvent.ReactionTargetUnavailable,
                unconfirmed.uiEvents.value
                    .single()
                    .value,
            )

            val sendFailure =
                viewModel(
                    channel,
                    FakeConnectionManager(
                        networkId = network.id,
                        state = IrcClientState.Ready("me", setOf("message-tags"), emptyMap()),
                        reactionError = true,
                    ),
                )
            sendFailure.state.first { it.buffer != null }
            sendFailure.react(message(channel.id, "confirmed", "m2", "alice"), "👍")
            advanceUntilIdle()
            assertEquals(
                ChatUiEvent.ReactionSendFailed,
                sendFailure.uiEvents.value
                    .single()
                    .value,
            )
        }

    @Test
    fun `redaction sends canonical target and reports rejected writes`() =
        runTest {
            val acceptedManager = FakeConnectionManager(network.id, redactionAccepted = true)
            val accepted = viewModel(channel, acceptedManager)
            accepted.state.first { it.buffer != null }
            accepted.redact(message(channel.id, "delete me", "m1", "alice"))
            advanceUntilIdle()
            assertEquals(listOf(channel.id to "m1"), acceptedManager.redactions)

            val rejected = viewModel(channel, FakeConnectionManager(network.id))
            rejected.state.first { it.buffer != null }
            rejected.redact(message(channel.id, "keep me", "m2", "alice"))
            advanceUntilIdle()
            assertEquals(
                ChatUiEvent.RedactionSendFailed,
                rejected.uiEvents.value
                    .single()
                    .value,
            )
        }

    @Test
    fun `retry preserves failed row when no replacement is accepted`() =
        runTest {
            val messages = FakeMessageRepository()
            val manager = FakeConnectionManager(network.id, retryAccepted = false)
            val vm = viewModel(channel, manager, messages = messages)
            vm.state.first { it.buffer != null }
            val failed = message(channel.id, "try again", null, "me", id = 77).copy(failed = true)

            vm.retry(failed)
            advanceUntilIdle()

            assertTrue(messages.deletedIds.isEmpty())
            assertTrue(manager.messages.isEmpty())
            assertEquals(
                ChatUiEvent.SendRejected,
                vm.uiEvents.value
                    .single()
                    .value,
            )
        }

    @Test
    fun `reply jump failure queues exact retry and retry reissues opaque msgid`() =
        runTest {
            val messages = FakeMessageRepository()
            val vm = viewModel(channel, FakeConnectionManager(network.id), messages = messages)
            vm.state.first { it.buffer != null }
            vm.onInitialPositionHandled()
            val exact = "MiXeD/opaque=Reply"

            vm.jumpToRepliedMessage(exact)
            advanceUntilIdle()

            val queued = vm.uiEvents.value.single()
            val failure = queued.value as ChatUiEvent.ReplyJumpUnavailable
            assertEquals(exact, failure.request.msgid)
            vm.acknowledgeUiEvent(queued.id)
            assertTrue(vm.uiEvents.value.isEmpty())

            messages.resolvedByMsgid = message(channel.id, "parent", exact, "alice", id = 90)
            vm.retryReplyJump(failure.request)
            advanceUntilIdle()

            assertEquals(listOf(exact, exact), messages.requestedMsgids)
            assertEquals(exact, vm.jumpTarget.value?.expectedMsgid)
            assertEquals(90L, vm.jumpTarget.value?.expectedEventId)
        }

    @Test
    fun `newer reply jump supersedes older target and ignores stale acknowledgment`() =
        runTest {
            val messages = FakeMessageRepository()
            val vm = viewModel(channel, FakeConnectionManager(network.id), messages = messages)
            vm.state.first { it.buffer != null }
            vm.onInitialPositionHandled()
            messages.resolvedByMsgid = message(channel.id, "first", "first", "alice", id = 90)

            vm.jumpToRepliedMessage("first")
            advanceUntilIdle()
            val first = vm.jumpTarget.value!!

            messages.resolvedByMsgid = message(channel.id, "second", "second", "alice", id = 91)
            vm.jumpToRepliedMessage("second")
            advanceUntilIdle()
            val second = vm.jumpTarget.value!!

            assertTrue(second.requestToken > first.requestToken)
            assertEquals("second", second.expectedMsgid)
            vm.onJumpHandled(first.requestToken)
            assertEquals(second, vm.jumpTarget.value)
            vm.onJumpHandled(second.requestToken)
            assertNull(vm.jumpTarget.value)
        }

    @Test
    fun `rapid reply taps cancel an in-flight older resolve`() =
        runTest {
            val messages = FakeMessageRepository().apply { blockedMsgid = "slow" }
            val vm = viewModel(channel, FakeConnectionManager(network.id), messages = messages)
            vm.state.first { it.buffer != null }
            vm.onInitialPositionHandled()

            vm.jumpToRepliedMessage("slow")
            advanceUntilIdle()
            assertTrue(messages.blockedResolutionStarted.isCompleted)

            messages.resolvedByMsgid = message(channel.id, "newer", "fast", "alice", id = 92)
            vm.jumpToRepliedMessage("fast")
            advanceUntilIdle()

            assertEquals("fast", vm.jumpTarget.value?.expectedMsgid)
            assertTrue(vm.uiEvents.value.isEmpty())
        }

    @Test
    fun `persisted current identity keeps account reaction ownership while disconnected`() =
        runTest {
            db.networkIdentityDao().upsert(NetworkIdentityEntity(network.id, selfNick = "newNick"))
            db.userDao().upsert(
                UserEntity(
                    networkId = network.id,
                    nick = IrcIdentityRules().normalize("newNick"),
                    account = "stable-account",
                ),
            )
            val messages =
                FakeMessageRepository().apply {
                    reactionRows =
                        listOf(
                            ReactionEntity(
                                bufferId = channel.id,
                                targetMsgid = "target",
                                actorKey = "account:stable-account",
                                sender = "oldNick",
                                emoji = "👍",
                                serverTime = 1,
                            ),
                        )
                }
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id, state = IrcClientState.Disconnected),
                    messages = messages,
                )
            vm.setVisibleMsgids(listOf("target"))

            val chips = vm.reactionChips.first { it["target"]?.singleOrNull()?.mine == true }
            assertTrue(chips.getValue("target").single().mine)
        }

    @Test
    fun `social toggles use rules-aware atomic preference mutation`() =
        runTest {
            val settings = FakeSettingsRepository()
            settings.settings.value = Settings(friends = setOf("Nick[", "nick{"))
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    settings = settings,
                )
            vm.state.first { it.buffer != null }

            vm.toggleFool("NICK{")
            advanceUntilIdle()

            val mutation = settings.foolMutations.single()
            assertEquals("NICK{", mutation.nick)
            assertTrue(mutation.enabled)
            assertTrue(mutation.rules.normalize("Nick[") == mutation.rules.normalize("nick{"))
            assertFalse(settings.legacyFoolMutationCalled)
        }

    @Test
    fun `recovered unread gap positions entry at oldest unread while preserving divider boundary`() =
        runTest {
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            message(channel.id, "marker", null, "alice").copy(
                                serverTime = 100,
                                dedupKey = "marker",
                            ),
                        ),
                    ).single()
            val historyIds =
                db.messageDao().insertAll(
                    (1..513).map { ordinal ->
                        message(channel.id, "history-$ordinal", null, "alice").copy(
                            serverTime = 100L + ordinal,
                            dedupKey = "history-$ordinal",
                        )
                    },
                )
            db.messageDao().insertAll(
                (1..3).map { ordinal ->
                    message(channel.id, "live-$ordinal", "live-$ordinal", "alice").copy(
                        serverTime = 1_000L + ordinal,
                        dedupKey = "live-$ordinal",
                    )
                },
            )
            val vm =
                viewModel(
                    channel.copy(
                        localReadAnchorTime = 100,
                        localReadAnchorEventId = markerId,
                    ),
                    FakeConnectionManager(network.id),
                    messages =
                        FakeMessageRepository(
                            events = listOf(checkNotNull(db.messageDao().byCanonicalId(historyIds.first()))),
                            newerCount = 515,
                        ),
                )

            vm.state.first { it.buffer != null }
            val divider = vm.unreadEntrySnapshot.first { it != null }
            val target = checkNotNull(vm.initialTarget.first { it != null })

            assertEquals(101L, divider?.marker?.serverTime)
            assertEquals(historyIds.first() - 1L, divider?.marker?.eventId)
            // Entry lands on the oldest unread row (history-1, with 515 rows newer than it): the first
            // unseen message tops the viewport and the rest of the unread continues below it.
            assertEquals(515, target.index)
            assertEquals(historyIds.first(), target.expectedEventId)
            assertNull(target.expectedMsgid)
            assertFalse(target.fromSavedPosition)
            // The marker target must displace an already-bottom conversation so entry actually scrolls.
            assertTrue(target.forceScrollOnEntry)
            // ChatScreen realizes the top placement from this flag: first unread tops the viewport.
            assertTrue(target.placeAtTop)
        }

    @Test
    fun `frozen divider boundary survives process death and is never re-derived`() =
        runTest {
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            message(channel.id, "marker", null, "alice").copy(
                                serverTime = 100,
                                dedupKey = "marker",
                            ),
                        ),
                    ).single()
            val unreadIds =
                db.messageDao().insertAll(
                    (1..3).map { ordinal ->
                        message(channel.id, "unread-$ordinal", null, "alice").copy(
                            serverTime = 100L + ordinal,
                            dedupKey = "unread-$ordinal",
                        )
                    },
                )
            val entered = channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId)
            // The visit's back-stack entry (and its SavedStateHandle) outlives the process; the
            // ViewModel does not.
            val visit = SavedStateHandle()
            val first = viewModel(entered, FakeConnectionManager(network.id), savedStateHandle = visit)
            first.state.first { it.buffer != null }
            val frozen = checkNotNull(first.unreadEntrySnapshot.first { it != null })
            assertEquals(101L, frozen.marker.serverTime)
            assertEquals(unreadIds.first() - 1L, frozen.marker.eventId)
            // Await the durable flag: the snapshot flow can emit before the persist lands.
            visit.getStateFlow("unread_entry_snapshot_computed", false).first { it }
            assertEquals(101L, visit.get<Long>("unread_entry_snapshot_time"))

            // Process death. The durable marker has meanwhile advanced past every unread row, so a
            // re-derivation would place the divider at what is unread NOW — nowhere — and the user
            // would come back with no idea where they had stopped reading.
            val read = channel.copy(localReadAnchorTime = 103, localReadAnchorEventId = unreadIds.last())
            val restored = viewModel(read, FakeConnectionManager(network.id), savedStateHandle = visit)
            restored.state.first { it.buffer != null }
            advanceUntilIdle()
            assertEquals(frozen, restored.unreadEntrySnapshot.value)

            // A deliberate re-entry pops the destination, so the next visit starts from a fresh handle
            // and freezes again — here, the absence of a boundary, recorded as durable state.
            val reentry = SavedStateHandle()
            val reentered = viewModel(read, FakeConnectionManager(network.id), savedStateHandle = reentry)
            reentered.state.first { it.buffer != null }
            advanceUntilIdle()
            assertNull(reentered.unreadEntrySnapshot.value)
            // The freeze persists off the test scheduler, so await the durable flag rather than
            // reading it straight after advanceUntilIdle: on a loaded machine it has not landed yet.
            reentry.getStateFlow("unread_entry_snapshot_computed", false).first { it }
            assertEquals(0L, reentry.get<Long>("unread_entry_snapshot_time"))

            // ...and that frozen absence survives process death too: messages arriving after entry
            // belong below the divider this visit never had, not above a newly invented one.
            db.messageDao().insertAll(
                listOf(
                    message(channel.id, "after-entry", null, "alice").copy(
                        serverTime = 200,
                        dedupKey = "after-entry",
                    ),
                ),
            )
            val restoredAbsence =
                viewModel(read, FakeConnectionManager(network.id), savedStateHandle = reentry)
            restoredAbsence.state.first { it.buffer != null }
            advanceUntilIdle()
            assertNull(restoredAbsence.unreadEntrySnapshot.value)
        }

    @Test
    fun `cold ready entry anchors immediately from at-rest data`() =
        runTest {
            // Entry does not wait for the network's history catch-up. `historyCatchUpPending` is held
            // across catch-up's whole retry loop, and waiting on it kept the screen's follow/FAB/read
            // machinery disarmed for a documented 42-second dead window — on a room whose read anchor
            // was in Room the entire time. A room with durable content is positioned from that anchor
            // on the frame it opens.
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "marker", "marker", "alice").copy(serverTime = 100)),
                    ).single()
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    client = testClient(),
                    historyPending = setOf(network.id),
                )
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    manager,
                    messages = FakeMessageRepository(),
                )
            vm.state.first { it.buffer != null }

            val entered = checkNotNull(vm.initialTarget.first { it != null })
            assertEquals(100L, entered.serverTime)
            // ...and the wait it used to perform is provably not what published it: catch-up for this
            // network is still pending.
            assertTrue(network.id in manager.connectionActivity.value.historyCatchUpPending)
        }

    @Test
    fun `history catch-up corrects an entry placement still in flight exactly once`() =
        runTest {
            // The correction that replaces the wait. Entry is published from the read anchor
            // immediately; while the screen is still materializing and placing that target, catch-up
            // lands unread history the at-rest resolution could not see, so the anchor is re-resolved
            // ONCE and the position (and the divider frozen with it) follow it.
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "marker", "marker", "alice").copy(serverTime = 100)),
                    ).single()
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    client = testClient(),
                    historyPending = setOf(network.id),
                )
            val messages = FakeMessageRepository()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    manager,
                    messages = messages,
                )
            vm.state.first { it.buffer != null }
            val entered = checkNotNull(vm.initialTarget.first { it != null })
            assertNull("nothing was unread at rest, so no divider was frozen", entered.expectedEventId)
            assertNull(vm.unreadEntrySnapshot.value)

            // Catch-up delivers the unread backlog only now, before the screen has settled the entry.
            val caughtUpId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "caught up", "m101", "alice").copy(serverTime = 101)),
                    ).single()
            db.messageDao().insertAll(
                listOf(message(channel.id, "recent unread", "m900", "alice").copy(serverTime = 900)),
            )
            messages.resolvedById = checkNotNull(db.messageDao().byCanonicalId(caughtUpId))
            manager.finishHistoryCatchUp(network.id)

            val repaired = checkNotNull(vm.initialTarget.first { it?.expectedEventId != null })
            assertEquals(caughtUpId, repaired.expectedEventId)
            assertEquals(101L, repaired.serverTime)
            assertTrue(repaired.placeAtTop)
            // The divider this visit froze as an absence is refined onto the same row the corrected
            // position lands on — one boundary, one anchor — because it has not been shown yet.
            assertEquals(
                caughtUpId - 1L,
                vm.unreadEntrySnapshot.value
                    ?.marker
                    ?.eventId,
            )

            // EXACTLY once: the correction is a single-shot arm, so a later catch-up edge is inert.
            vm.onInitialPositionHandled()
            manager.finishHistoryCatchUp(network.id)
            advanceUntilIdle()
            assertNull(vm.initialTarget.value)
        }

    @Test
    fun `a placed entry and its shown divider are never corrected by late catch-up`() =
        runTest {
            // The other half of the contract, and the one the frozen-boundary invariant depends on.
            // Something WAS unread at rest, so entry resolved a real divider and the screen placed it.
            // Once it has, the position is on screen and the divider has been SHOWN: both are outcomes
            // the reader has already seen, and history arriving afterwards — catch-up or a gap fill
            // across its seam — belongs below that boundary rather than redrawing it somewhere else
            // under them. (A bottom entry that froze NO divider has produced no such outcome; that case
            // stays correctable, pinned by the test below.)
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "marker", "marker", "alice").copy(serverTime = 100)),
                    ).single()
            val unreadId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "at-rest unread", "m150", "alice").copy(serverTime = 150)),
                    ).single()
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    client = testClient(),
                    historyPending = setOf(network.id),
                )
            val messages =
                FakeMessageRepository(
                    events = listOf(checkNotNull(db.messageDao().byCanonicalId(unreadId))),
                )
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    manager,
                    messages = messages,
                )
            vm.state.first { it.buffer != null }
            val entered = checkNotNull(vm.initialTarget.first { it != null })
            assertEquals(unreadId, entered.expectedEventId)
            assertEquals(
                unreadId - 1L,
                vm.unreadEntrySnapshot.value
                    ?.marker
                    ?.eventId,
            )
            vm.onInitialPositionHandled()

            // Catch-up lands an unread row OLDER than the shown divider — the exact input that would
            // have moved the boundary while the placement was still in flight.
            val caughtUpId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "caught up", "m101", "alice").copy(serverTime = 101)),
                    ).single()
            messages.resolvedById = checkNotNull(db.messageDao().byCanonicalId(caughtUpId))
            manager.finishHistoryCatchUp(network.id)
            advanceUntilIdle()

            assertNull("a placed entry is an outcome, not a placement in flight", vm.initialTarget.value)
            assertEquals(
                "the frozen boundary is not re-derived once it has been shown",
                unreadId - 1L,
                vm.unreadEntrySnapshot.value
                    ?.marker
                    ?.eventId,
            )
            assertEquals(EntryPositionState.Settled, vm.entryState.value)
        }

    @Test
    fun `late catch-up still corrects a settled bottom entry that froze no divider`() =
        runTest {
            // The gap between the two contracts above. Nothing was unread at rest, so entry resolved
            // the read-marker fallback at the live bottom and froze the divider as ABSENT; the screen
            // settles that identity-less target in milliseconds, long before catch-up lands the unread
            // backlog. Nothing was SHOWN that a correction could yank — no divider, no chosen row — so
            // the one owed correction survives the settled latch: the republished target and the
            // refined boundary land on the first unread row, exactly as they would have had the screen
            // still been placing.
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "marker", "marker", "alice").copy(serverTime = 100)),
                    ).single()
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    client = testClient(),
                    historyPending = setOf(network.id),
                )
            val messages = FakeMessageRepository()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    manager,
                    messages = messages,
                )
            vm.state.first { it.buffer != null }
            val entered = checkNotNull(vm.initialTarget.first { it != null })
            assertNull(entered.expectedEventId)
            assertNull(vm.unreadEntrySnapshot.value)
            runCurrent()
            // While that correction is live the settled-at-bottom viewport must not mark read: it
            // would acknowledge the arriving backlog before the re-resolve, leaving the correction
            // nothing unread to land on and erasing the divider for the next visit too.
            assertTrue(vm.viewportReadHold.value)
            vm.onInitialPositionHandled()
            assertEquals(EntryPositionState.Settled, vm.entryState.value)

            val caughtUpId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "caught up", "m101", "alice").copy(serverTime = 101)),
                    ).single()
            messages.resolvedById = checkNotNull(db.messageDao().byCanonicalId(caughtUpId))
            manager.finishHistoryCatchUp(network.id)

            val repaired = checkNotNull(vm.initialTarget.first { it?.expectedEventId != null })
            assertEquals(caughtUpId, repaired.expectedEventId)
            assertEquals(101L, repaired.serverTime)
            assertTrue(repaired.placeAtTop)
            // The boundary this visit froze as an absence is refined onto the corrected row.
            assertEquals(
                caughtUpId - 1L,
                vm.unreadEntrySnapshot.value
                    ?.marker
                    ?.eventId,
            )
            // The settled latch never downgrades; the correction republishes through it instead.
            assertEquals(EntryPositionState.Settled, vm.entryState.value)
            // The hold outlives the republish — releasing on publish would let the still-bottom
            // viewport acknowledge the backlog in the frames before the corrected position lands —
            // and is released the moment the screen consumes the target.
            assertTrue(vm.viewportReadHold.value)
            vm.onInitialPositionHandled()
            runCurrent()
            assertFalse(vm.viewportReadHold.value)
        }

    @Test
    fun `the viewport read hold expires with the correction window`() =
        runTest {
            // The bound that keeps the hold from reintroducing the 42-second dead window: a struggling
            // catch-up retires the correction at ENTRY_HISTORY_READY_TIMEOUT_MS, and the hold is
            // released with it — the at-rest bottom entry stands and read advancement re-arms.
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "marker", "marker", "alice").copy(serverTime = 100)),
                    ).single()
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    client = testClient(),
                    historyPending = setOf(network.id),
                )
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    manager,
                    messages = FakeMessageRepository(),
                )
            vm.state.first { it.buffer != null }
            checkNotNull(vm.initialTarget.first { it != null })
            runCurrent()
            assertTrue(vm.viewportReadHold.value)
            vm.onInitialPositionHandled()

            advanceTimeBy(ENTRY_HISTORY_READY_TIMEOUT_MS + 1)
            advanceUntilIdle()

            assertFalse(vm.viewportReadHold.value)
            assertNull(vm.initialTarget.value)
            assertNull(vm.unreadEntrySnapshot.value)
        }

    @Test
    fun `a reader who has moved the viewport is never corrected by late catch-up`() =
        runTest {
            // A drag before the screen finished placing entry: the reader took the viewport over, so the
            // correction stands down even though entry is still Pending. Read-marker correctness
            // converges through ordinary marker advancement instead of by yanking them somewhere they
            // did not ask to be.
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "marker", "marker", "alice").copy(serverTime = 100)),
                    ).single()
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    client = testClient(),
                    historyPending = setOf(network.id),
                )
            val messages = FakeMessageRepository()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    manager,
                    messages = messages,
                )
            vm.state.first { it.buffer != null }
            val entered = checkNotNull(vm.initialTarget.first { it != null })
            vm.onTimelineInteraction()

            val caughtUpId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "caught up", "m101", "alice").copy(serverTime = 101)),
                    ).single()
            messages.resolvedById = checkNotNull(db.messageDao().byCanonicalId(caughtUpId))
            manager.finishHistoryCatchUp(network.id)
            advanceUntilIdle()

            assertEquals("a viewport the reader is driving is not entry's to move", entered, vm.initialTarget.value)
            assertEquals(EntryPositionState.Pending, vm.entryState.value)
            assertNull(vm.unreadEntrySnapshot.value)
        }

    @Test
    fun `at-rest entry stands when history catch-up overruns its window`() =
        runTest {
            // The catch-up gate holds `historyCatchUpPending` across its WHOLE retry loop (exponential
            // backoff up to 30s per attempt). Entry never waits on that, and the correction it arms is
            // bounded by the same window: once the bound expires the at-rest position is final, which is
            // exactly how an offline network already enters.
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "marker", "marker", "alice").copy(serverTime = 100)),
                    ).single()
            val unreadId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "local unread", "m200", "alice").copy(serverTime = 200)),
                    ).single()
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    client = testClient(),
                    historyPending = setOf(network.id),
                )
            val messages = FakeMessageRepository()
            messages.resolvedById = checkNotNull(db.messageDao().byCanonicalId(unreadId))
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    manager,
                    messages = messages,
                )
            vm.state.first { it.buffer != null }

            // The locally known first unread row, without waiting for anything.
            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertEquals(unreadId, target.expectedEventId)
            assertEquals(200L, target.serverTime)

            // The correction's bound expires with catch-up still pending, and entry deliberately stays
            // un-settled and untouched here so nothing but that bound can be what retires it.
            advanceTimeBy(ENTRY_HISTORY_READY_TIMEOUT_MS + 1)
            advanceUntilIdle()
            assertEquals(target, vm.initialTarget.value)

            // Catch-up completing after the bound lands an OLDER unread row — the exact input that
            // would have moved the anchor inside the window — and must now change nothing.
            val lateId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "caught up late", "m150", "alice").copy(serverTime = 150)),
                    ).single()
            messages.resolvedById = checkNotNull(db.messageDao().byCanonicalId(lateId))
            manager.finishHistoryCatchUp(network.id)
            advanceUntilIdle()
            assertEquals(target, vm.initialTarget.value)
        }

    @Test
    fun `an empty room still waits for history catch-up before entry`() =
        runTest {
            // The only remaining wait, and the only room it is right for: no stored anchor, no saved
            // viewport, not one retained row. There is genuinely nothing to position in, and the reader
            // is looking at the empty-timeline spinner either way.
            val manager =
                FakeConnectionManager(
                    networkId = network.id,
                    state = IrcClientState.Ready("me", emptySet(), emptyMap()),
                    client = testClient(),
                    historyPending = setOf(network.id),
                )
            val vm = viewModel(channel, manager, messages = FakeMessageRepository())
            vm.state.first { it.buffer != null }
            runCurrent()

            assertNull(vm.initialTarget.value)

            db.messageDao().insertAll(
                listOf(message(channel.id, "first", "m1", "alice").copy(serverTime = 100)),
            )
            manager.finishHistoryCatchUp(network.id)

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertEquals(0, target.index)
        }

    @Test
    fun `synthetic mute floor fallback uses positional entry without impossible identity`() =
        runTest {
            val messages = FakeMessageRepository(newerCount = 1)
            val vm =
                viewModel(
                    channel.copy(localUnreadFloorTime = 100),
                    FakeConnectionManager(network.id),
                    messages = messages,
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertNull(target.expectedEventId)
            assertNull(target.expectedMsgid)
            assertEquals(100L, target.serverTime)
            assertTrue(target.placeAtTop)
            assertEquals(0, materializableTargetIndex(target.index, 1, hasExactIdentity = false))
        }

    @Test
    fun `hidden marker fallback uses positional entry without impossible identity`() =
        runTest {
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            message(channel.id, "hidden marker", "marker", "alice").copy(
                                serverTime = 100,
                                dedupKey = "marker",
                            ),
                        ),
                    ).single()
            val settings =
                FakeSettingsRepository().apply {
                    this.settings.value =
                        Settings(
                            fools = setOf("alice"),
                            foolsMode = FoolsMode.HIDE,
                        )
                }
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    FakeConnectionManager(network.id),
                    messages = FakeMessageRepository(newerCount = 1),
                    settings = settings,
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertNull(target.expectedEventId)
            assertNull(target.expectedMsgid)
            assertEquals(100L, target.serverTime)
            assertEquals(0, materializableTargetIndex(target.index, 1, hasExactIdentity = false))
        }

    @Test
    fun `initial re-resolution preserves first unread top placement`() =
        runTest {
            val id =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            message(channel.id, "target", "target", "alice").copy(
                                serverTime = 200,
                                dedupKey = "target",
                            ),
                        ),
                    ).single()
            val targetRow = checkNotNull(db.messageDao().byCanonicalId(id))
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    messages = FakeMessageRepository(events = listOf(targetRow)),
                )
            vm.state.first { it.buffer != null }

            vm
                .reresolveInitialOnce(
                    ChatPositionTarget(
                        index = 99,
                        expectedEventId = id,
                        expectedMsgid = "target",
                        serverTime = 200,
                        forceScrollOnEntry = true,
                        placeAtTop = true,
                    ),
                ).join()

            val repaired = checkNotNull(vm.initialTarget.value)
            assertTrue(repaired.forceScrollOnEntry)
            assertTrue(repaired.placeAtTop)
        }

    @Test
    fun `ordinary unresolved entry remains read gated without a message error`() =
        runTest {
            val vm = viewModel(channel, FakeConnectionManager(network.id))
            vm.state.first { it.buffer != null }

            vm.onInitialPositionUnresolved()

            assertEquals(
                EntryPositionState.Unresolved(messageUnavailable = false),
                vm.entryState.value,
            )
        }

    @Test
    fun `settled entry never downgrades to unresolved`() =
        runTest {
            val vm = viewModel(channel, FakeConnectionManager(network.id))
            vm.state.first { it.buffer != null }

            vm.onInitialPositionHandled()
            assertEquals(EntryPositionState.Settled, vm.entryState.value)

            // A late unresolved signal (ordinary or explicit-jump failure) must not clear the gate open.
            vm.onInitialPositionUnresolved()
            assertEquals(EntryPositionState.Settled, vm.entryState.value)
        }

    @Test
    fun `reply jump failure after concurrent entry settlement still reports unavailable`() =
        runTest {
            val vm = viewModel(channel, FakeConnectionManager(network.id))
            vm.state.first { it.buffer != null }

            // The tap lands while entry is Pending, so the jump would settle entry; entry then settles
            // on its own before the resolve completes NotFound.
            vm.jumpToRepliedMessage("missing")
            vm.onInitialPositionHandled()
            advanceUntilIdle()

            assertEquals(EntryPositionState.Settled, vm.entryState.value)
            val failure =
                vm.uiEvents.value
                    .single()
                    .value as ChatUiEvent.ReplyJumpUnavailable
            assertEquals("missing", failure.request.msgid)
        }

    @Test
    fun `message-unavailable failure never degrades to an ordinary unresolved entry`() =
        runTest {
            val handle = SavedStateHandle()
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    jumpToMsgid = "missing-message",
                    savedStateHandle = handle,
                )
            advanceUntilIdle()
            assertEquals(
                EntryPositionState.Unresolved(messageUnavailable = true),
                vm.entryState.value,
            )

            // A later ordinary unresolved signal must not clear the durable message-unavailable report;
            // live state stays consistent with the persisted SavedState keys.
            vm.onInitialPositionUnresolved()
            assertEquals(
                EntryPositionState.Unresolved(messageUnavailable = true),
                vm.entryState.value,
            )
            assertTrue(handle.get<Boolean>("entry_position_unresolved") == true)
            assertTrue(handle.get<Boolean>("entry_message_unavailable") == true)
            assertFalse(handle.get<Boolean>("entry_position_settled") == true)
        }

    @Test
    fun `entry state restores from persisted SavedState keys after process death`() =
        runTest {
            val settled =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    restoredState = mapOf("entry_position_settled" to true),
                )
            assertEquals(EntryPositionState.Settled, settled.entryState.value)

            val unavailable =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    restoredState =
                        mapOf(
                            "entry_position_unresolved" to true,
                            "entry_message_unavailable" to true,
                        ),
                )
            assertEquals(
                EntryPositionState.Unresolved(messageUnavailable = true),
                unavailable.entryState.value,
            )

            // Settled wins even when an unresolved flag is also persisted (no downgrade on restore).
            val both =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    restoredState =
                        mapOf(
                            "entry_position_settled" to true,
                            "entry_position_unresolved" to true,
                        ),
                )
            assertEquals(EntryPositionState.Settled, both.entryState.value)
        }

    @Test
    fun `mention FAB re-resolves its exact row against the live timeline`() =
        runTest {
            val mention =
                message(channel.id, "hello me", "mention", "alice", id = 42).copy(
                    serverTime = 500,
                    timelineOrder = 42,
                )
            val messages = FakeMessageRepository(events = listOf(mention), newerCount = 3)
            val vm =
                viewModel(
                    channel.copy(localUnreadFloorTime = 100),
                    FakeConnectionManager(network.id),
                    messages = messages,
                )
            vm.state.first { it.buffer != null }
            vm.initialTarget.first { it != null }

            vm.focusRecentMention(
                ChatPositionTarget(
                    index = 17,
                    expectedEventId = mention.id,
                    expectedMsgid = mention.msgid,
                    serverTime = mention.serverTime,
                ),
            )
            advanceUntilIdle()

            val target = checkNotNull(vm.jumpTarget.value)
            assertEquals(3, target.index)
            assertEquals(mention.id, target.expectedEventId)
            assertEquals(mention.msgid, target.expectedMsgid)
        }

    @Test
    fun `a deep jump publishes a global index and the newest escape abandons it`() =
        runTest {
            // The deep jump lands in the ONE unbounded timeline: its index is the repository's global
            // count of strictly-newer rows, and no narrower generation is created around it. That is
            // what keeps index 0 of the presented list the room's newest row, which the viewport
            // mark-read gate now depends on entirely.
            val mention =
                message(channel.id, "hello me", "mention", "alice", id = 42).copy(
                    serverTime = 500,
                    timelineOrder = 42,
                )
            val messages = FakeMessageRepository(events = listOf(mention), newerCount = 17)
            val vm =
                viewModel(
                    channel.copy(localUnreadFloorTime = 100),
                    FakeConnectionManager(network.id),
                    messages = messages,
                    jumpToTime = mention.serverTime,
                    jumpToEventId = mention.id,
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.jumpTarget.first { it != null })
            assertEquals(17, target.index)
            assertEquals(mention.id, target.expectedEventId)
            assertEquals(EntryPositionState.Pending, vm.entryState.value)

            // The newest FAB abandons the pending jump outright and releases the read gate, so the
            // screen's own scroll-to-newest is not fought by a one-shot positioning operation.
            vm.jumpToNewest()
            runCurrent()

            assertNull(vm.jumpTarget.value)
            assertNull(vm.initialTarget.value)
            assertEquals(EntryPositionState.Settled, vm.entryState.value)
        }

    @Test
    fun `missing entry message jump reports the unavailable target`() =
        runTest {
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    jumpToMsgid = "missing-message",
                )

            advanceUntilIdle()

            assertEquals(
                EntryPositionState.Unresolved(messageUnavailable = true),
                vm.entryState.value,
            )
        }

    @Test
    fun `coalesced saved viewport follows canonical event and retains pixel offset`() =
        runTest {
            val winnerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "history", "server-id", "alice").copy(serverTime = 500)),
                    ).single()
            val loserId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "live", null, "alice").copy(serverTime = 200)),
                    ).single()
            val positions =
                ChatScrollPositionStore().apply {
                    put(
                        channel.id,
                        ChatScrollPosition(
                            index = 1,
                            offset = 37,
                            msgid = null,
                            serverTime = 200,
                            rowId = loserId,
                        ),
                    )
                }
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    scrollPositions = positions,
                )
            vm.state.first { it.buffer != null }
            val restored = vm.initialTarget.first { it != null }
            assertEquals(1, restored?.index)
            assertEquals(37, restored?.offset)
            assertEquals(loserId, restored?.expectedEventId)
            assertTrue(restored?.fromSavedPosition == true)
            vm.onInitialPositionHandled()
            assertEquals(null, vm.initialTarget.value)

            db.canonicalTimelineDao().upsertEventRedirect(EventRedirectEntity(loserId, winnerId))
            db.messageDao().deleteById(loserId)

            val redirected = vm.initialTarget.first { it != null }
            assertEquals(0, redirected?.index)
            assertEquals(37, redirected?.offset)
            assertEquals(winnerId, redirected?.expectedEventId)
            assertTrue(redirected?.fromSavedPosition == true)
            assertEquals(winnerId, positions.get(channel.id)?.rowId)
            assertEquals(500L, positions.get(channel.id)?.serverTime)
            assertEquals(37, positions.get(channel.id)?.offset)
        }

    @Test
    fun `leaving at the bottom still enters at the divider when unread arrived while away`() =
        runTest {
            // The most common flow there is: read to the bottom, leave, messages arrive, tap the room
            // again. The park says where the reader STOPPED, which is a different question from what
            // they have not seen, so it cannot outrank the divider: entering at the newest row buries
            // the "N new messages" divider somewhere above the viewport, and the reader has to scroll
            // BACKWARDS past the new messages to find out what they missed.
            //
            // Same shape as the divider-entry case above, which resolves the unread anchor to index
            // 515 — so this asserts the divider survives a park, not merely that both agree.
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "marker", null, "alice").copy(serverTime = 100, dedupKey = "marker")),
                    ).single()
            val historyIds =
                db.messageDao().insertAll(
                    (1..513).map { ordinal ->
                        message(channel.id, "history-$ordinal", null, "alice").copy(
                            serverTime = 100L + ordinal,
                            dedupKey = "history-$ordinal",
                        )
                    },
                )
            db.messageDao().insertAll(
                (1..3).map { ordinal ->
                    message(channel.id, "live-$ordinal", "live-$ordinal", "alice").copy(
                        serverTime = 1_000L + ordinal,
                        dedupKey = "live-$ordinal",
                    )
                },
            )
            // The reader was following the conversation when they navigated away, which clears the
            // saved viewport and records the park.
            val positions = ChatScrollPositionStore().apply { markParkedAtBottom(channel.id) }

            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    FakeConnectionManager(network.id),
                    messages =
                        FakeMessageRepository(
                            events = listOf(checkNotNull(db.messageDao().byCanonicalId(historyIds.first()))),
                            newerCount = 515,
                        ),
                    scrollPositions = positions,
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            // The oldest unread row, with the frozen divider drawn above it, at the top of the window.
            assertEquals(515, target.index)
            assertEquals(historyIds.first(), target.expectedEventId)
            assertTrue(target.placeAtTop)
            assertTrue(target.forceScrollOnEntry)
            assertFalse(target.fromSavedPosition)
            // The entry target and the frozen divider name the same row: the divider is drawn on the
            // first row NEWER than its marker, and that marker is this row minus one.
            val divider = checkNotNull(vm.unreadEntrySnapshot.first { it != null })
            assertEquals(historyIds.first() - 1L, divider.marker.eventId)
        }

    @Test
    fun `leaving at the bottom returns to the bottom when nothing is unread`() =
        runTest {
            // The other half of the rule, and the whole reason the park is recorded at all: a follower
            // who left a caught-up room comes back to the newest row rather than to the read marker.
            // Absence of a saved viewport cannot carry that on its own — it is also what a room nobody
            // has opened looks like, and that one falls back to the marker.
            val ids = seedFiveRows()
            val positions = ChatScrollPositionStore().apply { markParkedAtBottom(channel.id) }

            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 500, localReadAnchorEventId = ids.last()),
                    FakeConnectionManager(network.id),
                    messages = realCounts(),
                    scrollPositions = positions,
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertEquals(0, target.index)
            assertFalse("the newest row is a position, not a divider placement", target.placeAtTop)
            assertNull(vm.unreadEntrySnapshot.value)
        }

    @Test
    fun `a never-read room with stored rows enters at the divider its badge promises`() =
        runTest {
            // The chat-list cue COALESCEs a missing read anchor to time zero and counts every visible
            // row, so a room that has never been read shows "N new messages" — while entry used to
            // skip unread resolution entirely without a marker and silently park at the bottom with no
            // divider. The epoch anchor closes that split: first open of a room someone else has
            // written to lands at the oldest unread row like any other unread entry.
            val ids =
                db.messageDao().insertAll(
                    (1..3).map { ordinal ->
                        message(channel.id, "unread-$ordinal", "m$ordinal", "alice").copy(
                            serverTime = 100L * ordinal,
                            dedupKey = "unread-$ordinal",
                        )
                    },
                )
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    messages =
                        FakeMessageRepository(
                            events = listOf(checkNotNull(db.messageDao().byCanonicalId(ids.first()))),
                            counts = MessageVisibilityReader(db),
                        ),
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertEquals(2, target.index)
            assertEquals(ids.first(), target.expectedEventId)
            assertEquals(100L, target.serverTime)
            assertTrue(target.placeAtTop)
            val divider = checkNotNull(vm.unreadEntrySnapshot.first { it != null })
            assertEquals(ids.first() - 1L, divider.marker.eventId)
        }

    @Test
    fun `an unread incoming file offer anchors the divider like any chat row`() =
        runTest {
            // The chat-list cue counts payload-bearing DCC_TRANSFER rows, so a parked room whose only
            // unread is a file offer shows a badge — and the unread anchor must resolve the same row,
            // or the follower enters at the bottom with no divider under a badge that promised one.
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "marker", "marker", "alice").copy(serverTime = 100)),
                    ).single()
            val offerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(
                            message(channel.id, "offer", "offer-1", "alice").copy(
                                serverTime = 200,
                                kind = MessageKind.DCC_TRANSFER,
                                eventPayload = "payload-v1",
                            ),
                        ),
                    ).single()
            val positions = ChatScrollPositionStore().apply { markParkedAtBottom(channel.id) }
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    FakeConnectionManager(network.id),
                    messages =
                        FakeMessageRepository(
                            events = listOf(checkNotNull(db.messageDao().byCanonicalId(offerId))),
                            counts = MessageVisibilityReader(db),
                        ),
                    scrollPositions = positions,
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertEquals(offerId, target.expectedEventId)
            assertTrue(target.placeAtTop)
            val divider = checkNotNull(vm.unreadEntrySnapshot.first { it != null })
            assertEquals(offerId - 1L, divider.marker.eventId)
        }

    @Test
    fun `sending abandons the entry position and its post-catch-up correction`() =
        runTest {
            // A send is an explicit trip to the live bottom (the screen scrolls there as part of the
            // same gesture). The screen keeps its entire auto-follow machinery disarmed until entry
            // settles, so a message sent while entry is still being placed echoed into a timeline that
            // did not follow it: the viewport stayed keyed to the previous newest row. The send must
            // therefore abandon the one-shot positioning exactly as the newest FAB does — and, on a
            // room entered mid-catch-up, its armed correction with it.
            val markerId =
                db
                    .messageDao()
                    .insertAll(
                        listOf(message(channel.id, "marker", null, "alice").copy(serverTime = 100, dedupKey = "marker")),
                    ).single()
            db.messageDao().insertAll(
                (1..5).map { ordinal ->
                    message(channel.id, "unread-$ordinal", "unread-$ordinal", "alice").copy(
                        serverTime = 1_000L + ordinal,
                        dedupKey = "unread-$ordinal",
                    )
                },
            )
            val manager = FakeConnectionManager(network.id, historyPending = setOf(network.id))
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId),
                    manager,
                )
            vm.state.first { it.buffer != null }
            // Entry is published from at-rest data even though catch-up is still running.
            checkNotNull(vm.initialTarget.first { it != null })
            assertEquals(EntryPositionState.Pending, vm.entryState.value)

            vm.saveDraft("hello there")
            vm.submit("hello there", {}, {}).join()

            // The author is at the live bottom now; entry settles so the follow machinery arms and
            // the echoed row is classified as a live arrival instead of landing above a dead gate.
            assertEquals(EntryPositionState.Settled, vm.entryState.value)
            assertNull(vm.initialTarget.value)
            assertEquals(listOf(SentMessage(channel.id, "hello there", null)), manager.messages)

            // Catch-up delivering older unread afterwards must not fire the correction and yank the
            // viewport away from the message that was just sent.
            db.messageDao().insertAll(
                listOf(message(channel.id, "caught up", "m101", "alice").copy(serverTime = 101)),
            )
            manager.finishHistoryCatchUp(network.id)
            advanceUntilIdle()
            assertNull(vm.initialTarget.value)
        }

    /** Five rows, oldest first, at serverTime 100..500. Index 0 is the newest. */
    private suspend fun seedFiveRows(): List<Long> =
        db.messageDao().insertAll(
            (1..5).map { ordinal ->
                message(channel.id, "row$ordinal", "m$ordinal", "alice").copy(
                    serverTime = 100L * ordinal,
                    dedupKey = "row$ordinal",
                )
            },
        )

    /**
     * A parked viewport, and optionally the deepest row this process displayed in the room.
     * [displayed] is `(rowId, serverTime)`, the same shape the screen reports after a measure pass.
     */
    private fun savedAt(
        rowId: Long,
        msgid: String,
        serverTime: Long,
        offset: Int = 0,
        displayed: Pair<Long, Long>? = null,
    ) = ChatScrollPositionStore().apply {
        put(
            channel.id,
            ChatScrollPosition(
                index = 0,
                offset = offset,
                msgid = msgid,
                serverTime = serverTime,
                rowId = rowId,
            ),
        )
        displayed?.let { (id, time) ->
            recordFurthestDisplayed(channel.id, TimelineAnchor(time, id, id))
        }
    }

    /** Counts every entry index from the one real database, the way the shipped repository does. */
    private fun realCounts() = FakeMessageRepository(counts = MessageVisibilityReader(db))

    @Test
    fun `a fully read room reopens at the saved viewport rather than the read marker`() =
        runTest {
            // The reported defect, at the level that decides it. Enter, scroll up, back out, re-enter:
            // the room is fully read, so it has no unread anchor and the saved viewport is the ONLY
            // statement of where the reader was. Entry used to divert to the read marker whenever the
            // room had a read anchor at all — which is every room anyone has ever opened — so the saved
            // position was resolved only for rooms that had never been read, and the restore silently
            // never happened.
            val ids = seedFiveRows()
            val parked = ids.first()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 500, localReadAnchorEventId = ids.last()),
                    FakeConnectionManager(network.id),
                    scrollPositions = savedAt(parked, "m1", serverTime = 100, offset = 12),
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertTrue("a fully-read room must reopen where the reader parked", target.fromSavedPosition)
            assertEquals(4, target.index)
            assertEquals(12, target.offset)
            assertEquals(parked, target.expectedEventId)
        }

    @Test
    fun `a viewport parked deeper than the unread boundary survives new messages`() =
        runTest {
            // Unread arrived while the reader was away, but they had parked FURTHER back in history
            // than where the unread starts. Entering at the unread row would drag them forward, out of
            // the history they were reading, and past nothing they had not already chosen to skip: the
            // unread run stays below the restored viewport, in their forward scroll direction.
            val ids = seedFiveRows()
            val parked = ids.first()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 400, localReadAnchorEventId = ids[3]),
                    FakeConnectionManager(network.id),
                    // The unread boundary is the newest row (index 0); the parked viewport is four deep.
                    // Counted from the same database as the saved viewport, so the comparison the rule makes
                    // is between two indices in one domain rather than against a constant.
                    messages = realCounts(),
                    scrollPositions = savedAt(parked, "m1", serverTime = 100),
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertTrue("the deeper of the two anchors wins", target.fromSavedPosition)
            assertEquals(4, target.index)
            assertEquals(parked, target.expectedEventId)
        }

    @Test
    fun `unread older than the saved viewport still opens at the first unread row`() =
        runTest {
            // The other side of the same rule, and the one the required E2E reopen depends on: a
            // backfill landed unread history OLDER than where the reader parked. Restoring the parked
            // viewport would leave that unread run above them, unseen and unreachable without scrolling
            // backwards, so the first unread row keeps the entry and its top placement.
            val ids = seedFiveRows()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = ids.first()),
                    FakeConnectionManager(network.id),
                    // The unread boundary is three rows deep; the parked viewport is one. Both counted from
                    // the one database, so this pins depths rather than a constant against a real index.
                    messages = realCounts(),
                    scrollPositions = savedAt(ids[3], "m4", serverTime = 400),
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertFalse("unread deeper than the park must not be skipped past", target.fromSavedPosition)
            assertEquals(3, target.index)
            assertTrue(target.placeAtTop)
        }

    @Test
    fun `a reader working forward through unread reopens where they got to`() =
        runTest {
            // The case the deeper-of rule cannot reach on its own. The reader ENTERED at the unread
            // divider three rows back, read forward, and left one row from the bottom. The read marker
            // did not move — advancing it needs the effective bottom (shouldMarkReadFromViewport) — so
            // the first unread row is still the divider they started from, and depth alone would send
            // them straight back to it on every reopen until they once reached the bottom.
            //
            // This is the SAME shape as the test above: park newer than first-unread. What separates
            // them is the watermark, which says the reader already had that divider row on screen.
            val ids = seedFiveRows()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = ids.first()),
                    FakeConnectionManager(network.id),
                    messages = realCounts(),
                    scrollPositions =
                        savedAt(
                            ids[3],
                            "m4",
                            serverTime = 400,
                            // Entered at the divider (index 3) and worked forward to index 1.
                            displayed = ids[1] to 200L,
                        ),
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertTrue("200 rows of reading must not be reset", target.fromSavedPosition)
            assertEquals(1, target.index)
            assertEquals(ids[3], target.expectedEventId)
        }

    @Test
    fun `unread the reader has never displayed still wins over the park`() =
        runTest {
            // The required E2E reopen, in miniature, and the boundary of the watermark rule: the unread
            // run reaches ONE row deeper than anything that has been on screen, so it is genuinely
            // unseen history above the viewport and must keep the entry and its top placement. The park
            // and the read marker are identical to the test above; only the watermark differs.
            val ids = seedFiveRows()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = ids.first()),
                    FakeConnectionManager(network.id),
                    messages = realCounts(),
                    scrollPositions =
                        savedAt(
                            ids[3],
                            "m4",
                            serverTime = 400,
                            // Displayed down to index 2; the first unread row sits at 3.
                            displayed = ids[2] to 300L,
                        ),
                )
            vm.state.first { it.buffer != null }

            val target = checkNotNull(vm.initialTarget.first { it != null })
            assertFalse("unseen unread history must not be stranded above the viewport", target.fromSavedPosition)
            assertEquals(3, target.index)
            assertTrue(target.placeAtTop)
        }

    @Test
    fun `the Pager is keyed at the anchor entry actually lands on`() =
        runTest {
            // The key and the target must name the same row. A forward reader's target is the park, so
            // keying the deeper unread anchor instead would rebuild the generation around a row entry
            // never scrolls to and push the park back out into the placeholder scroll the key exists to
            // avoid. 200 rows, so both candidates sit beyond the default newest load.
            val ids =
                db.messageDao().insertAll(
                    (1..200).map { ordinal ->
                        message(channel.id, "row$ordinal", "m$ordinal", "alice").copy(
                            serverTime = ordinal.toLong(),
                            dedupKey = "row$ordinal",
                        )
                    },
                )
            val messages = realCounts()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 1, localReadAnchorEventId = ids.first()),
                    FakeConnectionManager(network.id),
                    messages = messages,
                    scrollPositions =
                        savedAt(
                            ids[20],
                            "m21",
                            serverTime = 21,
                            // Entered at the first unread row (index 198) and read forward to the park (179).
                            displayed = ids[1] to 2L,
                        ),
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { } }

            // entryAnchorPagingKey(179) for the park, NOT entryAnchorPagingKey(198) for the divider.
            assertEquals(79, messages.firstInitialKey.await())
            assertEquals(179, vm.initialTarget.first { it != null }?.index)
        }

    @Test
    fun `a saved viewport beyond the newest load keys the Pager at itself`() =
        runTest {
            // Publishing the right target is only half of a restore. A viewport parked deeper than the
            // default newest load (initialLoadSize = 150) opens as an unloaded placeholder unless the
            // Pager is keyed there, and reaching it by scrolling to that placeholder drives a boundary
            // APPEND that churns the generation before the row can compose. The key used to be computed
            // for the unread anchor ONLY, so a deep restore had to be probed for rather than loaded.
            //
            // What the key must be is pinned by RecentPagingAppendReproTest over the real PagingSource;
            // this pins that the ViewModel asks for it at all, for a saved viewport.
            val ids =
                db.messageDao().insertAll(
                    (1..200).map { ordinal ->
                        message(channel.id, "row$ordinal", "m$ordinal", "alice").copy(
                            serverTime = ordinal.toLong(),
                            dedupKey = "row$ordinal",
                        )
                    },
                )
            val messages = FakeMessageRepository()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 200, localReadAnchorEventId = ids.last()),
                    FakeConnectionManager(network.id),
                    messages = messages,
                    // The oldest row: 199 newer rows sit below it.
                    scrollPositions = savedAt(ids.first(), "m1", serverTime = 1),
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { } }

            // entryAnchorPagingKey(199): the anchor shifted back by initialLoadSize - pageSize.
            assertEquals(99, messages.firstInitialKey.await())
        }

    @Test
    fun `a saved viewport inside the newest load leaves the Pager unkeyed`() =
        runTest {
            // The negative control for the key: a shallow restore is already inside the newest-first
            // refresh, so keying it would rebuild the generation around a row Paging was going to load
            // anyway — and would drop the newest rows below it out of the initial window for nothing.
            val ids = seedFiveRows()
            val messages = FakeMessageRepository()
            val vm =
                viewModel(
                    channel.copy(localReadAnchorTime = 500, localReadAnchorEventId = ids.last()),
                    FakeConnectionManager(network.id),
                    messages = messages,
                    scrollPositions = savedAt(ids.first(), "m1", serverTime = 100),
                )
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.messages.collect { } }

            assertNull(messages.firstInitialKey.await())
        }

    // --- the timeline's history rule: demand -> gate -> SeamLoadingRule -> HistoryGapFiller -------
    //
    // Models the store geometry the `unreadHistoryEntersAtMarkerAndRemainsCanonical` E2E journey
    // reaches just before it opens the room: a read marker, the 49-row reconnect catch-up island
    // above it, and the recoverable gap between them, one row below the parked viewport.
    //
    // This wiring had no coverage at any level below that journey. Everything DOWNSTREAM of
    // `HistoryGapFiller.fillGap` is pinned by `RecentPagingAppendReproTest`, and `SeamLoadingRule`
    // and `seamsWithinPrefetch` are pinned as pure functions by `ChatModelsTest` — but no ViewModel
    // test ever passed a `gapFiller`, so every one of them ran against `NoopHistoryGapFiller` and
    // could not have observed a fill being requested at all.

    /** The one seam a fill can be observed at: records what the timeline's rule decided to load. */
    private class RecordingGapFiller : HistoryGapFiller {
        override val fillsInFlight = MutableStateFlow<Set<Long>>(emptySet())
        val requests = mutableListOf<Pair<Long, Long>>()

        override suspend fun fillGap(
            roomId: Long,
            gapId: Long,
        ): GapFillProgress {
            requests += roomId to gapId
            return GapFillProgress.MOVED
        }
    }

    /** Rows are newest-first on screen, so index 0 is the newest catch-up row. */
    private data class SeamFixture(
        val markerId: Long,
        val oldestCatchUpId: Long,
        val gapId: Long,
    )

    /**
     * 50 already-read rows, 49 catch-up rows above them, and the catch-up gap between the two
     * islands. The gap is hand-inserted rather than driven through `persistHistoryPageResult`
     * because the production writer's shape is already pinned by `ReconnectGapPresentationTest` and
     * `RecentPagingAppendReproTest`; what is under test here starts at the seam the store publishes.
     * Both edges carry an eventId and no msgid, which is what soju's `MSGREFTYPES=timestamp` wire
     * leaves behind (the journal's `boundary_has_msgid=false`).
     */
    private suspend fun seedCatchUpSeam(): SeamFixture {
        val readIds =
            db.messageDao().insertAll(
                (1..50).map { ordinal ->
                    message(channel.id, "read-$ordinal", null, "alice")
                        .copy(serverTime = ordinal.toLong(), dedupKey = "read-$ordinal")
                },
            )
        val catchUpIds =
            db.messageDao().insertAll(
                (212..260).map { ordinal ->
                    message(channel.id, "row$ordinal", null, "alice")
                        .copy(serverTime = ordinal.toLong(), dedupKey = "row$ordinal")
                },
            )
        val gapId =
            db.historyGapDao().insert(
                HistoryGapEntity(
                    roomId = channel.id,
                    olderMsgid = null,
                    olderServerTime = 50,
                    olderEventId = readIds.last(),
                    newerMsgid = null,
                    newerServerTime = 212,
                    newerEventId = catchUpIds.first(),
                    recoverable = true,
                ),
            )
        return SeamFixture(readIds.last(), catchUpIds.first(), gapId)
    }

    /**
     * The window the fixture's viewport is looking at, newest first, exactly as Paging presents it.
     *
     * Loaded straight from the DAO rather than through the Pager: what the seam cases need from it
     * is the IDENTITY of the row at a given index, which is what the rule now measures depth in.
     */
    private suspend fun seamWindow(): List<MessageEntity> =
        (
            db
                .messageDao()
                .pagingSource(
                    messagePagingQuery(channel.id, MessageVisibilitySpec()),
                ).load(
                    PagingSource.LoadParams.Refresh(key = null, loadSize = 200, placeholdersEnabled = false),
                ) as PagingSource.LoadResult.Page<Int, MessageEntity>
        ).data

    /** The room the fixture entered: read up to the marker, so entry parks at the first unread. */
    private fun enteredAtMarker(fixture: SeamFixture) = channel.copy(localReadAnchorTime = 50, localReadAnchorEventId = fixture.markerId)

    /** A real repository, because `FakeMessageRepository` publishes no seams and never could. */
    @OptIn(androidx.paging.ExperimentalPagingApi::class)
    private fun seamRepository() =
        MessageRepositoryImpl(
            bufferDao = db.bufferDao(),
            networkIdentityDao = db.networkIdentityDao(),
            messageDao = db.messageDao(),
            reactionDao = db.reactionDao(),
            mediatorFactory = ChatHistoryMediatorFactory { _, _, _ -> error("paging is not exercised here") },
            historyGapDao = db.historyGapDao(),
        )

    /** A live network whose CHATHISTORY negotiation finished: the gate's `historyReady` input. */
    private fun readyHistoryManager() =
        FakeConnectionManager(
            network.id,
            historyAvailability = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP), 100),
        )

    @Test
    fun `the opened unread viewport reports the catch-up seam`() =
        runTest {
            // The negative control for everything below: with the whole window materialized and no
            // placeholders, the demand FUNCTION cannot abstain at this geometry. If this ever fails the
            // fixture was seeded wrong and the wiring assertions below mean nothing.
            val fixture = seedCatchUpSeam()
            val window = seamWindow()
            val seams = seamRepository().observeTimelineSeams(channel.id, MessageVisibilitySpec()).first()

            assertEquals("both islands are materialized", 99, window.size)
            assertEquals(
                "the seam sits directly above the oldest catch-up row",
                48,
                window.indexOfFirst { it.id == fixture.oldestCatchUpId },
            )
            assertEquals(fixture.gapId, seams.single().gapId)
            assertTrue("the catch-up gap is still fillable", seams.single().recoverable)

            // The journal's own viewport: `after_index=39`, seam one row below the visible end.
            assertEquals(
                setOf(fixture.gapId),
                seamsWithinPrefetch(
                    firstVisibleIndex = 39,
                    lastVisibleIndex = 49,
                    itemCount = window.size,
                    peek = { window.getOrNull(it) },
                    seams = seams,
                ),
            )
        }

    @Test
    fun `opening at the unread marker requests a gap-directed fill`() =
        runTest {
            val fixture = seedCatchUpSeam()
            val window = seamWindow()
            val filler = RecordingGapFiller()
            val vm =
                viewModel(
                    enteredAtMarker(fixture),
                    readyHistoryManager(),
                    messages = seamRepository(),
                    gapFiller = filler,
                )
            vm.state.first { it.buffer != null }
            // The screen's side of entry, collapsed to its two observable effects: the room is on
            // screen, and the one-shot entry placement finished.
            vm.onResume()
            vm.onInitialPositionHandled()
            advanceUntilIdle()

            vm.setSeamPrefetch(window[39].timelineAnchor(), olderEdgeIndex = 49, gapIds = setOf(fixture.gapId))
            advanceUntilIdle()

            assertEquals(listOf(channel.id to fixture.gapId), filler.requests)
        }

    @Test
    fun `the opened unread viewport takes exactly one quantum while it stands still`() =
        runTest {
            // The required E2E's run-2 failure, below E2E. The journey settled on 262 rows against an
            // exact 199 because the rule granted two more quanta to a viewport nobody touched: the OLDER
            // edge re-measured (the seam's divider entered its loading state, then 50 rows landed under
            // the fold) and the gap's demand blinked out and back as its own fill receded it. Every
            // report below carries the SAME newest visible row, which is the whole point.
            val fixture = seedCatchUpSeam()
            val window = seamWindow()
            val filler = RecordingGapFiller()
            val vm =
                viewModel(
                    enteredAtMarker(fixture),
                    readyHistoryManager(),
                    messages = seamRepository(),
                    gapFiller = filler,
                )
            vm.state.first { it.buffer != null }
            vm.onResume()
            vm.onInitialPositionHandled()
            advanceUntilIdle()
            val parked = window[39].timelineAnchor()

            vm.setSeamPrefetch(parked, olderEdgeIndex = 49, gapIds = setOf(fixture.gapId))
            advanceUntilIdle()
            vm.setSeamPrefetch(parked, olderEdgeIndex = 50, gapIds = setOf(fixture.gapId))
            advanceUntilIdle()
            vm.setSeamPrefetch(parked, olderEdgeIndex = 51, gapIds = emptySet())
            advanceUntilIdle()
            vm.setSeamPrefetch(parked, olderEdgeIndex = 50, gapIds = setOf(fixture.gapId))
            advanceUntilIdle()

            assertEquals(listOf(channel.id to fixture.gapId), filler.requests)
        }

    @Test
    fun `a reader who scrolls deeper gets a second quantum`() =
        runTest {
            // The other side of the same rule, and the one over-tightening would break: the E2E closes
            // the rest of its gap with deliberate scrolls, so a viewport that really did move to an
            // older row must load again without a tap.
            val fixture = seedCatchUpSeam()
            val window = seamWindow()
            val filler = RecordingGapFiller()
            val vm =
                viewModel(
                    enteredAtMarker(fixture),
                    readyHistoryManager(),
                    messages = seamRepository(),
                    gapFiller = filler,
                )
            vm.state.first { it.buffer != null }
            vm.onResume()
            vm.onInitialPositionHandled()
            advanceUntilIdle()

            vm.setSeamPrefetch(window[39].timelineAnchor(), olderEdgeIndex = 49, gapIds = setOf(fixture.gapId))
            advanceUntilIdle()
            vm.setSeamPrefetch(window[44].timelineAnchor(), olderEdgeIndex = 54, gapIds = setOf(fixture.gapId))
            advanceUntilIdle()

            assertEquals(
                listOf(channel.id to fixture.gapId, channel.id to fixture.gapId),
                filler.requests,
            )
        }

    @Test
    fun `a pending entry never starts a fill`() =
        runTest {
            // The gate's whole purpose: a fetch started while the one-shot entry placement is still
            // moving the viewport would page against a list that is about to be re-placed underneath it.
            val fixture = seedCatchUpSeam()
            val window = seamWindow()
            val filler = RecordingGapFiller()
            val vm =
                viewModel(
                    enteredAtMarker(fixture),
                    readyHistoryManager(),
                    messages = seamRepository(),
                    gapFiller = filler,
                )
            vm.state.first { it.buffer != null }
            vm.onResume()
            advanceUntilIdle()

            vm.setSeamPrefetch(window[39].timelineAnchor(), olderEdgeIndex = 49, gapIds = setOf(fixture.gapId))
            advanceUntilIdle()

            assertTrue("entry has not settled", vm.entryState.value is EntryPositionState.Pending)
            assertEquals(emptyList<Pair<Long, Long>>(), filler.requests)
        }

    @Test
    fun `demand reported before entry settles is not lost`() =
        runTest {
            // The demand signal is EDGE-triggered on the screen: a viewport that never moves again
            // reports once. If the gate were closed at that instant and the retained demand were not
            // re-evaluated when it opens, the seam would never load however long the reader waits —
            // which is exactly the terminal state the E2E journey reported, with a recoverable gap in
            // reach and not one `gap_fill_*` record in the app's journal.
            val fixture = seedCatchUpSeam()
            val window = seamWindow()
            val filler = RecordingGapFiller()
            val vm =
                viewModel(
                    enteredAtMarker(fixture),
                    readyHistoryManager(),
                    messages = seamRepository(),
                    gapFiller = filler,
                )
            vm.state.first { it.buffer != null }
            vm.onResume()
            advanceUntilIdle()

            vm.setSeamPrefetch(window[39].timelineAnchor(), olderEdgeIndex = 49, gapIds = setOf(fixture.gapId))
            advanceUntilIdle()
            assertEquals(emptyList<Pair<Long, Long>>(), filler.requests)

            // No second demand report: the gate opening is the only new fact.
            vm.onInitialPositionHandled()
            advanceUntilIdle()

            assertEquals(listOf(channel.id to fixture.gapId), filler.requests)
        }

    @Test
    fun `summary preparation follows Agentwire enable and disable transitions`() =
        runTest {
            val (entered, unread) = seedContextUnread()
            val prefs = FakeAgentwirePrefs()
            val shares = PendingShareStore()
            val vm =
                viewModel(
                    entered,
                    FakeConnectionManager(network.id),
                    messages = FakeMessageRepository(events = unread),
                    shares = shares,
                    agentwirePrefs = prefs,
                )
            vm.state.first { it.buffer != null }
            vm.unreadEntrySnapshot.first { it != null }

            assertFalse(vm.agentwireEnabled.value)
            assertEquals(AgentContextPreparation.NO_CONTEXT, vm.prepareCatchUpContext())
            assertEquals(AgentContextPreparation.NO_CONTEXT, vm.prepareThreadContext(unread.first().id))
            assertNull(shares.peek())

            prefs.setEnabled(true)
            vm.agentwireEnabled.first { it }
            assertEquals(AgentContextPreparation.READY, vm.prepareCatchUpContext())
            assertTrue(shares.consume() is PendingShare.AgentContext)
            assertEquals(AgentContextPreparation.READY, vm.prepareThreadContext(unread.first().id))
            assertTrue(shares.consume() is PendingShare.AgentContext)

            prefs.setEnabled(false)
            vm.agentwireEnabled.first { !it }
            assertEquals(AgentContextPreparation.NO_CONTEXT, vm.prepareCatchUpContext())
            assertEquals(AgentContextPreparation.NO_CONTEXT, vm.prepareThreadContext(unread.first().id))
            assertNull(shares.peek())
        }

    @Test
    fun `catch-up preparation keeps the visit marker and never sends or persists source output`() =
        runTest {
            val (entered, unread) = seedContextUnread()
            val manager = FakeConnectionManager(network.id)
            val savedState = SavedStateHandle()
            val buffers = FakeBufferRepository(entered)
            val shares = PendingShareStore()
            val vm =
                viewModel(
                    entered,
                    manager,
                    messages = FakeMessageRepository(events = unread),
                    savedStateHandle = savedState,
                    buffers = buffers,
                    shares = shares,
                    agentwirePrefs = FakeAgentwirePrefs(true),
                )
            vm.state.first { it.buffer != null }
            val frozen = checkNotNull(vm.unreadEntrySnapshot.first { it != null })
            savedState.getStateFlow("unread_entry_snapshot_computed", false).first { it }
            vm.onResume()
            val advancedMarker = TimelineAnchor(unread.last().serverTime, unread.last().id)
            vm.markRead(advancedMarker)
            buffers.update(
                entered.copy(
                    localReadAnchorTime = advancedMarker.serverTime,
                    localReadAnchorEventId = advancedMarker.eventId,
                ),
            )
            vm.localReadAnchor.first { it?.eventId == advancedMarker.eventId }
            runCurrent()
            val markerWrites = manager.readMarkers.size
            val savedSnapshot = savedState.keys().associateWith { savedState.get<Any>(it) }
            val before = db.messageDao().historyRowsForMerge(channel.id)

            assertEquals(AgentContextPreparation.READY, vm.prepareCatchUpContext())
            val share = shares.peek() as PendingShare.AgentContext
            val records =
                share.prompt
                    .lineSequence()
                    .filter { it.startsWith("{") }
                    .map(Json::parseToJsonElement)
                    .toList()
            assertEquals(
                unread.map { it.text },
                records.map {
                    it.jsonObject
                        .getValue("text")
                        .jsonPrimitive.content
                },
            )
            assertEquals(
                unread.map { it.sender },
                records.map {
                    it.jsonObject
                        .getValue("sender")
                        .jsonPrimitive.content
                },
            )
            assertEquals(frozen, vm.unreadEntrySnapshot.value)
            assertEquals(markerWrites, manager.readMarkers.size)
            assertEquals(savedSnapshot, savedState.keys().associateWith { savedState.get<Any>(it) })
            assertEquals(before, db.messageDao().historyRowsForMerge(channel.id))
            assertTrue(manager.messages.isEmpty())

            assertEquals(AgentContextPreparation.PENDING_SHARE, vm.prepareCatchUpContext())
            assertEquals(share, shares.peek())
        }

    @Test
    fun `catch-up discloses a frozen incomplete lower bound without a current history gap`() =
        runTest {
            val (entered, unread) = seedContextUnread()
            val marker = TimelineAnchor(unread.first().serverTime, unread.first().id - 1, unread.first().timelineOrder)
            val savedState =
                SavedStateHandle(
                    mapOf(
                        "unread_entry_snapshot_computed" to true,
                        "unread_entry_snapshot_time" to marker.serverTime,
                        "unread_entry_snapshot_event" to marker.eventId,
                        "unread_entry_snapshot_order" to marker.timelineOrder,
                        "unread_entry_snapshot_count" to unread.size,
                        "unread_entry_snapshot_lower_bound" to true,
                    ),
                )
            val shares = PendingShareStore()
            val vm =
                viewModel(
                    entered,
                    FakeConnectionManager(network.id),
                    savedStateHandle = savedState,
                    shares = shares,
                    agentwirePrefs = FakeAgentwirePrefs(true),
                )
            vm.state.first { it.buffer != null }

            assertEquals(AgentContextPreparation.READY, vm.prepareCatchUpContext())
            val share = shares.peek() as PendingShare.AgentContext
            assertTrue(share.coverage.contains("partial"))
            assertTrue(share.coverage.contains("missing local history"))
        }

    @Test
    fun `missing thread context does not replace a parked share or prepare an empty prompt`() =
        runTest {
            val shares = PendingShareStore()
            val vm =
                viewModel(
                    channel,
                    FakeConnectionManager(network.id),
                    shares = shares,
                    agentwirePrefs = FakeAgentwirePrefs(true),
                )
            vm.state.first { it.buffer != null }
            assertEquals(AgentContextPreparation.NO_CONTEXT, vm.prepareThreadContext(Long.MAX_VALUE))
            assertNull(shares.peek())

            val prior = PendingShare.AgentContext(channel.id, channel.displayName, "frozen context", "partial")
            shares.setIfEmpty(prior)
            assertEquals(AgentContextPreparation.PENDING_SHARE, vm.prepareThreadContext(Long.MAX_VALUE))
            assertEquals(prior, shares.peek())
        }

    @Test
    fun `context picker rejects ordinary chats and preserves both canceled source and existing destination draft`() =
        runTest {
            val shares = PendingShareStore()
            val original = PendingShare.AgentContext(query.id, query.displayName, "frozen messages", "partial")
            shares.setIfEmpty(original)
            val drafts = ComposerDraftStore(db)
            val existing = drafts.saveDraft(channel.id, "unfinished destination message", null)
            val buffers = FakeBufferRepository(channel)
            val prefs = FakeAgentwirePrefs(true)
            val picker = SharePickerViewModel(buffers, shares, drafts, prefs)

            assertFalse(picker.pick(channel.id))
            picker.cancel()
            assertEquals(original, shares.peek())
            assertNull(shares.agentContext(channel.id).value)
            assertEquals(existing, drafts.loadDraft(channel.id))

            buffers.update(channel.copy(topic = "agentwire:v1;account=controller;agent=host;backend=claude"))
            val reopened = SharePickerViewModel(buffers, shares, drafts, prefs)
            assertTrue(reopened.pick(channel.id))
            assertNull(shares.peek())
            assertEquals(original, shares.agentContext(channel.id).value)
            assertEquals(existing, drafts.loadDraft(channel.id))
        }

    private suspend fun seedContextUnread(): Pair<BufferEntity, List<MessageEntity>> {
        val markerId =
            db
                .messageDao()
                .insertAll(
                    listOf(
                        message(channel.id, "marker", null, "alice").copy(
                            serverTime = 100,
                            dedupKey = "context-marker",
                        ),
                    ),
                ).single()
        val unreadIds =
            db.messageDao().insertAll(
                listOf(
                    message(channel.id, "first unread", null, "alice").copy(
                        serverTime = 101,
                        dedupKey = "context-unread-1",
                    ),
                    message(channel.id, "second unread", null, "bob").copy(
                        serverTime = 102,
                        dedupKey = "context-unread-2",
                    ),
                ),
            )
        val unread = unreadIds.map { checkNotNull(db.messageDao().byCanonicalId(it)) }
        return channel.copy(localReadAnchorTime = 100, localReadAnchorEventId = markerId) to unread
    }

    private fun viewModel(
        buffer: BufferEntity,
        manager: FakeConnectionManager,
        history: HistoryResyncController =
            HistoryResyncCoordinator(
                db = db,
                processor = processor,
                scope = CoroutineScope(Dispatchers.Unconfined),
            ),
        messages: MessageRepository = FakeMessageRepository(),
        routeBufferId: Long = buffer.id,
        foreground: FakeForegroundBufferTracker = FakeForegroundBufferTracker(),
        scrollPositions: ChatScrollPositionStore = ChatScrollPositionStore(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        buffers: BufferRepository = FakeBufferRepository(buffer, routeBufferId),
        jumpToMsgid: String? = null,
        jumpToTime: Long = 0,
        jumpToEventId: Long? = null,
        restoredState: Map<String, Any> = emptyMap(),
        // Injectable so a test can observe the write-through entry-position keys after transitions.
        savedStateHandle: SavedStateHandle = SavedStateHandle(),
        // The seam the timeline's history rule ends at. The production default is a Noop, so a test
        // that wants to observe a fill at all has to hand one in.
        gapFiller: HistoryGapFiller = NoopHistoryGapFiller,
        // Injectable so a test can push prefills at the exact store instance the VM listens to.
        drafts: ComposerDraftStore = ComposerDraftStore(db),
        replyPrefs: ReplyPrefs = FakeReplyPrefs(),
        agentwirePrefs: AgentwirePrefs = FakeAgentwirePrefs(),
        shares: PendingShareStore = PendingShareStore(),
        channelWatch: ChannelWatch = ChannelWatch.Noop,
    ): ChatViewModel {
        val routeState = mutableMapOf<String, Any>("bufferId" to routeBufferId)
        jumpToMsgid?.let { routeState["jumpToMsgid"] = it }
        if (jumpToTime > 0) routeState["jumpToTime"] = jumpToTime
        jumpToEventId?.let { routeState["jumpToEventId"] = it }
        routeState.putAll(restoredState)
        routeState.forEach { (key, value) -> savedStateHandle[key] = value }
        return ChatViewModel(
            savedStateHandle = savedStateHandle,
            messageRepository = messages,
            bufferRepository = buffers,
            networkIdentityDao = db.networkIdentityDao(),
            dccTransferDao = db.dccTransferDao(),
            dccTransferController = FakeDccTransferController(),
            connectionManager = manager,
            typingTracker = FakeTypingTracker(),
            foregroundBufferTracker = foreground,
            linkPreviewRepository =
                object : LinkPreviewRepository {
                    override suspend fun preview(
                        url: String,
                        networkId: Long?,
                    ): LinkPreview? = null
                },
            draftStore = drafts,
            pendingShareStore = shares,
            scrollPositionStore = scrollPositions,
            historyPageLoader = HistoryPageLoader(processor),
            settingsRepository = settings,
            replyPrefs = replyPrefs,
            agentwirePrefs = agentwirePrefs,
            visibilityReader = MessageVisibilityReader(db),
            historyResyncCoordinator = history,
            userDao = db.userDao(),
            contentPreviewPrefs = FakeContentPreviewPrefs(),
            audioMetadataRepository = FakeAudioMetadataRepository(),
            audioPlaybackController = FakeAudioPlaybackController(),
            gapFiller = gapFiller,
            channelWatch = channelWatch,
        )
    }

    private fun testClient(transport: IrcTransport? = null) =
        IrcClient(
            config = IrcClientConfig("irc.example", 6697, true, "me", "me", "Me"),
            factory = TransportFactory { _, _, _, _, _ -> transport ?: error("transport is not used") },
            scope = CoroutineScope(SupervisorJob() + dispatcher),
        )

    private fun message(
        bufferId: Long,
        text: String,
        msgid: String?,
        sender: String,
        id: Long = 0,
    ) = MessageEntity(
        id = id,
        bufferId = bufferId,
        msgid = msgid,
        serverTime = 1,
        sender = sender,
        kind = MessageKind.PRIVMSG,
        text = text,
        dedupKey = msgid ?: "pending:$id",
    )

    private data class SentMessage(
        val bufferId: Long,
        val text: String,
        val replyTo: Long?,
        val channelContext: String? = null,
    )

    private data class SentReaction(
        val bufferId: Long,
        val msgid: String,
        val emoji: String,
    )

    private class FakeDccTransferController : DccTransferController {
        override fun observeAll(): Flow<List<DccTransferEntity>> = flowOf(emptyList())

        override fun observeForNetwork(networkId: Long): Flow<List<DccTransferEntity>> = flowOf(emptyList())

        override suspend fun acceptIncoming(
            transferId: Long,
            destinationUri: Uri,
            allowPrivateEndpoint: Boolean,
        ) = Unit

        override suspend fun reject(transferId: Long) = Unit

        override suspend fun removeRecord(transferId: Long) = Unit

        override suspend fun sendFile(
            bufferId: Long,
            sourceUri: Uri,
            secure: Boolean,
        ) = Unit
    }

    private class FakeConnectionManager(
        networkId: Long,
        state: IrcClientState = IrcClientState.Ready("me", emptySet(), emptyMap()),
        client: IrcClient? = null,
        private val retryAccepted: Boolean = true,
        private val sendAccepted: Boolean = true,
        private val sendGate: CompletableDeferred<Unit>? = null,
        private val reactionError: Boolean = false,
        private val redactionAccepted: Boolean = false,
        private val storedTexts: ((String) -> List<String>)? = null,
        private val sendRejection: io.github.trevarj.motd.service.SendRejectionReason? = null,
        private val retryRejection: io.github.trevarj.motd.service.SendRejectionReason? = null,
        private val inviteAccepted: Boolean = false,
        historyPending: Set<Long> = emptySet(),
        /**
         * What a negotiated CHATHISTORY wire would report. Defaults to the pre-registration answer
         * so a fake that models nothing keeps the history gate closed, exactly as the real accessor
         * does for a network with no live client.
         */
        private val historyAvailability: HistoryAvailability = HistoryAvailability.NegotiatingOrOffline,
    ) : NoopConnectionManager() {
        private var currentClient: IrcClient? = client
        override val connectionStates = MutableStateFlow(mapOf(networkId to state))
        override val connectionActivity =
            MutableStateFlow(
                io.github.trevarj.motd.service.ConnectionActivitySnapshot(
                    states = mapOf(networkId to state),
                    progressing = if (client != null) mapOf(networkId to true) else emptyMap(),
                    historyCatchUpPending = historyPending,
                ),
            )
        override val rosterStates = MutableStateFlow<Map<Long, RosterLoadState>>(emptyMap())
        val messages = mutableListOf<SentMessage>()
        val reactions = mutableListOf<SentReaction>()
        val redactions = mutableListOf<Pair<Long, String>>()
        val typing = mutableListOf<Pair<Long, String>>()
        val sentLines = mutableListOf<String>()
        val commandOrigins = mutableListOf<Pair<Long, IrcMessage>>()
        val joins = mutableListOf<Triple<Long, String, String?>>()
        val parts = mutableListOf<Pair<Long, String?>>()
        val invites = mutableListOf<Pair<Long, String>>()
        val readMarkers = mutableListOf<Pair<Long, TimelineAnchor>>()
        val messageStarted = CompletableDeferred<Unit>()
        val typingSent = CompletableDeferred<Unit>()
        val memberRequests = mutableListOf<Long>()

        fun replaceClient(client: IrcClient?) {
            currentClient = client
        }

        fun finishHistoryCatchUp(networkId: Long) {
            connectionActivity.value =
                connectionActivity.value.copy(
                    historyCatchUpPending = connectionActivity.value.historyCatchUpPending - networkId,
                )
        }

        fun publishState(
            networkId: Long,
            state: IrcClientState,
            progressing: Boolean = connectionActivity.value.progressing[networkId] == true,
            initialized: Boolean = true,
        ) {
            connectionStates.value = mapOf(networkId to state)
            connectionActivity.value =
                io.github.trevarj.motd.service.ConnectionActivitySnapshot(
                    states = mapOf(networkId to state),
                    progressing = if (progressing) mapOf(networkId to true) else emptyMap(),
                    initializationComplete = initialized,
                )
        }

        override fun clientFor(networkId: Long): IrcClient? = currentClient

        override fun historyAvailabilityFor(networkId: Long): HistoryAvailability = historyAvailability

        override suspend fun sendMessage(
            bufferId: Long,
            text: String,
            replyToEventId: Long?,
            channelContext: String?,
        ): io.github.trevarj.motd.service.SendAcceptance {
            sendRejection?.let {
                return io.github.trevarj.motd.service.SendAcceptance
                    .Rejected(it)
            }
            if (!sendAccepted) {
                return io.github.trevarj.motd.service.SendAcceptance.Rejected(
                    io.github.trevarj.motd.service.SendRejectionReason.PERSISTENCE_FAILED,
                )
            }
            messages += SentMessage(bufferId, text, replyToEventId, channelContext)
            messageStarted.complete(Unit)
            sendGate?.await()
            // Mirror the real manager, which reports what it actually persisted: a reply can gain
            // a visible prefix and newlines split one submission into several rows.
            val stored = storedTexts?.invoke(text) ?: listOf(text)
            return io.github.trevarj.motd.service.SendAcceptance.Accepted(
                eventIds = List(stored.size) { it + 1L },
                storedTexts = stored,
            )
        }

        override suspend fun retryMessage(eventId: Long): io.github.trevarj.motd.service.SendAcceptance =
            retryRejection?.let {
                io.github.trevarj.motd.service.SendAcceptance
                    .Rejected(it)
            } ?: if (retryAccepted) {
                io.github.trevarj.motd.service.SendAcceptance
                    .Accepted(listOf(eventId))
            } else {
                io.github.trevarj.motd.service.SendAcceptance.Rejected(
                    io.github.trevarj.motd.service.SendRejectionReason.EVENT_NOT_RETRYABLE,
                )
            }

        override suspend fun sendTyping(
            bufferId: Long,
            state: String,
        ) {
            typing += bufferId to state
            typingSent.complete(Unit)
        }

        override suspend fun sendReact(
            bufferId: Long,
            msgid: String,
            emoji: String,
        ) {
            if (reactionError) error("reaction rejected")
            reactions += SentReaction(bufferId, msgid, emoji)
        }

        override suspend fun redactMessage(
            bufferId: Long,
            msgid: String,
        ): Boolean {
            if (redactionAccepted) redactions += bufferId to msgid
            return redactionAccepted
        }

        override suspend fun sendCommand(
            networkId: Long,
            originBufferId: Long,
            message: IrcMessage,
            channelContext: String?,
        ) {
            commandOrigins += originBufferId to message
            when (message.command) {
                "JOIN" -> joinChannel(networkId, message.params.firstOrNull().orEmpty(), message.params.getOrNull(1))
                "PART" -> partChannel(originBufferId, message.params.getOrNull(1))
                else -> currentClient?.send(message)
            }
        }

        override suspend fun joinChannel(
            networkId: Long,
            channel: String,
            key: String?,
        ): Boolean {
            joins += Triple(networkId, channel, key)
            return true
        }

        override suspend fun partChannel(
            bufferId: Long,
            reason: String?,
        ) {
            parts += bufferId to reason
        }

        override suspend fun inviteToChannel(
            bufferId: Long,
            nick: String,
        ): Boolean {
            invites += bufferId to nick
            return inviteAccepted
        }

        override suspend fun ensureQueryBuffer(
            networkId: Long,
            nick: String,
        ): Long = 2L

        override suspend fun ensureServerBuffer(networkId: Long): Long = 3L

        override suspend fun markRead(
            bufferId: Long,
            anchor: TimelineAnchor,
        ) {
            readMarkers += bufferId to anchor
        }

        override suspend fun requestMembers(
            bufferId: Long,
            force: Boolean,
        ) {
            memberRequests += bufferId
        }
    }

    private class FakeHistoryResyncController(
        private val onReconcile: suspend (Int) -> Unit = {},
    ) : HistoryResyncController {
        private val bufferStatus = MutableStateFlow<HistorySyncStatus>(HistorySyncStatus.Idle)
        val reconciledBuffers = mutableListOf<Long>()
        val pendingReconciledBuffers = mutableListOf<Long>()

        override fun syncStatus(bufferId: Long): Flow<HistorySyncStatus> = bufferStatus

        fun setSyncStatus(status: HistorySyncStatus) {
            bufferStatus.value = status
        }

        override suspend fun reconcileBuffer(
            buffer: BufferEntity,
            client: IrcClient,
            isCurrent: () -> Boolean,
        ): HistoryResyncState {
            check(isCurrent())
            reconciledBuffers += buffer.id
            onReconcile(reconciledBuffers.size)
            return HistoryResyncState.UpToDate
        }

        override suspend fun reconcilePendingMessage(
            buffer: BufferEntity,
            client: IrcClient,
            isCurrent: () -> Boolean,
        ): HistoryResyncState {
            check(isCurrent())
            pendingReconciledBuffers += buffer.id
            onReconcile(pendingReconciledBuffers.size)
            return HistoryResyncState.UpToDate
        }
    }

    private class FakeBufferRepository(
        private val current: BufferEntity,
        private val routeId: Long = current.id,
    ) : BufferRepository {
        private val buffer = MutableStateFlow(current)
        val chatList = MutableStateFlow<List<ChatListRow>>(emptyList())
        val layoutWrites = mutableListOf<Pair<Long, LayoutDensity?>>()
        var layoutWriteResult = true
        val presenceWrites = mutableListOf<Pair<Long, PresenceMode?>>()
        var presenceWriteResult = true
        val memberNicks = MutableStateFlow<List<String>>(emptyList())

        fun update(value: BufferEntity) {
            buffer.value = value
        }

        override fun observeChatList(): Flow<List<ChatListRow>> = chatList

        override fun observeBuffer(id: Long): Flow<BufferEntity?> = buffer.takeIf { id == routeId || id == current.id } ?: flowOf(null)

        override fun observeMembers(bufferId: Long): Flow<List<MemberEntity>> = flowOf(emptyList())

        override fun observeMemberNicks(bufferId: Long): Flow<List<String>> = memberNicks

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
        ): Boolean {
            layoutWrites += id to layout
            if (layoutWriteResult) buffer.value = buffer.value.copy(layoutDensityOverride = layout)
            return layoutWriteResult
        }

        override suspend fun setPresenceModeOverride(
            id: Long,
            mode: PresenceMode?,
        ): Boolean {
            presenceWrites += id to mode
            if (presenceWriteResult) buffer.value = buffer.value.copy(presenceModeOverride = mode)
            return presenceWriteResult
        }

        override suspend fun deleteBuffer(id: Long) = Unit
    }

    private class FakeMessageRepository(
        private val events: List<MessageEntity> = emptyList(),
        private val newerCount: Int = 0,
        /**
         * Real timeline counts, from the same database the ViewModel resolves the saved viewport
         * and the displayed watermark against. Entry compares all three, so a constant here models
         * two coordinate systems — the incoherence
         * [io.github.trevarj.motd.data.repo.MessageRepositoryPagingTest] pins against. Tests that
         * only need "some index" keep the constant.
         */
        private val counts: MessageVisibilityReader? = null,
    ) : MessageRepository {
        val msgid = MutableStateFlow<String?>(null)
        val deletedIds = mutableListOf<Long>()
        val requestedMsgids = mutableListOf<String>()
        var resolvedByMsgid: MessageEntity? = null
        var resolvedById: MessageEntity? = null
        var reactionRows: List<ReactionEntity> = emptyList()
        var blockedMsgid: String? = null
        val blockedResolutionStarted = CompletableDeferred<Unit>()
        private val blockedResolutionRelease = CompletableDeferred<Unit>()

        /** The Pager initial key of the first generation the ViewModel created. */
        val firstInitialKey = CompletableDeferred<Int?>()

        override fun messages(
            bufferId: Long,
            visibility: MessageVisibilitySpec,
        ): Flow<PagingData<MessageEntity>> = flowOf(PagingData.empty())

        override fun messages(
            bufferId: Long,
            visibility: MessageVisibilitySpec,
            initialKey: Int?,
        ): Flow<PagingData<MessageEntity>> {
            firstInitialKey.complete(initialKey)
            return flowOf(PagingData.empty())
        }

        override fun reactions(
            bufferId: Long,
            msgids: List<String>,
        ): Flow<List<ReactionEntity>> = flowOf(reactionRows.filter { it.bufferId == bufferId && it.targetMsgid in msgids })

        override suspend fun byId(id: Long): MessageEntity? = resolvedById?.takeIf { it.id == id } ?: events.firstOrNull { it.id == id }

        override suspend fun byMsgid(
            bufferId: Long,
            msgid: String,
        ): MessageEntity? {
            requestedMsgids += msgid
            if (msgid == blockedMsgid) {
                blockedResolutionStarted.complete(Unit)
                blockedResolutionRelease.await()
            }
            return resolvedByMsgid?.takeIf { it.bufferId == bufferId && it.msgid == msgid }
        }

        override fun observeByMsgid(
            bufferId: Long,
            msgid: String,
        ): Flow<MessageEntity?> = flowOf(null)

        override suspend fun awaitMsgid(
            id: Long,
            timeoutMs: Long,
        ): String? = withTimeoutOrNull(timeoutMs) { msgid.filterNotNull().first() }

        override suspend fun countNewerThan(
            bufferId: Long,
            serverTime: Long,
            id: Long,
            visibility: MessageVisibilitySpec,
        ): Int = counts?.countTimelineNewer(bufferId, serverTime, id, visibility) ?: newerCount

        override suspend fun deleteMessage(id: Long) {
            deletedIds += id
        }
    }

    private class RecordingTransport : IrcTransport {
        private val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()

        override suspend fun connect() = Unit

        override val incoming: Flow<String> = inbound.consumeAsFlow()

        override suspend fun send(line: String) {
            sent += line
        }

        override suspend fun close() {
            inbound.close()
        }
    }

    private class FakeTypingTracker : TypingTracker {
        override fun typingNicks(bufferId: Long): StateFlow<List<String>> = MutableStateFlow(emptyList())
    }

    private class FakeForegroundBufferTracker : ForegroundBufferTracker {
        override val foregroundBufferId = MutableStateFlow<Long?>(null)

        override fun set(bufferId: Long?) {
            foregroundBufferId.value = bufferId
        }
    }

    private class FakeSettingsRepository : SettingsRepository {
        override val settings = MutableStateFlow(Settings())

        data class SocialMutation(
            val nick: String,
            val enabled: Boolean,
            val rules: IrcIdentityRules,
        )

        val foolMutations = mutableListOf<SocialMutation>()
        var legacyFoolMutationCalled = false

        override suspend fun setThemeMode(m: ThemeMode) = Unit

        override suspend fun setDynamicColor(enabled: Boolean) = Unit

        override suspend fun setDeliveryMode(m: DeliveryMode) = Unit

        override suspend fun setLayoutDensity(d: LayoutDensity) = Unit

        override suspend fun setNickColorsEnabled(enabled: Boolean) = Unit

        override suspend fun setNickColorPalette(p: NickColorPalette) = Unit

        override suspend fun setNickColorOverride(
            nick: String,
            hue: Int?,
        ) = Unit

        override suspend fun setFriend(
            nick: String,
            isFriend: Boolean,
        ) = Unit

        override suspend fun setFool(
            nick: String,
            isFool: Boolean,
        ) {
            legacyFoolMutationCalled = true
        }

        override suspend fun setFool(
            nick: String,
            isFool: Boolean,
            identityRules: IrcIdentityRules,
        ) {
            foolMutations += SocialMutation(nick, isFool, identityRules)
        }

        override suspend fun setFoolsMode(m: FoolsMode) = Unit

        override suspend fun setPresenceMode(m: PresenceMode) = Unit

        override suspend fun setAvatarStyle(style: AvatarStyle) = Unit

        override suspend fun setChatWallpaper(w: ChatWallpaper) = Unit

        override suspend fun setShowComposerEmoji(show: Boolean) = Unit

        override suspend fun setShowComposerFormattingTools(show: Boolean) = Unit

        override suspend fun setChatSoundsEnabled(enabled: Boolean) = Unit

        override suspend fun setHistorySyncDepth(d: io.github.trevarj.motd.data.prefs.HistorySyncDepth) = Unit

        override suspend fun setAutoAwayEnabled(enabled: Boolean) = Unit

        override suspend fun setAutoAwayMinutes(minutes: Int) = Unit

        override suspend fun setAutoAwayMessage(message: String) = Unit
    }

    private class FakeAgentwirePrefs(
        initial: Boolean = false,
    ) : AgentwirePrefs(ApplicationProvider.getApplicationContext()) {
        override val enabled = MutableStateFlow(initial)

        override suspend fun setEnabled(enabled: Boolean) {
            this.enabled.value = enabled
        }
    }

    private class FakeReplyPrefs(
        initial: ReplyConfig = ReplyConfig(),
    ) : ReplyPrefs {
        override val config = MutableStateFlow(initial)

        override suspend fun setVisibleChannelPrefix(enabled: Boolean) = Unit
    }

    private class FakeContentPreviewPrefs : ContentPreviewPrefs {
        override val config = MutableStateFlow(ContentPreviewConfig())

        override suspend fun setShowImages(show: Boolean) = Unit

        override suspend fun setShowLinkPreviews(show: Boolean) = Unit

        override suspend fun setAutoLoadOnUnmetered(enabled: Boolean) = Unit

        override suspend fun setAutoLoadOnMetered(enabled: Boolean) = Unit

        override suspend fun setDirectMediaOnProxiedNetworks(enabled: Boolean) = Unit
    }

    private class FakeAudioMetadataRepository : AudioMetadataRepository {
        override suspend fun metadata(
            url: String,
            networkId: Long?,
        ): AudioMetadata? = null
    }

    private class FakeAudioPlaybackController : AudioPlaybackController {
        override val state = MutableStateFlow(AudioPlaybackState())
        override val waveforms = MutableStateFlow<Map<String, io.github.trevarj.motd.audio.AudioWaveform>>(emptyMap())
        override val cacheStatuses = MutableStateFlow<Map<String, io.github.trevarj.motd.audio.AudioCacheStatus>>(emptyMap())

        override fun play(
            request: io.github.trevarj.motd.audio.AudioPlaybackRequest,
            speed: Float,
        ) = Unit

        override fun toggle(request: io.github.trevarj.motd.audio.AudioPlaybackRequest) = Unit

        override fun inspectCache(attachment: AudioAttachment) = Unit

        override fun toggleActive() = Unit

        override fun pause() = Unit

        override fun dismiss(itemId: String) = Unit

        override fun cancelLoading() = Unit

        override fun retryActive() = Unit

        override fun seekTo(
            itemId: String,
            positionMs: Long,
        ) = Unit

        override fun setSpeed(
            itemId: String,
            speed: Float,
        ) = Unit
    }
}
