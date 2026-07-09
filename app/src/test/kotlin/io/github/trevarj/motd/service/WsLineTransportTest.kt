package io.github.trevarj.motd.service

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Framing tests for [WsLineTransport] against a real MockWebServer WebSocket (no TLS). Validates the
 * IRCv3 `text.ircv3.net` contract: one IRC line per text frame, no trailing CRLF on send, inbound
 * frames surface as bare lines, and a server-side close completes the incoming flow.
 */
class WsLineTransportTest {

    private lateinit var server: MockWebServer

    // Captures what the client sent and lets the server push frames back.
    private val received = CopyOnWriteArrayList<String>()
    @Volatile private var serverSocket: WebSocket? = null
    private val opened = CountDownLatch(1)

    @Before
    fun setUp() {
        server = MockWebServer()
        val serverListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                serverSocket = webSocket
                opened.countDown()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                received.add(text)
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Complete the close handshake so MockWebServer's queue drains on shutdown.
                webSocket.close(code, null)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        server.start()
    }

    @After
    fun tearDown() {
        // Best-effort: the close handshake may still be draining; don't let cleanup fail the test.
        runCatching { server.shutdown() }
    }

    private fun wsUrl(): String = "ws://${server.hostName}:${server.port}/"

    @Test
    fun `inbound frames surface as bare IRC lines`() = runBlocking {
        val transport = WsLineTransport(url = wsUrl())
        transport.connect()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        withTimeout(5_000) {
            transport.incoming.test {
                // Server sends one IRC line per WS text frame (no CRLF, per text.ircv3.net).
                serverSocket!!.send("PING abc")
                serverSocket!!.send(":srv 001 me :Welcome")
                assertEquals("PING abc", awaitItem())
                assertEquals(":srv 001 me :Welcome", awaitItem())
                // A lenient server sending a trailing CRLF is still stripped to a bare line.
                serverSocket!!.send("NOTICE * :hi\r\n")
                assertEquals("NOTICE * :hi", awaitItem())
            }
        }
        transport.close()
    }

    @Test
    fun `send transmits one WS text frame per line without CRLF`() = runBlocking {
        val transport = WsLineTransport(url = wsUrl())
        transport.connect()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        transport.send("NICK motd")
        transport.send("USER motd 0 * :motd")
        // Any caller-supplied CRLF is stripped so the frame carries exactly the IRC line.
        transport.send("JOIN #a\r\n")

        // Poll until the server observed all three frames.
        withTimeout(5_000) {
            withContext(Dispatchers.IO) {
                while (received.size < 3) Thread.sleep(10)
            }
        }
        assertEquals(listOf("NICK motd", "USER motd 0 * :motd", "JOIN #a"), received.toList())
        transport.close()
    }

    @Test
    fun `server close completes the incoming flow`() = runBlocking {
        val transport = WsLineTransport(url = wsUrl())
        transport.connect()
        assertTrue(opened.await(5, TimeUnit.SECONDS))

        withTimeout(5_000) {
            transport.incoming.test {
                serverSocket!!.send("PING x")
                assertEquals("PING x", awaitItem())
                serverSocket!!.close(1000, "bye")
                awaitComplete()
            }
        }
        transport.close()
    }
}
