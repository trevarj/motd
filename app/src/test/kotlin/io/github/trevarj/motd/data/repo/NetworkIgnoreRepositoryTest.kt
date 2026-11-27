package io.github.trevarj.motd.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkIgnoreDao
import io.github.trevarj.motd.data.db.NetworkIgnoreEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.irc.proto.IrcCaseMapping
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.Prefix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NetworkIgnoreRepositoryTest {
    private lateinit var db: MotdDatabase
    private lateinit var dao: NetworkIgnoreDao
    private lateinit var cache: NetworkIgnoreCache
    private lateinit var repository: NetworkIgnoreRepositoryImpl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MotdDatabase::class.java).allowMainThreadQueries().build()
        dao = db.networkIgnoreDao()
        cache = NetworkIgnoreCache(dao)
        repository = NetworkIgnoreRepositoryImpl(db, dao, db.bufferDao(), cache)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `RFC1459 disable clears all enabled equivalents without changing broader rules or other networks`() =
        runTest {
            val network = network("first")
            val other = network("second")
            val first = seed(network, "[lice!*@*")
            val second = seed(network, "{Lice!*@*")
            val disabled = seed(network, "[LICE!*@*", enabled = false)
            val wildcard = seed(network, "?lice!*@*")
            val user = seed(network, "[lice!ident@*")
            val host = seed(network, "[lice!*@*.example.net")
            val unrelated = seed(other, "[lice!*@*")
            val rules = IrcIdentityRules(IrcCaseMapping.Rfc1459)
            assertTrue(hasExactNickIgnore(cache.enabledForNetwork(network), "{lice", rules))
            val otherCache = cache.enabledForNetwork(other)

            repository.setExactNickIgnored(network, "{lice", rules, ignored = false).getOrThrow()

            assertEquals(
                listOf(first.copy(enabled = false), second.copy(enabled = false), disabled, wildcard, user, host),
                dao.allForNetwork(network),
            )
            assertEquals(listOf(wildcard, user, host), cache.enabledForNetwork(network))
            assertEquals(listOf(unrelated), dao.allForNetwork(other))
            assertSame(otherCache, cache.enabledForNetwork(other))
        }

    @Test
    fun `enable reuses a disabled RFC1459 equivalent and is idempotent once one is enabled`() =
        runTest {
            val network = network()
            val disabled = seed(network, "[lice!*@*", enabled = false)
            val duplicate = seed(network, "{LICE!*@*", enabled = false)
            val rules = IrcIdentityRules(IrcCaseMapping.Rfc1459)
            assertFalse(hasExactNickIgnore(cache.enabledForNetwork(network), "{lice", rules))

            repository.setExactNickIgnored(network, "{lice", rules, ignored = true).getOrThrow()

            val enabled = disabled.copy(enabled = true)
            val enabledCache = cache.enabledForNetwork(network)
            assertEquals(listOf(enabled), enabledCache)
            repository.setExactNickIgnored(network, "{LICE", rules, ignored = true).getOrThrow()
            assertEquals(listOf(enabled, duplicate), dao.allForNetwork(network))
            assertSame(enabledCache, cache.enabledForNetwork(network))
        }

    @Test
    fun `ASCII casemap keeps bracket nick spellings distinct when creating and disabling an exact rule`() =
        runTest {
            val network = network()
            val existing = seed(network, "[lice!*@*")
            val rules = IrcIdentityRules(IrcCaseMapping.Ascii)

            repository.setExactNickIgnored(network, "{lice", rules, ignored = true).getOrThrow()

            val created = dao.allForNetwork(network).last()
            assertEquals("{lice!*@*", created.pattern)
            assertTrue(created.enabled)
            repository.setExactNickIgnored(network, "{LICE", rules, ignored = false).getOrThrow()
            assertEquals(listOf(existing, created.copy(enabled = false)), dao.allForNetwork(network))
        }

    @Test
    fun `unicode nick actions reuse retained masks under the IRC casemap`() =
        runTest {
            val network = network()
            val rules = IrcIdentityRules(IrcCaseMapping.Rfc1459)
            val nick = "ÉLODIE{"
            val source = Prefix(nick, "ident", "example.net")
            repository.addIgnore(network, "Élodie[").getOrThrow()
            val retained = dao.allForNetwork(network).single()
            assertEquals("Élodie[!*@*", retained.pattern)
            assertTrue(hasExactNickIgnore(cache.enabledForNetwork(network), nick, rules))
            assertEquals(DROP_IGNORED_MESSAGE, incomingIgnoreSignals(cache.enabledForNetwork(network), source, rules))

            repository.setExactNickIgnored(network, nick, rules, ignored = false).getOrThrow()
            assertEquals(listOf(retained.copy(enabled = false)), dao.allForNetwork(network))
            assertEquals(0, incomingIgnoreSignals(cache.enabledForNetwork(network), source, rules))
            repository.setExactNickIgnored(network, nick, rules, ignored = true).getOrThrow()
            assertEquals(listOf(retained), dao.allForNetwork(network))
            assertEquals(DROP_IGNORED_MESSAGE, incomingIgnoreSignals(cache.enabledForNetwork(network), source, rules))

            val differentNick = "élodie{"
            assertFalse(hasExactNickIgnore(cache.enabledForNetwork(network), differentNick, rules))
            assertEquals(0, incomingIgnoreSignals(cache.enabledForNetwork(network), source.copy(nick = differentNick), rules))
            repository.setExactNickIgnored(network, differentNick, rules, ignored = true).getOrThrow()
            assertEquals(listOf(retained.pattern, "$differentNick!*@*"), dao.allForNetwork(network).map { it.pattern })
        }

    @Test
    fun `exact actions reject glob masks separators and channels without changing persisted rules`() =
        runTest {
            val network = network()
            val existing = seed(network, "Alice!*@*")
            val rules = IrcIdentityRules(advertisedChanTypes = "#[")
            for (nick in listOf("Al*", "Al?ce", "Alice!user", "Alice@host", "Alice,Bob", ":Alice", "Ali\tce", "[chat")) {
                assertTrue(nick, repository.setExactNickIgnored(network, nick, rules, ignored = true).isFailure)
                assertTrue(nick, repository.setExactNickIgnored(network, nick, rules, ignored = false).isFailure)
            }
            assertEquals(listOf(existing), dao.allForNetwork(network))
        }

    @Test
    fun `cancellation rolls back partially disabled equivalents and propagates to the caller`() =
        runTest {
            val network = network()
            val first = seed(network, "[lice!*@*")
            val second = seed(network, "{lice!*@*")
            val cancelled = CancellationException("Interrupted disable")
            val backingDao = dao
            val cancellingDao =
                object : NetworkIgnoreDao by backingDao {
                    override suspend fun setEnabled(
                        id: Long,
                        enabled: Boolean,
                    ): Int {
                        if (id == second.id) throw cancelled
                        return backingDao.setEnabled(id, enabled)
                    }
                }
            val repo = NetworkIgnoreRepositoryImpl(db, cancellingDao, db.bufferDao(), cache)

            val thrown = runCatching { repo.setExactNickIgnored(network, "{lice", IrcIdentityRules(), ignored = false) }.exceptionOrNull()

            assertTrue(thrown is CancellationException)
            assertEquals(cancelled.message, thrown?.message)
            assertEquals(listOf(first, second), dao.allForNetwork(network))
        }

    @Test
    fun `saving the same normalized selective mask replaces and enables only its mode`() =
        runTest {
            val network = network()
            val other = network("other")
            val otherCache = cache.enabledForNetwork(other)
            assertTrue(cache.enabledForNetwork(network).isEmpty())

            repository.addIgnore(network, " [lice ", dropMessages = false, suppressedSignals = SUPPRESS_UNREAD).getOrThrow()
            val selective = cache.enabledForNetwork(network).single()
            assertEquals("[lice!*@*", selective.pattern)
            assertEquals(SUPPRESS_UNREAD, selective.suppressedSignals)
            val rules = IrcIdentityRules(IrcCaseMapping.Rfc1459)
            assertFalse(hasExactNickIgnore(listOf(selective), "{lice", rules))

            repository.setExactNickIgnored(network, "[lice", rules, ignored = true).getOrThrow()
            val hard = cache.enabledForNetwork(network).single { it.dropMessages }
            assertEquals(selective.pattern, hard.pattern)
            assertTrue(hasExactNickIgnore(cache.enabledForNetwork(network), "{lice", rules))
            repository.setExactNickIgnored(network, "{LICE", rules, ignored = false).getOrThrow()
            assertEquals(listOf(selective), cache.enabledForNetwork(network))

            repository.setIgnoreEnabled(selective.id, false)
            assertTrue(cache.enabledForNetwork(network).isEmpty())
            repository
                .addIgnore(network, "[lice!*@*", dropMessages = false, suppressedSignals = SUPPRESS_NOTIFICATIONS)
                .getOrThrow()

            val updated = cache.enabledForNetwork(network).single()
            assertFalse(updated.dropMessages)
            assertEquals(SUPPRESS_NOTIFICATIONS, updated.suppressedSignals)
            assertEquals(setOf(hard.copy(enabled = false), updated), dao.allForNetwork(network).toSet())
            assertSame(otherCache, cache.enabledForNetwork(other))
        }

    @Test
    fun `invalid selective bits or hard suppression flags cannot create a rule`() =
        runTest {
            val network = network()
            for (invalid in listOf(0, 8, SUPPRESS_UNREAD or 8, DROP_IGNORED_MESSAGE)) {
                assertTrue(repository.addIgnore(network, "alice", dropMessages = false, suppressedSignals = invalid).isFailure)
            }
            assertTrue(repository.addIgnore(network, "alice", suppressedSignals = SUPPRESS_UNREAD).isFailure)
            assertTrue(dao.allForNetwork(network).isEmpty())
        }

    private suspend fun network(name: String = "network"): Long =
        db.networkDao().insert(
            NetworkEntity(
                name = name,
                role = NetworkRole.DIRECT,
                host = "h",
                port = 6697,
                nick = "me",
                username = "me",
                realname = "Me",
            ),
        )

    private suspend fun seed(
        networkId: Long,
        pattern: String,
        enabled: Boolean = true,
    ): NetworkIgnoreEntity {
        val row = NetworkIgnoreEntity(networkId = networkId, pattern = pattern, enabled = enabled, createdAt = 1)
        return row.copy(id = dao.upsert(row))
    }
}
