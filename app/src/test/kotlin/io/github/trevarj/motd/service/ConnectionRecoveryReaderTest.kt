package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.buffer
import io.github.trevarj.motd.data.db.inMemoryDb
import io.github.trevarj.motd.data.db.network
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConnectionRecoveryReaderTest {
    private lateinit var db: MotdDatabase
    private lateinit var reader: ConnectionRecoveryReader
    private var networkId = 0L

    @Before
    fun setUp() = runTest {
        db = inMemoryDb()
        reader = ConnectionRecoveryReader(db.bufferDao())
        networkId = db.networkDao().insert(network())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun restoresOnlyChannelsStillMarkedJoinedInDurableState() = runTest {
        db.bufferDao().insert(buffer(networkId, "#joined").copy(displayName = "#Joined", joined = true))
        db.bufferDao().insert(buffer(networkId, "#parted").copy(joined = false))
        db.bufferDao().insert(
            buffer(networkId, "alice").copy(type = BufferType.QUERY, joined = true),
        )

        assertEquals(listOf("#Joined"), reader.joinedChannels(networkId))
    }
}
