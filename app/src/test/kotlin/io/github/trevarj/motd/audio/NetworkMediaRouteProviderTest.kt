package io.github.trevarj.motd.audio

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpServer
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.data.prefs.CertTrustStore
import io.github.trevarj.motd.service.LocalSocksEngine
import io.github.trevarj.motd.service.LocalSocksProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class NetworkMediaRouteProviderTest {
    private lateinit var db: MotdDatabase
    private lateinit var provider: NetworkMediaRouteProvider

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), MotdDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        provider = NetworkMediaRouteProvider(db, LocalSocksProvider.forTest { FailingEngine }, NoPinsTrustStore)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `unknown networks and bouncer children without a physical endpoint fail closed`() =
        runTest {
            val parent = db.networkDao().insert(network())
            val orphan = db.networkDao().insert(network().copy(role = NetworkRole.BOUNCER_CHILD, parentId = parent))
            val withoutParent = db.networkDao().insert(network().copy(role = NetworkRole.BOUNCER_CHILD))
            db.networkDao().deleteNetworkRows(listOf(parent))

            assertNull(provider.routeForNetwork(9_999))
            assertNull(provider.routeForNetwork(orphan))
            assertNull(provider.routeForNetwork(withoutParent))
        }

    @Test
    fun `bouncer child follows parent transport changes and cannot bypass a failed proxy`() =
        runTest {
            val parent = db.networkDao().insert(network())
            val child = db.networkDao().insert(network().copy(role = NetworkRole.BOUNCER_CHILD, parentId = parent))
            val requests = AtomicInteger()
            val server =
                HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                    createContext("/") { exchange ->
                        requests.incrementAndGet()
                        exchange.sendResponseHeaders(204, -1)
                        exchange.close()
                    }
                    start()
                }
            try {
                val url = "http://127.0.0.1:${server.address.port}/"
                requireNotNull(provider.routeForNetwork(child)).use { route ->
                    route.open(url).apply { assertEquals(204, responseCode) }.disconnect()
                }
                db.networkDao().update(network().copy(id = parent, obfsMode = ObfsMode.EMBEDDED_REALITY))
                requireNotNull(provider.routeForNetwork(child)).use { route ->
                    assertNotNull(route.proxyError)
                    assertThrows(IOException::class.java) { route.open(url) }
                }
                assertEquals(1, requests.get())
            } finally {
                server.stop(0)
            }
        }

    @Test
    fun `bouncer child filehost auth uses the same selected-network identity as IRC`() {
        val header = network().basicAuthorizationHeader(childNetworkSelector = "libera")

        assertEquals("trev/libera:password", decodeBasic(header))
    }

    @Test
    fun `non plain SASL does not synthesize HTTP basic credentials`() {
        assertNull(network().copy(saslMechanism = "EXTERNAL").basicAuthorizationHeader("libera"))
    }

    @Test
    fun `route only exposes bouncer credentials to explicitly authenticated requests`() {
        val received = mutableListOf<String?>()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    received += exchange.requestHeaders.getFirst("Authorization")
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                start()
            }
        val route =
            NetworkMediaRoute(
                networkId = 1L,
                endpoint = network(),
                proxy = null,
                proxyError = null,
                authorizationHeader = "Basic private",
            )

        try {
            val url = "http://127.0.0.1:${server.address.port}/"
            route.open(url).apply { responseCode }.disconnect()
            route.open(url, authenticated = true).apply { responseCode }.disconnect()

            assertEquals(listOf(null, "Basic private"), received)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a broken proxy cannot open a direct media connection`() {
        val requests = AtomicInteger()
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/") { exchange ->
                    requests.incrementAndGet()
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }
                start()
            }
        val route =
            NetworkMediaRoute(
                networkId = 1L,
                endpoint = network(),
                proxy = null,
                proxyError = "SOCKS unavailable",
                authorizationHeader = "Basic private",
            )
        try {
            assertThrows(IOException::class.java) {
                route.open("http://127.0.0.1:${server.address.port}/").apply { responseCode }.disconnect()
            }
            assertEquals(0, requests.get())
        } finally {
            server.stop(0)
        }
    }

    private fun network() =
        NetworkEntity(
            name = "Soju",
            role = NetworkRole.BOUNCER_ROOT,
            host = "irc.example",
            port = 6697,
            nick = "trev",
            username = "trev",
            realname = "trev",
            saslMechanism = "PLAIN",
            saslUser = "trev",
            saslPassword = "password",
        )

    private fun decodeBasic(header: String?): String {
        val encoded = requireNotNull(header).removePrefix("Basic ")
        return Base64.getDecoder().decode(encoded).toString(Charsets.UTF_8)
    }

    private object FailingEngine : LocalSocksEngine {
        override fun start(configJson: String): Result<Int> = Result.failure(IllegalStateException("no embedded core in unit tests"))

        override fun stop() = Unit
    }

    private object NoPinsTrustStore : CertTrustStore {
        override suspend fun pinnedFor(
            host: String,
            port: Int,
        ): String? = null

        override suspend fun isPinned(
            host: String,
            port: Int,
            sha256: String,
        ): Boolean = false

        override suspend fun pin(
            host: String,
            port: Int,
            sha256: String,
        ) = Unit

        override suspend fun unpin(
            host: String,
            port: Int,
        ) = Unit
    }
}
