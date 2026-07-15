package io.github.trevarj.motd.data.sync

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import io.github.trevarj.motd.data.db.BufferDao
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.repo.ChatHistoryMediatorFactory
import io.github.trevarj.motd.irc.client.ChatHistoryRequest
import io.github.trevarj.motd.irc.client.ChatHistoryResult
import io.github.trevarj.motd.irc.event.IrcEvent
import io.github.trevarj.motd.irc.ext.ChatHistorySelectors
import io.github.trevarj.motd.service.ConnectionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CHATHISTORY-backed infinite scroll (plans/04 algorithm). The list is DESC (newest first), so
 * APPEND fetches OLDER messages via CHATHISTORY BEFORE; PREPEND has nothing to fetch live-side.
 *
 * REFRESH → if the buffer is empty and the network advertises chathistory, pull LATEST once.
 * APPEND  → older boundary; stop when historyComplete/no cap; when the buffer is empty (no oldest
 *           boundary yet) pull LATEST once to backfill on first open; otherwise BEFORE the oldest
 *           local serverTime, insert (IGNORE), set historyComplete when the server returns nothing,
 *           and advance oldestFetchedTime bookkeeping.
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
) : RemoteMediator<Int, MessageEntity>() {

    /**
     * Minimal seam over the live [io.github.trevarj.motd.irc.client.IrcClient] (mirrors
     * reconnect coordinator's history-source seam) so the load logic is unit-testable
     * against scripted responses without a socket. Resolved per-load so a client that connects after
     * the buffer opens is picked up on the next boundary hit.
     */
    interface HistorySource {
        suspend fun hasCap(cap: String): Boolean
        suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResult
    }

    override suspend fun initialize(): InitializeAction =
        // Local cache is authoritative for the initial paint; only fetch on explicit boundary hit.
        InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MessageEntity>): MediatorResult {
        return try {
            val buffer = bufferDao.observeById(bufferId)
                ?: return MediatorResult.Success(endOfPaginationReached = true)
            val networkId = buffer.networkId
            if (!history.hasCap(CHATHISTORY_CAP)) {
                return MediatorResult.Success(endOfPaginationReached = true)
            }
            when (loadType) {
                LoadType.REFRESH -> refresh(networkId, buffer.name)
                LoadType.PREPEND -> MediatorResult.Success(endOfPaginationReached = true)
                LoadType.APPEND -> append(networkId, buffer.name, buffer.historyComplete)
            }
        } catch (e: Throwable) {
            MediatorResult.Error(e)
        }
    }

    private suspend fun refresh(networkId: Long, target: String): MediatorResult {
        val newest = messageDao.newestTime(bufferId)
        if (newest != null) {
            // Already have local history; the local PagingSource paints it. APPEND drives older.
            return MediatorResult.Success(endOfPaginationReached = false)
        }
        fetchLatest(networkId, target)
        return MediatorResult.Success(endOfPaginationReached = false)
    }

    private suspend fun append(networkId: Long, target: String, historyComplete: Boolean): MediatorResult {
        if (historyComplete) return MediatorResult.Success(endOfPaginationReached = true)
        val oldest = messageDao.oldestTime(bufferId)
        if (oldest == null) {
            // Empty local store hit the end boundary on first open. With SKIP_INITIAL_REFRESH the
            // REFRESH backfill never fires, so seed the newest page here via LATEST. If the server
            // has history the inserted rows re-run the PagingSource; a later APPEND then pages older.
            fetchLatest(networkId, target)
            val seeded = messageDao.oldestTime(bufferId) != null
            return MediatorResult.Success(endOfPaginationReached = !seeded)
        }
        val bound = ChatHistorySelectors.timestamp(oldest)
        val result = history.chathistory(
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.BEFORE, target, bound1 = bound, limit = pageSize),
        )
        // Apply the page as one IRC history batch. EventProcessor wraps HistoryBatch in a single
        // Room transaction, so Paging sees one invalidation instead of up to 50 row-by-row refreshes
        // while the user is entering or flinging through a channel.
        if (result.events.isNotEmpty()) {
            processor.process(networkId, IrcEvent.HistoryBatch(target, result.events))
        }
        if (result.events.isEmpty()) {
            bufferDao.markHistoryComplete(bufferId)
            return MediatorResult.Success(endOfPaginationReached = true)
        }
        val newOldest = messageDao.oldestTime(bufferId)
        bufferDao.setOldestFetchedTime(bufferId, newOldest)
        return MediatorResult.Success(endOfPaginationReached = false)
    }

    /** Pull the most recent page for [target] and persist it through the sole IRC→Room writer. */
    private suspend fun fetchLatest(networkId: Long, target: String) {
        val result = history.chathistory(ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = pageSize))
        if (result.events.isNotEmpty()) {
            processor.process(networkId, IrcEvent.HistoryBatch(target, result.events))
        }
    }

    companion object {
        const val CHATHISTORY_CAP = "draft/chathistory"
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
) : ChatHistoryMediatorFactory {
    override fun create(bufferId: Long): RemoteMediator<Int, MessageEntity> =
        ChatHistoryRemoteMediator(bufferId, bufferDao, messageDao, processor, historyFor(bufferId))

    // Resolve the live client lazily per call: the buffer can open before its network reaches
    // Ready, and clientFor(...) is only stable once connected. A missing client presents as
    // "no chathistory cap" so the mediator falls back to plain local paging until it reconnects.
    private fun historyFor(bufferId: Long): ChatHistoryRemoteMediator.HistorySource =
        object : ChatHistoryRemoteMediator.HistorySource {
            private suspend fun client() =
                bufferDao.observeById(bufferId)?.networkId?.let { connectionManager.clientFor(it) }

            override suspend fun hasCap(cap: String): Boolean = client()?.hasCap(cap) ?: false

            override suspend fun chathistory(req: ChatHistoryRequest): ChatHistoryResult =
                client()?.chathistory(req) ?: ChatHistoryResult(events = emptyList(), targets = emptyList())
        }
}
