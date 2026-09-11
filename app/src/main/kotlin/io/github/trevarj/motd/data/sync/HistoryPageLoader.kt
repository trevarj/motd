package io.github.trevarj.motd.data.sync

import io.github.trevarj.motd.avatar.AvatarController
import io.github.trevarj.motd.avatar.NoopAvatarController
import io.github.trevarj.motd.data.db.RoomId
import io.github.trevarj.motd.diagnostics.DiagnosticLogger
import io.github.trevarj.motd.irc.client.ChatHistoryReference
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sole owner of a single CHATHISTORY page fetch: it builds the directional request from a
 * caller-supplied local boundary, applies the msgid→timestamp fallback, guards unsafe continuation,
 * persists the page through the sole IRC→Room writer ([EventProcessor]), and owns all fetch
 * concurrency (per-network wire admission, per-direction coalescing, and the request timeout).
 *
 * Directional decisions — which boundary to page from, and how a per-focus gap constrains the
 * endOfPagination outcome — stay with the caller ([ChatHistoryRemoteMediator]); the loader only
 * turns a `(direction, boundary)` pair into one persisted page and reports whether that direction is
 * exhausted or must stop to avoid a refetch loop.
 *
 * The loader is also the single wire-access primitive for the orchestration in
 * [io.github.trevarj.motd.service.HistoryResyncCoordinator]: its multi-page reconnect/manual
 * traversals build no requests of their own but call [fetchPage]/[fetchMessages]/[fetchTargets],
 * which share this loader's per-network wire gates so a scroll fetch and a reconnect catch-up
 * admit on the same bounded gate (width 1 without labeled-response, so strictly serialized)
 * instead of racing pages into the same timeline. The deep-link/reply-quote AROUND prefetch joins
 * them through [loadAround], so every CHATHISTORY request the app issues — Paging-driven,
 * coordinator-issued, or jump-driven — is admitted, coalesced, and persisted here.
 */
@Singleton
class HistoryPageLoader
    @Inject
    constructor(
        private val processor: EventProcessor,
        // Opt-in fetch journal (availability gates, per-page outcomes, wire timeouts). Fields carry
        // classification, ids, counts, timestamps, and msgid PRESENCE only — never message content.
        private val diagnostics: DiagnosticLogger = DiagnosticLogger.Noop,
        private val avatarController: AvatarController = NoopAvatarController,
    ) {
        /**
         * Minimal seam over the live history transport (availability + a single labeled request),
         * resolved per call so a client that connects after a buffer opens is picked up on the next
         * boundary hit. Callers reuse this exact shape for their own source seams.
         */
        interface HistorySource {
            suspend fun availability(): HistoryAvailability

            suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse
        }

        /**
         * The directions a boundary can be paged toward. LATEST ignores the boundary; AROUND is
         * anchored on an exact message rather than on a local boundary and is issued through
         * [loadAround] rather than [loadPage].
         */
        enum class Direction { OLDER, NEWER, LATEST, AROUND }

        /** Outcome of a single page fetch. */
        sealed interface PageResult {
            /**
             * A page was fetched and persisted. [primaryCount] is the fetched primary-message count and
             * [insertedCount] the durable rows the persist actually added. [endOfDirection] is true when
             * this direction is exhausted (server end reached) or must stop to avoid a
             * non-advancing/saturated refetch loop; callers may still narrow that with their own
             * per-focus gap accounting.
             */
            data class Loaded(
                val primaryCount: Int,
                val insertedCount: Int,
                val endOfDirection: Boolean,
            ) : PageResult

            /** The network does not advertise CHATHISTORY. */
            data object Unsupported : PageResult

            /** History is negotiating or offline; the request is retryable. */
            data class Unavailable(
                val cause: Throwable,
            ) : PageResult

            /** The server returned a response that could not be used as a durable boundary. */
            data class Failed(
                val cause: Throwable,
            ) : PageResult
        }

        /**
         * One persisted CHATHISTORY LATEST page, as every newest-page caller sees it.
         *
         * Carries the exact [request] that produced it (its limit is what the saturation ambiguity is
         * measured against) and the [referenceTypes] the connection advertised, so a caller that joined
         * the flight rather than leading it draws its conclusions from the same facts the leader did.
         */
        internal data class LatestPage(
            val request: ChatHistoryRequest,
            val response: ChatHistoryResponse.Messages,
            val inserted: Int,
            val referenceTypes: Set<HistoryReferenceType>,
        )

        /**
         * The shared LATEST flight ran out of its budget.
         *
         * A newest-page flight has two kinds of caller — Paging, which needs a retryable transport
         * failure, and the catch-up coordinator, which needs a per-target timeout it can adapt its
         * fan-out to — and whichever of them happens to LEAD the flight decides nothing: the flight
         * completes with this one typed outcome and each caller classifies it for itself ([loadPage]
         * for Paging, `syncOneTarget` for the coordinator). Leaking the leader's own classification to
         * its followers is how a Paging-led timeout used to reach the coordinator as a transport
         * failure, escape its timeout carve-out, and abort a whole catch-up pass.
         *
         * Deliberately NOT a [kotlinx.coroutines.CancellationException]: [coalesced] reads cancellation
         * as "the leader abandoned this flight" and lets followers re-lead it, which for a timeout means
         * silently putting a second identical request on a wire that just proved it is too slow.
         */
        internal class LatestFlightTimeoutException : Exception("CHATHISTORY LATEST request timed out")

        /** A fetched, boundary-trimmed page plus the selector used and whether msgids remain usable. */
        internal data class FetchedPage(
            val response: ChatHistoryResponse.Messages,
            val request: ChatHistoryRequest,
            val selector: BoundarySelector,
            val msgidAllowed: Boolean,
        )

        /** A CHATHISTORY selector value plus the reference type it was derived from. */
        internal data class BoundarySelector(
            val value: String,
            val type: HistoryReferenceType,
        )

        // Sole fetch admission for Paging-driven pages, the coordinator's reconnect/manual traversals,
        // and the jump AROUND prefetch: all of them acquire these per-network gates (Phase 3 gate
        // collapse). Width is 1 (strict serialization, Mutex-equivalent) unless the connection
        // correlates concurrent CHATHISTORY via labeled-response, in which case a bounded number of
        // requests may share the wire.
        private class WireGate(
            val width: Int,
            val semaphore: Semaphore,
        )

        private val networkGates = ConcurrentHashMap<Long, WireGate>()

        // The payload type is fixed by the key's direction and never mixed: LATEST flights carry a
        // [LatestPage] (its two callers classify the same page differently), every other direction
        // carries a [PageResult]. Erased to Any only because one map cannot express that.
        private val inFlight = ConcurrentHashMap<FlightKey, CompletableDeferred<Any>>()
        internal var requestTimeoutMs: Long = REQUEST_TIMEOUT_MS

        internal suspend fun persistHistoryPageResult(
            networkId: Long,
            request: ChatHistoryRequest,
            response: ChatHistoryResponse.Messages,
            expectedRoomId: RoomId?,
            historyGapId: Long? = null,
        ): PersistedHistoryPage {
            avatarController.ingestDickordAvatars(networkId, response.events)
            return processor.persistHistoryPageResult(
                networkId,
                request,
                response,
                expectedRoomId,
                historyGapId,
            )
        }

        /**
         * Forget the wire gate for [networkId]; the network's connection is gone.
         *
         * [networkGates] is keyed by network and lives for the process, so this is the only thing that
         * ever shrinks it: a deleted network would otherwise keep its semaphore forever, and a later
         * connection reusing the id would inherit the retired connection's gate — including a permit
         * held by a request that outlived its socket, which would make the successor's first fetch
         * queue behind a page that is never coming.
         *
         * Safe against a fetch that is still inside the gate: [onWireLock] resolves its [WireGate] once
         * and holds that reference for the whole permit, so dropping the map entry only means the next
         * fetch builds a fresh gate. That is the same mechanic the width swap in [onWireLock] already
         * depends on, and it is why a straggler on a dead socket cannot serialize against a live one —
         * it is not on the same wire, so it must not be on the same gate.
         */
        fun releaseNetwork(networkId: Long) {
            networkGates.remove(networkId)
        }

        /**
         * The identity a concurrent fetch may join.
         *
         * The key still omits the BOUNDARY, and for the original reason: two Paging generations of the
         * same ladder re-read the local store after each page and issue their next load from their own
         * boundary, so joining whichever page is in flight is safe and stops a generation swap from
         * double-fetching. Both of them carry the same [gapId], so they still coalesce.
         *
         * It does NOT omit [gapId], because that is the one thing that makes two older-direction fetches
         * for the same room genuinely different questions. `null` is the bottom-of-timeline ladder,
         * which pages strictly below every open gap; a non-null id is a fill of one specific interior
         * interval. Coalescing across that split hands the follower a page for an interval it never
         * asked for and credits it with rows it did not fetch: its own boundary never moves and it reads
         * its zero inserts as "this interval is exhausted", which is precisely how a bounded gap fill
         * used to end after one page having inserted nothing.
         *
         * [anchor] is the same distinction for AROUND, whose interval is decided by an exact message
         * rather than by a local boundary: two jumps to different messages in one room are different
         * questions and must not join. It stays null for every boundary-paged direction, so the ladder
         * and gap-fill keys remain value-identical to what they were before AROUND joined the loader.
         */
        private data class FlightKey(
            val networkId: Long,
            val roomId: RoomId,
            val direction: Direction,
            val gapId: Long?,
            val anchor: String? = null,
        )

        /**
         * Fetch and persist exactly one page for [roomId] (always canonical) in [direction] from
         * [boundary]. Concurrent identical `(network, room, direction, gapId)` requests coalesce onto one
         * in-flight fetch; distinct fetches on the same network still serialize on the wire.
         */
        suspend fun loadPage(
            networkId: Long,
            roomId: RoomId,
            target: String,
            direction: Direction,
            source: HistorySource,
            pageSize: Int = 50,
            gapId: Long? = null,
            boundary: ChatHistoryReference? = null,
        ): PageResult {
            val availability = source.availability()
            diagnostics.record("chat_history", "loader_page_requested") {
                mapOf(
                    "network_id" to networkId,
                    "room_id" to roomId,
                    "direction" to direction.name,
                    "availability" to availability::class.simpleName,
                    "boundary_has_msgid" to (boundary?.msgid != null),
                    "boundary_server_time" to boundary?.serverTime,
                    "gap_id" to gapId,
                )
            }
            val ready =
                when (availability) {
                    HistoryAvailability.Unsupported -> return PageResult.Unsupported

                    HistoryAvailability.NegotiatingOrOffline -> return PageResult.Unavailable(
                        IrcDisconnectedException("CHATHISTORY", "history is negotiating or offline"),
                    )

                    is HistoryAvailability.Ready -> availability
                }
            val requestLimit = minOf(pageSize, ready.pageLimit).coerceAtLeast(1)
            val referenceTypes = ready.referenceTypes
            // Keep every fetch on this connection at one coherent gate width; mixed widths per call
            // would thrash the per-network gate.
            val allowConcurrent = ready.supportsConcurrentRequests
            return when (direction) {
                // LATEST coalesces one level down, in [fetchLatest], because the coordinator's own
                // newest-page fetch has to join the SAME flight: a chat opened while a catch-up pass is
                // seeding that room must not put a second identical LATEST on the wire.
                Direction.LATEST -> {
                    try {
                        fetchLatest(
                            networkId,
                            roomId,
                            target,
                            source,
                            requestLimit,
                            referenceTypes,
                            requestTimeoutMs,
                            allowConcurrent = allowConcurrent,
                        ).toPageResult()
                    } catch (_: LatestFlightTimeoutException) {
                        // Paging's half of the shared flight's timeout: a retryable transport failure, never
                        // a CancellationException, or the mediator would rethrow it to Paging and freeze
                        // this direction's LoadState at Loading behind a stale pending request.
                        throw IrcDisconnectedException("CHATHISTORY", "request timed out")
                    }
                }

                Direction.OLDER -> {
                    coalesced(FlightKey(networkId, roomId, direction, gapId)) {
                        loadOlder(
                            networkId,
                            roomId,
                            target,
                            source,
                            requestLimit,
                            referenceTypes,
                            gapId,
                            boundary,
                            allowConcurrent,
                        )
                    }
                }

                Direction.NEWER -> {
                    coalesced(FlightKey(networkId, roomId, direction, gapId)) {
                        loadNewer(
                            networkId,
                            roomId,
                            target,
                            source,
                            requestLimit,
                            referenceTypes,
                            gapId,
                            boundary,
                            allowConcurrent,
                        )
                    }
                }

                // AROUND is anchored on a message, not on a local boundary, so it cannot be
                // expressed as a (direction, boundary) pair; [loadAround] is its entry point.
                Direction.AROUND -> {
                    PageResult.Failed(
                        IllegalArgumentException("CHATHISTORY AROUND is loaded through loadAround"),
                    )
                }
            }.also { result ->
                diagnostics.record("chat_history", "loader_page_result") {
                    mapOf(
                        "room_id" to roomId,
                        "direction" to direction.name,
                        "result" to result::class.simpleName,
                        "primary_count" to (result as? PageResult.Loaded)?.primaryCount,
                        "inserted_count" to (result as? PageResult.Loaded)?.insertedCount,
                        "end_of_direction" to (result as? PageResult.Loaded)?.endOfDirection,
                    )
                }
            }
        }

        /**
         * Fetch and persist exactly one CHATHISTORY AROUND page centred on [msgid] (with [timeMs] as
         * its timestamp fallback) for [roomId] (always canonical).
         *
         * This is the deep-link / reply-quote prefetch: the target message is not local yet, so there is
         * no boundary to page from and [loadPage] cannot express it. It is deliberately a SINGLE
         * attempt — a jump either lands or reports "not found" to the reader, and a silent retry behind
         * a positioning gate is worse than a miss — so a wire timeout resolves as [PageResult.Unavailable]
         * rather than escaping as a cancellation that would kill the caller's resolution job.
         *
         * Concurrent jumps to the SAME message coalesce; jumps to different messages in one room do not,
         * because the interval each one asks for is decided by its own anchor.
         */
        suspend fun loadAround(
            networkId: Long,
            roomId: RoomId,
            target: String,
            msgid: String,
            timeMs: Long,
            limit: Int,
            source: HistorySource,
        ): PageResult {
            val availability = source.availability()
            val ready =
                when (availability) {
                    HistoryAvailability.Unsupported -> return PageResult.Unsupported

                    HistoryAvailability.NegotiatingOrOffline -> return PageResult.Unavailable(
                        IrcDisconnectedException("CHATHISTORY", "history is negotiating or offline"),
                    )

                    is HistoryAvailability.Ready -> availability
                }
            val timestampSelector =
                timeMs
                    .takeIf {
                        it > 0 && HistoryReferenceType.TIMESTAMP in ready.referenceTypes
                    }?.let(ChatHistorySelectors::timestamp)
            val msgidSelector =
                msgid
                    .takeIf {
                        it.isNotEmpty() && HistoryReferenceType.MSGID in ready.referenceTypes
                    }?.let(ChatHistorySelectors::msgid)
            val anchor =
                msgidSelector ?: timestampSelector ?: return PageResult.Failed(
                    IllegalStateException("CHATHISTORY AROUND has no advertised anchor selector"),
                )
            val request =
                ChatHistoryRequest(
                    subcommand = ChatHistoryRequest.Subcommand.AROUND,
                    target = target,
                    bound1 = anchor,
                    limit = minOf(limit, ready.pageLimit).coerceAtLeast(1),
                )
            diagnostics.record("chat_history", "loader_page_requested") {
                mapOf(
                    "network_id" to networkId,
                    "room_id" to roomId,
                    "direction" to Direction.AROUND.name,
                    "availability" to availability::class.simpleName,
                    "boundary_has_msgid" to (msgidSelector != null),
                    "gap_id" to null,
                )
            }
            return coalesced(
                FlightKey(networkId, roomId, Direction.AROUND, gapId = null, anchor = anchor),
            ) {
                // Only the wire round trip is inside the permit and the budget, exactly as the
                // directional loads keep it: persistence is Room work behind the sole IRC→Room writer
                // and must neither hold the wire nor spend the request's timeout.
                val (issued, response) =
                    try {
                        onWireLock(
                            networkId,
                            wireWidth(ready.supportsConcurrentRequests),
                            requestTimeoutMs,
                            retryableTimeout = false,
                        ) {
                            runAround(source, request, msgidSelector, timestampSelector)
                        }
                    } catch (_: TimeoutCancellationException) {
                        return@coalesced PageResult.Unavailable(
                            IrcDisconnectedException("CHATHISTORY", "request timed out"),
                        )
                    }
                val page =
                    (response as? ChatHistoryResponse.Messages)
                        ?.boundedToRequest(issued, preferredAroundMsgid = msgid)
                        ?: return@coalesced PageResult.Failed(
                            IllegalStateException("CHATHISTORY AROUND returned a TARGETS response"),
                        )
                val persisted =
                    persistHistoryPageResult(
                        networkId,
                        issued,
                        page,
                        expectedRoomId = roomId,
                    )
                // AROUND is not a direction that can be exhausted: it names one interval around one
                // message, and the ladder above and below it stays pageable.
                PageResult.Loaded(page.primaryMessageCount, persisted.inserted, endOfDirection = false)
            }.also { result ->
                diagnostics.record("chat_history", "loader_page_result") {
                    mapOf(
                        "room_id" to roomId,
                        "direction" to Direction.AROUND.name,
                        "result" to result::class.simpleName,
                        "primary_count" to (result as? PageResult.Loaded)?.primaryCount,
                        "inserted_count" to (result as? PageResult.Loaded)?.insertedCount,
                    )
                }
            }
        }

        /**
         * Run the AROUND request, applying the msgid→timestamp fallback the server can force at
         * runtime, and report which request actually produced the response so the page is persisted
         * against its exact selector. A rejection with no advertised timestamp fallback rethrows the
         * server's own error, exactly as [fetchPage] does for the directional selectors.
         */
        private suspend fun runAround(
            source: HistorySource,
            request: ChatHistoryRequest,
            msgidSelector: String?,
            timestampSelector: String?,
        ): Pair<ChatHistoryRequest, ChatHistoryResponse> =
            try {
                request to source.chathistory(request)
            } catch (error: IrcCommandException) {
                if (
                    msgidSelector == null || request.bound1 != msgidSelector ||
                    error.code != INVALID_MSGREFTYPE || timestampSelector == null
                ) {
                    throw error
                }
                val fallback = request.copy(bound1 = timestampSelector)
                fallback to source.chathistory(fallback)
            }

        /**
         * Pull the most recent page for [roomId], persist it through the sole IRC→Room writer, and hand
         * back the page itself — coalesced, so every LATEST caller for one room shares one fetch.
         *
         * This is the shared primitive behind BOTH newest-page callers: Paging's empty-store seed and
         * the catch-up coordinator's per-target seed. They ask the same question, and before they shared
         * a flight, opening a chat while its network was catching up put two identical CHATHISTORY
         * LATEST requests on the wire — the second one guaranteed to insert nothing.
         *
         * The page is returned raw rather than pre-classified because the two callers draw different
         * conclusions from it: Paging only needs "is this direction finished", while the coordinator
         * reads the boundary, the saturation ambiguity, and the high-water mark out of the same
         * response. Persistence is done once, by the leader, inside the flight.
         *
         * The timeout is classified by the caller for the same reason, through
         * [LatestFlightTimeoutException]: the flight has one outcome, and which caller happens to lead
         * it must not decide what the others see.
         */
        internal suspend fun fetchLatest(
            networkId: Long,
            roomId: RoomId,
            target: String,
            source: HistorySource,
            requestLimit: Int,
            referenceTypes: Set<HistoryReferenceType>,
            timeoutMs: Long,
            allowConcurrent: Boolean = false,
        ): LatestPage =
            coalesced(FlightKey(networkId, roomId, Direction.LATEST, gapId = null)) {
                val allowMsgid = HistoryReferenceType.MSGID in referenceTypes
                val request =
                    ChatHistoryRequest(
                        ChatHistoryRequest.Subcommand.LATEST,
                        target,
                        limit = requestLimit,
                    )
                val result =
                    try {
                        fetchMessages(
                            networkId,
                            source,
                            request,
                            referenceTypes,
                            allowMsgid,
                            timeoutMs,
                            retryableTimeout = false,
                            allowConcurrent = allowConcurrent,
                        )
                    } catch (_: TimeoutCancellationException) {
                        // Shared with every joiner as one typed, non-cancellation outcome; see
                        // [LatestFlightTimeoutException].
                        throw LatestFlightTimeoutException()
                    }
                val persisted =
                    persistHistoryPageResult(
                        networkId,
                        request,
                        result,
                        expectedRoomId = roomId,
                    )
                LatestPage(request, result, persisted.inserted, referenceTypes)
            }

        /**
         * Paging's reading of a newest page: is this direction finished, and did anything land.
         *
         * A page whose oldest row carries no advertised selector is reported as a failure because it is
         * no use as a durable boundary — but its rows are already persisted by then, and deliberately:
         * they are real messages, the flight is shared with a caller that has always kept them, and a
         * page cannot be persisted for one joiner and discarded for another.
         */
        private fun LatestPage.toPageResult(): PageResult {
            if (!response.isComplete && !response.hasUsableOldest(referenceTypes, true)) {
                return PageResult.Failed(
                    IllegalStateException("CHATHISTORY LATEST returned no advertised primary-message boundary"),
                )
            }
            return PageResult.Loaded(
                response.primaryMessageCount,
                inserted,
                endOfDirection =
                    response.isComplete ||
                        response.cannotSafelyPageBefore(referenceTypes, true, request.limit),
            )
        }

        /** Page older via BEFORE from [boundary], persisting into the optional focused [gapId]. */
        private suspend fun loadOlder(
            networkId: Long,
            roomId: RoomId,
            target: String,
            source: HistorySource,
            requestLimit: Int,
            referenceTypes: Set<HistoryReferenceType>,
            gapId: Long?,
            boundary: ChatHistoryReference?,
            allowConcurrent: Boolean,
        ): PageResult {
            val oldest =
                boundary ?: return PageResult.Failed(
                    IllegalStateException("CHATHISTORY BEFORE requires a local boundary"),
                )
            if (selectorOf(oldest, referenceTypes, msgidAllowed = true) == null) {
                return PageResult.Failed(
                    IllegalStateException("CHATHISTORY BEFORE has no advertised local boundary selector"),
                )
            }
            val fetched =
                fetchPage(
                    networkId,
                    target,
                    ChatHistoryRequest.Subcommand.BEFORE,
                    source,
                    oldest,
                    secondBoundary = null,
                    referenceTypes,
                    requestLimit,
                    msgidAllowed = true,
                    requestTimeoutMs,
                    retryableTimeout = true,
                    allowConcurrent = allowConcurrent,
                ) ?: return PageResult.Failed(
                    IllegalStateException("CHATHISTORY BEFORE has no advertised local boundary selector"),
                )
            val result = fetched.response
            if (!result.isComplete && !result.hasUsableOldest(referenceTypes, fetched.msgidAllowed)) {
                return PageResult.Failed(
                    IllegalStateException("CHATHISTORY BEFORE returned no advertised primary-message boundary"),
                )
            }
            // Apply the page as one IRC history batch. EventProcessor wraps HistoryBatch in a single
            // Room transaction, so Paging sees one invalidation instead of up to 50 row-by-row refreshes
            // while the user is entering or flinging through a channel.
            val persisted =
                persistHistoryPageResult(
                    networkId,
                    fetched.request,
                    result,
                    expectedRoomId = roomId,
                    historyGapId = gapId,
                )
            if (result.isComplete) {
                return PageResult.Loaded(result.primaryMessageCount, persisted.inserted, endOfDirection = true)
            }
            // A non-advancing cursor would refetch forever. A saturated timestamp-only page is also
            // ambiguous because BEFORE would skip any additional messages sharing its oldest timestamp.
            // Preserve the page, leave historyComplete false, and stop this direction.
            return PageResult.Loaded(
                result.primaryMessageCount,
                persisted.inserted,
                endOfDirection =
                    result.cannotSafelyPageBefore(
                        referenceTypes,
                        fetched.msgidAllowed,
                        requestLimit,
                        previous = fetched.selector,
                    ),
            )
        }

        /** Grow toward the recent window via AFTER from [boundary], persisting into focused [gapId]. */
        private suspend fun loadNewer(
            networkId: Long,
            roomId: RoomId,
            target: String,
            source: HistorySource,
            requestLimit: Int,
            referenceTypes: Set<HistoryReferenceType>,
            gapId: Long?,
            boundary: ChatHistoryReference?,
            allowConcurrent: Boolean,
        ): PageResult {
            val newer =
                boundary ?: return PageResult.Failed(
                    IllegalStateException("CHATHISTORY AFTER requires a local boundary"),
                )
            if (selectorOf(newer, referenceTypes, msgidAllowed = true) == null) {
                return PageResult.Failed(
                    IllegalStateException("CHATHISTORY AFTER has no advertised local boundary selector"),
                )
            }
            val fetched =
                fetchPage(
                    networkId,
                    target,
                    ChatHistoryRequest.Subcommand.AFTER,
                    source,
                    newer,
                    secondBoundary = null,
                    referenceTypes,
                    requestLimit,
                    msgidAllowed = true,
                    requestTimeoutMs,
                    retryableTimeout = true,
                    allowConcurrent = allowConcurrent,
                ) ?: return PageResult.Failed(
                    IllegalStateException("CHATHISTORY AFTER has no advertised local boundary selector"),
                )
            val result = fetched.response
            if (!result.isComplete && !result.hasUsableNewest(referenceTypes, fetched.msgidAllowed)) {
                return PageResult.Failed(
                    IllegalStateException("CHATHISTORY AFTER returned no advertised primary-message boundary"),
                )
            }
            val persisted =
                persistHistoryPageResult(
                    networkId,
                    fetched.request,
                    result,
                    expectedRoomId = roomId,
                    historyGapId = gapId,
                )
            return PageResult.Loaded(
                result.primaryMessageCount,
                persisted.inserted,
                endOfDirection =
                    result.isComplete ||
                        result.cannotSafelyPageAfter(
                            referenceTypes,
                            fetched.msgidAllowed,
                            requestLimit,
                            previous = fetched.selector,
                        ),
            )
        }

        /**
         * Build and run one directional CHATHISTORY message request from [boundary] (and optional
         * [secondBoundary] for BETWEEN or a bounded LATEST floor), serialized on the per-network wire
         * lock. [timeoutMs] bounds the whole operation — lock wait included — so a caller's budget
         * (e.g. the urgent pending-message path) cannot silently stretch behind a busy wire. Applies
         * the msgid→timestamp fallback on `INVALID_MSGREFTYPE` and trims boundaries the server never
         * advertised. Returns null when [boundary] (or a required [secondBoundary]) has no advertised
         * selector up front; a runtime msgid rejection with no advertised timestamp fallback instead
         * rethrows the server's original [IrcCommandException] so callers keep its diagnostics. Does
         * not persist; the caller owns persistence.
         */
        internal suspend fun fetchPage(
            networkId: Long,
            target: String,
            subcommand: ChatHistoryRequest.Subcommand,
            source: HistorySource,
            boundary: ChatHistoryReference,
            secondBoundary: ChatHistoryReference?,
            referenceTypes: Set<HistoryReferenceType>,
            limit: Int,
            msgidAllowed: Boolean,
            timeoutMs: Long,
            retryableTimeout: Boolean = false,
            allowConcurrent: Boolean = false,
        ): FetchedPage? {
            val selector = selectorOf(boundary, referenceTypes, msgidAllowed) ?: return null
            val secondSelector = secondBoundary?.let { selectorOf(it, referenceTypes, msgidAllowed = false)?.value }
            if (secondBoundary != null && secondSelector == null) return null
            val request =
                ChatHistoryRequest(
                    subcommand = subcommand,
                    target = target,
                    bound1 = selector.value,
                    bound2 = secondSelector,
                    limit = limit.coerceAtLeast(1),
                )
            return onWireLock(networkId, wireWidth(allowConcurrent), timeoutMs, retryableTimeout) {
                try {
                    FetchedPage(
                        runRequest(source, request).withAdvertisedBoundaries(referenceTypes, msgidAllowed),
                        request,
                        selector,
                        msgidAllowed,
                    )
                } catch (error: IrcCommandException) {
                    if (selector.type != HistoryReferenceType.MSGID || error.code != INVALID_MSGREFTYPE) {
                        throw error
                    }
                    // The pre-checks proved a msgid selector was advertised, yet the server rejected it
                    // at runtime and no timestamp fallback exists for this boundary. Surface the
                    // server's own error rather than a misleading "no selector" failure.
                    val timestamp =
                        selectorOf(boundary, referenceTypes, msgidAllowed = false)
                            ?: throw error
                    val fallbackRequest = request.copy(bound1 = timestamp.value)
                    FetchedPage(
                        runRequest(source, fallbackRequest).withAdvertisedBoundaries(referenceTypes, false),
                        fallbackRequest,
                        timestamp,
                        false,
                    )
                }
            }
        }

        /**
         * Run an arbitrary pre-built message request (an unbounded LATEST seed, for example) on the
         * per-network wire lock; [timeoutMs] bounds lock wait plus the request. The caller owns request
         * construction of subcommand/target/limit and owns persistence.
         */
        internal suspend fun fetchMessages(
            networkId: Long,
            source: HistorySource,
            request: ChatHistoryRequest,
            referenceTypes: Set<HistoryReferenceType>,
            msgidAllowed: Boolean,
            timeoutMs: Long,
            retryableTimeout: Boolean = false,
            allowConcurrent: Boolean = false,
        ): ChatHistoryResponse.Messages =
            onWireLock(networkId, wireWidth(allowConcurrent), timeoutMs, retryableTimeout) {
                runRequest(source, request).withAdvertisedBoundaries(referenceTypes, msgidAllowed)
            }

        /** Run one CHATHISTORY TARGETS discovery request on the per-network wire lock. */
        internal suspend fun fetchTargets(
            networkId: Long,
            source: HistorySource,
            request: ChatHistoryRequest,
            timeoutMs: Long,
            allowConcurrent: Boolean = false,
        ): ChatHistoryResponse.Targets =
            onWireLock(networkId, wireWidth(allowConcurrent), timeoutMs, retryableTimeout = false) {
                source.chathistory(request) as? ChatHistoryResponse.Targets
                    ?: error("CHATHISTORY TARGETS returned a message response")
            }

        /** The advertised selector for [reference], or null when nothing usable was advertised. */
        internal fun selectorOf(
            reference: ChatHistoryReference,
            referenceTypes: Set<HistoryReferenceType>,
            msgidAllowed: Boolean,
        ): BoundarySelector? = reference.selector(referenceTypes, msgidAllowed)

        /**
         * Coalesce concurrent identical fetches. The leader runs [block] in its caller's coroutine, so
         * cancelling the leader (e.g. a Pager generation replaced mid-APPEND by a bounds change) fails
         * its own flight — but must not poison joined followers from live generations. A follower whose
         * own context is still active treats the leader's [CancellationException] as "flight abandoned"
         * and retries: it becomes the next leader or joins a newer flight. A follower that is itself
         * cancelled rethrows its own cancellation; non-cancellation failures are shared by all awaiters.
         */
        @Suppress("UNCHECKED_CAST")
        private suspend fun <T : Any> coalesced(
            key: FlightKey,
            block: suspend () -> T,
        ): T {
            while (true) {
                val existing = inFlight[key]
                if (existing != null) {
                    try {
                        return existing.await() as T
                    } catch (cancelled: CancellationException) {
                        // Distinguish "the leader was cancelled" from "this follower was cancelled":
                        // only a still-active follower may retry.
                        currentCoroutineContext().ensureActive()
                        // Let the cancelled leader finish unwinding (it removes the failed flight in
                        // its finally) before re-inspecting the map, so the retry cannot busy-spin on
                        // the same dead deferred.
                        yield()
                        continue
                    }
                }
                val deferred = CompletableDeferred<Any>()
                if (inFlight.putIfAbsent(key, deferred) != null) continue
                try {
                    val result = block()
                    deferred.complete(result)
                    return result
                } catch (error: Throwable) {
                    deferred.completeExceptionally(error)
                    throw error
                } finally {
                    inFlight.remove(key, deferred)
                }
            }
        }

        /**
         * Acquire a permit on the per-network wire gate and run [block] with [timeoutMs] bounding the
         * WHOLE operation: permit wait plus the request(s). A timeout fired while still queued behind
         * other fetches cancels the pending acquisition cleanly, so a caller's budget is honored even
         * on a busy wire. [wireWidth] follows the connection's labeled-response support; a width change
         * across reconnects on the same networkId swaps the gate on the next fetch (one in-flight
         * request from the doomed connection may briefly overlap — harmless, because labels correlate
         * responses and EventProcessor serializes persists).
         */
        private suspend fun <T> onWireLock(
            networkId: Long,
            wireWidth: Int,
            timeoutMs: Long,
            retryableTimeout: Boolean,
            block: suspend () -> T,
        ): T {
            // withTimeout crosses a coroutine boundary, so coroutine stacktrace recovery would hand back
            // a copy of whatever the block raised. RemoteMediator must let the original
            // CancellationException instance reach Paging untouched, so capture and rethrow the exact
            // throwable the block produced.
            val gate =
                networkGates.compute(networkId) { _, existing ->
                    if (existing?.width == wireWidth) existing else WireGate(wireWidth, Semaphore(wireWidth))
                }!!
            var raised: Throwable? = null
            return try {
                withTimeout(timeoutMs) {
                    try {
                        gate.semaphore.withPermit { block() }
                    } catch (error: Throwable) {
                        raised = error
                        throw error
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                diagnostics.record("chat_history", "loader_wire_timeout") {
                    mapOf("network_id" to networkId, "timeout_ms" to timeoutMs, "retryable" to retryableTimeout)
                }
                // For Paging (retryableTimeout), never let the timeout escape as a CancellationException:
                // the mediator rethrows those to Paging, whose accessor would keep this direction's
                // LoadState stuck at Loading with a stale pending request. Surface it as a retryable
                // transport failure instead. The coordinator's traversals want the original timeout so
                // their own TimeoutCancellationException handlers can report a friendly result.
                if (retryableTimeout) throw IrcDisconnectedException("CHATHISTORY", "request timed out")
                throw timeout
            } catch (error: Throwable) {
                throw raised ?: error
            }
        }

        private suspend fun runRequest(
            source: HistorySource,
            request: ChatHistoryRequest,
        ): ChatHistoryResponse.Messages =
            (source.chathistory(request) as? ChatHistoryResponse.Messages)
                ?.boundedToRequest(request)
                ?: error("CHATHISTORY ${request.subcommand} returned a TARGETS response")

        /** Keep stored cursors constrained to selectors the server actually advertised. */
        private fun ChatHistoryResponse.Messages.withAdvertisedBoundaries(
            referenceTypes: Set<HistoryReferenceType>,
            allowMsgid: Boolean,
        ): ChatHistoryResponse.Messages {
            if (allowMsgid && HistoryReferenceType.MSGID in referenceTypes) return this
            return copy(
                oldest = oldest?.copy(msgid = null),
                newest = newest?.copy(msgid = null),
            )
        }

        private val ChatHistoryResponse.Messages.isComplete: Boolean
            get() = endOfHistory || primaryMessageCount == 0

        private fun ChatHistoryResponse.Messages.hasUsableOldest(
            referenceTypes: Set<HistoryReferenceType>,
            allowMsgid: Boolean,
        ): Boolean = oldest?.selector(referenceTypes, allowMsgid) != null

        private fun ChatHistoryResponse.Messages.hasUsableNewest(
            referenceTypes: Set<HistoryReferenceType>,
            allowMsgid: Boolean,
        ): Boolean = newest?.selector(referenceTypes, allowMsgid) != null

        private fun ChatHistoryResponse.Messages.cannotSafelyPageBefore(
            referenceTypes: Set<HistoryReferenceType>,
            allowMsgid: Boolean,
            requestLimit: Int,
            previous: BoundarySelector? = null,
        ): Boolean {
            if (isComplete) return false
            val next = oldest?.selector(referenceTypes, allowMsgid) ?: return true
            return next.value == previous?.value ||
                (next.type == HistoryReferenceType.TIMESTAMP && primaryMessageCount >= requestLimit)
        }

        private fun ChatHistoryResponse.Messages.cannotSafelyPageAfter(
            referenceTypes: Set<HistoryReferenceType>,
            allowMsgid: Boolean,
            requestLimit: Int,
            previous: BoundarySelector,
        ): Boolean {
            if (isComplete) return false
            val next = newest?.selector(referenceTypes, allowMsgid) ?: return true
            return next.value == previous.value ||
                (next.type == HistoryReferenceType.TIMESTAMP && primaryMessageCount >= requestLimit)
        }

        private fun ChatHistoryReference.selector(
            referenceTypes: Set<HistoryReferenceType>,
            allowMsgid: Boolean,
        ): BoundarySelector? {
            val exactMsgid = msgid
            val exactServerTime = serverTime
            return when {
                allowMsgid && HistoryReferenceType.MSGID in referenceTypes && !exactMsgid.isNullOrEmpty() -> {
                    BoundarySelector(ChatHistorySelectors.msgid(exactMsgid), HistoryReferenceType.MSGID)
                }

                HistoryReferenceType.TIMESTAMP in referenceTypes && exactServerTime != null -> {
                    BoundarySelector(ChatHistorySelectors.timestamp(exactServerTime), HistoryReferenceType.TIMESTAMP)
                }

                else -> {
                    null
                }
            }
        }

        private fun wireWidth(allowConcurrent: Boolean): Int = if (allowConcurrent) MAX_CONCURRENT_WIRE_REQUESTS else 1

        internal companion object {
            /**
             * Bounded CHATHISTORY fan-out when labeled-response correlates concurrent requests; width 1
             * (strict serialization) otherwise. Shared with the coordinator's per-pass fan-out so the
             * two bounds cannot drift: this gate is the hard ceiling for a connection, and the pass's
             * adaptive width moves underneath it. Fixed per connection on purpose — a gate whose width
             * changed with the pass would rebuild the semaphore mid-flight.
             */
            internal const val MAX_CONCURRENT_WIRE_REQUESTS = 6

            /** IRCv3 error code for "this msgid selector type is not accepted"; shared with callers. */
            internal const val INVALID_MSGREFTYPE = "INVALID_MSGREFTYPE"

            /**
             * IRCv3 error code for "this target has no retrievable history". It is scoped to the one
             * refused target and is permanent for the connection, so callers must not retry it or let
             * it fail a whole-network pass. Services such as ChanServ/NickServ commonly answer this.
             */
            internal const val INVALID_TARGET = "INVALID_TARGET"

            private const val REQUEST_TIMEOUT_MS = 35_000L
        }
    }

/**
 * The loader's transport seam over one live connection.
 *
 * Every caller that already holds an [IrcClient] and wants a loader fetch needs exactly this pair of
 * calls; keeping the adapter in one place is what stops a caller from reaching past the loader and
 * issuing its own request, which is how the deep-link AROUND prefetch ended up outside the wire gate
 * in the first place.
 */
internal fun IrcClient.historySource(): HistoryPageLoader.HistorySource =
    object : HistoryPageLoader.HistorySource {
        override suspend fun availability(): HistoryAvailability = historyAvailability

        override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse = this@historySource.chathistory(req)
    }
