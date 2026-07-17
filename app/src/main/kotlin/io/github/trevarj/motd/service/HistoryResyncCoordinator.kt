package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.prefs.NoopHistorySyncPrefs
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.CanonicalHistorySingleFlight
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResult
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

enum class HistoryRefreshRange {
    MISSING,
    HOURS_24,
    DAYS_7,
    DAYS_30,
    ALL_AVAILABLE,
}

sealed interface HistoryResyncState {
    data object Idle : HistoryResyncState
    data object WaitingForCapability : HistoryResyncState
    data class Running(val fetched: Int = 0, val limit: Int? = null) : HistoryResyncState
    data class Updated(val inserted: Int) : HistoryResyncState
    data object UpToDate : HistoryResyncState
    data object Unsupported : HistoryResyncState
    data class Failed(val reason: String) : HistoryResyncState
}

/** Chat-facing boundary for manual and lifecycle-driven history reconciliation. */
interface HistoryResyncController {
    fun state(bufferId: Long): Flow<HistoryResyncState>
    fun consumeState(bufferId: Long)
    fun cancelBufferResync(bufferId: Long)

    suspend fun resyncBuffer(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
        range: HistoryRefreshRange = HistoryRefreshRange.MISSING,
    ): HistoryResyncState

    suspend fun reconcileBuffer(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState
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
    @param:ApplicationScope private val scope: CoroutineScope,
    private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
) : HistoryResyncController {
    internal interface HistorySource {
        fun hasChatHistory(): Boolean
        fun supportsMsgidReferences(): Boolean
        fun pageLimit(): Int = 100
        suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResult
    }

    private data class RequestKey(val networkId: Long, val bufferId: Long?)
    private data class Boundary(val msgid: String?, val serverTime: Long)

    private val activeGuard = Mutex()
    private val active = HashMap<RequestKey, Deferred<HistoryResyncState>>()
    private val states = MutableStateFlow<Map<Long, HistoryResyncState>>(emptyMap())
    internal var requestTimeoutMs: Long = REQUEST_TIMEOUT_MS

    override fun state(bufferId: Long): Flow<HistoryResyncState> = states
        .map { it[bufferId] ?: HistoryResyncState.Idle }
        .distinctUntilChanged()

    override fun consumeState(bufferId: Long) {
        states.update { it - bufferId }
    }

    override suspend fun resyncBuffer(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
        range: HistoryRefreshRange,
    ): HistoryResyncState = resyncBuffer(buffer, client, isCurrent, range, publishState = true)

    /**
     * Reconcile a visible chat without exposing manual-refresh progress or result snackbars. The
     * request still shares the exact same per-buffer single flight as [resyncBuffer].
     */
    override suspend fun reconcileBuffer(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState = resyncBuffer(
        buffer,
        client,
        isCurrent,
        HistoryRefreshRange.MISSING,
        publishState = false,
    )

    private suspend fun resyncBuffer(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
        range: HistoryRefreshRange,
        publishState: Boolean,
    ): HistoryResyncState {
        diagnostics.record("history", "buffer_sync_requested") {
            mapOf(
                "network_id" to buffer.networkId,
                "buffer_id" to buffer.id,
                "range" to range.name,
            )
        }
        val source = ClientHistorySource(client)
        if (!source.hasChatHistory()) {
            if (publishState) {
                states.update { it + (buffer.id to HistoryResyncState.WaitingForCapability) }
            }
            when (awaitBouncerCapability(buffer.networkId, client, isCurrent)) {
                CapabilityAvailability.AVAILABLE -> Unit
                CapabilityAvailability.UNSUPPORTED -> {
                    if (publishState) {
                        states.update { it + (buffer.id to HistoryResyncState.Unsupported) }
                    }
                    return HistoryResyncState.Unsupported
                }
                CapabilityAvailability.PENDING -> {
                    val failed = HistoryResyncState.Failed("History support is still negotiating; try again")
                    if (publishState) states.update { it + (buffer.id to failed) }
                    return failed
                }
            }
        }
        if (publishState) {
            states.update { it + (buffer.id to HistoryResyncState.Running(limit = range.messageLimit)) }
        }
        val result = coalesced(RequestKey(buffer.networkId, buffer.id)) {
            syncBufferRange(
                networkId = buffer.networkId,
                bufferId = buffer.id,
                target = buffer.displayName,
                source = source,
                isCurrent = isCurrent,
                range = range,
            )
        }
        if (publishState) states.update { it + (buffer.id to result) }
        return result
    }

    /** Cancel the active user-requested refresh for [bufferId], if one exists. */
    override fun cancelBufferResync(bufferId: Long) {
        scope.launch {
            val request = activeGuard.withLock {
                active.entries.firstOrNull { it.key.bufferId == bufferId }?.value
            }
            request?.cancel()
            states.update { it - bufferId }
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
        diagnostics.record("history", "network_sync_started") {
            mapOf("network_id" to networkId, "open_buffers" to openBuffers.size)
        }
        if (!source.hasChatHistory()) return@coalesced HistoryResyncState.Unsupported
        // A room row's newest message is not a reliable reconnect cursor: a newer push-delivered
        // message in one buffer can otherwise hide an older missed message in another. Advance a
        // dedicated cursor only after a whole network pass has completed.
        val now = System.currentTimeMillis()
        val previousSync = syncPrefs.lastSuccessfulSync(networkId)
        val lower = (previousSync ?: Instant.EPOCH.toEpochMilli())
            .minus(TARGETS_FUZZ_MS)
            .coerceAtLeast(Instant.EPOCH.toEpochMilli())
        val upper = now + TARGETS_FUZZ_MS
        val discovered = try {
            discoverTargets(source, upper, lower).map { null to it }
        } catch (_: TimeoutCancellationException) {
            return@coalesced HistoryResyncState.Failed("Could not discover history targets")
        } catch (cancelled: CancellationException) {
            throw cancelled
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
            // Use the last completed whole-network pass, not Room's newest row, as the reconnect
            // cursor. A live line can arrive before catch-up starts and would otherwise hide older
            // lines missed while the socket was down. Replaying the overlap is safe because
            // Canonical event aliases make replaying this overlap safe.
            reconnectBoundary = previousSync?.let { Boundary(msgid = null, serverTime = lower) },
            // A bouncer can deliver a live self-JOIN before this first pass. That row is newer
            // than every retained line, so an AFTER-only request would permanently skip the
            // initial backlog. Seed a fresh network with LATEST as well; a known target with no
            // persisted chat row gets the same protection in syncTarget for pre-fix cursors.
            includeRecentOverlap = previousSync == null,
        )
        if (result !is HistoryResyncState.Failed && result != HistoryResyncState.Unsupported) {
            syncPrefs.setLastSuccessfulSync(networkId, now)
        }
        diagnostics.record("history", "network_sync_finished") {
            mapOf(
                "network_id" to networkId,
                "targets" to (openBuffers.size + discovered.size),
                "result" to result::class.simpleName,
            )
        }
        result
    }

    /**
     * Enumerate the complete TARGETS interval before the network cursor is advanced. TARGETS has
     * BETWEEN ordering semantics, so moving the upper selector to the oldest returned target walks
     * newest-to-oldest without replaying a page. A full page that cannot move the selector is a
     * protocol failure: returning Failed leaves the old cursor intact instead of permanently
     * hiding rooms that may remain beyond that page.
     */
    private suspend fun discoverTargets(
        source: HistorySource,
        upper: Long,
        lower: Long,
    ): List<String> {
        val limit = source.pageLimit().coerceAtLeast(1)
        val targets = LinkedHashMap<String, String>()
        var pageUpper = upper
        while (true) {
            val page = request(
                source,
                ChatHistoryRequest(
                    subcommand = ChatHistoryRequest.Subcommand.TARGETS,
                    target = "*",
                    bound1 = ChatHistorySelectors.timestamp(pageUpper),
                    bound2 = ChatHistorySelectors.timestamp(lower),
                    limit = limit,
                ),
            ).targets
            page.forEach { (target, _) -> targets.putIfAbsent(target.lowercase(), target) }
            if (page.size < limit) break

            val nextUpper = page.minOf { (_, latestMessageTime) -> latestMessageTime }
            if (nextUpper <= lower) break
            check(nextUpper < pageUpper) {
                "CHATHISTORY TARGETS pagination did not advance"
            }
            pageUpper = nextUpper
        }
        return targets.values.toList()
    }

    internal suspend fun resyncBuffer(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
        range: HistoryRefreshRange = HistoryRefreshRange.MISSING,
    ): HistoryResyncState = coalesced(RequestKey(networkId, bufferId)) {
        syncBufferRange(networkId, bufferId, target, source, isCurrent, range)
    }

    private suspend fun syncBufferRange(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean,
        range: HistoryRefreshRange,
    ): HistoryResyncState {
        if (!source.hasChatHistory()) return HistoryResyncState.Unsupported
        if (!isCurrent()) return staleConnection()
        val before = messageCount(bufferId)
        return try {
            when (range) {
                HistoryRefreshRange.MISSING -> syncTarget(
                    networkId,
                    bufferId,
                    target,
                    source,
                    isCurrent,
                    includeRecentOverlap = true,
                    healSparseGaps = true,
                )
                HistoryRefreshRange.HOURS_24,
                HistoryRefreshRange.DAYS_7,
                HistoryRefreshRange.DAYS_30,
                -> syncSince(
                    networkId = networkId,
                    bufferId = bufferId,
                    target = target,
                    source = source,
                    isCurrent = isCurrent,
                    cutoff = range.cutoffMillis(System.currentTimeMillis())!!,
                    range = range,
                )
                HistoryRefreshRange.ALL_AVAILABLE -> syncAllAvailable(
                    networkId,
                    bufferId,
                    target,
                    source,
                    isCurrent,
                )
            }
            val inserted = (messageCount(bufferId) - before).coerceAtLeast(0)
            if (inserted > 0) HistoryResyncState.Updated(inserted) else HistoryResyncState.UpToDate
        } catch (_: TimeoutCancellationException) {
            HistoryResyncState.Failed("History refresh timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleConnectionException) {
            staleConnection()
        } catch (error: Exception) {
            HistoryResyncState.Failed(error.message?.take(160) ?: "History refresh failed")
        }
    }

    /** Fetch the requested recent interval forward from its cutoff timestamp. */
    private suspend fun syncSince(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean,
        cutoff: Long,
        range: HistoryRefreshRange,
    ) {
        val pageLimit = source.pageLimit()
        var boundary = Boundary(msgid = null, serverTime = cutoff)
        var fetched = 0
        while (true) {
            if (!isCurrent()) throw StaleConnectionException()
            val page = request(
                source,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.AFTER,
                    target,
                    bound1 = boundary.selector(source.supportsMsgidReferences()),
                    limit = pageLimit,
                ),
            )
            if (!isCurrent()) throw StaleConnectionException()
            if (page.events.isEmpty()) return
            processor.process(networkId, IrcEvent.HistoryBatch(target, page.events))
            fetched += page.events.size
            states.update { it + (bufferId to HistoryResyncState.Running(fetched, range.messageLimit)) }
            val next = pageBoundary(page.events, newest = true) ?: return
            if (next == boundary || page.events.size < pageLimit) return
            boundary = next
        }
    }

    /** Fetch newest pages first so the all-history cap remains useful for active chats. */
    private suspend fun syncAllAvailable(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean,
    ) {
        val pageLimit = source.pageLimit()
        var boundary: Boundary? = null
        var fetched = 0
        while (fetched < ALL_HISTORY_LIMIT) {
            if (!isCurrent()) throw StaleConnectionException()
            val requestLimit = minOf(pageLimit, ALL_HISTORY_LIMIT - fetched)
            val historyRequest = if (boundary == null) {
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = requestLimit)
            } else {
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.BEFORE,
                    target,
                    bound1 = boundary.selector(source.supportsMsgidReferences()),
                    limit = requestLimit,
                )
            }
            val page = request(source, historyRequest)
            if (!isCurrent()) throw StaleConnectionException()
            if (page.events.isEmpty()) return
            processor.process(networkId, IrcEvent.HistoryBatch(target, page.events))
            fetched += page.events.size
            states.update { it + (bufferId to HistoryResyncState.Running(fetched, ALL_HISTORY_LIMIT)) }
            val next = pageBoundary(page.events, newest = false) ?: return
            if (next == boundary || page.events.size < requestLimit) return
            boundary = next
        }
    }

    private suspend fun syncTargets(
        networkId: Long,
        targets: List<Pair<Long?, String>>,
        source: HistorySource,
        isCurrent: () -> Boolean,
        reconnectBoundary: Boundary?,
        includeRecentOverlap: Boolean,
    ): HistoryResyncState {
        if (!source.hasChatHistory()) return HistoryResyncState.Unsupported
        if (!isCurrent()) return staleConnection()
        return try {
            var inserted = 0
            for ((knownBufferId, target) in targets) {
                if (!isCurrent()) return staleConnection()
                val canonicalRoomId = knownBufferId
                    ?: processor.ensureHistoryRoom(networkId, target)
                inserted += syncTarget(
                    networkId,
                    canonicalRoomId,
                    target,
                    source,
                    isCurrent,
                    includeRecentOverlap,
                    reconnectBoundary,
                )
            }
            if (inserted > 0) HistoryResyncState.Updated(inserted) else HistoryResyncState.UpToDate
        } catch (_: TimeoutCancellationException) {
            HistoryResyncState.Failed("History refresh timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleConnectionException) {
            staleConnection()
        } catch (error: Exception) {
            HistoryResyncState.Failed(error.message?.take(160) ?: "History refresh failed")
        }
    }

    private suspend fun syncTarget(
        networkId: Long,
        knownBufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean,
        includeRecentOverlap: Boolean,
        reconnectBoundary: Boundary? = null,
        healSparseGaps: Boolean = false,
    ): Int {
        val pageLimit = source.pageLimit()
        val before = messageCount(knownBufferId)
        val bufferId = knownBufferId
        var boundary = reconnectBoundary ?: latestBoundary(bufferId)
        // A live self-JOIN/part/topic row is not evidence that its retained chat history was
        // imported. In particular, older releases marked a network sync complete after using that
        // row as an AFTER cursor, leaving an existing bouncer channel permanently empty. Bypass
        // AFTER for a target with no stored chat content and seed it from LATEST instead.
        val lacksStoredChat = !hasStoredChat(bufferId)
        val knownOldestTime = if (lacksStoredChat) null else db.messageDao().oldestTime(bufferId)
        var afterRejected = false

        var pagingBoundary = when {
            reconnectBoundary != null -> reconnectBoundary
            lacksStoredChat -> null
            else -> boundary
        }
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
                    val next = latestBoundary(bufferId)
                    if (next == null || next == currentBoundary) break
                    pagingBoundary = next
                    boundary = next
                    if (page.events.size < pageLimit) break
                }
            } catch (_: TimeoutCancellationException) {
                afterRejected = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: StaleConnectionException) {
                throw StaleConnectionException()
            } catch (_: Exception) {
                // Servers may reject a stale/unsupported selector even while LATEST works.
                afterRejected = true
            }
        }

        var latestPage: ChatHistoryResult? = null
        if (boundary == null || afterRejected || includeRecentOverlap || lacksStoredChat) {
            val latest = request(
                source,
                ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = pageLimit),
            )
            latestPage = latest
            if (!isCurrent()) throw StaleConnectionException()
            if (latest.events.isNotEmpty()) {
                processor.process(networkId, IrcEvent.HistoryBatch(target, latest.events))
                if (!isCurrent()) throw StaleConnectionException()
            }
        }

        // A newest-page overlap catches small holes but cannot repair a sparse timeline whose
        // newest and oldest rows survived while more than one server page vanished locally. Only
        // explicit/visible-buffer MISSING reconciliation performs this bounded backward walk;
        // normal reconnect sync keeps its existing request pattern. Stop at overlap, a short page,
        // or strict request/message caps so an unusual server cannot trigger unbounded traffic.
        val overlap = latestPage
        var backwardBoundary = overlap?.events?.let { pageBoundary(it, newest = false) }
        var previousPageSize = overlap?.events?.size ?: 0
        var backfillPages = 0
        var backfillMessages = 0
        while (
            healSparseGaps &&
            backwardBoundary != null &&
            previousPageSize >= pageLimit &&
            backfillPages < MISSING_BACKFILL_PAGE_LIMIT &&
            backfillMessages < MISSING_BACKFILL_MESSAGE_LIMIT
        ) {
            if (!isCurrent()) throw StaleConnectionException()
            val currentBoundary = backwardBoundary
            val requestLimit = minOf(pageLimit, MISSING_BACKFILL_MESSAGE_LIMIT - backfillMessages)
            val countBeforePage = messageCount(bufferId)
            val page = request(
                source,
                ChatHistoryRequest(
                    ChatHistoryRequest.Subcommand.BEFORE,
                    target,
                    bound1 = currentBoundary.selector(source.supportsMsgidReferences()),
                    limit = requestLimit,
                ),
            )
            if (!isCurrent()) throw StaleConnectionException()
            if (page.events.isEmpty()) break
            processor.process(networkId, IrcEvent.HistoryBatch(target, page.events))
            if (!isCurrent()) throw StaleConnectionException()
            val countAfterPage = messageCount(bufferId)
            val inserted = (countAfterPage - countBeforePage).coerceAtLeast(0)
            val next = pageBoundary(page.events, newest = false)
            val reachedKnownBoundary = knownOldestTime != null &&
                page.events.flatMap { it.historyContexts() }.any { it.serverTime <= knownOldestTime }
            backfillPages++
            backfillMessages += page.events.size
            previousPageSize = page.events.size
            if (
                inserted == 0 ||
                reachedKnownBoundary ||
                page.events.size < requestLimit ||
                next == null ||
                next == currentBoundary
            ) break
            backwardBoundary = next
        }

        val after = messageCount(bufferId)
        return (after - before).coerceAtLeast(0)
    }

    private suspend fun <T> withNetworkLock(networkId: Long, block: suspend () -> T): T =
        CanonicalHistorySingleFlight.withNetwork(networkId, block)

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

    private suspend fun latestBoundary(bufferId: Long): Boundary? =
        db.messageDao().latestBoundary(bufferId)?.let { Boundary(it.msgid, it.serverTime) }

    private suspend fun messageCount(bufferId: Long): Int = db.messageDao().countForBuffer(bufferId)

    private suspend fun hasStoredChat(bufferId: Long): Boolean = db.messageDao().hasStoredChat(bufferId)

    private fun Boundary.selector(msgidSupported: Boolean): String =
        if (msgidSupported && !msgid.isNullOrBlank()) "msgid=$msgid"
        else ChatHistorySelectors.timestamp(serverTime)

    private fun pageBoundary(events: List<IrcEvent>, newest: Boolean): Boundary? {
        val contexts = events.flatMap { it.historyContexts() }
        val context = if (newest) {
            contexts.maxByOrNull { it.serverTime }
        } else {
            contexts.minByOrNull { it.serverTime }
        } ?: return null
        return Boundary(context.msgid, context.serverTime)
    }

    private fun IrcEvent.historyContexts(): List<io.github.trevarj.motd.irc.event.MessageContext> = when (this) {
        is IrcEvent.ChatMessage -> listOf(ctx)
        is IrcEvent.TagMessage -> listOf(ctx)
        is IrcEvent.Joined -> listOf(ctx)
        is IrcEvent.Parted -> listOf(ctx)
        is IrcEvent.Quit -> listOf(ctx)
        is IrcEvent.Kicked -> listOf(ctx)
        is IrcEvent.NickChanged -> listOf(ctx)
        is IrcEvent.TopicChanged -> listOf(ctx)
        is IrcEvent.ModeChanged -> listOf(ctx)
        is IrcEvent.Invited -> listOf(ctx)
        is IrcEvent.NetworkBatch -> events.flatMap { it.historyContexts() }
        is IrcEvent.HistoryBatch -> events.flatMap { it.historyContexts() }
        else -> emptyList()
    }

    private val HistoryRefreshRange.messageLimit: Int?
        get() = if (this == HistoryRefreshRange.ALL_AVAILABLE) ALL_HISTORY_LIMIT else null

    private fun HistoryRefreshRange.cutoffMillis(now: Long): Long? = when (this) {
        HistoryRefreshRange.HOURS_24 -> now - HOURS_24_MS
        HistoryRefreshRange.DAYS_7 -> now - DAYS_7_MS
        HistoryRefreshRange.DAYS_30 -> now - DAYS_30_MS
        HistoryRefreshRange.MISSING,
        HistoryRefreshRange.ALL_AVAILABLE,
        -> null
    }

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
                // IRCv3 requires clients to assume timestamp and msgid when MSGREFTYPES is absent.
                ?: true

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
        const val ALL_HISTORY_LIMIT = 5_000
        const val MISSING_BACKFILL_PAGE_LIMIT = 5
        const val MISSING_BACKFILL_MESSAGE_LIMIT = 500
        const val HOURS_24_MS = 24L * 60 * 60 * 1_000
        const val DAYS_7_MS = 7L * 24 * 60 * 60 * 1_000
        const val DAYS_30_MS = 30L * 24 * 60 * 60 * 1_000
    }
}
