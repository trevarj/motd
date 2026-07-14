package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MessageKind
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.message
import io.github.trevarj.motd.data.db.network
import io.github.trevarj.motd.irc.event.IrcEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadMarkerRepositoryTest {
    private lateinit var db: MotdDatabase
    private lateinit var repository: ReadMarkerRepository
    private var networkId = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        repository = ReadMarkerRepository(db)
        networkId = db.networkDao().insert(network())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `bulk boundary is newest incoming chat and ignores unsafe newer rows`() = runTest {
        val channel = db.bufferDao().insert(buffer(networkId, "#motd"))
        val server = db.bufferDao().insert(buffer(networkId, "*", BufferType.SERVER))
        db.messageDao().insertAll(
            listOf(
                message(channel, "incoming", serverTime = 100, dedupKey = "incoming"),
                message(
                    channel,
                    "pending self",
                    serverTime = 500,
                    dedupKey = "pending",
                    isSelf = true,
                    pendingLabel = "label",
                ),
                message(
                    channel,
                    "joined",
                    serverTime = 600,
                    dedupKey = "join",
                    kind = MessageKind.JOIN,
                ),
                message(server, "server", serverTime = 700, dedupKey = "server"),
            ),
        )

        assertEquals(
            listOf(BufferReadMarker(channel, "#motd", 100)),
            repository.latestIncoming(listOf(channel, server)),
        )
    }

    @Test
    fun `stored snapshot includes unset markers but excludes server buffers`() = runTest {
        val channel = db.bufferDao().insert(buffer(networkId, "#motd", readMarkerTime = 123))
        val query = db.bufferDao().insert(buffer(networkId, "alice", BufferType.QUERY))
        db.bufferDao().insert(buffer(networkId, "*", BufferType.SERVER, readMarkerTime = 999))

        assertEquals(
            listOf(
                BufferReadMarker(channel, "#motd", 123),
                BufferReadMarker(query, "alice", null),
            ),
            repository.storedForNetwork(networkId),
        )
    }

    @Test
    fun `sync requests set durable markers and fetch unset markers`() {
        val requests = readMarkerSyncRequests(
            listOf(
                BufferReadMarker(1, "#motd", 123),
                BufferReadMarker(2, "alice", null),
            ),
        )

        assertEquals(
            listOf(
                ReadMarkerSyncRequest(1, "#motd", 123),
                ReadMarkerSyncRequest(2, "alice", null),
            ),
            requests,
        )
    }

    @Test
    fun `marker waiter subscribes before request and selects matching target`() = runTest {
        val events = MutableSharedFlow<IrcEvent>()
        var requested = false

        val response = awaitReadMarkerResponse(
            events = events,
            target = "#MOTD",
            normalize = String::lowercase,
            timeoutMs = 1_000,
        ) {
            requested = true
            events.emit(IrcEvent.ReadMarker("#other", 50))
            events.emit(IrcEvent.ReadMarker("#motd", 100))
        }

        assertEquals(true, requested)
        assertEquals(IrcEvent.ReadMarker("#motd", 100), response)
    }
}
