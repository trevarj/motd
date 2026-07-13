package io.github.trevarj.motd.bouncer

import io.github.trevarj.motd.irc.event.IrcEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

private const val SERVICE_NICK = "BouncerServ"

data class BouncerServCapabilities(
    val commandPaths: Set<String> = emptySet(),
    val verified: Boolean = false,
) {
    val administrator: Boolean get() = commandPaths.any { it.startsWith("server ") || it.startsWith("user create") }
    fun supports(path: String): Boolean = path.lowercase() in commandPaths
}

sealed interface BouncerServResult {
    val displayCommand: String
    data class Success(override val displayCommand: String, val replies: List<String>) : BouncerServResult
    data class Timeout(override val displayCommand: String, val replies: List<String>) : BouncerServResult
    data class Disconnected(override val displayCommand: String) : BouncerServResult
    data class Failed(override val displayCommand: String, val message: String) : BouncerServResult
}

interface BouncerServClient {
    suspend fun execute(rootNetworkId: Long, command: BouncerServCommand): BouncerServResult
    suspend fun probe(rootNetworkId: Long): BouncerServCapabilities
}

data class BouncerServSession(
    val token: Any,
    val events: Flow<IrcEvent>,
    val send: suspend (String) -> Unit,
    val isCurrent: () -> Boolean,
)

interface BouncerServSessionProvider {
    suspend fun session(rootNetworkId: Long): BouncerServSession?
}

@Singleton
class ConnectionBouncerServSessionProvider @Inject constructor(
    private val connections: io.github.trevarj.motd.service.ConnectionManager,
) : BouncerServSessionProvider {
    override suspend fun session(rootNetworkId: Long): BouncerServSession? {
        val client = connections.clientFor(rootNetworkId) ?: return null
        return BouncerServSession(
            token = client,
            events = client.events,
            send = { wire ->
                val bufferId = connections.ensureQueryBuffer(rootNetworkId, SERVICE_NICK)
                connections.sendMessage(bufferId, wire)
            },
            isCurrent = { connections.clientFor(rootNetworkId) === client },
        )
    }
}

@Singleton
class BouncerServClientImpl @Inject constructor(
    private val sessions: BouncerServSessionProvider,
) : BouncerServClient {
    private val locks = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()

    override suspend fun execute(rootNetworkId: Long, command: BouncerServCommand): BouncerServResult =
        locks.getOrPut(rootNetworkId) { Mutex() }.withLock {
            val session = sessions.session(rootNetworkId)
                ?: return@withLock BouncerServResult.Disconnected(command.display)
            try {
                coroutineScope {
                    val replies = Channel<String>(Channel.UNLIMITED)
                    val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                        session.events.collect { event ->
                            if (!session.isCurrent()) return@collect
                            val chat = event as? IrcEvent.ChatMessage ?: return@collect
                            if (chat.kind != IrcEvent.ChatKind.PRIVMSG ||
                                !chat.source.nick.equals(SERVICE_NICK, ignoreCase = true)
                            ) return@collect
                            replies.send(chat.text)
                        }
                    }
                    try {
                        // The normal send path persists only the redacted display command and
                        // deduplicates its echo against that same safe text.
                        session.send(command.wire)
                        val lines = mutableListOf<String>()
                        val completed = withTimeoutOrNull(HARD_LIMIT_MS) {
                            val first = withTimeoutOrNull(FIRST_REPLY_MS) { replies.receive() }
                                ?: return@withTimeoutOrNull false
                            lines += first
                            while (true) {
                                val next = withTimeoutOrNull(QUIET_WINDOW_MS) { replies.receive() } ?: break
                                lines += next
                            }
                            true
                        } == true
                        if (completed) BouncerServResult.Success(command.display, lines)
                        else BouncerServResult.Timeout(command.display, lines)
                    } finally {
                        collector.cancel()
                        replies.close()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                BouncerServResult.Failed(command.display, error.message ?: "BouncerServ command failed")
            }
        }

    override suspend fun probe(rootNetworkId: Long): BouncerServCapabilities {
        val first = execute(rootNetworkId, BouncerServCommands.help())
        if (first !is BouncerServResult.Success) return BouncerServCapabilities()
        val rootPaths = parseAvailableCommandPaths(first.replies)
        if (rootPaths.isEmpty()) return BouncerServCapabilities()
        val paths = rootPaths.toMutableSet()
        // Probe command families advertised by the root help. A failed family probe does not erase
        // the root paths: guided actions remain version-gated by the advertised list.
        val families = rootPaths
            .filter { ' ' in it }
            .mapTo(linkedSetOf()) { it.substringBefore(' ') }
        for (family in families) {
            val result = execute(rootNetworkId, BouncerServCommands.help(family))
            if (result is BouncerServResult.Success) {
                paths += parseAvailableCommandPaths(result.replies, family)
            }
        }
        return BouncerServCapabilities(paths, verified = true)
    }

    private companion object {
        const val FIRST_REPLY_MS = 5_000L
        const val QUIET_WINDOW_MS = 400L
        const val HARD_LIMIT_MS = 15_000L
    }
}

fun parseAvailableCommandPaths(lines: List<String>, family: String? = null): Set<String> {
    val paths = linkedSetOf<String>()
    var inCommands = false
    for (line in lines) {
        val trimmed = line.trim()
        val markerIndex = trimmed.indexOf("available commands:", ignoreCase = true)
        val commandText = if (markerIndex >= 0) {
            inCommands = true
            trimmed.substring(markerIndex + "available commands:".length)
        } else {
            if (!inCommands) continue
            trimmed
        }
        for (entry in commandText.split(',')) {
            val candidate = entry.substringBefore("  ").substringBefore(" - ").trim()
            if (candidate.isBlank()) continue
            val words = candidate.split(Regex("\\s+")).take(2)
            if (words.all { it.matches(Regex("[a-z][a-z0-9-]*")) }) {
                val path = words.joinToString(" ").lowercase()
                paths += when {
                    family == null || path.startsWith("${family.lowercase()} ") -> path
                    else -> "${family.lowercase()} $path"
                }
            }
        }
    }
    return paths
}
