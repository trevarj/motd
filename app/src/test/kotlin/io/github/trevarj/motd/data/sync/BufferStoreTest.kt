package io.github.trevarj.motd.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BufferStoreTest {
    private lateinit var db: MotdDatabase
    private var networkId = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        networkId = db.networkDao().insert(
            NetworkEntity(
                name = "test",
                role = NetworkRole.DIRECT,
                host = "irc.example",
                port = 6697,
                nick = "me",
                username = "me",
                realname = "Me",
            ),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun concurrentGetOrCreate_convergesOnOneBuffer() = runTest {
        val store = BufferStore(db)
        val rows = (1..20).map {
            async { store.getOrCreate(networkId, "#room", "#Room", BufferType.CHANNEL) }
        }.awaitAll()

        assertEquals(1, rows.map { it.id }.distinct().size)
        assertEquals(rows.first().networkId, db.bufferDao().byName(networkId, "#room")?.networkId)
    }

    @Test
    fun independentBufferColumns_retainConcurrentUpdates() = runTest {
        val buffer = BufferStore(db).getOrCreate(networkId, "#room", "#Room", BufferType.CHANNEL)
        listOf(
            async { db.bufferDao().setPinned(buffer.id, true) },
            async { db.bufferDao().setMuted(buffer.id, true) },
            async { db.bufferDao().setTopic(buffer.id, "topic", "setter") },
            async { db.bufferDao().setJoined(buffer.id, true) },
            async { db.bufferDao().advanceReadMarker(buffer.id, 4_000) },
            async { db.bufferDao().setOldestFetchedTime(buffer.id, 1_000) },
            async { db.bufferDao().markHistoryComplete(buffer.id) },
        ).awaitAll()

        val updated = db.bufferDao().observeById(buffer.id)!!
        assertEquals(true, updated.pinned)
        assertEquals(true, updated.muted)
        assertEquals("topic", updated.topic)
        assertEquals("setter", updated.topicSetBy)
        assertEquals(true, updated.joined)
        assertEquals(4_000L, updated.readMarkerTime)
        assertEquals(1_000L, updated.oldestFetchedTime)
        assertEquals(true, updated.historyComplete)
    }
}
