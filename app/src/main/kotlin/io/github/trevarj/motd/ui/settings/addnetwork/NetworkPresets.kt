package io.github.trevarj.motd.ui.settings.addnetwork

import io.github.trevarj.motd.ui.onboarding.AuthForm
import io.github.trevarj.motd.ui.onboarding.ServerForm

enum class NetworkPresetId {
    CUSTOM, LIBERA, OFTC, EFNET, IRCNET, DALNET, RIZON, SNOONET, QUAKENET, UNDERNET,
}

data class NetworkPreset(
    val id: NetworkPresetId,
    val displayName: String,
    val host: String,
    val port: Int,
    val tls: Boolean,
    val legacyUnencrypted: Boolean = false,
) {
    fun matches(server: ServerForm): Boolean =
        server.host.equals(host, ignoreCase = true) && server.port == port.toString() && server.tls == tls
}

/** Compile-time convenience defaults. Secure entries are deliberately ordered before legacy IRC. */
val COMMON_NETWORK_PRESETS: List<NetworkPreset> = listOf(
    NetworkPreset(NetworkPresetId.LIBERA, "Libera.Chat", "irc.libera.chat", 6697, tls = true),
    NetworkPreset(NetworkPresetId.OFTC, "OFTC", "irc.oftc.net", 6697, tls = true),
    NetworkPreset(NetworkPresetId.EFNET, "EFnet", "irc.efnet.org", 6697, tls = true),
    NetworkPreset(NetworkPresetId.IRCNET, "IRCnet", "irc.ircnet.ca", 6697, tls = true),
    NetworkPreset(NetworkPresetId.DALNET, "DALnet", "irc.dal.net", 6697, tls = true),
    NetworkPreset(NetworkPresetId.RIZON, "Rizon", "irc.rizon.net", 6697, tls = true),
    NetworkPreset(NetworkPresetId.SNOONET, "Snoonet", "irc.snoonet.org", 6697, tls = true),
    NetworkPreset(NetworkPresetId.QUAKENET, "QuakeNet", "irc.quakenet.org", 6667, false, true),
    NetworkPreset(NetworkPresetId.UNDERNET, "Undernet", "irc.undernet.org", 6667, false, true),
)

fun networkPreset(id: NetworkPresetId): NetworkPreset? = COMMON_NETWORK_PRESETS.firstOrNull { it.id == id }

/** Apply only endpoint defaults, preserve IRC identity, and drop credentials from the old server. */
fun applyNetworkPreset(preset: NetworkPreset, server: ServerForm): Pair<ServerForm, AuthForm> =
    server.copy(host = preset.host, port = preset.port.toString(), tls = preset.tls) to AuthForm()
