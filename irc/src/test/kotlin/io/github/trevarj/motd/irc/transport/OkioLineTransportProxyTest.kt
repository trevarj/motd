package io.github.trevarj.motd.irc.transport

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.DataInputStream
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket

/**
 * Proves the SOCKS5 substrate (plans/20 Phase 1): dialing [OkioLineTransport] through a `Proxy`
 * MUST send the destination to the proxy as a DOMAIN NAME (ATYP=0x03), never a pre-resolved IP.
 * That is the leak-free / remote-DNS / `.onion`-capable behavior — Java's SOCKS impl only performs
 * remote resolution for an UNRESOLVED `InetSocketAddress`, which the transport builds via
 * `createUnresolved` on the proxied path.
 *
 * A tiny in-process SOCKS5 server parses the CONNECT request, records the destination type + host,
 * and then plays a fake IRC server so the transport's `incoming` flow is exercised end-to-end.
 */
class OkioLineTransportProxyTest {

    private lateinit var proxy: ServerSocket
    private var proxyPort = 0

    // Captured from the CONNECT request.
    @Volatile private var destAtyp: Int = -1
    @Volatile private var destHost: String = ""
    @Volatile private var destPort: Int = -1

    @Before
    fun setUp() {
        proxy = ServerSocket(0)
        proxyPort = proxy.localPort
    }

    @After
    fun tearDown() {
        runCatching { proxy.close() }
    }

    @Test
    fun `dials the proxy with an UNRESOLVED domain destination (remote DNS, no leak)`() = runBlocking {
        val serverJob = launch(Dispatchers.IO) {
            val conn = proxy.accept()
            val din = DataInputStream(conn.getInputStream())
            val out = conn.getOutputStream()

            // --- SOCKS5 greeting: VER, NMETHODS, METHODS... ---
            val ver = din.read()
            val nMethods = din.read()
            repeat(nMethods) { din.read() }
            // Reply: VER=5, METHOD=0 (no auth).
            out.write(byteArrayOf(0x05, 0x00)); out.flush()
            assertEquals(0x05, ver)

            // --- CONNECT request: VER, CMD, RSV, ATYP, ADDR, PORT ---
            din.read() // VER
            din.read() // CMD (1 = CONNECT)
            din.read() // RSV
            val atyp = din.read()
            destAtyp = atyp
            when (atyp) {
                0x03 -> { // DOMAINNAME: len byte then that many host bytes
                    val len = din.read()
                    val host = ByteArray(len)
                    din.readFully(host)
                    destHost = String(host, Charsets.US_ASCII)
                }
                0x01 -> { // IPv4 (would mean a LOCAL DNS leak — the failure case)
                    val ip = ByteArray(4); din.readFully(ip)
                    destHost = ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
                }
            }
            destPort = (din.read() shl 8) or din.read()

            // Reply success: VER, REP=0, RSV, ATYP=IPv4, BND.ADDR 0.0.0.0, BND.PORT 0.
            out.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush()

            // Now act as the tunneled IRC server: push a banner line, then EOF.
            val w = PrintWriter(out)
            w.print(":irc.example.com 001 me :Welcome\r\n")
            w.flush()
            conn.close()
        }

        // Dial a name that would resolve to nothing locally; the proxy must receive it as a DOMAIN.
        val transport = OkioLineTransport(
            host = "bnc.example.onion",
            port = 6697,
            tls = false,
            proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", proxyPort)),
        )
        transport.connect()

        withTimeout(5_000) {
            transport.incoming.test {
                assertEquals(":irc.example.com 001 me :Welcome", awaitItem())
                awaitComplete()
            }
        }
        transport.close()
        serverJob.join()

        // The destination reached the proxy as a DOMAIN NAME (remote DNS), not a resolved IP.
        assertEquals("ATYP must be DOMAINNAME (0x03), else DNS leaked locally", 0x03, destAtyp)
        assertEquals("bnc.example.onion", destHost)
        assertEquals(6697, destPort)
    }
}
