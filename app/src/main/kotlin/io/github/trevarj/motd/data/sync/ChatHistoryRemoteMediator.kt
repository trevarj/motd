package io.github.trevarj.motd.data.sync

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.HistoryCursorDao
import io.github.trevarj.motd.data.db.ircTarget
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResponse
import io.github.trevarj.motd.irc.client.HistoryAvailability
import io.github.trevarj.motd.irc.client.HistoryReferenceType
import io.github.trevarj.motd.irc.client.IrcCommandException
import io.github.trevarj.motd.irc.client.IrcDisconnectedException
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared network gate for reconnect discovery and Paging history requests. */
object CanonicalHistorySingleFlight {
    private val networkLocks = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withNetwork(networkId: Long, block: suspend () -> T): T =
        networkLocks.getOrPut(networkId, ::Mutex).withLock { block() }
}

/**
 * CHATHISTORY-backed infinite scroll (plans/04 algorithm). The list is DESC (newest first), so
 * APPEND fetches OLDER messages via CHATHISTORY BEFORE; PREPEND has nothing to fetch live-side.
 *
 * REFRESH → if the buffer is empty and the network advertises chathistory, pull LATEST once.
 * APPEND  → older boundary; stop when historyComplete/no cap; when the buffer is empty (no oldest
 *           boundary yet) pull LATEST once to backfill on first open; otherwise BEFORE the oldest
 *           protocol page boundary. Completed empty pages and explicit end markers persist the
 *           confirmed start-of-history state through EventProcessor.
 *
 * We use SKIP_INITIAL_REFRESH so the cached DB paints instantly, which means Paging never calls
 * load(REFRESH) on open. On an empty store the local PagingSource yields an empty page and Paging
 * drives an APPEND past the end boundary — so the empty-buffer LATEST backfill lives in APPEND, not
 * only REFRESH, or a freshly-connected/cleared buffer would never fetch its recent history.
 */
@OptIn(ExperimentalPagingApi::class)
class ChatHistoryRemoteMediator(
    private val bufferId: Long,
    private val bufferDao: BufferDao,
    private val messageDao: MessageDao,
    private val processor: EventProcessor,
    private val history: HistorySource,
    private val pageSize: Int = 50,
    private val historyCursorDao: HistoryCursorDao? = null,
) : RemoteMediator<Int, MessageEntity>() {

    /**
     * Minimal seam over the live [io.github.trevarj.motd.irc.client.IrcClient] (mirrors
     * reconnect coordinator's history-source seam) so the load logic is unit-testable
     * against scripted responses without a socket. Resolved per-load so a client that connects after
     * the buffer opens is picked up on the next boundary hit.
     */
    interface HistorySource {
        suspend fun availability(): HistoryAvailability
        suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse
    }

    override suspend fun initialize(): InitializeAction =
        // Local cache is authoritative for the initial paint; only fetch on explicit boundary hit.
        InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MessageEntity>): MediatorResult {
        return locks.getOrPut(bufferId, ::Mutex).withLock {
            try {
                val buffer = bufferDao.observeById(bufferId)
                    ?: return MediatorResult.Success(endOfPaginationReached = true)
                val networkId = buffer.networkId
                val availability = history.availability()
                if (availability !is HistoryAvailability.Ready) {
                    return when (availability) {
                        HistoryAvailability.Unsupported -> MediatorResult.Success(endOfPaginationReached = true)
                        HistoryAvailability.NegotiatingOrOffline -> MediatorResult.Error(
                            IrcDisconnectedException("CHATHISTORY", "history is negotiating or offline"),
                        )
                        is HistoryAvailability.Ready -> error("unreachable")
                    }
                }
                val requestLimit = minOf(pageSize, availability.pageLimit).coerceAtLeast(1)
                CanonicalHistorySingleFlight.withNetwork(networkId) {
                    when (loadType) {
                        LoadType.REFRESH -> refresh(
                            networkId,
                            buffer.id,
                            buffer.ircTarget,
                            requestLimit,
                            availability.referenceTypes,
                        )
                        LoadType.PREPEND -> MediatorResult.Success(endOfPaginationReached = true)
                        LoadType.APPEND -> append(
                            networkId,
                            buffer.id,
                            buffer.ircTarget,
                            buffer.historyComplete,
                            requestLimit,
                            availability.referenceTypes,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                MediatorResult.Error(e)
            }
        }
    }

    private suspend fun refresh(
        networkId: Long,
        roomId: Long,
        target: String,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
    ): MediatorResult {
        val newest = messageDao.newestTime(roomId)
        if (newest != null) {
            // Already have local history; the local PagingSource paints it. APPEND drives older.
            return MediatorResult.Success(endOfPaginationReached = false)
        }
        val response = fetchLatest(networkId, target, requestLimit, referenceTypes)
        return MediatorResult.Success(endOfPaginationReached = response.isComplete)
    }

    private suspend fun append(
        networkId: Long,
        roomId: Long,
        target: String,
        historyComplete: Boolean,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
    ): MediatorResult {
        if (historyComplete) return MediatorResult.Success(endOfPaginationReached = true)
        val cursor = historyCursorDao?.byRoom(roomId)
        val oldest = cursor?.let { ChatHistoryReference(it.oldestMsgid, it.oldestServerTime) }
            ?.takeIf { it.msgid != null || it.serverTime != null }
            ?: messageDao.oldestBoundary(roomId)?.let {
                ChatHistoryReference(it.msgid, it.serverTime)
            }
        if (oldest == null) {
            // Empty local store hit the end boundary on first open. With SKIP_INITIAL_REFRESH the
            // REFRESH backfill never fires, so seed the newest page here via LATEST. If the server
            // has history the inserted rows re-run the PagingSource; a later APPEND then pages older.
            val response = fetchLatest(networkId, target, requestLimit, referenceTypes)
            return MediatorResult.Success(endOfPaginationReached = response.isComplete)
        }
        val selected = oldest.selector(referenceTypes, allowMsgid = true)
            ?: return MediatorResult.Error(
                IllegalStateException("CHATHISTORY BEFORE has no advertised local boundary selector"),
            )
        var request = ChatHistoryRequest(
            ChatHistoryRequest.Subcommand.BEFORE,
            target,
            bound1 = selected.value,
            limit = requestLimit,
        )
        var responseMsgidAllowed = selected.type == HistoryReferenceType.MSGID
        val result = try {
            messages(request)
        } catch (error: IrcCommandException) {
            if (selected.type != HistoryReferenceType.MSGID || error.code != INVALID_MSGREFTYPE) {
                throw error
            }
            val timestamp = oldest.selector(referenceTypes, allowMsgid = false) ?: throw error
            request = request.copy(bound1 = timestamp.value)
            responseMsgidAllowed = false
            messages(request)
        }
        if (
            !result.isComplete &&
            !result.hasUsableOldest(referenceTypes, responseMsgidAllowed)
        ) {
            return MediatorResult.Error(
                IllegalStateException("CHATHISTORY BEFORE returned no advertised primary-message boundary"),
            )
        }
        // Apply the page as one IRC history batch. EventProcessor wraps HistoryBatch in a single
        // Room transaction, so Paging sees one invalidation instead of up to 50 row-by-row refreshes
        // while the user is entering or flinging through a channel.
        processor.persistHistoryPage(networkId, request, result)
        if (result.isComplete) return MediatorResult.Success(endOfPaginationReached = true)
        return MediatorResult.Success(endOfPaginationReached = false)
    }

    /** Pull the most recent page for [target] and persist it through the sole IRC→Room writer. */
    private suspend fun fetchLatest(
        networkId: Long,
        target: String,
        requestLimit: Int,
        referenceTypes: Set<HistoryReferenceType>,
    ): ChatHistoryResponse.Messages {
        val request = ChatHistoryRequest(
            ChatHistoryRequest.Subcommand.LATEST,
            target,
            limit = requestLimit,
        )
        val result = messages(request)
        if (!result.isComplete && !result.hasUsableOldest(referenceTypes, true)) {
            error("CHATHISTORY LATEST returned no advertised primary-message boundary")
        }
        processor.persistHistoryPage(networkId, request, result)
        return result
    }

    private suspend fun messages(request: ChatHistoryRequest): ChatHistoryResponse.Messages =
        history.chathistory(request) as? ChatHistoryResponse.Messages
            ?: error("CHATHISTORY ${request.subcommand} returned a TARGETS response")

    private val ChatHistoryResponse.Messages.isComplete: Boolean
        get() = endOfHistory || primaryMessageCount == 0

    private fun ChatHistoryResponse.Messages.hasUsableOldest(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
    ): Boolean = oldest?.selector(referenceTypes, allowMsgid) != null

    private fun ChatHistoryReference.selector(
        referenceTypes: Set<HistoryReferenceType>,
        allowMsgid: Boolean,
    ): BoundarySelector? = when {
        allowMsgid && HistoryReferenceType.MSGID in referenceTypes && !msgid.isNullOrEmpty() ->
            BoundarySelector(ChatHistorySelectors.msgid(msgid), HistoryReferenceType.MSGID)
        HistoryReferenceType.TIMESTAMP in referenceTypes && serverTime != null ->
            BoundarySelector(ChatHistorySelectors.timestamp(serverTime), HistoryReferenceType.TIMESTAMP)
        else -> null
    }

    private data class BoundarySelector(
        val value: String,
        val type: HistoryReferenceType,
    )

    companion object {
        private const val INVALID_MSGREFTYPE = "INVALID_MSGREFTYPE"
        private val locks = ConcurrentHashMap<Long, Mutex>()
    }
}

/**
 * Real mediator factory wired into [io.github.trevarj.motd.data.repo.MessageRepositoryImpl] via
 * the frozen [ChatHistoryMediatorFactory] contract; WP10 rebinds this over the WP1 no-op stub.
 */
@OptIn(ExperimentalPagingApi::class)
@Singleton
class ChatHistoryMediatorFactoryImpl @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val bufferDao: BufferDao,
    private val messageDao: MessageDao,
    private val processor: EventProcessor,
    private val historyCursorDao: HistoryCursorDao,
) : ChatHistoryMediatorFactory {
    override fun create(bufferId: Long): RemoteMediator<Int, MessageEntity> =
        ChatHistoryRemoteMediator(
            bufferId,
            bufferDao,
            messageDao,
            processor,
            historyFor(bufferId),
            historyCursorDao = historyCursorDao,
        )

    // Resolve the live client lazily per call: the buffer can open before its network reaches
    // Ready, and clientFor(...) is only stable once connected. Missing/negotiating clients remain
    // retryable rather than masquerading as unsupported or a completed empty history response.
    private fun historyFor(bufferId: Long): ChatHistoryRemoteMediator.HistorySource =
        object : ChatHistoryRemoteMediator.HistorySource {
            private suspend fun client() =
                bufferDao.observeById(bufferId)?.networkId?.let { connectionManager.clientFor(it) }

            override suspend fun availability(): HistoryAvailability =
                client()?.historyAvailability ?: HistoryAvailability.NegotiatingOrOffline

            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResponse =
                client()?.chathistory(req) ?: throw IrcDisconnectedException("CHATHISTORY", null)
        }
}
