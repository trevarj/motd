package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.NetworkEntity
import io.github.trevarj.motd.data.db.NetworkRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectNetworkEndpointsTest {
    private fun direct(host: String) =
        NetworkEntity(
            id = 7,
            name = "Libera.Chat",
            role = NetworkRole.DIRECT,
            host = host,
            port = 6697,
            tls = true,
            nick = "trev",
            username = "trev",
            realname = "Trev",
        )

    @Test
    fun direct_tls_endpoints_accept_normalized_hosts() {
        assertTrue(direct("irc.libera.chat").isDirectLiberaEndpoint())
        assertTrue(direct(" IRC.LIBERA.CHAT. ").isDirectLiberaEndpoint())
        assertTrue(direct("irc.oftc.net").isDirectOftcEndpoint())
        assertTrue(direct(" IRC.OFTC.NET. ").isDirectOftcEndpoint())
    }

    @Test
    fun libera_requires_a_direct_tls_endpoint_not_a_label_or_bouncer() {
        val libera = direct("irc.libera.chat")
        val invalidRows =
            listOf(
                null,
                libera.copy(role = NetworkRole.BOUNCER_ROOT),
                libera.copy(role = NetworkRole.BOUNCER_CHILD),
                libera.copy(host = "irc.example.org"),
                libera.copy(host = "irc.libera.chat.example.org"),
                libera.copy(host = "irc.oftc.net"),
                libera.copy(port = 6667),
                libera.copy(tls = false),
            )

        invalidRows.forEach { row -> assertFalse("Unexpected Libera endpoint: $row", row.isDirectLiberaEndpoint()) }
    }

    @Test
    fun oftc_requires_a_direct_tls_endpoint_not_a_label_or_bouncer() {
        val oftc = direct("irc.oftc.net").copy(name = "OFTC")
        val invalidRows =
            listOf(
                null,
                oftc.copy(role = NetworkRole.BOUNCER_ROOT),
                oftc.copy(role = NetworkRole.BOUNCER_CHILD),
                oftc.copy(host = "irc.example.org"),
                oftc.copy(host = "irc.oftc.net.example.org"),
                oftc.copy(host = "irc.libera.chat"),
                oftc.copy(port = 6667),
                oftc.copy(tls = false),
            )

        invalidRows.forEach { row -> assertFalse("Unexpected OFTC endpoint: $row", row.isDirectOftcEndpoint()) }
    }
}
