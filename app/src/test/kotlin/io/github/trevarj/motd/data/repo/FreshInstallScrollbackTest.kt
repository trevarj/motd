package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.paging.AsyncPagingDataDiffer
import androidx.paging.CombinedLoadStates
import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadState
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.sync.ChatHistoryRemoteMediator
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.data.visibility.MessageVisibilitySpec
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.Prefix
import io.github.trevarj.motd.ui.chat.ChatHistoryUiState
import io.github.trevarj.motd.ui.chat.chatHistoryUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fresh-install scrollback over the real Pager: a wiped store, a restored channel, and a bouncer
 * that serves deep history. Opening the room seeds the newest page; reaching the bottom of the
 * timeline must keep paging older.
 *
 * The regression this file exists for is the second test. A room whose FIRST seed found the server
 * holding nothing must not be branded start-of-history: that answer is a fact about what the server
 * could serve at that instant — a channel restored a moment ago, a bouncer that has archived
 * nothing for it yet — and the branding is durable, so the room could never page again even once
 * the history existed. Only a server-proven terminal batch, or an empty answer to a directional
 * BEFORE, proves where history starts.
 */
@OptIn(ExperimentalPagingApi::class, ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FreshInstallScrollbackTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private lateinit var loader: HistoryPageLoader
    private var networkId = 0L
    private var bufferId = 0L

    @Before fun setUp() =
        runTest {
            val context = ApplicationProvider.getApplicationContext<Context>()
            db =
                Room
                    .inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
                    .allowMainThreadQueries()
                    .setQueryExecutor { it.run() }
                    .setTransactionExecutor { it.run() }
                    .build()
            processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
            loader = HistoryPageLoader(processor)
            networkId =
                db.networkDao().insert(
                    NetworkEntity(
                        name = "soju",
                        role = NetworkRole.BOUNCER_CHILD,
                        host = "h",
                        port = 6697,
                        nick = "me",
                        username = "me",
                        realname = "Me",
                    ),
                )
            processor.onRegistered(networkId, "me", emptyMap())
            db.bufferDao().insert(
                BufferEntity(networkId = networkId, name = "#chan", displayName = "#chan", type = BufferType.CHANNEL),
            )
            bufferId = db.bufferDao().byName(networkId, "#chan")!!.id
        }

    @After fun tearDown() {
        db.close()
    }

    private fun chatMsg(ordinal: Int) =
        IrcEvent.ChatMessage(
            ctx = MessageContext("row$ordinal", ordinal.toLong(), null, "b", null),
            kind = IrcEvent.ChatKind.PRIVMSG,
            source = Prefix("alice"),
            target = "#chan",
            text = "row$ordinal",
            isSelf = false,
            replyToMsgid = null,
        )

    private fun joined(
        msgid: String,
        time: Long,
        nick: String = "lurker",
        isSelf: Boolean = false,
    ) = IrcEvent.Joined(
        ctx = MessageContext(msgid, time, null, "b", null),
        nick = nick,
        channel = "#chan",
        account = null,
        realname = null,
        isSelf = isSelf,
    )

    private fun hiddenPage(index: Int): List<IrcEvent> {
        val newest = 10_000 - ((index - 1) * 50) - 1
        return ((newest - 49)..newest).map { joined("join-$it", it.toLong()) }
    }

    private fun messages(events: List<IrcEvent>): ChatHistoryResponse.Messages {
        val refs =
            events
                .mapNotNull {
                    when (it) {
                        is IrcEvent.ChatMessage -> it.ctx
                        is IrcEvent.Joined -> it.ctx
                        else -> null
                    }
                }.map { ChatHistoryReference(it.msgid, it.serverTime) }
        return ChatHistoryResponse.Messages(
            events,
            oldest = refs.firstOrNull(),
            newest = refs.lastOrNull(),
            // soju 0.10.x omits draft/chathistory-end, so an exhausted page is never tagged as one.
            endOfHistory = false,
            primaryMessageCount = refs.size,
        )
    }

    /** Presence-heavy field shape: seven invisible pages before the first conversational row. */
    private inner class PresenceFloodHistory : ChatHistoryRemoteMediator.HistorySource {
        val requests = mutableListOf<ChatHistoryRequest>()
        private val before =
            ArrayDeque(
                (1..7).map(::hiddenPage) +
                    listOf(listOf(chatMsg(9_600)) + hiddenPage(8).drop(1)),
            )

        override suspend fun availability() = HistoryAvailability.Ready(setOf(HistoryReferenceType.TIMESTAMP), 100)

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            requests += req
            return messages(if (req.subcommand == ChatHistoryRequest.Subcommand.BEFORE) before.removeFirstOrNull().orEmpty() else emptyList())
        }
    }

    /** A soju-shaped server: timestamp-only references, no chathistory-end, [stored] rows of backlog. */
    private inner class SojuHistory(
        private val timestampOnlyWire: Boolean = true,
        /** What the bouncer has archived for this target; 0 models a channel restored a moment ago. */
        var stored: Int = TOTAL,
        /** Answer the next BEFORE with the boundary row itself: rows land nowhere, the ladder stalls. */
        var stallNextBefore: Boolean = false,
        /** Runs on the wire, inside the loader's serialization, before the page is answered. */
        private val onRequest: suspend (ChatHistoryRequest) -> Unit = {},
    ) : ChatHistoryRemoteMediator.HistorySource {
        val requests = mutableListOf<ChatHistoryRequest>()
        val bounds: List<String?> get() = requests.map { it.bound1 }

        override suspend fun availability() =
            HistoryAvailability.Ready(
                if (timestampOnlyWire) {
                    setOf(HistoryReferenceType.TIMESTAMP)
                } else {
                    setOf(HistoryReferenceType.TIMESTAMP, HistoryReferenceType.MSGID)
                },
                100,
            )

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse {
            requests += req
            onRequest(req)
            if (stored == 0) return messages(emptyList())
            return when (req.subcommand) {
                ChatHistoryRequest.Subcommand.LATEST -> {
                    messages((maxOf(1, stored - req.limit + 1)..stored).map { chatMsg(it) })
                }

                ChatHistoryRequest.Subcommand.BEFORE -> {
                    val boundary = boundaryOrdinal(req.bound1) ?: return messages(emptyList())
                    if (stallNextBefore) {
                        stallNextBefore = false
                        return messages(listOf(chatMsg(boundary)))
                    }
                    val newest = boundary - 1
                    if (newest < 1) return messages(emptyList())
                    messages((maxOf(1, newest - req.limit + 1)..newest).map { chatMsg(it) })
                }

                else -> {
                    messages(emptyList())
                }
            }
        }

        private fun boundaryOrdinal(bound: String?): Int? {
            bound ?: return null
            return when {
                bound.startsWith("msgid=") -> bound.removePrefix("msgid=").removePrefix("row").toIntOrNull()
                else -> (1..TOTAL).firstOrNull { ChatHistorySelectors.timestamp(it.toLong()) == bound }
            }
        }
    }

    private fun repository(history: ChatHistoryRemoteMediator.HistorySource) =
        MessageRepositoryImpl(
            db.bufferDao(),
            db.networkIdentityDao(),
            db,
            db.reactionDao(),
            ChatHistoryMediatorFactory { roomId, visibility, identityRules ->
                ChatHistoryRemoteMediator(
                    roomId,
                    db.bufferDao(),
                    db.messageDao(),
                    processor,
                    history,
                    50,
                    db.historyCursorDao(),
                    db.historyGapDao(),
                    loader,
                    visibility = visibility,
                    identityRules = identityRules,
                )
            },
            db.historyGapDao(),
        )

    private fun differ() =
        AsyncPagingDataDiffer(
            diffCallback =
                object : DiffUtil.ItemCallback<MessageEntity>() {
                    override fun areItemsTheSame(
                        a: MessageEntity,
                        b: MessageEntity,
                    ) = a.id == b.id

                    override fun areContentsTheSame(
                        a: MessageEntity,
                        b: MessageEntity,
                    ) = a == b
                },
            updateCallback =
                object : ListUpdateCallback {
                    override fun onInserted(
                        position: Int,
                        count: Int,
                    ) {}

                    override fun onRemoved(
                        position: Int,
                        count: Int,
                    ) {}

                    override fun onMoved(
                        fromPosition: Int,
                        toPosition: Int,
                    ) {}

                    override fun onChanged(
                        position: Int,
                        count: Int,
                        payload: Any?,
                    ) {}
                },
            mainDispatcher = Dispatchers.Unconfined,
            workerDispatcher = Dispatchers.Unconfined,
        )

    private suspend fun totalRows(): Int =
        db
            .messageDao()
            .pagingSource(bufferId)
            .load(
                androidx.paging.PagingSource.LoadParams
                    .Refresh(null, 2_000, false),
            ).let { (it as androidx.paging.PagingSource.LoadResult.Page).data.size }

    /** Open the room and keep dragging the viewport to the oldest loaded row, like a reader would. */
    private suspend fun TestScope.scrollToOldest(
        repository: MessageRepositoryImpl,
        rounds: Int = 12,
    ) {
        openAndScroll(repository, rounds) {}
    }

    /**
     * The same open, with [whileOpen] run against the live differ before it is torn down — so a test
     * can act on the timeline the reader is looking at instead of reopening the room.
     */
    private suspend fun TestScope.openAndScroll(
        repository: MessageRepositoryImpl,
        rounds: Int = 12,
        whileOpen: suspend (AsyncPagingDataDiffer<MessageEntity>) -> Unit,
    ) {
        val differ = differ()
        val job =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.messages(bufferId, MessageVisibilitySpec()).collectLatest { differ.submitData(it) }
            }
        repeat(rounds) {
            advanceUntilIdle()
            if (differ.itemCount > 0) differ.getItem(differ.itemCount - 1)
        }
        advanceUntilIdle()
        whileOpen(differ)
        advanceUntilIdle()
        job.cancel()
    }

    @Test
    fun oneOpenCrossesFilteredPresenceFloodWithoutReopeningTheRoom() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                processor.process(networkId, joined("self-join", 10_000, nick = "me", isSelf = true))
                val history = PresenceFloodHistory()

                openAndScroll(repository(history), rounds = 1) { differ ->
                    assertTrue(
                        "conversation behind hidden presence pages was not presented",
                        (0 until differ.itemCount).any { differ.peek(it)?.msgid == "row9600" },
                    )
                }

                assertTrue(
                    "one open did not walk the hidden-page quantum: ${history.requests}",
                    history.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE } >=
                        ChatHistoryRemoteMediator.PRESENTATION_PAGE_BUDGET,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun freshRoomSeedsThenKeepsPagingOlderAsTheReaderScrolls() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                val history = SojuHistory(timestampOnlyWire = true)

                scrollToOldest(repository(history))

                assertTrue(
                    "the seed must be followed by older pages, got ${history.bounds}",
                    history.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE } >= 3,
                )
                assertTrue("more than the seeded page is retained: ${totalRows()}", totalRows() > 150)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun roomSeededWhileTheServerHeldNothingStillPagesOnceHistoryExists() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                // First open, moments after the channel came back: the bouncer has archived nothing yet,
                // so the seed's LATEST answers with an empty batch and no chathistory-end tag.
                val empty = SojuHistory(timestampOnlyWire = true, stored = 0)
                scrollToOldest(repository(empty), rounds = 3)

                assertEquals("the seed asked once", 1, empty.requests.size)
                assertEquals(0, totalRows())
                assertFalse(
                    "an empty LATEST is what the server could serve now, not proof of where history starts",
                    db.bufferDao().observeById(bufferId)!!.historyComplete,
                )

                // The bouncer now holds the channel's backlog, and the reader comes back and scrolls.
                val stocked = SojuHistory(timestampOnlyWire = true, stored = TOTAL)
                scrollToOldest(repository(stocked))

                assertTrue(
                    "the room must page again once history exists, got ${stocked.bounds}",
                    stocked.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE } >= 3,
                )
                assertTrue("history backfilled after the empty first seed: ${totalRows()}", totalRows() > 150)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun aStalledLadderIsRecoverableInPlaceInsteadOfNeedingTheRoomReopened() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                // Live rows, no protocol cursor: the room opens on what the connection delivered.
                (951..TOTAL).forEach { processor.process(networkId, chatMsg(it)) }
                // The first older page answers with the boundary row the store already holds, so nothing
                // lands and the next request would repeat verbatim: the ladder stalls. Reported as
                // end-of-pagination this retired APPEND for the entire Pager — no scrolling revived it and
                // the reader had to leave the room and come back. It is retryable now, and the timeline's
                // "load older messages" affordance is exactly this retry.
                val history = SojuHistory(timestampOnlyWire = true, stallNextBefore = true)

                openAndScroll(repository(history)) { differ ->
                    assertEquals("the stall reached the wire once", 1, history.requests.size)
                    assertEquals(50, totalRows())
                    differ.retry()
                }

                assertTrue(
                    "retry re-armed the same ladder, got ${history.bounds}",
                    history.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.BEFORE } >= 3,
                )
                assertTrue("older history landed without reopening the room: ${totalRows()}", totalRows() > 150)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun readerAtTheOldestEndSeesTheFooterSpinnerWhileTheOlderPageIsOnTheWire() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                (951..TOTAL).forEach { processor.process(networkId, chatMsg(it)) }
                val onWire = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val history =
                    SojuHistory(timestampOnlyWire = true) { request ->
                        if (request.subcommand == ChatHistoryRequest.Subcommand.BEFORE &&
                            onWire.complete(Unit)
                        ) {
                            release.await()
                        }
                    }

                val differ = differ()
                val timeline = mutableListOf<Pair<Boolean, CombinedLoadStates>>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    differ.loadStateFlow.collect { timeline += onWire.isCompleted to it }
                }
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    repository(history)
                        .messages(bufferId, MessageVisibilitySpec())
                        .collectLatest { differ.submitData(it) }
                }
                repeat(4) {
                    advanceUntilIdle()
                    if (differ.itemCount > 0) differ.getItem(differ.itemCount - 1)
                }
                advanceUntilIdle()

                val observed =
                    checkNotNull(timeline.lastOrNull { it.first }?.second) {
                        "no load state was published while the page was on the wire"
                    }
                // The footer reads `loadState.append`, so pin the raw state Paging publishes for a
                // mediator page in flight as well as the mapping: only a real in-flight page may reach
                // the shimmer, an armed-but-idle ladder maps to Armed.
                assertTrue(
                    "append must report Loading while the page is on the wire, got ${observed.append}",
                    observed.append is LoadState.Loading,
                )
                assertEquals(
                    ChatHistoryUiState.Loading,
                    chatHistoryUiState(
                        bufferType = BufferType.CHANNEL,
                        connectionState = IrcClientState.Ready("me", emptySet(), emptyMap()),
                        availability = history.availability(),
                        append = observed.append,
                        historyComplete = false,
                    ),
                )

                release.complete(Unit)
                advanceUntilIdle()
            } finally {
                Dispatchers.resetMain()
            }
        }

    private companion object {
        private const val TOTAL = 1_000
    }
}
