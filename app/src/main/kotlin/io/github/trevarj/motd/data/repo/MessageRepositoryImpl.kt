package io.github.trevarj.motd.data.repo

import androidx.paging.ExperimentalPagingApi
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import io.github.trevarj.motd.data.db.MessageDao
import io.github.trevarj.motd.data.db.MessageEntity
import io.github.trevarj.motd.data.db.ReactionDao
import io.github.trevarj.motd.data.db.ReactionEntity
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

// Paging 3 stream backed by the local pagingSource, with a RemoteMediator supplied per buffer
// by the injected factory (WP1 no-op / WP5 CHATHISTORY-backed). PagingConfig is the frozen
// contract: pageSize 50, prefetch 25, no placeholders.
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val reactionDao: ReactionDao,
    private val mediatorFactory: ChatHistoryMediatorFactory,
) : MessageRepository {
    @OptIn(ExperimentalPagingApi::class)
    override fun messages(bufferId: Long): Flow<PagingData<MessageEntity>> =
        Pager(
            config = PagingConfig(
                pageSize = 50,
                prefetchDistance = 25,
                enablePlaceholders = false,
            ),
            remoteMediator = mediatorFactory.create(bufferId),
            pagingSourceFactory = { messageDao.pagingSource(bufferId) },
        ).flow

    // Kept for the frozen contract; scopes to a small, fixed msgid set (safe under 999 vars).
    override fun reactions(bufferId: Long, msgids: List<String>): Flow<List<ReactionEntity>> =
        reactionDao.observeFor(bufferId, msgids)

    // Buffer-scoped observe: one stable query regardless of how far the user scrolls back, so the
    // per-msgid IN(...) list can never exceed SQLite's ~999 bind-variable cap (plans/15 #5). The
    // screen aggregates only the visible msgids from this stream.
    override fun reactionsForBuffer(bufferId: Long): Flow<List<ReactionEntity>> =
        reactionDao.observeForBuffer(bufferId)

    override suspend fun byMsgid(bufferId: Long, msgid: String): MessageEntity? =
        messageDao.byMsgid(bufferId, msgid)

    override suspend fun countNewerThan(bufferId: Long, serverTime: Long, id: Long): Int =
        messageDao.countNewerThan(bufferId, serverTime, id)

    override suspend fun firstUnreadOtherTime(bufferId: Long, after: Long): Long? =
        messageDao.firstUnreadOtherTime(bufferId, after)

    override suspend fun deleteMessage(id: Long) = messageDao.deleteById(id)
}
