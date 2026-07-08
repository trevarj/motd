package io.github.trevarj.motd.irc.transport

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Framing/line-splitting tests against a real localhost ServerSocket (no TLS).
 * Validates: EOF completes the flow, multiple lines are split on CRLF, and
 * outbound sends have CRLF appended.
 */
class OkioLineTransportTest {

    private lateinit var server: ServerSocket
    private var port = 0

    @Before
    fun setUp() {
        server = ServerSocket(0)
        port = server.localPort
    }

    @After
    fun tearDown() {
        try {
            server.close()
        } catch (_: Exception) {
        }
    }

    @Test
    fun `splits incoming lines on CRLF and completes on EOF`() = runBlocking {
        // Accept in the background, push two lines, then close (EOF).
        val serverJob = launch(Dispatchers.IO) {
            val conn = server.accept()
            val out = PrintWriter(conn.getOutputStream())
            out.print("PING abc\r\n")
            out.print(":srv 001 me :Welcome\r\n")
            out.flush()
            conn.close()
        }

        val transport = OkioLineTransport("127.0.0.1", port, tls = false)
        transport.connect()

        withTimeout(5_000) {
            transport.incoming.test {
                assertEquals("PING abc", awaitItem())
                assertEquals(":srv 001 me :Welcome", awaitItem())
                awaitComplete() // clean EOF
            }
        }
        transport.close()
        serverJob.join()
    }

    @Test
    fun `handles line split across multiple socket writes`() = runBlocking {
        val serverJob = launch(Dispatchers.IO) {
            val conn = server.accept()
            val os = conn.getOutputStream()
            // Write a single logical line in two chunks with no CRLF between.
            os.write("PRIVMSG #a :hel".toByteArray())
            os.flush()
            Thread.sleep(50)
            os.write("lo world\r\n".toByteArray())
            os.flush()
            conn.close()
        }

        val transport = OkioLineTransport("127.0.0.1", port, tls = false)
        transport.connect()

        withTimeout(5_000) {
            transport.incoming.test {
                assertEquals("PRIVMSG #a :hello world", awaitItem())
                awaitComplete()
            }
        }
        transport.close()
        serverJob.join()
    }

    @Test
    fun `send appends CRLF`() = runBlocking {
        var received = ""
        val serverJob = launch(Dispatchers.IO) {
            val conn: Socket = server.accept()
            val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
            received = reader.readLine() ?: ""
            conn.close()
        }

        val transport = OkioLineTransport("127.0.0.1", port, tls = false)
        transport.connect()
        transport.send("NICK motd")
        // Give the server time to read the line.
        withContext(Dispatchers.IO) { serverJob.join() }
        transport.close()

        // readLine strips the CRLF; if CRLF weren't appended, readLine would block on EOF/return null.
        assertEquals("NICK motd", received)
    }

    @Test
    fun `multiple concurrent sends are serialized correctly`() = runBlocking {
        val lines = mutableListOf<String>()
        val serverJob = launch(Dispatchers.IO) {
            val conn = server.accept()
            val reader = BufferedReader(InputStreamReader(conn.getInputStream()))
            repeat(3) {
                val l = reader.readLine() ?: return@repeat
                lines.add(l)
            }
            conn.close()
        }

        val transport = OkioLineTransport("127.0.0.1", port, tls = false)
        transport.connect()
        // Launch concurrent sends; the Mutex must keep each line intact.
        val a = launch { transport.send("AAA") }
        val b = launch { transport.send("BBB") }
        val c = launch { transport.send("CCC") }
        a.join(); b.join(); c.join()
        withContext(Dispatchers.IO) { serverJob.join() }
        transport.close()

        assertEquals(setOf("AAA", "BBB", "CCC"), lines.toSet())
    }
}
