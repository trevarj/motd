package io.github.trevarj.motd.audio

import com.sun.net.httpserver.HttpServer
import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

class NetworkMediaRouteProviderTest {
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
}
