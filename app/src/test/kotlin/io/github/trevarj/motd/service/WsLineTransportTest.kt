package io.github.trevarj.motd.service

import app.cash.turbine.test
import io.github.trevarj.motd.irc.transport.MAX_IRC_LINE_BYTES
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
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
    private val clientClosed = CountDownLatch(1)

    @Volatile private var clientCloseCode: Int? = null

    // Counts the frames the server observed so a missing frame fails fast instead of hanging.
    private val framesReceived = CountDownLatch(3)

    @Before
    fun setUp() {
        server = MockWebServer()
        val serverListener =
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: okhttp3.Response,
                ) {
                    serverSocket = webSocket
                    opened.countDown()
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    received.add(text)
                    framesReceived.countDown()
                }

                override fun onClosing(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    clientCloseCode = code
                    clientClosed.countDown()
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
    fun `inbound frames surface as bare IRC lines`() =
        runBlocking {
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
    fun `exact inbound byte limit is accepted`() =
        runBlocking {
            val transport = WsLineTransport(url = wsUrl())
            transport.connect()
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            val line = "é".repeat(MAX_IRC_LINE_BYTES / 2)

            withTimeout(5_000) {
                transport.incoming.test {
                    serverSocket!!.send(line)
                    assertEquals(line, awaitItem())
                }
            }
            transport.close()
        }

    @Test
    fun `oversized multibyte frame fails instead of being retained`() =
        runBlocking {
            val transport = WsLineTransport(url = wsUrl())
            transport.connect()
            assertTrue(opened.await(5, TimeUnit.SECONDS))

            withTimeout(5_000) {
                transport.incoming.test {
                    serverSocket!!.send("é".repeat(MAX_IRC_LINE_BYTES / 2 + 1))
                    assertTrue(awaitError() is IOException)
                }
            }
            assertTrue(clientClosed.await(5, TimeUnit.SECONDS))
            assertEquals(1009, clientCloseCode)
            transport.close()
        }

    @Test
    fun `stalled inbound consumer disconnects on bounded queue overflow`() =
        runBlocking {
            val transport = WsLineTransport(url = wsUrl())
            transport.connect()
            assertTrue(opened.await(5, TimeUnit.SECONDS))

            repeat(4_097) { serverSocket!!.send(":srv NOTICE * :$it") }

            assertTrue(clientClosed.await(10, TimeUnit.SECONDS))
            assertEquals(1013, clientCloseCode)
            transport.close()
        }

    @Test
    fun `send transmits one WS text frame per line without CRLF`() =
        runBlocking {
            val transport = WsLineTransport(url = wsUrl())
            transport.connect()
            assertTrue(opened.await(5, TimeUnit.SECONDS))

            transport.send("NICK motd")
            transport.send("USER motd 0 * :motd")
            transport.send("JOIN #a\r\n")

            // Wait until the server observed all three frames; a missing frame fails here with a message
            // instead of spinning until the JUnit/Gradle timeout.
            assertTrue("server did not observe 3 frames", framesReceived.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("NICK motd", "USER motd 0 * :motd", "JOIN #a"), received.toList())
            transport.close()
        }

    @Test
    fun `outbound line is complete and byte bounded`() =
        runBlocking {
            val transport = WsLineTransport(url = wsUrl())
            transport.connect()
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            val line = "x".repeat(600)

            transport.send(line)
            withTimeout(5_000) {
                while (received.isEmpty()) delay(10)
            }
            assertEquals(line, received.single())
            val failure = runCatching { transport.send("é".repeat(MAX_IRC_LINE_BYTES / 2 + 1)) }.exceptionOrNull()
            assertTrue(failure is IOException)
            transport.close()
        }

    @Test
    fun `server close completes the incoming flow`() =
        runBlocking {
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
