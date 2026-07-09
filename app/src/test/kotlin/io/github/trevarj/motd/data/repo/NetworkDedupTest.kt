package io.github.trevarj.motd.data.repo

import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Duplicate-connection prevention (plans/05, plans/16): adding the same server twice must not
 * insert a second [NetworkEntity] (which would spawn a second actor/socket). Covers the
 * per-role identity key and the idempotent soju-child import.
 */
class NetworkDedupTest {

    /** In-memory NetworkDao: only the methods addNetwork touches are backed; rest throw. */
    private class InMemoryNetworkDao : NetworkDao {
        val rows = LinkedHashMap<Long, NetworkEntity>()
        private var nextId = 1L

        override suspend fun insert(n: NetworkEntity): Long {
            val id = nextId++
            rows[id] = n.copy(id = id)
            return id
        }

        override suspend fun allNow(): List<NetworkEntity> = rows.values.toList()
        override suspend fun byId(id: Long): NetworkEntity? = rows[id]
        override suspend fun childrenOf(rootId: Long): List<NetworkEntity> =
            rows.values.filter { it.parentId == rootId }

        override fun observeAll(): Flow<List<NetworkEntity>> = flowOf(rows.values.toList())
        override suspend fun connectable(): List<NetworkEntity> = rows.values.filter { it.autoConnect }
        override suspend fun update(n: NetworkEntity) { rows[n.id] = n }
        override suspend fun delete(n: NetworkEntity) { rows.remove(n.id) }
    }

    private fun direct(host: String, port: Int = 6697, nick: String = "motd") = NetworkEntity(
        name = host, role = NetworkRole.DIRECT,
        host = host, port = port, nick = nick, username = nick, realname = nick,
    )

    private fun root(host: String, saslUser: String?) = NetworkEntity(
        name = host, role = NetworkRole.BOUNCER_ROOT,
        host = host, port = 6697, nick = "motd", username = "motd", realname = "motd",
        saslMechanism = "PLAIN", saslUser = saslUser,
    )

    private fun child(parentId: Long, netId: String, host: String = "irc.child.org") = NetworkEntity(
        name = netId, role = NetworkRole.BOUNCER_CHILD, parentId = parentId, bouncerNetId = netId,
        host = host, port = 6697, nick = "motd", username = "motd", realname = "motd",
    )

    @Test
    fun `adding the same direct server twice returns the existing id and no second row`() = runBlocking {
        val dao = InMemoryNetworkDao()
        val repo = NetworkRepositoryImpl(dao)
        val first = repo.addNetwork(direct("irc.libera.chat"))
        val second = repo.addNetwork(direct("irc.libera.chat"))
        assertEquals(first, second)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `host normalization dedups trailing-dot and case variants`() = runBlocking {
        val dao = InMemoryNetworkDao()
        val repo = NetworkRepositoryImpl(dao)
        val a = repo.addNetwork(direct("irc.libera.chat"))
        val b = repo.addNetwork(direct("IRC.Libera.Chat."))
        assertEquals(a, b)
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `different nick on the same server is a distinct network`() = runBlocking {
        val dao = InMemoryNetworkDao()
        val repo = NetworkRepositoryImpl(dao)
        repo.addNetwork(direct("irc.libera.chat", nick = "alice"))
        repo.addNetwork(direct("irc.libera.chat", nick = "bob"))
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `different port is a distinct network`() = runBlocking {
        val dao = InMemoryNetworkDao()
        val repo = NetworkRepositoryImpl(dao)
        repo.addNetwork(direct("irc.libera.chat", port = 6697))
        repo.addNetwork(direct("irc.libera.chat", port = 6667))
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `same bouncer account added twice reuses the root`() = runBlocking {
        val dao = InMemoryNetworkDao()
        val repo = NetworkRepositoryImpl(dao)
        val a = repo.addNetwork(root("bnc.example.org", saslUser = "acct"))
        val b = repo.addNetwork(root("bnc.example.org", saslUser = "acct"))
        assertEquals(a, b)
        assertEquals(1, dao.rows.size)
        // A different soju login on the same host is a distinct root.
        repo.addNetwork(root("bnc.example.org", saslUser = "other"))
        assertEquals(2, dao.rows.size)
    }

    @Test
    fun `bouncer child import is idempotent per (parent, netId)`() = runBlocking {
        val dao = InMemoryNetworkDao()
        val repo = NetworkRepositoryImpl(dao)
        val rootId = repo.addNetwork(root("bnc.example.org", saslUser = "acct"))
        val first = repo.addNetwork(child(rootId, netId = "42"))
        val second = repo.addNetwork(child(rootId, netId = "42", host = "different.host"))
        assertEquals(first, second)
        assertEquals(2, dao.rows.size) // root + one child
        // A different netId under the same root is a distinct child.
        repo.addNetwork(child(rootId, netId = "43"))
        assertEquals(3, dao.rows.size)
    }

    @Test
    fun `child netId collision across different roots stays distinct`() = runBlocking {
        val dao = InMemoryNetworkDao()
        val repo = NetworkRepositoryImpl(dao)
        val root1 = repo.addNetwork(root("bnc1.example.org", saslUser = "a"))
        val root2 = repo.addNetwork(root("bnc2.example.org", saslUser = "b"))
        repo.addNetwork(child(root1, netId = "1"))
        repo.addNetwork(child(root2, netId = "1"))
        assertEquals(4, dao.rows.size) // two roots + two children
    }
}
