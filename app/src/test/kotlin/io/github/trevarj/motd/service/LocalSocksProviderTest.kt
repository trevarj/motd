package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ObfsMode
import io.github.trevarj.motd.obfs.VlessLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet6Address
import java.net.InetAddress

class LocalSocksProviderTest {
    private val link =
        VlessLink
            .parse(
                "vless://123e4567-e89b-12d3-a456-426614174000@edge.example:443?type=tcp&security=reality&sni=www.example.com&pbk=public-key&sid=abcd&fp=firefox",
            ).getOrThrow()

    @Test
    fun `shares one endpoint for leases of the same link`() {
        val first = FakeEngine(Result.success(11999))
        val provider = LocalSocksProvider.forTest { first }

        assertEquals(11999, provider.start(link).getOrThrow().port)
        assertEquals(11999, provider.start(link).getOrThrow().port)
        assertEquals(1, first.starts)
    }

    @Test
    fun `lease release stops shared core only after last release`() {
        val first = FakeEngine(Result.success(11999))
        val provider = LocalSocksProvider.forTest { first }

        val firstLease = provider.acquire(link).getOrThrow()
        val secondLease = provider.acquire(link).getOrThrow()

        firstLease.release()
        firstLease.release()
        assertEquals(0, first.stops)

        secondLease.release()
        assertEquals(1, first.stops)
    }

    @Test
    fun `invalid engine port fails closed`() {
        val provider = LocalSocksProvider.forTest { FakeEngine(Result.success(0)) }
        assertTrue(provider.start(link).isFailure)
    }

    @Test
    fun `stop clears all active endpoints`() {
        val first = FakeEngine(Result.success(11999))
        val second = FakeEngine(Result.success(12000))
        val engines = ArrayDeque(listOf(first, second))
        val provider = LocalSocksProvider.forTest { engines.removeFirst() }

        provider.start(link).getOrThrow()
        provider.start(link).getOrThrow()

        provider.stop()
        assertEquals(1, first.stops)
        assertEquals(0, second.stops)
    }

    @Test
    fun `independent links retain independent cores`() {
        val first = FakeEngine(Result.success(11001))
        val second = FakeEngine(Result.success(11002))
        val engines = ArrayDeque(listOf(first, second))
        val provider = LocalSocksProvider.forTest { engines.removeFirst() }
        val otherLink = link.copy(host = "other-edge.example")

        assertEquals(11001, provider.start(link).getOrThrow().port)
        assertEquals(11002, provider.start(otherLink).getOrThrow().port)
        assertEquals(0, first.stops)
        assertEquals(0, second.stops)

        provider.stop()
        assertEquals(1, first.stops)
        assertEquals(1, second.stops)
    }

    @Test
    fun `embedded links use loopback SOCKS without legacy proxy settings`() {
        for (uri in listOf(validLink, validWsLink)) {
            val engine = FakeEngine(Result.success(11001))
            val endpoint =
                NetworkEntity(
                    id = 1,
                    name = "soju",
                    role = NetworkRole.BOUNCER_ROOT,
                    host = "bouncer.example",
                    port = 6697,
                    tls = true,
                    nick = "motd",
                    username = "motd",
                    realname = "motd",
                    obfsMode = ObfsMode.EMBEDDED_REALITY,
                    proxyHost = null,
                    proxyPort = null,
                    obfsLink = uri,
                )

            val resolution = resolveTransportProxy(endpoint, LocalSocksProvider.forTest { engine })

            assertEquals(null, resolution.error)
            assertEquals(java.net.Proxy.Type.SOCKS, resolution.proxy!!.type())
            assertEquals("127.0.0.1", (resolution.proxy.address() as java.net.InetSocketAddress).hostString)
            resolution.release()
        }
    }

    @Test
    fun `embedded link errors fail closed and park reconnect`() {
        val endpoint =
            NetworkEntity(
                id = 1,
                name = "soju",
                role = NetworkRole.BOUNCER_ROOT,
                host = "bouncer.example",
                port = 6697,
                tls = true,
                nick = "motd",
                username = "motd",
                realname = "motd",
                obfsMode = ObfsMode.EMBEDDED_REALITY,
                obfsLink = "not-a-vless-link",
            )

        val resolution = resolveTransportProxy(endpoint, LocalSocksProvider.forTest { FakeEngine(Result.success(11001)) })

        assertEquals(null, resolution.proxy)
        assertNotNull(resolution.error)
        assertTrue(isConfigurationFailure("connect failed: ${resolution.error}"))

        val unavailable =
            resolveTransportProxy(
                endpoint.copy(obfsLink = validWsLink),
                LocalSocksProvider.forTest { FakeEngine(Result.failure(IllegalStateException("unavailable"))) },
            )
        assertEquals(null, unavailable.proxy)
        assertNotNull(unavailable.error)
        assertTrue(isConfigurationFailure("connect failed: ${unavailable.error}"))
    }

    @Test
    fun `only known transport configuration failures are parked`() {
        val socksError = proxyConfigurationErrorForNetwork(ObfsMode.SOCKS5, null, null)
        assertTrue(isConfigurationFailure("connect failed: $socksError"))
        assertFalse(isConfigurationFailure("connect failed: TLS handshake failed"))
    }

    @Test
    fun `local SOCKS allocation asks libbox to scan from a nonzero port`() {
        var startPort = 0
        val port =
            selectLocalSocksPort { start ->
                startPort = start
                24_321
            }

        assertEquals(20_000, startPort)
        assertEquals(24_321, port)
    }

    @Test
    fun `local SOCKS allocation rejects an invalid libbox port`() {
        assertTrue(runCatching { selectLocalSocksPort { 0 } }.isFailure)
    }

    @Test
    fun `local DNS responses separate families and omit IPv6 scope IDs`() {
        val addresses =
            arrayOf(
                InetAddress.getByAddress("relay.example", byteArrayOf(192.toByte(), 0, 2, 4)),
                Inet6Address.getByAddress("relay.example", InetAddress.getByName("fe80::1").address, 7),
                InetAddress.getByName("2001:db8::2"),
            )

        assertEquals("192.0.2.4", localDnsResponse("ip4", addresses))
        val ipv6 = localDnsResponse("ip6", addresses).split('\n')
        assertTrue(ipv6.all { ':' in it && '%' !in it && '/' !in it })
        assertEquals(
            listOf(InetAddress.getByName("fe80::1"), InetAddress.getByName("2001:db8::2")),
            ipv6.map(InetAddress::getByName),
        )
    }

    private class FakeEngine(
        private val result: Result<Int>,
    ) : LocalSocksEngine {
        var starts = 0
        var stops = 0

        override fun start(configJson: String): Result<Int> {
            starts++
            return result
        }

        override fun stop() {
            stops++
        }
    }

    private companion object {
        const val validLink = "vless://123e4567-e89b-12d3-a456-426614174000@edge.example:443?type=tcp&security=reality&sni=www.example.com&pbk=public-key&sid=abcd&fp=firefox"
        const val validWsLink = "vless://123e4567-e89b-12d3-a456-426614174000@relay.example:443?type=ws&security=tls&sni=relay.example&host=relay.example&path=%2Firc-vless"
    }
}
