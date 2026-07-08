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
import io.github.trevarj.motd.irc.client.IrcClient
import io.github.trevarj.motd.service.ConnectionManager
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CHATHISTORY-backed infinite scroll (plans/04 algorithm). The list is DESC (newest first), so
 * APPEND fetches OLDER messages via CHATHISTORY BEFORE; PREPEND has nothing to fetch live-side.
 *
 * REFRESH → if the buffer is empty and the network advertises chathistory, pull LATEST once.
 * APPEND  → older boundary; stop when historyComplete/no cap; otherwise BEFORE the oldest local
 *           serverTime, insert (IGNORE), set historyComplete when the server returns nothing,
 *           and advance oldestFetchedTime bookkeeping.
 */
@OptIn(ExperimentalPagingApi::class)
class ChatHistoryRemoteMediator(
    private val bufferId: Long,
    private val connectionManager: ConnectionManager,
    private val bufferDao: BufferDao,
    private val messageDao: MessageDao,
    private val processor: EventProcessor,
    private val pageSize: Int = 50,
) : RemoteMediator<Int, MessageEntity>() {

    override suspend fun initialize(): InitializeAction =
        // Local cache is authoritative for the initial paint; only fetch on explicit boundary hit.
        InitializeAction.SKIP_INITIAL_REFRESH

    override suspend fun load(loadType: LoadType, state: PagingState<Int, MessageEntity>): MediatorResult {
        return try {
            val buffer = bufferDao.observeById(bufferId)
                ?: return MediatorResult.Success(endOfPaginationReached = true)
            val networkId = buffer.networkId
            val client = connectionManager.clientFor(networkId)
                ?: return MediatorResult.Success(endOfPaginationReached = true)
            if (!client.hasCap(CHATHISTORY_CAP)) {
                return MediatorResult.Success(endOfPaginationReached = true)
            }
            when (loadType) {
                LoadType.REFRESH -> refresh(networkId, buffer.name, client)
                LoadType.PREPEND -> MediatorResult.Success(endOfPaginationReached = true)
                LoadType.APPEND -> append(networkId, buffer.name, buffer.historyComplete, client)
            }
        } catch (e: Throwable) {
            MediatorResult.Error(e)
        }
    }

    private suspend fun refresh(networkId: Long, target: String, client: IrcClient): MediatorResult {
        val newest = messageDao.newestTime(bufferId)
        if (newest != null) {
            // Already have local history; the local PagingSource paints it. APPEND drives older.
            return MediatorResult.Success(endOfPaginationReached = false)
        }
        val result = client.chathistory(ChatHistoryRequest(ChatHistoryRequest.Subcommand.LATEST, target, limit = pageSize))
        for (ev in result.events) processor.process(networkId, ev)
        return MediatorResult.Success(endOfPaginationReached = false)
    }

    private suspend fun append(networkId: Long, target: String, historyComplete: Boolean, client: IrcClient): MediatorResult {
        if (historyComplete) return MediatorResult.Success(endOfPaginationReached = true)
        val oldest = messageDao.oldestTime(bufferId)
            ?: return MediatorResult.Success(endOfPaginationReached = true)
        val bound = "timestamp=${Instant.ofEpochMilli(oldest)}"
        val result = client.chathistory(
            ChatHistoryRequest(ChatHistoryRequest.Subcommand.BEFORE, target, bound1 = bound, limit = pageSize),
        )
        for (ev in result.events) processor.process(networkId, ev)
        val buffer = bufferDao.observeById(bufferId)
        if (result.events.isEmpty()) {
            buffer?.let { bufferDao.update(it.copy(historyComplete = true)) }
            return MediatorResult.Success(endOfPaginationReached = true)
        }
        val newOldest = messageDao.oldestTime(bufferId)
        buffer?.let { bufferDao.update(it.copy(oldestFetchedTime = newOldest)) }
        return MediatorResult.Success(endOfPaginationReached = false)
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
        ChatHistoryRemoteMediator(bufferId, connectionManager, bufferDao, messageDao, processor)
}
