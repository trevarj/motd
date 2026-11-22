package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkIgnoreDao
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkIgnoreCacheTest {
    @Test
    fun `repeated message lookups load once and invalidation reloads`() =
        runTest {
            val dao = CountingIgnoreDao()
            val cache = NetworkIgnoreCache(dao)

            repeat(100) { assertEquals("alice!*@*", cache.enabledForNetwork(1).single().pattern) }
            assertEquals(1, dao.loads)

            dao.pattern = "bob!*@*"
            cache.invalidate(1)
            assertEquals("bob!*@*", cache.enabledForNetwork(1).single().pattern)
            assertEquals(2, dao.loads)
        }

    @Test
    fun `invalidating while a lookup is suspended cannot leave a stale snapshot`() =
        runTest {
            val release = CompletableDeferred<Unit>()
            val dao = CountingIgnoreDao().apply { beforeReturning = { release.await() } }
            val cache = NetworkIgnoreCache(dao)
            val inFlight = async(start = CoroutineStart.UNDISPATCHED) { cache.enabledForNetwork(1) }

            dao.beforeReturning = null
            dao.pattern = "bob!*@*"
            cache.invalidate(1)
            release.complete(Unit)

            inFlight.await()
            assertEquals("bob!*@*", cache.enabledForNetwork(1).single().pattern)
            assertEquals(2, dao.loads)
        }

    private class CountingIgnoreDao : NetworkIgnoreDao {
        var loads = 0
        var pattern = "alice!*@*"
        var beforeReturning: (suspend () -> Unit)? = null

        override fun observeForNetwork(networkId: Long): Flow<List<NetworkIgnoreEntity>> = flowOf(emptyList())

        override suspend fun allForNetwork(networkId: Long): List<NetworkIgnoreEntity> = emptyList()

        override suspend fun enabledForNetwork(networkId: Long): List<NetworkIgnoreEntity> {
            loads++
            val snapshot = listOf(NetworkIgnoreEntity(1, networkId, pattern, enabled = true, createdAt = 1))
            beforeReturning?.invoke()
            return snapshot
        }

        override suspend fun networkIdFor(id: Long): Long? = 1

        override suspend fun upsert(ignore: NetworkIgnoreEntity): Long = ignore.id

        override suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ): Int = 1

        override suspend fun delete(id: Long): Int = 1
    }
}
