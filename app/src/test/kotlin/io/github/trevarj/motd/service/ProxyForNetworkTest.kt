package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.ObfsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * [proxyForNetwork] builds the exact [Proxy] a network row's obfuscation config implies
 * (plans/20 Phase 1), leaving the destination UNRESOLVED so DNS is done remotely by the proxy.
 */
class ProxyForNetworkTest {

    private fun addr(p: Proxy) = p.address() as InetSocketAddress

    @Test
    fun `NONE and null are direct (no proxy)`() {
        assertNull(proxyForNetwork(ObfsMode.NONE, "1.2.3.4", 1080))
        assertNull(proxyForNetwork(null, "1.2.3.4", 1080))
    }

    @Test
    fun `SOCKS5 builds a SOCKS proxy with an unresolved host`() {
        val p = proxyForNetwork(ObfsMode.SOCKS5, "127.0.0.1", 1080)!!
        assertEquals(Proxy.Type.SOCKS, p.type())
        val a = addr(p)
        // Unresolved: no local DNS in the InetSocketAddress constructor (leak-free / .onion-capable).
        assertTrue("destination must be unresolved (remote DNS)", a.isUnresolved)
        assertEquals("127.0.0.1", a.hostString)
        assertEquals(1080, a.port)
    }

    @Test
    fun `EMBEDDED_REALITY maps to a SOCKS5 proxy for Phase 1`() {
        val p = proxyForNetwork(ObfsMode.EMBEDDED_REALITY, "127.0.0.1", 1080)!!
        assertEquals(Proxy.Type.SOCKS, p.type())
        assertEquals(1080, addr(p).port)
    }

    @Test
    fun `TOR pins Orbot's 127_0_0_1 9050 and ignores host or port`() {
        val p = proxyForNetwork(ObfsMode.TOR, null, null)!!
        val a = addr(p)
        assertEquals(Proxy.Type.SOCKS, p.type())
        assertTrue(a.isUnresolved)
        assertEquals(ORBOT_SOCKS_HOST, a.hostString)
        assertEquals(ORBOT_SOCKS_PORT, a.port)
    }

    @Test
    fun `SOCKS5 with a missing or invalid endpoint is a configuration failure`() {
        assertNull(proxyForNetwork(ObfsMode.SOCKS5, null, 1080))
        assertNull(proxyForNetwork(ObfsMode.SOCKS5, "  ", 1080))
        assertNull(proxyForNetwork(ObfsMode.SOCKS5, "127.0.0.1", null))
        assertNull(proxyForNetwork(ObfsMode.SOCKS5, "127.0.0.1", 0))
        assertNull(proxyForNetwork(ObfsMode.SOCKS5, "127.0.0.1", 70000))

        assertEquals("SOCKS5 proxy host is required", proxyConfigurationErrorForNetwork(ObfsMode.SOCKS5, null, 1080))
        assertEquals("SOCKS5 proxy host is required", proxyConfigurationErrorForNetwork(ObfsMode.EMBEDDED_REALITY, " ", 1080))
        assertEquals("SOCKS5 proxy port must be between 1 and 65535", proxyConfigurationErrorForNetwork(ObfsMode.SOCKS5, "127.0.0.1", 0))
        assertEquals("SOCKS5 proxy port must be between 1 and 65535", proxyConfigurationErrorForNetwork(ObfsMode.SOCKS5, "127.0.0.1", null))
    }

    @Test
    fun `configured proxy and WebSocket are rejected rather than bypassing the proxy`() {
        val proxy = proxyForNetwork(ObfsMode.SOCKS5, "127.0.0.1", 1080)!!
        val torProxy = proxyForNetwork(ObfsMode.TOR, null, null)!!

        assertEquals(
            "WebSocket transport cannot be used with a SOCKS5 or Tor proxy",
            transportConfigurationError("wss://bnc.example.test/", proxy, null),
        )
        assertEquals(
            "WebSocket transport cannot be used with a SOCKS5 or Tor proxy",
            transportConfigurationError("wss://bnc.example.test/", torProxy, null),
        )
        // The malformed proxy error wins even though it has no Proxy instance to pass to WSS.
        assertEquals(
            "SOCKS5 proxy host is required",
            transportConfigurationError(
                "wss://bnc.example.test/",
                null,
                proxyConfigurationErrorForNetwork(ObfsMode.SOCKS5, null, 1080),
            ),
        )
        assertNull(transportConfigurationError(null, proxy, null))
        assertNull(transportConfigurationError("wss://bnc.example.test/", null, null))
    }
}
