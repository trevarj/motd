package io.github.trevarj.motd.service

import io.github.trevarj.motd.data.db.BufferEntity
import io.github.trevarj.motd.data.db.BufferType
import io.github.trevarj.motd.data.db.HistoryBackfillCursorEntity
import io.github.trevarj.motd.data.db.MotdDatabase
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.prefs.HistorySyncPrefs
import io.github.trevarj.motd.data.prefs.NoopHistorySyncPrefs
import io.github.trevarj.motd.data.sync.AdvertisedActivity
import io.github.trevarj.motd.data.sync.EventProcessor
import io.github.trevarj.motd.data.sync.HistoryPageLoader
import io.github.trevarj.motd.data.sync.HistoryPruner
import io.github.trevarj.motd.di.ApplicationScope
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.ChatHistoryTarget
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.irc.proto.IrcIdentityRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.lang.ref.WeakReference
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

sealed interface HistoryResyncState {
    data object Idle : HistoryResyncState

    data object WaitingForCapability : HistoryResyncState

    data class Running(
        val fetched: Int = 0,
        val limit: Int? = null,
    ) : HistoryResyncState

    data class Updated(
        val inserted: Int,
    ) : HistoryResyncState

    data object UpToDate : HistoryResyncState

    data object Unsupported : HistoryResyncState

    open class Failed(
        open val reason: String,
    ) : HistoryResyncState {
        open override fun equals(other: Any?): Boolean = other is Failed && javaClass == other.javaClass && reason == other.reason

        open override fun hashCode(): Int = reason.hashCode()

        open override fun toString(): String = "${javaClass.simpleName}(reason=$reason)"
    }

    data class Incomplete(
        val inserted: Int,
        override val reason: String,
        val awaitsTargetClassification: Boolean = false,
        val retryRecommended: Boolean = false,
    ) : Failed(reason)

    data class Capped(
        val inserted: Int,
        val limit: Int,
        override val reason: String,
    ) : Failed(reason)
}

/** Per-buffer progress and actionable failure state for automatic and user-requested history work. */
sealed interface HistorySyncStatus {
    /** Settled cleanly; the buffer carries no entry in the published map. */
    data object Idle : HistorySyncStatus

    /** Registered in the current pass, waiting for a fetch slot. */
    data object Queued : HistorySyncStatus

    /**
     * Queued for a pass that cannot run yet: the app foregrounded without a Ready connection, or a
     * running pass died with its connection. Deliberately distinct from [Queued] so the UI can say
     * "waiting for connection" instead of implying work is under way; both are optimistic, neither
     * is an error.
     */
    data object AwaitingConnection : HistorySyncStatus

    /** A request for this buffer is on the wire right now. */
    data object Syncing : HistorySyncStatus

    /** The server permanently refuses this target (FAIL CHATHISTORY INVALID_TARGET). */
    data object Unavailable : HistorySyncStatus

    data class Partial(
        val reason: String,
    ) : HistorySyncStatus

    data class Failed(
        val reason: String,
    ) : HistorySyncStatus
}

/**
 * One network pass's aggregate progress. [total] grows as targets are registered (open buffers up
 * front, discovered targets mid-pass) and [settled] counts buffers that reached a terminal status,
 * so a UI can render "settled/total" without reconstructing it from the per-buffer status map,
 * where settled buffers no longer appear.
 */
data class SyncPassProgress(
    val total: Int,
    val settled: Int,
    /** A user-requested window fetch rather than a reconnect catch-up; the chrome words it differently. */
    val backfill: Boolean = false,
)

/** An open buffer offered to a network pass, carrying the ordering inputs the pass sorts on. */
data class OpenBufferTarget(
    val id: Long,
    val name: String,
    val pinned: Boolean = false,
)

/** Prevent a cancelled or superseded sync from publishing its initial transient status late. */
internal fun initialSyncStatusIfCurrent(
    current: Map<Long, HistorySyncStatus>,
    bufferId: Long,
    generation: Long,
    currentGeneration: Long?,
    status: HistorySyncStatus,
): Map<Long, HistorySyncStatus> =
    if (currentGeneration == generation) {
        current + (bufferId to status)
    } else {
        current
    }

private val EMPTY_SYNC_STATUSES: StateFlow<Map<Long, HistorySyncStatus>> = MutableStateFlow(emptyMap())
private val EMPTY_PASS_PROGRESS: StateFlow<Map<Long, SyncPassProgress>> = MutableStateFlow(emptyMap())

/** Chat-facing boundary for lifecycle-driven history reconciliation. */
interface HistoryResyncController {
    /**
     * Every buffer with a live or actionable history status. A buffer absent from the map is
     * settled, which is why [HistorySyncStatus.Idle] never appears as a value.
     */
    val syncStatuses: StateFlow<Map<Long, HistorySyncStatus>>
        get() = EMPTY_SYNC_STATUSES

    /** Live pass progress; keys are opaque session identifiers. */
    val passProgress: StateFlow<Map<Long, SyncPassProgress>>
        get() = EMPTY_PASS_PROGRESS

    /**
     * Optimistically mark open buffers as queued for a pass that cannot start yet (the app
     * foregrounded with a non-Ready connection). The real pass adopts these entries when it
     * queues, so the user sees no Idle gap between the optimistic mark and the pass.
     */
    fun markAwaitingConnection(
        networkId: Long,
        openBufferIds: List<Long>,
    ) = Unit

    /**
     * Drop every waiting entry for a network no pass will ever adopt them for — a server that does
     * not support history, or a connection retired by a deliberate disconnect. Backgrounding and
     * Doze intentionally do NOT clear it: those buffers really are still waiting.
     */
    fun clearAwaitingConnection(networkId: Long) = Unit

    fun syncStatus(bufferId: Long): Flow<HistorySyncStatus> =
        syncStatuses
            .map { it[bufferId] ?: HistorySyncStatus.Idle }
            .distinctUntilChanged()

    /**
     * User-requested dismissal of a settled per-buffer status (the "history may be incomplete"
     * chip and the chat-list badge). A later pass that fails again publishes a fresh status.
     */
    fun dismissSyncStatus(bufferId: Long) = Unit

    /**
     * Run caller-driven per-room [work] (a manual window fetch) under the same chat-list chrome a
     * reconnect pass gets: every id is queued up front, shows as syncing while its work runs, and
     * settles when it returns. The default runs the work without publishing anything.
     */
    suspend fun manualPass(
        networkId: Long,
        bufferIds: List<Long>,
        work: suspend (Long) -> Unit,
    ) = bufferIds.forEach { work(it) }

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
 * The sole reconnect/manual tail-revalidation entry point. The coordinator decides WHAT to fetch
 * (targets, ranges, ordering, gap recording, marker convergence) and what to report (states,
 * per-buffer sync status); every wire fetch goes through [HistoryPageLoader], whose bounded
 * per-network wire gate admits each individual CHATHISTORY request against scroll-driven Paging
 * (width 1 — strict serialization — unless labeled-response correlates concurrency). Equivalent
 * whole requests (a reconnect pass, a manual refresh) still coalesce onto one [ActiveFlight], but
 * only to back user-facing status and cancellation — not as a fetch lock: two concurrent
 * same-buffer LATEST fetches are safe because [EventProcessor] deduplicates rows by msgid/identity
 * and gap recording recognizes an already-recorded interval. IRC-derived rows still flow
 * exclusively through [EventProcessor].
 */
@Singleton
class HistoryResyncCoordinator
    @Inject
    constructor(
        private val db: MotdDatabase,
        private val processor: EventProcessor,
        private val syncPrefs: HistorySyncPrefs = NoopHistorySyncPrefs,
        @param:ApplicationScope private val scope: CoroutineScope,
        private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
        // The single wire-fetch primitive: every CHATHISTORY request the coordinator issues goes through
        // this shared singleton so reconnect/manual traversals share the loader's per-network wire gate
        // with scroll-driven Paging. Defaulted so tests keep the four-argument construction.
        private val loader: HistoryPageLoader = HistoryPageLoader(processor),
        // Read at sort time so the chat the user is looking at is admitted in the first fan-out wave.
        // Defaulted so test fixtures keep the shorter construction.
        private val foregroundBuffers: ForegroundBufferTracker = NoopForegroundBufferTracker,
        // Local retention runs right after a network's sync settles: server history is proven live
        // at that moment, so anything trimmed is fetchable again. Defaulted for test fixtures.
        private val pruner: HistoryPruner = HistoryPruner.Noop,
    ) : HistoryResyncController {
        // Reuses the loader's transport seam so a source can drive both the coordinator's orchestration
        // and the loader's fetch primitives directly, and adds the discovery/classification metadata the
        // reconnect pass needs (target normalization, channel detection, and a per-connection flight id).
        internal interface HistorySource : HistoryPageLoader.HistorySource {
            override suspend fun availability(): HistoryAvailability

            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse

            fun flightIdentity(): Any = this

            fun canClassifyTargets(): Boolean = true

            fun normalizeTarget(target: String): String = IrcIdentityRules().normalize(target)

            fun isChannelTarget(target: String): Boolean = IrcIdentityRules().isChannel(target)
        }

        private data class RequestKey(
            val networkId: Long,
            val bufferId: Long?,
        )

        private data class RequestSpec(
            val key: RequestKey,
            val sourceIdentity: Any,
        )

        private data class ActiveFlight(
            val spec: RequestSpec,
            val deferred: Deferred<HistoryResyncState>,
        )

        private data class FlightRegistration(
            val flight: ActiveFlight,
            val ownsFlight: Boolean,
        )

        private sealed interface WorkStatus {
            data object Complete : WorkStatus

            data class Incomplete(
                val reason: String,
                val awaitsTargetClassification: Boolean = false,
                /**
                 * Discovery finished the whole window but could not PROVE a timestamp tie exhausted
                 * (soju 0.10.x omits draft/chathistory-end on short tie pages). Enumeration is done;
                 * only the proof is missing.
                 */
                val unprovenTieOnly: Boolean = false,
            ) : WorkStatus

            data class Capped(
                val reason: String,
                val limit: Int,
            ) : WorkStatus
        }

        private data class WorkResult(
            val status: WorkStatus = WorkStatus.Complete,
            val highWater: Long? = null,
            val inserted: Int = 0,
        )

        private data class TargetPass(
            val inserted: Int,
            val status: WorkStatus,
            val highWater: Long?,
            val retryRecommended: Boolean,
        )

        /** One target's contribution to a pass; skips and refused targets contribute the neutral value. */
        private data class TargetOutcome(
            val inserted: Int = 0,
            val status: WorkStatus = WorkStatus.Complete,
            val highWater: Long? = null,
            val retryRecommended: Boolean = false,
        )

        private data class TargetDiscovery(
            val targets: List<ChatHistoryTarget>,
            val status: WorkStatus,
            val highWater: Long?,
        )

        // Cancellation is non-suspending, so registration and removal share a synchronous monitor.
        private val activeGuard = Any()
        private val activeFlights = LinkedHashMap<RequestSpec, ActiveFlight>()
        private val _syncStatuses = MutableStateFlow<Map<Long, HistorySyncStatus>>(emptyMap())
        override val syncStatuses: StateFlow<Map<Long, HistorySyncStatus>> = _syncStatuses
        private val _passProgress = MutableStateFlow<Map<Long, SyncPassProgress>>(emptyMap())
        override val passProgress: StateFlow<Map<Long, SyncPassProgress>> = _passProgress
        private val syncStatusGenerations = ConcurrentHashMap<Long, AtomicLong>()

        // Buffers optimistically marked [HistorySyncStatus.AwaitingConnection], per network. Entries
        // survive backgrounding and Doze; only an adopting pass or an explicit disconnect clears them.
        private val awaitingByNetwork = ConcurrentHashMap<Long, MutableSet<Long>>()

        // The newest network pass's session, kept across a retryable failure so the catch-up loop's
        // final give-up verdict can still be painted onto whatever that pass left behind.
        private val networkSessions = ConcurrentHashMap<Long, SyncStatusSession>()

        // Manual backfills never take [networkSessions]: a minutes-long pass must not evict a
        // reconnect session that still needs to settle its badges. One per network, enforced by
        // HistoryWindowFetcher.
        private val backfillSessions = ConcurrentHashMap<Long, SyncStatusSession>()

        // Connections the caller took offline deliberately, keyed by network. A pass runs in this
        // coordinator's own scope, so it outlives the caller that started it: this tombstone is how a
        // pass still winding down for a retired connection learns that nothing it publishes may reach
        // the UI. At most one entry per network, replaced by that network's next retirement; a tombstone
        // a live pass has outgrown simply covers nothing. See [NetworkRetirement].
        private val retiredNetworks = ConcurrentHashMap<Long, NetworkRetirement>()

        // Monotonic ticket drawn by every network session under [retireGuard]. It is what makes an
        // unnamed retirement safe: such a tombstone covers exactly the sessions whose ticket predates
        // it, so a later reconnect's pass is never silenced by it.
        private val sessionTickets = AtomicLong()

        // Serializes retirement against a pass's terminal so a terminal cannot slip its republication
        // between the tombstone and the clear, and arbitrates the per-network session slot: every read
        // of [networkSessions] that decides who owns a network's aggregate progress entry, and every
        // write that hands that slot over, happens under it. Always the OUTER lock: a session's own
        // monitor is only ever taken inside it, never the reverse.
        //
        // Internal rather than private only so a test can hold it across an interleaving: the invariant
        // it exists for is that an ownership decision and the publication it authorizes are one step,
        // and no public entry point holds it long enough to prove that from the outside.
        internal val retireGuard = Any()
        internal var requestTimeoutMs: Long = REQUEST_TIMEOUT_MS
        internal var targetsRequestLimit: Int = TARGETS_REQUEST_LIMIT

        override fun dismissSyncStatus(bufferId: Long) {
            // Bump the generation first so the dismissal wins any race with an in-flight pass: that
            // pass's later publishes for this buffer carry a stale generation and are dropped.
            syncStatusGenerations.computeIfAbsent(bufferId) { AtomicLong() }.incrementAndGet()
            _syncStatuses.update { it - bufferId }
        }

        /**
         * Optimistic pre-pass marking. Bumping the generation is the whole adoption story: a stale
         * in-flight publish from a dying pass carries an older generation and is dropped, and the real
         * pass's own registration bumps again, replacing waiting with Queued without an Idle gap.
         */
        override fun markAwaitingConnection(
            networkId: Long,
            openBufferIds: List<Long>,
        ) {
            if (openBufferIds.isEmpty()) return
            val waiting = awaitingByNetwork.computeIfAbsent(networkId) { ConcurrentHashMap.newKeySet() }
            openBufferIds.forEach { bufferId ->
                // A permanently refused target is not waiting for a connection; leave its badge alone.
                if (_syncStatuses.value[bufferId] == HistorySyncStatus.Unavailable) return@forEach
                waiting += bufferId
                beginSyncStatus(bufferId, HistorySyncStatus.AwaitingConnection)
            }
        }

        override fun clearAwaitingConnection(networkId: Long) {
            awaitingByNetwork.remove(networkId)?.forEach(::clearAwaitingBuffer)
        }

        /**
         * A real pass has taken over: every buffer it re-queued is already published as Queued, so only
         * the ids it did NOT re-queue (closed since the optimistic mark) still need clearing.
         */
        private fun adoptAwaiting(
            networkId: Long,
            queuedIds: Set<Long>,
        ) {
            val waiting = awaitingByNetwork.remove(networkId) ?: return
            waiting.forEach { bufferId ->
                if (bufferId !in queuedIds) clearAwaitingBuffer(bufferId)
            }
        }

        private fun clearAwaitingBuffer(bufferId: Long) {
            if (_syncStatuses.value[bufferId] != HistorySyncStatus.AwaitingConnection) return
            syncStatusGenerations.computeIfAbsent(bufferId) { AtomicLong() }.incrementAndGet()
            _syncStatuses.update { current ->
                if (current[bufferId] == HistorySyncStatus.AwaitingConnection) current - bufferId else current
            }
        }

        /**
         * Paint a network pass's final verdict on whatever its last attempt left behind. The catch-up
         * loop calls this when it gives up (attempts exhausted, or a failure it will not retry) so the
         * statuses a retryable failure deliberately kept alive do not linger forever.
         *
         * Value-guarded on [sourceIdentity]: between the caller's own superseded-client check and this
         * call the successor connection can already have registered its pass, and finishing a live
         * successor's session with the predecessor's give-up verdict would drop its queued buffers to
         * Idle mid-pass.
         */
        fun settleNetworkPass(
            networkId: Long,
            result: HistoryResyncState,
            sourceIdentity: Any,
        ): Unit =
            // Under [retireGuard] like every other slot handover: the give-up verdict releases this
            // network's progress header, and a successor may not register between the removal here and
            // that release, or the successor's own header would be the one this call deletes.
            synchronized(retireGuard) {
                val session = networkSessions[networkId] ?: return@synchronized
                if (!session.ownedBy(sourceIdentity)) return@synchronized
                if (!networkSessions.remove(networkId, session)) return@synchronized
                session.finish(result)
            }

        /**
         * The network was taken offline deliberately (user disconnect or service shutdown). Nothing is
         * going to reconnect it, so every status and progress entry it owns is retired: the live or
         * suspended-for-retry session is settled to Idle, the optimistic waiting entries are dropped,
         * and [sourceIdentity] is tombstoned so a pass still winding down in this coordinator's scope
         * publishes nothing late — in particular it may not repaint AwaitingConnection or re-register
         * waiting buffers after this call has cleared them.
         *
         * [sourceIdentity] is only the connection the caller knows about, and may be null when its actor
         * was already gone. Either way the tombstone also covers every session of this network that
         * already exists, so a pass for an earlier superseded client cannot repaint waiting badges after
         * this clear. See [NetworkRetirement].
         */
        fun retireNetwork(
            networkId: Long,
            sourceIdentity: Any?,
        ) = synchronized(retireGuard) {
            retiredNetworks[networkId] = NetworkRetirement(sourceIdentity, sessionTickets.get())
            networkSessions.remove(networkId)?.retire()
            clearAwaitingConnection(networkId)
            backfillSessions.remove(networkId)?.retire()
            // Also clears a stale predecessor entry when no current session survived to retire it.
            _passProgress.update { it - networkId }
            // The loader's wire gates are keyed by network and live for the process, so a retirement is
            // also the only moment anything can drop one. Without this the gate a deleted network built
            // is never reclaimed, and — worse — a later connection reusing the id inherits the retired
            // connection's semaphore, including a permit a request that outlived its socket still
            // holds. Removal is safe against that straggler: it kept its own gate reference when it
            // acquired, exactly as it does across the loader's existing width swap.
            loader.releaseNetwork(networkId)
        }

        /**
         * Open a network pass's session. A pass the network's tombstone does not cover proves the
         * network is live again and takes the session slot; a covered pass — the retired connection's
         * OWN late pass, or one that predates an unnamed retirement — stays silent and never takes the
         * slot away from its successor.
         */
        private fun beginNetworkSession(
            networkId: Long,
            sourceIdentity: Any,
            chromeEligible: Boolean,
            backfill: Boolean = false,
        ): SyncStatusSession =
            synchronized(retireGuard) {
                // Drawn inside the guard, so a session created before a retirement always carries a
                // smaller ticket than that retirement recorded, and one created after always a larger.
                val session =
                    SyncStatusSession(
                        networkId,
                        sourceIdentity,
                        sessionTickets.incrementAndGet(),
                        chromeEligible,
                        backfill,
                    )
                if (!session.networkRetired()) networkSessions[networkId] = session
                session
            }

        /**
         * One network's tombstone, covering every pass that may not publish after it.
         *
         * By ticket: every session that already existed when the network was taken offline. Those are
         * exactly the passes that could still repaint the statuses this retirement just cleared —
         * including one for an earlier superseded client, which is invisible to the caller and which
         * the retired connection's identity alone would miss. A session created later draws a newer
         * ticket and is never covered, so the next connection's pass publishes normally.
         *
         * By identity: passes the retired connection starts afterwards anyway. A catch-up loop the
         * caller could not stop can still open one, and it must be silent even though its ticket is
         * newer. Held weakly, because a tombstone whose connection is unreachable has no late pass left
         * to silence and a dead client must not be pinned by this map.
         */
        private class NetworkRetirement(
            sourceIdentity: Any?,
            private val atTicket: Long,
        ) {
            private val identity: WeakReference<Any>? = sourceIdentity?.let(::WeakReference)

            fun covers(
                sourceIdentity: Any?,
                ticket: Long,
            ): Boolean = ticket <= atTicket || (sourceIdentity != null && identity?.get() === sourceIdentity)
        }

        /**
         * One pass's per-buffer status publication: registration, the generation guard that keeps a
         * cancelled or superseded pass from publishing late, and settlement. Work without a session
         * (the paced background backfill) publishes nothing at all.
         *
         * A labeled-response pass drives a bounded number of buffers concurrently, so within-pass
         * bookkeeping is monitor-guarded; the cross-pass race — a manual retry superseding a reconnect
         * pass, or the reverse — is still arbitrated by the per-buffer generation counter, not by this
         * bookkeeping.
         *
         * A session can also run LATENT, which is how "sync chrome only for a real outage" is
         * expressed. A latent session does everything a visible one does — it registers buffers, bumps
         * their generations (so it still wins clobber races against a superseded pass), counts
         * progress, and settles — but it publishes no Queued, no Syncing and no aggregate progress. It
         * holds the current status of each buffer instead of a history of them, and [activate] releases
         * that snapshot in one step. Terminals are never withheld: a failure the user could act on, or
         * a permanently refused target, publishes whether the session was ever visible or not.
         */
        private inner class SyncStatusSession(
            // Null for a single-buffer reconcile: those publish no aggregate progress and have no
            // network-wide waiting state to fall back to.
            private val networkId: Long? = null,
            // The connection this pass belongs to, so a caller that only knows the client can prove a
            // session is still its own before settling it, and so retirement can silence exactly the
            // passes that belong to a connection the user took offline.
            private val sourceIdentity: Any? = null,
            // Creation order among network sessions, compared against an unnamed retirement's watermark.
            // A reconcile session has no ticket to lose: it is never covered by a network retirement.
            private val ticket: Long = Long.MAX_VALUE,
            // False keeps this pass latent for its whole life: it is a re-verification of a connection
            // that already converged, and there is nothing about it worth showing the user. True only
            // means chrome is ALLOWED — [activate] still has to prove there is work to show.
            private val chromeEligible: Boolean = true,
            // A user-requested window fetch; published on its progress so the chat list can say so.
            private val backfill: Boolean = false,
        ) {
            private val monitor = Any()

            // Backfills never take the per-network session slot (see [manualPass]), so their
            // aggregate progress publishes under their own key instead of the network's: the
            // negated session ticket, a value no network id (always a positive Room PK) and no
            // other live pass can mint. The chat list sums across all entries.
            private val progressKey: Long? get() = if (backfill) -ticket else null

            /** The key this session's aggregate progress publishes under; the network slot when not a backfill. */
            private fun progressKeyOrNetwork(id: Long): Long = progressKey ?: id

            // Buffers whose requests are on the wire right now; each wears the whole-pass verdict.
            private val inFlight = LinkedHashSet<Long>()

            // Registration order; an entry lives here until that buffer settles.
            private val generations = LinkedHashMap<Long, Long>()

            // Registered buffers the server permanently refuses. They own a generation (so this pass
            // can still settle them) but their transient Queued/Syncing publications are suppressed:
            // re-refusing an Unavailable target every pass must produce zero visible churn.
            private val silenced = LinkedHashSet<Long>()

            // Transient statuses withheld while latent, as a SNAPSHOT: one entry per buffer, overwritten
            // rather than appended, so activating replays where each buffer is now instead of replaying
            // a queue of states it has already left.
            private val withheld = LinkedHashMap<Long, HistorySyncStatus>()

            // A single-buffer reconcile (the retry pill, a JOIN seed, a notification tap) has nothing to
            // discover and no wave to plan: it IS the work, so it is visible from its first frame. Only
            // a network pass has to earn its chrome by finding something.
            private var activated = networkId == null
            private var total = 0
            private var settled = 0

            // Set once this pass's network is taken offline deliberately. The pass keeps running in the
            // coordinator's own scope, but from here on it publishes nothing at all.
            private var retired = false

            /** True when [identity] is the connection that started this pass. */
            fun ownedBy(identity: Any): Boolean = sourceIdentity === identity

            private fun latentLocked(): Boolean = !chromeEligible || !activated

            /**
             * Reveal this pass: discovery has proven there is something to fetch.
             *
             * Idempotent, and deliberately powerless on a session that is not [chromeEligible] — that
             * one is re-verifying a connection which already converged, so its work is invisible by
             * construction no matter what it finds.
             */
            fun activate() {
                synchronized(monitor) {
                    if (!chromeEligible || activated || retiredLocked()) return
                    activated = true
                    // Replayed under the monitor, exactly as [syncing] publishes under it: releasing the
                    // lock first would let a terminal land between the snapshot and its replay, and the
                    // replayed Queued — same generation, so the guard accepts it — would resurrect a
                    // buffer that had already settled.
                    withheld.forEach { (bufferId, status) ->
                        generations[bufferId]?.let { publishSyncStatus(bufferId, it, status) }
                    }
                    withheld.clear()
                }
                // Outside the monitor: [publishProgress] takes [retireGuard], which is always the outer
                // lock of the two.
                publishProgress()
            }

            /**
             * Publish a transient status now, or hold it until [activate]. Callers must hold [monitor].
             *
             * The generation is taken and published exactly as a visible session would; only the
             * emission waits. That is what keeps a latent pass's clobber protection intact — a
             * superseded pass publishing late still loses the generation comparison.
             */
            private fun publishTransientLocked(
                bufferId: Long,
                generation: Long,
                status: HistorySyncStatus,
            ) {
                if (latentLocked()) {
                    withheld[bufferId] = status
                } else {
                    publishSyncStatus(bufferId, generation, status)
                }
            }

            /**
             * True when this pass's network is already retired under a tombstone that covers it. Latches
             * the pass silent so it never registers or publishes anything. Callers must hold
             * [retireGuard] so the answer cannot change under them.
             */
            fun networkRetired(): Boolean = synchronized(monitor) { retiredLocked() }

            /** Register a buffer in this pass. Re-registration within one pass keeps the first turn. */
            fun queue(bufferId: Long) {
                synchronized(monitor) {
                    if (!registerLocked(bufferId)) return
                }
                publishProgress()
            }

            /** Bulk registration of the pass's known open buffers as one progress emission. */
            fun queueAll(bufferIds: Collection<Long>) {
                if (bufferIds.isEmpty()) return
                synchronized(monitor) { bufferIds.forEach { registerLocked(it) } }
                publishProgress()
            }

            private fun registerLocked(bufferId: Long): Boolean {
                if (retiredLocked()) return false
                if (generations.containsKey(bufferId)) return false
                val refused = _syncStatuses.value[bufferId] == HistorySyncStatus.Unavailable
                // The generation is claimed here in every case — visible, latent, or silenced — because
                // it is what arbitrates against a superseded pass's late publications, and a pass that
                // is merely quiet still owns these buffers.
                val generation = bumpSyncStatusGeneration(bufferId)
                generations[bufferId] = generation
                if (refused) {
                    silenced += bufferId
                } else {
                    publishTransientLocked(bufferId, generation, HistorySyncStatus.Queued)
                }
                total++
                return true
            }

            /** This buffer's request is about to go on the wire. */
            fun syncing(bufferId: Long) {
                synchronized(monitor) {
                    val generation = generations[bufferId] ?: return
                    inFlight += bufferId
                    if (bufferId in silenced) return
                    publishTransientLocked(bufferId, generation, HistorySyncStatus.Syncing)
                }
            }

            /** Terminal for one buffer: [HistorySyncStatus.Idle] removes it, anything else persists. */
            fun settle(
                bufferId: Long,
                status: HistorySyncStatus,
            ) {
                val changed = synchronized(monitor) { settleLocked(bufferId, status) }
                if (changed) publishProgress()
            }

            private fun settleLocked(
                bufferId: Long,
                status: HistorySyncStatus,
            ): Boolean {
                val generation = generations.remove(bufferId) ?: return false
                inFlight -= bufferId
                silenced -= bufferId
                // Whatever this buffer was waiting to show, it is past it now.
                withheld -= bufferId
                settled++
                // Terminals are never withheld. An Idle terminal publishes nothing visible anyway (it
                // removes an entry a latent pass never wrote), and a Partial/Failed/Unavailable one is
                // the actionable outcome the retry affordance is built on — withholding that would make
                // a quiet pass a silent one.
                finishSyncStatus(bufferId, generation, status)
                return true
            }

            /**
             * Pass end with no retry coming. Only buffers that actually had a request on the wire wear
             * the pass verdict (a cancelled sibling's request was genuinely in flight); every
             * still-queued buffer is simply dropped, because painting a whole-pass failure on untouched
             * buffers would be a lie.
             */
            fun finish(result: HistoryResyncState): Unit =
                synchronized(retireGuard) {
                    if (retireIfNetworkRetired()) return@synchronized
                    val verdict = result.toSyncStatus()
                    synchronized(monitor) {
                        inFlight.toList().forEach { settleLocked(it, verdict) }
                        generations.keys.toList().forEach { settleLocked(it, HistorySyncStatus.Idle) }
                    }
                    releaseProgress()
                }

            /**
             * The pass failed in a way the catch-up loop will retry. Publish nothing: the statuses this
             * attempt painted stay up through the backoff, and the next attempt's re-registration
             * republishes Queued over Queued, which is invisible. The frozen progress entry is
             * deliberately kept too — a truthful stale count beats a 0/N flash every backoff.
             */
            fun suspendForRetry(): Unit =
                synchronized(retireGuard) {
                    // Unless the retry can never come: a retired network has no next attempt to repaint
                    // these statuses, so freezing them would strand them for the process lifetime.
                    retireIfNetworkRetired()
                }

            /**
             * The network was retired under this pass. Drop everything it painted, publish no verdict,
             * no waiting state and no progress, and make every later registration and terminal a no-op.
             * Idempotent, and safe while the pass is still winding down.
             */
            fun retire(): Unit =
                synchronized(retireGuard) {
                    synchronized(monitor) {
                        retired = true
                        generations.keys.toList().forEach { settleLocked(it, HistorySyncStatus.Idle) }
                    }
                    releaseProgress()
                }

            /** Retire lazily for a tombstone published after this session was created. */
            private fun retireIfNetworkRetired(): Boolean {
                if (!synchronized(monitor) { retiredLocked() }) return false
                retire()
                return true
            }

            private fun retiredLocked(): Boolean {
                if (!retired && networkId != null &&
                    retiredNetworks[networkId]?.covers(sourceIdentity, ticket) == true
                ) {
                    retired = true
                }
                return retired
            }

            /**
             * The pass died with its connection. Survivors go back to the optimistic waiting state and
             * are registered for adoption by whichever pass the next connection starts.
             */
            fun abandonToAwaitingConnection(): Unit =
                synchronized(retireGuard) {
                    // A retired network is not waiting for anything: republishing here would repaint every
                    // unsettled buffer after the disconnect already cleared them. The guard is what makes
                    // that check reliable — retireNetwork holds it across the clear.
                    if (retireIfNetworkRetired()) return@synchronized
                    // Decided ONCE, and used for both the badge and the registration: painting a buffer
                    // AwaitingConnection without also registering it strands that badge for the process
                    // lifetime, because adoptAwaiting/clearAwaitingConnection/retireNetwork all clear by
                    // walking awaitingByNetwork and no later pass re-registers a buffer that closed in the
                    // meantime. When a successor already owns the slot its adoptAwaiting has run, so this
                    // pass's survivors are not waiting for a connection at all — they settle to Idle.
                    val owns = ownsNetworkSlot()
                    val survivors =
                        synchronized(monitor) {
                            val entries = generations.entries.toList()
                            generations.clear()
                            inFlight.clear()
                            // Whatever this pass was holding back is moot: the connection it belonged to is
                            // gone, and the waiting badge below is the only honest thing left to say.
                            withheld.clear()
                            val waiting = mutableListOf<Long>()
                            entries.forEach { (bufferId, generation) ->
                                settled++
                                if (silenced.remove(bufferId)) {
                                    // A permanently refused target is not waiting for anything; leave its badge.
                                    return@forEach
                                }
                                if (!owns) {
                                    finishSyncStatus(bufferId, generation, HistorySyncStatus.Idle)
                                    return@forEach
                                }
                                finishSyncStatus(bufferId, generation, HistorySyncStatus.AwaitingConnection)
                                waiting += bufferId
                            }
                            waiting
                        }
                    releaseProgress()
                    if (networkId != null && survivors.isNotEmpty()) {
                        awaitingByNetwork
                            .computeIfAbsent(networkId) { ConcurrentHashMap.newKeySet() }
                            .addAll(survivors)
                    }
                }

            /**
             * True when no OTHER session owns this network's slot.
             *
             * Per-buffer publications are arbitrated by the per-buffer generation counter, but the
             * aggregate progress entry and the network-wide waiting set are keyed by network alone, so
             * nothing stopped a superseded pass's terminal from deleting the live successor's header
             * mid-pass. A terminal legitimately runs after its own session was removed from the map
             * (see [endSession] and [settleNetworkPass]), so an empty slot counts as owned.
             *
             * Callers must hold [retireGuard]. Read outside it this is a check-then-act against a slot
             * another thread is in the middle of handing over, which is not an ownership decision.
             */
            private fun ownsNetworkSlot(): Boolean {
                val id = networkId ?: return false
                val owner = networkSessions[id]
                return owner == null || owner === this
            }

            /**
             * Publish this pass's aggregate counters, under the guard that arbitrates the slot.
             *
             * The guard is what makes the ownership decision and the mutation it authorizes one step.
             * Deciding outside it lost updates in both directions: the slot is momentarily empty
             * between a predecessor's [endSession] and its successor's [beginNetworkSession], so a
             * predecessor could read "still mine", watch the successor register and publish its own
             * (n, 0) header into that gap, and then overwrite that header with a stale count — or, from
             * [releaseProgress], delete it outright, leaving a live pass with no header at all for as
             * long as it had nothing further to emit. A backfill session skips the ownership check
             * (it owns its key outright) but keeps the guard for the same atomicity.
             */
            private fun publishProgress() {
                synchronized(retireGuard) {
                    val id = networkId ?: return
                    // A backfill owns its key outright — it never took the per-network slot, so no
                    // reconnect pass can evict its header, and it can never delete a live pass's.
                    if (progressKey == null && !ownsNetworkSlot()) return
                    val snapshot =
                        synchronized(monitor) {
                            // A retired pass publishes nothing at all. Its terminal already cleared this
                            // header under the same guard, and the empty slot that terminal left behind
                            // reads as "owned": without this check a publication that was already past its
                            // own ownership decision would re-add the entry, and a retired network has no
                            // later pass to clear it again.
                            if (retiredLocked()) return
                            // The header is the chat list's syncing banner. A latent pass has nothing to
                            // announce; [activate] publishes the counters as they stand at that moment.
                            if (latentLocked()) return
                            SyncPassProgress(total, settled, backfill)
                        }
                    _passProgress.update { it + (progressKeyOrNetwork(id) to snapshot) }
                }
            }

            /** Drop this pass's header, under the same ownership guard as [publishProgress]. */
            private fun releaseProgress() {
                synchronized(retireGuard) {
                    val id = networkId ?: return
                    if (progressKey == null && !ownsNetworkSlot()) return
                    _passProgress.update { it - progressKeyOrNetwork(id) }
                }
            }
        }

        /**
         * Reconcile a visible chat. This is its own per-buffer flight: it coalesces with another
         * reconcile of the same buffer on the same connection, but NOT with the network-wide reconnect
         * pass, whose flight key carries a null buffer id. Overlap is safe — two concurrent same-buffer
         * LATEST fetches deduplicate in [EventProcessor] — and the per-buffer generation counter
         * arbitrates which of the two owns the visible status.
         */
        override suspend fun reconcileBuffer(
            buffer: BufferEntity,
            client: IrcClient,
            isCurrent: () -> Boolean,
        ): HistoryResyncState =
            reconcileBuffer(
                networkId = buffer.networkId,
                bufferId = buffer.id,
                target = buffer.ircTarget,
                source = ClientHistorySource(client),
                isCurrent = isCurrent,
            )

        override suspend fun reconcilePendingMessage(
            buffer: BufferEntity,
            client: IrcClient,
            isCurrent: () -> Boolean,
        ): HistoryResyncState =
            reconcilePendingMessage(
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
            val ready =
                when (val availability = source.availability()) {
                    HistoryAvailability.Unsupported -> return HistoryResyncState.Unsupported
                    HistoryAvailability.NegotiatingOrOffline -> return historyUnavailable()
                    is HistoryAvailability.Ready -> availability
                }
            if (!isCurrent()) return staleConnection()
            val referenceTypes = ready.referenceTypes
            val msgidAllowed = HistoryReferenceType.MSGID in referenceTypes
            return try {
                val request =
                    ChatHistoryRequest(
                        subcommand = ChatHistoryRequest.Subcommand.LATEST,
                        target = target,
                        limit = ready.pageLimit.coerceAtMost(PAGE_LIMIT).coerceAtLeast(1),
                    )
                // The loader admits this LATEST on the same per-network wire gate as every other
                // history fetch. Because a permit is held per wire request (never for a whole discovery
                // pass), an urgent pending promotion interleaves between a network resync's pages instead
                // of queuing behind the entire pass — the guarantee the old bespoke bypass provided.
                val latest =
                    loader.fetchMessages(
                        networkId,
                        source,
                        request,
                        referenceTypes,
                        msgidAllowed,
                        timeoutMs = PENDING_MESSAGE_TIMEOUT_MS,
                        allowConcurrent = ready.supportsConcurrentRequests,
                    )
                if (!isCurrent()) return staleConnection()
                val inserted = ingest(networkId, bufferId, request, latest)
                if (
                    !latest.isTerminalPage() &&
                    !latest.hasUsableDirectionalBoundary(
                        ChatHistoryRequest.Subcommand.LATEST,
                        referenceTypes,
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

        suspend fun resyncNetwork(
            networkId: Long,
            openBuffers: List<OpenBufferTarget>,
            client: IrcClient,
            isCurrent: () -> Boolean,
            // null means "everything": the first pass enumerates from epoch and leaves no backfill.
            initialLookbackMs: Long? = INITIAL_SYNC_LOOKBACK_MS,
            // False for a re-verification of a connection that already converged: such a pass may find
            // work, but nothing it finds is worth interrupting the chat list to announce.
            chromeEligible: Boolean = true,
            // See the [resyncNetwork] overload below: fired when the visible half of the pass has
            // converged, so the caller's entry gate does not span the paced sweep behind it.
            onCatchUpConverged: (suspend () -> Unit)? = null,
            // A manual re-sync: discover from here instead of the stored watermark.
            discoveryLowerMs: Long? = null,
        ): HistoryResyncState {
            if (!client.targetClassificationReady.value) {
                withTimeoutOrNull(TARGET_CLASSIFICATION_WAIT_TIMEOUT_MS) {
                    client.targetClassificationReady.first { it }
                }
            }
            if (!isCurrent()) return staleConnection()
            return resyncNetwork(
                networkId,
                openBuffers,
                ClientHistorySource(client),
                isCurrent,
                initialLookbackMs,
                chromeEligible,
                onCatchUpConverged,
                discoveryLowerMs,
            )
        }

        suspend fun backfillTargets(
            networkId: Long,
            client: IrcClient,
            isCurrent: () -> Boolean,
        ) {
            if (!client.targetClassificationReady.value) {
                withTimeoutOrNull(TARGET_CLASSIFICATION_WAIT_TIMEOUT_MS) {
                    client.targetClassificationReady.first { it }
                } ?: return
            }
            if (!isCurrent()) return
            backfillTargets(networkId, ClientHistorySource(client), isCurrent)
        }

        /**
         * Paced background enumeration of targets older than the initial-sync window. Resumes from the
         * durable per-network cursor, seeds every discovered target with the same single newest page
         * the reconnect pass uses, and never touches the reconnect watermark or publishes per-buffer
         * status. A transport failure or a superseded connection simply leaves the cursor where it
         * last advanced; the next Ready session resumes from there.
         */
        internal suspend fun backfillTargets(
            networkId: Long,
            source: HistorySource,
            isCurrent: () -> Boolean,
        ) {
            val cursorDao = db.historyBackfillCursorDao()
            val cursor = cursorDao.byNetwork(networkId) ?: return
            if (cursor.complete) return
            if (cursor.upperBound <= Instant.EPOCH.toEpochMilli()) {
                cursorDao.markComplete(networkId)
                return
            }
            val ready = source.availability() as? HistoryAvailability.Ready ?: return
            if (!source.canClassifyTargets()) return
            diagnostics.record("history", "backfill_started") {
                mapOf("network_id" to networkId, "upper_bound" to cursor.upperBound)
            }
            val discovery =
                try {
                    discoverTargets(
                        networkId = networkId,
                        source = source,
                        upper = cursor.upperBound,
                        lower = Instant.EPOCH.toEpochMilli(),
                        onPageEnd = { page, nextUpper ->
                            // Seed before persisting the boundary: a killed process may re-enumerate a
                            // page (target dedup absorbs that) but can never skip one unseeded.
                            seedBackfillPage(networkId, page, source, isCurrent)
                            cursorDao.advance(networkId, nextUpper)
                        },
                        betweenPages = {
                            if (!isCurrent()) throw StaleConnectionException()
                            delay(BACKFILL_TARGETS_PACE_MS)
                        },
                        allowConcurrent = ready.supportsConcurrentRequests,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: StaleConnectionException) {
                    return
                } catch (error: Exception) {
                    diagnostics.record("history", "backfill_failed") {
                        mapOf(
                            "network_id" to networkId,
                            "error_fp" to diagnostics.fingerprint(error.message),
                        )
                    }
                    return
                }
            // The terminal page gets no onPageEnd; this final sweep seeds it, and targets already
            // seeded earlier skip cheaply on their stored room cursor.
            try {
                seedBackfillPage(networkId, discovery.targets, source, isCurrent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return
            }
            if (discovery.status == WorkStatus.Complete) cursorDao.markComplete(networkId)
            diagnostics.record("history", "backfill_finished") {
                mapOf(
                    "network_id" to networkId,
                    "targets" to discovery.targets.size,
                    "complete" to (discovery.status == WorkStatus.Complete),
                )
            }
        }

        private suspend fun seedBackfillPage(
            networkId: Long,
            page: List<ChatHistoryTarget>,
            source: HistorySource,
            isCurrent: () -> Boolean,
        ) {
            if (page.isEmpty()) return
            syncTargets(
                networkId = networkId,
                targets = mergeSyncTargets(emptyList(), page, source),
                source = source,
                isCurrent = isCurrent,
                hasDiscoveryWatermark = true,
                paceBetweenTargetsMs = BACKFILL_SEED_PACE_MS,
            )
        }

        /**
         * [onCatchUpConverged] is the pass telling its caller that the half a reader can see is done:
         * wave one has settled, the watermark has advanced, and only the paced sweep is left. The
         * Ready-session wiring hands its entry gate back here rather than at pass end, because that gate
         * is network-scoped: on an account with dozens of changed rooms the sweep runs for tens of
         * seconds, and a chat opened in that window — including one that settled in wave one — would sit
         * on the entry timeout waiting for background work it has no stake in. Never fired for a pass
         * the catch-up loop is going to retry, which is exactly when the gate should still be held.
         */
        internal suspend fun resyncNetwork(
            networkId: Long,
            openBuffers: List<OpenBufferTarget>,
            source: HistorySource,
            isCurrent: () -> Boolean = { true },
            initialLookbackMs: Long? = INITIAL_SYNC_LOOKBACK_MS,
            chromeEligible: Boolean = true,
            onCatchUpConverged: (suspend () -> Unit)? = null,
            discoveryLowerMs: Long? = null,
        ): HistoryResyncState =
            coalesced(
                RequestSpec(
                    RequestKey(networkId, null),
                    sourceIdentity = source.flightIdentity(),
                ),
            ) {
                diagnostics.record("history", "network_sync_started") {
                    mapOf("network_id" to networkId, "open_buffers" to openBuffers.size)
                }
                val identity = source.flightIdentity()
                val ready =
                    when (val availability = source.availability()) {
                        HistoryAvailability.Unsupported -> {
                            // This server will never serve history, so no later pass can ever adopt the
                            // optimistic waiting entries. Retire them instead of stranding them.
                            clearAwaitingConnection(networkId)
                            // Availability can degrade between two attempts of the same catch-up loop (a
                            // CAP DEL of chathistory). The previous attempt's session is suspended for a retry
                            // that just became impossible: settle it now or its frozen statuses and progress
                            // entry survive for the process lifetime.
                            settleNetworkPass(networkId, HistoryResyncState.Unsupported, identity)
                            return@coalesced HistoryResyncState.Unsupported
                        }

                        // Still negotiating (or offline): the buffers genuinely are waiting for a connection.
                        HistoryAvailability.NegotiatingOrOffline -> {
                            return@coalesced historyUnavailable()
                        }

                        is HistoryAvailability.Ready -> {
                            availability
                        }
                    }
                val session = beginNetworkSession(networkId, identity, chromeEligible)
                session.queueAll(openBuffers.map { it.id })
                // Queued is already published over the optimistic waiting state; only buffers that closed
                // between the foreground mark and this pass still need clearing.
                adoptAwaiting(networkId, openBuffers.mapTo(mutableSetOf()) { it.id })
                // A room row's newest message is not a reliable reconnect cursor: a newer push-delivered
                // message in one buffer can otherwise hide an older missed message in another. The wall
                // clock bounds discovery but is never persisted; only completed server response metadata
                // can advance the dedicated whole-network cursor.
                val previousSync = syncPrefs.lastSuccessfulSync(networkId)
                // First sync: bound discovery to the user's chosen window instead of epoch. A large bouncer
                // account advertises years of targets, and eagerly enumerating and seeding all of them froze
                // onboarding. Everything older trickles in behind the durable backfill cursor instead. A
                // null lookback is the explicit "everything" choice: enumerate from epoch in this one pass.
                val firstSyncLower =
                    initialLookbackMs
                        ?.let { Instant.now().toEpochMilli() - it }
                        ?: Instant.EPOCH.toEpochMilli()
                // A manual re-sync names its own window; the watermark still advances afterwards.
                val lower =
                    (discoveryLowerMs ?: previousSync ?: firstSyncLower)
                        .minus(TARGETS_FUZZ_MS)
                        .coerceAtLeast(Instant.EPOCH.toEpochMilli())
                val upper = Instant.now().toEpochMilli() + TARGETS_FUZZ_MS
                if (previousSync == null && initialLookbackMs != null) {
                    // +1 because TARGETS BETWEEN excludes both selectors: the backfill interval must
                    // include a target advertised exactly at this pass's lower boundary. An unbounded first
                    // pass reaches epoch itself, so there is nothing older left to seed a cursor for.
                    db.historyBackfillCursorDao().seed(
                        HistoryBackfillCursorEntity(networkId = networkId, upperBound = lower + 1),
                    )
                }
                val result =
                    try {
                        val discovery =
                            if (source.canClassifyTargets()) {
                                discoverTargets(
                                    networkId,
                                    source,
                                    upper,
                                    lower,
                                    // Badge and re-sort from discovery itself, page by page. The whole reason the
                                    // list used to feel slow on reconnect is that this information — which rooms
                                    // moved — arrives in the FIRST response, while the rows behind it arrive one
                                    // fan-out slot at a time.
                                    onPageEnd = { page, _ ->
                                        // Chrome can appear mid-discovery: the first page that names a room whose
                                        // tail moved is proof this pass has visible work, and waiting until the
                                        // whole window is enumerated to say so is what made the header arrive after
                                        // the rows it was supposed to explain.
                                        if (badgeAdvertisedActivity(networkId, page, source).isNotEmpty()) {
                                            session.activate()
                                        }
                                    },
                                    allowConcurrent = ready.supportsConcurrentRequests,
                                ).also { terminal ->
                                    // The page that ends discovery gets no onPageEnd (it returns straight out), so
                                    // badge the accumulated set here. Re-badging what earlier pages already wrote
                                    // is a no-op: the column only ever moves forward.
                                    badgeAdvertisedActivity(networkId, terminal.targets, source)
                                }
                            } else {
                                TargetDiscovery(
                                    targets = emptyList(),
                                    status =
                                        WorkStatus.Incomplete(
                                            "CHATHISTORY TARGETS deferred until CHANTYPES negotiation settles",
                                            awaitsTargetClassification = true,
                                        ),
                                    highWater = null,
                                )
                            }
                        val mergedTargets = mergeSyncTargets(openBuffers, discovery.targets, source)
                        val candidates = classifyTargets(mergedTargets, source)
                        val plan =
                            planCatchUpWaves(
                                candidates = candidates,
                                foregroundBufferId = foregroundBuffers.foregroundBufferId.value,
                            )
                        // A room discovery proved is already current gets no request and no spinner. Settling it
                        // up front is what stops a quiet account's reconnect from painting the whole list as
                        // "syncing" and then resolving every row to nothing.
                        plan.settledUnchanged.forEach { session.settle(it, HistorySyncStatus.Idle) }
                        // Same evidence, applied to the cue: discovery named these rooms and they have already
                        // reached what it named, so an advertised instant still standing above their newest
                        // visible row belongs to an event they will never show. Nothing else revisits them —
                        // that is the point of the unchanged partition — so this is where it is retired.
                        retireSettledAdvertisements(networkId, candidates)
                        // The badging hook above only sees rooms this device already has, so a discovered DM
                        // with no local room could not reveal the pass. This is the same question asked over
                        // the full plan: is there anything at all to fetch?
                        if (plan.waveOne.isNotEmpty()) session.activate()
                        val targetPass =
                            syncTargets(
                                networkId = networkId,
                                targets = plan.waveOne,
                                source = source,
                                isCurrent = isCurrent,
                                hasDiscoveryWatermark = previousSync != null,
                                session = session,
                            )
                        val status = discovery.status.merge(targetPass.status)
                        val highWater =
                            maxHighWater(
                                previousSync,
                                discovery.highWater,
                                targetPass.highWater,
                            )
                        // The watermark bounds the next pass's TARGETS window, so only DISCOVERY decides it:
                        // per-target message gaps are owned by room cursors and durable gaps, and on an active
                        // account some target is always mid-catch-up — gating on targetPass starved the
                        // watermark forever, re-running full-window discovery on every reconnect. The
                        // unproven-tie incompleteness still enumerated the whole window (only the proof is
                        // missing) and TARGETS_FUZZ_MS absorbs same-timestamp stragglers, so it advances too;
                        // hard discovery failures (unusable boundary, saturated tie) still preserve.
                        val advanceWatermark =
                            discovery.status == WorkStatus.Complete ||
                                (discovery.status as? WorkStatus.Incomplete)?.unprovenTieOnly == true
                        if (advanceWatermark && isCurrent() && highWater != null) {
                            syncPrefs.setLastSuccessfulSync(networkId, highWater)
                            pruner.schedule(networkId)
                        }
                        // Strictly after the watermark, and deliberately: wave two is a paced sweep that can run
                        // for as long as the account has rooms, and the next reconnect's discovery window must
                        // not be held open behind it. The caller's entry gate is handed back on the same
                        // boundary and for the same reason.
                        val waveOneState =
                            status.toState(
                                targetPass.inserted,
                                retryRecommended = targetPass.retryRecommended,
                            )
                        val waveOneConverged = !waveOneState.isRetryableCatchUpFailure()
                        if (waveOneConverged) onCatchUpConverged?.invoke()
                        // The sweep rides that same boundary: a retryable wave one is about to re-run this whole
                        // pass, and pacing the overflow in front of that retry delays the one timeline the reader
                        // is actually looking at by the length of the sweep. The overflow keeps its chat-list cue
                        // either way — discovery did not re-advertise it, so nothing retires it — and is seeded by
                        // the attempt that converges, by opening the chat, or by the paced backfill.
                        val overflow =
                            if (waveOneConverged) {
                                sweepWaveTwo(networkId, plan.waveTwo, source, isCurrent, previousSync != null)
                            } else {
                                0
                            }
                        // Wave two contributes what it inserted and nothing else. Its status must not reach
                        // `retryRecommended`: a silent background sweep that reports "retry the whole pass"
                        // would re-run full discovery for work the user never saw.
                        status.toState(
                            targetPass.inserted + overflow,
                            retryRecommended = targetPass.retryRecommended,
                        )
                    } catch (_: TimeoutCancellationException) {
                        HistoryResyncState.Failed("History refresh timed out")
                    } catch (cancelled: CancellationException) {
                        // The pass died with its connection (or with the Ready session that owns it): every
                        // survivor goes back to waiting instead of silently disappearing.
                        endSession(networkId, session) { it.abandonToAwaitingConnection() }
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
                when {
                    !isCurrent() -> endSession(networkId, session) { it.abandonToAwaitingConnection() }

                    // The catch-up loop will run this pass again; leave the painted statuses alone so the
                    // user does not watch every badge blink off and on across the backoff.
                    result.isRetryableCatchUpFailure() -> session.suspendForRetry()

                    else -> endSession(networkId, session) { it.finish(result) }
                }
                result
            }

        /**
         * Retire [session] from the per-network map (a newer pass may already have replaced it, which
         * the value-matched removal tolerates) and run its terminal. The terminal itself is always
         * safe: a superseded session's per-buffer generations no longer match, so it publishes nothing.
         *
         * Removal and terminal are one step under [retireGuard], which the terminal re-enters. Split,
         * they left the slot briefly empty in front of a terminal that reads it: a successor that
         * registered in that gap published a header this terminal then decided it still owned.
         */
        private fun endSession(
            networkId: Long,
            session: SyncStatusSession,
            terminal: (SyncStatusSession) -> Unit,
        ) {
            synchronized(retireGuard) {
                networkSessions.remove(networkId, session)
                terminal(session)
            }
        }

        /**
         * Mirrors the catch-up loop's retry decision (see `shouldRetryIncompleteCatchUp`): only a
         * failure that loop will genuinely reattempt may keep this pass's statuses painted.
         */
        private fun HistoryResyncState.isRetryableCatchUpFailure(): Boolean =
            when (this) {
                is HistoryResyncState.Incomplete -> awaitsTargetClassification || retryRecommended
                is HistoryResyncState.Capped -> false
                is HistoryResyncState.Failed -> true
                else -> false
            }

        /**
         * Enumerate the complete TARGETS interval before the network cursor is advanced. TARGETS has
         * BETWEEN ordering semantics. Each next upper bound overlaps the oldest returned millisecond;
         * target identity deduplication absorbs that replay while preserving same-timestamp ties. A
         * saturated tie that cannot move beyond the overlap is explicitly incomplete.
         */
        private suspend fun discoverTargets(
            networkId: Long,
            source: HistorySource,
            upper: Long,
            lower: Long,
            // Backfill hooks: [onPageEnd] runs after a page's boundary advances (seed-then-persist so a
            // killed process never skips enumerated targets), [betweenPages] paces the next request.
            onPageEnd: (suspend (page: List<ChatHistoryTarget>, nextUpper: Long) -> Unit)? = null,
            betweenPages: (suspend () -> Unit)? = null,
            allowConcurrent: Boolean = false,
        ): TargetDiscovery {
            val limit = source.pageLimit().coerceAtLeast(1)
            val targets = LinkedHashMap<String, ChatHistoryTarget>()
            var pageUpper = upper
            var highWater: Long? = null
            var previousTie: Pair<Long, Set<String>>? = null
            var requestsInChunk = 0
            var status: WorkStatus = WorkStatus.Complete
            val chunkLimit = targetsRequestLimit.coerceAtLeast(1)
            while (true) {
                val response =
                    loader.fetchTargets(
                        networkId,
                        source,
                        ChatHistoryRequest(
                            subcommand = ChatHistoryRequest.Subcommand.TARGETS,
                            target = "*",
                            bound1 = ChatHistorySelectors.timestamp(pageUpper),
                            bound2 = ChatHistorySelectors.timestamp(lower),
                            limit = limit,
                        ),
                        requestTimeoutMs,
                        allowConcurrent = allowConcurrent,
                    )
                requestsInChunk++
                val page = response.targets
                page.forEach { target ->
                    val key = source.normalizeTarget(target.name)
                    val existing = targets[key]
                    if (existing == null || target.latestMessageTime > existing.latestMessageTime) {
                        targets[key] = target
                    }
                    highWater = maxHighWater(highWater, target.latestMessageTime)
                }
                if (response.endOfHistory || page.isEmpty()) {
                    return TargetDiscovery(targets.values.toList(), status, highWater)
                }

                val oldest = page.minOf { it.latestMessageTime }
                val tiedKeys =
                    page
                        .asSequence()
                        .filter { it.latestMessageTime == oldest }
                        .map { source.normalizeTarget(it.name) }
                        .toSet()
                if (previousTie == (oldest to tiedKeys)) {
                    if (page.size < limit && oldest > lower) {
                        // Soju 0.10.x omits draft/chathistory-end. Move beyond its repeated short tie
                        // page so older targets are still recovered, but never call the pass complete:
                        // IRCv3 permits a server to return fewer than the requested limit, so another
                        // same-time target could remain undisclosed.
                        status =
                            status.merge(
                                WorkStatus.Incomplete(
                                    "CHATHISTORY TARGETS could not prove a timestamp tie was exhausted",
                                    unprovenTieOnly = true,
                                ),
                            )
                        pageUpper = oldest
                        previousTie = null
                        onPageEnd?.invoke(page, pageUpper)
                        if (requestsInChunk >= chunkLimit) {
                            requestsInChunk = 0
                            yield()
                        }
                        betweenPages?.invoke()
                        continue
                    }
                    return TargetDiscovery(
                        targets.values.toList(),
                        WorkStatus.Incomplete(
                            "CHATHISTORY TARGETS saturated a timestamp tie and could not advance",
                        ),
                        highWater,
                    )
                }
                previousTie = oldest to tiedKeys

                // BETWEEN excludes both timestamp selectors. Move one millisecond past the oldest
                // timestamp so every tied target is replayed and deduplicated instead of skipped.
                val nextUpper = oldest.takeIf { it < Long.MAX_VALUE }?.plus(1)
                if (nextUpper == null || nextUpper >= pageUpper || nextUpper <= lower) {
                    val reason =
                        if (page.size >= limit && nextUpper != null && nextUpper >= pageUpper) {
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
                onPageEnd?.invoke(page, pageUpper)
                if (requestsInChunk >= chunkLimit) {
                    diagnostics.record("history", "targets_sync_continued") {
                        mapOf("targets" to targets.size, "high_water" to highWater)
                    }
                    requestsInChunk = 0
                    yield()
                }
                betweenPages?.invoke()
            }
        }

        /**
         * Publish one discovery page's advertised activity onto the rooms this device already knows.
         *
         * Silent by construction: it writes a timestamp, not unread state, so a room that moved
         * re-sorts and shows the ordinary count-less unread cue immediately — no chrome, no spinner,
         * and no claim about how many messages are waiting (discovery does not know).
         *
         * Rooms this device has never seen are skipped rather than created. Creating a room here would
         * make DISCOVERY a writer of room identity; the pass's own per-target work already creates a
         * query room when it fetches one, and there is nothing to re-sort for a room the list does not
         * show yet.
         *
         * Returns the rooms this page reported as genuinely moved, so a caller can act on "discovery
         * found something" without asking twice.
         */
        private suspend fun badgeAdvertisedActivity(
            networkId: Long,
            page: List<ChatHistoryTarget>,
            source: HistorySource,
        ): List<AdvertisedActivity> {
            if (page.isEmpty()) return emptyList()
            val changed =
                page.mapNotNull { target ->
                    val room =
                        db.bufferDao().byName(networkId, source.normalizeTarget(target.name))
                            ?: return@mapNotNull null
                    val cursor = db.historyCursorDao().byRoom(room.id)
                    if (reachedAdvertised(cursor?.newestServerTime, target.latestMessageTime)) {
                        return@mapNotNull null
                    }
                    AdvertisedActivity(room.id, target.latestMessageTime)
                }
            processor.recordAdvertisedActivity(networkId, changed)
            return changed
        }

        /**
         * Retire the advertised-activity cue for every room the plan settled without fetching.
         *
         * Only rooms discovery described in THIS pass, and only up to what it described: the clamp is
         * bounded by the advertisement it proves against, so a newer discovery's value is untouched.
         * The write is skipped in SQL whenever the room's newest visible row already agrees, which on a
         * quiet account is every one of them.
         */
        private suspend fun retireSettledAdvertisements(
            networkId: Long,
            candidates: List<CatchUpCandidate>,
        ) {
            candidates.forEach { candidate ->
                if (candidate.changed) return@forEach
                val roomId = candidate.target.knownBufferId ?: return@forEach
                val advertised = candidate.target.latestMessageTime ?: return@forEach
                processor.clampAdvertisedActivity(networkId, roomId, advertised)
            }
        }

        private fun mergeSyncTargets(
            openBuffers: List<OpenBufferTarget>,
            discovered: List<ChatHistoryTarget>,
            source: HistorySource,
        ): List<SyncTarget> {
            val targets = LinkedHashMap<String, SyncTarget>()
            openBuffers.forEach { open ->
                targets[source.normalizeTarget(open.name)] =
                    SyncTarget(open.id, open.name, null, open.pinned)
            }
            discovered.forEach { target ->
                val key = source.normalizeTarget(target.name)
                val existing = targets[key]
                targets[key] =
                    if (existing == null) {
                        SyncTarget(null, target.name, target.latestMessageTime)
                    } else {
                        existing.copy(
                            latestMessageTime =
                                existing.latestMessageTime
                                    ?.let { maxOf(it, target.latestMessageTime) }
                                    ?: target.latestMessageTime,
                        )
                    }
            }
            // Read at sort time, not at pass start: every catch-up retry attempt re-sorts, so switching
            // chats mid-backoff re-prioritizes the newly visible conversation. The FIFO fan-out admits
            // in launch order, so this ordering decides who is in the first wave of requests.
            return targets.values.sortedWith(catchUpOrder(foregroundBuffers.foregroundBufferId.value))
        }

        /**
         * Ask, for every merged target, the one question the wave plan turns on: has it moved since we
         * last fetched it? The cursor read is the reason this cannot live inside the pure planner.
         */
        private suspend fun classifyTargets(
            targets: List<SyncTarget>,
            source: HistorySource,
        ): List<CatchUpCandidate> =
            targets.map { target ->
                val roomId = target.knownBufferId
                val cursor = roomId?.let { db.historyCursorDao().byRoom(it) }
                // A channel this device has no room for is one the user is not in: [syncOneTarget]
                // refuses to fetch it (creating channel rooms from discovery would resurrect every
                // channel the account ever had), so letting it look "changed" would only spend a
                // wave-one slot to reach that same conclusion.
                val unjoinedChannel = roomId == null && source.isChannelTarget(target.name)
                CatchUpCandidate(
                    target = target,
                    changed =
                        !unjoinedChannel &&
                            targetChanged(
                                advertisedLatest = target.latestMessageTime,
                                cursorNewest = cursor?.newestServerTime,
                                // An unknown room has nothing stored at all, so it has never been fetched.
                                hasCursor = cursor != null,
                            ),
                )
            }

        internal suspend fun reconcileBuffer(
            networkId: Long,
            bufferId: Long,
            target: String,
            source: HistorySource,
            isCurrent: () -> Boolean = { true },
        ): HistoryResyncState {
            val ready =
                when (val availability = source.availability()) {
                    HistoryAvailability.Unsupported -> return HistoryResyncState.Unsupported
                    HistoryAvailability.NegotiatingOrOffline -> return historyUnavailable()
                    is HistoryAvailability.Ready -> availability
                }
            if (!isCurrent()) return staleConnection()
            return coalesced(
                RequestSpec(
                    RequestKey(networkId, bufferId),
                    source.flightIdentity(),
                ),
            ) {
                // A user retry or JOIN seed is its own single-buffer pass; the generation guard lets it
                // supersede a stale reconnect-pass entry for the same buffer, and vice versa.
                val session = SyncStatusSession()
                session.queue(bufferId)
                val result =
                    try {
                        val work =
                            syncRecentTarget(
                                networkId = networkId,
                                bufferId = bufferId,
                                target = target,
                                source = source,
                                isCurrent = isCurrent,
                                discoveredLatestMessageTime = null,
                                session = session,
                                allowConcurrent = ready.supportsConcurrentRequests,
                            )
                        work.status.toState(work.inserted)
                    } catch (_: TimeoutCancellationException) {
                        HistoryResyncState.Failed("History refresh timed out")
                    } catch (_: HistoryPageLoader.LatestFlightTimeoutException) {
                        // Same fact through the shared newest-page flight (this reconcile may have joined a
                        // catch-up pass's fetch, or led one it joined); the pill must read the same either way.
                        HistoryResyncState.Failed("History refresh timed out")
                    } catch (cancelled: CancellationException) {
                        session.finish(HistoryResyncState.Idle)
                        throw cancelled
                    } catch (_: StaleConnectionException) {
                        staleConnection()
                    } catch (error: Exception) {
                        HistoryResyncState.Failed(error.message?.take(160) ?: "History refresh failed")
                    }
                session.finish(if (isCurrent()) result else HistoryResyncState.Idle)
                result
            }
        }

        /**
         * Trickle in the changed rooms that did not fit the visible wave.
         *
         * Same fetches, three deliberate differences: paced like the background backfill so it cannot
         * burst, silent (no session, so no chrome and no per-buffer status for work nobody asked to
         * watch), and unable to fail the pass — a sweep that reported failure would ask the catch-up
         * loop to re-run full discovery on the reader's behalf for rooms they are not looking at. A
         * chat opened onto one of these rooms mid-sweep joins the in-flight fetch through the loader's
         * LATEST flight rather than racing a second request onto the wire.
         */
        private suspend fun sweepWaveTwo(
            networkId: Long,
            targets: List<SyncTarget>,
            source: HistorySource,
            isCurrent: () -> Boolean,
            hasDiscoveryWatermark: Boolean,
        ): Int {
            if (targets.isEmpty()) return 0
            diagnostics.record("history", "catch_up_wave_two_started") {
                mapOf("network_id" to networkId, "targets" to targets.size)
            }
            return syncTargets(
                networkId = networkId,
                targets = targets,
                source = source,
                isCurrent = isCurrent,
                hasDiscoveryWatermark = hasDiscoveryWatermark,
                session = null,
                paceBetweenTargetsMs = BACKFILL_SEED_PACE_MS,
            ).inserted
        }

        private suspend fun syncTargets(
            networkId: Long,
            targets: List<SyncTarget>,
            source: HistorySource,
            isCurrent: () -> Boolean,
            hasDiscoveryWatermark: Boolean,
            // Null publishes nothing: the paced background backfill must stay invisible.
            session: SyncStatusSession? = null,
            paceBetweenTargetsMs: Long = 0,
        ): TargetPass {
            val ready =
                when (val availability = source.availability()) {
                    HistoryAvailability.Unsupported -> error("History support disappeared during reconciliation")
                    HistoryAvailability.NegotiatingOrOffline -> error("History support became unavailable")
                    is HistoryAvailability.Ready -> availability
                }
            if (!isCurrent()) throw StaleConnectionException()
            val outcomes =
                if (ready.supportsConcurrentRequests && paceBetweenTargetsMs == 0L) {
                    // Bounded fan-out: labeled-response correlates concurrent CHATHISTORY, so a reconnect
                    // pass may keep several targets on the wire. The coordinator-side permit is
                    // load-bearing: a fetch's timeout starts when it is CALLED and includes gate wait, so
                    // launching every target's fetch at once would start every timeout clock at once and
                    // mass-expire the tail of a large pass. The width adapts to what the server keeps up
                    // with, and admission is FIFO, so the ordering [mergeSyncTargets] chose survives.
                    val fanOut = AdaptiveFanOut()
                    val nextIndex = AtomicInteger()
                    val ordered = arrayOfNulls<TargetOutcome>(targets.size)
                    coroutineScope {
                        List(minOf(targets.size, HistoryPageLoader.MAX_CONCURRENT_WIRE_REQUESTS)) {
                            async {
                                while (true) {
                                    val claimed =
                                        fanOut.withSlot {
                                            val index = nextIndex.getAndIncrement()
                                            if (index >= targets.size) {
                                                null
                                            } else {
                                                index to
                                                    syncOneTarget(
                                                        networkId = networkId,
                                                        targetSpec = targets[index],
                                                        source = source,
                                                        isCurrent = isCurrent,
                                                        hasDiscoveryWatermark = hasDiscoveryWatermark,
                                                        session = session,
                                                        paceBeforeFetchMs = 0,
                                                        allowConcurrent = true,
                                                        fanOut = fanOut,
                                                    )
                                            }
                                        } ?: break
                                    ordered[claimed.first] = claimed.second
                                }
                            }
                        }.awaitAll()
                    }
                    ordered.mapIndexed { index, outcome ->
                        checkNotNull(outcome) { "history target $index did not settle" }
                    }
                } else {
                    // Strictly sequential: connections without labeled-response, and the paced backfill
                    // seed, keep today's one-at-a-time order.
                    targets.map { targetSpec ->
                        syncOneTarget(
                            networkId = networkId,
                            targetSpec = targetSpec,
                            source = source,
                            isCurrent = isCurrent,
                            hasDiscoveryWatermark = hasDiscoveryWatermark,
                            session = session,
                            paceBeforeFetchMs = paceBetweenTargetsMs,
                            allowConcurrent = false,
                        )
                    }
                }
            // Fold in list order so the first Incomplete reason (newest-first) stays deterministic.
            return TargetPass(
                inserted = outcomes.sumOf { it.inserted },
                status =
                    outcomes.fold(WorkStatus.Complete as WorkStatus) { acc, outcome ->
                        acc.merge(outcome.status)
                    },
                highWater = maxHighWater(*outcomes.map { it.highWater }.toTypedArray()),
                retryRecommended = outcomes.any { it.retryRecommended },
            )
        }

        /**
         * One target's share of a pass: resolve its room, skip cheaply when nothing changed, fetch the
         * newest page, and settle its status. Throws [StaleConnectionException] (aborting the pass and
         * cancelling fan-out siblings) when the connection is superseded; contains target-scoped
         * permanent refusals so one bad target cannot abort the pass.
         */
        private suspend fun syncOneTarget(
            networkId: Long,
            targetSpec: SyncTarget,
            source: HistorySource,
            isCurrent: () -> Boolean,
            hasDiscoveryWatermark: Boolean,
            session: SyncStatusSession?,
            paceBeforeFetchMs: Long,
            allowConcurrent: Boolean,
            // Present only for a concurrent pass; the sequential driver has no width to adapt.
            fanOut: AdaptiveFanOut? = null,
        ): TargetOutcome {
            if (!isCurrent()) throw StaleConnectionException()
            val target = targetSpec.name
            val canonicalRoomId =
                targetSpec.knownBufferId ?: if (source.isChannelTarget(target)) {
                    return TargetOutcome()
                } else {
                    processor.ensureHistoryQuery(networkId, target, source.normalizeTarget(target))
                }
            // A target discovered mid-pass registers here; without this it would sync with no
            // status at all, because only the pass's open buffers were registered up front.
            session?.queue(canonicalRoomId)
            val roomCursor = db.historyCursorDao().byRoom(canonicalRoomId)
            if (
                hasDiscoveryWatermark &&
                targetSpec.latestMessageTime == null &&
                roomCursor != null
            ) {
                // Nothing to fetch: settle now instead of leaving a spinner up until pass end.
                session?.settle(canonicalRoomId, HistorySyncStatus.Idle)
                return TargetOutcome()
            }
            // The advertised newest is already stored: nothing new to fetch regardless of the
            // watermark. This keeps first-run retries and the paced backfill from re-requesting a
            // page for every target they have already seeded.
            val advertisedLatest = targetSpec.latestMessageTime
            if (advertisedLatest != null && reachedAdvertised(roomCursor?.newestServerTime, advertisedLatest)) {
                // Already converged, so this is the last chance to retire an advertisement the room can
                // never show: no fetch will follow, and a value left above the newest visible row keeps
                // a permanent unread dot on a room with nothing in it. A no-op (and no invalidation)
                // whenever the two already agree.
                processor.clampAdvertisedActivity(networkId, canonicalRoomId, advertisedLatest)
                session?.settle(canonicalRoomId, HistorySyncStatus.Idle)
                return TargetOutcome()
            }
            if (paceBeforeFetchMs > 0) delay(paceBeforeFetchMs)
            val targetResult =
                try {
                    syncRecentTarget(
                        networkId = networkId,
                        bufferId = canonicalRoomId,
                        target = target,
                        source = source,
                        isCurrent = isCurrent,
                        discoveredLatestMessageTime = targetSpec.latestMessageTime,
                        session = session,
                        allowConcurrent = allowConcurrent,
                    )
                } catch (refused: IrcCommandException) {
                    // A target-scoped permanent refusal (services such as ChanServ typically answer
                    // FAIL CHATHISTORY INVALID_TARGET) must not abort the pass: letting it escape
                    // skipped every remaining target, marked every open buffer Failed, and left an
                    // unrecoverable retry banner because the next attempt reissues the same request.
                    if (refused.code != HistoryPageLoader.INVALID_TARGET) throw refused
                    diagnostics.record("history", "target_history_refused") {
                        mapOf(
                            "network_id" to networkId,
                            "room_id" to canonicalRoomId,
                            "target_fp" to diagnostics.fingerprint(source.normalizeTarget(target)),
                            "code" to refused.code,
                        )
                    }
                    // The server will never serve this target; a retry affordance would be a lie.
                    session?.settle(canonicalRoomId, HistorySyncStatus.Unavailable)
                    return TargetOutcome()
                } catch (_: TimeoutCancellationException) {
                    return timedOutTarget(networkId, canonicalRoomId, fanOut)
                } catch (_: HistoryPageLoader.LatestFlightTimeoutException) {
                    // The same timeout, reported by a shared LATEST flight rather than by this target's own
                    // withTimeout: a chat screen opening onto this room can be the flight's LEADER, and the
                    // typed marker is what keeps the leader's identity from deciding what this pass sees.
                    // Without it, a Paging-led timeout arrived here as a transport failure, missed the
                    // carve-out beside it, and aborted the whole pass — the mass abort it exists to remove.
                    return timedOutTarget(networkId, canonicalRoomId, fanOut)
                }
            fanOut?.onSuccess()
            // TARGETS describes the newest server event, which may be a JOIN or an event that is
            // intentionally filtered/rerouted during ingestion. Count either a durable local event
            // or an event observed in this response as reaching it; relying on the chat cursor alone
            // would retry forever for those valid cases.
            val newestStoredTime =
                maxHighWater(
                    db.messageDao().latestBoundary(canonicalRoomId)?.serverTime,
                    db.historyCursorDao().byRoom(canonicalRoomId)?.newestServerTime,
                    targetResult.highWater,
                )
            val reachedAdvertisedLatest =
                targetSpec.latestMessageTime?.let { latest ->
                    reachedAdvertised(newestStoredTime, latest)
                }
            if (
                reachedAdvertisedLatest == false &&
                targetResult.status == WorkStatus.Complete &&
                targetResult.inserted == 0
            ) {
                // The server proved it will replay nothing newer: LATEST is by definition the newest
                // page, and it added no rows, yet TARGETS still advertises a newer timestamp. That
                // timestamp is not reachable via CHATHISTORY (soju can index an event that replay
                // never returns), so painting Partial and recommending retries would never converge.
                // Deliberately NOT keyed on an end-of-history marker: soju 0.10.x omits
                // draft/chathistory-end on message batches too, so a terminal-page condition would
                // never fire against it. A miss that DID insert rows still reports Incomplete below,
                // giving a genuinely new message one more pass to be reached before this settles.
                diagnostics.record("history", "advertised_latest_unreachable") {
                    mapOf("network_id" to networkId, "room_id" to canonicalRoomId)
                }
                // Retire the advertisement with the same response that disproved it. Nothing else ever
                // will: the room is settled Idle, no later page can reach that timestamp, and the
                // discovery-first cue would otherwise sit on this row as an unread dot the reader
                // cannot clear (mark-read anchors on the newest LOCAL row, which is below it).
                processor.clampAdvertisedActivity(networkId, canonicalRoomId, advertisedLatest)
                session?.settle(canonicalRoomId, HistorySyncStatus.Idle)
                return TargetOutcome(highWater = targetResult.highWater)
            }
            val effectiveStatus =
                if (
                    reachedAdvertisedLatest == false && targetResult.status == WorkStatus.Complete
                ) {
                    WorkStatus.Incomplete("CHATHISTORY did not reach the latest advertised message")
                } else {
                    targetResult.status
                }
            // Converged against what discovery advertised: whatever is still above the room's newest
            // visible row is an event this device is never going to show (a JOIN, a filtered or
            // rerouted event), so the cue that stands in for its rows has nothing left to describe.
            if (reachedAdvertisedLatest == true) {
                processor.clampAdvertisedActivity(networkId, canonicalRoomId, advertisedLatest)
            }
            session?.settle(
                canonicalRoomId,
                if (reachedAdvertisedLatest != false || targetResult.status == WorkStatus.Complete) {
                    // A terminal fetch that fell short of the advertisement is bookkeeping, not damage:
                    // the automatic pass retry keeps chasing genuinely lagging replay, but a buffer
                    // whose own fetch succeeded end-to-end must not wear an error badge.
                    HistorySyncStatus.Idle
                } else {
                    effectiveStatus.toSyncStatus()
                },
            )
            return TargetOutcome(
                inserted = targetResult.inserted,
                status = effectiveStatus,
                highWater = targetResult.highWater,
                retryRecommended = reachedAdvertisedLatest == false,
            )
        }

        /**
         * One target ran out of its request budget — from its own `withTimeout` or from a shared LATEST
         * flight it joined; the two are the same fact about the server and must be read the same way.
         *
         * Order matters: a sibling that failed cancels this coroutine too, and cancellation inside the
         * fetch surfaces as a timeout it did not cause. Prove this coroutine is still alive BEFORE
         * reading the timeout as evidence about the server, or one slow target would shrink the fan-out
         * width once per cancelled sibling.
         *
         * One target running out of budget is not a failed pass. It used to be: the timeout escaped,
         * every remaining target was skipped, and every open buffer wore the failure. It is reported as
         * this target's own incompleteness and the pass's retry decides.
         */
        private suspend fun timedOutTarget(
            networkId: Long,
            roomId: Long,
            fanOut: AdaptiveFanOut?,
        ): TargetOutcome {
            currentCoroutineContext().ensureActive()
            fanOut?.onTimeout()
            diagnostics.record("history", "target_history_timed_out") {
                mapOf("network_id" to networkId, "room_id" to roomId)
            }
            return TargetOutcome(
                status = WorkStatus.Incomplete("CHATHISTORY request timed out"),
                retryRecommended = true,
            )
        }

        /**
         * Seed one changed target newest-first. Each completed response is published immediately so a
         * visible chat can paint after one round trip; any older retained interval remains a durable
         * gap for directional Paging instead of being traversed during reconnect.
         */
        private suspend fun syncRecentTarget(
            networkId: Long,
            bufferId: Long,
            target: String,
            source: HistorySource,
            isCurrent: () -> Boolean,
            discoveredLatestMessageTime: Long?,
            session: SyncStatusSession? = null,
            allowConcurrent: Boolean = false,
        ): WorkResult {
            val room = db.bufferDao().observeById(bufferId) ?: throw StaleConnectionException()
            val referenceTypes = source.referenceTypes()
            val msgidAllowed = HistoryReferenceType.MSGID in referenceTypes
            val discardedBoundary =
                ChatHistoryReference(
                    room.historyDiscardedThroughMsgid,
                    room.historyDiscardedThroughTime,
                ).takeIf { it.msgid != null || it.serverTime != null }
            if (room.dismissed && discoveredLatestMessageTime == null) return WorkResult()
            val discardedThroughTime = discardedBoundary?.serverTime
            if (
                room.dismissed &&
                discardedThroughTime != null &&
                discoveredLatestMessageTime != null &&
                discoveredLatestMessageTime <= discardedThroughTime
            ) {
                return WorkResult()
            }

            val requestLimit = minOf(source.pageLimit(), RECENT_PAGE_SIZE)
            // Everything above can settle this target without a round trip, so only announce Syncing
            // once a request is genuinely about to go on the wire.
            session?.syncing(bufferId)
            val boundedLatest =
                discardedBoundary
                    ?.takeIf { room.type == BufferType.QUERY }
                    ?.let { floor ->
                        fetchPageOrNullOnRejectedBoundary(
                            networkId = networkId,
                            target = target,
                            subcommand = ChatHistoryRequest.Subcommand.LATEST,
                            source = source,
                            boundary = floor,
                            secondBoundary = null,
                            referenceTypes = referenceTypes,
                            limit = requestLimit,
                            msgidAllowed = msgidAllowed,
                            allowConcurrent = allowConcurrent,
                        )
                    }
            // The ordinary newest-page seed goes through the loader's shared LATEST flight, so a chat
            // opened onto this room while the pass is reaching it joins this exact fetch instead of
            // putting an identical request on the wire behind it. The dismissed-query variant below is
            // deliberately excluded: its bounded floor makes it a different question about a different
            // interval, and joining it to the plain seed would hand one of them the wrong page.
            val request: ChatHistoryRequest
            val page: ChatHistoryResponse.Messages
            val inserted: Int
            if (boundedLatest != null) {
                request = boundedLatest.request
                page = boundedLatest.response
                if (!isCurrent()) throw StaleConnectionException()
                inserted = ingest(networkId, bufferId, request, page)
            } else {
                val latest =
                    loader.fetchLatest(
                        networkId = networkId,
                        roomId = bufferId,
                        target = target,
                        source = source,
                        requestLimit = requestLimit,
                        referenceTypes = referenceTypes,
                        timeoutMs = requestTimeoutMs,
                        allowConcurrent = allowConcurrent,
                    )
                request = latest.request
                page = latest.response
                inserted = latest.inserted
                if (!isCurrent()) throw StaleConnectionException()
            }
            val highWater = page.highWater()
            if (page.isTerminalPage()) return WorkResult(highWater = highWater, inserted = inserted)
            if (page.oldest?.msgid == null && page.primaryMessageCount >= request.limit) {
                // A full timestamp-only page that deduplicated entirely into the store proves the head
                // is already converged: the saturation ambiguity (same-timestamp siblings beyond the
                // cut) can only hide rows when this page ADDED rows. Without this, a busy msgid-less
                // channel re-fetched and re-failed on every pass forever.
                if (inserted == 0) return WorkResult(highWater = highWater, inserted = 0)
                return WorkResult(
                    WorkStatus.Incomplete("CHATHISTORY timestamp boundary is saturated"),
                    highWater,
                    inserted,
                )
            }

            if (page.directionalBoundary(ChatHistoryRequest.Subcommand.LATEST) == null) {
                return WorkResult(
                    WorkStatus.Incomplete("CHATHISTORY LATEST returned no usable oldest boundary"),
                    highWater,
                    inserted,
                )
            }
            // ingest persisted this non-terminal oldest boundary as a durable gap. Automatic reconnect
            // stops here; only user-authorized paging or manual refresh may traverse it with BEFORE.
            return WorkResult(highWater = highWater, inserted = inserted)
        }

        override suspend fun manualPass(
            networkId: Long,
            bufferIds: List<Long>,
            work: suspend (Long) -> Unit,
        ) {
            // NOT beginNetworkSession: backfills use their own progress key and registry so a
            // reconnect pass can keep its slot, terminal, and header through the overlap.
            val session =
                synchronized(retireGuard) {
                    SyncStatusSession(networkId, Any(), sessionTickets.incrementAndGet(), chromeEligible = true, backfill = true)
                        .also { backfillSessions[networkId] = it }
                }
            try {
                session.queueAll(bufferIds)
                // The user asked for this pass, so there is no "did discovery find anything" question to
                // wait on before announcing it: publish the counters straight away.
                session.activate()
                for (bufferId in bufferIds) {
                    session.syncing(bufferId)
                    work(bufferId)
                    session.settle(bufferId, HistorySyncStatus.Idle)
                }
                session.finish(HistoryResyncState.UpToDate)
            } catch (cancelled: CancellationException) {
                // A user stop is not a failure: nothing gets an error badge.
                session.finish(HistoryResyncState.UpToDate)
                throw cancelled
            } catch (failure: Throwable) {
                session.finish(HistoryResyncState.Failed("manual_pass"))
                throw failure
            } finally {
                synchronized(retireGuard) { backfillSessions.remove(networkId, session) }
            }
        }

        /** Manual eager recovery retained for explicit Missing/All Available requests. */
        private suspend fun coalesced(
            spec: RequestSpec,
            block: suspend () -> HistoryResyncState,
        ): HistoryResyncState {
            val registration =
                synchronized(activeGuard) {
                    val joined = activeFlights[spec]
                    if (joined != null) {
                        FlightRegistration(joined, ownsFlight = false)
                    } else {
                        val deferred =
                            scope.async(start = CoroutineStart.LAZY) {
                                // Wire admission lives in the loader's per-network gate, acquired per fetch
                                // inside block(); this flight only owns request-level coalescing and
                                // user-facing status.
                                block()
                            }
                        val created = ActiveFlight(spec, deferred)
                        activeFlights[spec] = created
                        deferred.invokeOnCompletion {
                            removeActiveFlight(created)
                        }
                        FlightRegistration(created, ownsFlight = true)
                    }
                }
            val flight = registration.flight
            if (registration.ownsFlight) flight.deferred.start()
            try {
                return flight.deferred.await()
            } finally {
                if (flight.deferred.isCompleted) {
                    removeActiveFlight(flight)
                }
            }
        }

        private fun removeActiveFlight(flight: ActiveFlight) {
            synchronized(activeGuard) {
                if (activeFlights[flight.spec] === flight) {
                    activeFlights.remove(flight.spec)
                }
            }
        }

        /**
         * [HistoryPageLoader.fetchPage] rethrows the server's original `INVALID_MSGREFTYPE` when a
         * rejected msgid boundary has no advertised timestamp fallback, so Paging surfaces those
         * diagnostics via MediatorResult.Error. Reconnect/manual orchestration instead treats that
         * unrecoverable local boundary exactly like a pre-check rejection (null): callers degrade to a
         * LATEST seed rather than failing the whole pass on a stale stored cursor.
         */
        private suspend fun fetchPageOrNullOnRejectedBoundary(
            networkId: Long,
            target: String,
            subcommand: ChatHistoryRequest.Subcommand,
            source: HistorySource,
            boundary: ChatHistoryReference,
            secondBoundary: ChatHistoryReference?,
            referenceTypes: Set<HistoryReferenceType>,
            limit: Int,
            msgidAllowed: Boolean,
            allowConcurrent: Boolean = false,
        ): HistoryPageLoader.FetchedPage? =
            try {
                loader.fetchPage(
                    networkId = networkId,
                    target = target,
                    subcommand = subcommand,
                    source = source,
                    boundary = boundary,
                    secondBoundary = secondBoundary,
                    referenceTypes = referenceTypes,
                    limit = limit,
                    msgidAllowed = msgidAllowed,
                    timeoutMs = requestTimeoutMs,
                    allowConcurrent = allowConcurrent,
                )
            } catch (error: IrcCommandException) {
                // Only the exact no-fallback msgid rejection degrades; every other command error (including
                // a rejected timestamp selector) still propagates as a failure.
                val unrecoverableMsgidRejection =
                    error.code == HistoryPageLoader.INVALID_MSGREFTYPE &&
                        loader.selectorOf(boundary, referenceTypes, msgidAllowed = false) == null
                if (!unrecoverableMsgidRejection) throw error
                null
            }

        private suspend fun HistorySource.referenceTypes(): Set<HistoryReferenceType> = (availability() as? HistoryAvailability.Ready)?.referenceTypes ?: emptySet()

        private suspend fun HistorySource.pageLimit(): Int =
            ((availability() as? HistoryAvailability.Ready)?.pageLimit ?: PAGE_LIMIT)
                .coerceAtMost(PAGE_LIMIT)
                .coerceAtLeast(1)

        private suspend fun HistorySource.supportsReference(type: HistoryReferenceType): Boolean =
            (availability() as? HistoryAvailability.Ready)
                ?.referenceTypes
                ?.contains(type) == true

        private suspend fun latestBoundaryFromRoom(bufferId: Long): ChatHistoryReference? = db.messageDao().latestBoundary(bufferId)?.let { ChatHistoryReference(it.msgid, it.serverTime) }

        private suspend fun hasStoredChat(bufferId: Long): Boolean = db.messageDao().hasStoredChat(bufferId)

        private fun ChatHistoryResponse.Messages.isTerminalPage(): Boolean = endOfHistory || primaryMessageCount == 0

        private fun ChatHistoryResponse.Messages.directionalBoundary(
            subcommand: ChatHistoryRequest.Subcommand,
        ): ChatHistoryReference? =
            when (subcommand) {
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
            referenceTypes: Set<HistoryReferenceType>,
        ): Boolean =
            directionalBoundary(subcommand)
                ?.let { loader.selectorOf(it, referenceTypes, HistoryReferenceType.MSGID in referenceTypes) } != null

        private fun ChatHistoryResponse.Messages.highWater(): Long? = if (primaryMessageCount == 0) null else maxHighWater(oldest?.serverTime, newest?.serverTime)

        private suspend fun ingest(
            networkId: Long,
            expectedRoomId: RoomId,
            request: ChatHistoryRequest,
            page: ChatHistoryResponse.Messages,
            historyGapId: Long? = null,
        ): Int = ingestResult(networkId, expectedRoomId, request, page, historyGapId).inserted

        private suspend fun ingestResult(
            networkId: Long,
            expectedRoomId: RoomId,
            request: ChatHistoryRequest,
            page: ChatHistoryResponse.Messages,
            historyGapId: Long? = null,
        ): io.github.trevarj.motd.data.sync.PersistedHistoryPage {
            if (db.bufferDao().rawById(expectedRoomId) == null) throw StaleConnectionException()
            return processor.persistHistoryPageResult(
                networkId,
                request,
                page,
                expectedRoomId = expectedRoomId,
                historyGapId = historyGapId,
            )
        }

        /**
         * Fetching remains outside Room, while every page collected for one room is committed in one
         * transaction after traversal settles. This keeps Paging from observing partially reconciled
         * rows or intermediate ordering. Already-validated pages are retained on timeout/cancellation,
         * matching the previous eager-persistence behavior without exposing its incremental redraws.
         */
        private fun beginSyncStatus(
            bufferId: Long,
            status: HistorySyncStatus,
        ): Long {
            val generation =
                syncStatusGenerations
                    .computeIfAbsent(bufferId) { AtomicLong() }
                    .incrementAndGet()
            _syncStatuses.update { current ->
                initialSyncStatusIfCurrent(
                    current = current,
                    bufferId = bufferId,
                    generation = generation,
                    currentGeneration = syncStatusGenerations[bufferId]?.get(),
                    status = status,
                )
            }
            return generation
        }

        /**
         * Take ownership of a buffer's status without publishing anything: the silent registration a
         * permanently refused target gets, so this pass can still settle it while its Unavailable badge
         * stays exactly as it was.
         */
        private fun bumpSyncStatusGeneration(bufferId: Long): Long = syncStatusGenerations.computeIfAbsent(bufferId) { AtomicLong() }.incrementAndGet()

        private fun publishSyncStatus(
            bufferId: Long,
            generation: Long,
            status: HistorySyncStatus,
        ) {
            _syncStatuses.update { current ->
                if (syncStatusGenerations[bufferId]?.get() == generation) {
                    current + (bufferId to status)
                } else {
                    current
                }
            }
        }

        private fun finishSyncStatus(
            bufferId: Long,
            generation: Long,
            status: HistorySyncStatus,
        ) {
            _syncStatuses.update { current ->
                if (syncStatusGenerations[bufferId]?.get() != generation) {
                    current
                } else if (status == HistorySyncStatus.Idle) {
                    current - bufferId
                } else {
                    current + (bufferId to status)
                }
            }
        }

        private fun WorkStatus.toState(
            inserted: Int,
            retryRecommended: Boolean = false,
        ): HistoryResyncState =
            when (this) {
                WorkStatus.Complete -> {
                    if (inserted > 0) HistoryResyncState.Updated(inserted) else HistoryResyncState.UpToDate
                }

                is WorkStatus.Incomplete -> {
                    HistoryResyncState.Incomplete(
                        inserted,
                        reason,
                        awaitsTargetClassification,
                        retryRecommended,
                    )
                }

                is WorkStatus.Capped -> {
                    HistoryResyncState.Capped(inserted, limit, reason)
                }
            }

        private fun WorkStatus.toSyncStatus(): HistorySyncStatus =
            when (this) {
                WorkStatus.Complete -> HistorySyncStatus.Idle
                is WorkStatus.Incomplete -> HistorySyncStatus.Partial(reason)
                is WorkStatus.Capped -> HistorySyncStatus.Partial(reason)
            }

        private fun HistoryResyncState.toSyncStatus(): HistorySyncStatus =
            when (this) {
                HistoryResyncState.Idle,
                is HistoryResyncState.Updated,
                HistoryResyncState.UpToDate,
                HistoryResyncState.Unsupported,
                -> HistorySyncStatus.Idle

                HistoryResyncState.WaitingForCapability -> HistorySyncStatus.Queued

                is HistoryResyncState.Running -> HistorySyncStatus.Syncing

                is HistoryResyncState.Incomplete -> HistorySyncStatus.Partial(reason)

                is HistoryResyncState.Capped -> HistorySyncStatus.Partial(reason)

                is HistoryResyncState.Failed -> HistorySyncStatus.Failed(reason)
            }

        private fun WorkStatus.merge(other: WorkStatus): WorkStatus =
            when {
                this is WorkStatus.Incomplete -> this
                other is WorkStatus.Incomplete -> other
                this is WorkStatus.Capped -> this
                other is WorkStatus.Capped -> other
                else -> WorkStatus.Complete
            }

        private fun maxHighWater(vararg values: Long?): Long? = values.filterNotNull().maxOrNull()

        /** See [reachedAdvertisedTolerance]; shared with the wave planner so the two cannot disagree. */
        private fun reachedAdvertised(
            stored: Long?,
            advertised: Long,
        ): Boolean = reachedAdvertisedTolerance(stored, advertised)

        private class ClientHistorySource(
            private val client: IrcClient,
        ) : HistorySource {
            override suspend fun availability(): HistoryAvailability = client.historyAvailability

            override fun flightIdentity(): Any = client

            override fun canClassifyTargets(): Boolean = client.targetClassificationReady.value

            override fun normalizeTarget(target: String): String = client.isupport.identityRules.normalize(target)

            override fun isChannelTarget(target: String): Boolean = client.isupport.identityRules.isChannel(target)

            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse = client.chathistory(req)
        }

        private class StaleConnectionException : Exception()

        private fun staleConnection(): HistoryResyncState.Failed = HistoryResyncState.Failed("Connection changed; try again")

        private fun historyUnavailable(): HistoryResyncState.Failed = HistoryResyncState.Failed("History support is still negotiating or the connection is offline")

        internal companion object {
            const val PAGE_LIMIT = 100
            const val RECENT_PAGE_SIZE = 50
            const val REQUEST_TIMEOUT_MS = 35_000L
            const val PENDING_MESSAGE_TIMEOUT_MS = 65_000L
            const val TARGETS_FUZZ_MS = 10_000L
            const val TARGET_CLASSIFICATION_WAIT_TIMEOUT_MS = 10_000L
            const val TARGETS_REQUEST_LIMIT = 100

            /** First-sync TARGETS window; everything older belongs to the paced backfill. */
            const val INITIAL_SYNC_LOOKBACK_MS = 30L * 24 * 60 * 60 * 1_000

            /** Delay between backfill TARGETS requests. */
            const val BACKFILL_TARGETS_PACE_MS = 2_000L

            /** Delay before each backfill per-target newest-page seed. */
            const val BACKFILL_SEED_PACE_MS = 500L
        }
    }
