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

class LocalSocksProviderTest {
    private val link = VlessLink.parse(
        "vless://123e4567-e89b-12d3-a456-426614174000@edge.example:443?type=tcp&security=reality&sni=www.example.com&pbk=public-key&sid=abcd&fp=firefox",
    ).getOrThrow()

    @Test
    fun `shares one endpoint for leases of the same link`() {
        val first = FakeEngine(Result.success(11999))
        val provider = LocalSocksProvider.forTest { first }

        assertEquals(11999, provider.start(link).getOrThrow().port)
        assertEquals(11999, provider.start(link).getOrThrow().port)
        assertEquals(1, first.starts)
        assertTrue(first.lastJson!!.contains("\"listen\":\"127.0.0.1\""))
        assertTrue(first.lastJson!!.contains("\"fingerprint\":\"firefox\""))
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
    fun `embedded link starts loopback provider before legacy SOCKS validation`() {
        val engine = FakeEngine(Result.success(11001))
        val endpoint = NetworkEntity(
            id = 1,
            name = "soju",
            role = NetworkRole.BOUNCER_ROOT,
            host = "bouncer.example",
            port = 6697,
            tls = true,
            nick = "motd",
            username = "motd",
            realname = "MOTD",
            obfsMode = ObfsMode.EMBEDDED_REALITY,
            // Phase 2 intentionally clears legacy SOCKS settings when persisting a VLESS link.
            proxyHost = null,
            proxyPort = null,
            obfsLink = validLink,
        )

        val resolution = resolveTransportProxy(endpoint, LocalSocksProvider.forTest { engine })

        assertEquals(null, resolution.error)
        assertNotNull(resolution.proxy)
        assertEquals(1, engine.starts)
    }

    @Test
    fun `invalid embedded link remains a fail closed configuration error`() {
        val endpoint = NetworkEntity(
            id = 1,
            name = "soju",
            role = NetworkRole.BOUNCER_ROOT,
            host = "bouncer.example",
            port = 6697,
            tls = true,
            nick = "motd",
            username = "motd",
            realname = "MOTD",
            obfsMode = ObfsMode.EMBEDDED_REALITY,
            obfsLink = "not-a-vless-link",
        )

        val resolution = resolveTransportProxy(endpoint, LocalSocksProvider.forTest { FakeEngine(Result.success(11001)) })

        assertEquals(null, resolution.proxy)
        assertTrue(resolution.error!!.startsWith("Embedded REALITY configuration:"))
    }

    @Test
    fun `only known transport configuration failures are parked`() {
        assertTrue(isConfigurationFailure("connect failed: Embedded REALITY configuration: unavailable"))
        assertTrue(isConfigurationFailure("connect failed: SOCKS5 proxy host is required"))
        assertFalse(isConfigurationFailure("connect failed: TLS handshake failed"))
    }

    @Test
    fun `local SOCKS allocation asks libbox to scan from a nonzero port`() {
        var startPort = 0
        val port = selectLocalSocksPort { start ->
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

    private class FakeEngine(private val result: Result<Int>) : LocalSocksEngine {
        var starts = 0
        var stops = 0
        var lastJson: String? = null
        override fun start(configJson: String): Result<Int> {
            starts++
            lastJson = configJson
            return result
        }
        override fun stop() { stops++ }
    }

    private companion object {
        const val validLink = "vless://123e4567-e89b-12d3-a456-426614174000@edge.example:443?type=tcp&security=reality&sni=www.example.com&pbk=public-key&sid=abcd&fp=firefox"
    }
}
