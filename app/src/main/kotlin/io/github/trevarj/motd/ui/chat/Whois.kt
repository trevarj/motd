package io.github.trevarj.motd.ui.chat

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.NetworkDao
import io.github.trevarj.motd.data.db.NetworkIdentityDao
import io.github.trevarj.motd.data.db.NetworkIdentityEntity
import io.github.trevarj.motd.data.db.UserEntity
import io.github.trevarj.motd.data.repo.NetworkIgnoreRepository
import io.github.trevarj.motd.data.repo.hasExactNickIgnore
import io.github.trevarj.motd.data.repo.isExactIgnoreNick
import io.github.trevarj.motd.dickord.isDickordRelayNick
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import io.github.trevarj.motd.irc.proto.IrcMessage
import io.github.trevarj.motd.service.PresenceState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Parsed WHOIS details for the nick sheet. Every field is optional because a server
 * may omit any numeric; the sheet renders only the lines it has.
 */
data class WhoisInfo(
    val nick: String,
    val username: String? = null,
    val host: String? = null,
    val realname: String? = null,
    val server: String? = null,
    val serverInfo: String? = null,
    val account: String? = null,
    val channels: List<String> = emptyList(),
    val idleSecs: Long? = null,
    val signonEpochSecs: Long? = null,
    val awayMessage: String? = null,
    val away: Boolean? = null,
    val isBot: Boolean? = null,
)

/** Nick-sheet state: the target nick plus its WHOIS details once they land. */
data class NickSheetState(
    val nick: String,
    val cached: UserEntity? = null,
    val whois: WhoisInfo? = null,
    val presence: PresenceState? = null,
    val ignore: NickIgnoreState? = null,
) {
    val details: WhoisInfo? get() = mergeUserDetails(nick, cached, whois)
}

class NickIgnoreState internal constructor(
    val ignored: Boolean,
    val saving: Boolean = false,
    val failed: Boolean = false,
)

internal class NickSheetController(
    private val scope: CoroutineScope,
    private val connections: StateFlow<Map<Long, IrcClientState>>,
    private val identities: NetworkIdentityDao,
    private val networks: NetworkDao,
    private val ignores: NetworkIgnoreRepository,
    private val currentRoom: () -> BufferEntity?,
) {
    private val _state = MutableStateFlow<NickSheetState?>(null)
    val state = _state.asStateFlow()
    private var session: Session? = null
    private var observation: Job? = null

    fun open(
        nick: String,
        loadIrcDetails: Boolean = true,
    ): Session {
        dismiss()
        val opened = Session(nick, currentRoom())
        session = opened
        _state.value = NickSheetState(nick)
        if (loadIrcDetails && !isDickordRelayNick(nick)) observation = scope.launch { opened.observeIgnore() }
        return opened
    }

    fun dismiss() {
        observation?.cancel()
        observation = null
        session = null
        _state.value = null
    }

    fun setIgnored(expected: NickIgnoreState) {
        session?.setIgnored(expected)
    }

    fun networkFor(expected: NickIgnoreState): Long? = session?.networkFor(expected)

    internal inner class Session(
        private val nick: String,
        private val room: BufferEntity?,
    ) {
        private var persisted: NetworkIdentityEntity? = null
        private var configuredNick: String? = null
        private var observedRules: IrcIdentityRules? = null
        private var saving = false
        private var failed = false

        fun update(transform: (NickSheetState) -> NickSheetState) {
            if (session === this) _state.value = _state.value?.let(transform)
        }

        suspend fun loadWhois(
            client: IrcClient,
            isCurrent: () -> Boolean,
        ) {
            val generation = client.criticalEvents

            if (!connectionIsCurrent()) return
            coroutineScope {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    client.awaitTermination()
                    update { it.copy(whois = null) }
                }
                val info =
                    scope
                        .async {
                            if (!connectionIsCurrent()) return@async null
                            val lines =
                                runCatching { client.whois(nick) }.getOrElse {
                                    if (it is CancellationException) throw it
                                    return@async null
                                }
                            parseWhois(lines)
                        }.await()
                if (info != null && connectionIsCurrent()) update { it.copy(whois = info) }
            }
        }

        private fun eligibleRules(ready: IrcClientState.Ready?): IrcIdentityRules? {
            val configured = configuredNick ?: return null
            val rules =
                IrcIdentityRules.from(
                    ready?.isupport?.get("CASEMAPPING")
                        ?: persisted?.caseMapping.takeUnless { ready?.removedIsupport?.contains("CASEMAPPING") == true },
                    ready?.isupport?.get("CHANTYPES")
                        ?: persisted?.chanTypes.takeUnless { ready?.removedIsupport?.contains("CHANTYPES") == true },
                )
            val selfNick = ready?.nick?.takeIf { it.isNotBlank() } ?: persisted?.selfNick?.takeIf { it.isNotBlank() } ?: configured
            return rules.takeIf { isExactIgnoreNick(nick, it) && it.normalize(nick) != it.normalize(selfNick) }
        }

        suspend fun observeIgnore() {
            val networkId = room?.networkId ?: return
            combine(
                connections.map { it[networkId] as? IrcClientState.Ready }.distinctUntilChanged(),
                identities.observe(networkId),
                networks.observeById(networkId).map { it?.nick }.distinctUntilChanged(),
                ignores.observeIgnores(networkId),
            ) { ready, identity, configured, rows ->
                persisted = identity
                configuredNick = configured
                eligibleRules(ready)?.let { it to hasExactNickIgnore(rows, nick, it) }
            }.distinctUntilChanged().collect { observation ->
                observedRules = observation?.first
                update { sheet -> sheet.copy(ignore = observation?.let { NickIgnoreState(it.second, saving, failed) }) }
            }
        }

        fun networkFor(expected: NickIgnoreState): Long? {
            val target = room ?: return null
            val current = currentRoom() ?: return null
            if (session !== this || current.id != target.id || current.networkId != target.networkId || _state.value?.ignore !== expected) return null
            val liveRules = eligibleRules(connections.value[current.networkId] as? IrcClientState.Ready)
            return current.networkId.takeIf { liveRules != null && liveRules == observedRules }
        }

        fun setIgnored(expected: NickIgnoreState) {
            val networkId = networkFor(expected) ?: return
            val rules = observedRules ?: return
            if (saving) return
            saving = true
            failed = false
            update { it.copy(ignore = NickIgnoreState(expected.ignored, saving = true)) }
            scope.launch {
                val result = ignores.setExactNickIgnored(networkId, nick, rules, ignored = !expected.ignored)
                val error = result.exceptionOrNull()
                if (error is CancellationException) throw error
                saving = false
                failed = result.isFailure
                update { sheet -> sheet.copy(ignore = sheet.ignore?.let { NickIgnoreState(it.ignored, failed = failed) }) }
            }
        }
    }
}

/** WHOIS is the fresher overlay; cached WHOX/NAMES data fills fields it did not return. */
fun mergeUserDetails(
    nick: String,
    cached: UserEntity?,
    whois: WhoisInfo?,
): WhoisInfo? {
    if (cached == null) return whois
    val cachedHost = cached.hostmask?.substringAfter('@', missingDelimiterValue = "")?.ifBlank { null }
    return (whois ?: WhoisInfo(nick)).copy(
        username = whois?.username ?: cached.username,
        host = whois?.host ?: cachedHost,
        realname = whois?.realname ?: cached.realname,
        account = whois?.account ?: cached.account,
        away = whois?.away ?: cached.away,
    )
}

/**
 * Fold WHOIS numerics from a labeled response into a [WhoisInfo]. Recognized numerics:
 *
 * - `311` RPL_WHOISUSER: params = [me, nick, user, host, "*", realname]
 * - `312` RPL_WHOISSERVER: params = [me, nick, server, serverInfo]
 * - `301` RPL_AWAY: params = [me, nick, awayMessage]
 * - `317` RPL_WHOISIDLE: params = [me, nick, idleSecs, (signon), ...]
 * - `319` RPL_WHOISCHANNELS: params = [me, nick, channelList]
 * - `330` RPL_WHOISACCOUNT: params = [me, nick, account, "is logged in as"]
 *
 * Returns null when neither a `311` nor a `318` (end-of-WHOIS) is present, i.e. the response does
 * not describe a real WHOIS.
 */
fun parseWhois(lines: List<IrcMessage>): WhoisInfo? {
    val has311 = lines.any { it.command == "311" }
    val has318 = lines.any { it.command == "318" }
    if (!has311 && !has318) return null

    // Nick comes from the 311/318 second param; fall back to any WHOIS numeric's nick param.
    val nick =
        lines.firstNotNullOfOrNull { it.params.getOrNull(1)?.takeIf { n -> n.isNotEmpty() } }
            ?: return null

    var info = WhoisInfo(nick = nick)
    for (msg in lines) {
        val p = msg.params
        when (msg.command) {
            "311" -> {
                info =
                    info.copy(
                        username = p.getOrNull(2),
                        host = p.getOrNull(3),
                        realname = p.getOrNull(5),
                    )
            }

            "312" -> {
                info = info.copy(server = p.getOrNull(2), serverInfo = p.getOrNull(3))
            }

            "301" -> {
                info = info.copy(awayMessage = p.getOrNull(2), away = true)
            }

            "317" -> {
                info =
                    info.copy(
                        idleSecs = p.getOrNull(2)?.toLongOrNull(),
                        signonEpochSecs = p.getOrNull(3)?.toLongOrNull(),
                    )
            }

            "319" -> {
                info =
                    info.copy(
                        channels =
                            p
                                .getOrNull(2)
                                ?.trim()
                                ?.split(' ')
                                ?.filter { it.isNotEmpty() }
                                .orEmpty(),
                    )
            }

            "330" -> {
                info = info.copy(account = p.getOrNull(2))
            }

            "335" -> {
                if (p.size >= 3) info = info.copy(isBot = true)
            }
        }
    }
    return info
}
