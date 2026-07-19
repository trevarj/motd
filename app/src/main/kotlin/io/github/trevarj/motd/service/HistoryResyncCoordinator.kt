package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.NetworkRole
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.prefs.NoopHistorySyncPrefs
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.CanonicalHistorySingleFlight
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.event.IrcClientState
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    open class Failed(open val reason: String) : HistoryResyncState {
        open override fun equals(other: Any?): Boolean =
            other is Failed && javaClass == other.javaClass && reason == other.reason

        open override fun hashCode(): Int = reason.hashCode()

        open override fun toString(): String = "${javaClass.simpleName}(reason=$reason)"
    }
    data class Incomplete(val inserted: Int, override val reason: String) : Failed(reason)
    data class Capped(val inserted: Int, val limit: Int, override val reason: String) : Failed(reason)
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

    /**
     * Fetch the newest page without waiting behind network-wide discovery/backfill. This urgent
     * path promotes a just-sent local row before a reply or reaction needs its durable msgid.
     */
    suspend fun reconcilePendingMessage(
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
        fun availability(): HistoryAvailability
        fun flightIdentity(): Any = this
        fun normalizeTarget(target: String): String = IrcIdentityRules().normalize(target)
        suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResponse
    }

    private data class RequestKey(val networkId: Long, val bufferId: Long?)
    private enum class RequestIntent { AUTOMATIC, MANUAL }
    private data class RequestSpec(
        val key: RequestKey,
        val intent: RequestIntent,
        val range: HistoryRefreshRange? = null,
        val sourceIdentity: Any,
        val cancellationGeneration: Long? = null,
    )
    private data class ActiveRequest(
        val spec: RequestSpec,
        val deferred: Deferred<HistoryResyncState>,
    )
    private data class Boundary(val msgid: String?, val serverTime: Long?)
    private data class Selector(val value: String, val type: HistoryReferenceType)
    private data class RequestedPage(
        val response: ChatHistoryResponse.Messages,
        val request: ChatHistoryRequest,
        val selector: Selector,
        val msgidAllowed: Boolean,
    )

    private sealed interface WorkStatus {
        data object Complete : WorkStatus
        data class Incomplete(val reason: String) : WorkStatus
        data class Capped(val reason: String, val limit: Int) : WorkStatus
    }

    private data class WorkResult(
        val status: WorkStatus = WorkStatus.Complete,
        val highWater: Long? = null,
    )

    private data class TargetPass(
        val inserted: Int,
        val status: WorkStatus,
        val highWater: Long?,
    )

    private data class TargetDiscovery(
        val targets: List<String>,
        val status: WorkStatus,
        val highWater: Long?,
    )

    private val activeGuard = Mutex()
    private val active = LinkedHashMap<RequestSpec, Deferred<HistoryResyncState>>()
    private val manualCancellationGenerations = ConcurrentHashMap<Long, AtomicLong>()
    private val states = MutableStateFlow<Map<Long, HistoryResyncState>>(emptyMap())
    internal var requestTimeoutMs: Long = REQUEST_TIMEOUT_MS
    internal var paginationRequestLimit: Int = PAGINATION_REQUEST_LIMIT
    internal var targetsRequestLimit: Int = TARGETS_REQUEST_LIMIT

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
    ): HistoryResyncState = resyncBuffer(
        buffer,
        client,
        isCurrent,
        range,
        publishState = true,
        healSparseGaps = true,
    )

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
        healSparseGaps = false,
    )

    override suspend fun reconcilePendingMessage(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
    ): HistoryResyncState = reconcilePendingMessage(
        networkId = buffer.networkId,
        bufferId = buffer.id,
        target = buffer.ircTarget,
        source = ClientHistorySource(client),
        isCurrent = isCurrent,
    )

    /**
     * A normal reconciliation owns the coarse per-network gate while it discovers targets and
     * repairs gaps. A user action that only needs the newest msgid must not queue behind that whole
     * pass. IrcClient still correlates labeled responses and serializes unlabeled CHATHISTORY at
     * the wire boundary, while EventProcessor remains the sole Room writer.
     */
    internal suspend fun reconcilePendingMessage(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
    ): HistoryResyncState {
        when (source.availability()) {
            HistoryAvailability.Unsupported -> return HistoryResyncState.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return historyUnavailable()
            is HistoryAvailability.Ready -> Unit
        }
        if (!isCurrent()) return staleConnection()
        val before = messageCount(bufferId)
        return try {
            val request = ChatHistoryRequest(
                subcommand = ChatHistoryRequest.Subcommand.LATEST,
                target = target,
                limit = source.pageLimit(),
            )
            val response = withTimeout(PENDING_MESSAGE_TIMEOUT_MS) {
                source.chathistory(request)
            }
            val latest = response as? ChatHistoryResponse.Messages
                ?: error("CHATHISTORY LATEST returned a TARGETS response")
            if (!isCurrent()) return staleConnection()
            ingest(networkId, request, latest)
            val inserted = (messageCount(bufferId) - before).coerceAtLeast(0)
            if (
                !latest.isTerminalPage() &&
                !latest.hasUsableDirectionalBoundary(
                    ChatHistoryRequest.Subcommand.LATEST,
                    source,
                )
            ) {
                HistoryResyncState.Incomplete(
                    inserted,
                    "CHATHISTORY LATEST returned no usable primary-message boundary",
                )
            } else if (inserted > 0) {
                HistoryResyncState.Updated(inserted)
            } else {
                HistoryResyncState.UpToDate
            }
        } catch (_: TimeoutCancellationException) {
            HistoryResyncState.Failed("Pending message history refresh timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleConnectionException) {
            staleConnection()
        } catch (error: Exception) {
            HistoryResyncState.Failed(
                error.message?.take(160) ?: "Pending message history refresh failed",
            )
        }
    }

    private suspend fun resyncBuffer(
        buffer: BufferEntity,
        client: IrcClient,
        isCurrent: () -> Boolean,
        range: HistoryRefreshRange,
        publishState: Boolean,
        healSparseGaps: Boolean,
    ): HistoryResyncState {
        val cancellationGeneration = if (publishState) {
            manualCancellationGeneration(buffer.id)
        } else {
            null
        }
        diagnostics.record("history", "buffer_sync_requested") {
            mapOf(
                "network_id" to buffer.networkId,
                "buffer_id" to buffer.id,
                "range" to range.name,
            )
        }
        val source = ClientHistorySource(client)
        if (source.availability() !is HistoryAvailability.Ready) {
            if (publishState) {
                publishManualState(
                    buffer.id,
                    checkNotNull(cancellationGeneration),
                    HistoryResyncState.WaitingForCapability,
                )
            }
            when (awaitBouncerCapability(buffer.networkId, client, isCurrent)) {
                CapabilityAvailability.AVAILABLE -> Unit
                CapabilityAvailability.UNSUPPORTED -> {
                    if (publishState) {
                        publishManualState(
                            buffer.id,
                            checkNotNull(cancellationGeneration),
                            HistoryResyncState.Unsupported,
                        )
                    }
                    return HistoryResyncState.Unsupported
                }
                CapabilityAvailability.PENDING -> {
                    val failed = HistoryResyncState.Failed("History support is still negotiating; try again")
                    if (publishState) {
                        publishManualState(
                            buffer.id,
                            checkNotNull(cancellationGeneration),
                            failed,
                        )
                    }
                    return failed
                }
            }
        }
        if (publishState) {
            publishManualState(
                buffer.id,
                checkNotNull(cancellationGeneration),
                HistoryResyncState.Running(limit = range.messageLimit),
            )
        }
        val result = coalesced(
            RequestSpec(
                key = RequestKey(buffer.networkId, buffer.id),
                intent = if (publishState) RequestIntent.MANUAL else RequestIntent.AUTOMATIC,
                range = range,
                sourceIdentity = source.flightIdentity(),
                cancellationGeneration = cancellationGeneration,
            ),
        ) {
            syncBufferRange(
                networkId = buffer.networkId,
                bufferId = buffer.id,
                target = buffer.displayName,
                source = source,
                isCurrent = isCurrent,
                range = range,
                healSparseGaps = healSparseGaps,
            )
        }
        if (publishState) {
            publishManualState(buffer.id, checkNotNull(cancellationGeneration), result)
        }
        return result
    }

    /** Cancel the active user-requested refresh for [bufferId], if one exists. */
    override fun cancelBufferResync(bufferId: Long) {
        val cancellationGeneration = manualCancellationGenerations
            .computeIfAbsent(bufferId) { AtomicLong() }
            .incrementAndGet()
        states.update { it - bufferId }
        scope.launch {
            val requests = activeGuard.withLock {
                active.filterKeys { spec ->
                    spec.key.bufferId == bufferId &&
                        spec.intent == RequestIntent.MANUAL &&
                        spec.cancellationGeneration?.let { it < cancellationGeneration } == true
                }.values.toList()
            }
            requests.forEach { it.cancel() }
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
    ): HistoryResyncState = coalesced(
        RequestSpec(
            RequestKey(networkId, null),
            RequestIntent.AUTOMATIC,
            sourceIdentity = source.flightIdentity(),
        ),
    ) {
        diagnostics.record("history", "network_sync_started") {
            mapOf("network_id" to networkId, "open_buffers" to openBuffers.size)
        }
        when (source.availability()) {
            HistoryAvailability.Unsupported -> return@coalesced HistoryResyncState.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return@coalesced historyUnavailable()
            is HistoryAvailability.Ready -> Unit
        }
        // A room row's newest message is not a reliable reconnect cursor: a newer push-delivered
        // message in one buffer can otherwise hide an older missed message in another. The wall
        // clock bounds discovery but is never persisted; only completed server response metadata
        // can advance the dedicated whole-network cursor.
        val previousSync = syncPrefs.lastSuccessfulSync(networkId)
        val lower = (previousSync ?: Instant.EPOCH.toEpochMilli())
            .minus(TARGETS_FUZZ_MS)
            .coerceAtLeast(Instant.EPOCH.toEpochMilli())
        val upper = Instant.now().toEpochMilli() + TARGETS_FUZZ_MS
        val result = try {
            val discovery = discoverTargets(source, upper, lower)
            val targetPass = syncTargets(
                networkId = networkId,
                targets = (openBuffers + discovery.targets.map { null to it })
                    .distinctBy { source.normalizeTarget(it.second) },
                source = source,
                isCurrent = isCurrent,
                // Use the last completed whole-network pass, not Room's newest row, as the
                // reconnect cursor. Replaying this overlap is safe because canonical aliases
                // deduplicate it.
                reconnectBoundary = previousSync?.let { Boundary(msgid = null, serverTime = lower) },
                // A live self-JOIN may precede the first pass. Seed a fresh network with LATEST so
                // that row cannot hide retained history.
                includeRecentOverlap = previousSync == null,
            )
            val inserted = targetPass.inserted
            val status = discovery.status.merge(targetPass.status)
            val highWater = maxHighWater(
                previousSync,
                discovery.highWater,
                targetPass.highWater,
            )
            if (status == WorkStatus.Complete && isCurrent() && highWater != null) {
                syncPrefs.setLastSuccessfulSync(networkId, highWater)
            }
            status.toState(inserted)
        } catch (_: TimeoutCancellationException) {
            HistoryResyncState.Failed("History refresh timed out")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: StaleConnectionException) {
            staleConnection()
        } catch (error: Exception) {
            HistoryResyncState.Failed(
                error.message?.take(160) ?: "History refresh failed",
            )
        }
        diagnostics.record("history", "network_sync_finished") {
            mapOf(
                "network_id" to networkId,
                "targets" to openBuffers.size,
                "result" to result::class.simpleName,
            )
        }
        result
    }

    /**
     * Enumerate the complete TARGETS interval before the network cursor is advanced. TARGETS has
     * BETWEEN ordering semantics. Each next upper bound overlaps the oldest returned millisecond;
     * target identity deduplication absorbs that replay while preserving same-timestamp ties. A
     * saturated tie that cannot move beyond the overlap is explicitly incomplete.
     */
    private suspend fun discoverTargets(
        source: HistorySource,
        upper: Long,
        lower: Long,
    ): TargetDiscovery {
        val limit = source.pageLimit().coerceAtLeast(1)
        val targets = LinkedHashMap<String, String>()
        var pageUpper = upper
        var highWater: Long? = null
        var previousTie: Pair<Long, Set<String>>? = null
        repeat(targetsRequestLimit.coerceAtLeast(1)) { requestIndex ->
            val response = requestTargets(
                source,
                ChatHistoryRequest(
                    subcommand = ChatHistoryRequest.Subcommand.TARGETS,
                    target = "*",
                    bound1 = ChatHistorySelectors.timestamp(pageUpper),
                    bound2 = ChatHistorySelectors.timestamp(lower),
                    limit = limit,
                ),
            )
            val page = response.targets
            page.forEach { target ->
                targets.putIfAbsent(source.normalizeTarget(target.name), target.name)
                highWater = maxHighWater(highWater, target.latestMessageTime)
            }
            if (response.endOfHistory || page.isEmpty()) {
                return TargetDiscovery(targets.values.toList(), WorkStatus.Complete, highWater)
            }

            val oldest = page.minOf { it.latestMessageTime }
            val tiedKeys = page.asSequence()
                .filter { it.latestMessageTime == oldest }
                .map { source.normalizeTarget(it.name) }
                .toSet()
            if (previousTie == (oldest to tiedKeys)) {
                val reason = if (page.size >= limit) {
                    "CHATHISTORY TARGETS saturated a timestamp tie and could not advance"
                } else {
                    "CHATHISTORY TARGETS pagination did not advance"
                }
                return TargetDiscovery(
                    targets.values.toList(),
                    WorkStatus.Incomplete(reason),
                    highWater,
                )
            }
            previousTie = oldest to tiedKeys

            // BETWEEN excludes both timestamp selectors. Move one millisecond past the oldest
            // timestamp so every tied target is replayed and deduplicated instead of skipped.
            val nextUpper = oldest.takeIf { it < Long.MAX_VALUE }?.plus(1)
            if (nextUpper == null || nextUpper >= pageUpper || nextUpper <= lower) {
                val reason = if (page.size >= limit && nextUpper != null && nextUpper >= pageUpper) {
                    "CHATHISTORY TARGETS saturated a timestamp tie and could not advance"
                } else {
                    "CHATHISTORY TARGETS returned an unusable boundary"
                }
                return TargetDiscovery(
                    targets.values.toList(),
                    WorkStatus.Incomplete(reason),
                    highWater,
                )
            }
            pageUpper = nextUpper
            if (requestIndex + 1 >= targetsRequestLimit.coerceAtLeast(1)) {
                return TargetDiscovery(
                    targets.values.toList(),
                    WorkStatus.Capped(
                        "CHATHISTORY TARGETS reached its $targetsRequestLimit-request safety cap",
                        targetsRequestLimit,
                    ),
                    highWater,
                )
            }
        }
        error("unreachable")
    }

    internal suspend fun resyncBuffer(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
        range: HistoryRefreshRange = HistoryRefreshRange.MISSING,
    ): HistoryResyncState {
        val cancellationGeneration = manualCancellationGeneration(bufferId)
        return coalesced(
            RequestSpec(
                RequestKey(networkId, bufferId),
                RequestIntent.MANUAL,
                range,
                source.flightIdentity(),
                cancellationGeneration,
            ),
        ) {
            syncBufferRange(
                networkId,
                bufferId,
                target,
                source,
                isCurrent,
                range,
                healSparseGaps = true,
            )
        }
    }

    internal suspend fun reconcileBuffer(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean = { true },
    ): HistoryResyncState = coalesced(
        RequestSpec(
            RequestKey(networkId, bufferId),
            RequestIntent.AUTOMATIC,
            HistoryRefreshRange.MISSING,
            source.flightIdentity(),
        ),
    ) {
        syncBufferRange(
            networkId,
            bufferId,
            target,
            source,
            isCurrent,
            HistoryRefreshRange.MISSING,
            healSparseGaps = false,
        )
    }

    private suspend fun syncBufferRange(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean,
        range: HistoryRefreshRange,
        healSparseGaps: Boolean,
    ): HistoryResyncState {
        when (source.availability()) {
            HistoryAvailability.Unsupported -> return HistoryResyncState.Unsupported
            HistoryAvailability.NegotiatingOrOffline -> return historyUnavailable()
            is HistoryAvailability.Ready -> Unit
        }
        if (!isCurrent()) return staleConnection()
        val before = messageCount(bufferId)
        return try {
            val work = when (range) {
                HistoryRefreshRange.MISSING -> syncTarget(
                    networkId,
                    bufferId,
                    target,
                    source,
                    isCurrent,
                    includeRecentOverlap = true,
                    healSparseGaps = healSparseGaps,
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
                    cutoff = range.cutoffMillis(Instant.now().toEpochMilli())!!,
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
            work.status.toState(inserted)
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
    ): WorkResult = paginateMessages(
        networkId = networkId,
        target = target,
        source = source,
        isCurrent = isCurrent,
        subcommand = ChatHistoryRequest.Subcommand.AFTER,
        initialBoundary = Boundary(msgid = null, serverTime = cutoff),
        maxRequests = paginationRequestLimit,
        onFetched = { fetched ->
            states.update { it + (bufferId to HistoryResyncState.Running(fetched, range.messageLimit)) }
        },
    )

    /** Fetch newest pages first so the all-history cap remains useful for active chats. */
    private suspend fun syncAllAvailable(
        networkId: Long,
        bufferId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean,
    ): WorkResult {
        val pageLimit = source.pageLimit()
        if (!isCurrent()) throw StaleConnectionException()
        val latestRequest = ChatHistoryRequest(
            ChatHistoryRequest.Subcommand.LATEST,
            target,
            limit = minOf(pageLimit, ALL_HISTORY_LIMIT),
        )
        val latest = request(source, latestRequest)
        if (!isCurrent()) throw StaleConnectionException()
        ingest(networkId, latestRequest, latest)
        val fetched = latest.primaryMessageCount
        var highWater = latest.highWater()
        states.update { it + (bufferId to HistoryResyncState.Running(fetched, ALL_HISTORY_LIMIT)) }
        if (latest.isTerminalPage()) return WorkResult(highWater = highWater)

        val oldest = latest.directionalBoundary(ChatHistoryRequest.Subcommand.LATEST)?.toBoundary()
            ?: return WorkResult(
                WorkStatus.Incomplete("CHATHISTORY LATEST returned no usable oldest boundary"),
                highWater,
            )
        if (oldest.selector(source, source.supportsReference(HistoryReferenceType.MSGID)) == null) {
            return WorkResult(
                WorkStatus.Incomplete("CHATHISTORY LATEST returned an unsupported oldest boundary"),
                highWater,
            )
        }
        if (fetched >= ALL_HISTORY_LIMIT) {
            return WorkResult(
                WorkStatus.Capped(
                    "History refresh reached its $ALL_HISTORY_LIMIT-message safety cap",
                    ALL_HISTORY_LIMIT,
                ),
                highWater,
            )
        }
        if (paginationRequestLimit <= 1) {
            return WorkResult(
                WorkStatus.Capped(
                    "History refresh reached its $paginationRequestLimit-request safety cap",
                    paginationRequestLimit,
                ),
                highWater,
            )
        }

        val older = paginateMessages(
            networkId = networkId,
            target = target,
            source = source,
            isCurrent = isCurrent,
            subcommand = ChatHistoryRequest.Subcommand.BEFORE,
            initialBoundary = oldest,
            maxRequests = paginationRequestLimit - 1,
            maxEvents = ALL_HISTORY_LIMIT - fetched,
            onFetched = { pageFetched ->
                states.update {
                    it + (bufferId to HistoryResyncState.Running(fetched + pageFetched, ALL_HISTORY_LIMIT))
                }
            },
        )
        highWater = maxHighWater(highWater, older.highWater)
        return WorkResult(older.status, highWater)
    }

    private suspend fun syncTargets(
        networkId: Long,
        targets: List<Pair<Long?, String>>,
        source: HistorySource,
        isCurrent: () -> Boolean,
        reconnectBoundary: Boundary?,
        includeRecentOverlap: Boolean,
    ): TargetPass {
        when (source.availability()) {
            HistoryAvailability.Unsupported -> error("History support disappeared during reconciliation")
            HistoryAvailability.NegotiatingOrOffline -> error("History support became unavailable")
            is HistoryAvailability.Ready -> Unit
        }
        if (!isCurrent()) throw StaleConnectionException()
        var inserted = 0
        var status: WorkStatus = WorkStatus.Complete
        var highWater: Long? = null
        for ((knownBufferId, target) in targets) {
            if (!isCurrent()) throw StaleConnectionException()
            val canonicalRoomId = knownBufferId
                ?: processor.ensureHistoryRoom(networkId, target)
            val before = messageCount(canonicalRoomId)
            val targetResult = syncTarget(
                networkId,
                canonicalRoomId,
                target,
                source,
                isCurrent,
                includeRecentOverlap,
                reconnectBoundary,
            )
            inserted += (messageCount(canonicalRoomId) - before).coerceAtLeast(0)
            status = status.merge(targetResult.status)
            highWater = maxHighWater(highWater, targetResult.highWater)
        }
        return TargetPass(inserted, status, highWater)
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
    ): WorkResult {
        val pageLimit = source.pageLimit()
        val bufferId = knownBufferId
        val protocolCursor = db.historyCursorDao().byRoom(bufferId)
        val boundary = reconnectBoundary ?: if (protocolCursor != null) {
            Boundary(protocolCursor.newestMsgid, protocolCursor.newestServerTime)
                .takeIf { it.msgid?.isNotEmpty() == true || it.serverTime != null }
        } else {
            latestBoundaryFromRoom(bufferId)
        }
        // A live self-JOIN/part/topic row is not evidence that its retained chat history was
        // imported. In particular, older releases marked a network sync complete after using that
        // row as an AFTER cursor, leaving an existing bouncer channel permanently empty. Bypass
        // AFTER for a target with no stored chat content and seed it from LATEST instead.
        val lacksStoredChat = !hasStoredChat(bufferId)
        val knownOldestTime = when {
            lacksStoredChat -> null
            protocolCursor != null -> protocolCursor.oldestServerTime
            else -> db.messageDao().oldestTime(bufferId)
        }
        val pagingBoundary = when {
            reconnectBoundary != null -> reconnectBoundary
            lacksStoredChat -> null
            else -> boundary
        }
        var highWater: Long? = null
        if (pagingBoundary != null) {
            val after = paginateMessages(
                networkId = networkId,
                target = target,
                source = source,
                isCurrent = isCurrent,
                subcommand = ChatHistoryRequest.Subcommand.AFTER,
                initialBoundary = pagingBoundary,
                maxRequests = paginationRequestLimit,
            )
            highWater = maxHighWater(highWater, after.highWater)
            if (after.status != WorkStatus.Complete) {
                return WorkResult(after.status, highWater)
            }
        }

        var latestPage: ChatHistoryResponse.Messages? = null
        if (boundary == null || includeRecentOverlap || lacksStoredChat) {
            val latestRequest = ChatHistoryRequest(
                ChatHistoryRequest.Subcommand.LATEST,
                target,
                limit = pageLimit,
            )
            val latest = request(source, latestRequest)
            latestPage = latest
            if (!isCurrent()) throw StaleConnectionException()
            ingest(networkId, latestRequest, latest)
            if (!isCurrent()) throw StaleConnectionException()
            highWater = maxHighWater(highWater, latest.highWater())
        }

        // A newest-page overlap catches small holes but cannot repair a sparse timeline whose
        // newest and oldest rows survived while more than one server page vanished locally. Only
        // explicit MISSING reconciliation performs this bounded BETWEEN walk. Automatic visible-
        // buffer and reconnect reconciliation stop at the newest-page overlap so merely reopening
        // a chat cannot import hundreds of older retained messages.
        val overlap = latestPage
        if (overlap == null) {
            return WorkResult(highWater = highWater)
        }
        if (overlap.isTerminalPage()) {
            return WorkResult(highWater = highWater)
        }
        val backwardBoundary = overlap.directionalBoundary(ChatHistoryRequest.Subcommand.LATEST)?.toBoundary()
            ?: return WorkResult(
                WorkStatus.Incomplete("CHATHISTORY LATEST returned no usable oldest boundary"),
                highWater,
            )
        if (backwardBoundary.selector(source, source.supportsReference(HistoryReferenceType.MSGID)) == null) {
            return WorkResult(
                WorkStatus.Incomplete("CHATHISTORY LATEST returned an unsupported oldest boundary"),
                highWater,
            )
        }
        if (!healSparseGaps || knownOldestTime == null) {
            return WorkResult(highWater = highWater)
        }
        if (backwardBoundary.serverTime != null && backwardBoundary.serverTime < knownOldestTime) {
            return WorkResult(highWater = highWater)
        }
        val lower = Boundary(
            msgid = null,
            serverTime = knownOldestTime.minus(HISTORY_TIE_OVERLAP_MS)
                .coerceAtLeast(Instant.EPOCH.toEpochMilli()),
        )
        val between = paginateMessages(
            networkId = networkId,
            target = target,
            source = source,
            isCurrent = isCurrent,
            subcommand = ChatHistoryRequest.Subcommand.BETWEEN,
            initialBoundary = backwardBoundary,
            secondBoundary = lower,
            maxRequests = MISSING_BACKFILL_PAGE_LIMIT,
            maxEvents = MISSING_BACKFILL_MESSAGE_LIMIT,
        )
        return WorkResult(
            between.status,
            maxHighWater(highWater, between.highWater),
        )
    }

    private suspend fun <T> withNetworkLock(networkId: Long, block: suspend () -> T): T =
        CanonicalHistorySingleFlight.withNetwork(networkId, block)

    /**
     * Traverse one directional response sequence. Page size is deliberately not a completion
     * signal: a completed batch may be short or oversized, and context events do not count toward
     * the server's limit. Only response metadata, an unusable cursor, or the safety caps terminate
     * the traversal.
     */
    private suspend fun paginateMessages(
        networkId: Long,
        target: String,
        source: HistorySource,
        isCurrent: () -> Boolean,
        subcommand: ChatHistoryRequest.Subcommand,
        initialBoundary: Boundary,
        secondBoundary: Boundary? = null,
        maxRequests: Int,
        maxEvents: Int? = null,
        onFetched: (Int) -> Unit = {},
    ): WorkResult {
        require(
            subcommand == ChatHistoryRequest.Subcommand.AFTER ||
                subcommand == ChatHistoryRequest.Subcommand.BEFORE ||
                subcommand == ChatHistoryRequest.Subcommand.BETWEEN,
        )
        val requestCap = maxRequests.coerceAtLeast(1)
        if (maxRequests <= 0 || maxEvents != null && maxEvents <= 0) {
            val limit = maxEvents ?: maxRequests
            return WorkResult(
                WorkStatus.Capped("CHATHISTORY $subcommand reached its $limit safety cap", limit),
            )
        }

        var boundary = initialBoundary
        var msgidAllowed = source.supportsReference(HistoryReferenceType.MSGID)
        var fetched = 0
        var highWater: Long? = null
        val usedSelectors = HashSet<String>()
        repeat(requestCap) { requestIndex ->
            if (!isCurrent()) throw StaleConnectionException()
            val remaining = maxEvents?.minus(fetched)
            if (remaining != null && remaining <= 0) {
                return WorkResult(
                    WorkStatus.Capped(
                        "CHATHISTORY $subcommand reached its $maxEvents-message safety cap",
                        maxEvents,
                    ),
                    highWater,
                )
            }
            val requested = requestAtBoundary(
                source = source,
                subcommand = subcommand,
                target = target,
                boundary = boundary,
                secondBoundary = secondBoundary,
                limit = remaining?.let { minOf(source.pageLimit(), it) } ?: source.pageLimit(),
                msgidAllowed = msgidAllowed,
            ) ?: return WorkResult(
                WorkStatus.Incomplete("CHATHISTORY $subcommand has no usable response boundary"),
                highWater,
            )
            msgidAllowed = requested.msgidAllowed
            usedSelectors += requested.selector.value
            val page = requested.response
            if (!isCurrent()) throw StaleConnectionException()
            ingest(networkId, requested.request, page)
            if (!isCurrent()) throw StaleConnectionException()
            fetched += page.primaryMessageCount
            onFetched(fetched)
            highWater = maxHighWater(highWater, page.highWater())
            if (page.isTerminalPage()) {
                return WorkResult(highWater = highWater)
            }

            val next = page.directionalBoundary(subcommand)?.toBoundary() ?: return WorkResult(
                WorkStatus.Incomplete(
                    "CHATHISTORY $subcommand returned no usable response boundary",
                ),
                highWater,
            )
            val nextSelector = next.selector(source, msgidAllowed)
                ?: return WorkResult(
                    WorkStatus.Incomplete(
                        "CHATHISTORY $subcommand returned an unsupported response boundary",
                    ),
                    highWater,
                )
            val wrongTimestampDirection =
                requested.selector.type == HistoryReferenceType.TIMESTAMP &&
                    nextSelector.type == HistoryReferenceType.TIMESTAMP &&
                    when (subcommand) {
                        ChatHistoryRequest.Subcommand.AFTER ->
                            next.serverTime!! <= boundary.serverTime!!
                        ChatHistoryRequest.Subcommand.BEFORE,
                        ChatHistoryRequest.Subcommand.BETWEEN,
                        -> next.serverTime!! >= boundary.serverTime!!
                        else -> false
                    }
            if (nextSelector.value in usedSelectors || wrongTimestampDirection) {
                return WorkResult(
                    WorkStatus.Incomplete("CHATHISTORY $subcommand pagination did not advance"),
                    highWater,
                )
            }
            if (maxEvents != null && fetched >= maxEvents) {
                return WorkResult(
                    WorkStatus.Capped(
                        "CHATHISTORY $subcommand reached its $maxEvents-message safety cap",
                        maxEvents,
                    ),
                    highWater,
                )
            }
            if (requestIndex + 1 >= requestCap) {
                return WorkResult(
                    WorkStatus.Capped(
                        "CHATHISTORY $subcommand reached its $requestCap-request safety cap",
                        requestCap,
                    ),
                    highWater,
                )
            }
            boundary = next
        }
        error("unreachable")
    }

    private suspend fun requestAtBoundary(
        source: HistorySource,
        subcommand: ChatHistoryRequest.Subcommand,
        target: String,
        boundary: Boundary,
        secondBoundary: Boundary?,
        limit: Int,
        msgidAllowed: Boolean,
    ): RequestedPage? {
        val selector = boundary.selector(source, msgidAllowed) ?: return null
        val secondSelector = secondBoundary?.selector(source, msgidAllowed = false)?.value
        if (secondBoundary != null && secondSelector == null) return null
        val historyRequest = ChatHistoryRequest(
            subcommand = subcommand,
            target = target,
            bound1 = selector.value,
            bound2 = secondSelector,
            limit = limit.coerceAtLeast(1),
        )
        return try {
            RequestedPage(request(source, historyRequest), historyRequest, selector, msgidAllowed)
        } catch (error: IrcCommandException) {
            if (selector.type != HistoryReferenceType.MSGID || error.code != INVALID_MSGREFTYPE) {
                throw error
            }
            val timestamp = boundary.selector(source, msgidAllowed = false) ?: return null
            val fallbackRequest = historyRequest.copy(bound1 = timestamp.value)
            RequestedPage(
                response = request(source, fallbackRequest),
                request = fallbackRequest,
                selector = timestamp,
                msgidAllowed = false,
            )
        }
    }

    private suspend fun coalesced(
        spec: RequestSpec,
        block: suspend () -> HistoryResyncState,
    ): HistoryResyncState {
        while (true) {
            val flight = activeGuard.withLock {
                spec.ensureNotManuallyCancelled()
                val joined = when (spec.intent) {
                    RequestIntent.MANUAL -> active[spec]?.let { ActiveRequest(spec, it) }
                    RequestIntent.AUTOMATIC -> active.entries
                        .firstOrNull { (candidate) ->
                            candidate.key == spec.key &&
                                candidate.intent == RequestIntent.MANUAL &&
                                candidate.sourceIdentity == spec.sourceIdentity
                        }
                        ?.let { (candidate, deferred) -> ActiveRequest(candidate, deferred) }
                        ?: active[spec]?.let { ActiveRequest(spec, it) }
                }
                joined ?: scope.async {
                    spec.ensureNotManuallyCancelled()
                    withNetworkLock(spec.key.networkId) {
                        spec.ensureNotManuallyCancelled()
                        block()
                    }
                }
                    .let { created ->
                        active[spec] = created
                        created.invokeOnCompletion {
                            scope.launch {
                                activeGuard.withLock {
                                    if (active[spec] === created) active.remove(spec)
                                }
                            }
                        }
                        ActiveRequest(spec, created)
                    }
            }
            try {
                return flight.deferred.await()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                val cancelledJoinedManual =
                    spec.intent == RequestIntent.AUTOMATIC &&
                        flight.spec.intent == RequestIntent.MANUAL &&
                        flight.deferred.isCancelled
                if (!cancelledJoinedManual) throw cancelled
            } finally {
                activeGuard.withLock {
                    if (flight.deferred.isCompleted && active[flight.spec] === flight.deferred) {
                        active.remove(flight.spec)
                    }
                }
            }
        }
    }

    private suspend fun request(
        source: HistorySource,
        request: ChatHistoryRequest,
    ): ChatHistoryResponse.Messages {
        val response = withTimeout(requestTimeoutMs) { source.chathistory(request) }
        return response as? ChatHistoryResponse.Messages
            ?: error("CHATHISTORY ${request.subcommand} returned a TARGETS response")
    }

    private suspend fun requestTargets(
        source: HistorySource,
        request: ChatHistoryRequest,
    ): ChatHistoryResponse.Targets {
        val response = withTimeout(requestTimeoutMs) { source.chathistory(request) }
        return response as? ChatHistoryResponse.Targets
            ?: error("CHATHISTORY TARGETS returned a message response")
    }

    private fun HistorySource.pageLimit(): Int =
        ((availability() as? HistoryAvailability.Ready)?.pageLimit ?: PAGE_LIMIT)
            .coerceAtMost(PAGE_LIMIT)
            .coerceAtLeast(1)

    private fun HistorySource.supportsReference(type: HistoryReferenceType): Boolean =
        (availability() as? HistoryAvailability.Ready)
            ?.referenceTypes
            ?.contains(type) == true

    private suspend fun latestBoundaryFromRoom(bufferId: Long): Boundary? =
        db.messageDao().latestBoundary(bufferId)?.let { Boundary(it.msgid, it.serverTime) }

    private suspend fun messageCount(bufferId: Long): Int = db.messageDao().countForBuffer(bufferId)

    private suspend fun hasStoredChat(bufferId: Long): Boolean = db.messageDao().hasStoredChat(bufferId)

    private fun Boundary.selector(source: HistorySource, msgidAllowed: Boolean): Selector? =
        if (
            msgidAllowed && source.supportsReference(HistoryReferenceType.MSGID) &&
            msgid?.isNotEmpty() == true
        ) {
            Selector(ChatHistorySelectors.msgid(msgid), HistoryReferenceType.MSGID)
        } else if (source.supportsReference(HistoryReferenceType.TIMESTAMP) && serverTime != null) {
            Selector(ChatHistorySelectors.timestamp(serverTime), HistoryReferenceType.TIMESTAMP)
        } else {
            null
        }

    private fun ChatHistoryReference.toBoundary(): Boundary = Boundary(msgid, serverTime)

    private fun ChatHistoryResponse.Messages.isTerminalPage(): Boolean =
        endOfHistory || primaryMessageCount == 0

    private fun ChatHistoryResponse.Messages.directionalBoundary(
        subcommand: ChatHistoryRequest.Subcommand,
    ): ChatHistoryReference? = when (subcommand) {
        ChatHistoryRequest.Subcommand.AFTER -> newest
        ChatHistoryRequest.Subcommand.LATEST,
        ChatHistoryRequest.Subcommand.BEFORE,
        ChatHistoryRequest.Subcommand.BETWEEN,
        -> oldest
        ChatHistoryRequest.Subcommand.AROUND -> null
        ChatHistoryRequest.Subcommand.TARGETS -> error("TARGETS is not a message page")
    }

    private fun ChatHistoryResponse.Messages.hasUsableDirectionalBoundary(
        subcommand: ChatHistoryRequest.Subcommand,
        source: HistorySource,
    ): Boolean = directionalBoundary(subcommand)
        ?.toBoundary()
        ?.selector(source, source.supportsReference(HistoryReferenceType.MSGID)) != null

    private fun ChatHistoryResponse.Messages.highWater(): Long? =
        if (primaryMessageCount == 0) null else maxHighWater(oldest?.serverTime, newest?.serverTime)

    private suspend fun ingest(
        networkId: Long,
        request: ChatHistoryRequest,
        page: ChatHistoryResponse.Messages,
    ) = processor.persistHistoryPage(networkId, request, page)

    private fun manualCancellationGeneration(bufferId: Long): Long =
        manualCancellationGenerations.computeIfAbsent(bufferId) { AtomicLong() }.get()

    private fun publishManualState(
        bufferId: Long,
        cancellationGeneration: Long,
        state: HistoryResyncState,
    ) {
        states.update { current ->
            if (manualCancellationGeneration(bufferId) == cancellationGeneration) {
                current + (bufferId to state)
            } else {
                current - bufferId
            }
        }
    }

    private fun RequestSpec.manualCancellationRequested(): Boolean {
        if (intent != RequestIntent.MANUAL) return false
        val bufferId = key.bufferId ?: return false
        return cancellationGeneration != manualCancellationGeneration(bufferId)
    }

    private fun RequestSpec.ensureNotManuallyCancelled() {
        if (manualCancellationRequested()) {
            throw CancellationException("manual history refresh cancelled before execution")
        }
    }

    private fun WorkStatus.toState(inserted: Int): HistoryResyncState = when (this) {
        WorkStatus.Complete ->
            if (inserted > 0) HistoryResyncState.Updated(inserted) else HistoryResyncState.UpToDate
        is WorkStatus.Incomplete -> HistoryResyncState.Incomplete(inserted, reason)
        is WorkStatus.Capped -> HistoryResyncState.Capped(inserted, limit, reason)
    }

    private fun WorkStatus.merge(other: WorkStatus): WorkStatus = when {
        this is WorkStatus.Incomplete -> this
        other is WorkStatus.Incomplete -> other
        this is WorkStatus.Capped -> this
        other is WorkStatus.Capped -> other
        else -> WorkStatus.Complete
    }

    private fun maxHighWater(vararg values: Long?): Long? = values.filterNotNull().maxOrNull()

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
        if (client.historyAvailability is HistoryAvailability.Ready) return CapabilityAvailability.AVAILABLE
        val role = db.networkDao().byId(networkId)?.role
        if (role != NetworkRole.BOUNCER_CHILD) {
            return if (client.historyAvailability is HistoryAvailability.Unsupported) {
                CapabilityAvailability.UNSUPPORTED
            } else {
                CapabilityAvailability.PENDING
            }
        }
        val ready = withTimeoutOrNull(CAPABILITY_WAIT_TIMEOUT_MS) {
            client.state.filterIsInstance<IrcClientState.Ready>().first { snapshot ->
                snapshot.caps.any { it == CHATHISTORY_CAP || it.startsWith("$CHATHISTORY_CAP=") } &&
                    client.historyAvailability is HistoryAvailability.Ready
            }
        }
        return when {
            !isCurrent() -> CapabilityAvailability.PENDING
            ready != null || client.historyAvailability is HistoryAvailability.Ready -> CapabilityAvailability.AVAILABLE
            else -> CapabilityAvailability.PENDING
        }
    }

    private class ClientHistorySource(private val client: IrcClient) : HistorySource {
        override fun availability(): HistoryAvailability = client.historyAvailability

        override fun flightIdentity(): Any = client

        override fun normalizeTarget(target: String): String = client.isupport.identityRules.normalize(target)

        override suspend fun chathistory(request: ChatHistoryRequest): ChatHistoryResponse =
            client.chathistory(request)
    }

    private class StaleConnectionException : Exception()

    private fun staleConnection(): HistoryResyncState.Failed =
        HistoryResyncState.Failed("Connection changed; try again")

    private fun historyUnavailable(): HistoryResyncState.Failed =
        HistoryResyncState.Failed("History support is still negotiating or the connection is offline")

    private companion object {
        const val CHATHISTORY_CAP = "draft/chathistory"
        const val PAGE_LIMIT = 100
        const val REQUEST_TIMEOUT_MS = 35_000L
        const val PENDING_MESSAGE_TIMEOUT_MS = 65_000L
        const val TARGETS_FUZZ_MS = 10_000L
        const val HISTORY_TIE_OVERLAP_MS = 1L
        const val CAPABILITY_WAIT_TIMEOUT_MS = 30_000L
        const val ALL_HISTORY_LIMIT = 5_000
        const val PAGINATION_REQUEST_LIMIT = 100
        const val TARGETS_REQUEST_LIMIT = 100
        const val MISSING_BACKFILL_PAGE_LIMIT = 5
        const val MISSING_BACKFILL_MESSAGE_LIMIT = 500
        const val INVALID_MSGREFTYPE = "INVALID_MSGREFTYPE"
        const val HOURS_24_MS = 24L * 60 * 60 * 1_000
        const val DAYS_7_MS = 7L * 24 * 60 * 60 * 1_000
        const val DAYS_30_MS = 30L * 24 * 60 * 60 * 1_000
    }
}
