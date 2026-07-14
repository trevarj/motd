package io.github.trevarj.motd.service

import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.prefs.NoopHistorySyncPrefs
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResult
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

sealed interface HistoryResyncState {
    data object Idle : HistoryResyncState
    data object WaitingForCapability : HistoryResyncState
    data object Running : HistoryResyncState
    data class Updated(val inserted: Int) : HistoryResyncState
    data object UpToDate : HistoryResyncState
    data object Unsupported : HistoryResyncState
    data class Failed(val reason: String) : HistoryResyncState
}

/**
 * The sole reconnect/manual tail-revalidation entry point. Work is single-flight per request and
 * serialized per network so a foreground reconnect and a user refresh cannot race CHATHISTORY
 * pages into the same Room timeline. IRC-derived rows still flow exclusively through
 * [EventProcessor].
 */
@Singleton
class HistoryResyncCoordinator @Inject constructor(
    private val db: MotdDatabase,
    private val processor: EventProcessor,
    private val syncPrefs: HistorySyncPrefs = NoopHistorySyncPrefs,
) {
    internal interface HistorySource {
        fun hasChatHistory(): Boolean
        fun supportsMsgidReferences(): Boolean
        fun pageLimit(): Int = 100
        suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResult
    }

    private data class RequestKey(val networkId: Long, val bufferId: Long?)
    private data class Boundary(val msgid: String?, val serverTime: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeGuard = Mutex()
    private val active = HashMap<RequestKey, Deferred<HistoryResyncState>>()
    private val networkLocks = ConcurrentHashMap<Long, Mutex>()
    private val states = MutableStateFlow<Map<Long, HistoryResyncState>>(emptyMap())
    internal var requestTimeoutMs: Long = REQUEST_TIMEOUT_MS

    fun state(bufferId: Long): Flow<HistoryResyncState> = states
        .map { it[bufferId] ?: HistoryResyncState.Idle }
        .distinctUntilChanged()

    fun consumeState(bufferId: Long) {
        states.update { it - bufferId }
    }

    suspend fun resyncBuffer(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState {
        val source = ClientHistorySource(client)
        if (!source.hasChatHistory()) {
            states.update { it + (buffer.id to HistoryResyncState.WaitingForCapability) }
            when (awaitBouncerCapability(buffer.networkId, client, isCurrent)) {
                CapabilityAvailability.AVAILABLE -> Unit
                CapabilityAvailability.UNSUPPORTED -> {
                    states.update { it + (buffer.id to HistoryResyncState.Unsupported) }
                    return HistoryResyncState.Unsupported
                }
                CapabilityAvailability.PENDING -> {
                    val failed = HistoryResyncState.Failed("History support is still negotiating; try again")
                    states.update { it + (buffer.id to failed) }
                    return failed
                }
            }
        }
        states.update { it + (buffer.id to HistoryResyncState.Running) }
        return coalesced(RequestKey(buffer.networkId, buffer.id)) {
            syncTargets(
                networkId = buffer.networkId,
                targets = listOf(buffer.id to buffer.displayName),
                source = source,
                isCurrent = isCurrent,
                includeRecentOverlap = true,
            ).also { result -> states.update { it + (buffer.id to result) } }
        }
    }

    suspend fun resyncNetwork(
        networkId: Long,
        openBuffers: List<Pair<Long, String>>,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState = resyncNetwork(networkId, openBuffers, ClientHistorySource(client), isCurrent)

    internal suspend fun resyncNetwork(
        networkId: Long,
        openBuffers: List<Pair<Long, String>>,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
    ): HistoryResyncState = coalesced(RequestKey(networkId, null)) {
        if (!source.hasChatHistory()) return@coalesced HistoryResyncState.Unsupported
        // A room row's newest message is not a reliable reconnect cursor: a newer push-delivered
        // message in one buffer can otherwise hide an older missed message in another. Advance a
        // dedicated cursor only after a whole network pass has completed.
        val now = System.currentTimeMillis()
        val lower = (syncPrefs.lastSuccessfulSync(networkId) ?: Instant.EPOCH.toEpochMilli())
            .minus(TARGETS_FUZZ_MS)
            .coerceAtLeast(Instant.EPOCH.toEpochMilli())
        val upper = now + TARGETS_FUZZ_MS
        val discovered = try {
            // TARGETS follows BETWEEN semantics. Request newest-first so its bounded limit covers
            // the most recently active conversations, including a first bouncer sync.
            request(
                source,
                ChatHistoryRequest(
                    subcommand = ChatHistoryRequest.Subcommand.TARGETS,
                    target = "*",
                    bound1 = "timestamp=${Instant.ofEpochMilli(upper)}",
                    bound2 = "timestamp=${Instant.ofEpochMilli(lower)}",
                    limit = source.pageLimit(),
                ),
            ).targets.map { null to it.first }
        } catch (error: Exception) {
            return@coalesced HistoryResyncState.Failed(
                error.message?.take(160) ?: "Could not discover history targets",
            )
        }
        val result = syncTargets(
            networkId = networkId,
            targets = (openBuffers.map { (id, name) -> id to name } + discovered)
                .distinctBy { it.second.lowercase() },
            source = source,
            isCurrent = isCurrent,
            includeRecentOverlap = false,
        )
        if (result !is HistoryResyncState.Failed && result != HistoryResyncState.Unsupported) {
            syncPrefs.setLastSuccessfulSync(networkId, now)
        }
        result
    }

    internal suspend fun resyncBuffer(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
    ): HistoryResyncState = coalesced(RequestKey(networkId, bufferId)) {
        syncTargets(networkId, listOf(bufferId to target), source, isCurrent, includeRecentOverlap = true)
    }

    private suspend fun syncTargets(
        networkId: Long,
        targets: List<Pair<Long?, String>>,
        source: HistorySource,
        isCurrent: () -> Boolean,
        includeRecentOverlap: Boolean,
    ): HistoryResyncState {
        if (!source.hasChatHistory()) return HistoryResyncState.Unsupported
        if (!isCurrent()) return staleConnection()
        return try {
            var inserted = 0
            for ((knownBufferId, target) in targets) {
                if (!isCurrent()) return staleConnection()
                inserted += syncTarget(
                    networkId,
                    knownBufferId,
                    target,
                    source,
                    isCurrent,
                    includeRecentOverlap,
                )
            }
            if (inserted > 0) HistoryResyncState.Updated(inserted) else HistoryResyncState.UpToDate
        } catch (_: StaleConnectionException) {
            staleConnection()
        } catch (error: Exception) {
            HistoryResyncState.Failed(error.message?.take(160) ?: "History refresh failed")
        }
    }

    private suspend fun syncTarget(
        networkId: Long,
        knownBufferId: Long?,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean,
        includeRecentOverlap: Boolean,
    ): Int {
        val pageLimit = source.pageLimit()
        val before = knownBufferId?.let { messageCount(it) } ?: 0
        var bufferId = knownBufferId
        var boundary = bufferId?.let { latestBoundary(it) }
        var afterRejected = false

        var pagingBoundary = boundary
        if (pagingBoundary != null) {
            try {
                while (true) {
                    val currentBoundary = pagingBoundary ?: break
                    val selector = currentBoundary.selector(source.supportsMsgidReferences())
                    val page = request(
                        source,
                        ChatHistoryRequest(
                            ChatHistoryRequest.Subcommand.AFTER,
                            target,
                            bound1 = selector,
                            limit = pageLimit,
                        ),
                    )
                    if (!isCurrent()) throw StaleConnectionException()
                    if (page.events.isEmpty()) break
                    processor.process(networkId, IrcEvent.HistoryBatch(target, page.events))
                    if (!isCurrent()) throw StaleConnectionException()
                    bufferId = bufferId ?: bufferIdFor(networkId, target)
                    val next = bufferId?.let { latestBoundary(it) }
                    if (next == null || next == currentBoundary) break
                    pagingBoundary = next
                    boundary = next
                    if (page.events.size < pageLimit) break
                }
            } catch (_: StaleConnectionException) {
                throw StaleConnectionException()
            } catch (_: Exception) {
                // Servers may reject a stale/unsupported selector even while LATEST works.
                afterRejected = true
            }
        }

        if (boundary == null || afterRejected || includeRecentOverlap) {
            val latest = request(
                source,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = pageLimit),
            )
            if (!isCurrent()) throw StaleConnectionException()
            if (latest.events.isNotEmpty()) {
                processor.process(networkId, IrcEvent.HistoryBatch(target, latest.events))
                if (!isCurrent()) throw StaleConnectionException()
                bufferId = bufferId ?: bufferIdFor(networkId, target)
            }
        }

        val after = bufferId?.let { messageCount(it) } ?: before
        return (after - before).coerceAtLeast(0)
    }

    private suspend fun <T> withNetworkLock(networkId: Long, block: suspend () -> T): T =
        networkLocks.getOrPut(networkId) { Mutex() }.withLock { block() }

    private suspend fun coalesced(
        key: RequestKey,
        block: suspend () -> HistoryResyncState,
    ): HistoryResyncState {
        val deferred = activeGuard.withLock {
            active[key] ?: scope.async { withNetworkLock(key.networkId, block) }
                .also { created ->
                    active[key] = created
                    created.invokeOnCompletion {
                        scope.launch {
                            activeGuard.withLock {
                                if (active[key] === created) active.remove(key)
                            }
                        }
                    }
                }
        }
        return try {
            deferred.await()
        } finally {
            activeGuard.withLock {
                if (deferred.isCompleted && active[key] === deferred) active.remove(key)
            }
        }
    }

    private suspend fun request(source: HistorySource, request: ChatHistoryRequest): ChatHistoryResult =
        withTimeout(requestTimeoutMs) { source.chathistory(request) }

    private suspend fun latestBoundary(bufferId: Long): Boundary? = withContext(Dispatchers.IO) {
        db.query(
            SimpleSQLiteQuery(
                "SELECT msgid, serverTime FROM messages WHERE bufferId = ? " +
                    "ORDER BY serverTime DESC, id DESC LIMIT 1",
                arrayOf<Any>(bufferId),
            ),
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else Boundary(
                msgid = if (cursor.isNull(0)) null else cursor.getString(0),
                serverTime = cursor.getLong(1),
            )
        }
    }

    private suspend fun messageCount(bufferId: Long): Int = withContext(Dispatchers.IO) {
        db.query(SimpleSQLiteQuery("SELECT COUNT(*) FROM messages WHERE bufferId = ?", arrayOf<Any>(bufferId)))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }

    private suspend fun bufferIdFor(networkId: Long, target: String): Long? = withContext(Dispatchers.IO) {
        db.query(
            SimpleSQLiteQuery(
                "SELECT id FROM buffers WHERE networkId = ? AND (name = ? COLLATE NOCASE OR displayName = ? COLLATE NOCASE) LIMIT 1",
                arrayOf<Any>(networkId, target, target),
            ),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    private fun Boundary.selector(msgidSupported: Boolean): String =
        if (msgidSupported && !msgid.isNullOrBlank()) "msgid=$msgid"
        else "timestamp=${Instant.ofEpochMilli(serverTime)}"

    private enum class CapabilityAvailability { AVAILABLE, UNSUPPORTED, PENDING }

    private suspend fun awaitBouncerCapability(
        networkId: Long,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): CapabilityAvailability {
        if (client.hasCap(CHATHISTORY_CAP)) return CapabilityAvailability.AVAILABLE
        val role = db.networkDao().byId(networkId)?.role
        if (role != NetworkRole.BOUNCER_CHILD) return CapabilityAvailability.UNSUPPORTED
        val ready = withTimeoutOrNull(CAPABILITY_WAIT_TIMEOUT_MS) {
            client.state.filterIsInstance<IrcClientState.Ready>().first { snapshot ->
                snapshot.caps.any { it == CHATHISTORY_CAP || it.startsWith("$CHATHISTORY_CAP=") }
            }
        }
        return when {
            !isCurrent() -> CapabilityAvailability.PENDING
            ready != null || client.hasCap(CHATHISTORY_CAP) -> CapabilityAvailability.AVAILABLE
            else -> CapabilityAvailability.PENDING
        }
    }

    private class ClientHistorySource(private val client: IrcClient) : HistorySource {
        override fun hasChatHistory(): Boolean = client.hasCap(CHATHISTORY_CAP)

        override fun supportsMsgidReferences(): Boolean =
            client.isupport["MSGREFTYPES"]
                ?.split(',', ' ')
                ?.any { it.equals("msgid", ignoreCase = true) }
                ?: false

        override fun pageLimit(): Int = client.isupport["CHATHISTORY"]
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.coerceAtMost(PAGE_LIMIT)
            ?: PAGE_LIMIT

        override suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResult =
            client.chathistory(request)
    }

    private class StaleConnectionException : Exception()

    private fun staleConnection(): HistoryResyncState.Failed =
        HistoryResyncState.Failed("Connection changed; try again")

    private companion object {
        const val CHATHISTORY_CAP = "draft/chathistory"
        const val PAGE_LIMIT = 100
        const val REQUEST_TIMEOUT_MS = 35_000L
        const val TARGETS_FUZZ_MS = 10_000L
        const val CAPABILITY_WAIT_TIMEOUT_MS = 30_000L
    }
}
