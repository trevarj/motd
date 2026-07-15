package io.github.trevarj.motd.service

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.MessageNotifier
import io.github.trevarj.motd.data.sync.TypingTrackerImpl
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResult
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.event.MessageContext
import io.github.trevarj.motd.irc.proto.Prefix
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HistoryResyncCoordinatorTest {
    private lateinit var db: MotdDatabase
    private lateinit var processor: EventProcessor
    private lateinit var coordinator: HistoryResyncCoordinator
    private var networkId = 0L
    private var bufferId = 0L
    private val syncPrefs = object : HistorySyncPrefs {
        private val values = mutableMapOf<Long, Long>()
        override suspend fun lastSuccessfulSync(networkId: Long): Long? = values[networkId]
        override suspend fun setLastSuccessfulSync(networkId: Long, timestamp: Long) {
            values[networkId] = timestamp
        }
        override suspend fun clear(networkId: Long) { values.remove(networkId) }
    }

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        processor = EventProcessor(db, TypingTrackerImpl(), MessageNotifier.Noop)
        coordinator = HistoryResyncCoordinator(
            db,
            processor,
            syncPrefs,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        networkId = db.networkDao().insert(
            NetworkEntity(
                name = "libera",
                role = NetworkRole.DIRECT,
                host = "h",
                port = 6697,
                nick = "me",
                username = "me",
                realname = "Me",
            ),
        )
        bufferId = db.bufferDao().insert(
            BufferEntity(
                networkId = networkId,
                name = "#chan",
                displayName = "#chan",
                type = BufferType.CHANNEL,
                readMarkerTime = 75,
            ),
        )
        processor.onRegistered(networkId, "me", emptyMap())
    }

    @After
    fun tearDown() = db.close()

    private fun message(msgid: String, time: Long, target: String = "#chan") = IrcEvent.ChatMessage(
        ctx = MessageContext(msgid, time, null, "batch", null),
        kind = IrcEvent.ChatKind.PRIVMSG,
        source = Prefix("alice"),
        target = target,
        text = msgid,
        isSelf = false,
        replyToMsgid = null,
    )

    private suspend fun rows(): List<MessageEntity> {
        val loaded = db.messageDao().pagingSource(bufferId).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 500, placeholdersEnabled = false),
        ) as PagingSource.LoadResult.Page
        return loaded.data
    }

    private class FakeSource(
        var supported: Boolean = true,
        var msgidRefs: Boolean = true,
        var pageLimit: Int = 100,
        val responder: suspend (ChatHistoryRequest) -> ChatHistoryResult,
    ) : HistoryResyncCoordinator.HistorySource {
        val requests = mutableListOf<ChatHistoryRequest>()
        override fun hasChatHistory() = supported
        override fun supportsMsgidReferences() = msgidRefs
        override fun pageLimit() = pageLimit
        override suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResult {
            requests += request
            return responder(request)
        }
    }

    @Test
    fun msgidAfterThenLatestOverlap_recoversMissingTail_andIsIdempotent() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> ChatHistoryResult(listOf(message("tail", 200)), emptyList())
                ChatHistoryRequest.Subcommand.LATEST -> ChatHistoryResult(
                    listOf(message("seed", 100), message("tail", 200)),
                    emptyList(),
                )
                else -> ChatHistoryResult(emptyList(), emptyList())
            }
        }

        val first = coordinator.resyncBuffer(networkId, bufferId, "#chan", source)
        val second = coordinator.resyncBuffer(networkId, bufferId, "#chan", source)

        assertEquals(HistoryResyncState.Updated(1), first)
        assertEquals(HistoryResyncState.UpToDate, second)
        assertEquals(2, rows().size)
        assertEquals("msgid=seed", source.requests.first().bound1)
        assertEquals(75L, db.bufferDao().byName(networkId, "#chan")?.readMarkerTime)
    }

    @Test
    fun timestampBoundaryIsUsedWhenMsgidReferencesAreUnavailable() = runTest {
        processor.process(networkId, message("seed", 1_000))
        val source = FakeSource(msgidRefs = false) { ChatHistoryResult(emptyList(), emptyList()) }

        coordinator.resyncBuffer(networkId, bufferId, "#chan", source)

        assertEquals("timestamp=1970-01-01T00:00:01.000Z", source.requests.first().bound1)
    }

    @Test
    fun timestampBoundaryRetainsMillisecondsWithExactlyThreeFractionalDigits() = runTest {
        processor.process(networkId, message("seed", 1_234))
        val source = FakeSource(msgidRefs = false) { ChatHistoryResult(emptyList(), emptyList()) }

        coordinator.resyncBuffer(networkId, bufferId, "#chan", source)

        assertEquals("timestamp=1970-01-01T00:00:01.234Z", source.requests.first().bound1)
    }

    @Test
    fun emptyBoundaryUsesBoundedLatest() = runTest {
        val source = FakeSource { request ->
            ChatHistoryResult(
                events = if (request.subcommand == ChatHistoryRequest.Subcommand.LATEST) {
                    listOf(message("latest", 200))
                } else emptyList(),
                targets = emptyList(),
            )
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(listOf(ChatHistoryRequest.Subcommand.LATEST), source.requests.map { it.subcommand })
    }

    @Test
    fun rejectedAfterFallsBackToLatestOverlap() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource { request ->
            if (request.subcommand == ChatHistoryRequest.Subcommand.AFTER) throw IOException("bad selector")
            ChatHistoryResult(listOf(message("recovered", 150)), emptyList())
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(
            listOf(ChatHistoryRequest.Subcommand.AFTER, ChatHistoryRequest.Subcommand.LATEST),
            source.requests.map { it.subcommand },
        )
    }

    @Test
    fun recentLatestOverlapRepairsSilentGapBehindNewestBoundary() = runTest {
        processor.process(networkId, message("old", 100))
        processor.process(networkId, message("newest", 300))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> ChatHistoryResult(emptyList(), emptyList())
                ChatHistoryRequest.Subcommand.LATEST -> ChatHistoryResult(
                    listOf(message("old", 100), message("gap", 200), message("newest", 300)),
                    emptyList(),
                )
                else -> ChatHistoryResult(emptyList(), emptyList())
            }
        }

        assertEquals(
            HistoryResyncState.Updated(1),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(listOf("newest", "gap", "old"), rows().mapNotNull { it.msgid })
    }

    @Test
    fun automaticNetworkResyncDiscoversTargetsAndCreatesMissingBuffer() = runTest {
        processor.process(networkId, message("seed", 100))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> ChatHistoryResult(emptyList(), listOf("#new" to 500L))
                ChatHistoryRequest.Subcommand.LATEST -> ChatHistoryResult(
                    if (request.target == "#new") listOf(message("found", 500, "#new")) else emptyList(),
                    emptyList(),
                )
                else -> ChatHistoryResult(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        assertTrue(db.bufferDao().byName(networkId, "#new") != null)
        assertTrue(source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS })
    }

    @Test
    fun freshNetworkDiscoversRetainedTargetsFromEpochAndStoresCursor() = runTest {
        db.bufferDao().deleteBuffer(bufferId)
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS -> ChatHistoryResult(emptyList(), listOf("#old" to 500L))
                ChatHistoryRequest.Subcommand.LATEST -> ChatHistoryResult(
                    if (request.target == "#old") listOf(message("retained", 500, "#old")) else emptyList(),
                    emptyList(),
                )
                else -> ChatHistoryResult(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, emptyList(), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        val targets = source.requests.first { it.subcommand == ChatHistoryRequest.Subcommand.TARGETS }
        assertEquals("timestamp=1970-01-01T00:00:00.000Z", targets.bound2)
        assertTrue(targets.bound1!!.matches(Regex("timestamp=.*\\.\\d{3}Z")))
        assertTrue(db.bufferDao().byName(networkId, "#old") != null)
        assertTrue(syncPrefs.lastSuccessfulSync(networkId) != null)
    }

    @Test
    fun networkSyncSeedsLatestWhenLiveJoinPrecedesRetainedHistoryEvenWithPriorCursor() = runTest {
        // A soju child sends the live self-JOIN as it binds. Without an initial LATEST overlap,
        // that new row becomes the AFTER cursor and hides every older retained channel message.
        // Keep a prior cursor to cover an upgrade from the behavior that already marked this
        // bouncer network successfully synced.
        syncPrefs.setLastSuccessfulSync(networkId, 2_000)
        processor.process(
            networkId,
            IrcEvent.Joined(
                ctx = MessageContext(null, 1_000, null, null, null),
                nick = "me",
                channel = "#chan",
                account = null,
                realname = null,
                isSelf = true,
            ),
        )
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    ChatHistoryResult(emptyList(), listOf("#chan" to 1_000L))
                ChatHistoryRequest.Subcommand.AFTER -> ChatHistoryResult(emptyList(), emptyList())
                ChatHistoryRequest.Subcommand.LATEST ->
                    ChatHistoryResult(listOf(message("retained", 500)), emptyList())
                else -> ChatHistoryResult(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        assertEquals(
            listOf(
                ChatHistoryRequest.Subcommand.TARGETS,
                ChatHistoryRequest.Subcommand.AFTER,
                ChatHistoryRequest.Subcommand.LATEST,
            ),
            source.requests.map { it.subcommand },
        )
        assertTrue(rows().any { it.msgid == "retained" })
    }

    @Test
    fun reconnectUsesLastCompletedSyncSoEarlyLiveMessageCannotHideGapOrDuplicate() = runTest {
        val base = 1_700_000_000_000L
        processor.process(networkId, message("seed", base + 100))
        syncPrefs.setLastSuccessfulSync(networkId, base + 150)

        // A live line can beat the reconnect catch-up coroutine into Room.
        processor.process(networkId, message("live", base + 300))
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.TARGETS ->
                    ChatHistoryResult(emptyList(), listOf("#chan" to (base + 300)))
                ChatHistoryRequest.Subcommand.AFTER -> ChatHistoryResult(
                    events = if (request.bound1?.startsWith("timestamp=") == true) {
                        // Soju may overlap the already-delivered live line. Room must insert only
                        // the missed line and retain one copy of the live line by msgid.
                        listOf(message("missed", base + 200), message("live", base + 300))
                    } else {
                        emptyList()
                    },
                    targets = emptyList(),
                )
                else -> ChatHistoryResult(emptyList(), emptyList())
            }
        }

        val result = coordinator.resyncNetwork(networkId, listOf(bufferId to "#chan"), source)

        assertEquals(HistoryResyncState.Updated(1), result)
        assertEquals(listOf("live", "missed", "seed"), rows().mapNotNull { it.msgid })
        assertEquals(1, rows().count { it.msgid == "live" })
        assertTrue(
            source.requests.first { it.subcommand == ChatHistoryRequest.Subcommand.AFTER }
                .bound1.orEmpty().startsWith("timestamp="),
        )
    }

    @Test
    fun timeRangeRefreshUsesAfterFromTheSelectedCutoff() = runTest {
        val now = System.currentTimeMillis()
        val source = FakeSource { request ->
            assertEquals(ChatHistoryRequest.Subcommand.AFTER, request.subcommand)
            ChatHistoryResult(listOf(message("recent", now - 1_000)), emptyList())
        }

        val result = coordinator.resyncBuffer(
            networkId,
            bufferId,
            "#chan",
            source,
            range = HistoryRefreshRange.HOURS_24,
        )

        assertEquals(HistoryResyncState.Updated(1), result)
        assertTrue(source.requests.single().bound1!!.startsWith("timestamp="))
        assertEquals("recent", rows().single().msgid)
    }

    @Test
    fun allAvailableStartsNewestThenPagesBackwardWithinTheCap() = runTest {
        var page = 0
        val source = FakeSource(pageLimit = 2) { request ->
            val response = when (page++) {
                0 -> {
                    assertEquals(ChatHistoryRequest.Subcommand.LATEST, request.subcommand)
                    listOf(message("m5", 5), message("m4", 4))
                }
                1 -> {
                    assertEquals(ChatHistoryRequest.Subcommand.BEFORE, request.subcommand)
                    assertEquals("msgid=m4", request.bound1)
                    listOf(message("m3", 3), message("m2", 2))
                }
                else -> {
                    assertEquals(ChatHistoryRequest.Subcommand.BEFORE, request.subcommand)
                    assertEquals("msgid=m2", request.bound1)
                    listOf(message("m1", 1))
                }
            }
            ChatHistoryResult(response, emptyList())
        }

        val result = coordinator.resyncBuffer(
            networkId,
            bufferId,
            "#chan",
            source,
            range = HistoryRefreshRange.ALL_AVAILABLE,
        )

        assertEquals(HistoryResyncState.Updated(5), result)
        assertEquals(listOf("m5", "m4", "m3", "m2", "m1"), rows().mapNotNull { it.msgid })
        assertEquals(3, source.requests.size)
    }

    @Test
    fun unsupportedAndTimeoutAreExplicitRetryableResults() = runTest {
        val unsupported = FakeSource(supported = false) { ChatHistoryResult(emptyList(), emptyList()) }
        assertEquals(
            HistoryResyncState.Unsupported,
            coordinator.resyncBuffer(networkId, bufferId, "#chan", unsupported),
        )
        assertTrue(unsupported.requests.isEmpty())

        coordinator.requestTimeoutMs = 5
        val slow = FakeSource {
            delay(100)
            ChatHistoryResult(emptyList(), emptyList())
        }
        val result = coordinator.resyncBuffer(networkId, bufferId, "#chan", slow)
        assertTrue(result is HistoryResyncState.Failed)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun staleGenerationDiscardsReturnedPageBeforeRoomWrite() = runTest {
        var current = true
        val source = FakeSource {
            current = false
            ChatHistoryResult(listOf(message("stale", 200)), emptyList())
        }

        val result = coordinator.resyncBuffer(
            networkId,
            bufferId,
            "#chan",
            source,
            isCurrent = { current },
        )

        assertTrue(result is HistoryResyncState.Failed)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun concurrentEquivalentRequestsShareOneFlight() = runTest {
        var calls = 0
        val source = FakeSource {
            calls++
            delay(20)
            ChatHistoryResult(emptyList(), emptyList())
        }

        val results = coroutineScope {
            listOf(
                async { coordinator.resyncBuffer(networkId, bufferId, "#chan", source) },
                async { coordinator.resyncBuffer(networkId, bufferId, "#chan", source) },
            ).awaitAll()
        }

        assertEquals(listOf(HistoryResyncState.UpToDate, HistoryResyncState.UpToDate), results)
        assertEquals(1, calls)
    }

    @Test
    fun fullAfterPageContinuesUntilShortPageWithoutDuplicates() = runTest {
        processor.process(networkId, message("seed", 1))
        val full = (2L..101L).map { message("m$it", it) }
        var afterPage = 0
        val source = FakeSource { request ->
            when (request.subcommand) {
                ChatHistoryRequest.Subcommand.AFTER -> {
                    afterPage++
                    ChatHistoryResult(if (afterPage == 1) full else listOf(message("last", 102)), emptyList())
                }
                ChatHistoryRequest.Subcommand.LATEST -> ChatHistoryResult(listOf(message("last", 102)), emptyList())
                else -> ChatHistoryResult(emptyList(), emptyList())
            }
        }

        assertEquals(
            HistoryResyncState.Updated(101),
            coordinator.resyncBuffer(networkId, bufferId, "#chan", source),
        )
        assertEquals(102, rows().size)
        assertEquals(2, source.requests.count { it.subcommand == ChatHistoryRequest.Subcommand.AFTER })
        assertTrue(source.requests.any { it.subcommand == ChatHistoryRequest.Subcommand.LATEST })
    }

    @Test
    fun automaticRetryBackoffRemainsBounded() {
        assertEquals(2_000L, catchUpRetryDelayMs(0))
        assertEquals(4_000L, catchUpRetryDelayMs(1))
        assertEquals(30_000L, catchUpRetryDelayMs(20))
    }
}
