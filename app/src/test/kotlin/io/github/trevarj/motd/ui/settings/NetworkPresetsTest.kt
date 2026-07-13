package io.github.trevarj.motd.ui.settings

import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.AuthMode
import io.github.trevarj.motd.ui.onboarding.ServerForm
import io.github.trevarj.motd.ui.settings.addnetwork.COMMON_NETWORK_PRESETS
import io.github.trevarj.motd.ui.settings.addnetwork.NetworkPresetId
import io.github.trevarj.motd.ui.settings.addnetwork.applyNetworkPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NetworkPresetsTest {
    @Test
    fun catalog_values_and_order_are_exact() {
        assertEquals(
            listOf(
                Triple("irc.libera.chat", 6697, true),
                Triple("irc.oftc.net", 6697, true),
                Triple("irc.efnet.org", 6697, true),
                Triple("irc.ircnet.ca", 6697, true),
                Triple("irc.dal.net", 6697, true),
                Triple("irc.rizon.net", 6697, true),
                Triple("irc.snoonet.org", 6697, true),
                Triple("irc.quakenet.org", 6667, false),
                Triple("irc.undernet.org", 6667, false),
            ),
            COMMON_NETWORK_PRESETS.map { Triple(it.host, it.port, it.tls) },
        )
        assertEquals(7, COMMON_NETWORK_PRESETS.count { !it.legacyUnencrypted })
        assertEquals(2, COMMON_NETWORK_PRESETS.count { it.legacyUnencrypted })
    }

    @Test
    fun applying_preset_preserves_identity_and_clears_auth() {
        val original = ServerForm(
            host = "old.example",
            port = "7000",
            tls = false,
            nick = "trev",
            username = "ident",
            realname = "Trev",
        )
        val preset = COMMON_NETWORK_PRESETS.first { it.id == NetworkPresetId.LIBERA }

        val (server, auth) = applyNetworkPreset(preset, original)

        assertEquals("irc.libera.chat", server.host)
        assertEquals("6697", server.port)
        assertEquals(true, server.tls)
        assertEquals("trev", server.nick)
        assertEquals("ident", server.username)
        assertEquals("Trev", server.realname)
        assertEquals(AuthForm(), auth)
        assertFalse(preset.matches(server.copy(host = "irc.example")))
    }
}
