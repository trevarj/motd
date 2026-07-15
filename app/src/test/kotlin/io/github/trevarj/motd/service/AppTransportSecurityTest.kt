package io.github.trevarj.motd.service

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTransportSecurityTest {
    @Test
    fun `preparation resolves STS port before TCP transport creation`() = runTest {
        val lookups = mutableListOf<Pair<String, Int>>()
        val policy = StsPolicy("irc.example", 6698, Long.MAX_VALUE)

        val security = prepareTransportSecurity(
            host = "irc.example",
            port = 6697,
            wsUrl = null,
            policyFor = { policy },
            pinnedFor = { host, port ->
                lookups += host to port
                "$host:$port"
            },
        )

        assertEquals(policy, security.stsPolicy)
        assertEquals("irc.example:6698", security.tcpPin)
        assertNull(security.wsPin)
        assertEquals(listOf("irc.example" to 6698), lookups)
    }

    @Test
    fun `websocket only resolves its secure endpoint pin`() = runTest {
        val lookups = mutableListOf<Pair<String, Int>>()

        val security = prepareTransportSecurity(
            host = "irc.example",
            port = 6667,
            wsUrl = "wss://web.example/socket",
            policyFor = { error("STS is not consulted for WebSockets") },
            pinnedFor = { host, port ->
                lookups.add(host to port)
                "web-pin"
            },
        )

        assertNull(security.stsPolicy)
        assertNull(security.tcpPin)
        assertEquals("web-pin", security.wsPin)
        assertEquals(listOf("web.example" to 443), lookups)
    }
}
